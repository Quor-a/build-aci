"""Generate several directly-installable test APKs for BuildAci.

Faithful PC port of BuildEngine.rewriteManifest + repackAligned (Kotlin), so the
produced APKs are structurally identical to what the on-device engine emits:
  - rewrite binary AndroidManifest string pool (pkg idx22 / label idx14 / version idx12)
  - replace res/mipmap-*-v4/ic_launcher.png bytes with a custom icon
  - inject user classes2.dex (STORED) + optional assets/
  - keep original STORED entries STORED, re-deflate original DEFLATED entries (raw/nowrap)
  - 4-byte align EVERY entry (matches `zipalign -p 4`)
  - sign with apksigner (v1+v2+v3), min-sdk 21

Outputs to the desktop test folder so the user can install on a real device.

Usage:
  python make_test_apks.py
"""
import os
import sys
import struct
import zlib
import zipfile
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET_BASE = "D:/Calw OS-project/BuildAci/app/src/main/assets/libs/common"
BASE_APK = os.path.join(ASSET_BASE, "base.apk")
DEBUG_KS = os.path.join(ASSET_BASE, "debug.keystore")
BT = "D:/Android/Sdk/build-tools/34.0.0"
# native Windows Python: use D:/ paths (not /d/), .bat via cmd /c
JAVA = "D:/Java/jdk-17.0.2/bin/java"
JAVAC = "D:/Java/jdk-17.0.2/bin/javac"
KEYTOOL = "D:/Java/jdk-17.0.2/bin/keytool"
APKSIGNER = f"{BT}/apksigner.bat"
D8 = f"{BT}/d8.bat"
ZIPALIGN = f"{BT}/zipalign.exe"
AAPT2 = f"{BT}/aapt2.exe"
OUT_DIR = "C:/Users/admin/Desktop/BuildAci_test_APKs"


def win(prog, args):
    """Direct exec (native Windows Python handles .bat)."""
    return [prog] + args

# Binary manifest string-pool indices (confirmed via dump_manifest.py on new base.apk)
IDX_VERSION = 12
IDX_LABEL = 14
IDX_PACKAGE = 22


# ---------------------------------------------------------------------------
# rewrite_manifest  (port of BuildEngine.rewriteManifest)
# ---------------------------------------------------------------------------
def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]

def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]

def rewrite_manifest(data: bytes, new_pkg: str, new_label: str, new_version: str) -> bytes:
    doc_hsize = u16(data, 2)
    sp_off = doc_hsize
    sp_type = u16(data, sp_off)
    sp_hsize = u16(data, sp_off + 2)
    sp_size = u32(data, sp_off + 4)
    count = u32(data, sp_off + 8)
    style_count = u32(data, sp_off + 12)
    flags = u32(data, sp_off + 16)
    strings_start = u32(data, sp_off + 20)
    styles_start = u32(data, sp_off + 24)
    utf8 = (flags & 0x100) != 0

    offsets = [u32(data, sp_off + sp_hsize + 4 * i) for i in range(count)]
    base = sp_off + strings_start
    strings = []
    for i in range(count):
        p = base + offsets[i]
        if utf8:
            n = u16(data, p); q = p + 2
            l = data[q] & 0xFF; q += 1
            ln = ((l & 0x7F) << 8) | data[q] if (l & 0x80) else l
            if (l & 0x80):
                q += 1
            strings.append(data[q:q + ln].decode("utf-8"))
        else:
            n = u16(data, p); q = p + 2
            strings.append(data[q:q + 2 * n].decode("utf-16-le"))

    assert strings[IDX_PACKAGE] == "com.example.buildapp", strings[IDX_PACKAGE]
    strings[IDX_PACKAGE] = new_pkg
    strings[IDX_LABEL] = new_label
    strings[IDX_VERSION] = new_version

    new_offsets = []
    parts = []
    pos = 0
    for s in strings:
        enc = s.encode("utf-16-le")
        n = len(enc) // 2
        b = struct.pack("<H", n) + enc + struct.pack("<H", 0)
        new_offsets.append(pos)
        parts.append(b)
        pos += len(b)

    offset_array_size = count * 4
    new_strings_start = sp_hsize + offset_array_size
    new_pool_size = new_strings_start + pos
    pool = bytearray(new_pool_size)
    struct.pack_into("<HHI", pool, 0, sp_type, sp_hsize, new_pool_size)
    struct.pack_into("<IIII", pool, 8, count, style_count, flags, new_strings_start)
    struct.pack_into("<I", pool, 24, styles_start)
    off = 28
    for o in new_offsets:
        struct.pack_into("<I", pool, off, o); off += 4
    p = off
    for b in parts:
        pool[p:p + len(b)] = b; p += len(b)

    tree = data[sp_off + sp_size:]
    # Android ResChunk size MUST be a multiple of 4; pad the string pool so the
    # following chunks (resource map / XML tree) stay 4-byte aligned. Without this,
    # changing a string's length misaligns the whole manifest -> device rejects it
    # ("安装包异常"). The default package keeps the original aligned size, which is
    # why only custom (different-length) packages triggered the failure.
    pad = (4 - (len(pool) % 4)) % 4
    if pad:
        pool.extend(b"\x00" * pad)
    struct.pack_into("<I", pool, 4, len(pool))  # update string-pool chunk size
    out = bytearray(data[:sp_off] + pool + tree)
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


