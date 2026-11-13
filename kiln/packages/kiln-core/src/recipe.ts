// What one frame tells InstallFlow: how to recognise the device, what to back up, what to write,
// and how to set the app up. InstallFlow owns the order, the progress, the backup before any write,
// the read-back of every write, and that boot is written last.
import type { PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';

/** `A`: the frame's asset names. `O`, `R`: what its app setup takes and returns. */
export interface Recipe<A extends string, O, R> {
  fel: {
    /** Throws unless the chip is this frame's (nothing written yet). Returns the line to log. */
    checkChip(fel: FelDevice): Promise<string>;
    /** Fetched, in this order, before `boot` runs. */
    loader: readonly A[];
    /** Starts the loader from RAM; it then brings up fastboot by itself. Writes nothing. */
    boot(fel: FelDevice, loader: Uint8Array[], onProgress: (x: number) => void): Promise<void>;
  };
  /** Throws unless fastboot shows this frame's partition layout. Returns the line to log. */
  checkLayout(fb: Fastboot): Promise<string>;
  backup: BackupPlan;
  /** The patch step's subtitle while it works. */
  patching: string;
  /** The new boot image, made from the backed-up one. Unchanged is refused unless `backup.isPatched` said it was patched already. */
  patchBoot(ctx: PatchContext<A>): Promise<Uint8Array>;
  /** Other partitions to write, in order. Never boot: InstallFlow writes it after them, once. Each must be in `backup.parts` and `backup.restore`. */
  writesBeforeBoot(ctx: PatchContext<A>): Promise<FlashStep[]>;
  /** Installs and sets up the app over adb once the patched Android is up. */
  setUp(panel: PanelShell, opts: O, ctx: SetUpContext<A>): Promise<R>;
}

export interface BackupPlan {
  /** Read in this order and kept. Must include `boot` (read twice and patched) and every `restore` part. */
  parts: readonly string[];
  /** What undo writes back, in this order: boot and everything `writesBeforeBoot` writes. Undo refuses a backup that lacks any of these. */
  restore: readonly string[];
  /** Backup ids, and the zip's name, are `<idPrefix>-YYYYMMDD-HHMMSS`. */
  idPrefix: string;
  /** The IndexedDB database that keeps backups. It holds people's only copy of their original software: never rename it. */
  db: string;
  /**
   * Whether a boot image is already patched by this frame's installer (a re-run). Its backup isn't the
   * original software: undo refuses it, and a patch may give the same boot back.
   */
  isPatched(boot: Uint8Array): Promise<boolean>;
  /**
   * Restore parts besides boot that an install rewrites, each with a test for this installer's own
   * copy (`what` names the part for people, e.g. "splash"). Undo refuses a backup where one is ours:
   * that part isn't the original, and undo would put ours back as if it were.
   */
  ours?: Readonly<Record<string, { what: string; is(data: Uint8Array): Promise<boolean> }>>;
}

export interface PatchContext<A extends string> {
  /** The backup just made, by partition. */
  backup: ReadonlyMap<string, Uint8Array>;
  asset(name: A): Promise<Uint8Array>;
  /** This browser's adb key, as the line adb_keys takes (without the newline). */
  adbPublicKey: string;
  /** Branded splash and boot animation; false is BRANDING=none in the CLI. */
  branding: boolean;
  log(line: string): void;
}

/** `what`: the title the UI shows while it writes. */
export interface FlashStep { part: string; data: Uint8Array; what: string }

export interface SetUpContext<A extends string> {
  asset(name: A): Promise<Uint8Array>;
  hasAsset(name: A): Promise<boolean>;
  /** A step heading, or with an empty step, a detail line under it. */
  step(step: string, detail?: string): void;
}
