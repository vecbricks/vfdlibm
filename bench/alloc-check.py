#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
r"""Fail when a port's vector body allocates, which means it boxed.

A boxed body computes the right bits, so the tests pass on it; only the allocation rate shows
that the Vector API fell back to Java (SKILLS.md, "Allocation is the verdict"). This script runs
every JMH benchmark method named *256, *Preferred or *Max of a class named *Benchmark, on common and
mixed inputs, under -prof gc with the body inlined as Varka emits it, and fails if any measured
iteration of any row allocates THRESHOLD bytes per op or more. It reads the numbers from JMH's JSON
result, and fails too when a benchmark that JMH lists for the regex threw or has no row there for an
inputs value it runs.

Rows left out: 128 bits, since on x86 C2 lowers no mask at two 64-bit lanes and every masked
128-bit body boxes (SKILLS.md); and 512 bits, which runs in software on an AVX2 host (the
preferred arm covers 512 bits on a host that has it). Mixed inputs are checked because they are
the ones that run the rare lanes' paths, which common inputs never reach. The *Max rows, at
SPECIES_MAX, are checked with the *256 and *Preferred ones. The *Boxed* rows are left out by
INCLUDE's regex (see the comment there), and so is ExpKindsBench, by its class name: its rows
call the bodies ExpBenchmark's rows call, and its other inputs values are checked by naming it,
as below.

With --why it runs nothing new through the check: it reads the last run's result and runs the
rows that failed once more (WHY_ROWS of them at most, one of each benchmark method first),
under the JVM options the result records for each row, with C2 printing what it did not
intrinsify and what it refused to inline (SKILLS.md, "Ask C2 what it refused"), each distinct
line with its count. A rerun measures one iteration, not three, and C2 compiles alone with the
inlining limit it has in tiered mode (see WHY); nothing else differs from the failing run.

Usage, from the repository root:
    bench/alloc-check.py                                  # the host's own flags
    bench/alloc-check.py --jvm-args="-XX:UseAVX=2 -XX:MaxVectorSize=32"   # as an AVX2 host
    bench/alloc-check.py --jvm-args=-XX:MaxVectorSize=16  # one option needs the = form
    bench/alloc-check.py --preferred-only                 # where 256 bits is not the hardware's
    bench/alloc-check.py --include 'ExpBenchmark.finish128'               # a row that boxes
    bench/alloc-check.py --include 'ExpKindsBench\.\w+(256|Preferred)$' \
        --inputs common,none,nan,overflow,underflow,subnormal,mixed       # other inputs values
    bench/alloc-check.py --why                            # ask C2 about the rows that failed

--inputs is for a class whose setUp throws on a value it does not know, as ExpKindsBench's
does. The <Function>Benchmark classes read every value but mixed as common, so a row of theirs
named for another value would pass on common data.
"""

import argparse
import collections
import json
import os
import re
import signal
import subprocess
import sys

# Bytes per op, one op being 1024 doubles. One boxed 256-bit DoubleVector is an object holding
# a double[4], about 64 bytes with headers, so a row that boxes a single vector per op reads
# some 64 B/op, and a boxed body kilobytes (exp's D2L path read 98304); a boxed 128-bit vector
# is smaller by its two doubles only, and one op holds twice as many. An unboxed row reads 0.01
# to 0.03 B/op on the x86 hosts and 0.04 to 0.10 on GitHub's Arm runner
# (bench/results/alloc-arm-2026-11-20.txt), JMH's own allocation spread over the ops. 1 B/op
# sits between the two. The unboxed reading is a rate times the time of an op, so it grows with
# a slow row: on Arm, 0.103 B/op at 14.7 us per op, and 1 B/op would be reached at about
# 143 us per op with no vector boxed. A row that slow wants the threshold read again.
THRESHOLD = 1.0

