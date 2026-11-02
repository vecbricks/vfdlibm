#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Compare JMH results of a base and a head, run in alternating rounds by bench/pair.sh.

    bench/pair.py compare --base B1.json B2.json ... --head H1.json ... [--threshold 10]
    bench/pair.py settled --base B1.json ... --head H1.json ... [--within 3]
    bench/pair.py --selftest

Adapted from vecbricks/varka's dev/varka_bench_pair.py, the script of the same role, reading
JMH's JSON result (-rf json) instead of Spark's benchmark tables. A row is a benchmark method
with its parameters and JMH mode (ExpBenchmark.finish256 inputs=common [avgt]); each file is one
round of one side.

`compare` prints each row's change in percent twice, + faster and - slower: from each side's
median score over its rounds (the typical run), and from each side's best, its fastest
measured iteration in any round (what the code does when nothing disturbs it; AGENTS.md asks
a ratio under about 1.3x to be compared by minimums). A row is slower when both changes are at
most -threshold, faster when both are at least +threshold; a row that only the median moves past
the threshold is marked as the median's alone, which is how drift shared by a whole round
reads. It prints the head's median gc.alloc.rate.norm beside, since an arm that starts to box
shows there first. Exit status 1 if any row is slower, which is the gate. Exit status 2, an
error and never a verdict, takes precedence: a row on one side only (a renamed benchmark or a
changed @Param must not read as "0 slower"), a row whose unit differs between the sides (ns/op
against us/op is not a 1000x change), a NaN or non-positive score, an unreadable file, or no row
on both sides. `settled` prints "settled" when every row on both sides has its median change
within --within percent, when more rounds would not change the verdict, and "open" otherwise;
it ignores one-sided rows, since more rounds cannot mend those (compare reports them), and on
an error it prints nothing and exits 2, which bench/pair.sh reads as not settled.
"""

import argparse
import json
import statistics
import math
import sys
import traceback

ALLOC = "gc.alloc.rate.norm"


class PairError(Exception):
    """A comparison that cannot be made: exit status 2, never a verdict."""


def row_key(r):
    """The benchmark, its parameters and its mode: two modes of one method are two rows."""
    params = r.get("params") or {}
    p = " ".join(f"{k}={v}" for k, v in sorted(params.items()))
    name = ".".join(r["benchmark"].split(".")[-2:])
    return f"{name} {p} [{r.get('mode')}]" if p else f"{name} [{r.get('mode')}]"


def num(x):
    """A JMH number. JMH writes a NaN or an infinity as a quoted string ("NaN"), which
    json.load leaves a string; anything else that is not a number is an error."""
    if isinstance(x, str) and x in ("NaN", "Infinity", "-Infinity"):
        return float(x)
    if isinstance(x, (int, float)) and not isinstance(x, bool):
        return float(x)
    raise PairError(f"not a number: {x!r}")


def parse(rows, path="<rows>"):
    """{row: (score, best iteration, alloc B/op or None, higher_is_better, unit)} of one round,
    from JMH's JSON result. A NaN alloc is read as none: the profiler did not measure it."""
    out = {}
    for r in rows:
        k = row_key(r)
        if k in out:
            raise PairError(f"{path}: row {k} appears twice")
        m = r["primaryMetric"]
        higher = r.get("mode") == "thrpt"
        raw = [num(x) for fork in m.get("rawData", []) for x in fork] or [num(m["score"])]
        best = max(raw) if higher else min(raw)
        alloc = (r.get("secondaryMetrics") or {}).get(ALLOC, {}).get("score")
        alloc = None if alloc is None or math.isnan(num(alloc)) else num(alloc)
        out[k] = (num(m["score"]), best, alloc, higher, m["scoreUnit"])
    return out


def load(path):
    with open(path, encoding="utf-8") as f:
        return parse(json.load(f), path)


def change(base, head, higher):
    """Percent change of head against base, + faster. For a time (lower is better) faster means
    a higher rate, the reciprocal: base / head - 1."""
    return (head / base - 1.0) * 100.0 if higher else (base / head - 1.0) * 100.0


