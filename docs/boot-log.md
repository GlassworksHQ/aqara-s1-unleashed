# Annotated stock boot log

Captured over the 3-pin UART console at **115200 8N1**. Device-unique values
(serial number, MACs, SID, vbmeta hash) are redacted.

## BOOT0 (first-stage) — DRAM bring-up

```
HELLO! BOOT0 is starting!
BOOT0 commit : e2b6c56-dirty
PMU: AXP806
vaild para:1  select dram para0
board init ok, set sys_vol to 950mv!
DRAM BOOT DRIVE INFO: V0.69
the chip id is 0x1000 ... chip id check OK
DRAM_VCC set to 1200 mv
[AUTO DEBUG]32bit,1 ranks training success!
[AUTO DEBUG]default config,dram_clk=792 ,mtest pass
[AUTO DEBUG]after escan,dram_tpr11 = f101612,dram_tpr12 = c0c110f
DRAM CLK =792 MHZ
DRAM Type =4 (3:DDR3,4:DDR4,7:LPDDR3,8:LPDDR4)
DRAM SIZE =2048 MBytes, para1 = 610a, para2 = 8000000, tpr13 = 107501
DRAM simple test OK.
```

Key DRAM params for rebuilding U-Boot: **DDR4, 792 MHz, 1200 mV, para1=0x610a,
para2=0x8000000, tpr11=0xf101612, tpr12=0xc0c110f, tpr13=0x107501**, AXP806 PMIC.

## eMMC

```
[mmc]: MMC 5.1 / HSSDR52...HS400 8 bit / 7456 MB
[mmc]: ***SD/MMC 2 init OK!!!***
Loading boot-pkg Succeed(index=0).
Entry_name = u-boot / monitor / scp / dtb
```

## BL31 (ARM Trusted Firmware) + U-Boot

```
NOTICE:  BL3-1: v1.0(debug) ... Built 2020-11-17
U-Boot 2018.05 (Dec 02 2025 - 14:21:03 +0800) Allwinner Technology
CPU:   Allwinner Family
Model: sun50iw10
DRAM:  2 GiB
secure enable bit: 0                      <-- SECURE BOOT OFF
PMU: AXP806
sunxi flash init ok
tool did not set rotpk burn flag, skip rotpk burn   <-- ROTPK not fused
Loading Environment from SUNXI_FLASH... OK
no secure os for keybox operation
```

## Fastboot / autoboot

```
usb burn from boot
do_burn_from_boot usb : have no handshake   <-- U-Boot fastboot/burn probe
...
Hit any key to stop autoboot:  0            <-- interruptible to U-Boot shell
misc partition found
serial num is: <REDACTED>
partinfo: name boot, start 0x2a000, size 0x10000
magic = AVB0  ...  release tool = avbtool 1.1.0   <-- AVB (not hw-enforced)
```

## Kernel + Android

```
Starting kernel ...
Linux version 4.9.170 (public@embd-allwinner-new) ... #1 SMP PREEMPT Tue Dec 2 14:22:03 CST 2025
Booting Linux on physical CPU 0x0
...
console:/ $                                 <-- root-capable serial shell
```

`getprop ro.build.fingerprint` →
`Allwinner/peony_evb2/peony-perf1:10/QP1A.191105.004/eng.public.<DATE>:user/test-keys`