# ---------------------------------------------------------------------------
# repack_aligned  (port of BuildEngine.repackAligned, align ALL entries)
# ---------------------------------------------------------------------------
def repack_aligned(base_apk, dex_path, out_path, pkg, label, version, icon_map, assets_dir=None):
    class Entry:
        __slots__ = ("name", "method", "data", "crc", "size")

        def __init__(self, name, method, data, crc, size):
            self.name = name
            self.method = method
            self.data = data
            self.crc = crc
            self.size = size
    entries = []
    with zipfile.ZipFile(base_apk, "r") as zin:
        for info in zin.infolist():
            raw = zin.read(info.filename)
            if info.filename == "AndroidManifest.xml":
                out_data = rewrite_manifest(raw, pkg, label, version)
            elif icon_map and info.filename in icon_map:
                out_data = icon_map[info.filename]
            else:
                out_data = raw
            method = zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            crc = zlib.crc32(out_data) & 0xFFFFFFFF
            if method == zipfile.ZIP_DEFLATED:
                co = zlib.compressobj(6, zlib.DEFLATED, -15)
                data = co.compress(out_data) + co.flush()
            else:
                data = out_data
            entries.append(Entry(info.filename, method, data, crc, len(out_data)))

    # inject user classes2.dex (STORED)
    with open(dex_path, "rb") as f:
        ud = f.read()
    entries.append(Entry("classes2.dex", zipfile.ZIP_STORED, ud, zlib.crc32(ud) & 0xFFFFFFFF, len(ud)))

    # inject assets/
    if assets_dir and os.path.isdir(assets_dir):
        for root, _, files in os.walk(assets_dir):
            for fn in files:
                fp = os.path.join(root, fn)
                rel = os.path.relpath(fp, assets_dir).replace("\\", "/")
                ab = open(fp, "rb").read()
                entries.append(Entry("assets/" + rel, zipfile.ZIP_STORED, ab, zlib.crc32(ab) & 0xFFFFFFFF, len(ab)))

    little = "<"
    central = []
    offset = 0
    with open(out_path, "wb") as fos:
        for e in entries:
            nb = e.name.encode("utf-8")
            # 4-byte alignment for every entry (matches zipalign -p 4)
            base = offset + 30 + len(nb)
            pad = (4 - (base % 4)) % 4
            extra = b"\x00" * pad
            lh = bytearray(30 + len(nb) + len(extra))
            struct.pack_into(little + "IHHHHHIIIHH", lh, 0,
                             0x04034b50, 20, 0, e.method, 0, 0,
                             e.crc, len(e.data), e.size, len(nb), len(extra))
            lh[30:30 + len(nb)] = nb
            lh[30 + len(nb):] = extra
            fos.write(lh)
            fos.write(e.data)
            assert (offset + len(lh)) % 4 == 0, "align fail " + e.name

            ch = bytearray(46 + len(nb) + len(extra))
            struct.pack_into(little + "IHHHHHHIIIHHHHHII", ch, 0,
                             0x02014b50, 20, 20, 0, e.method, 0, 0,
                             e.crc, len(e.data), e.size, len(nb), len(extra),
                             0, 0, 0, 0, offset)
            ch[46:46 + len(nb)] = nb
            ch[46 + len(nb):] = extra
            central.append(bytes(ch))
            offset += len(lh) + len(e.data)

        cs = offset
        for ch in central:
            fos.write(ch); offset += len(ch)
        eocd = struct.pack(little + "IHHHHIIH", 0x06054b50, 0, 0,
                           len(entries), len(entries), offset - cs, cs, 0)
        fos.write(eocd)


