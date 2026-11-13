# Mirror Android: can the lululemon Mirror run Android 8 (API 26) or newer?

**Status (2026-11-13): dead end for now (desk research).** No path to an API 26+ image is open
today. The Mirror's signed boot image fixes the kernel, `init`, the SELinux policy and `fstab` at
Android 6, and only `system` and `userdata` can be written. One untested way round it exists: the
stock kernel loads unsigned modules, so a kexec module might chainload our own kernel. That is a
research spike, not a plan. Ship the Mirror on the frozen engine (#39) and run the read-only probes
below.

Device: lululemon Studio Mirror, original Qualcomm board (`MIR63A0-00-P1`), stock software 1.x
(Android 6.0.1, build `IFC-6309-2.0-MIR`). Tools: none on the device. Public sources only, GitHub
API and `curl` for the files quoted. Part of #39; ties to #40 (blobs) and #41 (fastboot).

## Question

GeckoView 145 and newer need API 26 (Android 8). The newest GeckoView for Android 6 is 143, frozen
since October 2025 (#39). The maintainer decided on 2026-11-13: a frozen engine is acceptable as a
fallback, but first find out whether we can build a newer Android (API 26+) for the Mirror, so it
runs the current engine.

That needs four things: a kernel that Android 8+ accepts, a ramdisk with Android 8+ `init`, vendor
code for the GPU, display, Wi-Fi, audio and camera, and a way to put it all on the device without
bricking it.

## What we tried

All desk research on 2026-11-13. Nothing touched a device. Claims are marked **verified** (we ran
it), **read** (from a source, cited) or **untested**.

1. **Read the issues and the frame.** #39, #40, #41, #42 with comments, and
   `frames/lululemon-mirror/design/frame.json`. #40 records the partition list from the maintainer's
   unit and that only `system` and `userdata` can be flashed.
2. **Read what other Mirror projects found about the boot chain.**
   `repairman29/lululemon-mirror-repurpose` `docs/os-replacement.md` (commit `fa0f20e`, 2026-08-25)
   and `docs/recovery.md`, `docs/supported-devices.md` (`2f2f31c`).
   `Onemoremichael/lulu-mirror-software-only` `docs/BUILD.md`, `docs/COMPATIBILITY.md`,
   `docs/PROVENANCE.md` (`efddcbd`, 2026-10-21).
3. **Read a config for the stock kernel.** `lulu-mirror-software-only` publishes
   `build/kernel.config`. `PROVENANCE.md` calls it the "observed kernel configuration for
   compatibility build", taken from `running-kernel-build.config`. It is the config that project
   builds its `wlan.ko` against, not a dump we took from a unit, so treat each line as **read, not
   verified** until probe 2 below confirms it. We grepped it for the options that decide the paths
   below:
   ```
   $ grep -E '^(# )?CONFIG_(LOCALVERSION|IKCONFIG_PROC|MODULES|MODULE_SIG|KALLSYMS_ALL|ANDROID_BINDER_IPC|ANDROID_BINDER_IPC_32BIT|SECCOMP_FILTER|PID_NS|USER_NS|HOTPLUG_CPU|MSM_SCM|MSM_KGSL|FB_MSM_MDSS|SECURITY_SELINUX_DEVELOP)=?' kernel.config
   CONFIG_LOCALVERSION="-perf-gf46dad5260f"
   CONFIG_IKCONFIG_PROC=y
   CONFIG_MODULES=y
   # CONFIG_MODULE_SIG is not set
   CONFIG_KALLSYMS_ALL=y
   CONFIG_ANDROID_BINDER_IPC=y
   # CONFIG_ANDROID_BINDER_IPC_32BIT is not set
   CONFIG_SECCOMP_FILTER=y
   # CONFIG_PID_NS is not set
   # CONFIG_USER_NS is not set
   CONFIG_HOTPLUG_CPU=y
   CONFIG_MSM_SCM=y
   CONFIG_MSM_KGSL=y
   CONFIG_FB_MSM_MDSS=y
   CONFIG_SECURITY_SELINUX_DEVELOP=y
   ```
   The file has no `CONFIG_KEXEC`, `CONFIG_ANDROID_BINDER_DEVICES` or `CONFIG_ARM_PSCI` line at
   all. arm64 kexec only reached Linux in 4.8 ("arm64/kexec: Add core kexec support",
   2016-06-27), so a 3.10 arm64 kernel has no such option. The only LVDS panel driver enabled is
   `CONFIG_FB_MSM_LVDS_CHIMEI_WXGA_PANEL`, a 1280×800 panel, not the Mirror's 1920×1080 one. So
   this config says little about the display path.
4. **Read the teardown** (mdthats.me, "Smart Mirror Teardown", 2026) for the chips.
5. **Read what exists for msm8916.** The LineageOS org's `android_device_wileyfox_crackling` and
   `android_kernel_cyanogen_msm8916` (branches, `Makefile`, `lineageos_crackling_defconfig`), XDA
   threads, the upstream kernel's `arch/arm64/boot/dts/qcom/msm8916.dtsi` (Linux 7.4-rc2), and
   `msm8916-mainline/lk2nd` (release 23.1, 2026-07-14).
6. **Read the blob licences** in linux-firmware `WHENCE`.

What it rules out is in the next section.

## Result

### 1. The hardware

| Part | What it is | Source, how sure | Needs proprietary code on Android |
|---|---|---|---|
| SoC | Qualcomm Snapdragon 410E, **APQ8016E** (the modem-less MSM8916), 4× Cortex-A53 at 1.2 GHz | Teardown; `msm8916_64` build. Read | — |
| PMIC | PM8916 | Teardown. Read | No |
| RAM / eMMC | **1 GB** / 8 GB. The teardown guesses a Micron multi-chip package (RAM and eMMC), from its marking | `supported-devices.md`; teardown. Read; the package is the teardown's guess | — |
| Reference board | Inforce IFC6309 (build `IFC-6309-2.0-MIR`). The newest Inforce Android BSP we found for it is **7.1.1 on kernel 3.10.49** (V3.0, 2018), behind a login | Inforce/Penguin release; `os-replacement.md`. Read | — |
| Stock kernel | 3.10.49, `-perf-gf46dad5260f`, arm64. Stays on the unit; the community image keeps it | `COMPATIBILITY.md`; `kernel.config`. Read | — |
| Userspace ABI | arm64-v8a with 32-bit secondary (community image) | #40. Verified 2026-11-12 | — |
| GPU | Adreno 306 (`qcom,adreno-306.0` upstream) | Upstream `msm8916.dtsi`. Read | **Yes** on the stock kernel (KGSL, Adreno GLES blobs). No on mainline (Mesa freedreno) |
| Display | Samsung LTI400HN01, 40" 1920×1080 LVDS panel, used portrait. Downstream `FB_MSM_MDSS`. Whether a separate LVDS bridge chip sits between the SoC and the panel, and which, is not known: the teardown names none | Teardown; `kernel.config`. Read | The display HAL (`hardware/qcom/display`) is open source. The LVDS bridge has no known mainline driver. **Untested** which chip it is |
| Wi-Fi / BT | WCN3660B on the SoC's WCNSS | Teardown. Read | Firmware (`wcnss`) in the unit's `modem` partition; `wlan.ko` (prima, GPL) built from source; NV calibration data |
| Audio | SoC codec; TI TPA3116D2 class-D amp; mics on a Conexant CX20921 far-field board (two mics) | Teardown. Read | The audio HAL is open source; ACDB calibration libraries are not. How the CX20921 connects is **untested** |
| Camera | OmniVision OV5640 | Teardown. Read | **Yes** on Android: Qualcomm's `mm-camera` plus a sensor library (the community used a Quectel SDK, #40). Mainline has `ov5640` and CAMSS |
| Video decode | Venus | Upstream `msm8916.dtsi`. Read | Firmware (`venus`) in the `modem` partition; OMX libraries in CAF are open source |
| Secure boot | Fused OEM key. LK reports `Device unlocked: false`, `get_unlock_ability: 0` | `os-replacement.md`, `recovery.md`. Read | — |

### 2. What exists already

| What | Android | Kernel | Gaps | Source |
|---|---|---|---|---|
| Stock / community image | 6.0.1 (API 23) | 3.10.49 stock | — | #40, `COMPATIBILITY.md`. Read |
| Inforce IFC6309 BSP | 7.1.1 (API 25), the newest we found. `COMPATIBILITY.md` calls Android 7.x "an investigation target, not … an established ceiling" | 3.10.49 | Not public (TechWeb login). Signed with Inforce's key, not necessarily the Mirror's. Still below 26 | Inforce release; `os-replacement.md`. Read |
| LineageOS, Wileyfox Swift (`crackling`, msm8916, arm64) | Official branches `lineage-15.1`, `16.0`, `17.1` (Android 8.1 to 10) | **3.10.108**, with `CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"` backported | Uses Android 6/7-era Qualcomm blobs with shims. `lineage-17.1` is its last branch; LineageOS no longer builds it | GitHub, LineageOS org (branch list). Read |
| Unofficial LineageOS 18.1 (Android 11) | `crackling`, `a5lte` (Galaxy A5 2015) | Downstream 3.10 kernels | "Testing" builds, GApps don't fit (crackling) | XDA threads. Read through search results |
| Mainline Linux | — | 7.x; upstream `msm8916.dtsi` has MDP5/DSI, Adreno 306, Venus, CAMSS and WCNSS nodes; `apq8016-sbc.dts` (DragonBoard 410c); about 50 msm8916 and apq8016 device-tree files | No LVDS bridge for this board, no Mirror DTS. `msm8916-mainline` keeps a fork for many phones | Upstream tree; `msm8916-mainline/linux`. Read |
| Android on mainline | AOSP on the DragonBoard 410c with freedreno and drm_hwcomposer (Collabora, 2017–2018). SoMainline documents the `freedreno` + `gbm_gralloc` + `drm_hwcomposer` setup | Mainline | Camera, audio and HW video decode not covered. Nobody has published a maintained msm8916 Android-on-mainline build that we found | Collabora blog; SoMainline docs. Read |
| lk2nd | Secondary bootloader for msm8916, installed in `boot` | — | The Mirror's LK refuses it (below) | `msm8916-mainline/lk2nd`. Read |

So Android 8 to 11 does reach msm8916, but only with a **rebuilt** 3.10 kernel (a backported
binder driver with its three devices, other Oreo backports) and a matching ramdisk. All of that lives in `boot`.

### 3. The gate: the boot image is signed and stays Android 6

This decides every path, so the evidence is listed first.

| Fact | Source, how sure |
|---|---|
| `fastboot boot` of the official lk2nd image was uploaded and refused: `FAILED (remote: 'bootimage: incomplete or not signed')` | `os-replacement.md`, run by that project on 2026-08-23. Read |
| EDL (`05c6:9008`) is reachable from adb or a force-USB switch. The only public Firehose loader for this chip ID is signed with a different key. PBL stopped after its signed header | `os-replacement.md`. Read |
| Only `system` and `userdata` are writable over fastboot | #40, #41, `frame.json`. Read |
| The community image keeps the stock `boot`, `recovery` and `aboot` | #40; `lulu-mirror-software-only` README. Read |
| On Android 6, `init`, `init.rc`, `sepolicy`, `file_contexts` and `fstab` sit in the boot ramdisk. `/system/etc/init` arrived in Android 7 | AOSP layout. Read, general knowledge, not checked on this ramdisk |

What follows from it (read, reasoned from the table, not tried):

- A newer `system` cannot boot under Android 6 `init` and its SELinux policy. Android 8+ needs its
  own `init`, `hwservicemanager` and policy, and the stock `fstab` has no `/vendor`.
- The stock kernel has no `hwbinder` or `vndbinder` (no `CONFIG_ANDROID_BINDER_DEVICES`). Android 8
  vendor code talks over `/dev/hwbinder`. The driver itself is short too, not just the device
  nodes. HIDL uses scatter-gather transactions (`BC_TRANSACTION_SG`), added upstream on 2017-02-10
  (`7980240b6d63`, Linux 4.11). The 3.10 msm8916 kernel's `binder.h` on `cm-13.0` doesn't have it.
  The `lineage-17.1` branch does, so LineageOS backported the driver. The protocol version is not
  the gap: with `CONFIG_ANDROID_BINDER_IPC_32BIT` unset, 3.10's `binder.h` already defines
  `BINDER_CURRENT_PROTOCOL_VERSION 8`, the 64-bit ABI that Android 8 requires.
- An Android userspace in a container (the Waydroid or Anbox idea) needs PID namespaces. The stock
  kernel has `# CONFIG_PID_NS is not set`. That rules it out.

**The one lever: unsigned kernel modules (read, untested).** `CONFIG_MODULE_SIG` is not set, and
the community image already ships a `wlan.ko` built from source against the stock kernel, which
loads (Wi-Fi works). So code we put in `system` can run inside the signed kernel. Combined with
`CONFIG_KALLSYMS_ALL=y`, a **kexec module** could load our own kernel and ramdisk after the stock
boot, the way `kexec-kindle-mt8512` boots any kernel on a locked Kindle and `fabianishere/kexec-mod`
does for arm64. The boot chain is never written.

First, the module has to load at every boot, as root, before anything else needs the screen. That
means an `insmod` that the stock `init.rc` already runs from `/system`, or a `/system` binary or
script that stock `init` starts as root. There are two candidates, both **untested** on this
ramdisk:

- Qualcomm (CAF) `init.qcom.rc` files define `qcom-post-boot`, which runs
  `/system/etc/init.qcom.post_boot.sh` as `user root`. We read this in older CAF trees, not in the
  Mirror's own `init.qcom.rc`. The community build ships and edits that script (`build/prepare.py`).
- The Wi-Fi HAL loads `wlan.ko` from `/system/lib/modules/pronto/` (`build/container-build.sh`).
  That load comes late, from the framework, under its SELinux domain.

Probe 9 below shows which path exists.

Why it is a spike and not a plan (all untested):

- arm64 kexec does not exist in 3.10. The module would backport the 4.8+ relocation and
  `cpu_soft_restart` code to 3.10 by hand.
- The stock kernel brings up secondary CPUs through Qualcomm SCM calls (`CONFIG_MSM_SCM=y`, no
  `ARM_PSCI` in its config). Upstream `msm8916.dtsi` instead uses PSCI over SMC
  (`enable-method = "psci"`, `method = "smc"`), which the TZ firmware provides. So the spike must
  answer two things: can the module park the secondary CPUs so the new kernel can start them, and
  does PSCI still answer after kexec from a kernel that never used it? lk2nd exists partly
  because of this kind of CPU hand-off.
- The new kernel must re-initialise the display, the PMIC and the LVDS bridge that the stock kernel
  already set up. Wrong regulator settings on a 40" panel are a hardware risk, not just a crash.
- Every boot runs the stock kernel and Android 6 `init` first, then kexec. Boot takes about twice
  as long.

### 4. The paths

All need the kexec lever first, because none can replace `boot` any other way. Effort is rough,
for one experienced developer, after kexec works.

| | (a) Port a msm8916 LineageOS tree | (b) GSI on a Treble vendor | (c) AOSP on mainline | (d) postmarketOS + Linux kiosk |
|---|---|---|---|---|
| Kernel | Downstream 3.10 rebuilt with Oreo backports (from `crackling`'s kernel or CAF), plus the Mirror's board DT and LVDS panel from the live device tree | Same as (a) | Mainline 7.x with a new Mirror DTS and an LVDS bridge driver | Mainline, same as (c) |
| Android | LineageOS 15.1 to 17.1 (API 27 to 29) | Any GSI | AOSP 12+ with Mesa | None |
| Proprietary userspace | Adreno GLES, camera, ACDB audio, keymaster, from LA.BR.1.2.7 with shims | Same as (a), moved into `/vendor` | **None needed** for GPU and display; firmware only. Camera via libcamera, video decode immature | Firmware only |
| Extra blockers | 1 GB RAM. Google steers ≤1 GB devices to the Android Go configuration (`ro.config.low_ram` and friends); for our own build it is a choice, not automatic | No `vendor` partition; GPT changes need EDL or `boot` access; `system` is 1228 MB, smaller than most recent GSIs | No mainline LVDS bridge driver; audio routing; HW video decode in Android on V4L2 is not mature; 1 GB RAM | Not an Android frame (below) |
| Effort | Spike + 1–3 months | More than (a), for no gain | Spike + 3–6 months or more | Spike + 1–2 months, but a different product |
| Brick risk | Low for storage: only `system` and `userdata` are written. Moderate hardware risk from a kernel driving the PMIC and panel | Higher: needs repartitioning | Same as (a) | Same as (a) |
| Way back | `fastboot flash system` with the community image (LK and `boot` are never written) | Repartitioning has no safe undo without EDL | Same as (a) | Same as (a) |

- **(b) is not viable.** Treble is (a) plus a `vendor` partition the Mirror doesn't have, and the
  GPT can't be changed without EDL. Some XDA guides repartition other phones, but they all had boot
  and recovery access.
- **(d) is out of scope.** It is the most upstream route and the one
  `lululemon-mirror-repurpose` recommends for itself. But a Glassworks frame is an Android device
  that runs the S1 Kiosk APK, installed by Kiln over fastboot and adb. A Linux kiosk (Cage, WPE or
  Chromium) would be a second runtime with none of the app's panes. It also hits the same boot gate:
  lk2nd can't be installed either.

### 5. Blobs: what we could ship, what must come off the device (ties to #40)

| Kind | Examples | Can we ship it? | Source, how sure |
|---|---|---|---|
| Remote-processor firmware | `wcnss`, `venus`, `mba`, modem | Not needed. The unit's own `modem` partition (`/firmware`) holds it and is never overwritten. linux-firmware also carries `qcom/apq8016/{mba,modem,wcnss}.mbn` as "Redistributable" under `LICENSE.qcom`, but those are DragonBoard builds. TZ checks firmware signatures against the fused key, so **untested** whether DragonBoard firmware loads on a Mirror | `WHENCE`; #40. Read |
| Adreno microcode | `a300_pm4.fw`, `a300_pfp.fw` | **Yes**: linux-firmware, "Redistributable", `LICENSE.qcom` | `WHENCE`. Read |
| GPL kernel code | `wlan.ko`, a kexec module, our kernel | Our own builds from source. Rule 4 says we don't ship GPL payloads, so this needs a decision, as #40 already asks for `wlan.ko` | #40. Read |
| Android vendor userspace | Adreno GLES, `mm-camera`, ACDB, QSEECom/keymaster, `thermal-engine` | **No known licence.** The community set is the DragonBoard LA.BR.1.2.7 BSP re-hosted without a licence; Quectel unknown. Must come from the user, and #40 found no stock source for it | #40. Read |
| Open-source HALs | `hardware/qcom/{display,media,audio,bt}` | Yes, Apache-2.0 (CAF / AOSP) | AOSP and CAF trees. Read, licence of each repo not rechecked |

Path (c) is the only one that needs no vendor userspace at all. That is its real advantage, and it
costs the most work.

### 6. Recommendation

**No-go on a modern Android image for now.** Ship the Mirror on the frozen engine: GeckoView
`143.0.20251003115653`, locked down to the HA origin, as #39 recommends.

| | Frozen engine (GeckoView 143 on Android 6) | Modern image (path a, after kexec) |
|---|---|---|
| Works today | Yes, Android 6 runs on the unit now | Only if an untested kexec module works on 3.10 arm64 |
| Engine fixes | None after 2025-10-14 | Current GeckoView |
| Effort | Low: one Gradle module (#39) | A spike of uncertain outcome, then 1–3 months |
| Blobs to ship | None beyond what the community image already needs | The same userspace blobs, plus shims |
| Risk to the unit | None new | Kernel driving the PMIC and LVDS panel; storage stays recoverable |
| RAM | 1 GB, Android 6 | 1 GB, Android 8.1+, probably with the Go low-RAM settings. Same pressure for GeckoView |

Reasons:

1. The boot chain is the blocker, not the blobs. `lululemon-mirror-repurpose` ran both entry
   points (`fastboot boot` and EDL), and both enforce the OEM key.
   `lulu-mirror-software-only` keeps the signed boot chain and says a newer Android "needs a
   separate compatibility project"; it did not test the entry points itself.
2. The newest vendor BSP we found for the reference board is Android 7.1.1 (API 25), still below
   what GeckoView needs.
3. The only crack, kexec from a module, has no prior art on a 3.10 arm64 Qualcomm kernel that we
   found. Every path above waits on it.
4. The frozen engine's risk can be contained (HA origin only). A modern image doesn't fix the 1 GB
   RAM limit either.

What would reopen it: a kexec spike that boots any newer kernel to a console on the Mirror, a
lululemon- or Inforce-signed boot image for API 26+, or a Firehose loader signed with the Mirror's
OEM key.

## How to reproduce

**Read-only probes on the maintainer's Mirror** (community image, adb as `shell`, no root, nothing
written). The S1 is plugged in too, so address the Mirror by transport: `adb devices -l`, then
`adb -t <n>`. Redact serials before pasting output anywhere.

| # | Command | What it settles |
|---|---|---|
| 1 | `adb -t <n> shell cat /proc/version` | The kernel is the same 3.10.49 `-perf-gf46dad5260f` as the published config |
| 2 | `adb -t <n> shell 'zcat /proc/config.gz \| grep -E "CONFIG_(MODULE_SIG\|KEXEC\|KALLSYMS_ALL\|ANDROID_BINDER_DEVICES\|PID_NS\|ARM_PSCI\|MSM_SCM)"'` | This unit's config matches `kernel.config` (`IKCONFIG_PROC=y`). SELinux may deny `shell`; note it if so |
| 3 | `adb -t <n> shell 'ls -l /dev/binder /dev/hwbinder /dev/vndbinder'` | Expect only `/dev/binder` |
| 4 | `adb -t <n> shell cat /proc/modules` | `wlan` listed: a module from our `system` runs in the signed kernel |
| 5 | `adb -t <n> shell getenforce` | Whether SELinux is enforcing on the community image |
| 6 | `adb -t <n> shell 'head -2 /proc/meminfo; cat /proc/device-tree/model; echo'` | RAM as the kernel sees it, and the board's DT model |
| 7 | `adb -t <n> shell 'find /proc/device-tree -iname "*lvds*" -o -iname "*panel*" -o -iname "*bridge*" \| head -40'` | Which LVDS bridge the board uses. Don't dump `chosen/bootargs`: it holds the serial |
| 8 | In fastboot (`frame.json`: open the back, press the button): `fastboot getvar all` | Already #41 step 2. Note `secure`, `unlocked`, `version-bootloader`. Redact `serialno` |
| 9 | `adb -t <n> shell 'getprop \| grep -e init.svc.qcom-post-boot -e wlan.driver; grep -n -e post_boot -e insmod /init*.rc'` | Whether stock `init` runs `init.qcom.post_boot.sh` (and as which user), and any `insmod` it does from `/system`. The `grep` of `/init*.rc` may be denied to `shell`; note it if so |

Then, only if probes 2, 4 and 9 confirm unsigned modules, no kexec and a root load path at boot, decide whether to fund a
time-boxed kexec spike. It would write `system` (a test image with the module) and nothing else. The
way back is the published Glass Panes `system.img`, checked against its `SHA256SUMS`, and
`userdata` from a backup taken first.

## Open questions

- Does the stock kernel let `shell` read `/proc/config.gz` and `/proc/device-tree`? (Probes 2, 7.)
- Which LVDS bridge (if any) and panel timings does the board use? The published config only enables a
  1280×800 Chimei LVDS driver, so it doesn't answer this. The live device tree should.
- Can a kexec module park the secondary CPUs on this TZ, and does PSCI work after kexec? Only a
  spike answers it.
- Does stock `init` run anything from `/system` as root early enough to load the module? (Probe 9.)
- How does the CX20921 mic board connect (USB, I2S)? It matters for Assist on every path.
- Would GeckoView with the HA frontend fit in 1 GB on Android 6? This is the same question for the
  frozen engine, and nobody has measured it.
- `repairman29/lululemon-mirror-repurpose` describes authorised adb on stock `IFC-6309-2.0-MIR/329`
  units (`supported-devices.md`, `recovery.md`). If that holds, it reopens #40's option B (blobs from
  the user's own stock unit). Not checked here.

## Sources

All opened 2026-11-13.

- Issues #39, #40, #41, #42 in this repo, with comments; `frames/lululemon-mirror/design/frame.json`.
- `repairman29/lululemon-mirror-repurpose`: [`docs/os-replacement.md`](https://github.com/repairman29/lululemon-mirror-repurpose/blob/main/docs/os-replacement.md),
  [`docs/recovery.md`](https://github.com/repairman29/lululemon-mirror-repurpose/blob/main/docs/recovery.md),
  [`docs/supported-devices.md`](https://github.com/repairman29/lululemon-mirror-repurpose/blob/main/docs/supported-devices.md).
- `Onemoremichael/lulu-mirror-software-only`: [README](https://github.com/Onemoremichael/lulu-mirror-software-only),
  [`docs/BUILD.md`](https://github.com/Onemoremichael/lulu-mirror-software-only/blob/main/docs/BUILD.md),
  [`docs/COMPATIBILITY.md`](https://github.com/Onemoremichael/lulu-mirror-software-only/blob/main/docs/COMPATIBILITY.md),
  [`docs/PROVENANCE.md`](https://github.com/Onemoremichael/lulu-mirror-software-only/blob/main/docs/PROVENANCE.md),
  [`build/kernel.config`](https://github.com/Onemoremichael/lulu-mirror-software-only/blob/main/build/kernel.config).
- Teardown: [MDME, "Smart Mirror Teardown"](https://mdthats.me/projects/lululemon-smart-mirror-p1/).
- Inforce: [Android Nougat 7 for Inforce Snapdragon 410 platforms](https://ir.penguinsolutions.com/news/news-details/2018/Android-Nougat-7-for-Inforce-Snapdragon-410-Based-Platforms/default.aspx).
- LineageOS: [`android_device_wileyfox_crackling`](https://github.com/LineageOS/android_device_wileyfox_crackling)
  (branches, `BoardConfig.mk` on `lineage-17.1`),
  [`android_kernel_cyanogen_msm8916`](https://github.com/LineageOS/android_kernel_cyanogen_msm8916)
  (`Makefile` and `lineageos_crackling_defconfig` on `lineage-17.1`).
- XDA: [LineageOS 18.1 for Wileyfox Swift](https://xdaforums.com/t/rom-11-0-unofficial-testing-lineageos-18-1-for-wileyfox-swift.4310613/),
  [Galaxy A5 2015 LineageOS 18.1](https://xdaforums.com/t/samsung-galaxy-a5-2015-lineage-18-1-custom-rom-tutorial.4728212/),
  [How to repartition for vendor and use GSIs](https://xdaforums.com/t/guide-how-to-repartition-for-vendor-and-how-to-use-gsis.3789787/)
  (through search results).
- Binder: LineageOS [`android_kernel_cyanogen_msm8916`](https://github.com/LineageOS/android_kernel_cyanogen_msm8916)
  `drivers/staging/android/uapi/binder.h` on `cm-13.0` and `lineage-17.1`, and upstream
  [`7980240b6d63` "binder: Add support for scatter-gather"](https://github.com/torvalds/linux/commit/7980240b6d63).
- `qcom-post-boot` in CAF `init.qcom.rc`, e.g. [CodeAurora `qrd-gb-ssss-7225`](https://source.codeaurora.org/external/gigabyte/qrd-gb-ssss-7225/plain/android/system/core/rootdir/etc/init.qcom.rc)
  (through search results); `lulu-mirror-software-only` `build/prepare.py` and `build/container-build.sh`.
- Upstream Linux: [`arch/arm64/boot/dts/qcom/msm8916.dtsi`](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/msm8916.dtsi),
  `drivers/media/i2c/ov5640.c`, history of `arch/arm64/kernel/machine_kexec.c`.
- [`msm8916-mainline/linux`](https://github.com/msm8916-mainline/linux) and [`msm8916-mainline/lk2nd`](https://github.com/msm8916-mainline/lk2nd).
  The postmarketOS wiki now redirects to `wiki.nura.eco` behind a bot check, so it was not read.
- Android on mainline: [Collabora, "Android: Enabling mainline graphics"](https://www.collabora.com/news-and-blog/blog/2017/03/29/android-enabling-mainline-graphics/),
  [SoMainline, "Minimal Android Userspace"](https://docs.somainline.org/guides/minimal-android/).
- Android: [binder IPC and `CONFIG_ANDROID_BINDER_DEVICES`](https://source.android.com/docs/core/architecture/hidl/binder-ipc),
  [Linux.com on Oreo kernel requirements](https://www.linux.com/news/android-oreo-adds-linux-kernel-requirements-and-new-hardening-features/),
  [GSMArena on Android Go for ≤1 GB](https://www.gsmarena.com/google_announces_android_go_for_devices_with_1gb_of_ram_or_less-news-25082.php).
- kexec without `CONFIG_KEXEC`: [`fabianishere/kexec-mod`](https://github.com/fabianishere/kexec-mod),
  [`SolAstrius/kexec-kindle-mt8512`](https://github.com/SolAstrius/kexec-kindle-mt8512).
- linux-firmware [`WHENCE`](https://gitlab.com/kernel-firmware/linux-firmware/-/blob/main/WHENCE)
  (Adreno and `qcom/apq8016` entries, `LICENSE.qcom`).
