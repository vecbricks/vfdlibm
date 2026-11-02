#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Dispatches .github/workflows/benchmark.yml and waits for it. When the run failed on its CPU
# check (the runner was not an AMD EPYC 7763), it waits an hour and dispatches again; it stops on
# success, or on a failure anywhere else. The expected CPU is never relaxed (SKILLS.md, "Where
# headline numbers come from"). Run it detached, for example with nohup, and read its log.
#
#   bench/dispatch-epyc.sh <ref> [benchmark regex] [extra JVM options]
#
# Each dispatch carries a tag of its own as the run-tag input, which benchmark.yml puts in the
# run's name, and the script follows the run of that name; so two dispatchers on one branch, or
# a clock that differs from GitHub's, cannot make it follow the wrong run. The <ref> must have
# the run-tag input; on an older one the dispatch fails and the script says so. A gh call that
# fails is tried five times, a minute apart. Finding the run and waiting for it are bounded
# (ten minutes, about seven hours plus retries); the hourly CPU retry is not, by design.
# Exit status: 0 on success, 1 when the run failed other than on the CPU check, 2 when GitHub
# could not be asked.
#
# Then fetch the results: gh run download <run id> -R vecbricks/vfdlibm -n bench-results

R=vecbricks/vfdlibm; B=${1:?usage: bench/dispatch-epyc.sh <ref> [benchmark regex] [jvm args]}
N=${2:-ExpBenchmark}; X=${3:-}
t() { date +%H:%M; }
# Runs a gh command up to five times, a minute apart, until it succeeds, and prints only the
# successful attempt's output: a failed gh api call prints its error body to stdout, which
# must not be read as the answer.
try() {
  local i out; for i in 1 2 3 4 5; do
    out=$("$@") && { [ -n "$out" ] && printf '%s\n' "$out"; return 0; }
    echo "$(t) gh failed, try $i of 5: $*" >&2; [ $i -lt 5 ] && sleep 60
  done; return 1; }
# The id of the run named "Benchmark <tag>", if GitHub lists one.
run_of() {
  gh run list -R $R --workflow benchmark.yml --event workflow_dispatch -L 50 \
    --json databaseId,displayTitle \
    --jq ".[]|select(.displayTitle == \"Benchmark $1\")|.databaseId" | head -1; }
bench() {
  while :; do tag="dispatch-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
    # A dispatch whose reply was lost may still have started a run, so before each retry look
    # for a run with this tag, and dispatch again only if there is none.
    d=0; for i in 1 2 3 4 5; do
      gh workflow run benchmark.yml -R $R --ref "$B" -f benchmarks="$N" -f jvm-args="$X" \
        -f run-tag="$tag" && { d=1; break; }
      echo "$(t) dispatch failed, try $i of 5 (an older ref has no run-tag input)" >&2
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
    echo "$(t) dispatched $B $N benchmark $m ($tag)"
    # A job runs for six hours at most; seven hours covers the queue too.
    s=""; for _ in $(seq 210); do
      s=$(try gh api repos/$R/actions/runs/$m --jq .status) || s=""
      [ "$s" = completed ] && break; sleep 120; done
    if [ "$s" != completed ]; then
      echo "$(t) run $m not completed after seven hours (status '$s'), stopping"; return 2; fi
    if ! c=$(try gh api repos/$R/actions/runs/$m --jq .conclusion); then
      echo "$(t) could not read run $m's conclusion, stopping"; return 2; fi
    echo "$(t) $B $N benchmark $m: $c"
    [ "$c" = success ] && return 0
    # The failed step's name comes from the jobs API: a failed log can label every line
    # "UNKNOWN STEP", which made a log grep miss a CPU-check failure and stop. An API error is
    # tried again, not read as a failure elsewhere.
    if ! failed=$(try gh api "repos/$R/actions/runs/$m/jobs" \
        --jq '.jobs[].steps[]|select(.conclusion=="failure")|.name'); then
      echo "$(t) could not read run $m's jobs, stopping"; return 2; fi
    if grep -q '^Probe the machine' <<<"$failed"; then
      echo "$(t) $N: CPU check failed, retry in an hour"; sleep 3600
    else return 1; fi
  done; }
bench
