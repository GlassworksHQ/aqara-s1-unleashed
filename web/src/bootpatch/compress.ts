// magiskboot v31.1's compressors, compiled to wasm (see wasm/src/lib.rs and wasm/build-wasm.sh).
//
// The boot patch must be byte-identical to magiskboot, and neither the browser's CompressionStream
// (zlib) nor liblzma produce the same bytes as the Rust crates magiskboot uses (flate2 + zlib-rs at
// level 9, lzma-rust2 xz preset 6 + CRC32 + BCJ). So we run those exact crates.

/** Compressors backed by the wasm module. All calls are synchronous once loaded. */
export interface Compressor {
  /** gzip, Compression::best() (magiskboot `repack` of a gzip ramdisk). */
  gzip(data: Uint8Array): Uint8Array;
  /** Multi-member gunzip (magiskboot `unpack`). Throws on corrupt input. */
  gunzip(data: Uint8Array): Uint8Array;
  /**
   * xz preset 6, CRC32 check, BCJ filter chosen by magiskboot's detect_bcj_filter over the first
   * `sniffLen` bytes. `compress=xz` sniffs 4096 bytes; cpio `backup` sniffs the whole entry.
   */
  xz(data: Uint8Array, sniffLen?: number): Uint8Array;
  /** xz decode (multi-stream), or null if the data is not valid xz (cpio `restore` semantics). */
  unxz(data: Uint8Array): Uint8Array | null;
  /** Raw DEFLATE inflate (zip method 8). Throws on corrupt input. */
  inflateRaw(data: Uint8Array): Uint8Array;
  /** BCJ filter magiskboot would pick: 0 none, 1 x86, 2 ARM, 3 ARM-Thumb, 4 ARM64, 5 RISC-V. */
  bcjFilter(data: Uint8Array): number;
}

interface Exports {
  memory: WebAssembly.Memory;
  bp_alloc(len: number): number;
  bp_free(ptr: number, len: number): void;
  bp_out_ptr(): number;
  bp_out_len(): number;
  bp_out_free(): void;
  bp_bcj(ptr: number, len: number): number;
  bp_gzip(ptr: number, len: number): number;
  bp_gunzip(ptr: number, len: number): number;
  bp_xz(ptr: number, len: number, sniff: number): number;
  bp_unxz(ptr: number, len: number): number;
  bp_inflate(ptr: number, len: number): number;
}

const WASM_URL = new URL('./wasm/bootpatch.wasm', import.meta.url);

async function fetchWasm(url: URL): Promise<ArrayBuffer | Uint8Array> {
  if (url.protocol === 'file:') {
    // Node (vitest): fetch() has no file: support. Hidden from the bundler on purpose.
    const fs = 'node:fs/promises';
    const { readFile } = (await import(/* @vite-ignore */ fs)) as typeof import('node:fs/promises');
    return readFile(url);
  }
  const res = await fetch(url);
  if (!res.ok) throw new Error(`bootpatch.wasm: HTTP ${res.status}`);
  return res.arrayBuffer();
}

function wrap(ex: Exports): Compressor {
  const call = (data: Uint8Array, op: (ptr: number, len: number) => number): Uint8Array | null => {
    const len = data.length;
    const ptr = ex.bp_alloc(len);
    new Uint8Array(ex.memory.buffer, ptr, len).set(data);
    let rc: number;
    try {
      rc = op(ptr, len);
    } finally {
      ex.bp_free(ptr, len);
    }
    if (rc !== 0) {
      ex.bp_out_free();
      return null;
    }
    // memory.buffer may have been replaced by growth during the op: re-read it.
    const out = new Uint8Array(ex.memory.buffer, ex.bp_out_ptr(), ex.bp_out_len()).slice();
    ex.bp_out_free();
    return out;
  };
  const must = (what: string, r: Uint8Array | null): Uint8Array => {
    if (!r) throw new Error(`${what} failed`);
    return r;
  };
  return {
    gzip: (d) => must('gzip', call(d, ex.bp_gzip)),
    gunzip: (d) => must('gunzip', call(d, ex.bp_gunzip)),
    xz: (d, sniffLen = Number.MAX_SAFE_INTEGER) =>
      must('xz', call(d, (p, l) => ex.bp_xz(p, l, Math.min(sniffLen, 0xffffffff) >>> 0))),
    unxz: (d) => call(d, ex.bp_unxz),
    inflateRaw: (d) => must('inflate', call(d, ex.bp_inflate)),
    bcjFilter: (d) => {
      const ptr = ex.bp_alloc(d.length);
      new Uint8Array(ex.memory.buffer, ptr, d.length).set(d);
      try {
        return ex.bp_bcj(ptr, d.length);
      } finally {
        ex.bp_free(ptr, d.length);
      }
    },
  };
}

let loading: Promise<Compressor> | undefined;

/**
 * Load (once) and return the compressor. Pass `wasm` to supply the module bytes or URL yourself;
 * otherwise it is fetched from next to this file (Vite emits it as an asset).
 */
export function loadCompressor(wasm?: BufferSource | URL | string): Promise<Compressor> {
  if (wasm === undefined && loading) return loading;
  const p = (async () => {
    const src =
      wasm === undefined ? await fetchWasm(WASM_URL)
      : typeof wasm === 'string' || wasm instanceof URL ? await fetchWasm(new URL(wasm, WASM_URL))
      : wasm;
    const { instance } = await WebAssembly.instantiate(src as BufferSource, {});
    return wrap(instance.exports as unknown as Exports);
  })();
  if (wasm === undefined) {
    loading = p;
    p.catch(() => { loading = undefined; });
  }
  return p;
}
