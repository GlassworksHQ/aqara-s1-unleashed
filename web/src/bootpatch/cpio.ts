// newc cpio, exactly as magiskboot v31.1 handles it (native/src/boot/cpio.rs).
//
// Entries live in a map sorted by name (Rust BTreeMap<String>, i.e. UTF-8 byte order); `dump`
// writes them in that order with inodes from 300000, mtime 0, nlink 1. This canonical form is why
// output is deterministic and independent of the order edits were made in.

import type { Compressor } from './compress';

export const S_IFMT = 0o170000;
export const S_IFDIR = 0o040000;
export const S_IFREG = 0o100000;
export const S_IFLNK = 0o120000;
export const S_IFBLK = 0o060000;
export const S_IFCHR = 0o020000;

export interface CpioEntry {
  mode: number;
  uid: number;
  gid: number;
  rdevmajor: number;
  rdevminor: number;
  data: Uint8Array;
}

const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: true });

/** Rust `str::cmp`: byte-wise over UTF-8. */
export function cmpName(a: string, b: string): number {
  if (a === b) return 0;
  const x = enc.encode(a), y = enc.encode(b);
  const n = Math.min(x.length, y.length);
  for (let i = 0; i < n; i++) if (x[i] !== y[i]) return x[i]! - y[i]!;
  return x.length - y.length;
}

/** cpio.rs norm_path: drop empty components ("/a//b/" -> "a/b"). */
export function normPath(p: string): string {
  return p.split('/').filter((x) => x !== '').join('/');
}

const align4 = (x: number) => (x + 3) & ~3;

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function findBytes(hay: Uint8Array, needle: Uint8Array, from = 0): number {
  outer: for (let i = from; i + needle.length <= hay.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer;
    return i;
  }
  return -1;
}

const MAGIC = enc.encode('070701');

function x8u(b: Uint8Array, off: number): number {
  let v = 0;
  for (let i = 0; i < 8; i++) {
    const d = parseInt(String.fromCharCode(b[off + i]!), 16);
    if (Number.isNaN(d)) throw new Error('bad cpio header');
    v = v * 16 + d;
  }
  return v >>> 0;
}

const hex8 = (v: number) => (v >>> 0).toString(16).padStart(8, '0');

export const CPIO_STOCK = 0;
export const CPIO_MAGISK = 1;
export const CPIO_UNSUPPORTED = 2;

// patch.rs: remove_pattern / match_patterns
type Matcher = (buf: Uint8Array, at: number) => number | null;

function matchPatterns(patterns: Uint8Array[]): Matcher {
  return (buf, at) => {
    let len = buf[at] === 0x2c /* ',' */ ? 1 : 0;
    const b = at + len;
    if (b >= buf.length) return null;
    let found = false;
    for (const p of patterns) {
      if (b + p.length <= buf.length && p.every((c, i) => buf[b + i] === c)) {
        len += p.length;
        found = true;
        break;
      }
    }
    if (!found) return null;
    const rest = at + len;
    if (rest < buf.length && buf[rest] === 0x3d /* '=' */) {
      for (let i = rest; i < buf.length; i++) {
        const c = buf[i]!;
        if (c === 0x20 || c === 0x0a || c === 0x2c || c === 0) break;
        len++;
      }
    }
    return len;
  };
}

function removePattern(buf: Uint8Array, m: Matcher, log?: (s: string) => void): number {
  let write = 0, read = 0, sz = buf.length;
  while (read < buf.length) {
    const len = m(buf, read);
    if (len !== null) {
      log?.(`Remove pattern [${new TextDecoder().decode(buf.subarray(read, read + len))}]`);
      sz -= len;
      read += len;
    } else {
      buf[write++] = buf[read++]!;
    }
  }
  buf.fill(0, write);
  return sz;
}

const VERITY = matchPatterns(['verifyatboot', 'verify', 'avb_keys', 'avb', 'support_scfs', 'fsverity'].map((s) => enc.encode(s)));
const ENCRYPT = matchPatterns(['forceencrypt', 'forcefdeorfbe', 'fileencryption'].map((s) => enc.encode(s)));

/** patch.rs patch_verity: strips verity flags in place, returns the new length. */
export function patchVerity(buf: Uint8Array, log?: (s: string) => void): number {
  return removePattern(buf, VERITY, log);
}

/** patch.rs patch_encryption: strips force-encryption flags in place, returns the new length. */
export function patchEncryption(buf: Uint8Array, log?: (s: string) => void): number {
  return removePattern(buf, ENCRYPT, log);
}

export class Cpio {
  entries = new Map<string, CpioEntry>();
  log: (s: string) => void;

  constructor(log?: (s: string) => void) {
    this.log = log ?? (() => {});
  }

