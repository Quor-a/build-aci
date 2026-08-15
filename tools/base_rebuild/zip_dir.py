"""Zip a directory into a STORED (uncompressed) APK so that repackAligned keeps every
entry 4-byte aligned. STORED entries are required for apksigner v2/v3 alignment."""
import os
import sys
import zipfile


def main():
    src = sys.argv[1]
    dst = sys.argv[2]
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:
        for root, _, files in os.walk(src):
            for fn in sorted(files):
                full = os.path.join(root, fn)
                arc = os.path.relpath(full, src).replace(os.sep, "/")
                with open(full, "rb") as f:
                    data = f.read()
                zi = zipfile.ZipInfo(arc)
                zi.compress_type = zipfile.ZIP_STORED
                z.writestr(zi, data)
    print("dir zipped STORED ->", dst)


if __name__ == "__main__":
    main()
