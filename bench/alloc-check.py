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
THRESHOLD bytes per op or more. It reads the numbers from JMH's JSON result.

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


def run_jmh(mvn, include, jvm_args):
    append = " ".join(a for a in (INLINE, jvm_args) if a)
    jmh_args = f"{include} {JMH_OPTIONS} -rf json -rff {RESULT} -jvmArgsAppend \"{append}\""
    command = [mvn, "-B", "--no-transfer-progress", "-q", "-Pjmh", "test-compile", "exec:exec",
               f"-Djmh.args={jmh_args}"]
    print("Running:", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def check(path, threshold):
    with open(path, encoding="utf-8") as f:
        rows = json.load(f)
    if not rows:
        print("FAIL: JMH ran no benchmark")
        return False
    ok = True
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
    parser.add_argument("--no-run", action="store_true", help=f"only check an existing {RESULT}")
    args = parser.parse_args()
    if not args.no_run:
        run_jmh(args.mvn, args.include, args.jvm_args)
    if not check(RESULT, args.threshold):
        print(f"A row allocated {args.threshold} B/op or more: a vector body boxed, or, if"
              " only its first iteration did, C2 had not finished. See SKILLS.md.")
        sys.exit(1)
    print(f"No vector body allocates {args.threshold} B/op or more.")


if __name__ == "__main__":
    main()