  /** cpio.rs Cpio::load_from_data. */
  static load(data: Uint8Array, log?: (s: string) => void): Cpio {
    const c = new Cpio(log);
    let pos = 0;
    while (pos < data.length) {
      if (pos + 110 > data.length) throw new Error('truncated cpio header');
      if (!MAGIC.every((v, i) => data[pos + i] === v)) throw new Error('invalid cpio magic');
      const h = pos;
      pos += 110;
      const nameSz = x8u(data, h + 94);
      if (nameSz < 1 || pos + nameSz > data.length) throw new Error('bad cpio name size');
      if (data[pos + nameSz - 1] !== 0) throw new Error('cpio name not NUL-terminated');
      const name = dec.decode(data.subarray(pos, pos + nameSz - 1));
      pos = align4(pos + nameSz);
      if (name === '.' || name === '..') continue;
      if (name === 'TRAILER!!!') {
        const x = findBytes(data, MAGIC, pos);
        if (x < 0) break;
        pos = x;
        continue;
      }
      const fileSz = x8u(data, h + 54);
      if (pos + fileSz > data.length) throw new Error('truncated cpio data');
      c.entries.set(name, {
        mode: x8u(data, h + 14),
        uid: x8u(data, h + 22),
        gid: x8u(data, h + 30),
        rdevmajor: x8u(data, h + 78),
        rdevminor: x8u(data, h + 86),
        data: data.slice(pos, pos + fileSz),
      });
      pos = align4(pos + fileSz);
    }
    return c;
  }

  /** Entry names in dump order. */
  names(): string[] {
    return [...this.entries.keys()].sort(cmpName);
  }

  clone(): Cpio {
    const c = new Cpio(this.log);
    for (const [k, e] of this.entries) c.entries.set(k, { ...e, data: e.data.slice() });
    return c;
  }

  /** cpio.rs Cpio::dump. */
  dump(): Uint8Array {
    const names = this.names();
    let total = 0;
    const encNames = names.map((n) => enc.encode(n));
    names.forEach((n, i) => {
      total = align4(total + 110 + encNames[i]!.length + 1);
      total = align4(total + this.entries.get(n)!.data.length);
    });
    total = align4(total + 110 + 11);
    const out = new Uint8Array(total);
    let pos = 0;
    let ino = 300000;
    const hdr = (fields: number[]) => {
      out.set(MAGIC, pos);
      let s = '';
      for (const f of fields) s += hex8(f);
      out.set(enc.encode(s), pos + 6);
      pos += 110;
    };
    names.forEach((n, i) => {
      const e = this.entries.get(n)!;
      const nb = encNames[i]!;
      hdr([ino, e.mode, e.uid, e.gid, 1, 0, e.data.length, 0, 0, e.rdevmajor, e.rdevminor, nb.length + 1, 0]);
      out.set(nb, pos);
      pos = align4(pos + nb.length + 1);
      out.set(e.data, pos);
      pos = align4(pos + e.data.length);
      ino++;
    });
    hdr([ino, 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0, 11, 0]);
    out.set(enc.encode('TRAILER!!!\0'), pos);
    return out;
  }

  exists(path: string): boolean {
    return this.entries.has(normPath(path));
  }

  rm(path: string, recursive = false): void {
    const p = normPath(path);
    if (this.entries.delete(p)) this.log(`Removed entry [${p}]`);
    if (recursive) {
      const pre = p + '/';
      for (const k of this.names()) {
        if (k.startsWith(pre)) {
          this.entries.delete(k);
          this.log(`Removed entry [${k}]`);
        }
      }
    }
  }

  /** `add MODE ENTRY INFILE` for a regular file (mode | S_IFREG, uid/gid 0). */
  add(mode: number, path: string, data: Uint8Array): void {
    if (path.endsWith('/')) throw new Error('path cannot end with / for add');
    const m = (mode & 0o7777) | S_IFREG;
    this.entries.set(normPath(path), { mode: m, uid: 0, gid: 0, rdevmajor: 0, rdevminor: 0, data: data.slice() });
    this.log(`Add file [${path}] (${(m >>> 0).toString(8).padStart(4, '0')})`);
  }

  mkdir(mode: number, dir: string): void {
    this.entries.set(normPath(dir), { mode: (mode & 0o7777) | S_IFDIR, uid: 0, gid: 0, rdevmajor: 0, rdevminor: 0, data: new Uint8Array(0) });
    this.log(`Create directory [${dir}] (${(mode & 0o7777).toString(8).padStart(4, '0')})`);
  }

  ln(target: string, entry: string): void {
    this.entries.set(normPath(entry), { mode: S_IFLNK, uid: 0, gid: 0, rdevmajor: 0, rdevminor: 0, data: enc.encode(normPath(target)) });
    this.log(`Create symlink [${entry}] -> [${target}]`);
  }

  mv(from: string, to: string): void {
    const f = normPath(from);
    const e = this.entries.get(f);
    if (!e) throw new Error(`No such entry ${from}`);
    this.entries.delete(f);
    this.entries.set(normPath(to), e);
    this.log(`Move [${from}] -> [${to}]`);
  }

  extract(path: string): CpioEntry {
    const e = this.entries.get(normPath(path));
    if (!e) throw new Error(`No such file: ${path}`);
    return e;
  }

