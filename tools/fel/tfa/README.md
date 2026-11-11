# TF-A BL31 in the installer's `u-boot-sunxi-with-spl.bin`

The SPL image in the `installer-files-2` release carries a Trusted Firmware-A BL31 (BSD-3-Clause).
**The installer never runs it**: it starts U-Boot from RAM without BL31. It is there only because
U-Boot's sunxi build packs one into the image.

That BL31 is a debug build from DRAM bring-up (2026-11-10, "Built : 02:16:25, Nov 10 2026"):

- upstream https://github.com/ARM-software/arm-trusted-firmware commit
  `a413e1d7d38ed42dcdffc690d52a0cceb142a0e7` (v2.15.0 + integration);
- plus [`0001-s1-debug-bl31-uart-markers-icache-off.patch`](0001-s1-debug-bl31-uart-markers-icache-off.patch):
  raw UART progress markers, caches invalidated at entry, the EL3 I-cache left off;
- a `DEBUG=1` build for `PLAT=sun50i_a133` in a build directory named `build-noic`; the exact `make`
  flags weren't recorded.

A rebuild is not byte-identical (TF-A embeds the build time). The next installer-files release should
pack a clean release BL31 from a pinned commit, or none (FOLLOW-UP: needs one FEL test).