# *Max is in: the finish arm writes out the first eight lanes with constant lane indices, which
# C2 intrinsifies at SPECIES_MAX too, so finishMax allocates nothing on mixed data
# (bench/results/max-finish-alloc-free-laptop-2026-11-21.txt). Before that change the finish
# walked the rare mask's set bits, a variable lane index that C2 intrinsifies for the fixed
# shapes only, and boxed at SPECIES_MAX (bench/results/max-finish-laptop-2026-11-20.txt).
#
# Not *Boxed*: ExpBenchmark.finishPairBoxed256 and finishPairBoxedPreferred inline two
# vfdlibm bodies in one loop, which exceeds C2's NodeCountInliningCutoff (a develop flag, 18000
# nodes, fixed in product JVMs). Past it C2 refuses every callee larger than MaxTrivialSize
# (6 bytes) that is not @ForceInline, and a refused call boxes its vectors, so those rows
# allocate on every vector (SKILLS.md). They record that; they cannot pass.
#
# PREFERRED is the same with *256 left out too, for a host whose vectors are narrower than 256
# bits (GitHub's Arm runners, 128 bits): a *256 row runs in the Java fallback there, as 512
# bits does on an AVX2 host. Both come from one pattern, so an exclusion is written once.
ROWS = r".*Benchmark\.(?!\w*Boxed)\w+(%s)$"
INCLUDE = ROWS % "256|Preferred|Max"
PREFERRED = ROWS % "Preferred"
INLINE = "-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"
RESULT = "target/alloc-check.json"
# What --why adds to a failing row's JVM, and the lines it keeps. C2 alone compiles
# (-TieredCompilation): PrintInlining prints for every tier, and C1's refusals box nothing and
# would outnumber C2's. Turning the tiers off also lowers InlineSmallCode, a limit of C2's own
# inlining, so why() reads the value the JVM has with the tiers on and passes it back; with it
# the rerun's C2 inlines as the failing run's did, as far as this script knows. PrintIntrinsics'
# lines are kept whole, each cut at its "**", since a line of C2's can land in the middle of
# one of JMH's. Of PrintInlining's refusals, each cut at its "@ <bci>", two lists: those naming
# the library, the node count or a refused intrinsic, which are the ones that box a port's
# vectors and are printed in full; and the others naming the Vector API, up to WHY_LINES. A
# line carries every attempt at its call site, so one that repeats itself is cut at WHY_WIDTH.
WHY = ("-XX:-TieredCompilation"
       " -XX:CompileCommand=PrintIntrinsics,io.github.vecbricks.vfdlibm.*::*"
       " -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining")
NOT_INTRINSIFIED = re.compile(r"\*\* .*")
REFUSED = re.compile(r"@ \d+ .*failed to inline.*")
REFUSED_FIRST = re.compile(r"vfdlibm|NodeCountInliningCutoff|failed to inline \(intrinsic\)")
REFUSED_API = re.compile(r"jdk\.incubator\.vector|VectorSupport")
WHY_LINES = 40
WHY_WIDTH = 200
# The rows --why reruns at most, and the seconds one rerun may take: when every row fails, a
# few say why (one per benchmark method first), and a hung fork must not hold a CI job.
WHY_ROWS = 8
WHY_TIMEOUT = 600
# The inputs values the run covers unless --inputs names others; check() requires a row for
# each of them.
INPUTS = ("common", "mixed")
# One fork; warmup long enough for C2 to finish the body before the first measured iteration,
# which every iteration being checked (not only the mean) enforces.
JMH_OPTIONS = "-f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc"


def mvn_jmh(mvn, jmh_args, capture=False, check=True, timeout=None):
    """Runs JMH through the jmh profile's exec:exec, as CI and the benchmark workflow do.

    capture is False (the output goes to the terminal), True (stdout is returned) or "all"
    (stdout and stderr are returned as one text). The child gets no stdin: exec:exec would pass
    this script's on to JMH. Captured bytes that do not decode are replaced, not raised on:
    with "all", a line of the JVM's can cut one of JMH's in two. Maven runs in a process group
    of its own, and past timeout seconds the whole group is killed, JMH and its fork with it,
    before subprocess.TimeoutExpired is raised."""
    command = [mvn, "-B", "--no-transfer-progress", "-q", "-Pjmh", "test-compile", "exec:exec",
               f"-Djmh.args={jmh_args}"]
    print("Running:", " ".join(command), flush=True)
    try:
        process = subprocess.Popen(command, text=True, errors="replace", start_new_session=True,
                                   stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE if capture else None,
                                   stderr=subprocess.STDOUT if capture == "all" else None)
    except OSError as e:
        sys.exit(f"FAIL: cannot run {mvn} ({e.strerror}); name a Maven with --mvn")
    try:
        out, _ = process.communicate(timeout=timeout)
    except BaseException:
        # A timeout, or this script interrupted: leave no JMH behind.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        raise
    if check and process.returncode != 0:
        raise subprocess.CalledProcessError(process.returncode, command, output=out)
    return subprocess.CompletedProcess(command, process.returncode, stdout=out)


