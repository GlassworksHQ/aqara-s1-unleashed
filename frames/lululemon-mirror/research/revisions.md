# Mirror revisions: which ones are a target, and how not to tell them apart

**Status (2026-11-13): read only, no hardware.** Later revisions of the Mirror are reported to use
an NXP i.MX8 board, where only hardware-assisted (JTAG) access has been reported. They are not a target.
Screen size, year bought and branding don't tell you which board a unit has.

Device: none. This note is read from three public sources at the commits below. Nothing was run, and
no unit was opened.

## Question

The frame's `notice` used to say that the later version (software 5.x, about 43 inches, a blue
button on the top edge) has a locked NXP board. Nothing in this repo backed that. What do the
sources actually say about later revisions, and can a buyer tell them apart by size?

## What we read

1. **repairman29/lululemon-mirror-repurpose**, `docs/supported-devices.md` at `fa8af75`
   (2026-09-09). Read 2026-11-13.
   It lists the supported unit (`msm8916_64`, Android 6.0.1, build `IFC-6309-2.0-MIR/329`) and says:
   > Do not assume compatibility with later NXP i.MX8-based MIRROR revisions. Those devices use a
   > different SoC, bootloader, Android release, and trust chain.

2. **Onemoremichael/lulu-mirror-software-only**, `hardware/research/nxp.md` and `panels.md` at
   `efddcbd` (2026-10-21). Read 2026-11-13.
   - `nxp.md` is an "NXP i.MX8M Mini research lead". It comes from an owner's discussion and the
     "Making Lululemonade" writeup, which describes a hardware-assisted (JTAG) approach. It records
     "no independently validated software-only entry point".
   - `panels.md`: "Marketing size, year bought, branding and a reset button are weak clues. Record
     them as observations, but do not use them to assign SoC, revision or firmware."

3. **olm3ca/mirror**, `README.md` at `c3dd836` (2026-08-13). Read 2026-11-13.
   - Its own unit is "Rev08" with a 43-inch BOE DV430FHM-NN5 panel.
   - Around 2022, matching the rebrand to "lululemon Studio Mirror", marketing moved from 40 to 43
     inches, with the panel change from Samsung cells to BOE.
   - That is a panel change. The README doesn't name the board those units use, and Onemoremichael's
     `panels.md` notes that no SoC is established for the Rev08 designation.

## Result

| Claim | Evidence | Grade |
|---|---|---|
| Later revisions use an NXP i.MX8 (i.MX8M Mini) board | repairman29 `supported-devices.md`; Onemoremichael `nxp.md` | read, 2026-11-13; no hardware |
| On it, only hardware-assisted (JTAG) access has been reported | Onemoremichael `nxp.md` | read, 2026-11-13; no hardware |
| They are not a target for this frame | Different SoC, bootloader, Android and trust chain (repairman29); the frame is built for MSM8916 | Our decision |
| 40 vs 43 inches is a panel change; the source doesn't say whether the board changed with it | olm3ca `README.md` | read, 2026-11-13 |
| Size, year and branding don't identify the board | Onemoremichael `panels.md`; olm3ca | read, 2026-11-13 |

What this rules out: telling a target from its screen size, the year it was bought, or the "Studio"
branding. The earlier "about 43 inches, a blue button on the top edge" wording is dropped.

## How to tell

Use the check the frame already gives (`ratingsNote` in `design/frame.json`): in fastboot, the unit
must report MSM8916 and the expected partition sizes. If anything differs, stop.

The sizes are known from one unit only: the maintainer's, read from `/sys/class/block/*/uevent` with
the community image installed (#40). `system` is 1228 MB and `userdata` 5324 MB, the two partitions
the install writes. Treat them as a reference, not a spec: no other unit has been read.

The units differ: #40's sizes are megabytes, rounded, while `fastboot getvar partition-size:<name>` gives
bytes, in hex. So compare to the nearest megabyte, and record the exact byte values the next time a unit
is read in fastboot.

## Open questions

- Which units, by date or serial range, carry the NXP board. Nothing public establishes it.
- Whether any software-only way in exists on the NXP board. None is reported.
