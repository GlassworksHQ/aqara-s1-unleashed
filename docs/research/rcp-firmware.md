# A newer radio (RCP) firmware for the S1's EFR32MG21? (research, 2026-11-10)

Question: can a newer Silicon Labs multiprotocol RCP be built for this board, now that the panel has
a root path to flash one ([../radio.md](../radio.md#radio-firmware-updates-helper-v4))? Short answer:
**it can probably be built, but nothing needs it today, and any newer SDK's image also needs a newer
host cpcd on the panel.** No image is in this repository, and none was built or flashed.

## What runs today

- Stock RCP `0017/2.3.3.0_GitHub-e6df00dd6` (Gecko SDK 4.3.3), multi-PAN 802.15.4 over CPC/SPI,
  **CPC protocol v4**; Gecko bootloader v2.3 (EZSP-SPI), unsigned and uncompressed GBLs accepted
  (dry run 2026-11-09; the stock GBL is HEADER, APPLICATION, ERASEPROG 120308 B at `0x4000`, END).
- Host side: Aqara's cpcd 4.3.3.0 (inverted reset-pin driver; [../radio.md](../radio.md#flash-path-verified-2026-11-09)).
- Zigbee2MQTT's need (EZSP ≥ 13) is already met **without** a new RCP: Silicon Labs' zigbeed 8.2.2 runs
  on this RCP ([zigbee2mqtt.md](zigbee2mqtt.md)). Thread works on it too. So a new RCP would be about
  bug fixes or newer radio features, not about making Zigbee2MQTT possible.

## Findings

1. **CPC protocol is the hard constraint.** `cpc-daemon` sets `CPC_PROTOCOL_VERSION` per release
   (read from each tag's `CMakeLists.txt`): 4.3.3 = **4**; 4.4.0, 4.5.0, 4.6.0 = **5**; 4.7.0 = **6**.
   On the secondary side, Simplicity SDK v2024.6.3 has `SLI_CPC_PROTOCOL_VERSION (5)` and v2025.6.3 has
   `(6)` (`platform/service/cpc/inc/sli_cpc.h`). Aqara's cpcd 4.3.3 requires an exact match
   (`server_core/server_core.c:730`, `server_core_secondary_protocol_version != PROTOCOL_VERSION`).
   **Gecko SDK 4.3.3 is the last SDK with CPC v4, and it is what the stock image already is** (the
   gecko_sdk tags stop at v4.3.3 in the 4.3 line). So every "newer" RCP needs a newer cpcd for normal use.
2. **Flashing is not blocked by that.** With the recovery pins, Aqara's `cpcd -f` skips every CPC version
   check and talks only to the bootloader (EZSP-SPI), so it can flash any app GBL, and flash the stock
   one back. What breaks is everything after: Aqara's `cpcd -p` (our post-flash check), `zigbee_cpcd`,
   and libcpc 4.3.3 in otbr-agent and the zigbeed runtime. Our flash path would therefore roll such an
   image back automatically, which is the safe default until a radio bundle brings a matching host stack.
3. **A matching host stack is buildable**, as radio bundle content: upstream cpcd 4.7.x (protocol 6)
   built for this board, run by the helper instead of `zigbee_cpcd` in normal mode (normal mode does not
   drive the reset pin; it resets the secondary over CPC, so the inverted-pin problem only concerns
   `-f`/`-e`, which stays on Aqara's binary). Open: whether upstream cpcd's GPIO access (it uses the GPIO
   character device for the IRQ line; Aqara's build uses sysfs) works with this kernel; libcpc, otbr-agent
   and the zigbeed runtime rebuilt against 4.7; and Silicon Labs pairs zigbeed and RCP by SDK release,
   so zigbeed 8.2.2 would want the 2025.6 RCP anyway.
4. **The sample app exists for this exact configuration.** Simplicity SDK v2025.6.3
   `protocol/openthread/sample-apps/ot-ncp/rcp-spi-802154.slcp`: "Multiprotocol (OpenThread+Zigbee) - RCP
   (SPI)", `quality: production`, multi-PAN (`OPENTHREAD_CONFIG_MULTIPAN_RCP_ENABLE 1`), CPC over SPI
   with `cpc_secondary_driver_spi_usart` on Series 2 (the MG21 is Series 2; xG24 and later use EUSART).
   The MG21 here has 1024 kB flash (inferred from the stock image's NVM3 literal) and 96 kB RAM; the
   512 kB MG21 in Home Assistant's SkyConnect ran Silicon Labs' multiprotocol RCP builds, so size is not
   the issue.
5. **Tooling is available without a login.** The earlier note that `slc-cli` returns 403 is only true for
   the `silabs.com/documents/login/...` URL: Silicon Labs' Studio update site serves it openly
   (`https://updates.silabs.com/studio/v6/updates/update_site/archives/slc-cli/6.0.22/slc-cli.linux.gtk.<arch>.zip`,
   used hash-pinned by NabuCasa's `silabs-firmware-builder` Dockerfile, together with ZAP, a JRE and Python
   from the same site). The GSDK/SiSDK sources are public on GitHub. So a reproducible container build
   is possible.
6. **The real work is the board configuration**, none of which is public or done:
   - the CPC SPI secondary's USART instance and SCLK/MOSI/MISO/CS routes (the stock image uses the xG21
     USART block layout; the exact `GPIO->USARTROUTE` writes have to be read out of the stock image),
   - the nHOST_INT line (the host's PH19 / gpio 243) and the bootloader wake pin (host PB8 / gpio 40),
   - HFXO settings and CTUNE: Aqara stores CTUNE in the Secure Engine user-data area and its RCP has
     custom commands to read/write it; a new image must read the same location or the radio is detuned,
   - the bootloader's app start address (`0x4000`) and an uncompressed GBL (LZ4 support of this
     bootloader v2.3 is unknown).
   No community MG21-SPI target exists (darkxst/NabuCasa manifests are UART only).
7. **Risk is bounded but real.** An app-only GBL cannot write the bootloader (Gecko bootloader bounds
   check; our helper also refuses bootloader/SE-upgrade/encrypted tags), and the stock image is always on
   `/vendor` for rollback with the pins. A wrong pin or clock config means a radio that doesn't answer
   until the stock image is flashed back. Bootloader-level secure boot is effectively off; signed-upgrade
   enforcement is off (the unsigned stock GBL flashes).

## Recommendation

- Don't build or ship an RCP now: Zigbee2MQTT and Thread already work on the stock image, and every newer
  SDK drags in a new host cpcd stack on Android.
- If it is ever wanted (e.g. Silicon Labs fixes in multi-PAN, or to pair zigbeed 8.2.2 with its own RCP):
  1. read the pin/clock/CTUNE configuration out of the stock image;
  2. build `rcp-spi-802154` for `EFR32MG21A0x0F1024IM32` with SiSDK 2025.6.x in a pinned container
     (`slc-cli` from the update site), uncompressed GBL;
  3. build cpcd 4.7.x + libcpc for the panel and teach the helper to run it (and use it for the post-flash
     check), all as a radio bundle;
  4. flash only through the v4 path (signed bundle `firmware` item, armed request, automatic rollback),
     on a panel whose owner can reach the FEL button, and soak-test.

## Sources

- SiliconLabs/cpc-daemon tags v4.3.3, v4.4.0, v4.5.0, v4.6.0, v4.7.0 `CMakeLists.txt`; v4.3.3
  `server_core/server_core.c:730`.
- SiliconLabs/simplicity_sdk v2024.6.3 and v2025.6.3 `platform/service/cpc/inc/sli_cpc.h`; v2025.6.3
  `protocol/openthread/sample-apps/ot-ncp/rcp-spi-802154.slcp`. SiliconLabs/gecko_sdk tags (4.3 line ends at v4.3.3).
- NabuCasa/silabs-firmware-builder `Dockerfile` (slc-cli 6.0.22 from updates.silabs.com, sha256-pinned).
- The backups' `radio-fw/FINDINGS.md`, `FLASH-PLAN.md` and dry-run logs; the stock GBL re-parsed here.