def list_benchmarks(mvn, include):
    """The benchmarks the include regex matches, as JMH's -l lists them and its JSON names them.

    JMH reads the regex as a search, not a full match, so the filter here does the same."""
    out = mvn_jmh(mvn, f"-l {include}", capture=True).stdout
    return sorted(line.strip() for line in out.splitlines()
                  if re.fullmatch(r"[\w.$]+", line.strip()) and re.search(include, line.strip()))


def run_jmh(mvn, include, jvm_args, inputs=INPUTS):
    append = " ".join(a for a in (INLINE, jvm_args) if a)
    # -foe true: a benchmark that throws fails the run. Without it JMH prints <failure>, leaves
    # the row out of the JSON and exits 0, so a broken row would pass unseen.
    jmh_args = (f"{include} {JMH_OPTIONS} -p inputs={','.join(inputs)}"
                f" -foe true -rf json -rff {RESULT}"
                f" -jvmArgsAppend \"{append}\"")
    mvn_jmh(mvn, jmh_args)


def alloc_iterations(row):
    """Every measured iteration's gc.alloc.rate.norm of a JSON row, or None if it has none."""
    # JMH names the metric with a leading middle dot; match on the ASCII suffix.
    norms = [m for k, m in row.get("secondaryMetrics", {}).items()
             if k.endswith("gc.alloc.rate.norm")]
    return [v for fork in norms[0]["rawData"] for v in fork] if norms else None


def failing(path, threshold):
    """The JSON rows at path that check() fails for their allocation."""
    with open(path, encoding="utf-8") as f:
        rows = json.load(f)
    found = []
    for row in rows:
        iterations = alloc_iterations(row)
        if iterations and max(iterations) >= threshold:
            found.append(row)
    return found


def counted(lines, limit=None):
    """Prints each distinct line with its count, the most frequent first, limit at most."""
    counts = collections.Counter(" ".join(line.split()) for line in lines)
    for line, count in counts.most_common(limit):
        print(f"{count:6d}  {line}")
    if limit is not None and len(counts) > limit:
        print(f"        and {len(counts) - limit} more distinct lines")
    if not counts:
        print("        none")


def tiered_inline_small_code(jvm, jvm_args):
    """The -XX:InlineSmallCode=<n> the JVM has under jvm_args with the tiers on, or ""."""
    try:
        out = subprocess.run([jvm] + jvm_args + ["-XX:+PrintFlagsFinal", "-version"],
                             check=True, text=True, errors="replace", timeout=60,
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL).stdout
    except (OSError, subprocess.SubprocessError):
        return ""
    match = re.search(r"\bInlineSmallCode\s*=\s*(\d+)", out)
    return f"-XX:InlineSmallCode={match.group(1)}" if match else ""


