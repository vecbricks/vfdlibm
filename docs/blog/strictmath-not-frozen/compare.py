#!/usr/bin/env python3
"""Compares two JdkScan or StrictScan output directories input by input (StrictScan's with --all).

Prints, per function, how many results differ, and writes each differing pow input with both
results as "x y before after" in hex to the file given by --witnesses.

    python3 compare.py out-25 out-27 --witnesses data/pow-strictmath-jdk25-vs-jdk27.txt
    python3 compare.py out-25 out-26 --file pow-Math.bin   # Math.pow, e.g. with the intrinsic off
"""
import argparse
import struct
from pathlib import Path

FUNCTIONS = ["acos", "asin", "atan", "cosh", "sinh", "exp", "expm1", "log", "log10", "log1p",
             "atan2", "hypot", "pow"]


def bits(path):
    data = path.read_bytes()
    return struct.unpack(">%dq" % (len(data) // 8), data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    ap.add_argument("--file", help="compare this one file only")
    ap.add_argument("--after-file", help="with --file: the file of the same inputs in AFTER")
    ap.add_argument("--all", action="store_true", help="every .bin file in both directories")
    ap.add_argument("--witnesses", type=Path)
    ap.add_argument("--inputs", type=Path, help="pow-inputs.bin, for --witnesses")
    a = ap.parse_args()
    if a.after_file and not a.file:
        ap.error("--after-file needs --file")
    if a.witnesses and a.file and a.file not in ("pow.bin", "pow-Math.bin"):
        ap.error("--witnesses lists pow inputs; compare pow.bin or pow-Math.bin")
    if a.all:
        before = {p.name for p in a.before.glob("*.bin")}
        after = {p.name for p in a.after.glob("*.bin")}
        if before != after:
            ap.error("files in only one directory: %s" % ", ".join(sorted(before ^ after)))
        names = sorted(before)
    else:
        names = [a.file] if a.file else [f + ".bin" for f in FUNCTIONS]
    if a.witnesses and a.all:
        ap.error("--witnesses writes one file's differences; use it without --all")
    for name in names:
        b, c = bits(a.before / name), bits(a.after / (a.after_file or name))
        if len(b) != len(c):
            ap.error("%s: %d results against %d" % (name, len(b), len(c)))
        diff = [i for i in range(len(b)) if b[i] != c[i]]
        print("%-24s %9d inputs %7d differ" % (name, len(b), len(diff)) if a.all else
              "%-12s %9d inputs %6d differ" % (name, len(b), len(diff)))
        if a.witnesses and name in ("pow.bin", "pow-Math.bin"):  # rewritten, even when empty
            xy = bits(a.inputs or a.before / "pow-inputs.bin")
            with a.witnesses.open("w") as w:
                for i in diff:
                    w.write("%016x %016x %016x %016x\n" % tuple(v & (2**64 - 1) for v in
                            (xy[2 * i], xy[2 * i + 1], b[i], c[i])))


if __name__ == "__main__":
    main()
