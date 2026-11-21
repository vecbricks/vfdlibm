#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""The inputs of one function's hard-to-round cases in Lefevre's testlibm-data, as raw-bits hex.

The source is V. Lefevre's file testlibm-data (updated 2020-11-27),
https://www.vinc17.net/research/testlibm/testlibm-data.xz, from the page
https://www.vinc17.net/research/testlibm/, which states no licence or reuse terms: only the
input values are taken, as numeric facts, as in every src/test/resources/.../hard-cases/ file.
A line of the data reads `<mode> <tag> <x> <result> <direction>`, with x and the result in
binary, as `1.0101...E-25`, and the direction bit the 54th bit of the exact |result|. This keeps
the lines of mode N with the given tag, in the file's order, and prints each x as the raw bits of
the double, `0x` and sixteen hex digits, one a line on the standard output: the lines of a
hard-cases file after its header. (Mode N is round-to-nearest.)

Each line is rechecked with 400-digit decimal arithmetic before it is printed (Python's decimal
module, `exp`, and the Taylor series of the function below 1e-30): x is a double, the file's
result is the function's exact value rounded to nearest at 53 bits, and the direction bit is the
54th bit of the exact |value|. The distance from the midpoint is measured as well: how many bits
after the round bit repeat the pattern of a midpoint. The run stops with status 1 and no output
on a mismatch, and prints its summary on the standard error.

The tags in use: `sh` (sinh), `ch` (cosh), `em1` (expm1), `atn` (atan), `asn` (asin) and `acs`
(acos), the functions this knows the exact value of; the file also holds other tags. The exact
values are derived here, not read from the file: the exponential's series and `Decimal.exp` for
the first three, and for the last three a Taylor series of atan (after halving the angle) with pi
from Machin's formula, asin as `atan(x / sqrt(1 - x^2))` and acos as `pi/2 - asin`. The tags
`atn`, `asn` and `acs` were identified from the file's first lines only; the recheck of every
line, which fails on a line of another function, is what shows the tag is the function's.
hard-cases/expm1.txt's header describes the same method for the `em1` lines (its first 7578
inputs are this script's output for `em1`).

Usage, from the repository root; python3 -I keeps the working directory off the module path:
    curl -O https://www.vinc17.net/research/testlibm/testlibm-data.xz
    python3 -I dev/testlibm-extract.py sh sinh testlibm-data.xz > sinh-inputs.txt
    python3 -I dev/testlibm-extract.py ch cosh testlibm-data.xz | head
The arguments are the tag, the function (sinh, cosh, expm1, atan, asin or acos) and the data
file, plain or .xz. The tag and the function must be a pair of the table above.
    python3 -I dev/testlibm-extract.py --self-test
checks pi's first 50 digits, 4*atan(1) against pi, and the exact values at a few inputs against
the platform's `math` to 2^-50, and shows that the recheck fails on a result one unit in the
last place off, on the wrong direction bit and, for atan against asin, on another function's
value. It does not check the exact values to their 390 digits; the recheck of the data does, by
accepting only the file's correctly rounded results.

