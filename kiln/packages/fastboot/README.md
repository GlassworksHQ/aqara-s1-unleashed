# @glassworks/fastboot

Fastboot over WebUSB for every frame Kiln installs. It is a fork of
[fastboot.js](https://github.com/kdrag0n/fastboot.js) by Danny Lin (MIT, [LICENSE](LICENSE)), kept in
this monorepo and changed for Kiln (#111). It has two kinds of methods:

| For | Methods | Bootloader |
|---|---|---|
| Every frame | `getvar`, `download`, `flashBlob` (raw or sparse, split at `max-download-size`), `bootBlob` (`fastboot boot`) | Any fastboot |
| Our U-Boot only (the S1) | `oemRun`, `partitionSize`, `readPartition` (`oem run` + `upload`, patch 0002), `flash`, `flashVerified` (write, read back, compare), `reboot` | `tools/fel/uboot` |

Kiln's S1 install uses only the second row. `flashBlob` is for frames whose bootloader cannot read
partitions back, such as the Mirror's Qualcomm LK (#41).

## Upstream

| | |
|---|---|
| Repository | https://github.com/kdrag0n/fastboot.js |
| Commit | `5b613332aa9d66cca5bebb49f147cd084a76c464` (2023-10-17, after npm `android-fastboot` 1.1.3) |
| Taken | `src/fastboot.ts`, `src/sparse.ts`, `src/common.ts`, `LICENSE` |
| Left out | `src/factory.ts` (factory zip flashing) and its `@zip.js/zip.js` 2.2.27 dependency. No frame flashes factory zips |

The first commit of PR #111 imports these files byte for byte. The commits after it are our changes.
`sparse.ts` and `common.ts` keep upstream's formatting, so their upstream changes apply with few
conflicts. `fastboot.ts` is mostly ours: it keeps upstream's `flashBlob` and `bootBlob`, on our
transport.

To take later upstream changes:

1. In a clone of upstream, list them: `git log 5b61333..master -- src/fastboot.ts src/sparse.ts src/common.ts`.
2. Apply each one by hand to the files here. `sparse.ts` and `common.ts` usually take
   `git diff 5b61333 <commit> -- src/sparse.ts | patch -p1 -d kiln/packages/fastboot` directly.
3. Update the commit in the table above.
4. Run the S1 wire checks below. They must not change.

## What the fork does differently

On the S1 the fork sends exactly what `@glassworks/fastboot` sent before it (checked on 2026-11-12, see
[S1 wire checks](#s1-wire-checks)). Compared with upstream:

| What | Upstream | Fork | Why |
|---|---|---|---|
| USB transport | Opens the device itself, resets it, claims interface 0, reconnects on its own | `WebUsbBulk` from `@glassworks/fel`, the same as FEL | One transport for the fakes the tests use. The S1 flow already handles reconnects. No `reset()` |
| USB filter | Interface class `ff/42/03` | The frame's VID/PID (`FASTBOOT_FILTER` for our U-Boot) | Kiln asks for the frame's device by name |
| Bytes per USB transfer | 16 KiB | 1 MiB | What the S1 has always used. The Mirror's value is open (#41) |
| Reply read size | 64 bytes | 256 bytes | Unchanged from our client. Replies are 64 bytes at most |
| `INFO`, `TEXT` replies | `INFO` added to the result text. `TEXT` is an error | Both go to `log` | Unchanged from our client |
| `getvar` | Trimmed, `FAIL` returns null | Trimmed, `FAIL` throws. `flashBlob` treats `FAIL` as null for `has-slot`, `is-logical` and `max-download-size` | The S1 recipe expects a throw. Trimming changes nothing on our U-Boot, whose values have no spaces (`drivers/fastboot/fb_getvar.c:192,276`, read 2026-11-12) |
| Timeouts | 10 s for `getvar`, none otherwise | Every transfer: 10 s for `getvar`, 120 s for the rest. `flash:` in `flashBlob`: 120 s plus 1 s per 4 MiB | A stuck panel shows an error instead of hanging |
| Non-numeric `max-download-size` | NaN | 512 MiB default | Bug fix |
| A sparse piece that fills the limit | Up to 24 bytes over `max-download-size` | Within it | Bug fix: LK refuses the download ("data too large") |
| `max-download-size` not a multiple of the block size | Raw chunks cut mid-block | Cut at a block boundary | Bug fix: LK rejects the chunk |
| Raw image that is not whole blocks, larger than the limit | Fractional block count | Refused | Bug fix |
| Reading a Blob | `FileReader` | `blob.arrayBuffer()` | Works in Node, so the tests run |
| `download` and `flash` before the image is sent | Not checked | Checked against the buffer size and the partition size | Unchanged from our client |

## S1 wire checks

- `test/fastboot.test.ts` (`the S1 wire`) pins every USB call of `flashVerified` and `reboot`: size,
  timeout and order. It runs in CI.
- `kiln/test/wire-trace.test.ts` records every USB call of a full S1 install and undo on the simulated
  U-Boot with the real 4.5.80 partitions. It needs the partition goldens, so it runs only on a machine
  that has them. Run it before and after a change, then compare:

  ```sh
  cd kiln
  S1_WIRE_TRACE=/tmp/before.txt npx vitest run test/wire-trace.test.ts   # on main
  S1_WIRE_TRACE=/tmp/after.txt npx vitest run test/wire-trace.test.ts    # on the branch
  cmp /tmp/before.txt /tmp/after.txt
  ```

  The trace has hashes of the panel's partitions. Don't commit it.

## For the Mirror (MSM8916, #41)

`test/fake-lk.ts` models Qualcomm LK as read in lk2nd: `max-download-size` as `"\t0x%x"`, "data too
large" above it, `FAIL` for unknown variables, and sparse writes. `test/flash-blob.test.ts` flashes raw
and sparse images through it. Nothing here has run on a real Mirror. What #41 still needs:

1. The Mirror's USB IDs, as a filter for `Fastboot.request` and `Fastboot.known`.
2. The transfer size: 1 MiB or upstream's 16 KiB, measured on the Mirror (test plan step 5).
3. Whether the `flash:` timeout is long enough for a 256 MiB piece on its eMMC.
4. A verify step without `readPartition` (LK has no `upload`): Kiln-core calls `flashVerified` today,
   so the recipe needs another step, such as a hash over adb after first boot.
5. Large images from disk: `flashBlob` takes a `File`, but Kiln's asset path passes `Uint8Array`s, and
   the release download would need OPFS and a streaming SHA-256.
6. LK's exact replies for a refused partition and for the `has-slot`, `is-logical` and `current-slot`
   variables `flashBlob` asks for.
