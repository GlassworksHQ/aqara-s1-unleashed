# Third-party notices

S1 Unleashed's own code is MIT ([LICENSE](LICENSE)). This file lists the third-party code that the
project distributes: in the S1 Kiosk APK (with the signed radio bundle that release APKs carry), and in
the files the web installer at https://s1.edm.sh serves. For each part it gives the version, the licence
(as an SPDX identifier), where it comes from and where its source is.

The full licence texts are in [`LICENSES/`](LICENSES), named by SPDX identifier. Those files are the
standard texts. The copyright holders are named under each component below. The app shows this file
and those texts under **Open source licences** in its hidden settings: tap the top-left corner five
times.

Checked against the code on 2026-11-10. `app/kiosk/build.gradle.kts` fails a release build if a
library in the APK is missing from section 1.

## Contents

1. [S1 Kiosk APK](#1-s1-kiosk-apk)
2. [Radio bundle (inside release APKs)](#2-radio-bundle-inside-release-apks)
3. [Web installer downloads](#3-web-installer-downloads)
4. [The web installer page itself](#4-the-web-installer-page-itself)
   - [Kiln, the Next.js installer (not live yet)](#kiln-the-nextjs-installer-not-live-yet)
5. [Not distributed by this project](#5-not-distributed-by-this-project)
6. [Corresponding source for GPL, LGPL and MPL parts](#6-corresponding-source-for-gpl-lgpl-and-mpl-parts)

---

## 1. S1 Kiosk APK

`sh.edm.s1kiosk`, built from [`app/`](app) by `.github/workflows/release.yml`. These are the libraries
in the APK: the release runtime classpath of `app/kiosk`, including transitive dependencies. The
versions are the ones Gradle resolves.

### GeckoView (browser engine)

- Module: `org.mozilla.geckoview:geckoview-armeabi-v7a:159.0.20261102144711` (the armeabi-v7a APK),
  `org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261102144711` (the arm64-v8a APK)
- Licence: MPL-2.0. Some engine libraries are LGPL-2.1-or-later: `liblgpllibs.so`, and
  `libmozavcodec.so`/`libmozavutil.so` (parts of FFmpeg). Other parts are under other free licences.
- Copyright: Mozilla Foundation and contributors
- Upstream: https://geckoview.dev
- Source: mozilla-release revision `7b34a03a910d0385299f4a9f27aa585a786862d8`,
  https://hg.mozilla.org/releases/mozilla-release/rev/7b34a03a910d0385299f4a9f27aa585a786862d8
- GeckoView's own list of the third-party code it contains, with every licence text, is inside the
  APK (`about:license`, in `assets/omni.ja`). It is also at
  https://hg.mozilla.org/releases/mozilla-release/file/7b34a03a910d0385299f4a9f27aa585a786862d8/toolkit/content/license.html

### Libraries the app uses directly

- **TensorFlow Lite** 2.16.1: `org.tensorflow:tensorflow-lite:2.16.1`, `org.tensorflow:tensorflow-lite-api:2.16.1`.
  Apache-2.0, Copyright The TensorFlow Authors. https://github.com/tensorflow/tensorflow (tag
  `v2.16.1`). Its native library `libtensorflowlite_jni.so` also contains TensorFlow's bundled
  third-party code, for example ruy (Apache-2.0) and cpuinfo (BSD-2-Clause). Those are listed in the
  source tree's `third_party/`.
- **Bouncy Castle** (provider) 1.80: `org.bouncycastle:bcprov-jdk18on:1.80`. MIT (the "Bouncy Castle
  Licence" is the MIT text). Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.
  (https://www.bouncycastle.org). Source: https://github.com/bcgit/bc-java (tag `r1rv80`).
- **AndroidX Media3** 1.11.1 (ExoPlayer): Apache-2.0, Copyright The Android Open Source Project.
  https://github.com/androidx/media (tag `1.11.1`). Modules: `androidx.media3:media3-exoplayer:1.11.1`,
  `androidx.media3:media3-common:1.11.1`, `androidx.media3:media3-container:1.11.1`,
  `androidx.media3:media3-database:1.11.1`, `androidx.media3:media3-datasource:1.11.1`,
  `androidx.media3:media3-decoder:1.11.1`, `androidx.media3:media3-extractor:1.11.1`,
  `androidx.media3:media3-exoplayer-hls:1.11.1`.
- **ZXing** core 3.5.3: `com.google.zxing:core:3.5.3`. Apache-2.0, Copyright ZXing authors.
  https://github.com/zxing/zxing (tag `zxing-3.5.3`).
- **XZ for Java** 1.10: `org.tukaani:xz:1.10`. 0BSD. https://github.com/tukaani-project/xz-java
  (tag `v1.10`).

### Code ported into the app

- **TensorFlow Lite Micro audio microfrontend** (`tensorflow/lite/experimental/microfrontend/lib`:
  window, fft, filterbank, noise reduction, PCAN gain control, log scale). Apache-2.0, Copyright 2018
  The TensorFlow Authors. Ported to Kotlin in
  `app/kiosk/src/main/java/sh/edm/s1kiosk/voice/MicroFrontend.kt`, which stays under Apache-2.0 and
  says what changed. Ported from the copy in https://github.com/rhasspy/pymicro-features (tag
  `v2.0.2`, commit `96bd69cfad79aa67697e176570d3dd87052c3def`), the same code as
  https://github.com/tensorflow/tflite-micro.
- **kissfft** (the 16-bit fixed-point `kiss_fft` and `kiss_fftr` the microfrontend uses).
  BSD-3-Clause, Copyright (c) 2003-2010 Mark Borgerding. Ported in the same file, from the same copy.
  https://github.com/mborgerding/kissfft

### Kotlin and AndroidX

Apache-2.0. Kotlin is Copyright JetBrains s.r.o. and Kotlin Programming Language contributors
(https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines). AndroidX is
Copyright The Android Open Source Project (https://cs.android.com/androidx/platform/frameworks/support,
https://developer.android.com/jetpack/androidx). Most of them come in through GeckoView and Media3.

- `org.jetbrains.kotlin:kotlin-stdlib:2.4.20`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0` (version list only, no code)
- `org.jetbrains:annotations:23.0.0` (https://github.com/JetBrains/java-annotations)
- `androidx.activity:activity:1.0.0`
- `androidx.annotation:annotation:1.11.0`
- `androidx.annotation:annotation-jvm:1.11.0`
- `androidx.annotation:annotation-experimental:1.6.0`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.collection:collection:1.6.0`
- `androidx.collection:collection-jvm:1.6.0`
- `androidx.concurrent:concurrent-futures:1.2.0`
- `androidx.core:core:1.19.1`
- `androidx.core:core-viewtree:1.0.0`
- `androidx.customview:customview:1.0.0`
- `androidx.exifinterface:exifinterface:1.3.6`
- `androidx.fragment:fragment:1.1.0`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.11.0`
- `androidx.lifecycle:lifecycle-common-jvm:2.11.0`
- `androidx.lifecycle:lifecycle-livedata:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-process:2.11.0`
- `androidx.lifecycle:lifecycle-runtime:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0`
- `androidx.loader:loader:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.4.0`
- `androidx.savedstate:savedstate:1.0.0`
- `androidx.startup:startup-runtime:1.1.1`
- `androidx.tracing:tracing:1.2.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `androidx.viewpager:viewpager:1.0.0`

### Other open-source libraries pulled in by GeckoView or Media3

All Apache-2.0.

- **Guava** (Copyright The Guava Authors, https://github.com/google/guava):
  `com.google.guava:guava:33.3.1-android`, `com.google.guava:failureaccess:1.0.2`,
  `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` (an empty placeholder).
- **SnakeYAML** 2.2: `org.yaml:snakeyaml:2.2`. Copyright (c) 2008, SnakeYAML.
  https://bitbucket.org/snakeyaml/snakeyaml
- **JSpecify** 1.0.0: `org.jspecify:jspecify:1.0.0`. Copyright The JSpecify Authors.
  https://github.com/jspecify/jspecify

### Not included: Google Play services client libraries

GeckoView depends on Google's proprietary Play services client libraries (`play-services-fido`,
`-base`, `-basement`, `-tasks`) for WebAuthn/passkeys. The app's build excludes them
(`app/kiosk/build.gradle.kts`), as Firefox's F-Droid builds do: the panel has no Google Play services,
so they could never run. Passkey sign-in is therefore not available in the kiosk's browser.

### Assets in the APK

- `assets/extension/` (the built-in kiosk web extension): this project, MIT.
- `assets/licenses/`: this file and `LICENSES/`, copied at build time.
- `res/font/inter.ttf`: **Inter** 4.001, the variable font (weight and optical-size axes), unmodified.
  - Licence: OFL-1.1 (SIL Open Font License 1.1)
  - Copyright: 2020 The Inter Project Authors
  - Upstream: https://github.com/rsms/inter
  - Source: google/fonts, `ofl/inter/Inter[opsz,wght].ttf` at commit
    `e1d6480102fed30739fead0faee463101f892c8f` (SHA-256
    `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`)
  - Used by the app's own screens: voice overlay, timers, settings menu, setup and loading screens.
- No machine-learning models are bundled by the app. The wake word models are downloaded by the
  panel at runtime (section 5). GeckoView's `omni.ja` has its own assets, such as the Liberation fonts
  used by its PDF viewer (SIL OFL 1.1). Those are listed in `about:license`.
- Release APKs also carry the radio bundle in `assets/radio-bundle/` (section 2).

### Not in the APK

- JUnit 4.13.2 (EPL-1.0) and org.json 20250517 (Public Domain) are used only by the unit tests.
- `app/kiosk/src/test/resources/mww_clip.wav` (a unit test fixture, not in the APK): speech
  synthesised with Piper's `en_US-kristin-medium` voice (https://huggingface.co/rhasspy/piper-voices,
  MIT; trained from scratch on public-domain LibriVox recordings) by `tools/voice/mww_reference.py`.
  No recording of a person.

---

## 2. Radio bundle (inside release APKs)

`assets/radio-bundle/`, made and signed by `tools/radio/make-bundle.py` in the release workflow. The
panel installs it into its root side. Contents:

### `s1kiosk_radio.sh`

The radio helper, this project, MIT ([`tools/radio/s1kiosk_radio.sh`](tools/radio/s1kiosk_radio.sh)).

### `otbr-agent`, `ot-ctl`

OpenThread Border Router, the same binaries as the installer's (section 3). They are statically
linked, so they contain the following:

| Part | Version | Licence | Copyright | Source |
|---|---|---|---|---|
| ot-br-posix | `v2026.11.0` (`76fb21d666a41050b43f8eaabc30e71d9d75d9fd`) | BSD-3-Clause | (c) 2017, The OpenThread Authors | https://github.com/openthread/ot-br-posix |
| OpenThread | `ecd308b4a00f76d643b352f8f5c4ffc287bb33a2` | BSD-3-Clause | (c) 2016, The OpenThread Authors | https://github.com/openthread/openthread |
| Mbed TLS | `v3.6.5` (`e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df`) | Apache-2.0 (dual-licensed with GPL-2.0-or-later; used under Apache-2.0) | The Mbed TLS Contributors | https://github.com/Mbed-TLS/mbedtls |
| cJSON | `cf97c6f066d81fdbba4ef722cfd327bbbba2365c` | MIT | (c) 2009-2017 Dave Gamble and cJSON contributors | https://github.com/DaveGamble/cJSON |
| cpp-httplib | `03cf43ebaa55f27a2778bed870ea3549f7e84e2c` | MIT | (c) 2017 yhirose | https://github.com/yhirose/cpp-httplib |
| Silicon Labs CPC vendor interface (`cpc_interface.cpp`) | Simplicity SDK `v2025.6.3` (`b41bec3ff2485199c1a5a9995b3e649e118c1b8d`) | Zlib | Copyright 2024 Silicon Laboratories Inc. | https://github.com/SiliconLabs/simplicity_sdk |
| Silicon Labs libcpc (`otbr-agent` only) | cpc-daemon `v4.3.3` (`684595db6be6534f856cfc7455551e71567298ee`) | LicenseRef-Silabs-MSLA (source-code sections) | Copyright 2018 Silicon Laboratories Inc. | https://github.com/SiliconLabs/cpc-daemon |
| GNU C Library (static) | 2.35, Ubuntu 22.04 `libc6-dev-armhf-cross` 2.35-0ubuntu1cross3 | LGPL-2.1-or-later | Free Software Foundation, Inc. and others | section 6 |
| GCC runtime, libstdc++ (static) | GCC 11.4.0, Ubuntu `11.4.0-1ubuntu1~22.04.3` | GPL-3.0-or-later WITH GCC-exception-3.1 | Free Software Foundation, Inc. | section 6 |

The build and all its pins are in [`tools/radio/build-otbr.sh`](tools/radio/build-otbr.sh) (`PROFILE=br`).
The libcpc in `otbr-agent` has one build-time change: it reads the socket directory from
`$CPC_SOCKET_DIR`.

### `sisdk/` (runtime for the Silicon Labs Zigbee stack)

Built by [`tools/radio/build-sisdk-runtime.sh`](tools/radio/build-sisdk-runtime.sh). It must match the
committed [`tools/radio/sisdk-runtime.sha256`](tools/radio/sisdk-runtime.sha256). It has its own
`NOTICE.txt` and `licenses/`, which go into the bundle with it.

- `sisdk/zigbeed.sh`: this project, MIT.
- `sisdk/lib/libcpc.so.3`: Silicon Labs cpc-daemon `v4.3.3` (`684595db6be6534f856cfc7455551e71567298ee`),
  compiled unmodified. LicenseRef-Silabs-MSLA (source-code sections), Copyright 2018 Silicon
  Laboratories Inc., https://github.com/SiliconLabs/cpc-daemon. It is provided only for use with the
  Silicon Labs radio inside the panel, as part of this bundle. See `sisdk/NOTICE.txt` and
  https://www.silabs.com/about-us/legal/master-software-license-agreement.
- `sisdk/lib/ld-linux-armhf.so.3`, `libc.so.6`, `libm.so.6`: GNU C Library 2.35, unmodified, from Ubuntu
  22.04 `libc6-armhf-cross`. LGPL-2.1-or-later. Source: section 6.
- `sisdk/lib/libstdc++.so.6`, `libgcc_s.so.1`: GCC 12 runtime, unmodified, from Ubuntu 22.04
  `libstdc++6-armhf-cross` / `libgcc-s1-armhf-cross`. GPL-3.0-or-later WITH GCC-exception-3.1.
  Source: section 6.
- **Not included:** Silicon Labs' `zigbeed` (section 5).

The bundle carries no radio firmware.

---

## 3. Web installer downloads

The files the installer at https://s1.edm.sh serves and writes to the panel. Pinned by SHA-256 in
[`kiln/assets.lock.json`](kiln/assets.lock.json) and in `kiln/scripts/stage-assets.mjs`.

### U-Boot SPL and U-Boot (`u-boot-sunxi-with-spl.bin`, `u-boot.bin`)

- Licence: GPL-2.0-or-later. Copyright Wolfgang Denk and the U-Boot contributors.
- Version: U-Boot `2027.01-rc1`, built 2026-11-10 04:34 UTC.
- Release files: GitHub release `installer-files-2`. sha256 `b2e6820a…1ffa` (`u-boot-sunxi-with-spl.bin`)
  and `ea0050fd…b98e` (`u-boot.bin`).
- Corresponding source: upstream U-Boot `main` at commit `934995f642a21ca7f35fa9aed5f061394fcba243`
  (https://github.com/u-boot/u-boot/commit/934995f642a21ca7f35fa9aed5f061394fcba243). Add this
  repository's [`tools/fel/uboot/`](tools/fel/uboot) at commit `8b2bf488fed3c7ba8343a32c67c9651066479128`:
  - `patches/0001-sunxi-a133-ddr4-match-boot0-controller-setup.patch`
  - `patches/0002-fastboot-add-upload-command.patch`
  - `aqara-s1-plus_defconfig`
  - `sun50i-a133-aqara-s1-plus.dts`

  Build with [`tools/fel/build-uboot.sh`](tools/fel/build-uboot.sh) (arm64 `ubuntu:22.04`, gcc
  `11.4.0-1ubuntu1~22.04.3`). A rebuild is not byte-identical, because U-Boot embeds the build time.
- `u-boot-sunxi-with-spl.bin` also contains a TF-A BL31 (below). The installer starts U-Boot without
  it.

### Trusted Firmware-A BL31 (inside `u-boot-sunxi-with-spl.bin`)

- Licence: BSD-3-Clause. Copyright (c) Arm Limited and Contributors. https://www.trustedfirmware.org/projects/tf-a/
- Version: `v2.15.0` debug build for `sun50i_a133`, from upstream commit
  `a413e1d7d38ed42dcdffc690d52a0cceb142a0e7` (https://github.com/ARM-software/arm-trusted-firmware),
  with local debugging changes, kept as a patch in [`tools/fel/tfa/`](tools/fel/tfa/). The installer never
  runs it.

### `otbr-agent`, `ot-ctl`

The OpenThread Border Router build described in section 2. Release `installer-files-2`.

### `s1kiosk_verify`

The Ed25519 checker for radio bundles. Release `installer-files-3`, sha256 `6aa0dd83…9f80`.

- `tools/root/verify/s1kiosk_verify.c`: this project, MIT.
- **Monocypher 4.0.3**: BSD-2-Clause (dual-licensed with CC0-1.0; used under BSD-2-Clause).
  Copyright (c) 2017-2023, Loup Vaillant; Copyright (c) 2017-2019, Michael Savage; Copyright (c)
  2017-2023, Fabio Scotoni. https://monocypher.org (`monocypher-4.0.3.tar.gz`, sha256
  `8cc9bc341a66249016db9bd70e9142d8d0aef9945973744b1ac05dbc55d8ee66`).
- **musl libc** (static), Alpine 3.20: MIT. Copyright © 2005-2020 Rich Felker, et al.
  https://musl.libc.org
- Reproducible build: [`tools/root/build-verify.sh`](tools/root/build-verify.sh).

### Magisk v31.1

- Licence: GPL-3.0-only. Copyright John Wu (topjohnwu) and contributors.
- The official release APK, unmodified (sha256 `89cefac0…c6e7`), served by the installer. The
  installer also writes three libraries from it into the panel's boot image: `libmagiskinit.so`,
  `libmagisk.so` and `libinit-ld.so`.
- Corresponding source: tag `v31.1` of https://github.com/topjohnwu/Magisk
  (https://github.com/topjohnwu/Magisk/releases/tag/v31.1).

### Simple Keyboard

- `rkr.simplekeyboard.inputmethod`, F-Droid build 149, unmodified. Apache-2.0. Copyright the Simple
  Keyboard authors. https://github.com/rkkr/simple-keyboard

### This project's files

All MIT, from this repository:
- The root helpers and their init files: `tools/root/s1kiosk_*.sh`, `tools/root/*.rc`,
  `tools/radio/s1kiosk_radio.{sh,rc}`.
- `tools/root/s1kiosk_bundle.pub`.
- The boot branding in `branding/glass-tile/` (`bootanimation.zip`, `bootlogo.bmp`). It shows text set
  in Inter (SIL OFL 1.1, fetched at build time). These downloads carry only images made with the font;
  the font itself ships in the S1 Kiosk APK (section 1, "Assets in the APK").
- The S1 Kiosk APK (sections 1 and 2).

---

## 4. The web installer page itself

The page's JavaScript and WebAssembly ([`kiln/`](kiln)):

- **Tango / ya-webadb**: MIT, Copyright (c) 2020-2025 Simon Chan. https://github.com/yume-chan/ya-webadb.
  `@yume-chan/adb` 2.6.4, `@yume-chan/adb-daemon-webusb` 2.3.2, `@yume-chan/stream-extra` 2.6.1, and
  their `@yume-chan/*` dependencies.
- **fastboot.js**: MIT, "Copyright (c) 2021 Danny Lin <danny@kdrag0n.dev>".
  https://github.com/kdrag0n/fastboot.js, commit `5b613332aa9d66cca5bebb49f147cd084a76c464`.
  [`kiln/packages/fastboot`](kiln/packages/fastboot) is a fork of its `fastboot.ts`, `sparse.ts` and
  `common.ts`, changed by this project. Its licence is in `kiln/packages/fastboot/LICENSE`, and what we
  changed is in that package's README.
- **`bootpatch.wasm`**: this project's `kiln/packages/bootpatch/src/wasm` (MIT), statically linked with these
  crates:
  - flate2 1.1.10 (MIT OR Apache-2.0)
  - zlib-rs 0.6.8 (Zlib)
  - lzma-rust2 0.21.0 (Apache-2.0)
  - sha2 0.11.1, digest 0.11.3, block-buffer 0.12.1, crypto-common 0.2.2, const-oid 0.10.2,
    hybrid-array 0.4.15, typenum 1.20.1 and cfg-if 1.0.5 (MIT OR Apache-2.0)
  - the Rust standard library (MIT OR Apache-2.0)

  The list is in `kiln/packages/bootpatch/src/wasm/THIRD_PARTY.txt`. No Magisk code is included.

### Kiln, the Next.js installer (not live yet)

[`kiln/apps/kiln`](kiln/apps/kiln) serves the same installer code (above) through Next.js. It isn't on
s1.edm.sh yet (#27). Checked against its lockfile, each package's own licence file and a production
build on 2026-11-12. The copyright lines are copied from those files. Where a package has no licence
file, its `package.json` licence and author are given. What it adds:

- **Next.js** 16.4.6 (`next`): MIT, "Copyright (c) 2025 Vercel, Inc." https://github.com/vercel/next.js
  (tag `v16.4.6`). Next ships third-party code it has compiled in under `next/dist/compiled/`, each
  with its licence file there. That includes the copies of React and React DOM it serves App Router
  pages with.
- **React** and **React DOM** 19.3.0, **scheduler** 0.28.0: MIT, "Copyright (c) Meta Platforms, Inc. and
  affiliates." https://github.com/facebook/react
- **styled-jsx** 5.1.6: MIT, "Copyright (c) 2016-present Vercel, Inc." **client-only** 0.0.1: MIT (no
  licence file in the package). Both are part of Next's server runtime.
- **@swc/helpers** 0.5.23: Apache-2.0 (the licence file has no copyright line; `package.json` names
  강동윤 as author). https://github.com/swc-project/swc
- **Radix Primitives**: MIT, "Copyright (c) 2022 WorkOS" (the same line in each package).
  https://github.com/radix-ui/primitives. `@radix-ui/react-dialog` 1.3.1, `@radix-ui/react-radio-group`
  1.5.1, `@radix-ui/react-collapsible` 1.2.1, and the `@radix-ui/*` packages they use: `primitive`
  1.2.0, `react-collection` 1.2.0, `react-compose-refs` 1.1.5, `react-context` 1.3.0, `react-direction`
  1.2.0, `react-dismissable-layer` 1.2.1, `react-focus-guards` 1.2.0, `react-focus-scope` 1.3.1,
  `react-id` 1.1.4, `react-portal` 1.2.0, `react-presence` 1.1.11, `react-primitive` 2.2.0,
  `react-roving-focus` 1.2.1, `react-slot` 1.5.0, `react-use-callback-ref` 1.1.5,
  `react-use-controllable-state` 1.3.0, `react-use-effect-event` 0.0.5, `react-use-is-hydrated` 0.1.3,
  `react-use-layout-effect` 1.1.4, `react-use-size` 1.1.5.
- What Radix's dialog uses, all MIT (https://github.com/theKashey):
  - "Copyright (c) 2017 Anton Korzunov": **react-remove-scroll** 2.7.2, **react-style-singleton**
    2.2.3, **use-callback-ref** 1.3.3, **use-sidecar** 1.1.3, **aria-hidden** 1.2.6.
  - "Copyright (c) 2020 Anton Korzunov": **get-nonce** 1.0.1.
  - **react-remove-scroll-bar** 2.3.8: no licence file in the package; `package.json` names Anton
    Korzunov as author.
  - **detect-node-es** 1.1.0: "Copyright (c) 2017 Ilya Kantor".
  - **tslib** 2.9.1: 0BSD, "Copyright (c) Microsoft Corporation."
- **Inter** and **JetBrains Mono** (the font files, served by `next/font`): OFL-1.1. "Copyright (c) 2016
  The Inter Project Authors (https://github.com/rsms/inter)"; "Copyright 2020 The JetBrains Mono Project
  Authors (https://github.com/JetBrains/JetBrainsMono)". Fetched from Google Fonts at build time.
- The Glassworks tokens, mark, and Kiln's favicon and app icon (`kiln/packages/brand`) are Glassworks' own, from
  https://github.com/GlassworksHQ/glassworks.dev.
- **The device renders** (`frames/<id>/design/render.webp`, on the frame cards): screenshots of the
  **Home Assistant frontend** 20261028.5 (Apache-2.0, Copyright Home Assistant contributors,
  https://github.com/home-assistant/frontend) drawing a made-up dashboard, made by
  `tools/showcase/render.mjs`. The camera still and the album art in them are drawn by
  `tools/showcase/home.py` (this project's, MIT). The images show the frontend's own look, with the **Material Design
  Icons** it bundles (`@mdi/js`, Apache-2.0, Copyright Pictogrammers, https://github.com/Templarian/MaterialDesign)
  and text in the **Roboto** font it serves (Google). Only rendered pixels are kept: no code, icon file
  or font file from the frontend is in the images or in Kiln. The frontend's licence was read from its
  wheel's `METADATA` (`License-Expression: Apache-2.0`) on 2026-11-13. [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

Not shipped, by the build's traces:

- Next's build tools: `@next/env`, `@next/swc-*`, postcss, caniuse-lite, baseline-browser-mapping, and
  their dependencies.
- **sharp**, its LGPL libvips (`@img/*`), and `detect-libc`, `semver` and `@emnapi/runtime`. These appear
  only in the trace of the full `next start` server (`.next/next-server.js.nft.json`), with or without
  `images.unoptimized`. The minimal server's trace (`.next/next-minimal-server.js.nft.json`) lists only
  `next`, `react`, `client-only` and `styled-jsx`. Each route's trace lists only `next` and
  `@swc/helpers`.

Which server a deployment runs is checked on the deployed function's files before Kiln goes live (PR
#104). If sharp, `@img/*`, `detect-libc`, `semver` or `@emnapi/runtime` are there, they need entries
here, and `semver` (ISC) needs `LICENSES/ISC.txt`.

---

## 5. Not distributed by this project

- **Silicon Labs `zigbeed`** (EmberZNet 8.2.2). This is Silicon Labs object code under the Silicon Labs
  MSLA. The project neither ships nor hosts it. The panel downloads it from Silicon Labs' own release
  (`SiliconLabs/simplicity_sdk` `v2025.6.3`, `debian-bookworm.zip`) only when the Silicon Labs Zigbee
  stack is chosen, and checks its SHA-256. `tools/radio/make-bundle.py` refuses to package it, and the
  release workflow fails if an APK contains it.
- **microWakeWord models** (Apache-2.0, Copyright Kevin Ahrendt): `okay_nabu`, `hey_jarvis`,
  `alexa`, `hey_mycroft` from https://github.com/esphome/micro-wake-word-models (`models/v2` at commit
  `40ff33f57f8fc6ad71a75ef085abbf742495b225`). The panel downloads them itself and checks their
  SHA-256.
- **openWakeWord models** (CC-BY-NC-SA-4.0), the legacy wake word engine kept for one release. The
  panel downloads them from the official v0.5.1 release (https://github.com/dscripka/openWakeWord)
  and checks their SHA-256. 0.4 and earlier fetched them; a new panel doesn't, unless a legacy word
  is picked.
- **Home Assistant frontend** (`home-assistant-frontend` 20261028.5, the build HA 2026.11 serves):
  Apache-2.0 ([`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)), Copyright Home Assistant
  contributors, https://github.com/home-assistant/frontend (tag `20261028.5`). Clarity, the perf test
  in `tools/perf`, downloads the wheel from PyPI on the computer that runs the test, checks its
  SHA-256 (`df2af7a4…4258730`, `tools/perf/hafrontend.py`), and serves it to the panel under test,
  from that computer. Its files are served as they are, except its `index.html` template, which is
  filled in with the test's harness script added first (`tools/perf/fakeha.py`). The project neither
  ships nor hosts it. `tools/showcase/render.mjs` serves the same build the same way to a headless
  Chrome on that computer, to make the device renders (section 4); only those screenshots are kept.
- **FFmpeg with libx264** (GPL-2.0-or-later as built with libx264). Clarity's camera streams
  (`tools/perf/camera.py`) run the ffmpeg already on the computer that runs the test, to encode its
  built-in test pattern into the test's HLS segments. Those segments are test data, served to the panel
  under test from that computer. Nothing from ffmpeg or libx264, and none of the segments, ships in
  the app, the bundle or the repo.
- **Aqara/Lumi firmware.** None is included. The installer's partition backup stays with the owner. On
  the panel, the radio helper uses Aqara's own `cpcd` and stock radio firmware where they already are.

---

## 6. Corresponding source for GPL, LGPL and MPL parts

| Component | Licence | Where it ships | Corresponding source |
|---|---|---|---|
| U-Boot | GPL-2.0-or-later | installer | Upstream `934995f642a21ca7f35fa9aed5f061394fcba243` plus [`tools/fel/uboot/`](tools/fel/uboot) at `8b2bf488fed3c7ba8343a32c67c9651066479128` (section 3) |
| Magisk | GPL-3.0-only | installer, panel boot image | https://github.com/topjohnwu/Magisk/tree/v31.1 |
| GeckoView | MPL-2.0 (some parts LGPL-2.1-or-later) | APK | https://hg.mozilla.org/releases/mozilla-release/rev/7b34a03a910d0385299f4a9f27aa585a786862d8 |
| GNU C Library, static in `otbr-agent`/`ot-ctl` | LGPL-2.1-or-later | installer, radio bundle | Ubuntu 22.04 source package `cross-toolchain-base` 59ubuntu3, which built `libc6-dev-armhf-cross` 2.35-0ubuntu1cross3 from Ubuntu's `glibc` 2.35 source: https://launchpad.net/ubuntu/+source/cross-toolchain-base/59ubuntu3. To relink `otbr-agent`/`ot-ctl` with a modified C library, rebuild them from source with [`tools/radio/build-otbr.sh`](tools/radio/build-otbr.sh). |
| GNU C Library, `sisdk/lib/` | LGPL-2.1-or-later | radio bundle | The Ubuntu source packages named in `sisdk/NOTICE.txt` (Ubuntu snapshot `20261101T000000Z`, see `tools/radio/build-sisdk-runtime.sh`). The libraries can be replaced: the bundle loads them from that directory. |
| libstdc++, libgcc_s | GPL-3.0-or-later WITH GCC-exception-3.1 | installer, radio bundle | The Ubuntu GCC cross source packages: `gcc-11-cross` for the static copies in `otbr-agent`/`ot-ctl`, and those named in `sisdk/NOTICE.txt` for `sisdk/lib/`. All are on https://launchpad.net/ubuntu |

Questions about source: open an issue at https://github.com/GlassworksHQ/aqara-s1-unleashed/issues.