def one_sided(base_runs, head_runs):
    """[(row, side)] for each row that some round of one side has and no round of the other."""
    b = {k for r in base_runs for k in r}
    h = {k for r in head_runs for k in r}
    return sorted([(k, "base") for k in b - h] + [(k, "head") for k in h - b])


def compare(base_runs, head_runs):
    """{row: (median change, best change, base median, head median, head alloc, unit)} of the
    rows on both sides. Raises PairError on a unit that differs or a score that is not a
    positive number."""
    rows = {}
    keys = dict.fromkeys(k for r in base_runs for k in r)
    for k in keys:
        b = [r[k] for r in base_runs if k in r]
        h = [r[k] for r in head_runs if k in r]
        if not b or not h:
            continue
        units = sorted({x[4] for x in b + h})
        if len(units) > 1:
            raise PairError(f"row {k}: the unit differs between rounds or sides "
                            f"({', '.join(units)}); the scores are not comparable")
        bad = [x for x in b + h if not (x[0] > 0 and x[1] > 0 and math.isfinite(x[0] + x[1]))]
        if bad:
            raise PairError(f"row {k}: a score is not a positive number ({bad[0][0]}, best "
                            f"{bad[0][1]})")
        higher, unit = b[0][3], b[0][4]
        pick = max if higher else min
        bm = statistics.median(x[0] for x in b)
        hm = statistics.median(x[0] for x in h)
        bb = pick(x[1] for x in b)
        hb = pick(x[1] for x in h)
        allocs = [x[2] for x in h if x[2] is not None]
        alloc = statistics.median(allocs) if allocs else None
        rows[k] = (change(bm, hm, higher), change(bb, hb, higher), bm, hm, alloc, unit)
    return rows


def verdicts(rows, threshold):
    slower = [k for k, v in rows.items() if v[0] <= -threshold and v[1] <= -threshold]
    faster = [k for k, v in rows.items() if v[0] >= threshold and v[1] >= threshold]
    return slower, faster


def settled(rows, within):
    return bool(rows) and all(abs(v[0]) <= within for v in rows.values())


def report(rows, threshold, nbase, nhead, out):
    slower, faster = verdicts(rows, threshold)
    out.write(" median    best      base med      head med  unit    head B/op  row\n")
    for k, v in sorted(rows.items(), key=lambda kv: kv[1][0]):
        if k in slower:
            mark = "  <-- slower"
        elif k in faster:
            mark = "  <-- faster"
        elif abs(v[0]) >= threshold:
            mark = "  <-- the median's alone"
        else:
            mark = ""
        alloc = "-" if v[4] is None else f"{v[4]:.3f}"
        out.write(f"{v[0]:+6.1f}% {v[1]:+6.1f}%  {v[2]:12.1f}  {v[3]:12.1f}  {v[5]:6s}"
                  f"  {alloc:>9s}  {k}{mark}\n")
    widest = max(abs(v[0]) for v in rows.values())
    widest_best = max(abs(v[1]) for v in rows.values())
    out.write(f"\n{len(rows)} rows, {len(slower)} slower and {len(faster)} faster by "
              f"{threshold:g}% or more by both the median and the best, of {nbase} base and "
              f"{nhead} head rounds; the widest change either way is {widest:.1f}% by median "
              f"and {widest_best:.1f}% by best\n")
    return slower


