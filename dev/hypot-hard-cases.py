#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""The pairs of src/test/resources/.../hard-cases/hypot.txt, computed with exact integer arithmetic.

V. Lefevre's testlibm-data has tags of functions of one argument only. A published list does
exist, CORE-MATH's src/binary64/hypot/hypot.wc (MIT), from which dev/coremath-hard-cases.py takes
hard-cases/hypot-coremath.txt (issue 144); these pairs are self-computed beside it, derived here
from number theory alone, with no outside data. Each base pair (x, y) is of integers below 2^54,
so x*x + y*y is an exact integer s, and sqrt(s) is either an integer (an exact result) or lies
within 2^-50 of an ulp of a midpoint between two doubles (a case hard to round). The families,
each with its form of e_hypot.c's medium path (w = a-b > b is line 96's form, the other line
101's):

  near1  (n^2 + j, n), n in [2^26, 2^26.5), j in -1, 0, 1: s = M^2 + M - j with M = n^2 + j, so
         sqrt(s) is M + 1/2 less about (j + 1/4)/(2M), a midpoint of the ulp-1 binade. w > b.
  near2  (M, n), n even in [2^27, 2^27.5), M = (n^2 - j)/2 even, j in -4, 0, 4: s = (M + 1)^2
         + (j - 1), so sqrt(s) is M + 1 plus about (j - 1)/(2M + 2), and M + 1 is odd, a
         midpoint of the ulp-2 binade [2^53, 2^54). w > b.
  exact  (p^2 - q^2, 2pq), p the nearest integer to q(1 + sqrt 2), so the two legs are nearly
         equal: sqrt(s) = p^2 + q^2 exactly, below 2^53. w <= b.
  pell   (u + v, u - v) with X^2 - 8u^2 = 1 + 8v^2, X = 2M + 1 odd, v in 0..63: s = 2u^2 + 2v^2
         = M^2 + M, so sqrt(s) is M + 1/2 less about 1/(8M), a midpoint of the ulp-1 binade
         (v = 0 is hypot(u, u) = u sqrt 2). w <= b.

