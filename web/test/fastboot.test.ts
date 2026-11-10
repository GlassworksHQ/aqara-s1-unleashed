import { describe, expect, it } from 'vitest';
import { Fastboot, FastbootError } from '../src/fastboot';
import { FakeUboot } from './fakes';

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
