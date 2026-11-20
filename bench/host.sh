#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# The table of benchmark hosts, and the check of a machine against it. This is the one place
# that holds each host's CPU string: .github/workflows/benchmark.yml fetches it and runs it
# before anything else, bench/dispatch-bench.sh takes its host keys from it, and CI runs its
# self-test.
#
#   bench/host.sh --expect <key>   check this machine against one host; exit 1 on any other CPU
#   bench/host.sh                  print this machine's host key; exit 1 when it is no named host
#   bench/host.sh --keys           print the host keys, one per line
#   bench/host.sh --classify '<model name>' ['<flags>']
#                                  print the key of the host with that model name and those
#                                  CPU flags; exit 1 when there is none
#   bench/host.sh --self-test      check the table against the machines the runner pool gives
#
# A string here is never relaxed (SKILLS.md, "Where headline numbers come from"): a run on
# another CPU is not comparable with the results before it. Changing the hosts is a decision
# for the owner, and it is a new row, not a looser string.

# One host per line: key | where the string must sit | the string | flags the CPU must have.
#
# Each string is /proc/cpuinfo's model name as runs of that host printed it (SKILLS.md names the
# runs), matched ignoring case, since Azure VMs have reported Intel names in capitals
# ("INTEL(R) XEON(R) PLATINUM 8573C"). The two EPYC strings are the full names the runs printed
# and must end the model name ("end"), so that a longer model number cannot pass; the two Xeon
# strings had no recorded full form when they were written, so they may sit anywhere in it
# ("any"). The census of the pool (bench/pool/) has since printed both as whole names; making
# them "end" would be a change to the check, which is the owner's. The last field is
# a list of words of /proc/cpuinfo's flags line, separated by spaces, all of which the machine
# must have; no host of today's needs one, since each model name has come with one set of
# flags.
HOSTS=(
  "epyc7763|end|AMD EPYC 7763 64-Core Processor|"
  "xeon8573c|any|INTEL(R) XEON(R) PLATINUM 8573C|"
  "xeon6973p|any|Intel(R) Xeon(R) 6973P-C|"
  "epyc9v45|end|AMD EPYC 9V45 96-Core Processor|"
)

keys() { local row; for row in "${HOSTS[@]}"; do printf '%s\n' "${row%%|*}"; done; }

# The row of a key, or status 1.
row_of() {
  local row
  for row in "${HOSTS[@]}"; do
    if [ "${row%%|*}" = "$1" ]; then printf '%s\n' "$row"; return 0; fi
  done
  return 1; }

# The CPU string of a row.
string_of() { local key where string need; IFS='|' read -r key where string need <<<"$1"
  printf '%s\n' "$string"; }

# Whether a model name and a flags line are a row's host.
matches() {
  local key where string need flag lc want
  IFS='|' read -r key where string need <<<"$1"
  lc="${2,,}"; want="${string,,}"
  case "$where" in
    end) [[ "$lc" == *"$want" ]] || return 1 ;;
    any) [[ "$lc" == *"$want"* ]] || return 1 ;;
    *) return 1 ;;
  esac
  for flag in $need; do grep -qw -- "$flag" <<<"$3" || return 1; done
  return 0; }

# A model name as the check reads it: without the blanks that end it.
trim() { sed 's/[[:space:]]*$//' <<<"$1"; }

# The key of the host a model name and a flags line are, or status 1.
classify() {
  local row cpu
  cpu="$(trim "$1")"
  for row in "${HOSTS[@]}"; do
    if matches "$row" "$cpu" "$2"; then printf '%s\n' "${row%%|*}"; return 0; fi
  done
  return 1; }

# The check of one host: the key, the model name, the flags line. Prints what
# benchmark.yml's probe step has always printed.
expect() {
  local row cpu
  if ! row="$(row_of "$1")"; then echo "::error::Unknown host '$1'"; return 1; fi
  cpu="$(trim "$2")"
  echo "Host: $1, CPU: $cpu"
  if ! matches "$row" "$cpu" "$3"; then
    echo "::error::CPU mismatch on host $1! Expected '$(string_of "$row")' but got '$cpu'"
    return 1
  fi
  return 0; }

