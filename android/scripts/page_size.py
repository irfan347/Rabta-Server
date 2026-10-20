#!/usr/bin/env python3
"""Check that every 64-bit native library in an APK or app bundle suits phones with 16 KB memory pages.

Google Play rejects apps targeting Android 15+ whose arm64-v8a or x86_64 libraries load in 4 KB steps; such a library
also fails to load on a 16 KB phone. Usage: page_size.py FILE.apk|FILE.aab ... (exit 1 if any library does not suit).
"""
import struct
import sys
import zipfile

SIXTY_FOUR = ("arm64-v8a", "x86_64")


def load_alignments(elf):
    """Alignment of each loadable segment of a 64-bit ELF library."""
    if elf[:4] != b"\x7fELF" or elf[4] != 2:
        return []
    order = "<" if elf[5] == 1 else ">"
    phoff = struct.unpack_from(order + "Q", elf, 0x20)[0]
    size, count = struct.unpack_from(order + "HH", elf, 0x36)
    found = []
    for i in range(count):
        at = phoff + i * size
        if struct.unpack_from(order + "I", elf, at)[0] == 1:  # PT_LOAD
            found.append(struct.unpack_from(order + "Q", elf, at + 48)[0])
    return found


def check(path):
    bad, seen = [], 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            parts = name.split("/")
            if not name.endswith(".so") or not any(abi in parts for abi in SIXTY_FOUR):
                continue
            seen += 1
            aligns = load_alignments(z.read(name))
            if not aligns or min(aligns) < 16384:
                bad.append((name, sorted(set(aligns))))
    print(f"{path}: {seen} 64-bit libraries checked")
    for name, aligns in bad:
        print(f"  not 16 KB: {name} (segments align to {aligns})")
    return not bad


if __name__ == "__main__":
    ok = all([check(p) for p in sys.argv[1:]])
    sys.exit(0 if ok else 1)
