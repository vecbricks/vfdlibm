#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Checks that a change leaves the nightly's output for the functions of one argument as it was:
#
#   dev/unary-output-check.sh capture <dir>       write the output of a sweep and a fuzz of every
#                                                 function, and of four planted faults, to <dir>
#   dev/unary-output-check.sh compare <dir-a> <dir-b>
#   dev/unary-output-check.sh check <base-ref>    capture a build of <base-ref> (in a temporary
#                                                 worktree) and this tree, and compare them
#   dev/unary-output-check.sh --self-test
#
# It runs Sweep and Fuzz as the nightly does, on fixed slices with one thread and a bounded draw
# count, so that their logs and mismatch files are the same on every run of one build: each
# function at 128, 256 and max bits, a planted fault of four functions at 256 bits, and the usage
# errors. A capture needs the classes built (mvn test-compile); `check` builds the base itself,
# with MVN (default mvn). `check` took 48 seconds on a 24-core laptop (101 files, the seven
# functions of 21 November 2026, the base's build included, a warm Maven repository), and the
# self-test 5; it is run by a person, not by CI: use it on a change to Sweep, Fuzz, Nightly or
# Inputs. Lines that vary with the machine or the run (the timing, the
# host and the JVM's arguments) are left out; a file in only one of two captures is listed and not
# counted, so that a port which adds a function does not fail it. Exit status: 0 when every file
# in both captures is the same, 1 when one differs, 2 for a usage error.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
SLICE="$((0x3ff00000 >> 12))/$((1 << 20))"
SPECIES="${SPECIES:-128 256 max}"
FAULTS="exp.dropCorrection expm1.thresholdMoved log.dropCorrection sinh.splitMoved"

# The functions of one argument the classes under <classes> know: the list its own error gives.
functions() {
  java --add-modules jdk.incubator.vector -cp "$1" io.github.vecbricks.vfdlibm.Sweep \
    --function nope --seed 1 2>&1 | sed -n "s/^Sweep: unknown function 'nope'; known: //p" \
    | tr -d ','
}

# Output without what varies from run to run: timing, the host and the JVM's own arguments.
norm() { grep -v -E '^(swept|fuzzed|progress|cpu|cores|jdk|jvm args|species:)|^# jvm args'; }

capture() {
  local out="$1" classes="$2" f s pf
  mkdir -p "$out"
  local run=(java --add-modules jdk.incubator.vector)
  for f in $(functions "$classes"); do
    for s in $SPECIES; do
      "${run[@]}" -cp "$classes" io.github.vecbricks.vfdlibm.Sweep --function "$f" --species "$s" \
        --part "$SLICE" --seed 0x5eed --threads 1 --out "$out/sweep-$f-$s.txt" 2>&1 \
        | norm > "$out/sweep-$f-$s.log"
      "${run[@]}" -cp "$classes" io.github.vecbricks.vfdlibm.Fuzz --function "$f" --species "$s" \
        --seed 0x5eed --seconds 600 --draws 32768 --threads 1 --out "$out/fuzz-$f-$s.txt" 2>&1 \
        | norm > "$out/fuzz-$f-$s.log"
    done
  done
  for pf in $FAULTS; do
    f="${pf%%.*}"
    "${run[@]}" -Dvfdlibm.fault="$pf" -cp "$classes" io.github.vecbricks.vfdlibm.Sweep \
      --function "$f" --species 256 --part "$SLICE" --seed 0x5eed --threads 1 \
      --out "$out/fsweep-$pf.txt" 2>&1 | norm > "$out/fsweep-$pf.log"
    "${run[@]}" -Dvfdlibm.fault="$pf" -cp "$classes" io.github.vecbricks.vfdlibm.Fuzz \
      --function "$f" --species 256 --seed 0x5eed --seconds 600 --draws 32768 --threads 1 \
      --out "$out/ffuzz-$pf.txt" 2>&1 | norm > "$out/ffuzz-$pf.log"
  done
  {
    "${run[@]}" -cp "$classes" io.github.vecbricks.vfdlibm.Sweep --function exp 2>&1
    "${run[@]}" -cp "$classes" io.github.vecbricks.vfdlibm.Fuzz --fucntion exp 2>&1
  } | grep -v incubator > "$out/errors.log"
  echo "captured $(ls "$out" | wc -l) files in $out"
}

