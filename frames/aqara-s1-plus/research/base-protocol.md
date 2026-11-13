# Wall base UART protocol (`/dev/ttyS3`)

This is the protocol the Panel Hub S1 Plus speaks with its mains wall base (the "bracket"). It was
reverse-engineered from Aqara's `/vendor/bin/bracket_cli` and `/vendor/lib/libha_bracket.so`, which
`ha_master` loads with `-k`. Aqara's own `bracket_cli` was then run on the panel against
[`tools/bracket/base_emulator.py`](../../../tools/bracket/base_emulator.py) to check it, and it accepted
the emulator. The app's driver is `base/BracketProtocol.kt`, `base/BracketSession.kt` and
`base/UartBaseLink.kt`. Background on the base itself is in [bases.md](bases.md).

**What is still unverified:** no real base was attached. Everything below comes from Aqara's code,
and from Aqara's code accepting an emulator built from that code. See [Limitations](#limitations).

Tools: `llvm-objdump` (ARM mode; all functions are ARM, not Thumb), radare2, and a small annotator
that resolves PC-relative literals. All binaries are 32-bit ARM, stripped. `libha_bracket.so`
exports its function names. Addresses below are virtual addresses in the binaries named. The
binaries are Aqara's, so they are not in the repo (a copy is kept with the panel backups).

## Physical layer

- 115200 baud, 8N1, no parity, 1 stop bit, **no RTS/CTS, no XON/XOFF**, `CLOCAL|CREAD`, `IGNBRK`,
  raw.
- Both tools call c-periphery `serial_open_advanced(path, baud, 8, NONE, 1, xonxoff=false,
  rtscts=false)`. In bracket_cli this is `0x1cc0`: it uses a zeroed termios, `open(O_RDWR|O_NOCTTY)`,
  `cfset[io]speed` and `tcsetattr(TCSANOW)`. It makes **no ioctl** (no TIOCEXCL, no modem lines).
- This is also what `ha_master` leaves on the real port. Read-only, `busybox stty -F /dev/ttyS3 -g`
  gives `1:0:18b2:0:0:…`, i.e. iflag=IGNBRK, oflag=0, cflag=B115200|CS8|CREAD|CLOCAL, lflag=0.
- The app sets the same flags with `busybox stty`, adding `min 0 time 1` so its reader thread wakes
  every 100 ms. It reads them back with `stty -g` and checks them (`UartBaseLink.verifyTermios`).
- **Use `/vendor/bin/busybox stty`, not `/system/bin/stty`.** The firmware's toybox 0.8.0 `stty`
  corrupts `c_iflag` when it sets anything. On a pty, `stty -cstopb` alone turned on
  `ixon icrnl istrip inlcr igncr`. On this port that would eat 0x11/0x13 bytes and rewrite
  0x0d/0x0a. Reading with toybox is fine.

## Frame

```
offset  0    1    2    3    4     5   6        7       8 … 8+L-1   8+L     8+L+1
        55   80   02   seq  type  len (BE16)  hcksum  payload     crc_lo  crc_hi
```

| Field | Meaning | Source |
|---|---|---|
| `55 80 02` | constant. Receivers check `[1..2] == 0x8002` and the header checksum, but not `[0]` | header template `55 80 02 00 00 00 00 00` (bracket_cli `0x55a0`, lib `0x180d8`); check in `msg_head_parse` `0x75d4`, `msg_parse` `0x7650`, bracket_cli `0x11e0` |
| `seq` | request sequence number. **Responses echo it**; a response with another seq is dropped. Reports carry the base's own seq | lib `pack_msg_head` `0x7844`: `++seq` per transmission (a retransmission gets a new seq). bracket_cli `0x1470`: `srand(time(0)); rand() % 255`. Check: bracket_cli `0x1280` returns −2, lib `handle_read_rsp` / `handle_write_ack` match the queue entry by seq |
| `type` | 1 read, 2 write (panel → base); 3 write ack, 4 read response, 5 report (base → panel) | dispatch: `bracket_serial_recv` `0x90e0`, bracket_cli `0x1ac8` |
| `len` | payload length, big-endian. Frame length = 8 + len + 2 | `0x90c8` |
| `hcksum` | `-(sum of bytes 0..6) & 0xff` | `CheckSum` `0x5fa4` |
| `crc` | **CRC-16/MODBUS** over bytes 0 … 8+L−1, i.e. everything before the CRC: poly 0x8005 reflected (0xA001), init 0xFFFF, refin/refout, xorout 0, check("123456789") = 0x4B37. Sent **low byte first** | `CRC16` `0x5f30`: the classic Modbus hi/lo table code (tables at `0x539c` / `0x549c` in bracket_cli). Checked numerically against the bitwise Modbus CRC |

