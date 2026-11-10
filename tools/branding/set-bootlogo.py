#!/usr/bin/env python3
"""Replace the power-on splash (BOOTLOGO.BMP) inside a backup of the S1 Plus `bootloader` partition.

That partition is Allwinner's "boot-resource": a small FAT16 filesystem the stock bootloader reads
the splash from (it also holds battery/fastboot images). This rewrites only the file's data clusters,
in place: same size, same clusters, FAT tables and directory untouched. It therefore refuses any
image whose size differs from the stock file (720x1440 24-bit BMP = 3,110,454 bytes).

    tools/branding/set-bootlogo.py <bootloader.img> <new-bootlogo.bmp> <out.img>

Reads the backup and writes a new file; never touches the panel (fel-install.sh flashes it).
"""
import struct
import sys


def find_bootlogo(img):
    bps, spc, reserved, nfats, nroot, _, _, spf = struct.unpack_from('<HBHBHHBH', img, 11)
    if img[54:62] != b'FAT16   ':
        sys.exit('not a FAT16 boot-resource image')
    root = (reserved + nfats * spf) * bps
    data = root + nroot * 32
    for i in range(nroot):
        e = img[root + i * 32: root + i * 32 + 32]
        if e[:11] == b'BOOTLOGOBMP':
            first, size = struct.unpack_from('<HI', e, 26)
            return bps, spc, reserved, data, first, size
    sys.exit('BOOTLOGO.BMP not found in the root directory')


def chain(img, bps, reserved, first):
    fat, c, out = reserved * bps, first, []
    while 2 <= c < 0xFFF8:
        out.append(c)
        c = struct.unpack_from('<H', img, fat + 2 * c)[0]
        if len(out) > 1 << 16:
            sys.exit('FAT chain loop')
    return out


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    img = bytearray(open(sys.argv[1], 'rb').read())
    logo = open(sys.argv[2], 'rb').read()
    if logo[:2] != b'BM':
        sys.exit('new logo is not a BMP')
    w, h, _, bpp, comp = struct.unpack_from('<iiHHI', logo, 18)
    if (w, abs(h), bpp, comp) != (720, 1440, 24, 0):
        sys.exit(f'new logo must be 720x1440, 24-bit, uncompressed (got {w}x{h}, {bpp} bpp, comp {comp})')
    bps, spc, reserved, data, first, size = find_bootlogo(img)
    if len(logo) != size:
        sys.exit(f'size mismatch: stock BOOTLOGO.BMP is {size} bytes, new one is {len(logo)}')
    csize = bps * spc
    clusters = chain(img, bps, reserved, first)
    if len(clusters) * csize < size:
        sys.exit('FAT chain shorter than the file')
    for n, c in enumerate(clusters):
        part = logo[n * csize:(n + 1) * csize]
        if not part:
            break
        off = data + (c - 2) * csize
        img[off:off + len(part)] = part
    # Read it back through the same chain before writing anything out.
    back = b''.join(bytes(img[data + (c - 2) * csize: data + (c - 1) * csize]) for c in clusters)[:size]
    if back != logo:
        sys.exit('verification failed')
    open(sys.argv[3], 'wb').write(img)
    print(f'BOOTLOGO.BMP replaced in place ({size} bytes, {len(clusters)} clusters) -> {sys.argv[3]}')


if __name__ == '__main__':
    main()
