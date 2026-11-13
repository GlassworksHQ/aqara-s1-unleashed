#!/usr/bin/env python3
"""Replace the power-on splash (BOOTLOGO.BMP) inside a backup of the S1 Plus `bootloader` partition.

That partition is Allwinner's "boot-resource": a small FAT16 filesystem the stock bootloader reads
the splash from (it also holds battery/fastboot images). This rewrites only the file's data clusters,
in place: same size, same clusters, FAT tables and directory untouched. It therefore refuses any
image whose size differs from the stock file (720x1440 24-bit BMP = 3,110,454 bytes).

    tools/branding/set-bootlogo.py <bootloader.img> <new-bootlogo.bmp> <out.img>
    tools/branding/set-bootlogo.py --ours <bootloader.img> <branding/bootlogos.json>

Reads the backup and writes a new file; never touches the panel (fel-install.sh flashes it).
--ours reads only: it prints "ours <name>" when the image's BOOTLOGO.BMP is one of the splashes we
have shipped (fel-install.sh --undo refuses that backup), else "not ours".
"""
import hashlib
import json
import struct
import sys


class NotBootResource(Exception):
    pass


def find_bootlogo(img):
    bps, spc, reserved, nfats, nroot, _, _, spf = struct.unpack_from('<HBHBHHBH', img, 11)
    if img[54:62] != b'FAT16   ':
        raise NotBootResource('not a FAT16 boot-resource image')
    root = (reserved + nfats * spf) * bps
    data = root + nroot * 32
    for i in range(nroot):
        e = img[root + i * 32: root + i * 32 + 32]
        if e[:11] == b'BOOTLOGOBMP':
            first, size = struct.unpack_from('<HI', e, 26)
            return bps, spc, reserved, data, first, size
    raise NotBootResource('BOOTLOGO.BMP not found in the root directory')


def chain(img, bps, reserved, first):
    fat, c, out = reserved * bps, first, []
    while 2 <= c < 0xFFF8:
        out.append(c)
        c = struct.unpack_from('<H', img, fat + 2 * c)[0]
        if len(out) > 1 << 16:
            raise NotBootResource('FAT chain loop')
    return out


def read_bootlogo(img):
    """BOOTLOGO.BMP's bytes, through its FAT chain."""
    bps, spc, reserved, data, first, size = find_bootlogo(img)
    csize = bps * spc
    out = b''.join(img[data + (c - 2) * csize: data + (c - 1) * csize] for c in chain(img, bps, reserved, first))
    if len(out) < size:
        raise NotBootResource('BOOTLOGO.BMP runs past the image')
    return out[:size]


def ours(img_path, list_path):
    known = {b['sha256']: b['name'] for b in json.load(open(list_path, encoding='utf-8'))['bootlogos']}
    try:
        sha = hashlib.sha256(read_bootlogo(open(img_path, 'rb').read())).hexdigest()
    except (NotBootResource, struct.error) as e:
        # We only ever write our splash into a readable BOOTLOGO.BMP and leave the FAT as it was,
        # so an image without one doesn't hold ours.
        print(f'not ours ({e})')
        return
    print(f'ours {known[sha]}' if sha in known else 'not ours')


def replace(img_path, logo_path, out_path):
    img = bytearray(open(img_path, 'rb').read())
    logo = open(logo_path, 'rb').read()
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
    open(out_path, 'wb').write(img)
    print(f'BOOTLOGO.BMP replaced in place ({size} bytes, {len(clusters)} clusters) -> {out_path}')


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--ours':
        return ours(sys.argv[2], sys.argv[3])
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    try:
        replace(*sys.argv[1:])
    except NotBootResource as e:
        sys.exit(str(e))


if __name__ == '__main__':
    main()
