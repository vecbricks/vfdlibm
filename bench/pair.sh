#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs JMH benchmarks on a base and on the head in alternating rounds on one machine, and
# compares medians. Adapted from vecbricks/varka's dev/varka_bench_pair.sh, the script of the
# same role, with JMH's JSON result in place of Spark's benchmark files.
#
#   bench/pair.sh [options] <benchmark regex>
#   bench/pair.sh 'ExpBenchmark.finish256'                  # base: merge base with origin/main
#   bench/pair.sh --base HEAD 'LogBenchmark'                # A/A: the machine's own spread
#   bench/pair.sh --base 282813b --rounds 7 'ExpBenchmark'  # any commit or branch
#
# One run on one machine cannot tell a change from the machine's day: alternating the two sides
# round by round cancels what drifts between them, and the median of each side's rounds
# discards the round a neighbour disturbed. Each round runs the regex once on the base and once
# on the head, the order swapping every round, with the same JMH options and the body inlined
# as in .github/workflows/benchmark.yml. After at least three rounds it stops early when every
# row's median is within --within percent of the other side's. It then prints each row's change
# by the median and by the best iteration (+ faster, - slower; bench/pair.py) and exits 1 if any
# row is slower by --threshold percent or more by both. With --base HEAD it pairs the head with
# itself, an A/A run, whose widest change is the machine's own spread for those rows, to read a
# real pair's changes against.
#
# The head is this checkout as it stands; the base is built in a detached worktree of its own,
# removed afterwards unless --keep. Both are compiled once, before the first round. The results
# file (--result, default <out>/pair.txt) opens with the provenance header of bench/results/
# (AGENTS.md, "Measurements, not adjectives"), both commits and the datapath reading included,
# then the comparison, then each round's JMH summary; the out directory keeps every round's JSON
# and log. Copy the results file to bench/results/<function>-<comparison>-<host>-<date>.txt.
#
#   --base REF        the base: a commit, a branch or HEAD; default the merge base of HEAD and
#                     origin/main (main when there is no origin/main).
#   --rounds N        at most N rounds (default 5).
#   --threshold P     a row slower by P percent by both median and best fails (default 10).
#   --within P        the early stop's band (default 3).
#   --jmh OPTS        JMH options; default the fixed method, "-f 2 -wi 3 -w 2s -i 5 -r 2s
#                     -prof gc" (AGENTS.md). Shorter ones prove the plumbing, nothing more.
#   --jvm-args ARGS   extra JVM options for the benchmark JVMs, before the inline command.
#   --host KEY        the benchmark host's key, for the header (default "local", not a headline).
#   --datapath FILE   bench/datapath.sh's output, already taken; default run it here.
#   --run URL         the workflow run, for the header.
#   --out DIR         where the rounds' JSON and logs go (default a new temporary directory).
#   --result FILE     the results file (default <out>/pair.txt).
#   --mvn MVN         the Maven to run (default mvn).
#   --keep            keep the base worktree, and print where it is.
#
# Exit status: 0 when no row is slower, 1 when one is, 2 when a build or a run failed or no row
# was on both sides, 64 on a usage error.

set -uo pipefail
usage() { sed -n '10,54p' "$0" >&2; exit 64; }
base_ref=""; rounds=5; threshold=10; within=3; keep=0; out=""; result=""; mvn=mvn
jmh="-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc"; jvm_args=""; host="local"; datapath=""; run=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) base_ref="$2"; shift 2 ;;
    --rounds) rounds="$2"; shift 2 ;;
    --threshold) threshold="$2"; shift 2 ;;
    --within) within="$2"; shift 2 ;;
    --jmh) jmh="$2"; shift 2 ;;
    --jvm-args) jvm_args="$2"; shift 2 ;;
    --host) host="$2"; shift 2 ;;
    --datapath) datapath="$2"; shift 2 ;;
    --run) run="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --result) result="$2"; shift 2 ;;
    --mvn) mvn="$2"; shift 2 ;;
    --keep) keep=1; shift ;;
    -h|--help) usage ;;
    -*) echo "unknown option $1" >&2; usage ;;
    *) break ;;
  esac
