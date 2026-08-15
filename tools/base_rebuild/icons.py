"""Generate a simple default launcher icon (192x192) into res/mipmap-*/ic_launcher.png.

No PIL needed: minimal PNG encoder (RGBA, zlib deflate). The same 192px PNG is placed
in every density bucket as a placeholder; users replace it at build time.
"""
import zlib
import struct
import os

W = H = 192


def make_png(path):
    raw = bytearray()
    cx = cy = W / 2.0
    for y in range(H):
        raw.append(0)  # PNG filter type 0 (none) per scanline
        for x in range(W):
            dx = x - cx
            dy = y - cy
            d = (dx * dx + dy * dy) ** 0.5
            if d < 70:
                raw += bytes((255, 255, 255, 255))  # white center circle
            else:
                raw += bytes((0, 137, 123, 255))    # teal background
    comp = zlib.compress(bytes(raw), 9)

    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        c += struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
        return c

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", comp)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
    for d in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        dd = os.path.join(base, "mipmap-" + d)
        os.makedirs(dd, exist_ok=True)
        make_png(os.path.join(dd, "ic_launcher.png"))
    print("icons generated under", base)


if __name__ == "__main__":
    main()
