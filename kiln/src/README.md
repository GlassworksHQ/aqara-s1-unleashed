# Web installer: module contracts

The design is in `docs/research/web-installer.md`, and the reference flow in `tools/install/fel-install.sh` +
`tools/install.sh`. Every module is plain TypeScript (ES2022, no framework). Modules talk to each
other only through the exports below. `app/` is the only module that knows about the UI.

`kiln/` is an npm workspace. The protocol modules are packages in `packages/`, each with its own
tests: `@glassworks/fel`, `@glassworks/fastboot`, `@glassworks/adb`, `@glassworks/bootpatch`,
`@glassworks/setup-channel` and `improv-serial`. `@glassworks/kiln-core` (`packages/kiln-core`) is the
step engine, and the S1's part is its recipe, `@glassworks/aqara-s1-plus-installer`
(`frames/aqara-s1-plus/installer`). The page (`app/`, `assets/`) stays in `src/` and imports them all
by package name.

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

## `adb` (`packages/adb`) + the recipe's `provision.ts` — after first boot (owner: main session)
```ts
export function loadOrCreateAdbKey(storage?: KeyStorage): Promise<AdbKey>;  // { publicKey: 'QAAAA... name', store } (IndexedDB)
export interface PanelShell { run(cmd): Promise<{ out: string; code: number }>; push(path, data, mode?): Promise<void> }
export class Panel implements PanelShell { static connect(key, device?): Promise<Panel>; static known(): Promise<USBDevice[]> }
export function provision(panel: PanelShell, opts: ProvisionOptions, onStep?): Promise<{ ip?: string }>; // tools/install.sh
export function unprovision(panel: PanelShell, onStep?): Promise<void>;                                     // install.sh --undo
```
The recipe's `switch.ts`: when Android refuses the APK over another signing key (a debug build), `provision`
asks `opts.confirmSwitch(plan)` and then uninstalls + installs, carrying the app's data and Wi-Fi over
with adb root (panel side: `tools/install/s1kiosk-switch.sh`, same as install.sh). `unprovision` disables
instead of uninstalling when S1 Kiosk saved the panel's Wi-Fi (Android forgets it with the app).
Wake-word models are not pushed from the browser (CC BY-NC-SA, and GitHub sends no CORS headers);
the kiosk app fetches them on the panel.

## `assets/` — pinned blobs
`scripts/stage-assets.mjs` (`npm run stage`) copies them to `public/assets/blobs/` and writes
`public/assets/manifest.json` (`{ name, path, sha256, size, license, source }`; both gitignored).
`new Assets().get(name)` downloads a blob and checks its SHA-256, refusing on a mismatch.

## `kiln-core` (`packages/kiln-core`) — the step engine (owner: main session)
```ts
export class InstallFlow<A, O, R> {        // fel-install.sh steps 1-9 without the DOM
  constructor(recipe: Recipe<A, O, R>, deps: FlowDeps<A>, ui: FlowUi);
  bootFromFel(fel: FelDevice): Promise<void>;
  connectFastboot(): Promise<Fastboot>;
  install(fb: Fastboot): Promise<{ meta: BackupMeta; files: BackupFile[]; bootSha256: string }>;
  connectAdb(timeoutMs?: number): Promise<PanelShell>;
  setUpApp(panel: PanelShell, opts: O): Promise<R>;
}
export function restoreFromBackup(plan: BackupPlan, fb: Fastboot, files: Map<string, Uint8Array>, log, onProgress?): Promise<void>; // --undo
export interface FlowUi { log; progress(p: Progress); partition(part, state, info); ask(kind, action, signal) }
export interface Recipe<A, O, R> {
  fel: { checkChip(fel): Promise<string>; loader: A[]; boot(fel, loader, onProgress): Promise<void> };
  checkLayout(fb: Fastboot): Promise<string>;           // throws on the wrong device, returns the log line
  backup: BackupPlan;                                    // parts, restore, idPrefix, db, isPatched(boot)
  patching: string;
  patchBoot(ctx: PatchContext<A>): Promise<Uint8Array>;
  writesBeforeBoot(ctx: PatchContext<A>): Promise<FlashStep[]>;  // InstallFlow writes boot after these
  setUp(panel: PanelShell, opts: O, ctx: SetUpContext<A>): Promise<R>;
}
```
InstallFlow owns the order, the progress, the backup before any write (boot read twice), the read-back
of every write, and boot last. Whatever the recipe says, it refuses:

- before touching the device, a backup plan that doesn't read `boot` and every `restore` part, or has
  no `isPatched`;
- before writing, a recipe step that writes boot, and a patch that gives a stock boot back unchanged
  (the same boot back is fine on a re-run, when `backup.isPatched` says it was patched already);
- on undo, a backup that lacks boot or any `restore` part, or whose boot `isPatched`.

kiln-core doesn't depend on bootpatch: what "patched" means is the recipe's. USB comes in through
`Devices`. The S1's end-to-end test is `kiln/test/flow.test.ts`: the S1 recipe on simulated hardware
with the real 4.5.80 partitions. kiln-core's own `test/flow.test.ts` checks the rules above with a
made-up frame. `backup.ts`: backups in IndexedDB + a STORED zip (`zip.ts`) with a `SHA256SUMS` the
CLI's `--undo` accepts. kiln-core names no frame: its `no-frame-names` test fails on any S1 name.

## The S1 recipe (`frames/aqara-s1-plus/installer`)
`s1Plus` in `src/recipe.ts`: the A133 check and our U-Boot over FEL, the S1 Plus partition layout,
the backup and restore lists, `s1Overlay` and the vbmeta and splash writes, and setup through
`provision.ts` (`PKG`, `DEBLOAT`, Aqara's launcher and services). Also `switch.ts` and `improv.ts`
(the app's Improv socket). It sits outside `kiln/`, so npm hoists its dependencies where Node's lookup
from `frames/` doesn't reach: `vite.config.ts` (`resolve.dedupe`) and `tsconfig.base.json` (`paths`)
resolve them from `kiln/`, and its `test` and `typecheck` scripts run kiln's vitest and tsc.

## `app/` — the page (owner: main session)
- `ui.ts`: renders design E (`mockup/option-e.html`) from the flow's events, with the S1 recipe.

## Running it
```
npm install && npm run stage && npm run dev     # http://localhost:5173 (a secure context)
npm test                                        # goldens: S1_WEB_GOLDEN=<dir>, else skipped
npm test -w @glassworks/bootpatch               # one package (or: cd packages/bootpatch && npm test)
```
