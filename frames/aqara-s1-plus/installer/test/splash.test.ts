// Undo refuses an S1 backup whose splash is already ours (#106): a stock boot alone doesn't make a
// backup the original. The browser (isOurSplash) and the CLI (set-bootlogo.py --ours) must agree.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { readBootlogo, replaceBootlogo } from '@glassworks/bootpatch';
import { BackupRefused, checkRestorable, type BackupPlan } from '@glassworks/kiln-core';
import { s1Plus } from '../src/recipe';
import { isOurSplash } from '../src/splash';
import { REPO, bootResource, otherBootlogo, ourBootlogo } from './fakes';

const LIST = join(REPO, 'branding/bootlogos.json');
/** isPatched needs a real boot image; these tests are about the splash, so boot counts as stock. */
const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => false };
const backup = (bootloader: Uint8Array) => new Map([['boot', new Uint8Array(16)], ['vbmeta', new Uint8Array(16)], ['bootloader', bootloader]]);
const cli = (img: Uint8Array) => {
  const dir = mkdtempSync(join(tmpdir(), 's1-splash-'));
  try {
    writeFileSync(join(dir, 'bootloader.img'), img);
    return execFileSync('python3', [join(REPO, 'tools/branding/set-bootlogo.py'), '--ours', join(dir, 'bootloader.img'), LIST], { encoding: 'utf8' }).trim();
  } finally { rmSync(dir, { recursive: true }); }
};

describe('undo and our splash', () => {
  const stock = bootResource(otherBootlogo());
  const installed = replaceBootlogo(stock, ourBootlogo());

  it('reads BOOTLOGO.BMP through its FAT chain', () => {
    expect(readBootlogo(installed)).toEqual(ourBootlogo());
  });

  it('refuses a backup whose splash is ours', async () => {
    const p = checkRestorable(plan, backup(installed));
    await expect(p).rejects.toThrow('this backup\'s splash is already ours, not the original: use the one from your first install');
    await expect(p).rejects.toBeInstanceOf(BackupRefused);
  });

  it('accepts a backup whose splash is someone else\'s', async () => {
    await checkRestorable(plan, backup(stock));
  });

  it('a splash it can\'t read is not ours: we only ever write into a readable one', async () => {
    expect(await isOurSplash(new TextEncoder().encode('splash'))).toBe(false);
    expect(await isOurSplash(new Uint8Array(4096))).toBe(false);
    expect(await isOurSplash(installed.subarray(0, installed.length - 4096))).toBe(false);
  });

  it('the CLI agrees', async () => {
    expect(cli(installed)).toBe('ours Glass Tile');
    expect(cli(stock)).toBe('not ours');
    expect(await isOurSplash(installed)).toBe(true);
    expect(await isOurSplash(stock)).toBe(false);
  });

  it('knows every bootlogo.bmp in branding/ (common.build lists each one it writes)', () => {
    const listed = new Set((JSON.parse(readFileSync(LIST, 'utf8')) as { bootlogos: { sha256: string }[] }).bootlogos.map((b) => b.sha256));
    const bmps: string[] = [];
    const walk = (d: string): void => {
      for (const f of readdirSync(d)) {
        const p = join(d, f);
        if (statSync(p).isDirectory()) walk(p);
        else if (f === 'bootlogo.bmp') bmps.push(p);
      }
    };
    walk(join(REPO, 'branding'));
    expect(bmps.length).toBeGreaterThan(0);
    for (const p of bmps) expect(listed, `${relative(REPO, p)} is not in branding/bootlogos.json`).toContain(createHash('sha256').update(readFileSync(p)).digest('hex'));
  });
});

// The panel's real partitions (never committed): the stock one from a 4.5.80 panel, and the one
// fel-install.sh wrote with our splash. Skipped without them.
const B = join(homedir(), 'src/aqara-panel-backups');
const STOCK = join(B, 'install-4.5.80-20261109-203611/bootloader.img');
const BRANDED = join(process.env.S1_WEB_GOLDEN ?? join(B, 'web-golden'), 'out/bootloader-glass-tile.img');
describe.skipIf(!existsSync(STOCK) || !existsSync(BRANDED))('golden: a real bootloader partition', () => {
  it('stock is not ours, after an install it is', async () => {
    expect(await isOurSplash(new Uint8Array(readFileSync(STOCK)))).toBe(false);
    expect(await isOurSplash(new Uint8Array(readFileSync(BRANDED)))).toBe(true);
  });
});
