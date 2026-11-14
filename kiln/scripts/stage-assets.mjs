#!/usr/bin/env node
// Copies the installer's pinned blobs into kiln/public/assets/blobs/ and writes
// kiln/public/assets/manifest.json ({ name, path, sha256, size, license, source }). The browser
// checks every blob against that SHA-256 before using it (src/assets/assets.ts).
//
//   node scripts/stage-assets.mjs            (from kiln/; npm run stage)
//   node scripts/stage-assets.mjs --out apps/kiln/public/assets   (the Next.js app; relative to kiln/)
//
// Env overrides: UBOOT_BUILD (default ~/src/u-boot-s1/build-s1), MAGISK_APK
// (default ~/src/magisk/Magisk-v31.1.apk), KIOSK_APK (default the app's debug build),
// KEYBOARD_APK (optional; Simple Keyboard, Apache-2.0).
//
// Nothing device-specific is staged: no backups, no Aqara binaries, no keys. public/assets/blobs is
// gitignored.
//
// The hosted installer is rebuilt only when one of its inputs changes on main. If you add a repo
// file here, add it to the paths in .github/workflows/installer-deploy.yml too.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REPO = resolve(WEB, '..');
const outArg = process.argv.indexOf('--out');
const OUT = outArg > 0 ? resolve(WEB, process.argv[outArg + 1]) : join(WEB, 'public/assets');
const env = (k, d) => process.env[k] || d;
const UBOOT = env('UBOOT_BUILD', join(homedir(), 'src/u-boot-s1/build-s1'));
const MAGISK = env('MAGISK_APK', join(homedir(), 'src/magisk/Magisk-v31.1.apk'));
const MAGISK_SHA256 = '89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7';
const KIOSK = env('KIOSK_APK', join(REPO, 'app/kiosk/build/outputs/apk/debug/kiosk-debug.apk'));
const KEYBOARD = process.env.KEYBOARD_APK;

const repo = (p) => join(REPO, p);
const SRC = 'https://github.com/GlassworksHQ/glassworks';
const assets = [
  { name: 'uboot-spl', file: join(UBOOT, 'u-boot-sunxi-with-spl.bin'), license: 'GPL-2.0-or-later', source: `${SRC}/tree/main/tools/fel/uboot (U-Boot + our patches)` },
  { name: 'uboot', file: join(UBOOT, 'u-boot.bin'), license: 'GPL-2.0-or-later', source: `${SRC}/tree/main/tools/fel/uboot (U-Boot + our patches)` },
  { name: 'magisk', file: MAGISK, sha256: MAGISK_SHA256, license: 'GPL-3.0-only', source: 'https://github.com/topjohnwu/Magisk/releases/tag/v31.1' },
  { name: 'otbr-agent', file: repo('tools/radio/out/otbr-agent'), license: 'BSD-3-Clause', source: `${SRC}/blob/main/docs/radio.md (ot-br-posix, built by tools/radio)` },
  { name: 'ot-ctl', file: repo('tools/radio/out/ot-ctl'), license: 'BSD-3-Clause', source: `${SRC}/blob/main/docs/radio.md (ot-br-posix, built by tools/radio)` },
  { name: 'radio-rc', file: repo('tools/radio/s1kiosk_radio.rc'), license: 'MIT', source: SRC },
  { name: 'radio-sh', file: repo('tools/radio/s1kiosk_radio.sh'), license: 'MIT', source: SRC },
  { name: 'firstboot-rc', file: repo('tools/root/s1kiosk_firstboot.rc'), license: 'MIT', source: SRC },
  { name: 'firstboot-sh', file: repo('tools/root/s1kiosk_firstboot.sh'), license: 'MIT', source: SRC },
  { name: 'netwatch-rc', file: repo('tools/root/s1kiosk_netwatch.rc'), license: 'MIT', source: SRC },
  { name: 'netwatch-sh', file: repo('tools/root/s1kiosk_netwatch.sh'), license: 'MIT', source: SRC },
  { name: 'update-rc', file: repo('tools/root/s1kiosk_update.rc'), license: 'MIT', source: SRC },
  { name: 'update-sh', file: repo('tools/root/s1kiosk_update.sh'), license: 'MIT', source: SRC },
  { name: 'bundle-rc', file: repo('tools/root/s1kiosk_bundle.rc'), license: 'MIT', source: SRC },
  { name: 'bundle-sh', file: repo('tools/root/s1kiosk_bundle.sh'), license: 'MIT', source: SRC },
  { name: 'bundle-pub', file: repo('tools/root/s1kiosk_bundle.pub'), license: 'MIT', source: SRC },
  { name: 'verify', file: repo('tools/root/out/s1kiosk_verify'), license: 'MIT AND BSD-2-Clause', source: `${SRC}/tree/main/tools/root (s1kiosk_verify.c + Monocypher 4.0.3 + musl, tools/root/build-verify.sh)` },
  { name: 'branding-rc', file: repo('tools/root/s1kiosk_branding.rc'), license: 'MIT', source: SRC },
  { name: 'bootanimation', file: repo('branding/glass-tile/bootanimation.zip'), license: 'MIT', source: `${SRC}/tree/main/branding/glass-tile` },
  { name: 'bootlogo', file: repo('branding/glass-tile/bootlogo.bmp'), license: 'MIT', source: `${SRC}/tree/main/branding/glass-tile` },
  { name: 'kiosk-apk', file: KIOSK, license: 'MIT', source: `${SRC}/tree/main/app` },
  ...(KEYBOARD || process.argv.includes('--remote') ? [{ name: 'keyboard-apk', file: KEYBOARD ?? '', license: 'Apache-2.0', source: 'https://github.com/rkkr/simple-keyboard' }] : []),
];

