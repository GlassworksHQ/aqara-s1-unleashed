// The files tools/root/patch-boot-magisk.sh adds to the ramdisk after Magisk's own patch, with the
// same paths, modes and pre-flight checks. The caller supplies the bytes (fetched assets + the
// browser's adb key).

import type { OverlayFile } from './index';
import { zipEntries } from './magisk';

export interface S1OverlayInputs {
  radioRc: Uint8Array;        // tools/radio/s1kiosk_radio.rc
  radioSh: Uint8Array;        // tools/radio/s1kiosk_radio.sh
  otbrAgent: Uint8Array;      // tools/radio/out/otbr-agent
  otCtl: Uint8Array;          // tools/radio/out/ot-ctl
  firstbootRc: Uint8Array;    // tools/root/s1kiosk_firstboot.rc
  firstbootSh: Uint8Array;    // tools/root/s1kiosk_firstboot.sh
  /** adb authorized_keys line(s) ('QAAAA... name\n'), or empty (ADB_PUBKEY=none). */
  adbKeys: Uint8Array;
  netwatchRc: Uint8Array;     // tools/root/s1kiosk_netwatch.rc
  netwatchSh: Uint8Array;     // tools/root/s1kiosk_netwatch.sh
  updateRc: Uint8Array;       // tools/root/s1kiosk_update.rc
  updateSh: Uint8Array;       // tools/root/s1kiosk_update.sh
  /** Boot animation (all entries STORED) + tools/root/s1kiosk_branding.rc; omit both for stock. */
  branding?: { rc: Uint8Array; bootAnimation: Uint8Array };
}

/** The exact OverlayFile list patch-boot-magisk.sh adds. Throws where the script would exit 1. */
export function s1Overlay(i: S1OverlayInputs): OverlayFile[] {
  // patch-boot-magisk.sh: grep -q '^QAAAA' (some line must start with it); empty = ADB_PUBKEY=none
  if (i.adbKeys.length && !/^QAAAA/m.test(new TextDecoder().decode(i.adbKeys)))
    throw new Error('adbKeys does not look like an adb public key (must start with QAAAA)');
  const files: OverlayFile[] = [
    { path: 'overlay.d/s1kiosk_radio.rc', mode: 0o644, data: i.radioRc },
    { path: 'overlay.d/sbin/s1kiosk_radio.sh', mode: 0o700, data: i.radioSh },
    { path: 'overlay.d/sbin/otbr-agent', mode: 0o700, data: i.otbrAgent },
    { path: 'overlay.d/sbin/ot-ctl', mode: 0o700, data: i.otCtl },
    { path: 'overlay.d/s1kiosk_firstboot.rc', mode: 0o644, data: i.firstbootRc },
    { path: 'overlay.d/sbin/s1kiosk_firstboot.sh', mode: 0o700, data: i.firstbootSh },
    { path: 'overlay.d/sbin/s1kiosk_adb_keys', mode: 0o600, data: i.adbKeys },
    { path: 'overlay.d/s1kiosk_netwatch.rc', mode: 0o644, data: i.netwatchRc },
    { path: 'overlay.d/sbin/s1kiosk_netwatch.sh', mode: 0o700, data: i.netwatchSh },
    { path: 'overlay.d/s1kiosk_update.rc', mode: 0o644, data: i.updateRc },
    { path: 'overlay.d/sbin/s1kiosk_update.sh', mode: 0o700, data: i.updateSh },
  ];
  if (i.branding) {
    if (!i.branding.bootAnimation.length) throw new Error('empty boot animation');
    const bad = [...zipEntries(i.branding.bootAnimation).values()].filter((e) => e.method !== 0).map((e) => e.name);
    if (bad.length) throw new Error(`bootanimation.zip entries must be STORED, not compressed: ${bad.slice(0, 3).join(', ')}`);
    files.push(
      { path: 'overlay.d/s1kiosk_branding.rc', mode: 0o644, data: i.branding.rc },
      { path: 'overlay.d/sbin/s1kiosk_bootanimation.zip', mode: 0o644, data: i.branding.bootAnimation },
    );
  }
  return files;
}
