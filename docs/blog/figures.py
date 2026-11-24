"""Writes the blog post's figures, img/*.svg, from the committed data under strictmath-vs-libm/.

    python3 figures.py          # from docs/blog/, or with any working directory

Every figure is 560 units wide with text of 15 units or more, so that a phone that scales it to
its width (about 360 CSS pixels) still shows text of about 10 pixels, and it uses a font stack
with system fallbacks, since an SVG shown through <img> loads no web font. Text widths are
estimated generously, so a wider fallback font still fits. The data figures read only committed
files:

- strictmath-vs-libm/data/exp1-bits.txt (ExpOne.java's output): the bits of exp(1);
- photon-check.sql and strictmath-vs-libm/data/*.csv: who returns what on the 32 inputs;
- strictmath-vs-libm/data/compare-glibc-2.43.txt (compare.py's output): how often they differ;
- strictmath-vs-libm/data/nantier-jdk-25.0.5.1.txt: NanTier.java's output, one run per mode;
- strictmath-vs-libm/data/hypot-order.txt (hypot-order.c's and HypotOrder.java's output): the
  hypot argument-order card;
- strictmath-vs-libm/data/aggregate-photon-2026-11-24.csv and aggregate-spark-4.3.0-jdk21.tsv
  (aggregate-check.sql's output on each engine): the aggregate card;
- ../../bench/results/exp-xeon8573c-2026-11-21.txt: the allocation of exp's finish rows;
- ../../reference/fdlibm-5.3/e_hypot.c: the lines of the hypot figure, by line number.

The other figures are diagrams whose text is written here. The speedup figure and the {{E}} of
the comparison figure are placeholders until the README's v0.3.0 speedup table exists; at
release, this script is to read them from that table (not the SVG files edited by hand). No figure holds a number that is not
in a committed file or in the post's sources.
"""

import csv
import html
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
DATA = HERE / "strictmath-vs-libm" / "data"
OUT = HERE / "img"
W = 560
M = 24            # the margin
IW = W - 2 * M    # the inner width

SANS = "Inter, 'IBM Plex Sans', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
MONO = "'JetBrains Mono', 'IBM Plex Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace"
INK, GREY, LINE = "#1d1d1f", "#5a5a5a", "#d6d6d6"
BLUE_F, BLUE_S, BLUE_T = "#e8f0fb", "#4a7fd0", "#2f63b3"
ORANGE_F, ORANGE_S, ORANGE_T = "#fdf0e3", "#e08a2e", "#b5521d"
KHAKI_F, KHAKI_S = "#f3ecd9", "#b8a67a"
GREY_F, GREY_S = "#f2f2f2", "#bdbdbd"


def esc(s):
    return html.escape(str(s), quote=True)


def width_of(s, size, mono=False):
    """A generous estimate of a text's width, so that a fallback font still fits."""
    return len(s) * size * (0.62 if mono else 0.57)


def wrap(s, size, maxw, mono=False):
    words, lines, cur = s.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if cur and width_of(t, size, mono) > maxw:
            lines.append(cur)
            cur = w
        else:
            cur = t
    if cur:
        lines.append(cur)
    return lines