this_cpu() { grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //'; }
this_flags() { grep -m1 '^flags' /proc/cpuinfo; }

self_test() {
  local failed=0 n=0 want got out
  # check <want> <model name> <flags>: classify gives the key <want>, or no key for "-".
  check() {
    n=$((n + 1)); got="$(classify "$2" "$3")" || got="-"
    if [ "$got" != "$1" ]; then
      echo "host.sh: '$2' with flags '$3' is '$got', expected '$1'" >&2; failed=1
    fi; }
  # expecting <status> <key> <model name>: the check of one host ends with that status.
  expecting() {
    n=$((n + 1)); out="$(expect "$2" "$3" "")"; got=$?
    if [ "$got" != "$1" ]; then
      echo "host.sh: --expect $2 on '$3' ended with $got, expected $1: $out" >&2; failed=1
    fi; }
  local avx2="fpu sse4_2 avx avx2 fma"
  local avx512="$avx2 avx512f avx512dq avx512cd avx512bw avx512vl"

  # Every kind of x86 machine in the census of the runner pool
  # (bench/pool/nightly-2026-11-03-to-2026-11-19.tsv), and the Arm runners, whose
  # /proc/cpuinfo has no model name.
  check epyc7763 'AMD EPYC 7763 64-Core Processor' "$avx2"
  check epyc9v45 'AMD EPYC 9V45 96-Core Processor' "$avx512"
  check - 'AMD EPYC 9V74 80-Core Processor' "$avx2"
  check - 'AMD EPYC 9V74 80-Core Processor' "$avx512"
  check xeon6973p 'Intel(R) Xeon(R) 6973P-C' "$avx512"
  check - 'Intel(R) Xeon(R) 6975P-C' "$avx512"
  check - 'Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz' "$avx512"
  check xeon8573c 'INTEL(R) XEON(R) PLATINUM 8573C' "$avx512"
  check - '' ''

  # Names match whatever their case, and blanks after a name do not count.
  check xeon8573c 'Intel(R) Xeon(R) Platinum 8573C' "$avx512"
  check xeon6973p 'INTEL(R) XEON(R) 6973P-C' "$avx512"
  check epyc7763 'amd epyc 7763 64-core processor' "$avx2"
  check epyc7763 'AMD EPYC 7763 64-Core Processor  ' "$avx2"
  # A Xeon string may sit anywhere in the name; an EPYC string must end it.
  check xeon8573c 'INTEL(R) XEON(R) PLATINUM 8573C CPU @ 2.30GHz' "$avx512"
  check - 'AMD EPYC 7763 64-Core Processor X' "$avx2"
  check - 'AMD EPYC 9V45 96-Core Processor 2' "$avx512"
  check - 'AMD EPYC 7763' "$avx2"
  check - 'AMD EPYC 7763P 64-Core Processor' "$avx2"
  check - 'Intel(R) Xeon(R) 6973P' "$avx512"

  # The check of one host passes on that host alone, and an unknown key fails.
  expecting 0 epyc7763 'AMD EPYC 7763 64-Core Processor'
  expecting 1 epyc7763 'AMD EPYC 9V74 80-Core Processor'
  expecting 1 xeon8573c 'AMD EPYC 7763 64-Core Processor'
  expecting 1 xeon6973p 'Intel(R) Xeon(R) 6975P-C'
  expecting 0 epyc9v45 'AMD EPYC 9V45 96-Core Processor'
  expecting 1 epyc9v45 'AMD EPYC 9V74 80-Core Processor'
  expecting 1 nosuchhost 'AMD EPYC 7763 64-Core Processor'
  n=$((n + 1)); out="$(expect xeon8573c 'AMD EPYC 7763 64-Core Processor' "")"
  want="::error::CPU mismatch on host xeon8573c! Expected 'INTEL(R) XEON(R) PLATINUM 8573C'"
  want+=" but got 'AMD EPYC 7763 64-Core Processor'"
  if [ "$(tail -n 1 <<<"$out")" != "$want" ]; then
    echo "host.sh: a mismatch printed '$out'" >&2; failed=1
  fi

  # The keys are the four hosts, each once, and every row is well formed.
  n=$((n + 1)); got="$(keys | tr '\n' ' ')"
  if [ "$got" != "epyc7763 xeon8573c xeon6973p epyc9v45 " ]; then
    echo "host.sh: the keys are '$got'" >&2; failed=1
  fi
  local row key where string need
  for row in "${HOSTS[@]}"; do
    n=$((n + 1)); IFS='|' read -r key where string need <<<"$row"
    if ! [[ $key =~ ^[a-z0-9]+$ ]] || [ -z "$string" ] \
        || { [ "$where" != end ] && [ "$where" != any ]; }; then
      echo "host.sh: malformed row '$row'" >&2; failed=1
    fi
  done

  # The keys are the choices of benchmark.yml's host input, in its order: a key on one side
  # only would fail a dispatch, or fail every run in the probe step, which reads as a CPU miss.
  local yml; yml="$(dirname "$0")/../.github/workflows/benchmark.yml"
  n=$((n + 1))
  got="$(awk '/^      host:/ {h = 1; next} h && /^      [a-z]/ {exit}
    h && /^        options:/ {o = 1; next} h && o && /^          - / {print $2}' "$yml")"
  if [ "$got" != "$(keys)" ]; then
    echo "host.sh: benchmark.yml's host choices are '$(tr '\n' ' ' <<<"$got")'" >&2; failed=1
  fi

  # The script as the workflow runs it, on this machine, for every key: it passes for the key
  # this machine is, if it is a host, and then writes that host's string for the results
  # header; for every other key it fails and writes nothing.
  local here env line
  here="$(classify "$(this_cpu)" "$(this_flags)")" || here="-"
  env="$(mktemp)"
  for key in $(keys); do
    n=$((n + 1)); : > "$env"
    GITHUB_ENV="$env" "$0" --expect "$key" > /dev/null; got=$?
    line="$(cat "$env")"
    if [ "$key" = "$here" ]; then
      want="EXPECTED_CPU=$(string_of "$(row_of "$key")")"
      if [ "$got" != 0 ] || [ "$line" != "$want" ]; then
        echo "host.sh: --expect $key here ended with $got and wrote '$line'" >&2; failed=1
      fi
    elif [ "$got" != 1 ] || [ -n "$line" ]; then
      echo "host.sh: --expect $key here ended with $got and wrote '$line'" >&2; failed=1
    fi
  done
  rm -f "$env"

  # A row's flags are required, every one of them. No host of today's has any, so the test
  # adds a row of its own: one model name that is a host only with AVX-512.
  HOSTS+=("testhost|end|Test CPU 1 Processor|avx512f avx512dq")
  check testhost 'Test CPU 1 Processor' "$avx512"
  check - 'Test CPU 1 Processor' "$avx2"
  check - 'Test CPU 1 Processor' "$avx2 avx512f"
  check - 'Test CPU 1 Processor' "$avx2 avx512f avx512dq_x"

  if [ "$failed" != 0 ]; then echo "host.sh: self-test FAILED" >&2; return 1; fi
  echo "host.sh: self-test passed, $n checks"; }

usage() {
  echo "usage: bench/host.sh [--expect <key> | --keys | --classify '<model name>' ['<flags>']" \
    "| --self-test]" >&2
  exit 64; }

case "${1:-}" in
  "")
    [ $# -eq 0 ] || usage
    cpu="$(this_cpu)"
    if ! classify "$cpu" "$(this_flags)"; then
      echo "host.sh: '$(trim "$cpu")' is not a benchmark host" >&2; exit 1
    fi ;;
  --expect)
    [ $# -eq 2 ] || usage
    expect "$2" "$(this_cpu)" "$(this_flags)" || exit 1
    # For the results header of benchmark.yml's later steps.
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "EXPECTED_CPU=$(string_of "$(row_of "$2")")" >> "$GITHUB_ENV"
    fi ;;
  --keys) [ $# -eq 1 ] || usage; keys ;;
  --classify)
    [ $# -eq 2 ] || [ $# -eq 3 ] || usage
    if ! classify "$2" "${3:-}"; then
      echo "host.sh: '$(trim "$2")' is not a benchmark host" >&2; exit 1
    fi ;;
  --self-test) [ $# -eq 1 ] || usage; self_test || exit 1 ;;
  *) usage ;;
esac
