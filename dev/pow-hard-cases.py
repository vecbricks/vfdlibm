#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""The pairs of src/test/resources/.../hard-cases/pow.txt, computed with exact integer arithmetic.

No published table of hard cases for pow is known (V. Lefevre's testlibm-data has tags of
functions of one argument only), so these pairs are derived here with no outside data. Every
pair's y is an integer, or x is a power of two and x**y one too, so x**y is an exact rational
and every claim below is checked on it with integers alone (no floating-point arithmetic decides
anything; floats only name the doubles, and each is converted back and compared). A double is
held as M * 2^k with M and k integers. The families:

  exact    x**y is a double: b**e for an integer b from 3 to 40 and e >= 3 with b**e below 2^53,
           x = b * 2^k scaled so that the result lands at each of the binades TARGETS; and
           x = 2^a with an integer y, or x = 2^(4a) or 2^(8a) with y a multiple of 1/4 or 1/8,
           whose result is 2^(a*y) from 2^1023 down to the subnormals and 2^-1074.
  tie      x**y is exactly halfway between two doubles: M odd with M**e of 54 bits (e from 3 to
           7), scaled as above; and M odd with M**5 or M**25 below 2^53 at x = M * 2^(-1075/e),
           whose result M**e * 2^-1075 is an odd multiple of half the subnormals' spacing.
  near     x**y within 2^-28 ulp of a midpoint and not on it: x = 2^52 + d for e from 3 to 9,
           d from the leading terms of (2^52 + d)**e for a tail of (2j + 1)/2 ulp, kept when the
           exact distance is below 2^-28; scaled as above. The bound is 2^-28 and not hypot's
           2^-40: the distance is |C(e,2) d^2 - (2j+1) 2^51| / 2^52 to leading order, and the
           squares are too sparse modulo 2^51 for it to come nearer (the best of 20,000 steps of
           j read about 2^-30), so a nearer family would need another construction.
  bound    x and its neighbour on either side of a rounding threshold of the result: 2^1024 -
           2^970 (overflow), 2^-1022 - 2^-1075 (the smallest normal against the largest
           subnormal) and 2^-1075 (zero against the smallest subnormal), for integer y of either
           sign, found by bisection over the doubles with exact comparisons.

