"""Which of StrictMath.exp and VectorOperators.EXP is correctly rounded where the two differ.

It reads OperatorExp.java's dump files, one "x strictmath operator" line per lane where the two
differ, and computes exp(x) as rounding.py does: in decimal arithmetic at 80 significant digits,
rounded to a double by float() of the Decimal (which rounds correctly, to nearest). An error of a
few units in the 80th digit cannot move a double's rounding unless the exact value lies within
about 1e-60 of a midpoint between doubles, which the script counts as "too close to call". Every
x here is in [-700, 700), so exp(x) is a positive normal double.

    python3 operator_rounding.py dump-256.txt [dump-512.txt ...]

For each file it prints one line: the lanes, and on how many the correctly rounded result is
StrictMath's, the operator's, neither or too close to call.
"""

import decimal
import math
import sys

decimal.getcontext().prec = 80
D = decimal.Decimal


def too_close(v):
    """True if v lies within 1e-60 (relative) of a midpoint between two doubles."""
    lo = float(v)
    hi = math.nextafter(lo, math.inf) if D(lo) < v else math.nextafter(lo, -math.inf)
    mid = (D(lo) + D(hi)) / 2
    return abs(v - mid) <= abs(v) * D("1e-60")


for path in sys.argv[1:]:
    counts = {"strictmath": 0, "operator": 0, "neither": 0, "too close": 0}
    with open(path) as f:
        for line in f:
            xs, ss, os_ = line.split()
            x, s, o = float(xs), float(ss), float(os_)
            v = D(x).exp()  # D(x) is the double's exact value
            if too_close(v):
                counts["too close"] += 1
                continue
            cr = float(v)
            counts["strictmath" if cr == s else "operator" if cr == o else "neither"] += 1
    n = sum(counts.values())
    print(f"{path}: {n} lanes differ; correctly rounded is StrictMath's on {counts['strictmath']},"
          f" the operator's on {counts['operator']}, neither on {counts['neither']},"
          f" too close to call on {counts['too close']}")
