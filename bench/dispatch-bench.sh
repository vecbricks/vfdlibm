#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Dispatches .github/workflows/benchmark.yml on one benchmark host and waits for it. When the run
# failed on its CPU check (the runner was not the host's CPU), it waits an hour and dispatches
# again; it stops on success, on a failure anywhere else, or after --attempts dispatches. One
# other failure is retried the same way: a run that failed while fetching the check's script,
# before any step looked at the machine; the third such run in a row stops the script. The
# expected CPU is never relaxed (SKILLS.md, "Where headline numbers come from"). Run it
# detached, for example with nohup, and read its log.
#
#   bench/dispatch-bench.sh [--host <host>] [--attempts <n>] [--base <base>] <ref>
#                           [benchmark regex] [extra JVM options]
#
# The host is one of epyc7763 (AMD EPYC 7763, the default), xeon8573c (Intel Xeon Platinum
# 8573C), xeon6973p (Intel Xeon 6973P-C), epyc9v45 (AMD EPYC 9V45) or neoversen2 (Arm Neoverse
# N2, GitHub's Arm runner, which has no timing result yet); bench/host.sh holds the keys and
# each host's CPU string and runner. The keys are checked against this checkout's table, and
# the run checks its machine against the <ref>'s, so the two should hold the same hosts.
# bench/dispatch-epyc.sh is this script with the default host, kept for the calls written
# before the other hosts.
#
# --attempts bounds the dispatches. For the three hosts that results wait on, it defaults to none
# (the hourly retry goes on until the host is drawn). For epyc9v45, a draw of about one in eighteen
# that is recorded whenever drawn but never waited for, it defaults to 18: an hourly try for
# about a day, which draws it at least once with a chance of about 64%; the check
# comes first in the job, after only the fetch of its script and that script's own test, and
# what a miss costs on the current workflow has not been measured. --attempts 1 is a single
# opportunistic try. Running out of attempts on a CPU miss is exit status 3, not a failure of
# the run; when the last attempt failed before the CPU check, it is 1. For neoversen2 it
# defaults to 3: the Arm label gave one CPU in three probe draws (HARDWARE.md), so a miss most
# likely means the pool changed or the mapping is wrong, which hours of tries do not mend.
#
# --base <base> makes each run a pair (bench/pair.sh in the workflow): the benchmarks on <base>
# and on the dispatched <ref> as head, alternating, on one machine. <base> is a branch or tag of
# origin, a commit on one of origin's branches (as this clone last fetched them), or HEAD for an
# A/A pair. It is checked here, before the first dispatch, so that a typo fails at once rather
# than in a run hours of CPU retries away (exit status 64; 2 when origin could not be asked).
#
# Each dispatch carries a tag of its own, which names the host, as the run-tag input, which
# benchmark.yml puts in the run's name, and the script follows the run of that name; so two
# dispatchers on one branch, or a clock that differs from GitHub's, cannot make it follow the
# wrong run. The <ref> must have the run-tag and host inputs, and the base input with --base; on
# an older one the dispatch fails and the script says so. A gh call that fails is tried five
# times, a minute apart. Finding the run and waiting for it are bounded (ten minutes, about seven
# hours plus retries); the hourly CPU retry is bounded only by --attempts, by design. A run that
# failed in its fetch of bench/host.sh, so that the steps after it, the one that checks the
# fetched script and the CPU check, were skipped, says nothing about the machine, so it is
# retried an hour later like a miss, and counts as an attempt; three such runs in a row stop
# the script. A script that was fetched and cannot be run, or does not know the host, fails
# that step of its own and is not retried. Exit status: 0 on success, 1 when the run
# failed other than on the CPU check (for a pair, also when a row is slower), or before it three
# times in a row or on the last attempt, 2 when GitHub could not be asked or bench/host.sh,
# which must sit beside this script, could not be run, 3 when --attempts ran out without drawing
# the host, 64 on a usage error.
#
# Then make the results file: bench/record.py write --run <run id> downloads the run's artifact
# and writes one file a benchmark class (<function>-<host>-<date>.txt; a pair's is named
# <function>-pair-...) but for its title, reason and reading, and bench/record.py check <file>
# --run <run id> checks the file before it is committed (AGENTS.md, "Measurements, not
# adjectives"; bench/record.py's header says the rest).

