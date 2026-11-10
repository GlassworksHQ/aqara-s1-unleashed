# Releasing the kiosk app

Panels update themselves from GitHub Releases: the app checks
`https://api.github.com/repos/aguilaair/aqara-s1-unleashed/releases/latest` and shows the result in Home
Assistant as **update.s1_panel_app_update** ([esphome.md](esphome.md#app-updates)). A release is
built and published by [`.github/workflows/release.yml`](../.github/workflows/release.yml) when you
push a `v*` tag. It holds two assets:

- `s1-kiosk-<version>.apk`, signed with the release key;
- `s1-kiosk-<version>.apk.sha256`, its `sha256sum` line.

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

## 2. Keep it safe

- Store `s1kiosk-release.jks` and its password in your password manager, and keep a second copy
  offline (an encrypted USB stick).
- Never commit it. `.gitignore` ignores `*.jks` and `*.keystore`, but keep it outside the repo anyway.
- If it leaks, someone can sign updates your panels would accept, if they can also get them onto a
  panel. Rotate: make a new key, reinstall the panels by hand, and replace the secrets.

## 3. Add the four secrets to GitHub (once)

Repository secrets, with the [GitHub CLI](https://cli.github.com/). These commands read the values
from the file or from a prompt, so nothing ends up in your shell history:

```sh
cd ~/secure
# The keystore, base64-encoded, from the file:
base64 < s1kiosk-release.jks | gh secret set S1_KEYSTORE_B64 --repo aguilaair/aqara-s1-unleashed
# Passwords: gh prompts for them ("Paste your secret"); the same password twice for PKCS12.
gh secret set S1_KEYSTORE_PASSWORD --repo aguilaair/aqara-s1-unleashed
gh secret set S1_KEY_PASSWORD --repo aguilaair/aqara-s1-unleashed
# The alias is not secret:
gh secret set S1_KEY_ALIAS --repo aguilaair/aqara-s1-unleashed --body s1kiosk
gh secret list --repo aguilaair/aqara-s1-unleashed
```

The workflow uses only these four secrets and the job's own `GITHUB_TOKEN`.

## 4. Release

Versions are `MAJOR.MINOR.PATCH`, with minor and patch below 100. The versionCode is
`major*10000 + minor*100 + patch`, so `1.2.3` is 10203. Every release must have a higher version
than the last one. Pre-release tags (`v1.2.3-rc.1`) are refused by the workflow, and the app would
ignore them anyway.

First add a `## [1.0.0] - <date>` section to [CHANGELOG.md](../CHANGELOG.md) (move the
*Unreleased* entries into it). Its first line is what Home Assistant shows on the update card, so make
it a one-sentence summary. The release fails early if the section is missing.

```sh
git switch main && git pull
git tag -a v1.0.0 -m "S1 Kiosk 1.0.0"
git push origin v1.0.0
gh run watch --repo aguilaair/aqara-s1-unleashed   # or the Actions tab
```

The workflow then:

1. checks the tag;
2. builds `:kiosk:assembleRelease` with `S1_VERSION=1.0.0`. The build fails if the key is missing
   (`S1_REQUIRE_RELEASE_SIGNING=true`);
3. runs `apksigner verify --print-certs`, so the certificate fingerprint is in the log, and checks
   the package name and version;
4. writes the `.sha256`;
5. creates the release, with that version's CHANGELOG.md section as the notes.

HA shows the first 255 characters of the notes, with a link to the full release.

Panels see the release within 24 hours. To check right away, call `homeassistant.update_entity`
on `update.s1_panel_app_update`: HA sends the entity's "check" command.

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

## Debug builds and the release key

`assembleDebug` is unchanged: version `0.1.0` (versionCode 1), signed with the local debug key.
A panel running a debug build **cannot** update to a release through HA, because the keys differ.
The update entity then says so ("signed with a different key than this app"). Moving such a panel
to releases takes a one-time reinstall: uninstall the debug app, then install the release APK.
That clears the app's data. If the app is device owner, release that first
(`--ez release_device_owner true`, see ImprovActivity).
