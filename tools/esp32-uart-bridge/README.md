# ESP32-S3 USB↔UART bridge (dev tool)

Turns any ESP32-S3 into a 3.3 V USB-to-serial adapter for the panel's debug
console. **End users do not need this** — it's for development/reverse
engineering. Built with ESP-IDF v5.3.

- Host side: the S3's native USB-Serial/JTAG (shows up as a `usbmodem`/`ttyACM`),
  or an onboard CP2102 if your board has one.
- Panel side: hardware **UART1**, default **GPIO40 = TX**, **GPIO41 = RX**, 115200 8N1.
- Console/logging is disabled so the data path stays clean.

## Build & flash

```sh
# with ESP-IDF v5.3 (native or the espressif/idf:release-v5.3 Docker image)
idf.py set-target esp32s3 build
python -m esptool --chip esp32s3 -p <PORT> write_flash \
  0x0 build/bootloader/bootloader.bin \
  0x8000 build/partition_table/partition-table.bin \
  0x10000 build/box_bridge.bin
```

## Wiring

```
ESP32-S3 GND     -> panel GND
ESP32-S3 GPIO40  -> panel RX
ESP32-S3 GPIO41  -> panel TX
```

Change `PANEL_TX` / `PANEL_RX` in `main/bridge.c` to use other free GPIOs.
Loopback-test first: jumper GPIO40↔GPIO41 and confirm your terminal echoes.
