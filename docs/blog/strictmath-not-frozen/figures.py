#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Writes the figures of the post "Even StrictMath is not frozen" into img/, from data/.

It reuses ../figures.py's drawing (its Fig class, sizes and colours), writing here instead:

    python3 figures.py
"""

import math
import re
import struct
import sys
from decimal import Decimal, getcontext
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import figures as base  # noqa: E402  (the first post's figures.py, for its drawing)

base.OUT = HERE / "img"
DATA = HERE / "data"
Fig, M, IW = base.Fig, base.M, base.IW


def moved():
    """compare.py --all's lines of strict-scan-*.txt, as {(pair, file): count}."""
    text = (DATA / "strict-scan-2026-11-25.txt").read_text()
    out, pair = {}, None
    for line in text.splitlines():
        m = re.match(r"=== (\d+) vs (\d+)", line)
        if m:
            pair = m.groups()
            continue
        m = re.match(r"(StrictMath|Math)-(\w+)\.bin\s+(\d+) inputs\s+(\d+) differ", line)
        if m and pair:
            out[(pair, m.group(1), m.group(2))] = (int(m.group(4)), int(m.group(3)))
    return out


def fig_moved():
    d = moved()
    pairs = [("21", "25"), ("25", "26"), ("26", "27")]
    f = Fig("moved", "Whose bits moved with the JDK",
            "On 20 double functions, about 3 million inputs each: StrictMath moved once in four "
            "JDKs, Math three times on this x86-64 CPU.",
            "StrictMath: no change from JDK 21 to 25 or 25 to 26; pow changed on 1,258 inputs "
            "from 26 to 27, Math none. Math on an AMD Ryzen AI 9 HX PRO 370: cbrt and tanh changed from 21 "
            "to 25, sinh from 25 to 26.")
    y = f.header() + 8
    col = (IW - 120) / 3
    for i, (a, b) in enumerate(pairs):
        f.text(M + 120 + col * i + col / 2, y, f"JDK {a} → {b}", size=16, weight=700, anchor="middle")
    y += 18
    for who, fill, stroke, tint in (("StrictMath", base.BLUE_F, base.BLUE_S, base.BLUE_T),
                                    ("Math", base.ORANGE_F, base.ORANGE_S, base.ORANGE_T)):
        names = [[fn for (p, w, fn) in d if p == pr and w == who] for pr in pairs]
        h = 34 + 30 * max(1, max(len(n) for n in names))
        f.rect(M, y, IW, h, "#ffffff", base.LINE)
        f.text(M + 14, y + 28, who, size=17, weight=700, mono=True)
        for i, ns in enumerate(names):
            x = M + 120 + col * i + 8
            if not ns:
                f.text(x + col / 2 - 8, y + 28, "no change", size=15, fill=base.GREY, anchor="middle")
            for k, fn in enumerate(sorted(ns)):
                n, total = d[(pairs[i], who, fn)]
                f.rect(x, y + 10 + 30 * k, col - 16, 26, fill, stroke, rx=6)
                f.text(x + 8, y + 28 + 30 * k, fn, size=15, weight=700, fill=tint, mono=True)
                f.text(x + col - 24, y + 28 + 30 * k, f"{n:,}", size=14, fill=tint, anchor="end")
        y += h + 14
    y = f.caption(y + 18, "Counts are inputs whose result differs from the earlier JDK, out of "
                  "3,000,000 to 4,005,016 per function. StrictMath's results do not depend on the "
                  "CPU; Math's do, so another CPU may move other functions. Data: "
                  "strict-scan-2026-11-25.txt.")
    f.write(y)