def why(mvn, path, threshold):
    """Runs failing rows of path once more and prints C2's account of each.

    A row runs under the JVM options the result records for it, the ones it failed under, not
    under this command's: the same row can box under one set of flags and not under another."""
    if not os.path.exists(path):
        print(f"No {path}: the check did not get as far as a result, see its own output.")
        return
    rows = failing(path, threshold)
    if not rows:
        print(f"No row of {path} allocated {threshold} B/op or more; if the check failed, a"
              " row was missing or had no gc.alloc.rate.norm, see its own output.")
    if len(rows) > WHY_ROWS:
        # One row of each benchmark method first, its last (mixed data, which runs the rare
        # paths too), so that eight slots reach as many bodies as they can.
        last = {row["benchmark"]: row for row in rows}
        first = list(last.values())
        rows = first + [row for row in rows if all(row is not chosen for chosen in first)]
        print(f"{len(rows)} rows failed; asking about {WHY_ROWS} of them, one of each"
              " benchmark method first.")
    for row in rows[:WHY_ROWS]:
        name, inputs = row["benchmark"], row.get("params", {}).get("inputs")
        print(f"\n== {name} [inputs={inputs}]", flush=True)
        param = f" -p inputs={inputs}" if inputs else ""
        jvm_args = row.get("jvmArgs", [])
        small_code = tiered_inline_small_code(row.get("jvm", "java"), jvm_args)
        if not small_code:
            print("Could not read InlineSmallCode with the tiers on; this run's C2 has the"
                  " lower limit of -TieredCompilation, so a refusal as \"already compiled into"
                  " a big method\" below may be this run's alone.")
        append = " ".join(jvm_args + [WHY, small_code]).strip()
        try:
            run = mvn_jmh(mvn, f"{re.escape(name)}${param} -f 1 -wi 3 -w 1s -i 1 -r 1s -prof gc"
                               f" -jvmArgsAppend \"{append}\"", capture="all", check=False,
                          timeout=WHY_TIMEOUT)
        except subprocess.TimeoutExpired:
            print(f"The run did not end within {WHY_TIMEOUT} seconds and was stopped.")
            continue
        lines = run.stdout.splitlines()
        alloc = [line for line in lines if "gc.alloc.rate.norm" in line]
        if run.returncode != 0 or not alloc:
            # Empty sections below would read as "C2 refused nothing"; show what happened.
            print(f"The run did not measure the row (Maven exited with {run.returncode});"
                  " the end of its output:")
            print("\n".join(lines[-40:]))
            continue
        print(alloc[-1])
        # JMH prints a score under 0.001 as an approximation, with its sign for "about"; such
        # a score is under every threshold in use. Any other text that is no number is not
        # judged, and no notice is printed for it.
        try:
            boxed = float(alloc[-1].split()[-2]) >= threshold
        except (IndexError, ValueError):
            boxed = "\u2248" not in alloc[-1]
        if not boxed:
            print(f"This run read under {threshold} B/op: the row did not box this time, so"
                  " the lines below do not explain the failure. If only the first iteration"
                  " of the failing run allocated, C2 was late there (SKILLS.md).")
        print("PrintIntrinsics, each distinct line with its count:")
        counted(m.group(0) for m in map(NOT_INTRINSIFIED.search, lines) if m)
        refused = [" ".join(m.group(0).split())[:WHY_WIDTH]
                   for m in map(REFUSED.search, lines) if m]
        print("PrintInlining, C2's refused calls that name the library, the node count or a"
              " refused intrinsic:")
        counted(line for line in refused if REFUSED_FIRST.search(line))
        print("PrintInlining, C2's other refused calls into the Vector API:")
        counted((line for line in refused if not REFUSED_FIRST.search(line)
                 and REFUSED_API.search(line)), WHY_LINES)


def check(path, threshold, expected=None, inputs=INPUTS):
    """Checks every row of the JSON at path; with expected, also that each of those benchmarks
    has a row for every value in inputs."""
    if not os.path.exists(path):
        print(f"FAIL: JMH wrote no {path}")
        return False
    try:
        with open(path, encoding="utf-8") as f:
            rows = json.load(f)
    except ValueError:  # not JSON, or cut inside a character
        print(f"FAIL: {path} is not valid JSON (empty, or a run was cut short)")
        return False
    if not rows:
        print("FAIL: JMH ran no benchmark")
        return False
    ok = True
    if expected is not None:
        got = {(row["benchmark"], row.get("params", {}).get("inputs")) for row in rows}
        for name in expected:
            for value in inputs:
                if (name, value) not in got:
                    print(f"FAIL  {name} [inputs={value}]: no row in {path}")
                    ok = False
    for row in rows:
        name = row["benchmark"].rsplit(".", 2)[-2:]
        params = ",".join(f"{k}={v}" for k, v in sorted(row.get("params", {}).items()))
        label = ".".join(name) + (f" [{params}]" if params else "")
        iterations = alloc_iterations(row)
        if not iterations:
            print(f"FAIL  {label}: no gc.alloc.rate.norm (was -prof gc on?)")
            ok = False
            continue
        worst = max(iterations)
        verdict = "ok  " if worst < threshold else "FAIL"
        ok = ok and worst < threshold
        print(f"{verdict}  {label}: max {worst:.3f} B/op over {len(iterations)} iterations")
    return ok


