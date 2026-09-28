#!/usr/bin/env python3
"""Is every native library in an APK 16 KB page-size ready?

Android 15 introduced 16 KB memory pages; a device using them refuses to load a
shared library whose LOAD segments are aligned to the old 4 KB boundary. On an
S24 Ultra running Android 16 this surfaces as a system dialog — "This app isn't
16 KB-compatible. ELF alignment check failed" — over the map on every launch,
and for a non-debuggable build it is a load failure rather than a warning.

Two things have to be right and this checks both:

  1. **ELF LOAD segment alignment** (`p_align >= 16384`) in each `.so`. This is
     the library's own build and can only be fixed by upgrading the dependency
     that ships it.
  2. **Zip alignment** of the entry inside the APK, because uncompressed
     libraries are mapped straight out of the archive.

Usage:
    python3 scripts/check_16kb.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

Exits non-zero if any library would fail, so it can gate a release.
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_aligns(data: bytes):
    """Minimum p_align across the ELF's PT_LOAD segments, or None if not an ELF."""
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        align_off = 0x30
        unpack = "<Q"
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2C)[0]
        align_off = 0x1C
        unpack = "<I"
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", data, off)[0] == PT_LOAD:
            aligns.append(struct.unpack_from(unpack, data, off + align_off)[0])
    return min(aligns) if aligns else None


def data_offset(z: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Byte offset of the entry's DATA, past its local header."""
    with open(z.filename, "rb") as fh:
        fh.seek(info.header_offset + 26)
        name_len, extra_len = struct.unpack("<HH", fh.read(4))
        return info.header_offset + 30 + name_len + extra_len


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    apk = argv[1]
    z = zipfile.ZipFile(apk)
    libs = sorted(n for n in z.namelist() if n.endswith(".so"))
    if not libs:
        print(f"{apk}: no native libraries")
        return 0
    print(f"{apk}")
    print(f"  {'library':44} {'LOAD align':>11} {'zip offset':>11}  verdict")
    bad = 0
    for n in libs:
        info = z.getinfo(n)
        a = load_aligns(z.read(n))
        off = data_offset(z, info)
        elf_ok = a is not None and a >= PAGE
        # Only an UNCOMPRESSED entry is mapped from the archive; a compressed
        # one is extracted, so its zip offset does not matter.
        zip_ok = info.compress_type != zipfile.ZIP_STORED or off % PAGE == 0
        why = []
        if not elf_ok:
            why.append(f"LOAD align {a} < {PAGE}")
        if not zip_ok:
            why.append(f"zip offset {off} not {PAGE}-aligned")
        if why:
            bad += 1
        print(f"  {n:44} {str(a):>11} {off:>11}  "
              f"{'OK' if not why else 'FAIL: ' + '; '.join(why)}")
    print(f"  -> {len(libs) - bad}/{len(libs)} libraries are 16 KB ready")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