done
[ $# -eq 1 ] || usage
regex="$1"
case "$rounds" in ''|*[!0-9]*|0) echo "--rounds takes a positive number" >&2; usage ;; esac

here="$(cd "$(dirname "$0")" && pwd)"
head_dir="$(git -C "$here" rev-parse --show-toplevel)" || exit 2
if [ -z "$base_ref" ]; then
  if git -C "$head_dir" rev-parse -q --verify origin/main > /dev/null; then
    base_ref="$(git -C "$head_dir" merge-base HEAD origin/main)"
  else
    base_ref="$(git -C "$head_dir" merge-base HEAD main)"
  fi
fi
base_sha="$(git -C "$head_dir" rev-parse -q --verify "$base_ref^{commit}")" \
  || { echo "base '$base_ref' is not a commit" >&2; exit 2; }
head_sha="$(git -C "$head_dir" rev-parse HEAD)"
if [ -n "$out" ]; then mkdir -p "$out" || exit 2; else out="$(mktemp -d -t vfdlibm-pair.XXXXXX)"; fi
out="$(cd "$out" && pwd)"
[ -n "$result" ] || result="$out/pair.txt"
base_dir="$(mktemp -d -t vfdlibm-pair-base.XXXXXX)"
rmdir "$base_dir"
# shellcheck disable=SC2329 # run by the EXIT trap
cleanup() {
  if [ "$keep" -eq 0 ]; then
    git -C "$head_dir" worktree remove --force "$base_dir" > /dev/null 2>&1 || true
  else
    echo "base worktree kept in $base_dir"
  fi
  echo "rounds kept in $out; results in $result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
git -C "$head_dir" worktree add -q --detach "$base_dir" "$base_sha" || exit 2

subject() { git -C "$head_dir" log -1 --format='%h %s' "$1" | cut -c1-72; }
dirty=""
[ -z "$(git -C "$head_dir" status --porcelain --untracked-files=no)" ] \
  || dirty=", with uncommitted changes"
echo "base: $(subject "$base_sha") ($base_dir)"
echo "head: $(subject "$head_sha") ($head_dir$dirty)"

# The datapath reading, taken before the builds load the machine.
if [ -n "$datapath" ]; then
  dp="$(sed -n 's/^datapath: //p' "$datapath" 2> /dev/null)"
else
  dp="$("$here/datapath.sh" | sed -n 's/^datapath: //p')"
fi
load_start="$(cut -d ' ' -f 1-3 /proc/loadavg)"

for side in base head; do
  if [ "$side" = base ]; then tree="$base_dir"; else tree="$head_dir"; fi
  echo "== building the $side"
  if ! (cd "$tree" && "$mvn" -B -q --no-transfer-progress -Pjmh test-compile) \
      > "$out/$side-build.log" 2>&1; then
    cat "$out/$side-build.log" >&2; echo "the $side did not build" >&2; exit 2
  fi
done

inline="-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"
append="$inline"
[ -z "$jvm_args" ] || append="$jvm_args $inline"
loads=()
run_side() {  # side round
  local side="$1" r="$2" tree
  if [ "$side" = base ]; then tree="$base_dir"; else tree="$head_dir"; fi
  echo "== round $r: $side (load $(cut -d ' ' -f 1 /proc/loadavg))"
  loads+=("round $r $side: load $(cut -d ' ' -f 1-3 /proc/loadavg) at start")
  if ! (cd "$tree" && "$mvn" -B -q --no-transfer-progress -Pjmh exec:exec \
      "-Djmh.args=$regex $jmh -foe true -rf json -rff $out/$side-$r.json \
-jvmArgsAppend \"$append\"") > "$out/$side-$r.log" 2>&1; then
    tail -n 30 "$out/$side-$r.log" >&2; echo "the $side run of round $r failed" >&2; exit 2
  fi
}

base_files=(); head_files=(); done_rounds=0
for r in $(seq 1 "$rounds"); do
  if [ $((r % 2)) -eq 1 ]; then order="base head"; else order="head base"; fi
  for side in $order; do run_side "$side" "$r"; done
  base_files+=("$out/base-$r.json"); head_files+=("$out/head-$r.json"); done_rounds=$r
  if [ "$r" -ge 3 ] && [ "$r" -lt "$rounds" ] && [ "$("$here/pair.py" settled \
      --base "${base_files[@]}" --head "${head_files[@]}" --within "$within")" = settled ]; then
    echo "every row is within $within% after $r rounds; stopping"
    break
  fi
done

flags="$(grep -m1 '^flags' /proc/cpuinfo)"
has() { if grep -qw "$1" <<<"$flags"; then echo yes; else echo no; fi; }
{
  echo "# Pair: $regex, base against head, $(date -u +'%Y-%m-%d %H:%M UTC')"
  echo "#"
  if [ "$host" = local ]; then
    echo "# Host:        local machine (laptop numbers, not headlines)"
  else
    echo "# Host:        $host"
  fi
  echo "# Base:        $base_sha ($(subject "$base_sha" | cut -d ' ' -f 2-))"
  echo "# Head:        $head_sha ($(subject "$head_sha" | cut -d ' ' -f 2-)$dirty)"
  [ -z "$run" ] || echo "# Run:         $run"
  echo "# JDK:         $(java -version 2>&1 | tail -n 1)"
  echo "# CPU:         $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
  echo "# AVX-512:     avx512f=$(has avx512f) avx512dq=$(has avx512dq)"
  echo "# CPU flags:   $(tr ' ' '\n' <<<"$flags" | grep -E '^avx' | sort -u | tr '\n' ' ')"
  # shellcheck disable=SC2086 # the extra JVM options are words
  echo "# Vector:      $(java $jvm_args -XX:+PrintFlagsFinal -version 2>/dev/null \
    | grep -E ' (MaxVectorSize|UseAVX) ' | awk '{print $2 "=" $4}' | tr '\n' ' ')"
  echo "# Datapath:    ${dp:-not measured (the probe failed)}"
  echo "#              (bench/datapath.sh, at the machine's own JVM flags)"
  echo "# Kernel:      $(uname -r)"
  echo "# Load:        $load_start at start, $(cut -d ' ' -f 1-3 /proc/loadavg) at end" \
    "($(nproc) cores)"
  echo "# JMH:         $jmh"
  echo "#              -jvmArgsAppend \"$append\""
  echo "# Pair:        $done_rounds of at most $rounds rounds, base first in odd rounds;"
  echo "#              threshold $threshold%, early stop within $within% after three rounds"
  echo "# Command:     bench/pair.sh --base $base_ref --rounds $rounds --threshold $threshold" \
    "'$regex'"
  echo "#"
  echo "# Comparison (bench/pair.py): + is faster, - slower; median of the rounds' scores and"
  echo "# best measured iteration of any round, head against base."
  echo "#"
} > "$result"
"$here/pair.py" compare --base "${base_files[@]}" --head "${head_files[@]}" \
  --threshold "$threshold" > "$out/compare.txt" 2> "$out/compare.err"
status=$?
cat "$out/compare.txt" >> "$result"
{
  echo
  echo "# Load at the start of each side:"
  for l in "${loads[@]}"; do echo "#   $l"; done
  echo "#"
  echo "# Each round's JMH summary table follows; the out directory has the JSON and full logs."
  for r in $(seq 1 "$done_rounds"); do
    for side in base head; do
      echo
      echo "# round $r, $side"
      sed -n '/^Benchmark /,$p' "$out/$side-$r.log"
    done
  done
} >> "$result"
cat "$out/compare.err" >&2
cat "$out/compare.txt"
exit "$status"
