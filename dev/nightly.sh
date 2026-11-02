#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# The nightly correctness runs, one JVM per species (src/test/java/.../Nightly.java says why):
#
#   dev/nightly.sh sweep <function> <parts> <seed> [<species>...]
#   dev/nightly.sh fuzz <function> <seconds> <seed> [<species>...]
#
# <function> is exp, log or log1p. A sweep checks the high words of <parts>: comma-separated
# parts i/N of the 2^32, or stripe:s/S for the stripe Sweep.java's --stripe takes. A fuzz runs
# <seconds> at each species (Fuzz.java). <seed> is decimal or 0x hex. The species default to the
# host's: 128, 256 and 512 bits up to the preferred species, and max (`Nightly species`). For
# example, a slice of exp at 256 bits:
#
#   mvn -B -q test-compile && dev/nightly.sh sweep exp 1023/4096 0x1234 256
#
# It needs the classes built (mvn test-compile). NIGHTLY_OUT is where each species' log and
# mismatch file go (default target/nightly; <mode>-<function>-<species>.log and .txt),
# NIGHTLY_THREADS the threads per JVM (default: every processor), and NIGHTLY_JVM_ARGS extra JVM
# options, such as -XX:UseAVX=2 or -Dvfdlibm.fault=exp.dropCorrection. Every species runs even
# after one fails. Exit status: 0 when every lane matched, 1 when some lane differed, 2 when a
# run failed to run (a usage error, or a harness failure), which outranks 1.
set -uo pipefail

usage() { sed -n '/^# The nightly correctness/,/^[^#]/p' "$0" | sed '$d'; exit 2; }
[ $# -ge 4 ] || usage
mode="$1" function="$2" amount="$3" seed="$4"
shift 4
case "$mode" in
  sweep)
    main=Sweep
    case "$amount" in
      stripe:*) what=(--stripe "${amount#stripe:}") ;;
      *) what=(--part "$amount") ;;
    esac ;;
  fuzz) main=Fuzz; what=(--seconds "$amount") ;;
  *) usage ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
cp="$root/target/classes:$root/target/test-classes"
if [ ! -f "$root/target/test-classes/io/github/vecbricks/vfdlibm/Sweep.class" ]; then
  echo "dev/nightly.sh: build the classes first: mvn -B -q test-compile" >&2
  exit 2
fi
out="${NIGHTLY_OUT:-$root/target/nightly}"
mkdir -p "$out"
threads="${NIGHTLY_THREADS:-$(nproc)}"
read -r -a jvm_args <<< "${NIGHTLY_JVM_ARGS:-}"
java_cmd=(java --add-modules jdk.incubator.vector "${jvm_args[@]}" -cp "$cp")

if [ $# -gt 0 ]; then
  species=("$@")
else
  read -r -a species <<< "$("${java_cmd[@]}" io.github.vecbricks.vfdlibm.Nightly species \
    2>/dev/null)"
  if [ ${#species[@]} -eq 0 ]; then
    echo "dev/nightly.sh: could not list the host's species" >&2
    exit 2
  fi
fi

status=0
for s in "${species[@]}"; do
  name="$mode-$function-$s"
  echo "== $name: ${java_cmd[*]} io.github.vecbricks.vfdlibm.$main ..."
  "${java_cmd[@]}" "io.github.vecbricks.vfdlibm.$main" --function "$function" --species "$s" \
    "${what[@]}" --seed "$seed" --threads "$threads" --out "$out/$name.txt" 2>&1 \
    | tee "$out/$name.log"
  code="${PIPESTATUS[0]}"
  echo "== $name: exit $code"
  if [ "$code" -ne 0 ] && { [ "$code" -ne 1 ] || [ "$status" -eq 0 ]; }; then
    if [ "$code" -eq 1 ]; then status=1; else status=2; fi
  fi
done
exit "$status"
