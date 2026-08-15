"""PC prototype for the BuildAci runtime APK builder.

Validates the full algorithm that will be ported to Kotlin (BuildEngine):
  1. rewrite the binary AndroidManifest.xml string pool (package + label)
  2. replace res/mipmap-*-v4/ic_launcher.png bytes with a custom icon
  3. repackage STORED + 4-byte aligned (zipalign -f 4)
  4. sign with a custom keystore (or the bundled debug.keystore)

Usage:
  python prototype_build.py --pkg PKG --label LABEL [--icon PNG]
      [--ks KEYSTORE --alias ALIAS --kspass P --keypass P] [--out APK]
"""
import os
import sys
import struct
import zipfile
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
BT = "D:/Android/Sdk/build-tools/34.0.0"
ASSET_BASE = "D:/Calw OS-project/BuildAci/app/src/main/assets/libs/common"
DEBUG_KS = os.path.join(ASSET_BASE, "debug.keystore")

# Binary manifest string-pool indices (from dump_manifest.py on the new base.apk)
IDX_LABEL = 14
IDX_PACKAGE = 22
# IDX_ACTIVITY = 23  (com.example.buildapp.MainActivity) -> deliberately unchanged


def rewrite_manifest(data: bytes, new_pkg: str, new_label: str) -> bytes:
    doc_hsize = struct.unpack_from("<H", data, 2)[0]   # 8
    sp_off = doc_hsize
    sp_type = struct.unpack_from("<H", data, sp_off)[0]
    sp_hsize = struct.unpack_from("<H", data, sp_off + 2)[0]
    sp_size = struct.unpack_from("<I", data, sp_off + 4)[0]
    count = struct.unpack_from("<I", data, sp_off + 8)[0]
    stylecount = struct.unpack_from("<I", data, sp_off + 12)[0]
    flags = struct.unpack_from("<I", data, sp_off + 16)[0]
    strings_start = struct.unpack_from("<I", data, sp_off + 20)[0]
    styles_start = struct.unpack_from("<I", data, sp_off + 24)[0]
    utf8 = (flags & 0x100) != 0

    offs = [struct.unpack_from("<I", data, sp_off + sp_hsize + 4 * i)[0] for i in range(count)]
    base = sp_off + strings_start
    strings = []
    for i in range(count):
        p = base + offs[i]
        if utf8:
            n = struct.unpack_from("<H", data, p)[0]; p += 2
            l = data[p]; p += 1
            if l & 0x80:
                l2 = data[p]; p += 1
                ln = ((l & 0x7F) << 8) | l2
            else:
                ln = l
            s = data[p:p + ln].decode("utf-8")
        else:
            n = struct.unpack_from("<H", data, p)[0]; p += 2
            s = data[p:p + 2 * n].decode("utf-16-le")
        strings.append(s)

    assert strings[IDX_PACKAGE] == "com.example.buildapp", strings[IDX_PACKAGE]
    strings[IDX_PACKAGE] = new_pkg
    strings[IDX_LABEL] = new_label

    new_offs = []
    str_parts = []
    pos = 0
    for s in strings:
        enc = s.encode("utf-16-le")
        n = len(enc) // 2
        b = struct.pack("<H", n) + enc + struct.pack("<H", 0)
        new_offs.append(pos)
        str_parts.append(b)
        pos += len(b)

    offset_array_size = count * 4
    new_strings_start = sp_hsize + offset_array_size
    new_pool_size = new_strings_start + pos

    pool = bytearray()
    pool += struct.pack("<HHI", sp_type, sp_hsize, new_pool_size)
    pool += struct.pack("<IIII", count, stylecount, flags, new_strings_start)
    pool += struct.pack("<I", styles_start)
    for o in new_offs:
        pool += struct.pack("<I", o)
    for b in str_parts:
        pool += b

    tree = data[sp_off + sp_size:]
    out = bytearray()
    out += data[:sp_off]          # document header
    out += pool
    out += tree
    struct.pack_into("<I", out, 4, len(out))  # document header size
    return bytes(out)


def zipinfo_for(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    zi = zipfile.ZipInfo(info.filename)
    zi.date_time = info.date_time
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = info.external_attr
    return zi


def repackage(in_apk: str, out_apk: str, manifest_data: bytes, icon_map: dict):
    with zipfile.ZipFile(in_apk, "r") as zin:
        infos = zin.infolist()
        with zipfile.ZipFile(out_apk, "w", zipfile.ZIP_STORED) as zout:
            for info in infos:
                if info.filename == "AndroidManifest.xml":
                    zout.writestr(zipinfo_for(info), manifest_data)
                elif info.filename in icon_map:
                    zout.writestr(zipinfo_for(info), icon_map[info.filename])
                else:
                    zout.writestr(zipinfo_for(info), zin.read(info.filename))


def run(cmd):
    print("+", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.stdout:
        print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr)
        raise SystemExit("command failed: " + " ".join(cmd))
    return r


def main():
    args = {}
    i = 1
    while i < len(sys.argv):
        a = sys.argv[i]
        if a in ("--pkg", "--label", "--icon", "--ks", "--alias", "--kspass", "--keypass", "--out"):
            args[a.lstrip("-")] = sys.argv[i + 1]
            i += 2
        else:
            i += 1

    pkg = args.get("pkg", "com.example.buildapp")
    label = args.get("label", "BuildApp")
    icon_path = args.get("icon")
    out_apk = args.get("out", "out.apk")

    base_apk = os.path.join(HERE, "base.apk")
    with zipfile.ZipFile(base_apk, "r") as z:
        manifest = z.read("AndroidManifest.xml")

    print("original manifest head:", manifest[:4].hex())
    new_manifest = rewrite_manifest(manifest, pkg, label)
    print("rewritten package=%r label=%r" % (pkg, label))

    icon_map = {}
    if icon_path:
        with open(icon_path, "rb") as f:
            icon_bytes = f.read()
        with zipfile.ZipFile(base_apk, "r") as z:
            for n in z.namelist():
                if n.startswith("res/mipmap-") and n.endswith("/ic_launcher.png"):
                    icon_map[n] = icon_bytes
        print("replacing %d icon entries with %d bytes" % (len(icon_map), len(icon_bytes)))

    unsigned = os.path.join(HERE, "app-unsigned.apk")
    repackage(base_apk, unsigned, new_manifest, icon_map)

    aligned = os.path.join(HERE, "app-aligned.apk")
    run([f"{BT}/zipalign.exe", "-f", "4", unsigned, aligned])
    run([f"{BT}/zipalign.exe", "-c", "4", aligned])

    # signing
    ks = args.get("ks", DEBUG_KS)
    alias = args.get("alias", "androiddebugkey")
    kspass = args.get("kspass", "android")
    keypass = args.get("keypass", "android")
    final = os.path.join(HERE, out_apk)
    run([
        f"{BT}/apksigner.bat", "sign",
        "--ks", ks,
        "--ks-key-alias", alias,
        "--ks-pass", f"pass:{kspass}",
        "--key-pass", f"pass:{keypass}",
        "--out", final, aligned,
    ])
    run([f"{BT}/apksigner.bat", "verify", final])

    print("DONE ->", final)
    # show resulting manifest
    run([f"{BT}/aapt2.exe", "dump", "xmltree", final, "--file", "AndroidManifest.xml"])


if __name__ == "__main__":
    main()
