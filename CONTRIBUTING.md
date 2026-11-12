# Contributing

## Issues

Pick a template: **Bug**, **Hardware report** (a model, firmware or base we haven't tested), or
**Feature request**. Include the S1 Kiosk version and Aqara firmware, and strip tokens, Wi-Fi
passwords and Thread datasets from logs.

## Pull requests

Small PRs against `main`. CI (`.github/workflows/pr.yml`) runs the app's unit tests and release
build, the installer's typecheck, tests and build, the install scripts' tests and the docs' links.

```sh
cd app && ./gradlew test :kiosk:assembleDebug        # the app
cd web && npm install && npm run stage && npm test   # the web installer
tools/install/test/install-tests.sh                  # the install scripts
python3 tools/check-doc-links.py                     # docs links
```

- Say in the PR what you tested on: a real panel (model, firmware, HA version) or tests only.
- Add a line to `CHANGELOG.md` under **[Unreleased]** if people running a panel will notice it.
- Changes to the boot patch, root helper or radio need testing on a real panel before merge.
- **UI changes need screenshots** in the PR: before and after for a changed screen, after for a new
  one. Without a panel, use the app's mockup tests or the installer's `?demo=` states
  ([AGENTS.md](AGENTS.md#pull-requests)).
- **Write down what you find on the hardware** in `docs/research/`, including what didn't work
  ([the playbook](docs/research/bringup.md), [template](docs/research/TEMPLATE.md)).

Coding agents: start at [AGENTS.md](AGENTS.md).

A signed test build is made for any branch named `test-build/vX.Y.Z/<name>`
([releasing.md](docs/releasing.md)).

## Branding

UI, READMEs and new libraries follow the Glassworks brand. [docs/branding.md](docs/branding.md) says
where to find it and which badge or banner to use.

## Labels

| Label | Use |
|---|---|
| `frame: …` | Only one device (frame), e.g. `frame: aqara-s1-plus`. The exact model goes in the issue form, not a label |
| `pane: …` | Which pane, named after its folder: `pane: zigbee-thread`, `pane: matter`; a frame's own panes as `pane: <frame>/<pane>`, e.g. `pane: aqara-s1-plus/wall-base` |
| `area: …` | Which part of the project |
| `needs info`, `needs hardware` | Waiting on the reporter, or on someone with that hardware |
| `upstream` | Caused by or waiting on HA, zigpy, Zigbee2MQTT or another project |
| `hardware report` | Results from untested hardware |

Code is MIT ([LICENSE](LICENSE)).
