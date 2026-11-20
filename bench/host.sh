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
# that holds each host's CPU string: .github/workflows/benchmark.yml fetches it, runs its
# self-test and then checks the machine with it, before any step but its fetch;
# bench/dispatch-bench.sh takes its host keys from it, and CI runs its self-test.
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
# for the owner, and it is a new row, not a looser string. A row's key is also a choice of
# benchmark.yml's host input (the self-test compares the two), and bench/dispatch-bench.sh
# names epyc9v45 for its default of 18 attempts: a host nobody waits for needs its line there.

# Names are compared ignoring case, by ASCII's rules whatever the caller's locale is.
export LC_ALL=C

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
# flags. A row that requires a flag outside the self-test's AVX-512 list also needs its census
# cases there updated. A row can only require a flag, not forbid one, so of a model name that
# comes both with and without a flag only the kind with it can be a row.
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

# The flags a row requires.
need_of() { local key where string need; IFS='|' read -r key where string need <<<"$1"
  printf '%s\n' "$need"; }

# The CPU string of a row.
string_of() { local key where string need; IFS='|' read -r key where string need <<<"$1"
  printf '%s\n' "$string"; }

# Whether a model name is a row's.
name_matches() {
  local key where string need lc want
  IFS='|' read -r key where string need <<<"$1"
  lc="${2,,}"; want="${string,,}"
  case "$where" in
    end) [[ "$lc" == *"$want" ]] ;;
    any) [[ "$lc" == *"$want"* ]] ;;
    *) return 1 ;;
  esac; }

# The flags a row requires that a flags line lacks, on one line; nothing when it has them all.
missing_flags() {
  local key where string need flag missing=""
  IFS='|' read -r key where string need <<<"$1"
  for flag in $need; do grep -qwF -- "$flag" <<<"$2" || missing+="${missing:+ }$flag"; done
  printf '%s' "$missing"; }

# Whether a model name and a flags line are a row's host.
matches() { name_matches "$1" "$2" && [ -z "$(missing_flags "$1" "$3")" ]; }

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
  local row cpu missing
  if ! row="$(row_of "$1")"; then echo "::error::Unknown host '$1'"; return 1; fi
  cpu="$(trim "$2")"
  echo "Host: $1, CPU: $cpu"
  if ! name_matches "$row" "$cpu"; then
    echo "::error::CPU mismatch on host $1! Expected '$(string_of "$row")' but got '$cpu'"
    return 1
  fi
  missing="$(missing_flags "$row" "$3")"
  if [ -n "$missing" ]; then
    echo "::error::CPU mismatch on host $1! '$cpu' lacks the flags '$missing'"
    return 1
  fi
  return 0; }

# The check of one host, and on success the line for the results header of benchmark.yml's
# later steps, written to $GITHUB_ENV when it is set: the key, the model name, the flags line.
expect_and_record() {
  expect "$1" "$2" "$3" || return 1
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "EXPECTED_CPU=$(string_of "$(row_of "$1")")" >> "$GITHUB_ENV"
  fi; }