class Fig:
    def __init__(self, name, title, subtitle, desc):
        self.name, self.title, self.subtitle, self.desc = name, title, subtitle, desc
        self.parts = []

    def add(self, s):
        self.parts.append(s)

    def text(self, x, y, s, size=17, weight=400, fill=INK, mono=False, anchor="start"):
        fam = MONO if mono else SANS
        st = ' style="font-variant-ligatures: none; white-space: pre" xml:space="preserve"' if mono else ""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{esc(fam)}" font-size="{size}" '
                 f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}"{st}>{esc(s)}</text>')

    def para(self, x, y, s, size=17, maxw=IW, lh=None, **kw):
        """Wrapped text from baseline y; returns the last line's baseline."""
        lh = lh or round(size * 1.35)
        lines = wrap(s, size, maxw, kw.get("mono", False))
        for i, ln in enumerate(lines):
            self.text(x, y + i * lh, ln, size=size, **kw)
        return y + (len(lines) - 1) * lh

    def rect(self, x, y, w, h, fill, stroke, rx=12, dash=False, sw=2):
        d = ' stroke-dasharray="8 6"' if dash else ""
        self.add(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')

    def arrow(self, x1, y1, x2, y2):
        self.add(f'<path d="M{x1:.1f},{y1:.1f} L{x2:.1f},{y2:.1f}" stroke="#333" stroke-width="2" '
                 f'fill="none" marker-end="url(#arrow-{self.name})"/>')

    def pill(self, x, y, w, h, fill, s, size=17, color="#fff"):
        self.rect(x, y, w, h, fill, fill, rx=h / 2, sw=0)
        self.text(x + w / 2, y + h / 2 + size * 0.36, s, size=size, weight=700, fill=color, anchor="middle")

    def badge(self, cx, cy, s, fill):
        self.add(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="15" fill="{fill}"/>')
        self.text(cx, cy + 6, s, size=16, weight=700, fill="#fff", anchor="middle")

    def header(self):
        y = self.para(M, 46, self.title, size=24, weight=700)
        return self.para(M, y + 30, self.subtitle, size=16, fill=GREY) + 28

    def caption(self, y, s):
        return self.para(M, y, s, size=15, fill=GREY, lh=20) + 22

    def write(self, height):
        body = "\n  ".join(self.parts)
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {height:.0f}" width="{W}" height="{height:.0f}" role="img" aria-labelledby="t-{self.name} d-{self.name}">
  <title id="t-{self.name}">{esc(self.title)}</title>
  <desc id="d-{self.name}">{esc(self.desc)}</desc>
  <defs><marker id="arrow-{self.name}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#333"/></marker></defs>
  <rect x="1" y="1" width="{W - 2}" height="{height - 2:.0f}" rx="16" fill="#fbfbfa" stroke="{LINE}" stroke-width="2"/>
  {body}
</svg>
'''
        (OUT / f"{self.name}.svg").write_text(svg)


# --- data -------------------------------------------------------------------------------------

def rows_of_sql():
    sql = (HERE / "photon-check.sql").read_text()
    out = []
    for f, x, s, l in re.findall(r"\('(\w+)', ([^D]+)D, ([^D]+)D, ([^D]+)D\)", sql):
        out.append({"f": f, "x": float(x), "strictmath": float(s), "libm": float(l)})
    return out


def column(path, field):
    with open(path) as fh:
        return {(r["f"], float(r["x"])): float(r[field]) for r in csv.DictReader(fh) if r[field]}


# --- figures ----------------------------------------------------------------------------------

def fig_exp1():
    vals = {}
    for line in (DATA / "exp1-bits.txt").read_text().splitlines():
        name, v, bits = line.rsplit(" ", 2)
        vals[name] = (v, int(bits, 16))
    a_v, a = vals["StrictMath.exp(1)"]
    b_v, b = vals["Math.E"]
    assert vals["Math.exp(1)"][1] == b
    diff = a ^ b
    nd = bin(diff).count("1")
    f = Fig("exp1-bits", "Next-door doubles",
            "exp(1) from StrictMath, which Spark calls, and Java's Math.E, as 64 bits each.",
            f"StrictMath.exp(1) is {a_v} and Math.E is {b_v}. Their bits are {a:016x} and {b:016x}; "
            f"they differ in the last {nd} bits, and as numbers they are neighbours.")
    y = f.header()
    f.text(M, y + 6, "1 sign bit · 11 exponent bits · 52 mantissa bits", size=15, fill=GREY)
    y += 22
    cell = 7.6
    for label, v, bits, on in (("StrictMath.exp(1)", a_v, a, BLUE_S), ("Math.E", b_v, b, "#8a8a8a")):
        f.text(M, y + 20, label, size=17, weight=700)
        f.text(M + IW, y + 20, v, size=17, weight=700, mono=True, anchor="end")
        y += 32
        for i in range(64):
            bit = (bits >> (63 - i)) & 1
            xx = M + i * cell + (3 if i >= 1 else 0) + (3 if i >= 12 else 0)
            hit = (diff >> (63 - i)) & 1
            f.rect(xx, y, cell - 1.6, 20, on if bit else "#ffffff",
                   ORANGE_S if hit else "#c9c9c9", rx=1.5, sw=2.4 if hit else 0.8)
        y += 46
        hexs = f"{bits:016x}"
        f.text(M, y, "0x" + " ".join(hexs[i:i + 4] for i in range(0, 16, 4)), size=16, mono=True)
        lb = f"{bits & 0xff:08b}"
        f.text(M + IW, y, f"last byte {lb[:4]} {lb[4:]}", size=16, mono=True, weight=700,
               fill=ORANGE_T, anchor="end")
        y += 34
    f.pill(M, y, IW, 42, ORANGE_S, "One ulp apart: no double in between", size=17)
    y += 72
    y = f.caption(y, f"Filled squares are 1 bits; the {nd} outlined ones differ. Bits from "
                     "Double.doubleToRawLongBits on JDK 25 (ExpOne.java). Math.exp(1) has the "
                     "same bits as Math.E.")
    f.write(y)


def fig_spark_emits():
    f = Fig("spark-emits", "What Spark's generated code calls",
            "Spark 4.3.0, mathExpressions.scala: who computes each SQL function.",
            "Spark's generated code calls java.lang.StrictMath for exp, expm1, ln, log10, log1p and "
            "power, and for log2, log with a base, asinh, acosh and atanh through StrictMath.log "
            "and log1p. java.lang.Math serves sin, cos, tan, asin, acos, atan, sinh, cosh, tanh, "
            "cbrt, sqrt, hypot and atan2.")
    y = f.header()
    x0 = M + 18
    top, idx = y, len(f.parts)
    f.text(x0, y + 36, "java.lang.StrictMath", size=19, weight=700, mono=True)
    f.text(x0, y + 62, "the same bits on every JVM and CPU", size=16, fill=GREY)
    yy, xs = y + 80, x0
    for fn in ("exp", "expm1", "ln", "log10", "log1p", "power"):
        w = width_of(fn, 16, True) + 24
        if xs + w > M + IW - 12:
            xs, yy = x0, yy + 42
        f.pill(xs, yy, w, 32, BLUE_S, fn, size=16)
        xs += w + 8
    yy = f.para(x0, yy + 60, "and log2, log(base, x), asinh, acosh and atanh, through "
                "StrictMath.log and log1p",
                size=15, maxw=IW - 36, fill=GREY)
    h = yy + 20 - top
    f.parts.insert(idx, f'<rect x="{M}" y="{top}" width="{IW}" height="{h}" rx="12" fill="{BLUE_F}" '
                   f'stroke="{BLUE_S}" stroke-width="2"/>')
    y = top + h + 18
    top, idx = y, len(f.parts)
    f.text(x0, y + 36, "java.lang.Math", size=19, weight=700, mono=True)
    f.text(x0, y + 62, "the base classes' default, and atan2's own code", size=16, fill=GREY)
    yy = f.para(x0, y + 94, "sin, cos, tan, asin, acos, atan, sinh, cosh, tanh, cbrt, sqrt, hypot, atan2",
                size=16, maxw=IW - 36, mono=True)
    h = yy + 24 - top
    f.parts.insert(idx, f'<rect x="{M}" y="{top}" width="{IW}" height="{h}" rx="12" '
                   f'fill="{GREY_F}" stroke="#9e9e9e" stroke-width="2" stroke-dasharray="8 6"/>')
    y = top + h + 30
    y = f.caption(y, "Exp, Expm1 and Pow override the generated code; Log, Log10 and Log1p inherit it "
                     "from UnaryLogExpression. The interpreted path is passed the same functions.")
    f.write(y)


def fig_one_call():
    f = Fig("one-call", "How one lane gets StrictMath's bits",
            "Inside VFdlibm.exp(x): fdlibm's own tests send each lane one of two ways.",
            "fdlibm's own compares on each lane's high word split the lanes of a vector in two. A "
            "common lane is computed by the vector code, fdlibm's C operation by operation. In most "
            "functions a rare lane is computed again, one at a time, by StrictMath, and that result "
            "is written over the lane. Either way the lane holds StrictMath's bits.")
    y = f.header()
    f.rect(M + 40, y, IW - 80, 84, KHAKI_F, KHAKI_S)
    f.text(W / 2, y + 34, "One lane of a DoubleVector", size=19, weight=700, anchor="middle")
    f.text(W / 2, y + 62, "the C's own branch tests", size=16, anchor="middle")
    top = y + 84
    y += 140
    half = (IW - 16) / 2
    f.arrow(W / 2 - 50, top + 2, M + half / 2, y - 4)
    f.arrow(W / 2 + 50, top + 2, W - M - half / 2, y - 4)
    f.text(M + half / 2 - 8, top + 34, "common", size=15, mono=True, anchor="end")
    f.text(W - M - half / 2 + 8, top + 34, "rare", size=15, mono=True)
    for x, badge, head, body, fill, stroke, dash, tcol, notes, res in (
            (M, "A", "Vector code", "fdlibm's C, operation by operation, every lane at once",
             ORANGE_F, ORANGE_S, False, ORANGE_T, "+ − × ÷ √ and bit operations; no fused multiply-add",
             "The C's roundings"),
            (M + half + 16, "B", "One at a time", "StrictMath for each rare lane, written over it",
             BLUE_F, BLUE_S, True, BLUE_T, "range edges, infinities, NaNs", "StrictMath's result")):
        f.rect(x, y, half, 214, fill, stroke, dash=dash)
        f.badge(x + 28, y + 30, badge, "#d9622b")
        f.text(x + 52, y + 36, head, size=18, weight=700)
        f.para(x + 16, y + 72, body, size=16, maxw=half - 30)
        f.para(x + 16, y + 150, notes, size=15, maxw=half - 30, fill=GREY, lh=19)
        f.text(x + 16, y + 244, res, size=17, weight=700, fill=tcol)
    y += 272
    f.pill(M, y, IW, 44, ORANGE_S, "Each lane = StrictMath's, by raw bits", size=17)
    y += 76
    y = f.caption(y, "One exception: a lane of two different NaNs in atan2, hypot or pow. Functions "
                     "differ: some compute a rare branch in the vector too, and atan2 sends no lane "
                     "to StrictMath.")
    f.write(y)


def fig_who(full):
    """The grid of who returns what: all 32 inputs and five sources (full), or for the post's body
    two inputs a function (the first, and the first where the correctly rounded result is
    StrictMath's if there is one, else the second) and three sources."""
    rows = rows_of_sql()
    spark = column(DATA / "spark-4.3.0-local-2026-11-24.csv", "spark")
    photon = column(DATA / "photon-2026-11-24.csv", "photon")
    mvec = column(DATA / "libmvec-glibc-2.43-avx2.csv", "libmvec")
    correct = column(DATA / "correctly-rounded.csv", "correct")
    cols = [("Spark", "4.3.0", spark), ("Databricks", "Photon", photon),
            ("glibc", "scalar", None)]
    if full:
        cols += [("glibc", "libmvec", mvec), ("correct", "rounding", correct)]
    else:
        picked = []
        for fn in dict.fromkeys(r["f"] for r in rows):
            mine = [r for r in rows if r["f"] == fn]
            cr_s = [r for r in mine[1:] if correct.get((fn, r["x"])) == r["strictmath"]]
            picked += [mine[0], cr_s[0] if cr_s else mine[1]]
        rows = picked
    counts = [[0, 0, 0] for _ in cols]
    if full:
        f = Fig("who-returns-what-full", "Who returns what on 32 inputs",
                "Inputs where StrictMath and glibc 2.43 give different doubles.", "")
    else:
        f = Fig("who-returns-what", "Who returns what",
                f"{len(rows)} of the 32 inputs where StrictMath and glibc 2.43 give different doubles.", "")
    y = f.header()
    f.pill(M, y, 30, 24, ORANGE_S, "G", size=15)
    f.text(M + 38, y + 18, "= glibc's result", size=15)
    f.pill(M + 210, y, 30, 24, BLUE_S, "S", size=15)
    f.text(M + 248, y + 18, "= StrictMath's", size=15)
    if full:
        f.text(M + IW, y + 18, "· neither", size=15, fill=GREY, anchor="end")
    y += 46
    cw = 78 if full else 110
    c0 = M + IW - len(cols) * cw
    for i, (a, b, _) in enumerate(cols):
        cx = c0 + i * cw + cw / 2
        f.text(cx, y + 14, a, size=14 if full else 15, weight=700, anchor="middle")
        f.text(cx, y + 32, b, size=13, fill=GREY, anchor="middle")
    y += 44
    names = {"exp": "exp(x)", "ln": "ln(x)", "expm1": "expm1(x)", "pow": "pow(2, x)"}
    last = None
    for r in rows:
        if r["f"] != last:
            f.rect(M, y, IW, 26, "#ececec", "#ececec", rx=6, sw=0)
            f.text(M + 10, y + 19, names[r["f"]], size=15, weight=700, mono=True)
            y += 31
            last = r["f"]
        f.text(M + 10, y + 17, f"x = {r['x']:.5g}", size=15, mono=True, fill=GREY)
        key = (r["f"], r["x"])
        for i, (_, _, data) in enumerate(cols):
            v = r["libm"] if data is None else data.get(key)
            k = 2 if v is None else 0 if v == r["libm"] else 1 if v == r["strictmath"] else 2
            counts[i][k] += 1
            cx = c0 + i * cw + cw / 2
            if k == 2:
                f.text(cx, y + 18, "·", size=20, weight=700, fill=GREY, anchor="middle")
            else:
                f.pill(cx - 16, y + 2, 32, 21, ORANGE_S if k == 0 else BLUE_S, "G" if k == 0 else "S", size=14)
        y += 25
    y += 6
    f.text(M + 10, y + 18, "G / S", size=15, weight=700)
    for i, c in enumerate(counts):
        f.text(c0 + i * cw + cw / 2, y + 18, f"{c[0]} / {c[1]}", size=15, weight=700, anchor="middle")
    y += 50
    if full:
        y = f.caption(y, "Consistent with a call to glibc's scalar functions for each element; "
                         "inconsistent with StrictMath, a correctly rounded library and glibc 2.43's "
                         "AVX2 libmvec. Not excluded: another library or glibc version with the same "
                         "doubles here, a hand-vectorized port of glibc's algorithms, libmvec at "
                         "another width, another CPU (the warehouse's is unknown).")
    y = f.caption(y, "Photon: Databricks Free Edition, SQL warehouse, 24 November 2026. Spark and glibc: my "
                     "laptop, glibc 2.43." + ("" if full else " All 32 inputs give the same picture."))
    f.desc = (f"For {len(rows)} inputs, which result each source returned: G for glibc 2.43's "
              "result, S for StrictMath's. Totals G / S: " + "; ".join(
                  f"{a} {b}: {c[0]} / {c[1]}" for (a, b, _), c in zip(cols, counts)) + ".")
    f.write(y)
    return counts


def fig_how_often():
    txt = (DATA / "compare-glibc-2.43.txt").read_text()
    n = int(re.search(r"^(\d+) inputs", txt, re.M).group(1))
    items = re.findall(r"^\s*(\S.*?): StrictMath and libm differ on ([\d,]+)$", txt, re.M)
    items = sorted(((name, int(c.replace(",", ""))) for name, c in items), key=lambda t: -t[1])
    f = Fig("how-often", "How often StrictMath and glibc differ",
            f"{n:,} random inputs, x uniform between -700 and 700 (seed 7); JDK 25 against glibc 2.43.",
            "Inputs on which StrictMath and glibc 2.43 give different doubles: " +
            ", ".join(f"{name} {c:,}" for name, c in items) + f", of {n:,}.")
    y = f.header()
    top = max(c for _, c in items)
    for name, c in items:
        f.text(M, y + 18, name, size=16, mono=True)
        y += 28
        w = max(3, IW * c / top)
        f.rect(M, y, w, 26, ORANGE_F, ORANGE_S, rx=6)
        label = f"{c:,} ({100 * c / n:.2g}%)"
        if w > width_of(label, 16) + 20:
            f.text(M + w - 10, y + 19, label, size=16, weight=700, anchor="end", fill=ORANGE_T)
        else:
            f.text(M + w + 10, y + 19, label, size=16, weight=700, fill=ORANGE_T)
        y += 44
    y += 12
    y = f.caption(y, "Bars to scale with the largest count. Gen.java and compare.py, in "
                     "strictmath-vs-libm/; Python's math module calls the C library.")
    f.write(y)


def fig_compare():
    f = Fig("compare", "Three ways to compute exp",
            "What each one promises, from its Javadoc or this project's files.",
            "StrictMath.exp gives fdlibm's bits on every CPU, one double per call. VectorOperators.EXP "
            "works on a vector and promises an accuracy bound, not StrictMath's bits; its speed is not "
            "measured here. VFdlibm.exp works on a vector and gives StrictMath's bits in every lane.")
    y = f.header()
    red = "#c62828"
    cards = (("StrictMath.exp", BLUE_S, BLUE_F, False,
              [("Bits", "fdlibm 5.3's, by definition", None), ("Every CPU", "the same bits", None),
               ("Per call", "one double", None), ("Speed", "the baseline", None)]),
             ("VectorOperators.EXP", "#8a8a8a", GREY_F, True,
              [("Bits", "not promised: an accuracy bound", red), ("Every CPU", "not promised", red),
               ("Per call", "a vector of doubles", None), ("Speed", "not measured here", None)]),
             ("VFdlibm.exp", ORANGE_S, ORANGE_F, False,
              [("Bits", "StrictMath's, every lane", None), ("Every CPU", "the same bits", None),
               ("Per call", "a vector of doubles", None),
               ("Speed", "{{E}}× (common data, Xeon 8573C)", None)]))
    for name, pc, fill, dash, lines in cards:
        h = 66 + 28 * len(lines) + 4
        f.rect(M, y, IW, h, fill, pc, dash=dash)
        f.pill(M + 16, y + 14, width_of(name, 15, True) + 32, 32, pc, name, size=15)
        yy = y + 74
        for k, v, col in lines:
            f.text(M + 18, yy, k, size=16, weight=700)
            f.text(M + 128, yy, v, size=16, weight=700 if col else 400, fill=col or INK)
            yy += 28
        y += h + 14
    y += 12
    y = f.caption(y, "Sources: the JDK 25 Javadoc of Math, StrictMath and VectorOperators; vfdlibm's "
                     "README. {{E}} is a placeholder until the README's v0.3.0 speedup table exists.")
    f.write(y)


def fig_nan():
    f = Fig("nan-tier", "Same call, different NaN",
            "StrictMath.atan2(NaN#1, NaN#2), two million times: which NaN comes back?",
            "Under -Xint all 2,000,000 calls returned the first NaN. Under the default JIT tiers "
            "1,983,613 returned the first and 16,387 the second. One run each on a laptop, JDK "
            "25.0.5.1 on x86-64.")
    y = f.header()
    txt = (DATA / "nantier-jdk-25.0.5.1.txt").read_text()
    runs = re.findall(r"^\$ (java .*?NanTier\.java)\n.*first=(\d+) second=(\d+)", txt, re.M)
    assert len(runs) == 2, runs
    for band, first, second in ((b, int(a), int(c)) for b, a, c in runs):
        f.rect(M, y, IW, 30, "#ececec", "#ececec", rx=6, sw=0)
        f.text(M + 12, y + 21, band, size=15, mono=True, weight=700)
        y += 42
        total = first + second
        w1 = IW * first / total
        f.rect(M, y, w1, 40, BLUE_F, BLUE_S, rx=8)
        if second:
            f.rect(M + w1, y, max(4, IW - w1), 40, ORANGE_S, ORANGE_S, rx=0, sw=0)
        f.text(M + 14, y + 26, "first NaN", size=16)
        f.text(M + w1 - 14, y + 26, f"{first:,}", size=17, weight=700, mono=True, fill=BLUE_T, anchor="end")
        y += 66
        f.text(M, y, "second NaN:", size=16)
        f.text(M + 104, y, f"{second:,}", size=17, weight=700, mono=True, fill=ORANGE_T if second else INK)
        y += 34
    f.pill(M, y, IW, 42, ORANGE_S, "StrictMath disagrees with itself here", size=17)
    y += 72
    y = f.caption(y, "One run each on my laptop, JDK 25.0.5.1 on x86-64; your counts will differ. "
                     "Bars to scale; the orange sliver is the second NaN.")
    f.write(y)


def fig_hypot():
    src = (HERE.parent.parent / "reference" / "fdlibm-5.3" / "e_hypot.c").read_text().splitlines()
    show = [78, None, 81, 82, 83, 84, 85, 86, 87, None, 89, 90, None, 95, 96, 97, 98]
    marks = {82: ("× 2^1022", ORANGE_F, ORANGE_T), 83: ("", ORANGE_F, ORANGE_T),
             84: ("ha, hb kept", ORANGE_F, ORANGE_T),
             89: ("written back", GREY_F, GREY), 90: ("", GREY_F, GREY),
             97: ("old word", BLUE_F, BLUE_T)}
    f = Fig("hypot-stale", "hypot reads a word it did not update",
            "fdlibm 5.3's e_hypot.c: for a subnormal argument the values are scaled, the words are not.",
            "Lines 81 to 84 multiply a and b by 2^1022 and leave ha and hb as they were; lines 85 to 90, "
            "the 2^600 scaling, write the words back; line 97 reads ha, which on the 2^1022 path is the "
            "word of the unscaled a.")
    y = f.header()
    lh = 26
    base = min(len(src[n - 1].expandtabs(8)) - len(src[n - 1].expandtabs(8).lstrip(" "))
               for n in show if n)
    f.rect(M, y, IW, lh * len(show) + 26, "#ffffff", LINE)
    y += 32
    for n in show:
        if n is None:
            f.text(M + 46, y, "…", size=16, mono=True, fill=GREY)
            y += lh
            continue
        code = re.sub(r"\s*/\*.*?\*/", "", src[n - 1].expandtabs(8)).rstrip()
        lead = len(code) - len(code.lstrip(" ")) - base
        code = "\u00a0" * (lead // 2) + code.lstrip(" ")  # half the C's indentation, to fit
        if n in marks:
            note, fill, col = marks[n]
            f.rect(M + 6, y - 18, IW - 12, lh - 2, fill, fill, rx=4, sw=0)
            if note:
                f.text(M + IW - 14, y, note, size=15, weight=700, fill=col, anchor="end")
        f.text(M + 12, y, f"{n:>3}", size=15, mono=True, fill="#8a8a8a")
        f.text(M + 54, y, code, size=16, mono=True)
        y += lh
    y += 26
    y = f.caption(y, "Lines as in reference/fdlibm-5.3/e_hypot.c, comments left out, indentation "
                     "narrowed. Reading the words again after line 84 changes the result on some "
                     "inputs (PROOFS.md, L16).")
    f.write(y)


def fig_boxing():
    res = (HERE.parent.parent / "bench" / "results" / "exp-xeon8573c-2026-11-21.txt").read_text()

    def norm(row):
        m = re.search(rf"^ExpBenchmark\.{row}:gc\.alloc\.rate\.norm\s+common\s+N/A\s+avgt\s+\d+\s+([\d.]+)",
                      res, re.M)
        return float(m.group(1))

    v256, v128 = norm("finish256"), norm("finish128")
    f = Fig("boxing", "The same code, as vectors or boxed",
            "exp's rows that finish rare lanes one at a time: bytes allocated per 1024 doubles.",
            f"On the Xeon 8573C on 21 November 2026, on common data, exp's finish row reads {v256:.3f} "
            f"bytes per 1024 doubles at 256 bits, and {v128:,.0f} bytes at 128 bits on x86, where "
            "the masks box.")
    y = f.header()
    half = (IW - 16) / 2
    for x, head, num, sub, fill, stroke, dash, col in (
            (M, "256 bits", f"{v256:.3f} B", "vectors in registers", ORANGE_F, ORANGE_S, False, ORANGE_T),
            (M + half + 16, "128 bits, x86", f"{v128:,.0f} B", "boxed: vectors as objects", BLUE_F, BLUE_S, True,
             BLUE_T)):
        f.rect(x, y, half, 150, fill, stroke, dash=dash)
        f.text(x + 16, y + 34, head, size=17, weight=700)
        f.text(x + 16, y + 86, num, size=29, weight=700, fill=col)
        f.para(x + 16, y + 122, sub, size=15, maxw=half - 28, fill=GREY)
    y += 186
    y = f.caption(y, "The finish rows on common data, the Xeon 8573C's file of 21 November "
                     "(bench/results/exp-xeon8573c-2026-11-21.txt). The results are the same either "
                     "way; only the allocation shows it.")
    f.write(y)


def fig_hypot_order():
    txt = (DATA / "hypot-order.txt").read_text()
    c = re.search(r"hypot\(x, y\) = ([0-9a-f]{16}), hypot\(y, x\) = ([0-9a-f]{16})", txt)
    cxy, cyx = c.group(1), c.group(2)
    xy = re.search(r"x = 0x([0-9a-f]{16}), y = 0x([0-9a-f]{16})", txt)
    xb, yb = xy.group(1), xy.group(2)
    assert xb[:8] == yb[:8], "the card says the two share their high 32 bits"
    jdks = re.findall(r"^JDK (\S+): StrictMath\.hypot\(x, y\) = ([0-9a-f]{16}), StrictMath\.hypot\(y, x\) = "
                      r"([0-9a-f]{16}), Math\.hypot\(x, y\) = ([0-9a-f]{16}), Math\.hypot\(y, x\) = ([0-9a-f]{16})",
                      txt, re.M)
    assert jdks and all(len(set(j[1:])) == 1 for j in jdks), jdks
    sm = jdks[0][1]
    assert all(j[1] == sm for j in jdks)
    assert cxy != cyx and sm in (cxy, cyx), "the card says the C's answer depends on the order"
    versions = ", ".join(j[0].split(".")[0] for j in jdks)
    f = Fig("hypot-order", "One input, two answers from the C",
            "hypot(x, y) and hypot(y, x) on the input the nightly fuzzer found.",
            f"x = 0x{xb} and y = 0x{yb} share their high 32 bits. fdlibm 5.3's "
            f"C returns {cxy} for hypot(x, y) and {cyx} for hypot(y, x). StrictMath.hypot and "
            f"Math.hypot return {sm} in both orders on JDK {versions}.")
    y = f.header()
    f.rect(M, y, IW, 64, GREY_F, GREY_S, rx=10)
    f.text(M + 16, y + 26, f"x = 0x{xb[:8]} {xb[8:]}", size=16, mono=True)
    f.text(M + 16, y + 50, f"y = 0x{yb[:8]} {yb[8:]}", size=16, mono=True)
    f.text(M + IW - 16, y + 38, "same high 32 bits", size=15, fill=GREY, anchor="end")
    y += 88

    for label, sub, val, fill, stroke, tcol, dash in (
            ("fdlibm 5.3 C", "hypot(x, y)", cxy, ORANGE_F, ORANGE_S, ORANGE_T, False),
            ("fdlibm 5.3 C", "hypot(y, x)", cyx, ORANGE_F, ORANGE_S, BLUE_T, False),
            ("StrictMath", f"both orders, JDK {versions}", sm, BLUE_F, BLUE_S, BLUE_T, False)):
        f.rect(M, y, IW, 70, fill, stroke, dash=dash)
        f.text(M + 16, y + 30, label, size=17, weight=700)
        f.text(M + 16, y + 54, sub, size=15, mono=sub.startswith("hypot"), fill=GREY)
        f.add(f'<text x="{M + IW - 16:.1f}" y="{y + 44:.1f}" font-family="{esc(MONO)}" font-size="18" '
              f'text-anchor="end" style="font-variant-ligatures: none"><tspan fill="{INK}">0x{esc(val[:-1])}</tspan>'
              f'<tspan fill="{tcol}" font-weight="700">{esc(val[-1])}</tspan></text>')
        y += 80
    y += 6
    f.pill(M, y, IW, 42, ORANGE_S, "The C's answer depends on the order", size=17)
    y += 72
    y = f.caption(y, "The C picks the larger argument by its high 32 bits; here they are equal, so it "
                     "keeps the order given. Math.hypot gives StrictMath's bits in both orders too. "
                     "Data: strictmath-vs-libm/data/hypot-order.txt.")
    f.write(y)


def aggregate_rows():
    """aggregate-check.sql's rows: Photon's (CSV, as pasted) and Spark 4.3.0's on JDK 21 (TSV)."""
    cols = "f n sum_y xor_y min_y max_y cast_lossy xor_x sum_x".split()
    with open(DATA / "aggregate-photon-2026-11-24.csv") as fh:
        photon = {r["f"]: r for r in csv.DictReader(fh)}
    spark = {}
    for line in (DATA / "aggregate-spark-4.3.0-jdk21.tsv").read_text().splitlines():
        if line.strip():
            r = dict(zip(cols, line.split("\t")[:9]))
            spark[r["f"]] = r
    return photon, spark


def fig_aggregate():
    photon, spark = aggregate_rows()
    for f_, r in photon.items():  # the controls: inputs, casts and hashes agree on every row
        assert all(r[c] == spark[f_][c] for c in ("n", "cast_lossy", "xor_x", "sum_x")), f_
        assert r["cast_lossy"] == "0", f_
    differ = sorted(f_ for f_ in photon if photon[f_]["sum_y"] != spark[f_]["sum_y"])
    same = sorted(f_ for f_ in photon if f_ not in differ)
    assert same == ["sqrt"], same
    assert photon["sqrt"] == spark["sqrt"], "the post says the whole sqrt row agrees"
    # what How I checked's table says: Photon equals the glibc 2.43 prediction on every function
    # but cbrt (the prediction file has four more columns after the nine)
    cols = "f n sum_y xor_y min_y max_y cast_lossy xor_x sum_x".split()
    pred = {}
    for line in (DATA / "aggregate-glibc-2.43-predicted-jdk21.tsv").read_text().splitlines():
        if line.strip():
            v = line.split("\t")
            pred[v[0]] = dict(zip(cols, v[:9]))
            assert v[9] == "0", "x_mismatch"
    match = sorted(f_ for f_ in photon if photon[f_] == pred[f_])
    assert match == sorted(set(photon) - {"cbrt"}), match
    a, b = spark["exp"]["sum_y"], photon["exp"]["sum_y"]
    k = next(i for i in range(len(a)) if a[i] != b[i])
    f = Fig("aggregate", "A million rows, two totals",
            "The same query on Apache Spark 4.3.0 and on Databricks Photon: the exact sum of "
            "exp(x) over 1,000,000 inputs.",
            f"The exact DECIMAL sum of exp(x) over 1,000,000 inputs is {a} on Apache Spark 4.3.0 "
            f"and {b} on Databricks Photon. The sqrt control is {spark['sqrt']['sum_y']} on both. "
            f"Of the nine functions, every one but sqrt differs.")
    y = f.header()
    f.text(M, y + 8, "sum of exp(x), 1,000,000 rows", size=15, fill=GREY)
    y += 22
    for label, val, fill, stroke, col in (("Apache Spark 4.3.0", a, BLUE_F, BLUE_S, BLUE_T),
                                          ("Databricks Photon", b, ORANGE_F, ORANGE_S, ORANGE_T)):
        f.rect(M, y, IW, 76, fill, stroke)
        f.text(M + 16, y + 28, label, size=17, weight=700)
        f.add(f'<text x="{M + 16}" y="{y + 58}" font-family="{esc(MONO)}" font-size="18" '
              f'style="font-variant-ligatures: none"><tspan fill="{INK}">{esc(val[:k])}</tspan>'
              f'<tspan fill="{col}" font-weight="700">{esc(val[k:])}</tspan></text>')
        y += 86
    y += 6
    f.rect(M, y, IW, 76, GREY_F, "#9e9e9e", dash=True)
    f.text(M + 16, y + 28, "Control: sum of sqrt(x), on both", size=17, weight=700)
    f.text(M + 16, y + 58, spark["sqrt"]["sum_y"], size=18, mono=True)
    y += 98
    f.pill(M, y, IW, 42, ORANGE_S, "Every function but sqrt differs", size=17)
    y += 72
    y = f.caption(y, "The inputs, their sums and hashes agree on both engines, so the difference is "
                     "in the math. Photon: Databricks Free Edition, SQL warehouse, 24 November 2026. "
                     "Spark: my laptop, local mode, JDK 21. Data: strictmath-vs-libm/data/aggregate-*.")
    f.write(y)


def fig_speedup():
    f = Fig("speedup", "Speedup over StrictMath",
            "Per function, on the Intel Xeon Platinum 8573C (AVX-512) and the AMD EPYC 7763 (AVX2); "
            "to be drawn from the README's v0.3.0 speedup table at release.",
            "A placeholder: the speedup bars are made from the README's v0.3.0 table when it exists.")
    y = f.header()
    f.rect(M, y, IW, 130, GREY_F, "#9e9e9e", dash=True)
    f.text(W / 2, y + 60, "{{SPEEDUP_BARS}}", size=18, weight=700, mono=True, anchor="middle", fill=GREY)
    f.text(W / 2, y + 92, "per function and host, common and mixed data", size=15, anchor="middle", fill=GREY)
    y += 160
    f.write(y)


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    fig_exp1()
    fig_spark_emits()
    fig_one_call()
    counts = fig_who(full=True)
    fig_who(full=False)
    fig_how_often()
    fig_compare()
    fig_nan()
    fig_hypot()
    fig_boxing()
    fig_hypot_order()
    fig_aggregate()
    fig_speedup()
    print("who-returns-what G/S counts:", counts)
