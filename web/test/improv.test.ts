import { describe, expect, it } from 'vitest';
import { ImprovSerialClient, PacketReader, PacketType, Rpc, State, frame, parseResult, rpc, strings, wifiSettings, type ByteStream, type Packet } from '../src/improv';

/** The panel's side of Improv Serial, as the app will implement it (state Ready = no Allow tap). */
class FakePanel implements ByteStream {
  private reader = new PacketReader();
  private cb: (c: Uint8Array) => void = () => {};
  password = 'right';
  got: Packet[] = [];
  onData(cb: (c: Uint8Array) => void) { this.cb = cb; }
  private send(type: number, data: Uint8Array) {
    const f = frame(type, data);
    // deliver in two pieces to exercise reassembly
    queueMicrotask(() => { this.cb(f.subarray(0, 5)); this.cb(f.subarray(5)); });
  }
  private result(cmd: number, ...ss: string[]) {
    const s = strings(...ss);
    const d = new Uint8Array(2 + s.length); d[0] = cmd; d[1] = s.length; d.set(s, 2);
    this.send(PacketType.RpcResult, d);
  }
  async write(data: Uint8Array) {
    for (const p of this.reader.push(data)) {
      this.got.push(p);
      expect(p.type).toBe(PacketType.Rpc);
      const { command } = { command: p.data[0] };
      const body = p.data.subarray(2, 2 + p.data[1]!);
      if (command === Rpc.RequestState) this.send(PacketType.State, Uint8Array.of(State.Ready));
      if (command === Rpc.RequestInfo) this.result(Rpc.RequestInfo, 'S1 Kiosk', '0.1.0', 'Allwinner A133', 'S1 Panel');
      if (command === Rpc.ScanWifi) {
        this.result(Rpc.ScanWifi, 'Home Net', '-61', 'YES');
        this.result(Rpc.ScanWifi, 'Cafe', '-70', 'NO');
        this.result(Rpc.ScanWifi, 'Home Net', '-48', 'YES');
        this.result(Rpc.ScanWifi);
      }
      if (command === Rpc.WifiSettings) {
        const r = parseResult(Uint8Array.of(0, body.length, ...body));
        this.send(PacketType.State, Uint8Array.of(State.Provisioning));
        if (r.strings[1] === this.password) {
          this.send(PacketType.State, Uint8Array.of(State.Provisioned));
          this.result(Rpc.WifiSettings, 'http://192.168.1.50:6053');
        } else {
          this.send(PacketType.Error, Uint8Array.of(0x03));
        }
      }
    }
  }
  async close() {}
}

describe('Improv Serial framing', () => {
  it('frames packets exactly as the spec: IMPROV, version 1, type, length, data, checksum', () => {
    const f = rpc(Rpc.RequestState);
    expect(Array.from(f)).toEqual([0x49, 0x4d, 0x50, 0x52, 0x4f, 0x56, 1, 0x03, 2, 0x02, 0, (0x49 + 0x4d + 0x50 + 0x52 + 0x4f + 0x56 + 1 + 3 + 2 + 2) & 0xff]);
    const w = wifiSettings('Net', 'pw');
    expect(Array.from(w.subarray(9, 9 + w[8]!))).toEqual([0x01, 7, 3, 0x4e, 0x65, 0x74, 2, 0x70, 0x77]);
  });

  it('the reader resyncs after garbage and a corrupt packet', () => {
    const good = frame(PacketType.State, Uint8Array.of(State.Ready));
    const bad = frame(PacketType.State, Uint8Array.of(State.Provisioned)); bad[bad.length - 1]! ^= 0xff;
    const r = new PacketReader();
    const got = [...r.push(Uint8Array.of(1, 2, 3)), ...r.push(bad), ...r.push(good.subarray(0, 4)), ...r.push(good.subarray(4))];
    expect(got).toEqual([{ type: PacketType.State, data: Uint8Array.of(State.Ready) }]);
  });
});

describe('ImprovSerialClient against a simulated panel', () => {
  it('state, info, scan (deduplicated, strongest first)', async () => {
    const c = new ImprovSerialClient(new FakePanel());
    expect(await c.requestState()).toBe(State.Ready);
    expect((await c.info()).name).toBe('S1 Panel');
    expect(await c.scan()).toEqual([
      { ssid: 'Home Net', rssi: -48, secure: true },
      { ssid: 'Cafe', rssi: -70, secure: false },
    ]);
  });

  it('provisions and returns the redirect URL; a wrong password is a clear error', async () => {
    const panel = new FakePanel();
    const c = new ImprovSerialClient(panel);
    const progress: string[] = [];
    expect(await c.provision('Home Net', 'right', (s) => progress.push(s))).toBe('http://192.168.1.50:6053');
    expect(progress).toEqual(['Connecting to Home Net…']);
    await expect(c.provision('Home Net', 'wrong')).rejects.toThrow(/could not join/);
  });
});
