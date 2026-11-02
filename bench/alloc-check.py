#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Fail when a port's vector body allocates, which means it boxed.

A boxed body computes the right bits, so the tests pass on it; only the allocation rate shows
that the Vector API fell back to Java (SKILLS.md, "Allocation is the verdict"). This script runs
every JMH benchmark method named *256 or *Preferred, on common inputs, under -prof gc with the
body inlined as Varka emits it, and fails if any measured iteration of any row allocates
THRESHOLD bytes per op or more. It reads the numbers from JMH's JSON result, and fails too when
a benchmark that JMH lists for the regex threw or has no row there.

Rows left out: 128 bits, since on x86 C2 lowers no mask at two 64-bit lanes and every masked
128-bit body boxes (SKILLS.md); 512 bits, which runs in software on an AVX2 host (the preferred
arm covers 512 bits on a host that has it); and mixed inputs, where the finish arm's per-lane
fallback allocates its round trip arrays by design.

Usage, from the repository root:
    bench/alloc-check.py                                  # the host's own flags
    bench/alloc-check.py --jvm-args "-XX:UseAVX=2 -XX:MaxVectorSize=32"   # as an AVX2 host
    bench/alloc-check.py --include 'ExpBenchmark.finish128'               # a row that boxes
"""

import argparse
import json
import os
import re
import subprocess
import sys

# Bytes per op, one op being 1024 doubles. One boxed 256-bit DoubleVector is an object holding
# a double[4], about 64 bytes with headers, so a row that boxes a single vector per op reads
# some 64 B/op, and a boxed body kilobytes (exp's D2L path read 98304). An unboxed row reads
# 0.01 to 0.03 B/op, JMH's own allocation spread over the ops. 1 B/op sits between the two.
THRESHOLD = 1.0

INCLUDE = r".*Benchmark\.\w+(256|Preferred)$"
INLINE = "-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"
RESULT = "target/alloc-check.json"
# One fork; warmup long enough for C2 to finish the body before the first measured iteration,
# which every iteration being checked (not only the mean) enforces.
JMH_OPTIONS = "-f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc -p inputs=common"


def mvn_jmh(mvn, jmh_args, capture=False):
    """Runs JMH through the jmh profile's exec:exec, as CI and the benchmark workflow do."""
    command = [mvn, "-B", "--no-transfer-progress", "-q", "-Pjmh", "test-compile", "exec:exec",
               f"-Djmh.args={jmh_args}"]
    print("Running:", " ".join(command), flush=True)
    return subprocess.run(command, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None)


def list_benchmarks(mvn, include):
    """The benchmarks the include regex matches, as JMH's -l lists them and its JSON names them.

    JMH reads the regex as a search, not a full match, so the filter here does the same."""
    out = mvn_jmh(mvn, f"-l {include}", capture=True).stdout
    return sorted(line.strip() for line in out.splitlines()
                  if re.fullmatch(r"[\w.$]+", line.strip()) and re.search(include, line.strip()))


def run_jmh(mvn, include, jvm_args):
    append = " ".join(a for a in (INLINE, jvm_args) if a)
    # -foe true: a benchmark that throws fails the run. Without it JMH prints <failure>, leaves
    # the row out of the JSON and exits 0, so a broken row would pass unseen.
    jmh_args = (f"{include} {JMH_OPTIONS} -foe true -rf json -rff {RESULT}"
                f" -jvmArgsAppend \"{append}\"")
    mvn_jmh(mvn, jmh_args)


def check(path, threshold, expected=None):
    """Checks every row of the JSON at path; with expected, also that each of those benchmarks
    has its inputs=common row."""
    if not os.path.exists(path):
        print(f"FAIL: JMH wrote no {path}")
        return False
    with open(path, encoding="utf-8") as f:
        rows = json.load(f)
    if not rows:
        print("FAIL: JMH ran no benchmark")
        return False
    ok = True
    if expected is not None:
        got = {(row["benchmark"], row.get("params", {}).get("inputs")) for row in rows}
        for name in expected:
            if (name, "common") not in got:
                print(f"FAIL  {name} [inputs=common]: no row in {path}")
                ok = False
    for row in rows:
        name = row["benchmark"].rsplit(".", 2)[-2:]
        params = ",".join(f"{k}={v}" for k, v in sorted(row.get("params", {}).items()))
        label = ".".join(name) + (f" [{params}]" if params else "")
        # JMH names the metric with a leading middle dot; match on the ASCII suffix.
        norms = [m for k, m in row.get("secondaryMetrics", {}).items()
                 if k.endswith("gc.alloc.rate.norm")]
        if not norms:
            print(f"FAIL  {label}: no gc.alloc.rate.norm (was -prof gc on?)")
            ok = False
            continue
        iterations = [v for fork in norms[0]["rawData"] for v in fork]
        worst = max(iterations)
        verdict = "ok  " if worst < threshold else "FAIL"
        ok = ok and worst < threshold
        print(f"{verdict}  {label}: max {worst:.3f} B/op over {len(iterations)} iterations")
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mvn", default="mvn", help="the Maven to run (default: mvn)")
    parser.add_argument("--jvm-args", default="", help="extra options for the benchmark JVMs")
    parser.add_argument("--include", default=INCLUDE, help="JMH benchmark regex")
    parser.add_argument("--threshold", type=float, default=THRESHOLD, help="bytes per op")
    parser.add_argument("--no-run", action="store_true",
                        help=f"only check the rows of an existing {RESULT}")
    args = parser.parse_args()
    expected = None
    if not args.no_run:
        # A result left by an earlier run must not stand in for this one.
        if os.path.exists(RESULT):
            os.remove(RESULT)
        try:
            expected = list_benchmarks(args.mvn, args.include)
            if not expected:
                print(f"FAIL: no benchmark matches {args.include}")
                sys.exit(1)
            run_jmh(args.mvn, args.include, args.jvm_args)
        except subprocess.CalledProcessError as e:
            print(f"FAIL: Maven exited with {e.returncode}; a benchmark threw or the build"
                  " failed, see its output above")
            sys.exit(1)
    if not check(RESULT, args.threshold, expected):
        print(f"A row is missing, or allocated {args.threshold} B/op or more: a vector body"
              " boxed, or, if only its first iteration did, C2 had not finished. See SKILLS.md.")
        sys.exit(1)
    print(f"No vector body allocates {args.threshold} B/op or more.")


if __name__ == "__main__":
    main()