Each pair is written as `0x<x> 0x<y>` (raw bits, Double.doubleToRawLongBits), one a line, on the
standard output: the lines of the hard-cases file after its header. For every second pair of an
integer y the pair with -x follows, whose result is +-x**y. A summary goes to the standard
error; the run stops with status 1 and no output if a check fails.
"""

import math
import struct
import sys
from fractions import Fraction

TARGETS = [-1000, -700, -300, 0, 300, 700, 1000]
NEAR_BITS = 28
NEAR_SCAN = 1 << 16
NEAR_PER_E = 12
BOUND_YS = [3, 5, 7, 10, 33, 100, 511, 1023, -3, -5, -7, -10, -33, -100, -511, -1023]
THRESHOLDS = {
    "overflow": ((1 << 54) - 1, 970),  # (2^54 - 1) * 2^970 = 2^1024 - 2^970
    "min-normal": ((1 << 53) - 1, -1075),  # 2^-1022 - 2^-1075
    "zero": (1, -1075),
}


def fail(message):
    raise SystemExit("pow-hard-cases: " + message)


def bits_of(d):
    return struct.unpack(">Q", struct.pack(">d", d))[0]


def double_of(b):
    return struct.unpack(">d", struct.pack(">Q", b))[0]


def split(b):
    """The positive double of raw bits b (sign cleared) as (M, k), value M * 2^k exactly."""
    b &= (1 << 63) - 1
    e = b >> 52
    f = b & ((1 << 52) - 1)
    if e == 0x7FF:
        fail(f"0x{b:016x} is not finite")
    if e == 0:
        return f, -1074
    return f | (1 << 52), e - 1075


def bits_from(m, k):
    """The raw bits of the double m * 2^k, which must be exact and finite."""
    if m <= 0:
        fail("not positive")
    while m % 2 == 0:
        m //= 2
        k += 1
    n = m.bit_length()
    top = k + n - 1  # m * 2^k in [2^top, 2^(top+1))
    if top > 1023:
        fail(f"{m} * 2^{k} overflows")
    if top >= -1022:
        if n > 53:
            fail(f"{m} * 2^{k} is not a double")
        f = m << (52 - (n - 1))
        b = ((top + 1023) << 52) | (f & ((1 << 52) - 1))
    else:
        if k < -1074:
            fail(f"{m} * 2^{k} is below the subnormals' spacing")
        b = m << (k + 1074)
        if b >= 1 << 52:
            fail("not subnormal")
    if normalized(*split(b)) != normalized(m, k):
        fail(f"{m} * 2^{k} did not convert back")
    return b


def normalized(m, k):
    while m % 2 == 0:
        m //= 2
        k += 1
    return m, k


def power(xb, n):
    """|x|**n for an integer n, exactly, as (numerator, denominator, power of two s): N/D * 2^s."""
    m, k = split(xb)
    if n >= 0:
        return m ** n, 1, k * n
    return 1, m ** (-n), k * n


def compare(value, threshold):
    """The sign of value - threshold, value as power() gives it, threshold as (P, q): P * 2^q."""
    num, den, s = value
    p, q = threshold
    # num/den * 2^s against p * 2^q: num * 2^(s-q) against p * den
    left, right = num, p * den
    if s >= q:
        left <<= s - q
    else:
        right <<= q - s
    return (left > right) - (left < right)


def classify(num, s):
    """For a positive integer value num * 2^s: 'exact', 'tie', the distance from the nearest
    midpoint in ulps as a fraction (a, b) for 'near', with the grid of the doubles at that
    value (normal or subnormal)."""
    top = num.bit_length() - 1 + s
    ulp = max(top - 52, -1074)
    if s >= ulp:
        return "exact", None
    width = ulp - s  # the value in ulps is num / 2^width
    tail = num & ((1 << width) - 1)
    if tail == 0:
        return "exact", None
    half = 1 << (width - 1)
    if tail == half:
        return "tie", None
    return "near", (abs(tail - half), 1 << width)


def is_near(distance):
    a, b = distance
    return a << NEAR_BITS < b


def scaled_pairs(base, e, targets=TARGETS):
    """x = base * 2^k for each target binade of base**e, as raw bits: k with (k*e + the top bit
    of base**e) nearest the target, kept when x and the result are normal and finite."""
    top0 = (base ** e).bit_length() - 1
    out = []
    for t in targets:
        k = round((t - top0) / e)
        top = top0 + k * e
        xtop = base.bit_length() - 1 + k
        if -1022 <= top <= 1022 and -1022 <= xtop <= 1023:
            out.append(bits_from(base, k))
    return out


def exact_family():
    pairs = []
    for b in range(3, 41):
        e = 3
        while b ** e < 1 << 53:
            for xb in scaled_pairs(b, e):
                pairs.append((xb, float(e)))
            e += 1
    for a in (1, 2, 3, 5, 7, 10, -1, -2, -3, -7, -10):
        for t in (1023, 1000, 500, 1, -500, -1000, -1022, -1023, -1060, -1074):
            if t % a == 0 and t // a not in (0, 1, -1, 2):
                pairs.append((bits_from(1, a), float(t // a)))
    for a, denominator in ((4, 4), (8, 8), (-4, 4), (-8, 8)):
        for t in (1023, 1001, 3, -3, -1021, -1023, -1049, -1073, -1074):
            # x = 2^a, y = t / a, with a * y = t: y a multiple of 1/4 or 1/8
            y = t / a
            if y * a != t or bits_of(y) != bits_of(float(y)):
                fail(f"2^{a} ** {t}/{a} is not exact")
            pairs.append((bits_from(1, a), y))
    return pairs


def tie_family():
    pairs = []
    for e in range(3, 8):
        # odd M with M**e in [2^53, 2^54): M from the e-th root of 2^53 up
        m = int_root(1 << 53, e)
        found = 0
        while found < 6:
            if m % 2 == 1 and (m ** e).bit_length() == 54:
                for xb in scaled_pairs(m, e):
                    pairs.append((xb, float(e)))
                found += 1
            m += 1
            if (m ** e).bit_length() > 54:
                break
    for e, k in ((5, -215), (25, -43)):
        for m in range(3, 1600, 2):
            if m ** e >= 1 << 53:
                break
            if m % 4 == 1 or e == 25:
                pairs.append((bits_from(m, k), float(e)))
    return pairs


def int_root(n, e):
    """The smallest integer r with r**e >= n."""
    r = int(round(n ** (1.0 / e)))
    while r ** e >= n:
        r -= 1
    while r ** e < n:
        r += 1
    return r


def near_family():
    pairs = []
    for e in range(3, 10):
        c2 = e * (e - 1) // 2
        kept = []
        for j in range(NEAR_SCAN):
            # leading terms: C(e,2) d^2 2^(52e-104) = (2j+1) 2^(52e-53), d^2 = (2j+1) 2^51 / C(e,2)
            d0 = math.isqrt(((2 * j + 1) << 51) // c2)
            for d in (d0 - 1, d0, d0 + 1):
                m = (1 << 52) + d
                if not (1 << 52) <= m < 1 << 53:
                    continue
                kind, distance = classify(m ** e, 0)
                if kind == "near" and is_near(distance):
                    kept.append(m)
            if len(kept) >= NEAR_PER_E:
                break
        for m in sorted(set(kept))[:NEAR_PER_E]:
            for xb in scaled_pairs(m, e):
                pairs.append((xb, float(e)))
    return pairs


def bound_family():
    pairs = []
    for name, threshold in THRESHOLDS.items():
        for n in BOUND_YS:
            # |x|**n is increasing in x's bits for n > 0 and decreasing for n < 0
            p, q = threshold
            guess = math.ldexp(1.0, round((math.log2(p) + q) / n))
            lo = hi = bits_of(guess)
            step = 1 << 20

            def above(b):
                return compare(power(b, n), threshold) >= 0 if n > 0 else \
                    compare(power(b, n), threshold) < 0

            while above(lo):
                lo = max(lo - step, 1)
                step *= 2
            step = 1 << 20
            while not above(hi):
                hi = min(hi + step, 0x7FEFFFFFFFFFFFFF)
                step *= 2
            while hi - lo > 1:
                mid = (lo + hi) // 2
                if above(mid):
                    hi = mid
                else:
                    lo = mid
            for xb in (lo, hi):
                if not 0x0010000000000000 <= xb <= 0x7FEFFFFFFFFFFFFF:
                    continue
                pairs.append((xb, float(n)))
    return pairs


def straddles(xb, n):
    """Whether some threshold lies between |x|**n at x's two neighbours, as the checker asks."""
    below, above = power(xb - 1, n), power(xb + 1, n)
    for threshold in THRESHOLDS.values():
        a, b = compare(below, threshold), compare(above, threshold)
        if a != b:
            return True
    return False


