"""Which of the two results in ../photon-check.sql is the correctly rounded one.

For each of the 32 rows (f, x, strictmath, libm) it computes f(x) in decimal arithmetic at 80
significant digits and rounds that to a double (float() of a Decimal rounds correctly, to
nearest). On these 32 inputs (no expm1 or pow argument near 0, where exp(x) - 1 would cancel)
an error of a few units in the 80th digit cannot move a double's rounding unless the exact value
lies within about 1e-60 of a midpoint between doubles, which the script reports as "too close to
call"; it is not a general method for any input. The functions: exp(x), ln(x), expm1(x) as exp(x) - 1, and pow(2.0, x) as
exp(ln(2) * x). It also writes rows.txt in the current directory, one "f x libm" line a row,
for libmvec.c.
"""

import decimal
import math
import pathlib
import re
import sys

decimal.getcontext().prec = 80
D = decimal.Decimal
here = pathlib.Path(__file__).parent
sql = (here.parent / "photon-check.sql").read_text()
rows = re.findall(r"\('(\w+)', ([^D]+)D, ([^D]+)D, ([^D]+)D\)", sql)


def exact(f, x):
    d = D(x)  # the double's exact value
    if f == "exp":
        return d.exp()
    if f == "ln":
        return d.ln()
    if f == "expm1":
        return d.exp() - 1
    if f == "pow":
        return (D(2).ln() * d).exp()
    raise ValueError(f)


def too_close(v):
    """True if v lies within 1e-60 (relative) of a midpoint between two doubles."""
    lo = float(v)
    hi = math.nextafter(lo, math.inf) if D(lo) < v else math.nextafter(lo, -math.inf)
    mid = (D(lo) + D(hi)) / 2
    return abs(v - mid) <= abs(v) * D("1e-60")


counts = {"libm": 0, "strictmath": 0, "neither": 0, "too close": 0}
with open("rows.txt", "w") as out:  # in the current directory
    for f, xs, ss, ls in rows:
        x, s, l = float(xs), float(ss), float(ls)
        out.write(f"{f} {x!r} {l!r}\n")
        v = exact(f, x)
        cr = float(v)
        if too_close(v):
            print(f"{f}({x!r}): too close to call")
            counts["too close"] += 1
            continue
        who = "libm" if cr == l else "strictmath" if cr == s else "neither"
        counts[who] += 1
        if who != "libm":
            print(f"{f}({x!r}): correctly rounded {cr!r} is {who}'s; libm {l!r}, strictmath {s!r}")

print(f"{len(rows)} rows; the correctly rounded result is libm's on {counts['libm']}, "
      f"StrictMath's on {counts['strictmath']}, neither on {counts['neither']}, "
      f"too close to call on {counts['too close']}")
sys.exit(0 if len(rows) == 32 and sum(counts.values()) == 32 else 1)
