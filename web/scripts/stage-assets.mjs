#!/usr/bin/env node
// Copies the installer's pinned blobs into web/public/assets/blobs/ and writes
// web/public/assets/manifest.json ({ name, path, sha256, size, license, source }). The browser
// checks every blob against that SHA-256 before using it (src/assets/assets.ts).
//
//   node scripts/stage-assets.mjs            (from web/; npm run stage)
//
// Env overrides: UBOOT_BUILD (default ~/src/u-boot-s1/build-s1), MAGISK_APK
// (default ~/src/magisk/Magisk-v31.1.apk), KIOSK_APK (default the app's debug build),
// KEYBOARD_APK (optional; Simple Keyboard, Apache-2.0).
//
// Nothing device-specific is staged: no backups, no Aqara binaries, no keys. public/assets/blobs is
// gitignored.
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REPO = resolve(WEB, '..');
const OUT = join(WEB, 'public/assets');
const env = (k, d) => process.env[k] || d;
const UBOOT = env('UBOOT_BUILD', join(homedir(), 'src/u-boot-s1/build-s1'));
const MAGISK = env('MAGISK_APK', join(homedir(), 'src/magisk/Magisk-v31.1.apk'));
const MAGISK_SHA256 = '89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7';
const KIOSK = env('KIOSK_APK', join(REPO, 'app/kiosk/build/outputs/apk/debug/kiosk-debug.apk'));
const KEYBOARD = process.env.KEYBOARD_APK;

const repo = (p) => join(REPO, p);
const SRC = 'https://github.com/aguilaair/aqara-s1-unleashed';
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
  { name: 'branding-rc', file: repo('tools/root/s1kiosk_branding.rc'), license: 'MIT', source: SRC },
  { name: 'bootanimation', file: repo('branding/glass-tile/bootanimation.zip'), license: 'MIT', source: `${SRC}/tree/main/branding/glass-tile` },
  { name: 'bootlogo', file: repo('branding/glass-tile/bootlogo.bmp'), license: 'MIT', source: `${SRC}/tree/main/branding/glass-tile` },
  { name: 'kiosk-apk', file: KIOSK, license: 'MIT', source: `${SRC}/tree/main/app` },
  ...(KEYBOARD ? [{ name: 'keyboard-apk', file: KEYBOARD, license: 'Apache-2.0', source: 'https://github.com/rkkr/simple-keyboard' }] : []),
];

rmSync(join(OUT, 'blobs'), { recursive: true, force: true });
mkdirSync(join(OUT, 'blobs'), { recursive: true });
const manifest = [];
let missing = 0;
for (const a of assets) {
  if (!existsSync(a.file)) { console.error(`MISSING ${a.name}: ${a.file}`); missing++; continue; }
  const data = readFileSync(a.file);
  const sha256 = createHash('sha256').update(data).digest('hex');
  if (a.sha256 && a.sha256 !== sha256) { console.error(`BAD SHA-256 ${a.name}: ${a.file} is ${sha256}, expected ${a.sha256}`); process.exit(1); }
  const path = `blobs/${a.name}`;
  copyFileSync(a.file, join(OUT, path));
  manifest.push({ name: a.name, path, sha256, size: statSync(a.file).size, license: a.license, source: a.source });
  console.log(`${a.name.padEnd(14)} ${String(data.length).padStart(10)}  ${sha256.slice(0, 16)}  ${a.file}`);
}
if (missing) { console.error(`${missing} asset(s) missing: not writing the manifest`); process.exit(1); }
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ generated: new Date().toISOString(), assets: manifest }, null, 2) + '\n');
console.log(`wrote ${join(OUT, 'manifest.json')} (${manifest.length} assets)`);
