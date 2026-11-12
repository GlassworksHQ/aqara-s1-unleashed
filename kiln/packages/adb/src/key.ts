// This browser's adb key. The public half is baked into the patched boot image
// (overlay.d/sbin/s1kiosk_adb_keys, see tools/root/s1kiosk_firstboot.sh), so the panel authorizes
// this browser on its first boot without a prompt on the screen. Kept in IndexedDB so a later visit
// (undo, re-run) is still authorized.
import { adbGeneratePublicKey, type AdbCredentialStore, type AdbPrivateKey } from '@yume-chan/adb';

export const KEY_NAME = 's1-unleashed-web@browser';

/** Where the PKCS#8 private key lives. IndexedDB in the browser, memory in the tests. */
export interface KeyStorage {
  load(): Promise<Uint8Array | undefined>;
  save(pkcs8: Uint8Array): Promise<void>;
}

export class IndexedDbKeyStorage implements KeyStorage {
  constructor(private readonly db = 's1-unleashed', private readonly store = 'adb-key') {}

  private open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(this.db, 1);
      r.onupgradeneeded = () => r.result.createObjectStore(this.store);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error ?? new Error('IndexedDB open failed'));
    });
  }

  private async tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await this.open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const req = f(db.transaction(this.store, mode).objectStore(this.store));
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error ?? new Error('IndexedDB request failed'));
      });
    } finally {
      db.close();
    }
  }

  async load(): Promise<Uint8Array | undefined> {
    const v = await this.tx<unknown>('readonly', (s) => s.get('pkcs8'));
    return v instanceof Uint8Array ? v : v instanceof ArrayBuffer ? new Uint8Array(v) : undefined;
  }

  async save(pkcs8: Uint8Array): Promise<void> {
    await this.tx('readwrite', (s) => s.put(pkcs8, 'pkcs8'));
  }
}

export class MemoryKeyStorage implements KeyStorage {
  private v: Uint8Array | undefined;
  async load() { return this.v; }
  async save(p: Uint8Array) { this.v = p; }
}

async function generatePkcs8(): Promise<Uint8Array> {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
    true, ['sign', 'verify'],
  );
  return new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
}

function base64(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export interface AdbKey {
  /** One adb_keys line: base64 Android RSA public key, a space, the name (like ~/.android/adbkey.pub). */
  publicKey: string;
  store: AdbCredentialStore;
}

/** Loads this browser's adb key, creating it the first time. */
export async function loadOrCreateAdbKey(storage: KeyStorage = new IndexedDbKeyStorage()): Promise<AdbKey> {
  let pkcs8 = await storage.load();
  if (!pkcs8) {
    pkcs8 = await generatePkcs8();
    await storage.save(pkcs8);
  }
  const key: AdbPrivateKey = { buffer: pkcs8, name: KEY_NAME };
  return {
    publicKey: `${base64(adbGeneratePublicKey(pkcs8))} ${KEY_NAME}`,
    store: {
      generateKey: () => key, // never needed: the panel already trusts this key
      iterateKeys: () => [key],
    },
  };
}