The inputs are not all hard cases: the summary on the standard error counts the lines with fewer
than 40 bits after the round bit pointing at the midpoint (none for `atn`, one for `asn`, most of
`acs`), and a file of hard cases written from this output should say which lines it keeps.
To check a committed file against the data: the output must equal the file's lines that start
with 0x, in the same order (sections after the data's, if any, are the file's own).
"""

import lzma
import math
import struct
import sys
from decimal import Decimal, getcontext, localcontext

getcontext().prec = 400
TAGS = {"sh": "sinh", "ch": "cosh", "em1": "expm1", "atn": "atan", "asn": "asin", "acs": "acos"}
EPSILON = Decimal(10) ** -395
_PI = []


def parse(text):
    """`1.0101...E-25` (binary significand, exponent a power of two) as (integer, exponent)."""
    sign = -1 if text.startswith("-") else 1
    text = text.lstrip("-")
    mantissa, exponent = text.split("E")
    whole, fraction = mantissa.split(".")
    return sign * int(whole + fraction, 2), int(exponent) - len(fraction)


def pow2(exponent):
    """2 to an integer exponent, exact: a power of two down to 2^-1074 has about 750 digits."""
    with localcontext() as context:
        context.prec = 1600
        return Decimal(2) ** exponent


def value(parsed):
    """A parsed (integer, exponent) as an exact Decimal: a power of two down to 2^-1074 needs 750
    digits and the integer 16, so the product is made with room."""
    integer, exponent = parsed
    with localcontext() as context:
        context.prec = 1600
        return Decimal(integer) * pow2(exponent)


def taylor(x, odd_only):
    """x + x^3/3! + x^5/5! + ... (sinh) or x + x^2/2! + x^3/3! + ... (expm1), for a small x."""
    term, total, n = x, Decimal(0), 1
    while True:
        total += term
        if odd_only:
            n += 2
            term = term * x * x / ((n - 1) * n)
        else:
            n += 1
            term = term * x / n
        if abs(term) < abs(total) * Decimal(10) ** -390:
            return total + term


def atan_series(x):
    """x - x^3/3 + x^5/5 - ... for a small x, to about 395 digits."""
    if x == 0:
        return Decimal(0)
    square, term, total, n = x * x, x, Decimal(0), 1
    while True:
        part = term / n
        total += part
        if abs(part) < abs(total) * EPSILON:
            return total
        term, n = -term * square, n + 2


def pi():
    """pi by Machin's formula, 4 * (4 atan(1/5) - atan(1/239))."""
    if not _PI:
        _PI.append(4 * (4 * atan_series(Decimal(1) / 5) - atan_series(Decimal(1) / 239)))
    return _PI[0]


def decimal_atan(x):
    """atan of the Decimal x: the argument above 1 goes to pi/2 - atan(1/x), and the angle is
    halved, tan(a/2) = tan(a) / (1 + sqrt(1 + tan(a)^2)), until the series converges fast."""
    if x < 0:
        return -decimal_atan(-x)
    if x > 1:
        return pi() / 2 - decimal_atan(1 / x)
    halvings = 0
    while x > Decimal("1e-3"):
        x = x / (1 + (1 + x * x).sqrt())
        halvings += 1
    return atan_series(x) * Decimal(2) ** halvings


def decimal_asin(x):
    """asin of the Decimal x in [-1, 1]."""
    if abs(x) == 1:
        return pi() / 2 if x > 0 else -pi() / 2
    return decimal_atan(x / (1 - x * x).sqrt())


def exact(function, x):
    """The function's value at the Decimal x, to about 390 digits."""
    small = abs(x) < Decimal("1e-30")
    if function == "sinh":
        return taylor(x, True) if small else (x.exp() - 1 / x.exp()) / 2
    if function == "cosh":
        return (x.exp() + 1 / x.exp()) / 2
    if function == "atan":
        return decimal_atan(x)
    if function == "asin":
        return decimal_asin(x)
    if function == "acos":
        return pi() / 2 - decimal_asin(x)
    return taylor(x, False) if small else x.exp() - 1


def check(function, x_double, x_decimal, result, direction):
    """Returns (problem or None, bits after the round bit that repeat a midpoint's pattern)."""
    ax = abs(exact(function, x_decimal))
    if ax == 0:
        return "the exact value is zero, which has no binade", 0
    binade = math.floor(float(ax.ln() / Decimal(2).ln()))
    while pow2(binade) > ax:
        binade -= 1
    while pow2(binade + 1) <= ax:
        binade += 1
    scaled = ax / pow2(binade - 53)          # 54 integer bits: 2^53 <= scaled < 2^54
    integer = int(scaled)
    fraction = scaled - integer
    bit54 = integer & 1
    half = scaled / 2
    rounded = int(half)
    remainder = half - rounded
    if remainder > Decimal("0.5") or (remainder == Decimal("0.5") and rounded & 1):
        rounded += 1
    with localcontext() as context:
        context.prec = 1600
        rounded_value = Decimal(rounded) * pow2(binade - 52)
    if rounded_value != abs(value(result)):
        return "the file's result is not the exact value rounded to nearest", 0
    if bit54 != direction:
        return "the direction bit is not the 54th bit of the exact value", 0
    # A midpoint is the 54th bit 1 then zeros; near one it is 1 then zeros, or 0 then ones.
    repeats, rest = 0, fraction
    while repeats < 400:
        rest *= 2
        bit = int(rest)
        rest -= bit
        if bit != (0 if bit54 == 1 else 1):
            break
        repeats += 1
    return None, repeats