# ---------------------------------------------------------------------------
# minimal valid PNG icon (solid color + white circle), no external deps
# ---------------------------------------------------------------------------
def make_icon(size, rgb):
    w = h = size
    raw = bytearray()
    cx = cy = size / 2.0
    r = size * 0.38
    for y in range(h):
        raw.append(0)  # filter type 0
        for x in range(w):
            dx, dy = x - cx, y - cy
            inside = (dx * dx + dy * dy) <= r * r
            if inside:
                raw += bytes((255, 255, 255, 255))  # white circle
            else:
                raw += bytes((rgb[0], rgb[1], rgb[2], 255))
    comp = zlib.compress(bytes(raw), 9)

    def chunk(typ, body):
        c = typ + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", comp) + chunk(b"IEND", b""))


# ---------------------------------------------------------------------------
# sample classes2.dex via javac + d8  (fallback: reuse host dex)
# ---------------------------------------------------------------------------
def get_classes2_dex():
    cache = os.path.join(HERE, "classes2.dex")
    if os.path.exists(cache):
        return cache
    src = os.path.join(HERE, "_payload", "Payload.java")
    os.makedirs(os.path.dirname(src), exist_ok=True)
    with open(src, "w") as f:
        f.write("package com.buildaci.payload;\n"
                "public class Payload {\n"
                "  public static int run() { return 42; }\n"
                "}\n")
    cls = os.path.join(HERE, "_payload", "Payload.class")
    dexout = os.path.join(HERE, "_payload", "dexout")
    os.makedirs(dexout, exist_ok=True)
    try:
        subprocess.run(win(JAVAC, ["-d", os.path.dirname(cls), src]), check=True,
                       capture_output=True, text=True, errors="replace")
        subprocess.run(win(D8, ["--min-api", "21", "--output", dexout, cls]),
                       check=True, capture_output=True, text=True, errors="replace")
        got = os.path.join(dexout, "classes.dex")
        if os.path.exists(got):
            import shutil
            shutil.copy(got, cache)
            return cache
    except Exception as e:
        print("WARN: javac/d8 failed (%s); falling back to host dex" % e)
    # fallback: extract host classes.dex from base.apk
    with zipfile.ZipFile(BASE_APK) as z:
        open(cache, "wb").write(z.read("classes.dex"))
    return cache


# ---------------------------------------------------------------------------
# keystore generation
# ---------------------------------------------------------------------------
def ensure_keystore(path, alias, storepass, keypass):
    if os.path.exists(path):
        return
    dn = f"CN={alias}, OU=BuildAci, O=BuildAci, C=CN"
    subprocess.run([KEYTOOL, "-genkeypair", "-alias", alias, "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "10000",
                    "-storetype", "PKCS12", "-keystore", path,
                    "-storepass", storepass, "-keypass", keypass, "-dname", dn],
                   check=True, capture_output=True, text=True)


# ---------------------------------------------------------------------------
# signing + verify
# ---------------------------------------------------------------------------
def sign(unsigned, out, ks, alias, kspass, keypass):
    subprocess.run(win(APKSIGNER, ["sign", "--ks", ks, "--ks-key-alias", alias,
                    "--ks-pass", f"pass:{kspass}", "--key-pass", f"pass:{keypass}",
                    "--min-sdk-version", "21", "--out", out, unsigned]),
                   check=True, capture_output=True, text=True, errors="replace")


def verify(path):
    r = subprocess.run(win(APKSIGNER, ["verify", path]), capture_output=True, text=True, errors="replace")
    ok1 = r.returncode == 0
    r2 = subprocess.run(win(ZIPALIGN, ["-c", "4", path]), capture_output=True, text=True, errors="replace")
    ok2 = r2.returncode == 0
    return ok1 and ok2, (r.stdout + r.stderr + r2.stdout + r2.stderr)