  /** cpio.rs Cpio::test: 0 stock, 1 Magisk, 2 unsupported. */
  test(): number {
    for (const f of ['sbin/launch_daemonsu.sh', 'sbin/su', 'init.xposed.rc', 'boot/sbin/launch_daemonsu.sh']) {
      if (this.exists(f)) return CPIO_UNSUPPORTED;
    }
    for (const f of ['.backup/.magisk', 'init.magisk.rc', 'overlay/init.magisk.rc']) {
      if (this.exists(f)) return CPIO_MAGISK;
    }
    return CPIO_STOCK;
  }

  /** cpio.rs Cpio::patch with KEEPVERITY / KEEPFORCEENCRYPT. A no-op when both are true. */
  patch(keepVerity: boolean, keepForceEncrypt: boolean): void {
    this.log(`Patch with flag KEEPVERITY=[${keepVerity}] KEEPFORCEENCRYPT=[${keepForceEncrypt}]`);
    for (const name of this.names()) {
      const e = this.entries.get(name)!;
      const fstab = (!keepVerity || !keepForceEncrypt)
        && (e.mode & S_IFMT) === S_IFREG
        && !name.startsWith('.backup')
        && !name.startsWith('twrp')
        && !name.startsWith('recovery')
        && name.startsWith('fstab');
      if (!keepVerity) {
        if (fstab) {
          this.log(`Found fstab file [${name}]`);
          const len = patchVerity(e.data, this.log);
          if (len !== e.data.length) e.data = e.data.slice(0, len);
        } else if (name === 'verity_key') {
          this.entries.delete(name);
          continue;
        }
      }
      if (!keepForceEncrypt && fstab) {
        const len = patchEncryption(e.data, this.log);
        if (len !== e.data.length) e.data = e.data.slice(0, len);
      }
    }
  }

  /**
   * cpio.rs Cpio::backup: diff `origin` against this cpio; originals that changed or disappeared go
   * to `.backup/<name>.xz` (regular files, xz with full-buffer BCJ sniffing), names that are new go
   * to `.backup/.rmlist` (NUL-separated).
   */
  backup(origin: Cpio, comp: Compressor, skipCompress = false): void {
    const backups = new Map<string, CpioEntry>();
    let rmList = '';
    backups.set('.backup', { mode: S_IFDIR, uid: 0, gid: 0, rdevmajor: 0, rdevminor: 0, data: new Uint8Array(0) });
    const o = origin.clone();
    o.log = this.log;
    o.rm('.backup', true);
    this.rm('.backup', true);

    const left = o.names();
    const right = this.names();
    let i = 0, j = 0;
    const doBackup = (name: string, entry: CpioEntry) => {
      let bname = `.backup/${name}`;
      if (!skipCompress && (entry.mode & S_IFMT) === S_IFREG) {
        entry = { ...entry, data: comp.xz(entry.data) };
        bname += '.xz';
      }
      this.log(`Backup [${name}] -> [${bname}]`);
      backups.set(bname, entry);
    };
    const record = (name: string) => {
      this.log(`Record new entry: [${name}] -> [.backup/.rmlist]`);
      rmList += name + '\0';
    };
    while (i < left.length || j < right.length) {
      if (i < left.length && j < right.length) {
        const ln = left[i]!, rn = right[j]!;
        const c = cmpName(ln, rn);
        if (c < 0) { doBackup(ln, o.entries.get(ln)!); i++; }
        else if (c > 0) { record(rn); j++; }
        else {
          const le = o.entries.get(ln)!;
          if (!bytesEqual(this.entries.get(rn)!.data, le.data)) doBackup(ln, le);
          i++; j++;
        }
      } else if (i < left.length) {
        doBackup(left[i]!, o.entries.get(left[i]!)!); i++;
      } else {
        record(right[j]!); j++;
      }
    }
    if (rmList !== '') {
      backups.set('.backup/.rmlist', { mode: S_IFREG, uid: 0, gid: 0, rdevmajor: 0, rdevminor: 0, data: enc.encode(rmList) });
    }
    for (const [k, v] of backups) this.entries.set(k, v);
  }

  /** cpio.rs Cpio::restore: undo `backup` (needs xz decode for `.backup/*.xz`). */
  restore(comp: Compressor): void {
    const backups = new Map<string, CpioEntry>();
    let rmList = '';
    for (const name of this.names()) {
      if (!name.startsWith('.backup/')) continue;
      let entry = this.entries.get(name)!;
      this.entries.delete(name);
      if (name === '.backup/.rmlist') {
        try {
          rmList += dec.decode(entry.data);
        } catch { /* not UTF-8: ignored, like the Rust code */ }
      } else if (name !== '.backup/.magisk') {
        let newName = name.slice(8);
        if (name.endsWith('.xz') && (entry.mode & S_IFMT) === S_IFREG) {
          const d = comp.unxz(entry.data);
          if (d) {
            entry = { ...entry, data: d };
            newName = name.slice(8, name.length - 3);
          }
        }
        this.log(`Restore [${name}] -> [${newName}]`);
        backups.set(newName, entry);
      }
    }
    this.rm('.backup', false);
    if (rmList === '' && backups.size === 0) {
      this.entries.clear();
      return;
    }
    for (const rm of rmList.split('\0')) if (rm !== '') this.rm(rm, false);
    for (const [k, v] of backups) this.entries.set(k, v);
  }
}
