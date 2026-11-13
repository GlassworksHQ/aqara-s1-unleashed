// The Aqara Panel Hub S1 Plus for Kiln: tools/install/fel-install.sh's device-specific steps. FEL on
// the A133 boots our U-Boot from RAM, fastboot shows the S1 Plus layout, the patched boot carries the
// root helper, radio and update services, and provisioning is tools/install.sh (provision.ts).
import { isMagiskPatched, patchBoot, replaceBootlogo, s1Overlay, vbmetaFlags, withVbmetaFlags } from '@glassworks/bootpatch';
import { bootUboot, checkChip } from '@glassworks/fel';
import type { FlashStep, Recipe } from '@glassworks/kiln-core';
import { provision, type Provisioned, type ProvisionOptions } from './provision';
import { isOurSplash } from './splash';

/** The blobs in kiln/assets.lock.json (staged by kiln/scripts/stage-assets.mjs). */
export type AssetName =
  | 'uboot-spl' | 'uboot' | 'magisk' | 'otbr-agent' | 'ot-ctl'
  | 'radio-rc' | 'radio-sh' | 'firstboot-rc' | 'firstboot-sh' | 'netwatch-rc' | 'netwatch-sh' | 'update-rc' | 'update-sh'
  | 'bundle-rc' | 'bundle-sh' | 'verify' | 'bundle-pub'
  | 'branding-rc' | 'bootanimation' | 'bootlogo' | 'kiosk-apk' | 'keyboard-apk';

export type SetUpOptions = Omit<ProvisionOptions, 'kioskApk' | 'keyboardApk'> & { keyboard?: boolean };

const BOOT_SIZE = 0x2000000;      // 32 MiB: S1 Plus layout check (fel-install.sh step 3)
const SUPER_SIZE = 0x60000000;    // 1.5 GiB

export const s1Plus: Recipe<AssetName, SetUpOptions, Provisioned> = {
  fel: {
    async checkChip(fel) {
      const chip = await checkChip(fel);
      return `SoC 0x${chip.socId.toString(16)} (A133), secure boot off`;
    },
    loader: ['uboot-spl', 'uboot'],
    boot: (fel, [spl, uboot], onProgress) => bootUboot(fel, spl!, uboot!, onProgress),
  },

  async checkLayout(fb) {
    const product = await fb.getvar('product');
    const bootSize = await fb.partitionSize('boot');
    const superSize = await fb.partitionSize('super');
    if (product !== 'sunxi' || bootSize !== BOOT_SIZE || superSize !== SUPER_SIZE) {
      throw new Error(`this does not look like an Aqara S1 Plus (product ${product}, boot ${bootSize}, super ${superSize}). Nothing was changed.`);
    }
    return 'partition layout OK (S1 Plus)';
  },

  backup: {
    parts: ['boot', 'vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'dtbo', 'misc', 'recovery', 'env', 'bootloader'],
    // fel-install.sh --undo: boot, vbmeta and splash
    restore: ['boot', 'vbmeta', 'bootloader'],
    idPrefix: 's1-backup',
    db: 's1-unleashed-backups',
    isPatched: isMagiskPatched,
    // A backup taken after our splash went on (boot put back without it, then installed again)
    ours: { bootloader: { what: 'splash', is: isOurSplash } },
  },

  patching: 'Adding root, the radio helper and this browser\'s key',

  async patchBoot({ backup, asset: a, adbPublicKey, branding, log }) {
    const [magiskApk, radioRc, radioSh, otbrAgent, otCtl, firstbootRc, firstbootSh, netwatchRc, netwatchSh, updateRc, updateSh,
      bundleRc, bundleSh, verify, bundlePub] =
      await Promise.all([a('magisk'), a('radio-rc'), a('radio-sh'), a('otbr-agent'), a('ot-ctl'), a('firstboot-rc'), a('firstboot-sh'), a('netwatch-rc'), a('netwatch-sh'), a('update-rc'), a('update-sh'),
        a('bundle-rc'), a('bundle-sh'), a('verify'), a('bundle-pub')]);
    const overlay = s1Overlay({
      radioRc, radioSh, otbrAgent, otCtl, firstbootRc, firstbootSh, netwatchRc, netwatchSh, updateRc, updateSh,
      bundleRc, bundleSh, verify, bundlePub,
      adbKeys: new TextEncoder().encode(`${adbPublicKey}\n`),
      branding: branding ? { rc: await a('branding-rc'), bootAnimation: await a('bootanimation') } : undefined,
    });
    const patched = await patchBoot({ boot: backup.get('boot')!, magiskApk, overlay });
    patched.log.forEach((l) => log(l));
    return patched.image;
  },

  async writesBeforeBoot({ backup, asset, branding, log }) {
    // 6: vbmeta flags 3 (verification off), only if needed
    const vbmeta = backup.get('vbmeta')!;
    const flags = vbmetaFlags(vbmeta);
    log(`vbmeta flags: ${flags}`);
    const steps: FlashStep[] = [];
    if (flags !== 3) steps.push({ part: 'vbmeta', data: withVbmetaFlags(vbmeta, 3), what: 'Turning off verified boot' });
    // 7: splash
    if (branding) {
      const bl = backup.get('bootloader')!;
      const branded = replaceBootlogo(bl, await asset('bootlogo'));
      if (branded.some((x, i) => x !== bl[i])) steps.push({ part: 'bootloader', data: branded, what: 'Writing the boot logo' });
      else log('splash already in place: unchanged');
    }
    return steps;
  },

  async setUp(panel, opts, { asset, hasAsset, step }) {
    const kioskApk = await asset('kiosk-apk');
    const keyboardApk = opts.keyboard !== false && (await hasAsset('keyboard-apk')) ? await asset('keyboard-apk') : undefined;
    return provision(panel, { ...opts, kioskApk, keyboardApk }, step);
  },
};