Every base pair is checked here: an exact s is a square, and otherwise the distance from the
nearest midpoint, measured exactly, is below 2^-50 ulp. Each is then written scaled by 2^k for
each k of SCALES, which is exact (the legs are integers below 2^54, and the scaled values are
doubles: checked by converting back), and carries the property to every scale whose result is
of the same binade's spacing, normal or subnormal: the scales put the pair in each of
e_hypot.c's ranges, the 2^-600 scaling above 2^500, the 2^600 scaling below 2^-500 and the
2^1022 scaling of a subnormal b (k = -1074, -1070 and -1050). Each scaled pair is written as
`0x<x> 0x<y>` (raw bits, Double.doubleToRawLongBits) in both orders, with the signs of the two
arguments cycling through the four combinations, one pair a line, on the standard output: the
lines of the hard-cases file after its header. A summary goes to the standard error; the run
stops with status 1 and no output if a check fails.
"""

import math
import struct
import sys
from fractions import Fraction

SCALES = [0, -1074, -1070, -1050, -1000, -600, -560, -520, 450, 480, 520, 560, 900, 960]
PER_FAMILY = 40
MIN_BITS = 50


def bits(d):
    return struct.unpack(">Q", struct.pack(">d", d))[0]


def scaled(m, k):
    """m * 2^k as a double, which must be exact."""
    d = math.ldexp(float(m), k)
    if Fraction(d) != Fraction(m) * Fraction(2) ** k:
        raise SystemExit(f"hypot-hard-cases: {m} * 2^{k} is not a double")
    return d


def midpoint_bits(s):
    """For a non-square integer s: how many bits after the round bit sqrt(s) agrees with a midpoint.

    The ulp u of sqrt(s) at 53 bits is a power of two; the midpoints are odd multiples of u/2.
    With q = s / (u/2)^2, a midpoint is an odd integer o near sqrt(q), and the distance in ulps is
    |sqrt(q) - o| / 2, which is |q - o^2| / (2 (sqrt(q) + o)), within a factor 2 of
    |q - o^2| / (4 o).
    """
    r = math.isqrt(s)
    e = r.bit_length() - 53  # sqrt(s) in [2^(52+e), 2^(53+e)): ulp 2^e
    half = Fraction(2) ** (e - 1)
    q = Fraction(s) / (half * half)
    root = math.isqrt(q.numerator // q.denominator)
    best = None
    for o in (root - 1, root, root + 1, root + 2):
        if o % 2 == 1 and o > 0:
            d = abs(q - o * o) / (4 * o)
            best = d if best is None or d < best else best
    if best == 0:
        return 10**6
    return -math.log2(best)


def near1():
    lo, hi = 1 << 26, math.isqrt((1 << 53) - 2)
    for i in range(PER_FAMILY):
        n = lo + 1 + i * (hi - lo - 2) // PER_FAMILY
        for j in (-1, 0, 1):
            yield "near1", (n * n + j, n)


def near2():
    lo, hi = 1 << 27, math.isqrt((1 << 55) - 8)
    for i in range(PER_FAMILY):
        n = lo + 2 + i * (hi - lo - 4) // PER_FAMILY
        n -= n % 2
        for j in (-4, 0, 4):
            yield "near2", ((n * n - j) // 2, n)


def exact():
    lo = 1 << 24
    hi = math.isqrt(((1 << 53) - 1) * 1000 // 6829)  # p^2 + q^2 < 2^53 for p near 2.414 q
    for i in range(PER_FAMILY):
        q = lo + i * (hi - lo) // PER_FAMILY
        # p = round(q (1 + sqrt 2)), by integers: q * sqrt(2) = isqrt(2 q^2)
        p = q + math.isqrt(2 * q * q)
        if math.isqrt(4 * 2 * q * q) % 2 == 1:
            p += 1
        yield "exact", (p * p - q * q, 2 * p * q)


def pell():
    """Solutions of X^2 - 8u^2 = 1 + 8v^2, grown by (X, u) -> (3X + 8u, X + 3u), in range."""
    found = 0
    for v in range(64):
        n = 1 + 8 * v * v
        seeds = []
        for u in range(0, 4 * v + 4):
            x2 = n + 8 * u * u
            x = math.isqrt(x2)
            if x * x == x2:
                seeds.append((x, u))
        seen = set()
        for x, u in seeds:
            while True:
                m = (x - 1) // 2
                if m >= 1 << 53:
                    break
                if m >= 1 << 52 and u > v and (x, u) not in seen:
                    seen.add((x, u))
                    yield "pell", (u + v, u - v)
                    found += 1
                x, u = 3 * x + 8 * u, x + 3 * u
        if found >= PER_FAMILY:
            return


def main():
    counts = {}
    lines = []
    sign = 0
    for family, (x, y) in [*near1(), *near2(), *exact(), *pell()]:
        s = x * x + y * y
        r = math.isqrt(s)
        if family == "exact":
            if r * r != s or r >= 1 << 53:
                raise SystemExit(f"hypot-hard-cases: {family} ({x}, {y}) is not exact")
        else:
            got = midpoint_bits(s)
            if got < MIN_BITS:
                raise SystemExit(f"hypot-hard-cases: {family} ({x}, {y}): {got:.1f} bits")
        if x >= 1 << 54 or y >= 1 << 54 or y <= 0:
            raise SystemExit(f"hypot-hard-cases: {family} ({x}, {y}) out of range")
        counts[family] = counts.get(family, 0) + 1
        for k in SCALES:
            a, b = scaled(x, k), scaled(y, k)
            for first, second in ((a, b), (b, a)):
                sx = -1.0 if sign & 1 else 1.0
                sy = -1.0 if sign & 2 else 1.0
                sign += 1
                lines.append(f"0x{bits(sx * first):016x} 0x{bits(sy * second):016x}")
    print("\n".join(lines))
    print(f"hypot-hard-cases: {counts}, {len(SCALES)} scales, two orders: {len(lines)} pairs",
          file=sys.stderr)


if __name__ == "__main__":
    main()
