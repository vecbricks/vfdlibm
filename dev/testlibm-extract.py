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

The tags in use: `sh` (sinh), `ch` (cosh) and `em1` (expm1), the functions this knows the exact
value of; the file also holds other tags. hard-cases/expm1.txt's header describes the same method
for the `em1` lines (its first 7578 inputs are this script's output for `em1`).

Usage, from the repository root; python3 -I keeps the working directory off the module path:
    curl -O https://www.vinc17.net/research/testlibm/testlibm-data.xz
    python3 -I dev/testlibm-extract.py sh sinh testlibm-data.xz > sinh-inputs.txt
    python3 -I dev/testlibm-extract.py ch cosh testlibm-data.xz | head
The arguments are the tag, the function (sinh, cosh or expm1) and the data file, plain or .xz.
To check a committed file against the data: the output must equal the file's lines that start
with 0x, in the same order (sections after the data's, if any, are the file's own).
"""

import lzma
import math
import struct
import sys
from decimal import Decimal, getcontext, localcontext

getcontext().prec = 400
FUNCTIONS = ("sinh", "cosh", "expm1")


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


def exact(function, x):
    """The function's value at the Decimal x, to about 390 digits."""
    small = abs(x) < Decimal("1e-30")
    if function == "sinh":
        return taylor(x, True) if small else (x.exp() - 1 / x.exp()) / 2
    if function == "cosh":
        return (x.exp() + 1 / x.exp()) / 2
    return taylor(x, False) if small else x.exp() - 1


def check(function, x_double, x_decimal, result, direction):
    """Returns (problem or None, bits after the round bit that repeat a midpoint's pattern)."""
    ax = abs(exact(function, x_decimal))
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


def main(argv):
    if len(argv) != 4 or argv[1] not in ("sh", "ch", "em1") or argv[2] not in FUNCTIONS:
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
