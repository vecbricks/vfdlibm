"""Counts the inputs on which StrictMath and the C library's libm give different doubles.

Reads Gen.java's output on stdin: per line, x and StrictMath's exp(x), log(|x|), expm1(x / 100)
and pow(2.0, x / 700). Python's math module calls the platform's C library for these functions
(CPython's math.exp, math.log, math.expm1 and math.pow), so on Linux with glibc this is glibc's
libm. Each decimal reads back as the same double it was written from, so == compares bits (no
NaN or zero results occur on these inputs). Prints the libc it ran on and one count a function.
"""

import math
import platform
import sys

names = ("exp(x)", "ln(|x|)", "expm1(x/100)", "pow(2.0, x/700)")
differ = [0, 0, 0, 0]
n = 0
for line in sys.stdin:
    x, *strict = (float(v) for v in line.split())
    libm = (math.exp(x), math.log(abs(x)), math.expm1(x / 100), math.pow(2.0, x / 700))
    for i in range(4):
        if libm[i] != strict[i]:
            differ[i] += 1
    n += 1

print(f"{n} inputs, libc {' '.join(platform.libc_ver())}, Python {platform.python_version()}")
for name, d in zip(names, differ):
    print(f"{name:>16}: StrictMath and libm differ on {d:,}")