this_cpu() { grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //'; }
this_flags() { grep -m1 '^flags' /proc/cpuinfo; }

# Lines joined into one, for the self-test's messages: a quoted line that began with "::" would
# be read by GitHub as a command of its own.
flat() { tr '\n' '|' <<<"$1" | sed 's/|$//'; }

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
      echo "host.sh: --expect $2 on '$3' ended with $got, expected $1: $(flat "$out")" >&2; failed=1
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
    echo "host.sh: a mismatch printed '$(flat "$out")'" >&2; failed=1
  fi

  # The dispatcher gives the key epyc9v45 its default of 18 attempts; a host nobody waits for
  # needs that line, so the key it names must still be a row.
  n=$((n + 1))
  if ! keys | grep -qxF epyc9v45; then
    echo "host.sh: no row for epyc9v45, whose default of 18 attempts the dispatcher sets" >&2
    failed=1
  fi

  # No key is there twice, and every row is well formed.
  n=$((n + 1)); got="$(keys | sort | uniq -d | tr '\n' ' ')"
  if [ -n "$got" ]; then echo "host.sh: more than one row for '$got'" >&2; failed=1; fi
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
  local yml; yml="$(dirname "${BASH_SOURCE[0]}")/../.github/workflows/benchmark.yml"
  if ! [ -r "$yml" ]; then
    echo "host.sh: no $yml beside this script; run it by its path in the repository" >&2
    return 1
  fi
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
  if ! env="$(mktemp)"; then echo "host.sh: no temporary file for the self-test" >&2; return 1; fi
  for key in $(keys); do
    n=$((n + 1)); : > "$env"
    GITHUB_ENV="$env" bash "${BASH_SOURCE[0]}" --expect "$key" > /dev/null; got=$?
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

  # On every machine, the success path with each host's own name: status 0 and the one line.
  for key in $(keys); do
    n=$((n + 1)); : > "$env"
    GITHUB_ENV="$env" expect_and_record "$key" "$(string_of "$(row_of "$key")")" \
      "$avx512 $(need_of "$(row_of "$key")")" \
      > /dev/null; got=$?
    line="$(cat "$env")"; want="EXPECTED_CPU=$(string_of "$(row_of "$key")")"
    if [ "$got" != 0 ] || [ "$line" != "$want" ]; then
      echo "host.sh: a $key machine ended with $got and wrote '$line'" >&2; failed=1
    fi
    # Each row's own name is that row's host and no other row's: classify takes the first
    # row that matches, and --expect checks one key, so an overlap would let one machine pass
    # as two hosts.
    n=$((n + 1)); local other matched=""
    for other in $(keys); do
      if matches "$(row_of "$other")" "$(string_of "$(row_of "$key")")" \
          "$avx512 $(need_of "$(row_of "$key")")"; then
        matched+="${matched:+ }$other"
      fi
    done
    if [ "$matched" != "$key" ]; then
      echo "host.sh: the name of $key is the host of '$matched'" >&2; failed=1
    fi
  done
  rm -f "$env"

  # bench/dispatch-bench.sh reads three steps of benchmark.yml by their exact names.
  local step
  for step in "Fetch the probe's script" "Check the probe's script" "Probe the machine's CPU"; do
    n=$((n + 1))
    if ! grep -qxF -- "      - name: $step" "$yml"; then
      echo "host.sh: benchmark.yml has no step named \"$step\"" >&2; failed=1
    fi
  done

  # A row's flags are required, every one of them. No host of today's has any, so the test
  # adds a row of its own: one model name that is a host only with AVX-512.
  HOSTS+=("testhost|end|Test CPU 1 Processor|avx512f avx512dq")
  check testhost 'Test CPU 1 Processor' "$avx512"
  check - 'Test CPU 1 Processor' "$avx2"
  check - 'Test CPU 1 Processor' "$avx2 avx512f"
  check - 'Test CPU 1 Processor' "$avx2 avx512f avx512dq_x"
  n=$((n + 1)); out="$(expect testhost 'Test CPU 1 Processor' "$avx2 avx512f")"; got=$?
  want="::error::CPU mismatch on host testhost! 'Test CPU 1 Processor' lacks the flags"
  want+=" 'avx512dq'"
  if [ "$got" != 1 ] || [ "$(tail -n 1 <<<"$out")" != "$want" ]; then
    echo "host.sh: missing flags ended with $got and printed '$(flat "$out")'" >&2; failed=1
  fi
  n=$((n + 1)); out="$(expect testhost 'Test CPU 1 Processor' "$avx512")"; got=$?
  if [ "$got" != 0 ]; then
    echo "host.sh: a host with its flags ended with $got and printed '$(flat "$out")'" >&2; failed=1
  fi

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
    expect_and_record "$2" "$(this_cpu)" "$(this_flags)" || exit 1 ;;
  --keys) [ $# -eq 1 ] || usage; keys ;;
  --classify)
    [ $# -eq 2 ] || [ $# -eq 3 ] || usage
    if ! classify "$2" "${3:-}"; then
      echo "host.sh: '$(trim "$2")' is not a benchmark host" >&2; exit 1
    fi ;;
  --self-test) [ $# -eq 1 ] || usage; self_test || exit 1 ;;
  *) usage ;;
esac
