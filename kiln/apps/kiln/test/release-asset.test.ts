// /api/release-asset: only this repo's S1 Kiosk release APKs pass, and the body streams through.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GET } from '../src/app/api/release-asset/route';

const get = (q: string) => GET(new Request(`https://kiln.example/api/release-asset?${q}`));
afterEach(() => vi.unstubAllGlobals());

describe('the release asset route', () => {
  it.each([
    'tag=v0.4.3&name=evil.apk',
    'tag=main&name=s1-kiosk-0.4.3.apk',
    'tag=v0.4.3&name=s1-kiosk-0.4.4.apk',
    'tag=v0.4.3/../x&name=s1-kiosk-0.4.3.apk',
    '',
  ])('refuses %s', async (q) => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    expect((await get(q)).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('streams a release APK from GitHub, following its redirect', async () => {
    const fetch = vi.fn(async () => new Response(new Uint8Array([0x50, 0x4b, 3, 4]), { headers: { 'content-length': '4' } }));
    vi.stubGlobal('fetch', fetch);
    const r = await get('tag=aqara-s1-plus-v0.4.3&name=s1-kiosk-0.4.3.apk');
    expect(fetch).toHaveBeenCalledWith('https://github.com/GlassworksHQ/glassworks/releases/download/aqara-s1-plus-v0.4.3/s1-kiosk-0.4.3.apk', { redirect: 'follow' });
    expect(r.status).toBe(200);
    expect(r.headers.get('content-type')).toBe('application/vnd.android.package-archive');
    expect(r.headers.get('content-length')).toBe('4');
    expect(new Uint8Array(await r.arrayBuffer())).toEqual(new Uint8Array([0x50, 0x4b, 3, 4]));
  });

  it('says so when GitHub fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('no', { status: 404 })));
    expect((await get('tag=v0.4.3&name=s1-kiosk-0.4.3.apk')).status).toBe(502);
  });
});