R=vecbricks/vfdlibm; H=epyc7763; A=""; P=""
# The host keys are bench/host.sh's, the table benchmark.yml checks the machine against, read
# once the options have parsed, so that an unknown option needs no other file. The table is
# beside this script, as check_base's git is: the script is run by its path in the repository.
KEYS=""
usage() {
  local hosts="<a key of bench/host.sh>"
  [ -z "$KEYS" ] || hosts=$(tr '\n' '|' <<<"$KEYS" | sed 's/|$//')
  echo "usage: bench/dispatch-bench.sh [--host $hosts]" \
    "[--attempts n] [--base ref] <ref> [benchmark regex] [jvm args]" >&2; exit 64; }
while [ $# -gt 0 ]; do
  case "$1" in
    --host) [ $# -ge 2 ] || usage; H=$2; shift 2 ;;
    --host=*) H=${1#--host=}; shift ;;
    --attempts) [ $# -ge 2 ] || usage; A=$2; shift 2 ;;
    --attempts=*) A=${1#--attempts=}; shift ;;
    --base) [ $# -ge 2 ] || usage; P=$2; shift 2 ;;
    --base=*) P=${1#--base=}; shift ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done
# A key of bench/host.sh's table, so a typo stops here and not at GitHub.
KEYS=$(bash "$(dirname "$0")/host.sh" --keys) || {
  echo "could not read bench/host.sh's keys" >&2; exit 2; }
known=0; for k in $KEYS; do [ "$k" = "$H" ] && known=1; done
[ $known = 1 ] || { echo "unknown host '$H'" >&2; usage; }
[ "$H" = epyc9v45 ] && A=${A:-18}
[ "$H" = neoversen2 ] && A=${A:-3}
case "$A" in "") ;; *[!0-9]*|0) echo "--attempts takes a positive number" >&2; usage ;; esac
[ $# -ge 1 ] && [ $# -le 3 ] || usage
B=$1; N=${2:-ExpBenchmark}; X=${3:-}
t() { date +%H:%M; }
# Runs a gh command up to five times, a minute apart, until it succeeds, and prints only the
# successful attempt's output: a failed gh api call prints its error body to stdout, which
# must not be read as the answer.
try() {
  local i out; for i in 1 2 3 4 5; do
    out=$("$@") && { [ -n "$out" ] && printf '%s\n' "$out"; return 0; }
    echo "$(t) gh failed, try $i of 5: $*" >&2; [ $i -lt 5 ] && sleep 60
  done; return 1; }
# The id of the run named "Benchmark <tag>", if GitHub lists one. Anything but a number (a
# failed call's error body) is no id.
run_of() {
  local id
  id=$(gh run list -R "$R" --workflow benchmark.yml --event workflow_dispatch -L 50 \
    --json databaseId,displayTitle \
    --jq ".[]|select(.displayTitle == \"Benchmark $1\")|.databaseId" | head -1)
  [[ $id =~ ^[0-9]+$ ]] && printf '%s\n' "$id"; }
# Whether the --base would resolve in the run: benchmark.yml takes a commit or a branch of its
# checkout, which has every branch and tag of origin. A name is asked of origin itself; a commit
# must be one that this clone knows to be on an origin branch.
check_base() {
  local g=(git -C "$(dirname "$0")") rc sha
  [ "$P" = HEAD ] && return 0
  "${g[@]}" ls-remote --exit-code origin "refs/heads/$P" "refs/tags/$P" > /dev/null; rc=$?
  [ $rc -eq 0 ] && return 0
  if [ $rc -ne 2 ]; then echo "could not ask origin whether base '$P' is on it" >&2; return 2; fi
  if [[ $P =~ ^[0-9a-fA-F]{4,40}$ ]] && sha=$("${g[@]}" rev-parse -q --verify "$P^{commit}") \
      && [ -n "$("${g[@]}" for-each-ref --count=1 --contains "$sha" refs/remotes/origin)" ]; then
    return 0
  fi
  echo "base '$P' is neither a branch or tag of origin nor a commit on an origin branch" >&2
  return 64; }
# The names of the run's failed steps, from $steps, on one line.
failed_steps() {
  local f; f=$(sed -n 's/^failure //p' <<<"$steps" | tr '\n' ';' | sed 's/;$//')
  printf '%s\n' "${f:-none}"; }
bench() {
  local n=0 early=0 probe
  while :; do n=$((n + 1)); tag="dispatch-$H-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
    # A dispatch whose reply was lost may still have started a run, so before each retry look
    # for a run with this tag, and dispatch again only if there is none.
    d=0; for i in 1 2 3 4 5; do
      gh workflow run benchmark.yml -R "$R" --ref "$B" -f host="$H" -f benchmarks="$N" \
        -f jvm-args="$X" ${P:+-f base="$P"} -f run-tag="$tag" && { d=1; break; }
      echo "$(t) dispatch failed, try $i of 5 (an older ref lacks an input)" >&2
      sleep 60; [ -n "$(run_of "$tag")" ] && { d=1; break; }
    done
    if [ $d = 0 ]; then
      echo "$(t) could not dispatch benchmark.yml on $B, stopping"; return 2; fi
    # The run appears within seconds as a rule; ten minutes without it is an error.
    m=""; for _ in $(seq 30); do sleep 20
      m=$(run_of "$tag")
      [ -n "$m" ] && break; done
    if [ -z "$m" ]; then
      echo "$(t) no run named \"Benchmark $tag\" after ten minutes, stopping"; return 2; fi
    echo "$(t) dispatched $B $N benchmark on $H${P:+ paired with $P}: $m ($tag)," \
      "attempt $n of ${A:-unbounded}"
    # A job runs for six hours at most; seven hours covers the queue too.
    s=""; for _ in $(seq 210); do
      s=$(try gh api "repos/$R/actions/runs/$m" --jq .status) || s=""
      [ "$s" = completed ] && break; sleep 120; done
    if [ "$s" != completed ]; then
      echo "$(t) run $m not completed after seven hours (status '$s'), stopping"; return 2; fi
    if ! c=$(try gh api "repos/$R/actions/runs/$m" --jq .conclusion); then
      echo "$(t) could not read run $m's conclusion, stopping"; return 2; fi
    echo "$(t) $B $N benchmark on $H, run $m: $c"
    [ "$c" = success ] && return 0
    # The failed step's name comes from the jobs API: a failed log can label every line
    # "UNKNOWN STEP", which made a log grep miss a CPU-check failure and stop. An API error is
    # tried again, not read as a failure elsewhere.
    if ! steps=$(try gh api "repos/$R/actions/runs/$m/jobs" \
        --jq '.jobs[].steps[]|"\(.conclusion) \(.name)"'); then
      echo "$(t) could not read run $m's jobs, stopping"; return 2; fi
    probe=$(sed -n "s/ Probe the machine's CPU\$//p" <<<"$steps" | head -1)
    if [ "$probe" = failure ]; then
      early=0
      if [ -n "$A" ] && [ "$n" -ge "$A" ]; then
        echo "$(t) $N: CPU check failed (not $H) on attempt $n of $A; no attempts left"
        return 3; fi
      echo "$(t) $N: CPU check failed (not $H), retry in an hour"; sleep 3600; continue
    fi
    # Retried only when, in a failed run, the fetch of the script failed and the two steps
    # after it were skipped: the machine is not known. Anything else is a failure not to be
    # retried: the check passed, the fetched script cannot be run or lacks the host, the run
    # was cancelled, a step other than the fetch failed before the check, or no job started (a
    # run with no steps, a startup failure).
    if grep -qx "failure Check the probe's script" <<<"$steps"; then
      echo "$(t) $N: run $m could not use $B's bench/host.sh; no benchmark ran, stopping"
      return 1; fi
    if [ "$probe" != skipped ] || [ "$c" != failure ] \
        || ! grep -qx "failure Fetch the probe's script" <<<"$steps" \
        || ! grep -qx "skipped Check the probe's script" <<<"$steps"; then
      echo "$(t) $N: run $m ended as $c, its failed steps: $(failed_steps); stopping"
      return 1; fi
    early=$((early + 1))
    if [ $early -ge 3 ]; then
      echo "$(t) $N: run $m failed before its CPU check (in: $(failed_steps)), the third in" \
        "a row, stopping"
      return 1; fi
    # Out of attempts on such a run is a failure, not status 3: no CPU was seen to be another.
    if [ -n "$A" ] && [ "$n" -ge "$A" ]; then
      echo "$(t) $N: run $m failed before its CPU check (in: $(failed_steps)) on attempt $n" \
        "of $A; no attempts left"
      return 1; fi
    echo "$(t) $N: run $m failed before its CPU check (in: $(failed_steps)), retry in an" \
        "hour"; sleep 3600
  done; }
if [ -n "$P" ]; then check_base || exit $?; fi
bench