def claim(xb, y):
    """What the pair is, by the checks HardCasePairsTest makes too, in the same order: a power of
    two x whose x**y is a power of two in the doubles' range is exact; then, for a positive
    integer y, the exact value's place on the doubles' grid (exact, tie or near); then, for any
    integer y, a threshold between the results at x's two neighbours (bound)."""
    m, k = normalized(*split(xb))
    if m == 1:
        t = Fraction(y) * k
        if t.denominator == 1 and -1074 <= t <= 1023:
            return "exact"
    if y != math.floor(y):
        fail(f"0x{xb:016x} ** {y}: a fractional y needs x**y a power of two")
    n = int(y)
    if n > 0:
        num, den, s = power(xb, n)
        kind, distance = classify(num, s)
        if num.bit_length() - 1 + s <= 1023:
            if kind in ("exact", "tie"):
                return kind
            if is_near(distance):
                return "near"
    if straddles(xb, n):
        return "bound"
    fail(f"0x{xb:016x} ** {n}: no claim holds")


def main():
    families = [("exact", exact_family()), ("tie", tie_family()), ("near", near_family()),
                ("bound", bound_family())]
    lines = []
    seen = set()
    counts = {}
    flip = 0
    for family, pairs in families:
        for xb, y in pairs:
            yb = bits_of(y)
            if double_of(yb) != y:
                fail("y did not convert back")
            variants = [xb]
            if y == math.floor(y):
                flip += 1
                if flip % 2 == 0:
                    variants.append(xb | 1 << 63)
            for v in variants:
                key = (v, yb)
                if key in seen:
                    continue
                seen.add(key)
                got = claim(v & ((1 << 63) - 1), y)
                if family != "bound" and got != family and not (family == "exact"
                                                                 and got == "exact"):
                    fail(f"0x{v:016x} 0x{yb:016x} of {family} is {got}")
                counts[got] = counts.get(got, 0) + 1
                lines.append(f"0x{v:016x} 0x{yb:016x}")
    print("\n".join(lines))
    print(f"pow-hard-cases: {counts}: {len(lines)} pairs", file=sys.stderr)


if __name__ == "__main__":
    main()
