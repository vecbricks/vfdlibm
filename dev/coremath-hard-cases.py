#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""hard-cases/hypot-coremath.txt and pow-coremath.txt, selected from CORE-MATH's published lists.

CORE-MATH (https://gitlab.inria.fr/core-math/core-math, MIT) publishes, per function, a list of
inputs that are hard for correct rounding: src/binary64/hypot/hypot.wc and
src/binary64/pow/pow.wc, pairs `x,y` as C floating-point literals with `#` comments. This script
takes some of their lines, by line number, and writes them in the pair format of the other
hard-cases files. Only the data files are read, as numeric facts; no CORE-MATH code is.

The two files are not committed. Fetch them at the commit below and run, from the repository:

  C=185b7dec645407a4ded6ee01d6af14a784a17514
  B=https://gitlab.inria.fr/core-math/core-math/-/raw/$C/src/binary64
  mkdir /tmp/cm   # an empty directory of their own, outside the repository
  curl -fsSLo /tmp/cm/hypot.wc $B/hypot/hypot.wc
  curl -fsSLo /tmp/cm/pow.wc $B/pow/pow.wc
  python3 -I dev/coremath-hard-cases.py /tmp/cm/hypot.wc /tmp/cm/pow.wc

The script refuses a file whose sha256 is not the one below, so a later version of the lists,
whose line numbers would differ, is never read by these numbers. Every selected line must be a
data line of two fields, each spelling exactly one double (hex or decimal, `inf` or `nan`): a
spelling that strtod would round is refused, as is a line of the filtered ranges without its
source's `# N` annotation, and the run stops with status 1 and writes nothing. Each double is
built from an integer mantissa and exponent, never by a rounding conversion, and Python's
float.fromhex only cross-checks it. A selected line whose pair an earlier selected line already
gave is not written again; the header lists each with the line it repeats. The output is
reproducible: a second run rewrites both files byte for byte.
"""

import hashlib
import re
import struct
import sys
import textwrap
from pathlib import Path

COMMIT = "185b7dec645407a4ded6ee01d6af14a784a17514"
REPO = "https://gitlab.inria.fr/core-math/core-math"
OUT = (Path(__file__).resolve().parent.parent
       / "src/test/resources/io/github/vecbricks/vfdlibm/hard-cases")
NOTICE_LINES = 20  # lines 1-20 of each .wc file: the project line and the MIT permission notice

# function: (path in the repository, line count, sha256, ranges);
# a range is (first, last, filter, why), filter None (every line), ("N", n) (the lines whose
# source annotation `# N` is at least n) or ("first per y", k) (the first k lines of each y).
LISTS = {
    "hypot": (
        "src/binary64/hypot/hypot.wc", 33276,
        "0ccf8be8b70eac41c87749928a1e2d8bf74b714812a4cb2dcdcd3e4f11b5dfec",
        [
            (27102, 27102, None,
             "the source's \"worst-case for RNDN with 104 identical bits after the round bit\":"
             " the exact result within 2^-105 ulp of a midpoint"),
            (27109, 27116, None,
             "the source's \"failures found by Eric Hennenhoefer using AI\": exact ties, x*x + y*y"
             " the square of a midpoint between two doubles"),
            (27104, 27104, None,
             "the source's \"worst-case for RNDZ with 104 identical bits after the round bit\":"
             " the exact result within 2^-105 ulp of a double"),
            (23778, 23778, None, None), (25500, 25500, None, None), (25524, 25524, None, None),
            (25681, 25681, None, None), (25706, 25706, None, None), (26084, 26084, None, None),
            (26307, 26307, None, None), (26429, 26429, None, None), (26558, 26558, None, None),
            (26596, 26596, None,
             "these ten lines, of the source's section \"worst cases with exp(y) = exp(x) - m"
             " with 1 <= m <= 27\" (lines 385-26644, 26260 pairs): every pair of it whose exact"
             " result lies within 2^-60 ulp of a midpoint, as measured when the list was"
             " evaluated (issue 144)"),
        ],
    ),
    "pow": (
        "src/binary64/pow/pow.wc", 1003380,
        "e3b6d6af7d5855b1196c6cf4238b3f99dc159f07be6de46a2d687514dffd8253",
        [
            (25, 104, None,
             "the source's first section, \"worst cases\" for pow: all 80 lines, 79 of them"
             " annotated `# N` or `# -m N`, N the identical bits after the round bit (from 44 to"
             " 153)"),
            (977527, 990843, ("N", 110),
             "Bernt Gombala's targeted search (wc_targeted_search_result_cases.txt): 13317"
             " near-midpoint cases of 100 to 117 identical bits; those of N >= 110"),
            (990862, 992304, ("N", 100),
             "Bernt Gombala's near-rational cases (wc_near_rational_cases.txt): 1443 cases of 90"
             " to 153 identical bits, near a midpoint or a double; those of N >= 100"),
            (31265, 31542, None,
             "the source's \"exact/midpoint cases from core-math-power/bacsel-results\", y = 3/2:"
             " all 278 lines, 139 exact results and 139 ties"),
            (992315, 999710, ("first per y", 3),
             "Bernt Gombala's normal midpoint cases (wc_norm_midpoint_cases.txt): 7396 exact ties"
             " over 41 values of y; the first 3 lines of each y"),
        ],
    ),
}

HEX = re.compile(r"0x([0-9a-f]*)(?:\.([0-9a-f]*))?(?:p([+-]?[0-9]+))?")
DEC = re.compile(r"[0-9]+(?:\.[0-9]*)?|\.[0-9]+")


def fail(message):
    raise SystemExit("coremath-hard-cases: " + message)


def double_bits(mant, exp2):
    """The raw bits of the positive double mant * 2^exp2, or None if it is not exactly one."""
    if mant == 0:
        return 0
    while mant % 2 == 0:
        mant //= 2
        exp2 += 1
    bl = mant.bit_length()
    top = exp2 + bl - 1
    if bl > 53 or top > 1023 or exp2 < -1074:
        return None
    if top >= -1022:
        significand = mant << (52 - (bl - 1))
        return (top + 1023) << 52 | (significand - (1 << 52))
    return mant << (exp2 + 1074)


def parse(token, where):
    """strtod's exact subset: the raw bits of the one double the token spells, else refuse."""
    s = token.strip().lower()
    sign = 0
    if s[:1] in "+-" and s:
        sign = 1 << 63 if s[0] == "-" else 0
        s = s[1:]
    special = {"inf": 0x7FF0000000000000, "infinity": 0x7FF0000000000000,
               "nan": 0x7FF8000000000000}
    if s in special:
        return sign | special[s]
    m = HEX.fullmatch(s)
    if m:
        ip, fp, ex = m.group(1), m.group(2) or "", m.group(3)
        if not ip and not fp:
            fail(f"{where}: not a number: {token!r}")
        mant = int(ip + fp, 16)
        exp2 = (int(ex) if ex else 0) - 4 * len(fp)
        bits = double_bits(mant, exp2)
        if bits is not None:
            # a cross-check only: fromhex rounds, so it never decides exactness
            if struct.unpack(">Q", struct.pack(">d", float.fromhex(s)))[0] != bits:
                fail(f"{where}: fromhex disagrees on {token!r}")
    elif DEC.fullmatch(s):
        ip, _, fp = s.partition(".")
        digits = int((ip + fp) or "0")
        five = 5 ** len(fp)
        if digits % five:
            fail(f"{where}: not exactly a double (strtod would round): {token!r}")
        bits = double_bits(digits // five, -len(fp))
    else:
        fail(f"{where}: not a number: {token!r}")
    if bits is None:
        fail(f"{where}: not exactly a double (strtod would round): {token!r}")
    return sign | bits


def read(path, rel, count, digest):
    data = Path(path).read_bytes()
    got = hashlib.sha256(data).hexdigest()
    if got != digest:
        fail(f"{path}: sha256 {got}, not {digest} ({rel} at {COMMIT})")
    lines = data.decode("utf-8").split("\n")
    if lines[-1] == "":
        lines.pop()
    if len(lines) != count:
        fail(f"{path}: {len(lines)} lines, not {count}")
    return lines


def select(name, lines, ranges):
    """The pairs of the ranges' lines, in order; the pairs written per range; the repeats.

    A line whose pair an earlier selected line already gave is not written again: its number
    and the earlier one's go to the repeats.
    """
    pairs = []
    first_line = {}
    per_range = []
    repeats = []
    for first, last, keep, _ in ranges:
        per_y = {}
        written = 0
        for number in range(first, last + 1):
            line = lines[number - 1]
            where = f"{name}.wc line {number}"
            body, hash_mark, note = line.partition("#")
            fields = body.split(",")
            if len(fields) != 2 or not body.strip():
                fail(f"{where}: not a data line of two fields: {line!r}")
            x, y = (parse(f, where) for f in fields)
            if keep is not None and keep[0] == "N":
                if not hash_mark or not note.strip().isdigit():
                    fail(f"{where}: no `# N` annotation: {line!r}")
                if int(note.strip()) < keep[1]:
                    continue
            if keep is not None and keep[0] == "first per y":
                per_y[y] = per_y.get(y, 0) + 1
                if per_y[y] > keep[1]:
                    continue
            if (x, y) in first_line:
                repeats.append((number, first_line[(x, y)]))
                continue
            first_line[(x, y)] = number
            pairs.append((x, y))
            written += 1
        per_range.append(written)
    return pairs, per_range, repeats


def ranges_text(ranges, per_range):
    text = []
    pending = []
    for (first, last, keep, why), written in zip(ranges, per_range):
        span = str(first) if first == last else f"{first}-{last}"
        if keep is not None:
            kind = "N >= %d" if keep[0] == "N" else "the first %d per y"
            span += f" ({kind % keep[1]})"
        pending.append((span, written))
        if why is not None:
            total = sum(w for _, w in pending)
            spans = ", ".join(span for span, _ in pending)
            text.append(f"  {spans}: {why}; {total} pair{'s' * (total != 1)} written.")
            pending = []
    return text


def repeats_text(repeats):
    if not repeats:
        return ["No pair of these lines repeats another."]
    listed = ", ".join(f"{n} ({m})" for n, m in repeats)
    return [f"A line whose pair an earlier line here already gave is not written again:"
            f" {len(repeats)} lines, each with the line it repeats: {listed}."]


def wrap(text, width=100):
    """Lines of `# ` comment, each at most width characters, continuation lines indented."""
    out = []
    for paragraph in text:
        if not paragraph:
            out.append("#")
            continue
        indent = "# " + " " * (len(paragraph) - len(paragraph.lstrip()))
        later = indent + ("  " if paragraph.startswith(" ") else "")
        # a code span such as `# N` is not broken across lines
        kept = re.sub(r"`[^`]*`", lambda m: m.group(0).replace(" ", "\0"), paragraph.strip())
        out.extend(line.replace("\0", " ") for line in textwrap.wrap(
            kept, width, initial_indent=indent, subsequent_indent=later,
            break_long_words=False, break_on_hyphens=False))
    return out


def header(name, rel, count, digest, notice, ranges, pairs, per_range, repeats):
    url = f"{REPO}/-/raw/{COMMIT}/{rel}"
    if name == "hypot":
        claim = [
            "Each pair is hard for correct rounding: hypot's exact result is a midpoint between two"
            " doubles, or lies within 2^-60 ulp of one, or within 2^-105 ulp of a double."
            " HardCasePairsTest rechecks each of these claims with BigInteger arithmetic of its"
            " own, by the pair's place in this file.",
        ]
        port = "e_hypot.c"
    else:
        claim = [
            "Each pair is hard for correct rounding: pow's exact result is a double or a midpoint"
            " between two doubles (the last two ranges, whose y is a multiple of 1/16, which"
            " HardCasePairsTest rechecks with BigInteger arithmetic of its own), or lies near"
            " one, by N identical bits after the round bit, N the source's `# N` annotation"
            " (the first three ranges). That annotation is the source's claim: the test checks"
            " those pairs' parse and count only, since rechecking N needs x**y to well over 100"
            " bits after the round bit.",
        ]
        port = "e_pow.c"
    text = [
        f"Pairs for {name} (a function of two arguments) from CORE-MATH's published list of its hard"
        " cases: one pair a line, `x y`, each as its raw bits in hex (Double.doubleToRawLongBits),"
        f" x first. Read by Inputs.hardCasePairs(\"{name}-coremath\"). Lines starting with # are"
        f" comments. hard-cases/{name}.txt is the self-computed file beside it.",
        "",
        f"Source: the CORE-MATH project, {REPO}, commit {COMMIT}, file {rel} ({count} lines,"
        f" sha256 {digest}), fetched from the URL below. Only input pairs are taken from it, as"
        " numeric facts. The file's notice, its lines 1-20, verbatim, follows the URL.",
        "",
        "  " + url,
    ]
    out = wrap(text)
    out.append("#")
    out.extend(notice)
    out.append("#")
    out.extend(wrap([
        "Written by dev/coremath-hard-cases.py (its docstring says how to fetch the list), which"
        " checks the file's sha256, takes these of its lines, by the source's line numbers, in this"
        " order, and refuses any line that does not spell exactly one double per argument:",
    ]))
    out.extend(wrap(ranges_text(ranges, per_range)))
    out.append("#")
    out.extend(wrap(repeats_text(repeats)))
    out.append("#")
    out.extend(wrap(claim + [
        "",
        f"{port} does not round correctly (its error is below one ulp), so these pairs are no"
        " harder for a port that follows fdlibm's operations than any other pair; their use is"
        " that a dropped correction term or a reassociated sum, a change far below one ulp, is"
        " most likely to flip the result exactly at them. They are compared with StrictMath like"
        " every hard case, and nothing else is claimed of StrictMath's value.",
        "",
        f"Written by the same run: {len(pairs)} pairs.",
    ]))
    out.append("# arity: 2")
    return out


def main(argv):
    if len(argv) != 3:
        fail("usage: python3 -I dev/coremath-hard-cases.py <hypot.wc> <pow.wc>")
    written = {}
    for name, path in zip(("hypot", "pow"), argv[1:]):
        rel, count, digest, ranges = LISTS[name]
        lines = read(path, rel, count, digest)
        notice = lines[:NOTICE_LINES]
        if not notice[0].startswith("# This file is part of the CORE-MATH project") or \
                notice[-1] != "# SOFTWARE.":
            fail(f"{path}: lines 1-{NOTICE_LINES} are not the notice")
        pairs, per_range, repeats = select(name, lines, ranges)
        body = header(name, rel, count, digest, notice, ranges, pairs, per_range, repeats)
        body += [f"0x{x:016x} 0x{y:016x}" for x, y in pairs]
        written[name] = "\n".join(body) + "\n"
    for name, text in written.items():
        (OUT / f"{name}-coremath.txt").write_text(text, encoding="ascii")
        print(f"coremath-hard-cases: {name}-coremath.txt: "
              f"{sum(1 for l in text.splitlines() if l.startswith('0x'))} pairs", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv)
