// Streams an S1 Kiosk release APK from GitHub Releases through the installer's own domain. Browsers
// can't fetch GitHub release downloads directly (no CORS headers), and the APK (~100 MB) is too big
// to ship as a static file. Only this repo's `s1-kiosk-X.Y.Z.apk` assets of a `vX.Y.Z` tag are
// allowed; the installer checks the SHA-256 published with the release before using the bytes.
export const config = { runtime: 'edge' };

const REPO = 'aguilaair/aqara-s1-unleashed';

export default async function handler(req: Request): Promise<Response> {
  const q = new URL(req.url).searchParams;
  const tag = q.get('tag') ?? '';
  const name = q.get('name') ?? '';
  const m = /^v(\d+\.\d+\.\d+)$/.exec(tag);
  if (!m || name !== `s1-kiosk-${m[1]}.apk`) return new Response('not an S1 Kiosk release asset', { status: 400 });
  const upstream = await fetch(`https://github.com/${REPO}/releases/download/${tag}/${name}`, { redirect: 'follow' });
  if (!upstream.ok || !upstream.body) return new Response(`GitHub answered ${upstream.status}`, { status: 502 });
  const headers = new Headers({
    'content-type': 'application/vnd.android.package-archive',
    'cache-control': 'public, max-age=31536000, immutable', // a tag's asset never changes
  });
  const len = upstream.headers.get('content-length');
  if (len) headers.set('content-length', len);
  return new Response(upstream.body, { status: 200, headers });
}
