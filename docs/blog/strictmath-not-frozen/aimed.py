#!/usr/bin/env python3
"""Counts JdkScan's aimed pow inputs that reach the arithmetic of fdlibm's branch for |y| > 2^31.

Reads JdkScan's out-<jdk>/pow-inputs.bin (x and y per input, in order; the aimed inputs are the
last 1,000,000) and counts those with |y| past e_pow.c's test on line 191 (high word over 0x41e00000,
up to 0x43f00000) and |x| within 2^-20 of 1 (high word 0x3fefffff or 0x3ff00000), and of those the ones that are not a negative x with
a non-integer y, which e_pow.c returns as NaN first (line 185):

    python3 aimed.py out-25/pow-inputs.bin
"""
import math
import struct
import sys


def main():
    data = open(sys.argv[1], "rb").read()
    v = struct.unpack(">%dQ" % (len(data) // 8), data)
    n = len(v) // 2
    window = reach = 0
    for i in range(n - 1_000_000, n):
        hi = (v[2 * i] >> 32) & 0x7FFFFFFF
        hy = (v[2 * i + 1] >> 32) & 0x7FFFFFFF
        if hi not in (0x3FEFFFFF, 0x3FF00000) or not 0x41E00000 < hy <= 0x43F00000:
            continue
        window += 1
        x = struct.unpack(">d", v[2 * i].to_bytes(8, "big"))[0]
        y = struct.unpack(">d", v[2 * i + 1].to_bytes(8, "big"))[0]
        if not (x < 0 and y != math.floor(y)):
            reach += 1
    print("aimed inputs with |x| within 2^-20 of 1: %d; reaching the branch's arithmetic: %d" % (window, reach))


if __name__ == "__main__":
    main()