def as_parsed(r):
    """The double r as (integer, exponent), the form parse() gives."""
    mantissa, exponent = math.frexp(r)
    return int(mantissa * 2 ** 53), exponent - 53


def self_test():
    """Exact values against `math` and identities, and the recheck failing where it must."""
    failures = []
    if not str(pi()).startswith("3.14159265358979323846264338327950288419716939937510"):
        failures.append("pi")
    if abs(4 * decimal_atan(Decimal(1)) - pi()) > pi() * EPSILON * 10:
        failures.append("4 atan(1) is not pi")
    samples = {"atan": [0.5, 1.0, 3.0, -2.5, 1e-9, 1e30, 0.4375],
               "asin": [0.5, -0.9, 0.999, 1e-9, 0.0625],
               "acos": [0.5, -0.9, 0.999, 0.0, 1e-9],
               "sinh": [0.5, -3.0, 1e-9], "cosh": [0.5, -3.0, 20.0],
               "expm1": [0.5, -3.0, 1e-9, 30.0]}
    for function, xs in samples.items():
        for x in xs:
            value_here = exact(function, Decimal(x))
            near = getattr(math, function)(x)
            if abs(float(value_here) - near) > 2.0 ** -50 * abs(near):
                failures.append("%s(%r) is not near math's value" % (function, x))
            r = float(value_here)
            accepted = {}
            for direction in (0, 1):
                problem, _ = check(function, x, Decimal(x), as_parsed(r), direction)
                accepted[direction] = problem is None
            if sum(accepted.values()) != 1:
                failures.append("%s(%r): the direction bit does not decide" % (function, x))
            if function == "atan" and 0.4 <= abs(x) < 1:   # another function's value must fail
                for direction in (0, 1):
                    problem, _ = check("asin", x, Decimal(x), as_parsed(r), direction)
                    if problem is None:
                        failures.append("atan(%r)'s value passes as asin's" % x)
            for neighbour in (math.nextafter(r, math.inf), math.nextafter(r, -math.inf)):
                for direction in (0, 1):
                    problem, _ = check(function, x, Decimal(x), as_parsed(neighbour), direction)
                    if problem is None:
                        failures.append("%s(%r): a result one ulp off passes" % (function, x))
    for failure in failures:
        print("self-test failed: " + failure, file=sys.stderr)
    if failures:
        sys.exit(1)
    print("self-test ok", file=sys.stderr)


def main(argv):
    if len(argv) == 2 and argv[1] == "--self-test":
        return self_test()
    if len(argv) != 4 or TAGS.get(argv[1]) != argv[2]:
        sys.exit(__doc__)
    tag, function, path = argv[1:]
    opener = lzma.open if path.endswith(".xz") else open
    with opener(path, "rt", encoding="ascii") as data:
        lines = [line.split() for line in data]
    lines = [f for f in lines if len(f) >= 5 and f[0] == "N" and f[1] == tag]
    out, repeats, problems = [], [], []
    for f in lines:
        x = parse(f[2])
        x_double = math.ldexp(x[0], x[1])
        if value(x) != Decimal(x_double):
            problems.append("%s: x is not a double" % f[2])
            continue
        problem, nearest = check(function, x_double, Decimal(x_double), parse(f[3]), int(f[4]))
        if problem:
            problems.append("%s: %s" % (f[2], problem))
            continue
        bits = struct.unpack(">Q", struct.pack(">d", x_double))[0]
        out.append("0x%016x" % bits)
        repeats.append(nearest)
    print("%d lines of mode N for %s, %d problems; bits after the round bit pointing at the "
          "midpoint: fewest %s, fewer than 40: %d" % (
              len(lines), tag, len(problems), min(repeats) if repeats else "-",
              sum(1 for r in repeats if r < 40)), file=sys.stderr)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        sys.exit(1)
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv)
