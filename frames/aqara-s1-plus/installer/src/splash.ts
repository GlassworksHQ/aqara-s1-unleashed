// Whether a `bootloader` partition (the S1's boot-resource) shows one of our splashes: its
// BOOTLOGO.BMP's SHA-256 is in branding/bootlogos.json, which the branding build appends to.
// fel-install.sh asks the same question with `tools/branding/set-bootlogo.py --ours`.
import { readBootlogo } from '@glassworks/bootpatch';
import { toHex } from '@glassworks/kiln-core';
import list from '../../../../branding/bootlogos.json';

const OURS = new Set(list.bootlogos.map((b) => b.sha256));

export async function isOurSplash(bootloader: Uint8Array): Promise<boolean> {
  // We only ever write our splash into a readable BOOTLOGO.BMP and leave the FAT as it was, so an
  // image without one doesn't hold ours.
  const bmp = readBootlogo(bootloader);
  return !!bmp && OURS.has(toHex(await crypto.subtle.digest('SHA-256', bmp as Uint8Array<ArrayBuffer>)));
}