// --remote (the Vercel build): the binaries come from pinned URLs (assets.lock.json) instead of this
// computer, and the kiosk APK from the latest S1 Kiosk release, streamed by api/release-asset.ts.
const REMOTE = process.argv.includes('--remote');
const sha = (b) => createHash('sha256').update(b).digest('hex');

async function download(url) {
  const r = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 's1-unleashed-installer-build' } });
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return Buffer.from(await r.arrayBuffer());
}

/** Latest published S1 Kiosk release (v*, not a pre-release): its APK's tag, name, size and SHA-256. */
async function latestKiosk() {
  const gh = { 'user-agent': 's1-unleashed-installer-build', accept: 'application/vnd.github+json',
    ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}) };
  const r = await fetch('https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=30', { headers: gh });
  if (!r.ok) throw new Error(`GitHub releases: HTTP ${r.status}`);
  const rel = (await r.json()).find((x) => !x.draft && !x.prerelease && /^(aqara-s1-plus-)?v\d+\.\d+\.\d+$/.test(x.tag_name));
  if (!rel) throw new Error('no S1 Kiosk release yet');
  const v = rel.tag_name.replace(/^aqara-s1-plus-/, '').slice(1);
  const apk = rel.assets.find((x) => x.name === `s1-kiosk-${v}.apk`);
  const sum = rel.assets.find((x) => x.name === `s1-kiosk-${v}.apk.sha256`);
  if (!apk || !sum) throw new Error(`${rel.tag_name} lacks its APK or .sha256`);
  const sha256 = (await download(sum.browser_download_url)).toString().trim().split(/\s+/)[0];
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new Error(`${rel.tag_name}: bad .sha256`);
  return { tag: rel.tag_name, name: apk.name, size: apk.size, sha256 };
}

rmSync(join(OUT, 'blobs'), { recursive: true, force: true });
mkdirSync(join(OUT, 'blobs'), { recursive: true });
const manifest = [];
let missing = 0;
const lock = REMOTE ? JSON.parse(readFileSync(join(WEB, 'assets.lock.json'), 'utf8')).assets : [];
for (const a of assets) {
  const pinned = lock.find((l) => l.name === a.name);
  if (REMOTE && a.name === 'kiosk-apk') {
    const k = await latestKiosk();
    const path = `/api/release-asset?tag=${encodeURIComponent(k.tag)}&name=${encodeURIComponent(k.name)}`;
    manifest.push({ name: a.name, path, sha256: k.sha256, size: k.size, license: a.license, source: `${SRC}/releases/tag/${k.tag}` });
    console.log(`${a.name.padEnd(14)} ${String(k.size).padStart(10)}  ${k.sha256.slice(0, 16)}  ${k.tag} (streamed)`);
    continue;
  }
  let data;
  if (pinned) {
    data = await download(pinned.url);
    if (sha(data) !== pinned.sha256) { console.error(`BAD SHA-256 ${a.name}: ${pinned.url} is ${sha(data)}, expected ${pinned.sha256}`); process.exit(1); }
    a.license = pinned.license; a.source = pinned.source; a.file = pinned.url;
  } else {
    if (!existsSync(a.file)) {
      if (REMOTE && a.name === 'keyboard-apk') continue;
      console.error(`MISSING ${a.name}: ${a.file}`); missing++; continue;
    }
    data = readFileSync(a.file);
    if (a.sha256 && a.sha256 !== sha(data)) { console.error(`BAD SHA-256 ${a.name}: ${a.file} is ${sha(data)}, expected ${a.sha256}`); process.exit(1); }
  }
  const sha256 = sha(data);
  const path = `blobs/${a.name}`;
  writeFileSync(join(OUT, path), data);
  manifest.push({ name: a.name, path, sha256, size: data.length, license: a.license, source: a.source });
  console.log(`${a.name.padEnd(14)} ${String(data.length).padStart(10)}  ${sha256.slice(0, 16)}  ${a.file}`);
}
if (missing) { console.error(`${missing} asset(s) missing: not writing the manifest`); process.exit(1); }
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ generated: new Date().toISOString(), assets: manifest }, null, 2) + '\n');
console.log(`wrote ${join(OUT, 'manifest.json')} (${manifest.length} assets)`);
