# Web installer: module contracts

The design is in `docs/research/web-installer.md`, and the reference flow in `tools/install/fel-install.sh` +
`tools/install.sh`. Every module is plain TypeScript (ES2022, no framework). Modules talk to each
other only through the exports below. `app/` is the only module that knows about the UI.

`kiln/` is an npm workspace. The protocol modules are packages in `packages/`, each with its own
tests: `@glassworks/fel`, `@glassworks/fastboot`, `@glassworks/adb`, `@glassworks/bootpatch`,
`@glassworks/setup-channel` and `improv-serial`. The page (`app/`, `assets/`, `improv/`,
`provision/`) stays in `src/` and imports them by package name.

## `fel` (`packages/fel`) — FEL over WebUSB (sunxi-fel subset; owner: main session, needs the panel)
```ts
export const FEL_FILTER: USBDeviceFilter;                  // { vendorId: 0x1f3a, productId: 0xefe8 }
export class FelDevice {
  static request(): Promise<FelDevice>;                    // navigator.usb.requestDevice + open/claim
  static fromDevice(d: USBDevice): Promise<FelDevice>;
  version(): Promise<{ socId: number; scratchpad: number; raw: Uint8Array }>;   // 'ver'
  readl(addr: number): Promise<number>;
  write(addr: number, data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void>; // 64 KiB chunks
  read(addr: number, len: number): Promise<Uint8Array>;
  runSpl(splWithUboot: Uint8Array): Promise<void>;         // 'spl': DRAM init, returns to FEL
  reset64(entry: number): Promise<void>;                   // RMR warm reset into AArch64 (device leaves FEL)
  close(): Promise<void>;
}
/** fel-boot.sh NO_BL31=1: spl, then u-boot.bin at 0x4a000000 in chunks, then reset64. */
export function bootUboot(fel: FelDevice, splWithUboot: Uint8Array, ubootBin: Uint8Array,
                          onProgress?: (p: number) => void): Promise<void>;
export const A133: { socId: 0x1855; sidSecureBootReg: 0x030060a0; ubootAddr: 0x4a000000; /* ... */ };
```

## `fastboot` (`packages/fastboot`) — our U-Boot's fastboot (owner: main session)
```ts
export const FASTBOOT_FILTER: USBDeviceFilter;             // U-Boot gadget VID:PID
export class Fastboot {
  static request(): Promise<Fastboot>;
  getvar(name: string): Promise<string>;
  oemRun(cmd: string): Promise<void>;                      // 'oem run:<cmd>' (<= 64 bytes incl. prefix)
  readPartition(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>; // oem run + upload
  flash(name: string, data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void>;
  reboot(): Promise<void>;
}
```

## `bootpatch` (`packages/bootpatch`) — byte-exact boot image + partition edits (owner: patcher agent; pure, no USB)
```ts
export interface OverlayFile { path: string; mode: number; data: Uint8Array }  // e.g. 'overlay.d/sbin/x', 0o700
export interface PatchInput {
  boot: Uint8Array;               // the panel's boot.img: stock, or already Magisk-patched (restored first)
  magiskApk: Uint8Array;          // Magisk v31.1 APK (sha256 verified by the caller)
  overlay: OverlayFile[];         // exactly what tools/root/patch-boot-magisk.sh adds (see there)
  preinitDevice?: string;         // default 'cache'
}
export interface PatchResult { image: Uint8Array; wasMagisk: boolean; magiskConfig: string; log: string[] }
export function patchBoot(input: PatchInput): Promise<PatchResult>;  // == patch-boot-magisk.sh output, byte for byte
export function isMagiskPatched(boot: Uint8Array): Promise<boolean>;
export function vbmetaFlags(vbmeta: Uint8Array): number;            // BE u32 at offset 120
export function withVbmetaFlags(vbmeta: Uint8Array, set: number): Uint8Array; // flags |= set
export function replaceBootlogo(bootloaderImg: Uint8Array, bmp: Uint8Array): Uint8Array; // == tools/branding/set-bootlogo.py
```

## `adb` (`packages/adb`) + `provision/` — after first boot (owner: main session)
```ts
export function loadOrCreateAdbKey(storage?: KeyStorage): Promise<AdbKey>;  // { publicKey: 'QAAAA... name', store } (IndexedDB)
export interface PanelShell { run(cmd): Promise<{ out: string; code: number }>; push(path, data, mode?): Promise<void> }
export class Panel implements PanelShell { static connect(key, device?): Promise<Panel>; static known(): Promise<USBDevice[]> }
export function provision(panel: PanelShell, opts: ProvisionOptions, onStep?): Promise<{ ip?: string }>; // tools/install.sh
export function unprovision(panel: PanelShell, onStep?): Promise<void>;                                     // install.sh --undo
```
`provision/switch.ts`: when Android refuses the APK over another signing key (a debug build), `provision`
asks `opts.confirmSwitch(plan)` and then uninstalls + installs, carrying the app's data and Wi-Fi over
with adb root (panel side: `tools/install/s1kiosk-switch.sh`, same as install.sh). `unprovision` disables
instead of uninstalling when S1 Kiosk saved the panel's Wi-Fi (Android forgets it with the app).
Wake-word models are not pushed from the browser (CC BY-NC-SA, and GitHub sends no CORS headers);
the kiosk app fetches them on the panel.

## `assets/` — pinned blobs
`scripts/stage-assets.mjs` (`npm run stage`) copies them to `public/assets/blobs/` and writes
`public/assets/manifest.json` (`{ name, path, sha256, size, license, source }`; both gitignored).
`new Assets().get(name)` downloads a blob and checks its SHA-256, refusing on a mismatch.

## `app/` — the page (owner: main session)
- `flow.ts`: `InstallFlow` = `fel-install.sh` steps 1-9 without the DOM (`bootFromFel`, `connectFastboot`,
  `install`, `connectAdb`, `setUpApp`) + `restoreFromBackup` (= `--undo`). USB comes in through `Devices`,
  so `test/flow.test.ts` runs it end to end on simulated hardware with the real 4.5.80 partitions.
- `backup.ts`: backups in IndexedDB + a STORED zip (`zip.ts`) with a `SHA256SUMS` the CLI's `--undo` accepts.
- `ui.ts`: renders design E (`mockup/option-e.html`) from the flow's events.

## Running it
```
npm install && npm run stage && npm run dev     # http://localhost:5173 (a secure context)
npm test                                        # goldens: S1_WEB_GOLDEN=<dir>, else skipped
npm test -w @glassworks/bootpatch               # one package (or: cd packages/bootpatch && npm test)
```
