//! The compression primitives magiskboot v31.1 uses, exposed to JS through a tiny C-like ABI.
//!
//! Mirrors `native/src/boot/compress.rs` (get_encoder / get_encoder_bcj_detection / get_decoder)
//! and `native/src/boot/format.rs` (detect_bcj_filter, is_raw_arm32) exactly:
//!   gzip   : flate2 GzEncoder, Compression::best()          (zlib-rs backend)
//!   gunzip : flate2 MultiGzDecoder
//!   xz     : lzma-rust2 XzOptions::with_preset(6) + CRC32, BCJ pre-filter from detect_bcj_filter
//!   unxz   : lzma-rust2 XzReader::new(r, true)
//! plus raw inflate for reading the Magisk APK (zip).
//!
//! ABI: JS calls `bp_alloc(n)`, copies the input in, calls an op with (ptr, len[, arg]). Ops return
//! 0 on success and leave the output in a module-global buffer read via `bp_out_ptr`/`bp_out_len`;
//! JS copies it and calls `bp_out_free`. Inputs are freed with `bp_free(ptr, n)`.

use std::io::{Read, Write};

use flate2::Compression;
use flate2::read::{DeflateDecoder, MultiGzDecoder};
use flate2::write::GzEncoder;
use lzma_rust2::{CheckType, FilterType, XzOptions, XzReader, XzWriter};

static mut OUT: Vec<u8> = Vec::new();

fn set_out(v: Vec<u8>) {
    // SAFETY: wasm32-unknown-unknown is single-threaded and JS never re-enters an op.
    unsafe { OUT = v };
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_alloc(len: usize) -> *mut u8 {
    let mut v = Vec::<u8>::with_capacity(len.max(1));
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_free(ptr: *mut u8, len: usize) {
    unsafe { drop(Vec::from_raw_parts(ptr, 0, len.max(1))) };
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_ptr() -> *const u8 {
    unsafe { (*std::ptr::addr_of!(OUT)).as_ptr() }
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_len() -> usize {
    unsafe { (*std::ptr::addr_of!(OUT)).len() }
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_free() {
    set_out(Vec::new());
}

unsafe fn input<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if len == 0 { &[] } else { unsafe { std::slice::from_raw_parts(ptr, len) } }
}

fn finish(r: std::io::Result<Vec<u8>>) -> i32 {
    match r {
        Ok(v) => {
            set_out(v);
            0
        }
        Err(_) => {
            set_out(Vec::new());
            1
        }
    }
}

// format.rs: is_raw_arm32
fn is_raw_arm32(buf: &[u8]) -> bool {
    let sample = &buf[..buf.len().min(256)];
    if sample.len() < 64 {
        return false;
    }
    let words = sample.chunks_exact(4);
    let count = words.len();
    let arm_cond_count = words
        .filter(|chunk| {
            let top_nibble = chunk[3] >> 4;
            top_nibble == 0xE || top_nibble == 0xF
        })
        .count();
    arm_cond_count * 100 / count >= 75
}

// format.rs: detect_bcj_filter
fn detect_bcj_filter(buf: &[u8]) -> Option<FilterType> {
    if buf.len() >= 20 && buf.starts_with(b"\x7fELF") {
        let e_machine = match buf[5] {
            1 => u16::from_le_bytes([buf[18], buf[19]]),
            2 => u16::from_be_bytes([buf[18], buf[19]]),
            _ => return None,
        };
        return match e_machine {
            0x03 | 0x3E => Some(FilterType::BcjX86),
            0x28 => Some(FilterType::BcjArmThumb),
            0xB7 => Some(FilterType::BcjArm64),
            0xF3 => Some(FilterType::BcjRiscv),
            _ => None,
        };
    }
    if buf.len() >= 0x3C && &buf[0x38..0x3C] == b"ARM\x64" {
        return Some(FilterType::BcjArm64);
    }
    if buf.len() >= 0x34 && &buf[0x30..0x34] == b"RSC\x05" {
        return Some(FilterType::BcjRiscv);
    }
    if is_raw_arm32(buf) {
        return Some(FilterType::BcjArm);
    }
    None
}

/// Which BCJ filter magiskboot would pick for `sniff` (0 none, 1 x86, 2 ARM, 3 ARM-Thumb,
/// 4 ARM64, 5 RISC-V). For logging/tests only; `bp_xz` does its own detection.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_bcj(ptr: *const u8, len: usize) -> i32 {
    match detect_bcj_filter(unsafe { input(ptr, len) }) {
        None => 0,
        Some(FilterType::BcjX86) => 1,
        Some(FilterType::BcjArm) => 2,
        Some(FilterType::BcjArmThumb) => 3,
        Some(FilterType::BcjArm64) => 4,
        Some(FilterType::BcjRiscv) => 5,
        Some(_) => -1,
    }
}

/// gzip, Compression::best() (compress.rs get_encoder GZIP; used by `repack` for the ramdisk).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_gzip(ptr: *const u8, len: usize) -> i32 {
    let data = unsafe { input(ptr, len) };
    finish((|| {
        let mut e = GzEncoder::new(Vec::new(), Compression::best());
        e.write_all(data)?;
        e.finish()
    })())
}

/// MultiGzDecoder (compress.rs get_decoder GZIP; used by `unpack`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_gunzip(ptr: *const u8, len: usize) -> i32 {
    let data = unsafe { input(ptr, len) };
    finish((|| {
        let mut out = Vec::new();
        MultiGzDecoder::new(data).read_to_end(&mut out)?;
        Ok(out)
    })())
}

/// xz preset 6 + CRC32 + detected BCJ (compress.rs get_encoder_bcj_detection XZ).
/// `sniff_len` is how much of the input the BCJ detection sees: `compress=xz` (compress_cmd)
/// sniffs the first 4096 bytes, `CpioEntry::compress` (cpio backup) the whole buffer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_xz(ptr: *const u8, len: usize, sniff_len: usize) -> i32 {
    let data = unsafe { input(ptr, len) };
    let sniff = &data[..data.len().min(sniff_len)];
    finish((|| {
        let mut opt = XzOptions::with_preset(6);
        opt.set_check_sum_type(CheckType::Crc32);
        if let Some(filter) = detect_bcj_filter(sniff) {
            opt.prepend_pre_filter(filter, 0);
        }
        let mut e = XzWriter::new(Vec::new(), opt)?;
        e.write_all(data)?;
        e.finish()
    })())
}

/// XzReader::new(r, true) (compress.rs get_decoder XZ; used by cpio `restore`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_unxz(ptr: *const u8, len: usize) -> i32 {
    let data = unsafe { input(ptr, len) };
    finish((|| {
        let mut out = Vec::new();
        XzReader::new(data, true).read_to_end(&mut out)?;
        Ok(out)
    })())
}

/// Raw DEFLATE inflate (zip method 8), for reading the Magisk APK.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bp_inflate(ptr: *const u8, len: usize) -> i32 {
    let data = unsafe { input(ptr, len) };
    finish((|| {
        let mut out = Vec::new();
        DeflateDecoder::new(data).read_to_end(&mut out)?;
        Ok(out)
    })())
}
