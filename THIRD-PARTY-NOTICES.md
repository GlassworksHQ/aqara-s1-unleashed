# Third-party notices

S1 Unleashed's own code is MIT ([LICENSE](LICENSE)). This file lists the third-party code that the
project distributes: in the S1 Kiosk APK (with the signed radio bundle that release APKs carry), and in
the files the web installer at https://s1.edm.sh serves. For each part it gives the version, the licence
(as an SPDX identifier), where it comes from and where its source is.

The full licence texts are in [`LICENSES/`](LICENSES), named by SPDX identifier. Those files are the
standard texts. The copyright holders are named under each component below. The app shows this file
and those texts under **Open source licences** in its hidden settings: tap the top-left corner five
times.

Checked against the code on 2026-11-11. `app/kiosk/build.gradle.kts` fails a release build if a
library in the APK is missing from section 1.

## Contents

1. [S1 Kiosk APK](#1-s1-kiosk-apk)
2. [Radio bundle (inside release APKs)](#2-radio-bundle-inside-release-apks)
3. [Web installer downloads](#3-web-installer-downloads)
4. [The web installer page itself](#4-the-web-installer-page-itself)
5. [Not distributed by this project](#5-not-distributed-by-this-project)
6. [Corresponding source for GPL, LGPL and MPL parts](#6-corresponding-source-for-gpl-lgpl-and-mpl-parts)

---

## 1. S1 Kiosk APK

`sh.edm.s1kiosk`, built from [`app/`](app) by `.github/workflows/release.yml`. These are the libraries
in the APK: the release runtime classpath of `app/kiosk`, including transitive dependencies. The
versions are the ones Gradle resolves.

### GeckoView (browser engine)

- Module: `org.mozilla.geckoview:geckoview-armeabi-v7a:159.0.20261102144711`
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

### rs-matter-android (Matter library, native code)

`lib/armeabi-v7a/librs_matter_android.so` and the Kotlin classes `org.rsmatter.android.*`: the Matter
device the panel runs to be commissioned over Bluetooth ([docs/matter.md](docs/matter.md)). Built from
source by the app's build, from the git submodule `app/third_party/rs-matter`: the owner's fork of
rs-matter (https://github.com/aguilaair/rs-matter, branch `android`), which adds the Android port. The
submodule's commit is the exact source. rs-matter is Apache-2.0, Copyright Project CHIP Authors and
the rs-matter contributors (https://github.com/project-chip/rs-matter).

The native library links the Rust crates below, all under permissive licences. Where a crate offers a
choice, this project uses it under MIT or Apache-2.0 (texts in `LICENSES/`); `domain`, `octseq` and
`subtle` are BSD-3-Clause. Generated by `tools/release/rust-notices.py` (rerun it when the submodule
moves).

| Crate | Version | Licence | Copyright (authors) | Source |
|---|---|---|---|---|
| `aead` | 0.6.1 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `aes` | 0.9.4 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/block-ciphers |
| `aho-corasick` | 1.1.5 | Unlicense OR MIT | Andrew Gallant | https://github.com/BurntSushi/aho-corasick |
| `android_log-sys` | 0.3.2 | MIT OR Apache-2.0 | The android_log_sys Developers | https://github.com/rust-mobile/android_log-sys-rs |
| `android_logger` | 0.14.1 | MIT OR Apache-2.0 | The android_logger Developers | https://github.com/rust-mobile/android_logger-rs |
| `async-channel` | 2.5.0 | Apache-2.0 OR MIT | Stjepan Glavina | https://github.com/smol-rs/async-channel |
| `async-io` | 2.6.0 | Apache-2.0 OR MIT | Stjepan Glavina | https://github.com/smol-rs/async-io |
| `base16ct` | 1.0.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `bitflags` | 2.13.2 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/bitflags/bitflags |
| `block-buffer` | 0.12.1 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/utils |
| `byteorder` | 1.5.0 | Unlicense OR MIT | Andrew Gallant | https://github.com/BurntSushi/byteorder |
| `bytes` | 1.12.1 | MIT | Carl Lerche, Sean McArthur | https://github.com/tokio-rs/bytes |
| `ccm` | 0.6.1 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/AEADs |
| `cesu8` | 1.1.0 | Apache-2.0 OR MIT | Eric Kidd | https://github.com/emk/cesu8-rs |
| `cfg-if` | 1.0.5 | MIT OR Apache-2.0 | Alex Crichton | https://github.com/rust-lang/cfg-if |
| `chacha20` | 0.10.2 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/stream-ciphers |
| `cipher` | 0.5.2 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `cmov` | 0.5.4 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/utils |
| `combine` | 4.6.9 | MIT | Markus Westerlind | https://github.com/Marwes/combine |
| `concurrent-queue` | 2.5.0 | Apache-2.0 OR MIT | Stjepan Glavina, Taiki Endo, John Nunley | https://github.com/smol-rs/concurrent-queue |
| `const-oid` | 0.10.3 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `cpubits` | 0.1.1 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/utils |
| `critical-section` | 1.2.0 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-embedded/critical-section |
| `crossbeam-utils` | 0.8.23 | MIT OR Apache-2.0 | see the crate | https://github.com/crossbeam-rs/crossbeam |
| `crypto-bigint` | 0.7.11 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/crypto-bigint |
| `crypto-common` | 0.2.2 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `ctr` | 0.10.1 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/block-modes |
| `ctutils` | 0.4.8 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/utils |
| `der` | 0.8.3 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `deranged` | 0.5.9 | MIT OR Apache-2.0 | Jacob Pratt | https://github.com/jhpratt/deranged |
| `digest` | 0.11.3 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `domain` | 0.12.3 | BSD-3-Clause | NLnet Labs | https://github.com/nlnetlabs/domain/ |
| `ecdsa` | 0.17.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/signatures |
| `either` | 1.19.0 | MIT OR Apache-2.0 | see the crate | https://github.com/rayon-rs/either |
| `elliptic-curve` | 0.14.3 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `embassy-executor-timer-queue` | 0.1.0 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embassy-futures` | 0.1.2 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embassy-sync` | 0.8.0 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embassy-time` | 0.5.1 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embassy-time-driver` | 0.2.2 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embassy-time-queue-utils` | 0.3.2 | MIT OR Apache-2.0 | see the crate | https://github.com/embassy-rs/embassy |
| `embedded-hal` | 0.2.7 | MIT OR Apache-2.0 | The Embedded HAL Team, Jorge Aparicio, Jonathan 'theJPster' Pallant | https://github.com/rust-embedded/embedded-hal |
| `embedded-hal` | 1.0.0 | MIT OR Apache-2.0 | The Embedded HAL Team, Jorge Aparicio, Jonathan 'theJPster' Pallant | https://github.com/rust-embedded/embedded-hal |
| `embedded-hal-async` | 1.0.0 | MIT OR Apache-2.0 | The Embedded HAL Team and Contributors | https://github.com/rust-embedded/embedded-hal |
| `embedded-io` | 0.7.1 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-embedded/embedded-hal |
| `embedded-io-async` | 0.7.0 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-embedded/embedded-hal |
| `env_filter` | 0.1.4 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-cli/env_logger |
| `errno` | 0.3.14 | MIT OR Apache-2.0 | Chris Wong, Dan Gohman | https://github.com/lambda-fairy/rust-errno |
| `event-listener` | 5.4.2 | Apache-2.0 OR MIT | Stjepan Glavina, John Nunley | https://github.com/smol-rs/event-listener |
| `event-listener-strategy` | 0.5.4 | Apache-2.0 OR MIT | John Nunley | https://github.com/smol-rs/event-listener-strategy |
| `fastrand` | 2.5.0 | Apache-2.0 OR MIT | Stjepan Glavina | https://github.com/smol-rs/fastrand |
| `ff` | 0.14.0 | MIT OR Apache-2.0 | Sean Bowe, Jack Grigg | https://github.com/zkcrypto/ff |
| `flagset` | 0.4.7 | Apache-2.0 | Nathaniel McCallum | https://github.com/enarx/flagset |
| `futures-core` | 0.3.34 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-lang/futures-rs |
| `futures-io` | 0.3.34 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-lang/futures-rs |
| `futures-lite` | 2.6.1 | Apache-2.0 OR MIT | Stjepan Glavina, Contributors to futures-rs | https://github.com/smol-rs/futures-lite |
| `futures-sink` | 0.3.34 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-lang/futures-rs |
| `getrandom` | 0.4.3 | MIT OR Apache-2.0 | The Rand Project Developers | https://github.com/rust-random/getrandom |
| `group` | 0.14.0 | MIT OR Apache-2.0 | Sean Bowe, Jack Grigg | https://github.com/zkcrypto/group |
| `hash32` | 0.3.1 | MIT OR Apache-2.0 | Jorge Aparicio | https://github.com/japaric/hash32 |
| `heapless` | 0.9.3 | MIT OR Apache-2.0 | Jorge Aparicio, Per Lindgren, Emil Fresk | https://github.com/rust-embedded/heapless |
| `hkdf` | 0.13.0 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/KDFs/ |
| `hmac` | 0.13.0 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/MACs |
| `hybrid-array` | 0.4.15 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/hybrid-array |
| `if-addrs` | 0.15.0 | MIT OR BSD-3-Clause | MaidSafe Developers, Messense Lv | https://github.com/messense/if-addrs |
| `inout` | 0.2.6 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/utils |
| `jiff` | 0.2.42 | Unlicense OR MIT | Andrew Gallant | https://github.com/BurntSushi/jiff |
| `jiff-core` | 0.1.4 | Unlicense OR MIT | Andrew Gallant | https://github.com/BurntSushi/jiff |
| `jni` | 0.21.1 | MIT OR Apache-2.0 | Josh Chase | https://github.com/jni-rs/jni-rs |
| `jni-sys` | 0.3.1 | MIT OR Apache-2.0 | Steven Fackler | https://github.com/jni-rs/jni-sys |
| `jni-sys` | 0.4.1 | MIT OR Apache-2.0 | Steven Fackler, Robert Bragg | https://github.com/jni-rs/jni-sys |
| `libc` | 0.2.191 | MIT OR Apache-2.0 | see the crate | https://github.com/rust-lang/libc |
| `linux-raw-sys` | 0.12.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | Dan Gohman | https://github.com/sunfishcode/linux-raw-sys |
| `log` | 0.4.35 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-lang/log |
| `memchr` | 2.8.3 | Unlicense OR MIT | Andrew Gallant, bluss | https://github.com/BurntSushi/memchr |
| `memoffset` | 0.9.1 | MIT | Gilad Naaman | https://github.com/Gilnaa/memoffset |
| `nb` | 0.1.3 | MIT OR Apache-2.0 | Jorge Aparicio | https://github.com/rust-embedded/nb |
| `nb` | 1.1.0 | MIT OR Apache-2.0 | Jorge Aparicio | https://github.com/rust-embedded/nb |
| `nix` | 0.31.3 | MIT | see the crate | https://github.com/nix-rust/nix |
| `num` | 0.4.3 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num |
| `num-complex` | 0.4.6 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num-complex |
| `num-conv` | 0.2.2 | MIT OR Apache-2.0 | Jacob Pratt | https://github.com/jhpratt/num-conv |
| `num-integer` | 0.1.47 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num-integer |
| `num-iter` | 0.1.46 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num-iter |
| `num-rational` | 0.4.2 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num-rational |
| `num-traits` | 0.2.19 | MIT OR Apache-2.0 | The Rust Project Developers | https://github.com/rust-num/num-traits |
| `num_enum` | 0.7.6 | BSD-3-Clause OR MIT OR Apache-2.0 | Daniel Wagner-Hall, Daniel Henry-Mantilla, Vincent Esche | https://github.com/illicitonion/num_enum |
| `octseq` | 0.6.1 | BSD-3-Clause | NLnet Labs | https://github.com/NLnetLabs/octets/ |
| `p256` | 0.14.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/elliptic-curves |
| `parking` | 2.2.1 | Apache-2.0 OR MIT | Stjepan Glavina, The Rust Project Developers | https://github.com/smol-rs/parking |
| `pbkdf2` | 0.13.0 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/password-hashes |
| `pin-project-lite` | 0.2.17 | Apache-2.0 OR MIT | see the crate | https://github.com/taiki-e/pin-project-lite |
| `pinned-init` | 0.0.10 | MIT OR Apache-2.0 | y86-dev | https://github.com/Rust-for-Linux/pin-init |
| `polling` | 3.11.0 | Apache-2.0 OR MIT | Stjepan Glavina, John Nunley | https://github.com/smol-rs/polling |
| `powerfmt` | 0.2.1 | MIT OR Apache-2.0 | Jacob Pratt | https://github.com/jhpratt/powerfmt |
| `primefield` | 0.14.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/elliptic-curves |
| `primeorder` | 0.14.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/elliptic-curves |
| `qrcodegen-no-heap` | 1.8.1 | MIT | Project Nayuki | https://github.com/nayuki/QR-Code-generator |
| `rand` | 0.10.3 | MIT OR Apache-2.0 | The Rand Project Developers, The Rust Project Developers | https://github.com/rust-random/rand |
| `rand_core` | 0.10.1 | MIT OR Apache-2.0 | The Rand Project Developers | https://github.com/rust-random/rand_core |
| `regex` | 1.13.1 | MIT OR Apache-2.0 | The Rust Project Developers, Andrew Gallant | https://github.com/rust-lang/regex |
| `regex-automata` | 0.4.18 | MIT OR Apache-2.0 | The Rust Project Developers, Andrew Gallant | https://github.com/rust-lang/regex |
| `regex-syntax` | 0.8.11 | MIT OR Apache-2.0 | The Rust Project Developers, Andrew Gallant | https://github.com/rust-lang/regex |
| `rfc6979` | 0.6.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/signatures |
| `rs-matter` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-android` | 0.1.0 | Apache-2.0 | see the crate | https://github.com/project-chip/rs-matter |
| `rs-matter-bdx` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-cert` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-crypto` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-dm` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-dm-clusters` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-dm-core` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-im` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-im-core` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-node` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-node-core` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-onboard` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-pairing` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-respond` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-sc` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-stack` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-tlv` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-transport` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rs-matter-utils` | 0.4.1 | Apache-2.0 | Kedar Sovani, Ivan Markov, Project CHIP Authors | https://github.com/project-chip/rs-matter |
| `rustix` | 1.1.6 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | Dan Gohman, Jakub Konka | https://github.com/bytecodealliance/rustix |
| `scopeguard` | 1.2.0 | MIT OR Apache-2.0 | bluss | https://github.com/bluss/scopeguard |
| `sec1` | 0.8.1 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `sha1` | 0.11.0 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/hashes |
| `sha2` | 0.11.1 | MIT OR Apache-2.0 | RustCrypto Developers | https://github.com/RustCrypto/hashes |
| `signature` | 3.0.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/traits |
| `slab` | 0.4.12 | MIT | Carl Lerche | https://github.com/tokio-rs/slab |
| `socket2` | 0.6.5 | MIT OR Apache-2.0 | Alex Crichton, Thomas de Zeeuw | https://github.com/rust-lang/socket2 |
| `spki` | 0.8.1 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `stable_deref_trait` | 1.2.1 | MIT OR Apache-2.0 | Robert Grosse | https://github.com/storyyeller/stable_deref_trait |
| `strum` | 0.28.0 | MIT | Peter Glotfelty | https://github.com/Peternator7/strum |
| `subtle` | 2.6.1 | BSD-3-Clause | Isis Lovecruft, Henry de Valence | https://github.com/dalek-cryptography/subtle |
| `thiserror` | 1.0.69 | MIT OR Apache-2.0 | David Tolnay | https://github.com/dtolnay/thiserror |
| `time` | 0.3.47 | MIT OR Apache-2.0 | Jacob Pratt, Time contributors | https://github.com/time-rs/time |
| `time-core` | 0.1.8 | MIT OR Apache-2.0 | Jacob Pratt, Time contributors | https://github.com/time-rs/time |
| `typenum` | 1.20.1 | MIT OR Apache-2.0 | see the crate | https://github.com/paholg/typenum |
| `verhoeff` | 1.0.0 | BlueOak-1.0.0 OR MIT OR Apache-2.0 | Chris Morgan | https://gitlab.com/chris-morgan/verhoeff |
| `void` | 1.0.2 | MIT | Jonathan Reem | https://github.com/reem/rust-void.git |
| `wnaf` | 0.14.1 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/elliptic-curves |
| `x509-cert` | 0.3.0 | Apache-2.0 OR MIT | RustCrypto Developers | https://github.com/RustCrypto/formats |
| `zeroize` | 1.9.1 | Apache-2.0 OR MIT | The RustCrypto Project Developers | https://github.com/RustCrypto/utils |

145 crates.

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
- No machine-learning models are bundled by the app. The openWakeWord models are downloaded by the
  panel at runtime (section 5). GeckoView's `omni.ja` has its own assets, such as the Liberation fonts
  used by its PDF viewer (SIL OFL 1.1). Those are listed in `about:license`.
- Release APKs also carry the radio bundle in `assets/radio-bundle/` (section 2).

### Not in the APK

- JUnit 4.13.2 (EPL-1.0) and org.json 20250517 (Public Domain) are used only by the unit tests.

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
[`web/assets.lock.json`](web/assets.lock.json) and in `web/scripts/stage-assets.mjs`.

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

The page's JavaScript and WebAssembly ([`web/`](web)):

- **Tango / ya-webadb**: MIT, Copyright (c) 2020-2025 Simon Chan. https://github.com/yume-chan/ya-webadb.
  `@yume-chan/adb` 2.6.4, `@yume-chan/adb-daemon-webusb` 2.3.2, `@yume-chan/stream-extra` 2.6.1, and
  their `@yume-chan/*` dependencies.
- **`bootpatch.wasm`**: this project's `web/src/bootpatch/wasm` (MIT), statically linked with these
  crates:
  - flate2 1.1.10 (MIT OR Apache-2.0)
  - zlib-rs 0.6.8 (Zlib)
  - lzma-rust2 0.21.0 (Apache-2.0)
  - sha2 0.11.1, digest 0.11.3, block-buffer 0.12.1, crypto-common 0.2.2, const-oid 0.10.2,
    hybrid-array 0.4.15, typenum 1.20.1 and cfg-if 1.0.5 (MIT OR Apache-2.0)
  - the Rust standard library (MIT OR Apache-2.0)

  The list is in `web/src/bootpatch/wasm/THIRD_PARTY.txt`. No Magisk code is included.

---

## 5. Not distributed by this project

- **Silicon Labs `zigbeed`** (EmberZNet 8.2.2). This is Silicon Labs object code under the Silicon Labs
  MSLA. The project neither ships nor hosts it. The panel downloads it from Silicon Labs' own release
  (`SiliconLabs/simplicity_sdk` `v2025.6.3`, `debian-bookworm.zip`) only when the Silicon Labs Zigbee
  stack is chosen, and checks its SHA-256. `tools/radio/make-bundle.py` refuses to package it, and the
  release workflow fails if an APK contains it.
- **openWakeWord models** (CC-BY-NC-SA-4.0). The panel downloads them from the official v0.5.1
  release (https://github.com/dscripka/openWakeWord) and checks their SHA-256.
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

Questions about source: open an issue at https://github.com/aguilaair/aqara-s1-unleashed/issues.