def pow_errors():
    """(|y ln |x||, JDK 25's error, JDK 27's error) in ulps, for each normal changed result."""
    getcontext().prec = 80
    rows = []
    for line in (DATA / "pow-strictmath-jdk25-vs-jdk27.txt").read_text().split("\n"):
        if not line:
            continue
        x, y, a, b = (struct.unpack(">d", int(v, 16).to_bytes(8, "big"))[0] for v in line.split())
        if not (sys.float_info.min <= abs(a) < math.inf and sys.float_info.min <= abs(b) < math.inf):
            continue
        exact = (Decimal(y) * Decimal(abs(x)).ln()).exp()
        if x < 0 and y == int(y) and int(y) % 2:
            exact = -exact
        u = Decimal(2) ** (math.floor(abs(exact).log10() / Decimal(2).log10()) - 52)  # exact's binade
        rows.append((abs(y * math.log(abs(x))), float(abs((Decimal(a) - exact) / u)),
                     float(abs((Decimal(b) - exact) / u))))
    return rows


def fig_pow_errors():
    rows = pow_errors()
    worst25 = max(r[1] for r in rows)
    worst27 = max(r[2] for r in rows)
    least25 = min(r[1] for r in rows)
    f = Fig("pow-errors", "StrictMath.pow's error, JDK 25 and JDK 27",
            f"On the {len(rows):,} inputs whose result JDK 27 changed (normal results, of "
            "1,000,000 aimed at |y| > 2^31 with |x| near 1): JDK 25 was off by more than an ulp on "
            "every one.",
            f"Error in ulps against an 80-digit pow, by |y ln |x||: JDK 25 from {least25:.0f} to "
            f"{worst25:.0f} ulps, JDK 27 at most {worst27:.2f} ulp.")
    y0 = f.header() + 4
    px, pw, ph = M + 52, IW - 64, 300
    lo, hi = math.log10(0.01), math.log10(1000)
    xlo, xhi = 150, 720

    def X(z):
        return px + (z - xlo) / (xhi - xlo) * pw

    def Y(e):
        return y0 + ph - (math.log10(max(e, 0.01)) - lo) / (hi - lo) * ph

    f.rect(px, y0, pw, ph, "#ffffff", base.LINE, rx=4)
    for e, label in ((0.01, "0.01"), (0.1, "0.1"), (1, "1 ulp"), (10, "10"), (100, "100"), (1000, "1000")):
        style = 'stroke="#9e9e9e" stroke-width="2" stroke-dasharray="6 4"' if e == 1 else \
            'stroke="#ececec" stroke-width="1"'
        f.add(f'<path d="M{px:.1f},{Y(e):.1f} H{px + pw:.1f}" {style}/>')
        f.text(px - 8, Y(e) + 5, label, size=13, fill=base.GREY, anchor="end")
    for z in (200, 300, 400, 500, 600, 700):
        f.text(X(z), y0 + ph + 20, str(z), size=13, fill=base.GREY, anchor="middle")
    f.text(px + pw / 2, y0 + ph + 42, "|y ln |x||, which is |ln |pow(x, y)||", size=14,
           fill=base.GREY, anchor="middle")
    for z, e25, e27 in rows:
        f.add(f'<circle cx="{X(z):.1f}" cy="{Y(e25):.1f}" r="2.2" fill="{base.ORANGE_S}" fill-opacity="0.55"/>')
        f.add(f'<circle cx="{X(z):.1f}" cy="{Y(e27):.1f}" r="2.2" fill="{base.BLUE_S}" fill-opacity="0.55"/>')
    ly = y0 + ph + 70
    f.add(f'<circle cx="{M + 8}" cy="{ly - 5}" r="6" fill="{base.ORANGE_S}"/>')
    f.text(M + 22, ly, "JDK 25.0.5.1", size=15, weight=700, fill=base.ORANGE_T)
    f.add(f'<circle cx="{M + 158}" cy="{ly - 5}" r="6" fill="{base.BLUE_S}"/>')
    f.text(M + 172, ly, "JDK 27.0.1.1", size=15, weight=700, fill=base.BLUE_T)
    y = f.caption(ly + 30, "Each input is one orange and one blue dot; errors under 0.01 ulp are "
                  "drawn at 0.01. Data: "
                  "pow-strictmath-jdk25-vs-jdk27.txt and errors.py.")
    f.write(y)


if __name__ == "__main__":
    fig_moved()
    fig_pow_errors()
    for p in sorted(base.OUT.glob("*.svg")):
        print(p.name, p.stat().st_size)
