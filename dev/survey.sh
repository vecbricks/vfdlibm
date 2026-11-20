#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# The survey of one-step mutants (dev/survey/README.md): which changes of one step to a
# function's C the unit tests, a fuzz and the sweep would catch.
#
#   dev/survey.sh [<function>...]
#
# <function> is exp, expm1, log or log10; the default is all four. It runs the functions' unit
# tests once with -Dvfdlibm.survey.record, which makes Oracle write every input it is given to
# target/survey/inputs, and then src/test/java/.../Survey.java in two passes per function. The
# first counts every mutant and writes <out>/<function>.tsv. The second takes the mutants the
# first found on no input, one JVM run per mutant, with a longer fuzz, every stripe of the
# sweep and a longer search, and adds each one's row to <out>/<function>-uncaught.tsv.
#
# A run takes up where an earlier one stopped: a first-pass table that exists is kept, and a
# mutant with a row in the second table is not run again. Remove a table to make it anew. One
# JVM runs at a time, with a 2 GB heap. From the environment:
#
#   SURVEY_MVN          the mvn to run (default mvn)
#   SURVEY_OUT          where the tables go (default dev/survey)
#   SURVEY_SEED         the seed of the fuzz, the sweep's low words and the search (0x5eed)
#   SURVEY_THREADS      threads of the one JVM (default 2)
#   SURVEY_LOCK         a command each mvn and JVM run goes under, such as "flock <file>", on
#                       a machine shared with other heavy work; held per run, a few minutes
#   SURVEY_FUZZ         first pass: fuzz draws (default 67108864, 2^26)
#   SURVEY_STRIPE       first pass: the sweep's stripe s/S (default 0/64: 2^26 high words)
#   SURVEY_SEARCH       first pass: search draws (default 67108864)
#   SURVEY_DEEP_FUZZ    second pass: fuzz draws (default 1073741824, 2^30); 0 skips the pass
#   SURVEY_DEEP_STRIPE  second pass: the stripe (default 0/1, every high word, as a night)
#   SURVEY_DEEP_SEARCH  second pass: search draws (default 4294967296, 2^32)
#   SURVEY_INPUTS       a directory of inputs recorded before, to skip the test run
#
# Exit status: 0 when every table was written, 2 otherwise, also when a transcription with
# nothing changed differs from StrictMath, since no count means anything then.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2
mvn="${SURVEY_MVN:-mvn}"
out="${SURVEY_OUT:-dev/survey}"
seed="${SURVEY_SEED:-0x5eed}"
threads="${SURVEY_THREADS:-2}"
draws="${SURVEY_FUZZ:-67108864}"
stripe="${SURVEY_STRIPE:-0/64}"
search="${SURVEY_SEARCH:-67108864}"
deep_draws="${SURVEY_DEEP_FUZZ:-1073741824}"
deep_stripe="${SURVEY_DEEP_STRIPE:-0/1}"
deep_search="${SURVEY_DEEP_SEARCH:-4294967296}"
read -r -a lock <<< "${SURVEY_LOCK:-}"
if [ $# -gt 0 ]; then functions=("$@"); else functions=(exp expm1 log log10); fi

tests=""
for f in "${functions[@]}"; do
  case "$f" in
    exp) class=ExpTest ;;
    expm1) class=Expm1Test ;;
    log) class=LogTest ;;
    log10) class=Log10Test ;;
    *) echo "dev/survey.sh: no transcription of '$f'; known: exp, expm1, log, log10" >&2
       exit 2 ;;
  esac
  tests="$tests${tests:+,}$class"
done

if [ -n "${SURVEY_INPUTS:-}" ]; then
  inputs="$SURVEY_INPUTS"
  "${lock[@]}" "$mvn" -B -q test-compile || exit 2
else
  inputs="$root/target/survey/inputs"
  rm -rf "$inputs"
  # A -Dtest filter leaves the fault runs out (the profile one-test of pom.xml).
  "${lock[@]}" "$mvn" -B -q test -Dtest="$tests" \
    -Dvfdlibm.test.jvmArgs="-Dvfdlibm.survey.record=$inputs" || exit 2
fi

commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then commit="$commit with local changes"; fi
note="commit $commit; $(date -u +%Y-%m-%d); $(java -version 2>&1 | head -1)"

survey() {
  "${lock[@]}" java -Xmx2g --add-modules jdk.incubator.vector \
    -cp target/classes:target/test-classes io.github.vecbricks.vfdlibm.Survey \
    --suite "$inputs" --seed "$seed" --threads "$threads" --note "$note" "$@"
}

status=0
mkdir -p "$out"
for f in "${functions[@]}"; do
  if [ ! -s "$out/$f.tsv" ]; then
    rm -f "$out/$f-uncaught.tsv"
    survey --function "$f" --fuzz "$draws" --stripe "$stripe" --search "$search" \
      --out "$out/$f.tsv.part" && mv "$out/$f.tsv.part" "$out/$f.tsv" || { status=2; continue; }
  fi
  [ "$deep_draws" != 0 ] || continue
  # Each mutant whose caught_by column reads "none", unless the second table has its row.
  while IFS= read -r name; do
    if [ -s "$out/$f-uncaught.tsv" ] && cut -f1 "$out/$f-uncaught.tsv" | grep -qxF -- "$name"
    then
      continue
    fi
    survey --function "$f" --fuzz "$deep_draws" --stripe "$deep_stripe" \
      --search "$deep_search" --only "\\Q$name\\E" --append true \
      --out "$out/$f-uncaught.tsv" || status=2
  done < <(awk -F'\t' '!/^#/ && $9 == "none" { print $1 }' "$out/$f.tsv")
done
exit "$status"
