// The panel's own partitions, kept in this browser (IndexedDB) and offered as a zip download.
// It is the undo: the first backup of a panel is its only copy of the original software.
import { toHex } from '../assets/assets';
import { crc32, zipBlob } from './zip';

export const BACKUP_PARTS = ['boot', 'vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'dtbo', 'misc', 'recovery', 'env', 'bootloader'] as const;
export type BackupPart = (typeof BACKUP_PARTS)[number];
/** What undo writes back (fel-install.sh --undo). */
export const RESTORE_PARTS = ['boot', 'vbmeta', 'bootloader'] as const;

export interface BackupFile { part: string; data: Uint8Array; sha256: string; crc: number }
export interface BackupMeta {
  id: string;            // s1-backup-YYYYMMDD-HHMMSS
  created: string;       // ISO time
  serial?: string;       // fastboot serial: shown only in this browser, never sent anywhere
  /** The boot partition was already Magisk-patched (a re-run): not the original software. */
  bootWasPatched: boolean;
  files: { part: string; size: number; sha256: string }[];
}

export async function backupFile(part: string, data: Uint8Array): Promise<BackupFile> {
  return { part, data, sha256: toHex(await crypto.subtle.digest('SHA-256', data as Uint8Array<ArrayBuffer>)), crc: crc32(data) };
}

export function backupId(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `s1-backup-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

/** Same format as `shasum -a 256 *.img` (fel-install.sh), so the CLI's --undo accepts the unzipped backup. */
export function sha256sums(files: { part: string; sha256: string }[]): string {
  return [...files].sort((a, b) => (a.part < b.part ? -1 : 1)).map((f) => `${f.sha256}  ${f.part}.img\n`).join('');
}

export function backupZip(meta: BackupMeta, files: BackupFile[]): Blob {
  const sums = new TextEncoder().encode(sha256sums(files));
  const info = new TextEncoder().encode(JSON.stringify({ ...meta, serial: undefined }, null, 2) + '\n');
  return zipBlob([
    ...files.map((f) => ({ name: `${meta.id}/${f.part}.img`, data: f.data, crc: f.crc })),
    { name: `${meta.id}/SHA256SUMS`, data: sums, crc: crc32(sums) },
    { name: `${meta.id}/backup.json`, data: info, crc: crc32(info) },
  ]);
}

/** Parses a backup zip made by backupZip (STORED only) back into files, checking SHA256SUMS. */
export async function readBackupZip(zip: Blob): Promise<{ id: string; files: Map<string, Uint8Array> }> {
  const buf = new Uint8Array(await zip.arrayBuffer());
  const v = new DataView(buf.buffer);
  const files = new Map<string, Uint8Array>();
  let id = '';
  let sums = '';
  for (let o = 0; o + 30 <= buf.length && v.getUint32(o, true) === 0x04034b50; ) {
    if (v.getUint16(o + 8, true) !== 0) throw new Error('not a backup made by this installer (compressed zip)');
    const size = v.getUint32(o + 18, true), nlen = v.getUint16(o + 26, true), xlen = v.getUint16(o + 28, true);
    const name = new TextDecoder().decode(buf.subarray(o + 30, o + 30 + nlen));
    const data = buf.subarray(o + 30 + nlen + xlen, o + 30 + nlen + xlen + size);
    const [dir, base] = name.split('/');
    if (dir && base) {
      id = dir;
      if (base === 'SHA256SUMS') sums = new TextDecoder().decode(data);
      else if (base.endsWith('.img')) files.set(base.slice(0, -4), data);
    }
    o += 30 + nlen + xlen + size;
  }
  if (!sums) throw new Error('this zip has no SHA256SUMS: not a panel backup');
  for (const line of sums.trim().split('\n')) {
    const [sha, name] = line.split(/\s+/);
    const part = name?.replace(/\.img$/, '');
    const data = part ? files.get(part) : undefined;
    if (!data) throw new Error(`backup is missing ${name}`);
    const got = toHex(await crypto.subtle.digest('SHA-256', data as Uint8Array<ArrayBuffer>));
    if (got !== sha) throw new Error(`${name} does not match its SHA256SUMS: the backup is damaged`);
  }
  return { id, files };
}

/** IndexedDB: one record per backup (meta + files). */
export class BackupStore {
  constructor(private readonly db = 's1-unleashed-backups') {}

  private open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(this.db, 1);
      r.onupgradeneeded = () => { r.result.createObjectStore('meta', { keyPath: 'id' }); r.result.createObjectStore('files'); };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
    });
  }

  async save(meta: BackupMeta, files: BackupFile[]): Promise<void> {
    try { await navigator.storage?.persist?.(); } catch { /* best effort */ }
    const db = await this.open();
    try {
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(['meta', 'files'], 'readwrite');
        tx.objectStore('meta').put(meta);
        for (const f of files) tx.objectStore('files').put(f, `${meta.id}/${f.part}`);
        tx.oncomplete = () => resolve();
        tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('could not save the backup in this browser (out of space?)'));
      });
    } finally { db.close(); }
  }

  async list(): Promise<BackupMeta[]> {
    const db = await this.open();
    try {
      return await new Promise((resolve, reject) => {
        const r = db.transaction('meta').objectStore('meta').getAll();
        r.onsuccess = () => resolve((r.result as BackupMeta[]).sort((a, b) => (a.created < b.created ? -1 : 1)));
        r.onerror = () => reject(r.error);
      });
    } finally { db.close(); }
  }

  async files(meta: BackupMeta): Promise<BackupFile[]> {
    const db = await this.open();
    try {
      const store = db.transaction('files').objectStore('files');
      return await Promise.all(meta.files.map((f) => new Promise<BackupFile>((resolve, reject) => {
        const r = store.get(`${meta.id}/${f.part}`);
        r.onsuccess = () => (r.result ? resolve(r.result as BackupFile) : reject(new Error(`backup ${meta.id} lost ${f.part}`)));
        r.onerror = () => reject(r.error);
      })));
    } finally { db.close(); }
  }

  /** The oldest backup whose boot was not already patched: the original software. */
  async original(): Promise<BackupMeta | undefined> {
    return (await this.list()).find((m) => !m.bootWasPatched);
  }
}