def main():
    # A terminated script (timeout, a closed terminal) must not leave JMH running: turn the
    # signals into an exit, which mvn_jmh answers by killing Maven's process group.
    for name in ("SIGTERM", "SIGHUP"):
        signal.signal(getattr(signal, name), lambda number, frame: sys.exit(128 + number))
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mvn", default="mvn", help="the Maven to run (default: mvn)")
    parser.add_argument("--jvm-args", default="", help="extra options for the benchmark JVMs")
    parser.add_argument("--include", default=None, help="JMH benchmark regex")
    parser.add_argument("--preferred-only", action="store_true",
                        help="the *Preferred rows without the *256 ones, for a host whose"
                             " vectors are narrower than 256 bits")
    parser.add_argument("--inputs", default=None,
                        help="the values of the inputs parameter to run, comma-separated;"
                             " a value other than common and mixed only for a class whose"
                             " setUp rejects a value it does not know (default: common,mixed)")
    parser.add_argument("--threshold", type=float, default=THRESHOLD, help="bytes per op")
    parser.add_argument("--no-run", action="store_true",
                        help=f"only check the rows of an existing {RESULT}")
    parser.add_argument("--why", action="store_true",
                        help=f"run each failing row of an existing {RESULT} once more, with C2"
                             " printing what it did not intrinsify or inline")
    args = parser.parse_args()
    if args.include and args.preferred_only:
        parser.error("--include and --preferred-only both name the rows; pass one")
    if args.why:
        if args.jvm_args or args.include or args.preferred_only or args.no_run or args.inputs:
            parser.error("--why reruns the failing rows of the last result, each under the"
                         " options the result records; --jvm-args, --include,"
                         " --preferred-only, --inputs and --no-run do not apply")
        why(args.mvn, RESULT, args.threshold)
        return
    if args.no_run and (args.include or args.preferred_only):
        parser.error("--no-run checks every row of the existing result; --include and"
                     " --preferred-only do not apply")
    inputs = INPUTS
    if args.inputs is not None:
        # The <Function>Benchmark classes read every value but mixed as common, so a row
        # named for another value would pass on common data. Such a value is therefore refused
        # below for those classes; it is for classes that throw on an unknown value
        # (ExpKindsBench). Nothing here checks that a class named apart throws: that rests on
        # AGENTS.md's rule and on the class's own test.
        if args.no_run:
            parser.error("--inputs cannot go with --no-run, which checks the rows it finds")
        inputs = tuple(args.inputs.split(","))
        if not all(re.fullmatch(r"\w+", v) for v in inputs):
            parser.error("--inputs takes comma-separated names, e.g. common,mixed")
    include = args.include or (PREFERRED if args.preferred_only else INCLUDE)
    expected = None
    if not args.no_run:
        # A result left by an earlier run must not stand in for this one.
        if os.path.exists(RESULT):
            os.remove(RESULT)
        try:
            expected = list_benchmarks(args.mvn, include)
            if not expected:
                print(f"FAIL: no benchmark matches {include}")
                sys.exit(1)
            unknown = [v for v in inputs if v not in INPUTS]
            lenient = [name for name in expected
                       if name.rsplit(".", 2)[-2].endswith("Benchmark")]
            apart = [name for name in expected if name not in lenient]
            if apart and args.inputs is None:
                print(f"FAIL: {apart[0]} is a class named apart, which has inputs values of its"
                      " own; name the ones to check with --inputs")
                sys.exit(1)
            if unknown and lenient:
                print(f"FAIL: --inputs {unknown[0]} with {lenient[0]}: a <Function>Benchmark"
                      " class reads any value but mixed as common, so the row would pass on"
                      " common data")
                sys.exit(1)
            run_jmh(args.mvn, include, args.jvm_args, inputs)
        except subprocess.CalledProcessError as e:
            print(e.stdout or "", end="")
            print(f"FAIL: Maven exited with {e.returncode}; a benchmark threw or the build"
                  " failed, see its output above")
            sys.exit(1)
    if not check(RESULT, args.threshold, expected, inputs):
        print(f"A row is missing, or allocated {args.threshold} B/op or more: a vector body"
              " boxed, or, if only its first iteration did, C2 had not finished. See SKILLS.md.")
        sys.exit(1)
    ran = "the rows found" if args.no_run else f"inputs={','.join(inputs)}"
    print(f"No vector body allocates {args.threshold} B/op or more ({ran}).")


if __name__ == "__main__":
    main()
