#!/usr/bin/env python3
"""Each result's error in ulps against pow computed with 80 significant digits (decimal).

Reads "x y before after" lines in hex (pow-strictmath-jdk25-vs-jdk27.txt, or Moderate.java's
differing lines as "x y before after") and prints, for the normal results, the largest error of
each side, how many exceed one ulp and on how many each side is the nearer.

    python3 errors.py data/pow-strictmath-jdk25-vs-jdk27.txt
"""
import math
import struct
import sys
from decimal import Decimal, getcontext

getcontext().prec = 80


def f(u):
    return struct.unpack(">d", u.to_bytes(8, "big"))[0]


def main():
    e_before, e_after = [], []
    for line in open(sys.argv[1]):
        x, y, a, b = (f(int(v, 16)) for v in line.split()[-4:])
        if not (sys.float_info.min <= abs(a) < math.inf and sys.float_info.min <= abs(b) < math.inf):
            continue
        exact = (Decimal(y) * Decimal(abs(x)).ln()).exp()
        if x < 0 and y == int(y) and int(y) % 2:
            exact = -exact
        u = Decimal(math.ulp(abs(float(exact))))  # the ulp at the exact value
        e_before.append(abs((Decimal(a) - exact) / u))
        e_after.append(abs((Decimal(b) - exact) / u))
    print("normal results", len(e_before))
    if not e_before:
        return
    for name, e in (("before", e_before), ("after", e_after)):
        print("%-6s max error %.3f ulp, over 1 ulp on %d" % (name, max(e), sum(v > 1 for v in e)))
    print("after nearer on %d, before nearer on %d" % (sum(a < b for a, b in zip(e_after, e_before)),
                                                     sum(b < a for a, b in zip(e_after, e_before))))


if __name__ == "__main__":
    main()