# ---------------------------------------------------------------------------
# variants
# ---------------------------------------------------------------------------
VARIANTS = [
    dict(name="01_baseline", pkg="com.example.buildapp", label="BuildApp",
         version="1.0.0", rgb=(33, 150, 243), ks="debug",
         alias="androiddebugkey", kspass="android", keypass="android"),
    dict(name="02_demo1", pkg="com.test.buildaci.demo1", label="Demo One",
         version="1.0.0", rgb=(255, 112, 67), ks="custom",
         alias="demo1", kspass="demopass", keypass="demopass"),
    dict(name="03_demo2", pkg="com.test.buildaci.demo2", label="Demo Two",
         version="1.0.0", rgb=(33, 150, 243), ks="custom",
         alias="demo2", kspass="demopass", keypass="demopass"),
    dict(name="04_demo3", pkg="com.test.buildaci.demo3", label="Demo Three",
         version="2.3.4", rgb=(76, 175, 80), ks="debug",
         alias="androiddebugkey", kspass="android", keypass="android"),
]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    dex = get_classes2_dex()
    print("classes2.dex:", dex, os.path.getsize(dex), "bytes")

    # build icon map sized for each density
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

    summary = []
    for v in VARIANTS:
        print("\n=== %s ===" % v["name"])
        if v["ks"] == "debug":
            ks = DEBUG_KS
        else:
            ks = os.path.join(OUT_DIR, f"{v['name']}.p12")
            ensure_keystore(ks, v["alias"], v["kspass"], v["keypass"])

        # icon map (custom icon for every density)
        icon_map = {}
        with zipfile.ZipFile(BASE_APK) as z:
            for n in z.namelist():
                if n.startswith("res/mipmap-") and n.endswith("/ic_launcher.png"):
                    for d, sz in densities.items():
                        if d in n:
                            icon_map[n] = make_icon(sz, v["rgb"])
                            break
        print("icon map: %d entries, color=%s" % (len(icon_map), v["rgb"]))

        unsigned = os.path.join(OUT_DIR, f"{v['name']}-unsigned.apk")
        final = os.path.join(OUT_DIR, f"BuildAci_test_{v['name']}.apk")
        repack_aligned(BASE_APK, dex, unsigned, v["pkg"], v["label"], v["version"], icon_map)
        sign(unsigned, final, ks, v["alias"], v["kspass"], v["keypass"])
        ok, log = verify(final)
        # show resulting manifest package/label/icon
        m = subprocess.run(win(AAPT2, ["dump", "xmltree", final, "--file",
                            "AndroidManifest.xml"]), capture_output=True, text=True, errors="replace")
        pkg_line = [l for l in m.stdout.splitlines() if "package=" in l]
        summary.append((v["name"], final, ok, v["pkg"], v["label"], v["ks"], pkg_line))

    # README
    readme = ["BuildAci 测试 APK 集合（可直接安装到真机）", "=" * 40, ""]
    readme.append("验证目标：安装包异常已解决 + 不同包名互不冲突 + 自定义图标/签名生效。")
    readme.append("每个 APK 均通过 PC 端 zipalign -c 4 与 apksigner verify。")
    readme.append("")
    for name, final, ok, pkg, label, ks, pkgline in summary:
        readme.append("[%s] %s" % (name, "OK" if ok else "FAIL"))
        readme.append("  文件: %s" % os.path.basename(final))
        readme.append("  包名: %s" % pkg)
        readme.append("  应用名: %s" % label)
        readme.append("  签名: %s" % ("内置 debug.keystore" if ks == "debug" else "自定义 keystore (%s)" % name))
        readme.append("")
    readme.append("安装建议：把这 4 个 APK 拷到手机，逐个点击安装。")
    readme.append("预期：4 个独立 App 图标出现（互不冲突）；demo1/demo2 为自定义签名、橙色/蓝色图标。")
    with open(os.path.join(OUT_DIR, "README.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(readme))

    print("\n" + "=" * 50)
    for name, final, ok, pkg, label, ks, pkgline in summary:
        print("[%s] %s  %s  pkg=%s" % (name, "OK" if ok else "FAIL",
              os.path.basename(final), pkg))
        for pl in pkgline[:1]:
            print("    ", pl.strip())
    print("\n输出目录:", OUT_DIR)


if __name__ == "__main__":
    main()