## Resource ids

A dotted resource name such as `4.1.85` is **not** encoded on the wire. Both binaries use a fixed
table to map it to 3 bytes, `cls idx dev`. The table entry is
`{u8 attr; u8 cls; u8 idx; u8 dev; char *name; u32/float value}`. In libha_bracket it is
`s_tResMaps` at `0x1a018` (17 entries; the 12-byte stride shows in `get_res_map` `0x6ccc`). In
bracket_cli it is at `0x7010` (16 entries).

`cls` also gives the value encoding, used identically by every encoder and decoder (the `0x25` /
`0x12` bit tests in `pack_msg_write` `0x7990`, `msg_parse`, `set_res_value` and others):
`cls` 1 and 4 = **u8**, 0, 2 and 5 = **u32 big-endian**, 3 and 6 = **IEEE-754 float big-endian**.

| name | cls idx dev | type | attr | meaning (ha_master use) |
|---|---|---|---|---|
| `200.1.159` | 00 00 80 | u32 | 01 | **identity / handshake**: bits 31..16 model (`0xF110` = switch base), bits 7..0 channel count |
| `8.0.2022` | 00 01 80 | u32 | 05 | base firmware version |
| `200.4.1` | 00 0f 80 | u32 | 02 | config, written `3` after the handshake (meaning unknown) |
| `4.1.85` `4.2.85` `4.3.85` | 01 00/01/02 55 | u8 | 05 | relay 1..3, read/write 0/1 |
| `200.4.5` | 05 00 55 | u32 | 00 | over-temperature state (bracket_cli calls it `8.0.2014`) |
| `200.4.6` | 05 01 55 | u32 | 00 | over-load state (bracket_cli: `8.0.2014`) |
| `4.103.85` | 05 05 55 | u32 | 06 | config, written `1` after the handshake (power-on state / memory?) |
| `0.11.85` | 06 00 55 | float | 00 | probably voltage. Stored only |
| `0.14.85` | 06 01 55 | float | 04 | current (rounded to 0.01). Stored only |
| `0.12.85` | 06 02 55 | float | 04 | **power, W** (rounded to 0.01) |
| `0.13.85` | 06 04 55 | float | 04 | energy. **Kept host-side** (see below), never read from the base |
| `0.32.85` | 06 08 55 | float | 04 | unknown (chip temperature?). Stored only |
| `200.4.2` | 06 06 55 | float | 02 | config, written `85.0` (over-temperature threshold?; bracket_cli's default is 70.0) |
| `200.4.3` | 06 07 55 | float | 02 | config, written `2200.0` (over-load W?) |
| `200.4.4` | 06 09 55 | float | 02 | config, written `10000.0` (unknown) |

`attr` bits: bit0 = read after a successful handshake (`0x84a4`); bit1 = written with its table
value after the handshake (`dev_config` `0x7b8c`); bit2 = included in the hourly heartbeat
report to the host (`dev_heartbeat` `0x7db8`). On receive, `msg_parse` looks resources up by
`cls`/`idx` only and ignores `dev`.

The following are **not on the wire**. ha_master synthesizes them:

- `14.160.85` (dock class) = `0x01000000 | (200.1.159 >> 16)`, so `0x0100F110` = 16838928 (`0x8448`).
- `13.8.85` = channel count.
- `8.0.2014` (protection), from 200.4.5/200.4.6 reports (`dev_overload_overheat` `0x7ef0`):
  state 1 → 1/2, 2 → 3/4, 0 → 5/6 (over-temperature / over-load).
- `14.21.85`, `13.x.85`, `4.x.28` and the rest of the `13.*.85` family come from ha_master itself,
  not from libha_bracket.

**There are no button-click (`13.x.85`) frames on the UART.** A physical relay change, if the base
has a button, arrives as a `4.x.85` report.

## Messages

| type | direction | payload | total |
|---|---|---|---|
| 1 read | panel → base | `cls idx dev` | 13 |
| 2 write | panel → base | `cls idx dev` + value (1 or 4 bytes) | 14 / 17 |
| 3 write ack | base → panel | `status` (0 = ok; anything else leaves the write pending, so it is retried) | 11 |
| 4 read response | base → panel | `cls idx dev` + value | 12 / 15 |
| 5 report | base → panel | `cls idx dev` + value | 12 / 15 |

Encoders: `pack_msg_read` `0x789c`, `pack_msg_write` `0x7990` (bracket_cli `0x1508` / `0x164c`).
Handlers: `handle_write_ack` `0x883c` (checks the CRC over 9 bytes, requires status 0),
`handle_read_rsp` `0x8378`, `handle_report` `0x8b60`.

There are no separate sync, tick, keepalive or error message types. `bracket_sync` and
`bracket_tick` are host-side functions; see below.

## Host behaviour (libha_bracket inside ha_master)

- **Startup.** `ha_bracket_ioctl` `0x5888` has flags 1 = ready, 4 = associated, 8 = restore,
  32 = OTA. On ready it loads the persisted `4.x.85`, `4.103.85` and `0.13.85` from ha_master
  (`bracket_sync` `0x6c4c`, which only fills the cache) and calls `bracket_ready` `0x6f58`. That
  clears the queue and sends the **handshake read of `200.1.159`**.
- **Handshake answer** (`0x8434`). Model `0xF110` sets online = 1. ha_master then runs
  `dev_config`, which writes every attr-bit1 resource with its value: `200.4.1=3`, `4.103.85=1`,
  `200.4.2=85`, `200.4.3=2200`, `200.4.4=10000`. After a factory reset (restore) it also sets the
  relays to 0. Then it reads every attr-bit0 resource (`8.0.2022`, the relays) and reports
  `14.160.85` and `13.8.85`. A relay above the channel count is disabled (attr = 0). **Any other
  model sets online = 0**, and ha_master does nothing more with the base.
- **No keepalive is sent.** After the handshake the host transmits only on demand: relay writes
  from the cloud or app (`bracket_res_write` `0x913c`, which only allows `4.1/2/3.85`,
  `4.103.85` and the aliases `200.1.10004–10006`), and re-reads after a failed write.
  `200.1.10003` (re-detect) answers from the cache and causes no UART traffic.
- **Liveness.** `bracket_tick` `0x69c8` is called by ha_master's module timer. The `bracket`
  module object is built with `1000` (`0x527908` in ha_master), so the tick is most likely 1 s.
  This is an inference, consistent with the ~50 min heartbeat. The tick counts up; **any received
  write ack, read response or report resets it**. At 61 ticks the base is logged `bracket offline`
  and the queue is dropped. While offline, a **`0.12.85` power report starts the handshake
  again** (`0x8bb8`). So the base evidently sends power reports by itself and needs nothing from
  the panel to keep running; the relays are in the base.
- **Every 3000 ticks** (~50 min) ha_master integrates energy and sends a heartbeat of the attr-bit2
  values to its host. Nothing goes over the UART.
- **Queue and timing** (`dev_serial_send` `0x8008`, `dev_write_timeout` `0x8234`,
  `serial_busy` `0x6418`): one request in flight, up to 20 queued. No answer within **200 ms**
  causes a retransmission with a new seq; there are **3 transmissions** in total, at least
  **51 ms apart**. A write that never gets acked is replaced by a read of the same resource.
  Writes are "cached" while the line is busy.
- **Reports** (`handle_report`):
  - Relays update the cache and are reported up.
  - Power and current are rounded to 0.01. Power goes up when it changes by ≥ 3 W and ≥ 5 %, or
    drops to ≤ 0.1 W.
  - **Energy `0.13.85` is integrated by the host**: `+= P · Δt[s] / 3600`, so in Wh. It is
    persisted by ha_master. It is never read from the base.
- **Input framing.** ha_master passes raw `read()` chunks to `ha_bracket_inject` →
  `bracket_serial_recv`, which parses frames back to back from offset 0 with no resync.

`bracket_cli` (Aqara's CLI) skips all of this. It opens the port, sends one request, does a
`serial_read(…, 1024, 200 ms)` (`0x2268`), and does that 3 times. It prints the value (`puts`) or
returns 0 for an acked write. There is no handshake.

### Why `bracket_cli … 14.160.85` wrote nothing on a pty

`14.160.85` is not in bracket_cli's 16-entry table. Its read builder (`0x1508`) does `strcmp` over
the table, finds nothing and returns −1. `main` (`0xdc8`) then prints `###opration failed.
key=14.160.85` **before any I/O**. It was never about the pty: no ioctl, no wait for the base. The
dock class can't be read with bracket_cli at all. The way to get it is `200.1.159` (which
bracket_cli does support), as shown below.

## Proof: Aqara's bracket_cli against the emulator

The emulator runs on the Mac. `adb reverse tcp:9999 tcp:9999`, then on the panel as shell
`/vendor/bin/socat pty,link=/data/local/tmp/s3a,raw,echo=0 tcp:127.0.0.1:9999`, then
`bracket_cli /data/local/tmp/s3a 115200 …`. The script is
[`tools/bracket/cli_proof.sh`](../../../tools/bracket/cli_proof.sh). `RX` = bytes written by
bracket_cli, `TX` = emulator replies. Run on 2026-11-09 with the emulator as a 2-channel base,
model 0xF110, firmware 23:

```
$ bracket_cli /data/local/tmp/s3a 115200 200.1.159
4044357634                    (= 0xF1100002: model 0xF110, 2 channels -> dock class 0x0100F110)
$ bracket_cli /data/local/tmp/s3a 115200 8.0.2022
23
$ bracket_cli /data/local/tmp/s3a 115200 4.1.85
0
$ bracket_cli /data/local/tmp/s3a 115200 4.1.85 1
(exit 0)
$ bracket_cli /data/local/tmp/s3a 115200 4.1.85
1
$ bracket_cli /data/local/tmp/s3a 115200 0.12.85
60.000000
$ bracket_cli /data/local/tmp/s3a 115200 200.4.3 1800.5
(exit 0)
$ bracket_cli /data/local/tmp/s3a 115200 14.160.85
###opration failed. key=14.160.85        (exit 255, nothing written)

RX 55 80 02 4b 01 00 03 da 00 00 80 23 5a                 read 200.1.159 seq=0x4b crc ok
TX 55 80 02 4b 04 00 07 d3 00 00 80 f1 10 00 02 7a 09    read-rsp 0xF1100002
RX 55 80 02 bf 01 00 03 66 00 01 80 09 ed                 read 8.0.2022
TX 55 80 02 bf 04 00 07 5f 00 01 80 00 00 00 17 33 d5    read-rsp 23
RX 55 80 02 bf 01 00 03 66 01 00 55 98 22                 read 4.1.85
TX 55 80 02 bf 04 00 04 62 01 00 55 00 13 e2             read-rsp 0
RX 55 80 02 bf 02 00 04 64 01 00 55 01 da 08             write 4.1.85=1 (u8) crc ok
TX 55 80 02 bf 03 00 01 66 00 c6 ac                       write-ack status 0
TX 55 80 02 21 05 00 04 ff 01 00 55 01 1e 54             report 4.1.85=1 (other seq: skipped by bracket_cli)
RX 55 80 02 c2 01 00 03 63 06 02 55 ee f2                 read 0.12.85
TX 55 80 02 c2 04 00 07 5c 06 02 55 42 70 00 00 e1 20    read-rsp 60.0 (float BE)
RX 55 80 02 c2 02 00 07 5e 06 07 55 44 e1 10 00 22 81    write 200.4.3=1800.5 (float BE) crc ok
TX 55 80 02 c2 03 00 01 63 00 cf d1                       write-ack status 0
```

Negative tests (`cli_proof.sh --fault …`) show that bracket_cli really checks what the decoder
assumes. In every case below it rejects the reply, retransmits 3 times and exits 255 with no value:

| `--fault` | what the emulator does to each reply | bracket_cli's result |
|---|---|---|
| `crc` | flips a CRC bit | rejected |
| `crc-be` | sends the CRC high byte first | rejected, so the CRC really is little-endian on the wire |
| `seq` | sends seq + 1 (with the header checksum and CRC fixed up) | rejected |

```
RX 55 80 02 f9 01 00 03 2c 00 00 80 9a 7f              read 200.1.159
TX 55 80 02 f9 04 00 07 25 00 00 80 f1 10 00 02 4e 9f  (crc flipped)
RX 55 80 02 f9 01 00 03 2c 00 00 80 9a 7f              retry …  (exit 255)
```

The JVM tests (`BracketProtocolTest`) use these exact bracket_cli frames as vectors. The Kotlin
encoder reproduces them byte for byte.

### The app's driver against the same emulator, on the panel

With `--es base_transport tcp:127.0.0.1:9999` (and `adb reverse`), the app ran the full
libha_bracket startup sequence. Excerpt from `adb logcat -s S1Base`:

```
TX 55 80 02 01 01 00 03 24 00 00 80 96 fd read 200.1.159 (try 1)
RX … Report 0.12.85=0.0                       power report while offline: handshake
RX 55 80 02 01 04 00 07 1d 00 00 80 f1 10 00 02 ee f2 ReadRsp 200.1.159=4044357634
base online: model 0xf110, 2 channel(s)
dock class 16838928 -> switch (2ch) (via uart read)
TX … write 200.4.1=3 / 4.103.85=1 / 200.4.2=85.0 / 200.4.3=2200.0 / 200.4.4=10000.0 (each acked)
TX … read 8.0.2022 -> 23, read 4.1.85 -> 0, read 4.2.85 -> 0
base layout none -> switch (2ch); rebuilding entities
(emulator stdin "t1", "o1", "o0": physical toggle, over-load alarm, cleared)
RX … Report 4.1.85=1   RX … Report 0.12.85=60.0   RX … Report 200.4.6=1 -> 8.0.2014=2   … 200.4.6=0 -> 6
```

On the real `/dev/ttyS3` with no base attached: ha_master is stopped, then the port is opened, the
termios verified, 3 handshake reads go out 200 ms apart, there is no answer, and the base reads as
`none`.

## Limitations

- **Not verified against a real base.** Unverified items: the base's reply latency (we assume
  < 200 ms, like bracket_cli); whether and how often it sends power reports unprompted (ha_master
  relies on it for re-detection); the meaning of `200.4.1`, `200.4.4`, `4.103.85`, `0.11.85` and
  `0.32.85`; whether the base needs the config writes; the middle byte of `200.1.159`.
- **Only model `0xF110` is supported**, as in ha_master. Other models show as
  `unknown (0x7f)`: the app reports the synthetic dock class `0x7F000000 | model`.
- The Ethernet base (dock class `0x10`) is not handled by libha_bracket at all, so it is not
  supported over UART either.
- No click events exist on this link. The *Base button N* entities never fire with the UART backend.
- Energy is the host-side integral of the reported power, in Wh. It is persisted by the app
  (`s1base` prefs) and starts at 0, not at ha_master's counter.
- The base firmware update (`bracket_ota`, `bracket_isp.sh`, which drives GPIO PH6/PH7) is out of
  scope. It still needs ha_master.
