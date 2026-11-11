# Legal notes

What S1 Unleashed does and doesn't do, stated as facts about the project. This is not legal advice,
and the rules differ from country to country.

## Scope: your own device

S1 Unleashed changes the software on an Aqara Panel Hub S1 Plus that you own. The installer
works on the panel connected to your computer over USB. Before it writes anything, it saves a backup of
the partitions it changes, and that backup stays with you. *Undo a previous install* writes it back
([install.md](install.md)). Changing the panel's software probably voids its warranty.

## No copy protection is bypassed

The panel's own boot log shows that secure boot is not enabled and that no root-of-trust key (ROTPK)
was ever fused into the chip ([research/findings.md](research/findings.md#secure-boot--verified-boot)).
So the chip's mask-ROM recovery mode, FEL, accepts unsigned code. It asks for no signature, key or
password. The installer uses FEL as it is meant to be used: it loads U-Boot, built from source, into RAM
([research/fel.md](research/fel.md)).

The install doesn't break or extract any keys and doesn't decrypt anything. To boot the patched boot
image, it sets the "verification disabled" flag in the panel's own `vbmeta` partition. That is a
standard Android Verified Boot setting. On this panel AVB is not enforced by the hardware, because
secure boot is off. Your backup holds the original `vbmeta`.

## Bring your own firmware

The project doesn't distribute Aqara software. The installer patches the boot image already on your
panel, in your browser, using Magisk and the project's own files. Aqara's system, apps, radio daemon
(`cpcd`) and stock radio firmware stay on the panel and are used where they are.

Silicon Labs' Zigbee stack, `zigbeed`, is not distributed either. When you choose that stack, the panel
downloads it from Silicon Labs' own GitHub release and checks its SHA-256. The radio bundle and the APK
are checked so that they never contain it.

Some Silicon Labs code does ship, though: the client library `libcpc` (from Silicon Labs' public
`cpc-daemon` source, under Silicon Labs' MSLA) and a Zlib-licensed interface file. They are built into
`otbr-agent`, and the radio bundle has a copy of `libcpc` for `zigbeed`. They are there to talk to the
panel's own Silicon Labs radio. [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md#2-radio-bundle-inside-release-apks)
lists them.

## Trademarks

S1 Unleashed is not affiliated with, endorsed by or sponsored by Aqara or Lumi United Technology.
"Aqara", "Panel Hub S1 Plus" and the other product names are trademarks of their owners. They are
used here only to say which device the project works with. The same applies to Silicon Labs, Home
Assistant and other names mentioned in this project.

## Licences

The project's own code is MIT ([LICENSE](../LICENSE)). Third-party code keeps its own licence.
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) lists everything the APK and the installer ship,
with its licence and where its source is. The licence texts are in [LICENSES/](../LICENSES). On the
panel, they are under *Open source licences* in the hidden settings: tap the top-left corner five
times.
