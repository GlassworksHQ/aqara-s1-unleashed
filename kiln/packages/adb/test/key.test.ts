import { describe, expect, it } from 'vitest';
import { MemoryKeyStorage, loadOrCreateAdbKey } from '../src';

describe('adb key', () => {
  it('is created once, kept, and its public key is an adb_keys line for the same modulus', async () => {
    const storage = new MemoryKeyStorage();
    const a = await loadOrCreateAdbKey(storage);
    const b = await loadOrCreateAdbKey(storage);
    expect(b.publicKey).toBe(a.publicKey);
    expect(a.publicKey).toMatch(/^QAAAA[A-Za-z0-9+/=]+ s1-unleashed-web@browser$/);
    // Android RSAPublicKey: u32 len (64 words), u32 n0inv, n[256] LE, rr[256] LE, u32 e
    const raw = Uint8Array.from(atob(a.publicKey.split(' ')[0]!), (c) => c.charCodeAt(0));
    expect(raw.length).toBe(524);
    const v = new DataView(raw.buffer);
    expect(v.getUint32(0, true)).toBe(64);
    expect(v.getUint32(520, true)).toBe(65537);
    const pkcs8 = (await storage.load())!;
    const key = await crypto.subtle.importKey('pkcs8', pkcs8 as Uint8Array<ArrayBuffer>, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-1' }, true, ['sign']);
    const jwk = await crypto.subtle.exportKey('jwk', key);
    const n = Uint8Array.from(atob(jwk.n!.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
    expect(Array.from(raw.subarray(8, 8 + 256)).reverse()).toEqual(Array.from(n));
  });
});
