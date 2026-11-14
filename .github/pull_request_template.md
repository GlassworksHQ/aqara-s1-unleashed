## What and why

<!-- What changes, and the issue it fixes (Fixes #123). -->

## Tested on

<!-- Delete what doesn't apply. -->

- [ ] A real panel: model ___, firmware ___, Home Assistant ___
- [ ] The web installer in Chrome or Edge on ___
- [ ] Unit tests only (no panel needed for this change)

## Checklist

- [ ] Fits [Glassworks Principles](https://github.com/GlassworksHQ/glassworks/blob/main/docs/principles.md): HA is the brain, ESPHome is the wire, nothing to install in HA
- [ ] `CHANGELOG.md` has a line under **[Unreleased]**, if people running a panel will notice it
- [ ] Docs updated (the frame README's status table, `docs/`), if behaviour or entities changed
- [ ] Nothing logs tokens, keys, Wi-Fi passwords or Thread datasets
- [ ] Changes to the boot patch, root helper or radio say so above; they need a real panel
- [ ] UI changes have screenshots below (before and after for a changed screen)
- [ ] Hardware or reverse-engineering findings are written up in `frames/<id>/research/` (or `docs/research/` if general), dead ends included

## Screenshots

<!-- Required for any change to the app's screens, the web installer or the boot branding.
     Before (from main) and after, at the same state and size, with a caption saying what to look at.
     Panel: adb exec-out screencap -p > after.png. No panel: the app's *Mockups tests
     (-Ps1MockupDir=<dir>) or the installer's ?demo= states. No real addresses, tokens or network names.
     Delete this section if nothing on screen changes. -->
