import { describe, expect, it } from 'vitest';
import { Fastboot, FastbootError } from '../src';
import type { BulkTransport } from '@glassworks/fel/transport';
import { FakeUboot } from '@glassworks/fel/fakes';

describe('Fastboot against a simulated U-Boot', () => {
  it('getvar', async () => {
    const fb = new Fastboot(new FakeUboot());
    expect(await fb.getvar('product')).toBe('sunxi');
    expect(await fb.partitionSize('boot')).toBe(0x2000000 / 64);
  });

  it('readPartition sends exactly fb-read-partition.sh commands and returns the partition', async () => {
    const u = new FakeUboot();
    const fb = new Fastboot(u);
    const got = await fb.readPartition('boot');
    expect(got).toEqual(u.data('boot'));
    expect(u.commands).toEqual([
      'getvar:partition-size:boot',
      'oem run:mmc dev 1',
      'oem run:part start mmc 1 boot s',
      'oem run:part size mmc 1 boot n',
      'oem run:mmc read 0x50000000 $s $n',
      'oem run:setexpr fastboot_upload_size $n * 200',
      'upload',
    ]);
    expect(u.replies).toHaveLength(0);
  });

  it('flashVerified writes and reads back; INFO lines are logged', async () => {
    const u = new FakeUboot();
    const fb = new Fastboot(u);
    const lines: string[] = [];
    fb.log = (l) => lines.push(l);
    const img = new Uint8Array(0x10000).fill(0xa5);
    await fb.flashVerified('vbmeta', img);
    expect(u.data('vbmeta')).toEqual(img);
    expect(lines).toContain('writing');
  });

  it('refuses an image larger than the partition, and an unknown one', async () => {
    const fb = new Fastboot(new FakeUboot());
    await expect(fb.flash('vbmeta', new Uint8Array(0x10001))).rejects.toThrow(/larger than partition/);
    await expect(fb.readPartition('nope')).rejects.toThrow(FastbootError);
  });

  it('FAIL replies become errors', async () => {
    const fb = new Fastboot(new FakeUboot());
    await expect(fb.oemRun('mmc dev 0')).rejects.toThrow(/command failed/);
  });

  it('refuses commands over 64 bytes', async () => {
    const fb = new Fastboot(new FakeUboot());
    await expect(fb.oemRun('x'.repeat(60))).rejects.toThrow(/too long/);
  });
});

describe('Fastboot reboot', () => {
  it('a disconnect right after "reboot" is not an error (U-Boot resets at once)', async () => {
    const u = new FakeUboot();
    u.inPacket = async () => { throw new DOMException('The device was disconnected.', 'NetworkError'); };
    const fb = new Fastboot(u);
    const lines: string[] = [];
    fb.log = (l) => lines.push(l);
    await expect(fb.reboot()).resolves.toBeUndefined();
    expect(u.commands.at(-1)).toBe('reboot');
    expect(u.closed).toBe(true);
    expect(lines[0]).toMatch(/restarting/);
  });

  it('but a failure to send it still is', async () => {
    const u = new FakeUboot();
    u.out = async () => { throw new Error('USB write failed'); };
    await expect(new Fastboot(u).reboot()).rejects.toThrow(/write failed/);
  });
});

describe('the S1 wire', () => {
  // Every USB call of a flash, read-back and reboot, with its size and timeout. The S1 install
  // depends on these exact calls (1 MiB transfers, 256-byte reply reads, 10 s for getvar, 120 s
  // otherwise). A change here needs a real panel install before merge.
  it('flashVerified and reboot make exactly these USB calls', async () => {
    const u = new FakeUboot([['boot', 0x400000]]);
    const calls: string[] = [];
    const rec: BulkTransport = {
      out: async (d, ms) => { calls.push(`out ${d.length < 64 ? new TextDecoder().decode(d) : d.length} ${ms}`); await u.out(d); },
      in: async (n, ms) => { calls.push(`in ${n} ${ms}`); return u.in(n); },
      inPacket: async (n, ms) => { calls.push(`inPacket ${n} ${ms}`); return u.inPacket(n); },
      close: async () => { calls.push('close'); await u.close(); },
    };
    const fb = new Fastboot(rec);
    await fb.flashVerified('boot', new Uint8Array(0x280005).fill(1));
    await fb.reboot();
    const reply = (ms: number) => `inPacket 256 ${ms}`;
    const cmd = (c: string, ms = 120000) => [`out ${c} ${ms}`, reply(ms)];
    expect(calls).toEqual([
      ...cmd('getvar:partition-size:boot', 10000),
      ...cmd('download:00280005'),
      'out 1048576 120000', 'out 1048576 120000', 'out 524293 120000', reply(120000),
      ...cmd('flash:boot'), reply(120000), // INFOwriting, then OKAY
      ...cmd('getvar:partition-size:boot', 10000),
      ...cmd('oem run:mmc dev 1'),
      ...cmd('oem run:part start mmc 1 boot s'),
      ...cmd('oem run:part size mmc 1 boot n'),
      ...cmd('oem run:mmc read 0x50000000 $s $n'),
      ...cmd('oem run:setexpr fastboot_upload_size $n * 200'),
      ...cmd('upload'),
      'in 1048576 120000', 'in 1048576 120000', 'in 1048576 120000', 'in 1048576 120000', reply(120000),
      'out reboot 10000', 'inPacket 256 5000', 'close',
    ]);
  });
});
