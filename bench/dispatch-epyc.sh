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
# Then fetch the results: gh run download <run id> -R vecbricks/vfdlibm -n bench-results

R=vecbricks/vfdlibm; B=${1:?usage: bench/dispatch-epyc.sh <ref> [benchmark regex] [jvm args]}
N=${2:-ExpBenchmark}; X=${3:-}
t() { date +%H:%M; }
bench() {
  while :; do since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    gh workflow run benchmark.yml -R $R --ref $B -f benchmarks="$N" -f jvm-args="$X"
    m=""; until [ -n "$m" ]; do sleep 20
      m=$(gh run list -R $R --workflow benchmark.yml --branch $B -L 5 \
        --json databaseId,createdAt \
        --jq ".[]|select(.createdAt >= \"$since\")|.databaseId" | head -1); done
    echo "$(t) dispatched $B $N benchmark $m"
    until [ "$(gh api repos/$R/actions/runs/$m --jq .status)" = completed ]; do sleep 120; done
    c=$(gh api repos/$R/actions/runs/$m --jq .conclusion); echo "$(t) $B $N benchmark $m: $c"
    [ "$c" = success ] && return 0
    # The failed step's name comes from the jobs API: a failed log can label every line
    # "UNKNOWN STEP", which made a log grep miss a CPU-check failure and stop.
    if gh api "repos/$R/actions/runs/$m/jobs" \
        --jq '.jobs[].steps[]|select(.conclusion=="failure")|.name' 2>/dev/null \
        | grep -q '^Probe the machine'; then
      echo "$(t) $N: CPU check failed, retry in an hour"; sleep 3600
    else return 1; fi
  done; }
bench
