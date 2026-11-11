// A ByteStream over adb (Tango / ya-webadb, e.g. on WebUSB) to a `localabstract:` socket in an
// Android app, such as the improv library's ImprovAdbServer.
import type { Adb } from '@yume-chan/adb';
import type { ByteStream } from './serial';

/** The adb service for an app's abstract socket `@name`. */
export function localAbstract(name: string): string {
  return `localabstract:${name}`;
}

/**
 * Opens an adb stream to `service` (e.g. `localAbstract('my_improv')`).
 *
 * Android 10's adbd reads the OPEN payload as a C string. Tango omits the NUL terminator for
 * protocol >= 0x01000001, so adbd can read past the name into garbage and refuse the service (seen
 * on an Android 10 device: refused without the NUL, opens with it). Real adb always sends it, and so
 * does this.
 */
export async function openAdbStream(adb: Adb, service: string): Promise<ByteStream> {
  const socket = await adb.createSocket(service.endsWith('\0') ? service : `${service}\0`);
  const writer = socket.writable.getWriter();
  return {
    write: (data) => writer.write(data),
    onData: (cb) => {
      void (async () => {
        const reader = socket.readable.getReader();
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            cb(value);
          }
        } catch { /* closed */ }
      })();
    },
    close: async () => { try { await socket.close(); } catch { /* already closed */ } },
  };
}
