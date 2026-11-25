#!/usr/bin/env python3
"""Compares two JdkScan output directories input by input.

Prints, per function, how many results differ, and writes each differing pow input with both
results as "x y before after" in hex to the file given by --witnesses.

    python3 compare.py out-25 out-27 --witnesses data/pow-jdk25-vs-jdk27.txt
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
    ap.add_argument("--witnesses", type=Path)
    ap.add_argument("--inputs", type=Path, help="pow-inputs.bin, for --witnesses")
    a = ap.parse_args()
    names = [a.file] if a.file else [f + ".bin" for f in FUNCTIONS]
    for name in names:
        b, c = bits(a.before / name), bits(a.after / name)
        assert len(b) == len(c), name
        diff = [i for i in range(len(b)) if b[i] != c[i]]
        print("%-12s %9d inputs %6d differ" % (name, len(b), len(diff)))
        if a.witnesses and name in ("pow.bin", "pow-Math.bin") and diff:
            xy = bits(a.inputs or a.before / "pow-inputs.bin")
            with a.witnesses.open("w") as w:
                for i in diff:
                    w.write("%016x %016x %016x %016x\n" % tuple(v & (2**64 - 1) for v in
                            (xy[2 * i], xy[2 * i + 1], b[i], c[i])))


if __name__ == "__main__":
    main()
