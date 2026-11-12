# Agent guide: the web installer (`kiln/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). The module contracts are in
[`src/README.md`](src/README.md), and the design with its verified sources is in
[`../docs/research/web-installer.md`](../docs/research/web-installer.md).

## What it is

Plain TypeScript (ES2022, no framework), built with Vite, running over WebUSB in Chrome or Edge.
It does in the browser what the CLI does in `tools/install/fel-install.sh` and `tools/install.sh`:
FEL boot → our U-Boot's fastboot → backup → byte-exact Magisk patch → flash and read back → adb
provisioning. **The CLI is the reference.** When the two disagree, fix the browser side, or change
both in the same PR.

## Rules

- **The boot patch must be byte-identical to `tools/root/patch-boot-magisk.sh`.** That is why
  `bootpatch/wasm/` compiles magiskboot's exact compression crates (`zlib-rs` level 9, `lzma-rust2`
  with the ARM Thumb BCJ filter). Don't swap in `CompressionStream`, pako or liblzma. If you change the
  overlay, change the shell script too and regenerate the goldens (`S1_WEB_GOLDEN=<dir> npm test`;
  without it the golden tests are skipped).
- **Backups come before writes, and writes are verified by read-back.** Keep the refusal points
  from the CLI: wrong SoC id, unexpected partition layout, a backup `boot` that is already patched.
- Assets (U-Boot, Magisk, the APK) are pinned by SHA-256 in `assets.lock.json` and checked on download.
  Bump the hash and the source together.
- Only `app/` touches the DOM. Other modules talk through their exports, so `test/flow.test.ts` can
  run the whole flow on simulated hardware (`test/fakes.ts`). New USB behaviour needs a fake for it.
- `oem run:` commands must fit fastboot's 64-byte command limit, prefix included.
- The installer will become **Kiln**, generic across frames. Keep S1-specific constants (`A133`,
  partition names, VID:PID) in one place rather than inline.

## Commands

```sh
npm install && npm run stage   # stage pinned blobs into public/assets (gitignored)
npm run dev                    # http://localhost:5173, a secure context, so WebUSB works
npm run typecheck && npm test && npm run build
```

## Screenshots

UI changes need before and after screenshots in the PR. The dev server has simulated states that
need no panel: `?demo=wifi` and `?demo=wifi-fail` (see `demo()` in `src/app/ui.ts`). Add a demo
state when you add a screen, so the next person can capture it too. The images in
`docs/img/installer/` come from these states. Update them, as `.webp`, when a screen they show
changes.