def selftest():
    def run(score, best=None):
        return {"E.a": (score, best or score, 0.0, False, "ns/op"),
                "E.b": (50.0, 50.0, None, False, "ns/op")}

    base = [run(100.0), run(110.0), run(90.0)]
    rows = compare(base, [run(99.0)] * 3)
    assert abs(rows["E.a"][0] - (100 / 99 - 1) * 100) < 1e-9 and settled(rows, 3.0), rows
    # Twice the time is half the rate: -50%, slower by both.
    assert verdicts(compare(base, [run(200.0)] * 3), 10.0) == (["E.a"], [])
    # One disturbed head round does not move the median.
    assert verdicts(compare([run(100.0)] * 3, [run(500.0), run(100.0), run(100.0)]), 10.0) == (
        [], [])
    # Drift shared by a whole side moves the median and not the best.
    rows = compare([run(100.0)] * 3, [run(120.0, 100.0), run(120.0, 100.0), run(120.0, 100.0)])
    assert rows["E.a"][0] < -10.0 and abs(rows["E.a"][1]) < 1e-9
    assert verdicts(rows, 10.0) == ([], [])
    # Throughput: higher is better.
    t = lambda s: {"T.x": (s, s, None, True, "ops/s")}  # noqa: E731
    assert abs(compare([t(100.0)], [t(150.0)])["T.x"][0] - 50.0) < 1e-9
    # A row on one side only is not compared, and is reported as one-sided.
    x, y = {"X.x": (1.0, 1.0, None, False, "ns/op")}, {"Y.y": (1.0, 1.0, None, False, "ns/op")}
    assert compare([x], [y]) == {}
    assert one_sided([x], [x, y]) == [("Y.y", "head")], one_sided([x], [x, y])

    # From JMH's JSON: the mode is in the key, so two modes of one method do not collide.
    def jmh(score, unit="ns/op", mode="avgt", params=None, alloc=1.0):
        r = {"benchmark": "io.github.vecbricks.vfdlibm.bench.E.a", "mode": mode,
             "primaryMetric": {"score": score, "scoreUnit": unit, "rawData": [[score, score]]},
             "secondaryMetrics": {ALLOC: {"score": alloc}}}
        if params:
            r["params"] = params
        return r

    two = parse([jmh(100.0), jmh(100.0, "ops/us", "thrpt")])
    assert sorted(two) == ["E.a [avgt]", "E.a [thrpt]"], two
    assert "E.a n=8 [avgt]" in parse([jmh(1.0, params={"n": "8"})])
    rows = compare([two], [parse([jmh(200.0), jmh(100.0, "ops/us", "thrpt")])])
    assert verdicts(rows, 10.0) == (["E.a [avgt]"], []), rows

    def raises(f):
        try:
            f()
        except PairError:
            return True
        return False

    # A unit that differs is an error, not a 49900% change.
    assert raises(lambda: compare([parse([jmh(100.0)])], [parse([jmh(0.2, "us/op")])]))
    # JMH's quoted NaN: a NaN score is an error; a NaN alloc is read as none.
    assert raises(lambda: compare([parse([jmh(100.0)])], [parse([jmh("NaN")])]))
    assert compare([parse([jmh(100.0)])], [parse([jmh(200.0, alloc="NaN")])])["E.a [avgt]"][4] \
        is None
    # A zero score would give no change at all; it is an error too.
    assert raises(lambda: compare([parse([jmh(0.0)])], [parse([jmh(1.0)])]))
    assert raises(lambda: parse([jmh("fast")]))
    print("pair.py: selftest passed")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("cmd", choices=["compare", "settled"])
    ap.add_argument("--base", nargs="+", required=True)
    ap.add_argument("--head", nargs="+", required=True)
    ap.add_argument("--threshold", type=float, default=10.0)
    ap.add_argument("--within", type=float, default=3.0)
    args = ap.parse_args()
    # Any failure here is an error, exit status 2: never 1, which says "slower".
    try:
        sys.exit(run(args))
    except PairError as e:
        print(f"pair.py: error: {e}", file=sys.stderr)
    except Exception:  # noqa: BLE001 - a crash must not read as a verdict
        traceback.print_exc()
        print("pair.py: error: the comparison failed", file=sys.stderr)
    sys.exit(2)


def run(args):
    base_runs = [load(p) for p in args.base]
    head_runs = [load(p) for p in args.head]
    rows = compare(base_runs, head_runs)
    if args.cmd == "settled":
        print("settled" if settled(rows, args.within) else "open")
        return 0
    if not rows:
        print("pair.py: error: no row is on both sides: nothing was compared", file=sys.stderr)
        return 2
    slower = report(rows, args.threshold, len(args.base), len(args.head), sys.stdout)
    lone = one_sided(base_runs, head_runs)
    if lone:
        print(f"pair.py: error: {len(lone)} rows are on one side only and were not compared "
              "(a renamed benchmark or a changed @Param?):", file=sys.stderr)
        for k, side in lone:
            print(f"  {side} only: {k}", file=sys.stderr)
        return 2
    return 1 if slower else 0


if __name__ == "__main__":
    main()