# The paths in a file name the directory it was written to: compared as if both were <dir>.
compare() {
  local a="$1" b="$2" f differ=0 same=0 only=0
  for f in "$a"/*; do
    local name
    name="$(basename "$f")"
    if [ ! -e "$b/$name" ]; then
      echo "only in $a: $name"; only=$((only + 1)); continue
    fi
    if cmp -s <(sed "s#$a#<dir>#g" "$f") <(sed "s#$b#<dir>#g" "$b/$name"); then
      same=$((same + 1))
    else
      echo "DIFFERS: $name"; differ=$((differ + 1))
    fi
  done
  for f in "$b"/*; do
    [ -e "$a/$(basename "$f")" ] || { echo "only in $b: $(basename "$f")"; only=$((only + 1)); }
  done
  echo "$same files the same, $differ differ, $only in one capture only"
  [ "$differ" -eq 0 ]
}

self_test() {
  local classes="$root/target/classes:$root/target/test-classes"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  FAULTS="exp.dropCorrection"
  SPECIES=256
  mkdir -p "$tmp/one" "$tmp/two"
  # One function only keeps the test short: the capture of every function is the same code.
  functions() { echo exp; }
  capture "$tmp/one" "$classes" > /dev/null && capture "$tmp/two" "$classes" > /dev/null
  compare "$tmp/one" "$tmp/two" > /dev/null || { echo "self-test: a repeat differs"; return 1; }
  # A changed line is found ...
  echo "an extra line" >> "$tmp/two/sweep-exp-256.log"
  if compare "$tmp/one" "$tmp/two" > /dev/null; then
    echo "self-test: a changed log was not found"; return 1
  fi
  # ... and so is a change of behaviour: the capture of a build with a fault planted.
  capture "$tmp/two" "$classes" > /dev/null
  java --add-modules jdk.incubator.vector -Dvfdlibm.fault=exp.dropCorrection -cp "$classes" \
    io.github.vecbricks.vfdlibm.Sweep --function exp --species 256 --part "$SLICE" \
    --seed 0x5eed --threads 1 --out "$tmp/two/sweep-exp-256.txt" 2>&1 | norm \
    > "$tmp/two/sweep-exp-256.log"
  if compare "$tmp/one" "$tmp/two" > /dev/null; then
    echo "self-test: a planted fault was not found"; return 1
  fi
  echo "self-test passed: a repeat is the same, a changed log and a planted fault are found"
}

case "${1:-}" in
  capture)
    [ $# -eq 2 ] || { sed -n '9,19p' "$0"; exit 2; }
    capture "$2" "$root/target/classes:$root/target/test-classes" ;;
  compare)
    [ $# -eq 3 ] || { sed -n '9,19p' "$0"; exit 2; }
    compare "$2" "$3" ;;
  check)
    [ $# -eq 2 ] || { sed -n '9,19p' "$0"; exit 2; }
    work="$(mktemp -d)"
    trap 'git -C "$root" worktree remove --force "$work/base" 2>/dev/null; rm -rf "$work"' EXIT
    git -C "$root" worktree add --detach "$work/base" "$2" > /dev/null || exit 2
    (cd "$work/base" && "${MVN:-mvn}" -B -q test-compile) || exit 2
    capture "$work/base-out" "$work/base/target/classes:$work/base/target/test-classes" || exit 2
    capture "$work/this-out" "$root/target/classes:$root/target/test-classes" || exit 2
    compare "$work/base-out" "$work/this-out" ;;
  --self-test) self_test ;;
  *) sed -n '9,19p' "$0"; exit 2 ;;
esac
