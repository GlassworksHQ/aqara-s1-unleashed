# improv-serial

An [Improv Wi-Fi Serial](https://www.improv-wifi.com/serial/) client for the browser: it asks a
device which networks it sees and sends it Wi-Fi credentials. It works over any byte stream (a
UART through Web Serial, a socket, a test double). An optional adapter connects it to an
**Android app over WebUSB adb**, so a web page can put an Android device on Wi-Fi over one USB
cable, with no Bluetooth and no app on a phone.

The Android side is `ImprovAdbServer` in the [improv](../../../app/libs/improv/README.md) Kotlin
library. Both were extracted from S1 Unleashed, whose web installer uses this package to set up
the panel's Wi-Fi. MIT licensed.

## Use

```ts
import { ImprovSerialClient, State } from 'improv-serial';
import { localAbstract, openAdbStream } from 'improv-serial/adb';
import type { Adb } from '@yume-chan/adb';

export async function setUpWifi(adb: Adb, ssid: string, password: string): Promise<string> {
  const client = new ImprovSerialClient(await openAdbStream(adb, localAbstract('myapp_improv')), {
    deviceLabel: 'tablet', // error messages say "The tablet could not join that network…"
  });
  try {
    if ((await client.requestState()) === State.Provisioned) console.log('already on Wi-Fi');
    const info = await client.info();                 // firmware, version, chip, name
    const networks = await client.scan();             // deduplicated, strongest first
    console.log(info.name, networks.map((n) => n.ssid));
    return await client.provision(ssid, password, (s) => console.log(s)); // the redirect URL, or throws
  } finally {
    await client.close();
  }
}
```

`adb` is a connected [Tango (ya-webadb)](https://github.com/yume-chan/ya-webadb) `Adb` instance,
for example from `@yume-chan/adb-daemon-webusb`. For any other transport, implement `ByteStream`
(`write`, `onData`, `close`) and pass it to `ImprovSerialClient`.

`openAdbStream` also works around an Android 10 adbd quirk: adbd needs a NUL-terminated service
name, which Tango leaves out on newer protocol versions. Without the NUL, adbd can refuse
`localabstract:` services.

Exports: `ImprovSerialClient` (`requestState`, `info`, `scan`, `provision`, `close`, `onState`),
the packet helpers `frame`, `rpc`, `strings`, `wifiSettings`, `parseResult` and `PacketReader`,
the constants `PacketType`, `Rpc` and `State`, and `errorMessage`. From `improv-serial/adb`:
`openAdbStream` and `localAbstract`.

## Status

Not on npm yet (`"private": true`). It ships as TypeScript source, so use it from a bundler (Vite,
esbuild…). `@yume-chan/adb` is an optional peer dependency, needed only for `improv-serial/adb`.

## Tests

`packages/improv-serial/test/serial.test.ts` tests the client against a simulated device, and
`readme.test.ts` type-checks the example above. Both run with the web installer's tests: `cd kiln &&
npm test`.
