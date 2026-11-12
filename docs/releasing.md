# Releasing the kiosk app

Panels update themselves from GitHub Releases: the app lists
`https://api.github.com/repos/GlassworksHQ/aqara-s1-unleashed/releases`, picks its own newest release
and shows the result in Home Assistant as **update.s1_panel_app_update**
([esphome.md](esphome.md#app-updates)). A release is built and published by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when you push a `v*` or
`aqara-s1-plus-v*` tag. It holds one APK per ABI, each with its `.sha256` (see [ABIs](#abis)):

- `s1-kiosk-<version>.apk`, the `armeabi-v7a` APK the S1 runs, signed with the release key;
- `s1-kiosk-arm64-v8a-<version>.apk`, the same app for `arm64-v8a` devices;
- `s1-kiosk-<version>.apk.sha256` and `s1-kiosk-arm64-v8a-<version>.apk.sha256`, their `sha256sum` lines.

Every release **must be signed with the same key**. Android only installs an update signed with the
key of the installed app, and the app refuses anything else before trying. Lose the key and every
panel needs a manual reinstall, which clears its settings.

## 1. Create the release key (once)

On your own computer, outside the repository. `keytool` comes with any JDK.

```sh
mkdir -p ~/secure && cd ~/secure
keytool -genkeypair -v \
  -keystore s1kiosk-release.jks -storetype PKCS12 \
  -alias s1kiosk -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=S1 Kiosk, O=aqara-s1-unleashed"
```

- `keytool` asks for the keystore password twice; it is not echoed and not stored in your shell
  history. Use a long random one from your password manager.
- PKCS12 keystores have one password for the store and the key, so the key password is the same.
- `-validity 10000` is about 27 years (the Android documentation's recommendation is at least 25).

Write down the certificate fingerprint; it is public, and lets anyone check a release:

```sh
keytool -list -v -keystore ~/secure/s1kiosk-release.jks -alias s1kiosk | grep SHA256
```

The fingerprint is also committed in
[`tools/release/release-cert.sha256`](../tools/release/release-cert.sha256) (lowercase hex, no
colons). The release and test-build workflows refuse an APK signed with any other key
([`tools/release/check-update-compat.sh`](../tools/release/check-update-compat.sh)), so a wrong or
swapped secret fails the build instead of shipping an update no panel can install. Rotating the key
means changing that file in the same PR as the secrets.

## 2. Keep it safe

- Store `s1kiosk-release.jks` and its password in your password manager, and keep a second copy
  offline (an encrypted USB stick).
- Never commit it. `.gitignore` ignores `*.jks` and `*.keystore`, but keep it outside the repo anyway.
- If it leaks, someone can sign updates your panels would accept, if they can also get them onto a
  panel. Rotate: make a new key, reinstall the panels by hand, and replace the secrets.
- A copy is in Bitwarden as the secure note "S1 Unleashed signing keys" (keystore, its password,
  and the radio bundle key), saved and checked byte for byte on 2026-11-11.

## 3. Add the four secrets to GitHub (once)

Secrets of the **`release-signing` environment**, not repository secrets. A repository secret can be
read by any workflow that anyone with write access pushes, on any branch; an environment secret only
by jobs that run on what the environment allows. Here that's `main`, `test-build/**` branches and the
`v*` and `aqara-s1-plus-v*` tags, and a ruleset lets only admins create those branches and tags
(see *Who can sign*, below).

Create the environment once (Settings → Environments → New environment → `release-signing`;
Deployment branches and tags → Selected: `main`, `test-build/**`, tags `v*`, `aqara-s1-plus-v*`),
then set the secrets with the [GitHub CLI](https://cli.github.com/). These commands read the values
from the file or from a prompt, so nothing ends up in your shell history:

```sh
cd ~/secure
# The keystore, base64-encoded, from the file:
base64 < s1kiosk-release.jks | gh secret set S1_KEYSTORE_B64 --env release-signing --repo GlassworksHQ/aqara-s1-unleashed
# Passwords: gh prompts for them ("Paste your secret"); the same password twice for PKCS12.
gh secret set S1_KEYSTORE_PASSWORD --env release-signing --repo GlassworksHQ/aqara-s1-unleashed
gh secret set S1_KEY_PASSWORD --env release-signing --repo GlassworksHQ/aqara-s1-unleashed
# The alias is not secret:
gh secret set S1_KEY_ALIAS --env release-signing --repo GlassworksHQ/aqara-s1-unleashed --body s1kiosk
gh secret list --env release-signing --repo GlassworksHQ/aqara-s1-unleashed
```

The workflow uses only these four secrets, the radio bundle key below, the optional `VERCEL_DEPLOY_HOOK`
(a Vercel deploy hook URL for the web installer), and the job's own `GITHUB_TOKEN`.

### Who can sign

Anyone who can run a job in `release-signing` or `radio-signing` can sign code every panel accepts.
Rulesets (Settings → Rules) keep that to admins:

- **Release tags** (`v*`, `aqara-s1-plus-v*`): only admins can create, move or delete them.
- **Test builds** (`test-build/**`): only admins can create, push to or delete these branches.
- **main**: changes go through a pull request with the `app`, `web`, `scripts` and `root` checks
  green; no force pushes or deletion. Admins can bypass, for emergencies.

Pull requests from forks never get any secret, and no workflow uses `pull_request_target` or
`workflow_run`. Before anyone else gets admin, add yourself as a required reviewer on both
environments, so every signed build waits for an approval.

## 3b. The radio bundle key (once)

The APK carries a **signed radio bundle**: the root radio helper, otbr-agent/ot-ctl and the Silicon Labs
Zigbee runtime ([radio.md](radio.md#radio-bundles-helper-v4)). Panels whose boot image has the bundle
loader install it only if it is signed with the Ed25519 key whose public half is baked into the boot
image ([`tools/root/s1kiosk_bundle.pub`](../tools/root/s1kiosk_bundle.pub)). It is a separate key from
the APK key on purpose: it lets root code run on the panel, so it lives only in `~/secure` and in one
repository secret.

```sh
# Done once (2026-11-10): ~/secure/s1kiosk-radio-bundle-ed25519.pem, mode 600, never printed.
openssl genpkey -algorithm ed25519 -out ~/secure/s1kiosk-radio-bundle-ed25519.pem   # OpenSSL 3
openssl pkey -in ~/secure/s1kiosk-radio-bundle-ed25519.pem -pubout -outform DER | tail -c 32 | xxd -p -c 64 \
  > tools/root/s1kiosk_bundle.pub
# The secret: an environment secret of the `radio-signing` environment (Settings -> Environments;
# create the environment first), so only the release workflow's signing job can read it:
gh secret set S1_RADIO_BUNDLE_KEY --env radio-signing --repo GlassworksHQ/aqara-s1-unleashed \
  < ~/secure/s1kiosk-radio-bundle-ed25519.pem
```

- Only the workflow's `sign-bundle` job names that environment. It signs only tags whose commit is on
  `main` (`git merge-base --is-ancestor`), runs no third-party action (GitHub's own checkout and
  download-artifact, pinned by commit SHA), and takes nothing from the unprivileged `inputs` job
  without checking it: the Silicon Labs runtime against the committed
  [`tools/radio/sisdk-runtime.sha256`](../tools/radio/sisdk-runtime.sha256), otbr-agent/ot-ctl against
  `web/assets.lock.json`, the helper from the checkout. No required reviewer is set on the environment
  (one releaser); adding one later only needs the environment setting.
- Keep no repository-level `S1_RADIO_BUNDLE_KEY`: other jobs would then see it.

- Back it up like the APK key. **Losing it** means no more radio bundle updates until panels get a boot
  image with a new public key (one FEL session each). **If it leaks**, someone who can also get a file
  into the kiosk's private storage could run root code: rotate it (new key, new
  `s1kiosk_bundle.pub`, new boot image on every panel).
- The bundle version is the APK's versionCode, and panels refuse a lower one than they have, so a
  bundle can't be rolled back to an older (perhaps buggy) helper by replaying it.

## 3c. Web installer files

The installer's Vercel build downloads its binaries from pinned URLs ([`web/assets.lock.json`](../web/assets.lock.json)).
The boot image's signature checker is one of them since the radio bundle loader:

```sh
tools/root/build-verify.sh      # reproducible; must print sha256 6aa0dd83…9f80 (the lock's pin)
gh release upload installer-files-3 tools/root/out/s1kiosk_verify --repo GlassworksHQ/aqara-s1-unleashed
```

(Create the `installer-files-3` release first if it doesn't exist, e.g. as a copy of
`installer-files-2` plus this file.) Until it is uploaded, the hosted installer's build fails at
`stage-assets.mjs --remote` with a download error; local builds use `tools/root/out/` directly.

## 4. Release

Versions are `MAJOR.MINOR.PATCH`, with minor and patch below 100. The versionCode is
`major*10000 + minor*100 + patch`, so `1.2.3` is 10203. Every release must have a higher version
than the last one. Pre-release tags (`v1.2.3-rc.1`) are refused by the workflow, and the app would
ignore them anyway.

### ABIs

The app is built once per ABI, because each GeckoView AAR is for one ABI and carries its own classes
and `omni.ja`, so two cannot go into one APK. `-Ps1Abi=<abi>` (or `S1_ABI`) picks it, and without it
the build is `armeabi-v7a`, the S1's:

```sh
./gradlew :kiosk:assembleRelease                      # armeabi-v7a
./gradlew :kiosk:assembleRelease -Ps1Abi=arm64-v8a    # arm64-v8a
```

| ABI | Release asset | Radio bundle | Who runs it |
|---|---|---|---|
| `armeabi-v7a` | `s1-kiosk-<version>.apk` | yes | the S1 (32-bit userspace), other 32-bit devices |
| `arm64-v8a` | `s1-kiosk-arm64-v8a-<version>.apk` | no (the bundle is the S1's root side) | arm64 tablets, the arm64 emulator |

- **Every ABI's APK has the same versionCode**, `major*10000 + minor*100 + patch`. Store schemes put
  the ABI in the versionCode because a store must choose between APKs. Here the app chooses: it
  takes the APK of the first ABI in `Build.SUPPORTED_ABIS` (the device's order of preference) that
  the release has (`update/Releases.kt`, `ApkAbi`). So versions go up per ABI exactly as they did
  before, and `ApkCheck` still wants the versionCode of the release's version.
- **The S1's asset name never changes.** Apps up to 0.4.3 and the web installer only know
  `s1-kiosk-<version>.apk`. An S1 lists `armeabi-v7a, armeabi`, so it keeps taking that file.
- An arm64 device that also runs 32-bit code falls back to `s1-kiosk-<version>.apk` for releases
  that have no arm64 APK (all releases up to 0.4.3). An arm64-only device skips them.
- A device whose ABIs the app is not built for (x86_64) gets no offer, and the update entity says why.
- Adding an ABI means adding it to `s1Abis` in `app/kiosk/build.gradle.kts`, to `ApkAbi`, and to
  `ABIS` in both workflows. `ApkAbiListsTest` fails if they disagree.

**Tags, now that the repo will hold other frames and libraries (Glassworks).** Each of them releases
under its own tag prefix, and this app's is `aqara-s1-plus-` (the board id it reports, since other S1
models may get their own frame). From 0.4.4 the app accepts both `v1.2.3` and
`aqara-s1-plus-v1.2.3` and picks its own newest release from the list, whatever else was released
after it. Apps up to **0.4.3** only read `releases/latest` and only understand `v1.2.3`, so:

- keep tagging S1 releases `v1.2.3` until the repo is renamed and other frames start releasing;
- an S1 release is always published with `--latest` (the workflow does this), and every other frame's
  or library's release with `--latest=false`, so `releases/latest` stays an S1 release for old panels.

First add a `## [1.0.0] - <date>` section to [CHANGELOG.md](../CHANGELOG.md) (move the
*Unreleased* entries into it). Its first line is what Home Assistant shows on the update card, so make
it a one-sentence summary. The release fails early if the section is missing.

```sh
git switch main && git pull
git tag -a v1.0.0 -m "S1 Kiosk 1.0.0"
git push origin v1.0.0
gh run watch --repo GlassworksHQ/aqara-s1-unleashed   # or the Actions tab
```

The workflow then:

1. checks the tag;
2. builds the Silicon Labs runtime (`inputs` job: `tools/radio/build-sisdk-runtime.sh`, Docker, a pinned
   image and Ubuntu snapshot; it must reproduce `tools/radio/sisdk-runtime.sha256`; never Silicon Labs'
   zigbeed itself), then signs the radio bundle in the `sign-bundle` job (environment `radio-signing`,
   main only, every input checked against committed hashes; `tools/radio/make-bundle.py --app-version`,
   version = the versionCode, checked against `tools/root/s1kiosk_bundle.pub`);
3. builds `:kiosk:assembleRelease` with `S1_VERSION=1.0.0` once per ABI, the `armeabi-v7a` one with
   the bundle as APK assets. The build fails if a key is missing (`S1_REQUIRE_RELEASE_SIGNING=true`);
4. runs `apksigner verify --print-certs` on each APK, so the certificate fingerprint is in the log,
   checks the package name, version and native code ABI, that the signed bundle is in the
   `armeabi-v7a` APK and not in the others, and that no zigbeed is anywhere;
5. writes a `.sha256` for each;
6. creates the release, with that version's CHANGELOG.md section as the notes. The bundle is not
   published on its own (its Silicon Labs libcpc may only be distributed inside the product).
7. asks Vercel to rebuild the web installer, so it installs the new release (`VERCEL_DEPLOY_HOOK`).

HA shows the first 255 characters of the notes, with a link to the full release.

Panels see the release within 24 hours. To check right away, call `homeassistant.update_entity`
on `update.s1_panel_app_update`: HA sends the entity's "check" command.

**Panels on 0.4.0 or older can't install it from HA.** Their update path refused every release as
"the APK is not signed": on Android 10, `getPackageArchiveInfo` with only `GET_SIGNING_CERTIFICATES`
returns no signer for a downloaded APK. 0.4.1 also asks for `GET_SIGNATURES`
([esphome.md](esphome.md#app-updates)). Because the bug is in the version doing the installing, those
panels need one manual update to 0.4.1 or later (the web installer's *Already installed? Finish setting
it up*, or `adb install -r`, see [install.md](install.md#updating)); from then on HA installs work.

The hosted web installer installs the latest release it saw when it was last built
(`web/scripts/stage-assets.mjs`). The workflow's last step asks Vercel to rebuild it through the
`VERCEL_DEPLOY_HOOK` secret; without that secret it only warns, and you redeploy the installer by hand.

Vercel does not build on pushes (`git.deploymentEnabled: false` in `web/vercel.json`), so PRs get no
Vercel preview. Production builds come only from that deploy hook, called by `release.yml` and by
[`installer-deploy.yml`](../.github/workflows/installer-deploy.yml). The second runs on a push to
`main` that changes an installer input (`web/`, `branding/`, or a repo file the installer stages), or
by hand from the Actions tab.

## Building a signed APK locally

The same environment variables as CI. Without them, `assembleRelease` produces an **unsigned** APK
(`kiosk-release-unsigned.apk`); it never falls back to the debug key.

```sh
cd app
export S1_KEYSTORE_FILE=~/secure/s1kiosk-release.jks S1_KEY_ALIAS=s1kiosk S1_VERSION=1.0.0
read -rs S1_KEYSTORE_PASSWORD && export S1_KEYSTORE_PASSWORD S1_KEY_PASSWORD="$S1_KEYSTORE_PASSWORD"
./gradlew :kiosk:assembleRelease
```

If only some of the four variables are set, the build stops and names the missing ones.

## Signed test builds of a branch

To try a branch on a panel that runs a release, without losing the app's data, let CI sign it:

```sh
git push origin HEAD:test-build/<name>          # builds as the latest release's version
git push origin HEAD:test-build/v1.2.3/<name>   # builds as 1.2.3 (panel on an unreleased version)
gh run download <run-id> -n s1-kiosk-test-<sha>              # armeabi-v7a, for the S1
adb install -r s1-kiosk-test-*.apk
gh run download <run-id> -n s1-kiosk-test-arm64-v8a-<sha>    # arm64-v8a, its own artifact
```

[`test-build.yml`](../.github/workflows/test-build.yml) runs the unit tests and builds the release
variant with the release key, once per ABI. It takes the latest release's version, or the one in the branch name
(Android refuses to install a lower versionCode, so a panel on an unreleased version needs that). The
APK then installs over the panel's app, HA's update entity offers nothing, and the next real release
replaces it. It carries **no
radio bundle**, so the panel's root side stays as it is. The artifact is kept for 3 days, and since
the repo is public, any signed-in GitHub user can download it. To go back to the release, install
its APK from the release page with `adb install -r`. Delete the `test-build/` branch when you're
done.

## Debug builds and the release key

`assembleDebug` is unchanged: version `0.1.0` (versionCode 1), signed with the local debug key.
A panel running a debug build **cannot** update to a release through HA, because the keys differ.
The update entity then says so ("signed with a different key than this app"). Moving such a panel
to releases takes a one-time reinstall: uninstall the debug app, then install the release APK.
Both installers do that for you, after asking, and keep the panel on its Wi-Fi. The app's data is
kept only where adb has root; on a normal panel the switch **resets the app's settings** (sign-in,
pairing key, options). See [install.md](install.md#switching-a-development-build-to-a-release).
