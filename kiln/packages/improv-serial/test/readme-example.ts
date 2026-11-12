// The README example with relative imports; type-checked by tsc and imported by readme.test.ts.
import { ImprovSerialClient, State } from '../src';
import { localAbstract, openAdbStream } from '../src/adb';
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
