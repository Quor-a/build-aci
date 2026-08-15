"""Dump the string pool of a binary AndroidManifest.xml (extracted from an APK) so we can
design the runtime manifest rewriter (package / label string replacement)."""
import struct
import sys


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def main():
    data = open(sys.argv[1], "rb").read()
    typ = u16(data, 0)
    hdr = u16(data, 2)
    size = u32(data, 4)
    print("doc type=0x%04x headerSize=%d size=%d" % (typ, hdr, size))

    sp_off = hdr
    styp = u16(data, sp_off)
    shdr = u16(data, sp_off + 2)
    ssize = u32(data, sp_off + 4)
    count = u32(data, sp_off + 8)
    stylecount = u32(data, sp_off + 12)
    flags = u32(data, sp_off + 16)
    strings_start = u32(data, sp_off + 20)
    utf8 = (flags & 0x100) != 0
    print("stringpool type=0x%04x headerSize=%d size=%d" % (styp, shdr, ssize))
    print("count=%d stylecount=%d flags=0x%08x utf8=%s" % (count, stylecount, flags, utf8))

    off = sp_off + shdr
    offs = [u32(data, off + 4 * i) for i in range(count)]
    base = sp_off + strings_start
    for i in range(count):
        p = base + offs[i]
        if utf8:
            u16len = u16(data, p)
            p += 2
            l = data[p]
            p += 1
            if l & 0x80:
                l2 = data[p]
                p += 1
                ln = ((l & 0x7F) << 8) | l2
            else:
                ln = l
            s = data[p:p + ln].decode("utf-8", errors="replace")
        else:
            n = u16(data, p)
            p += 2
            s = data[p:p + 2 * n].decode("utf-16-le", errors="replace")
        print("[%d] %r" % (i, s))


if __name__ == "__main__":
    main()
