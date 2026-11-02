#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Describes the machine this runs on and measures its vector datapath. Adapted from
# vecbricks/varka's dev/varka_datapath.sh, the script of the same role, without its CPU gate:
# here the benchmark workflow's first step checks the CPU, and this only records.
#
#   bench/datapath.sh
#
# It prints the CPU model, the cores and their distinct maximum frequencies (a machine with two
# core types measures two machines averaged unless pinned), the vector flags (avx2, avx512f,
# avx512dq), the JVM's UseAVX, UseSVE and MaxVectorSize at the machine's own flags, and the
# probe bench/Datapath.java at 128, 256 and 512 bits in lane-operations per nanosecond, with the
# 256:128 ratio (the probe's control, about 2 wherever 256-bit vectors exist) and the 512:256
# ratio (about 2 on a full-width 512-bit datapath, about 1 on a double-pumped one; HARDWARE.md).
# The last line, "datapath: ...", is the one the benchmark workflow and bench/pair.sh copy into
# a results header. It needs a JDK 25 on the path and nothing built, and takes about ten seconds.
#
# Exit status: 0 with a usable reading; 1 when the probe printed no rate or its control is under
# 1.50, in which case the datapath line says the reading is unusable (no 512-bit verdict from a
# probe that cannot see the 128-to-256 doubling); 64 on a usage error.

set -uo pipefail
case "${1:-}" in
  "") ;;
  -h|--help) sed -n '10,27p' "$0"; exit 0 ;;
  *) echo "usage: bench/datapath.sh" >&2; exit 64 ;;
esac
here="$(cd "$(dirname "$0")" && pwd)"

flags="$(grep -m1 -E '^(flags|Features)' /proc/cpuinfo | sed 's/.*: //')"
has() { if grep -qw "$1" <<<"$flags"; then echo yes; else echo no; fi; }
echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
echo "cores: $(nproc); distinct core max MHz:" \
  "$(lscpu --extended=MAXMHZ 2>/dev/null | tail -n +2 | sort -u | tr '\n' ' ')"
echo "vector flags: avx2=$(has avx2) avx512f=$(has avx512f) avx512dq=$(has avx512dq);" \
  "all: $(tr ' ' '\n' <<<"$flags" | grep -E '^(avx|sve|asimd)' | sort -u | tr '\n' ' ')"
echo "jvm: $(java -XX:+PrintFlagsFinal -version 2>/dev/null \
  | grep -E ' (MaxVectorSize|UseAVX|UseSVE) ' | awk '{print $2 "=" $4}' | tr '\n' ' ')"

# One reading: the probe's best window at a vector cap of $1 bytes. A cap above the machine's
# widest vector gives its widest species, so the 512-bit reading of an AVX2 machine is its 256.
probe() {
  java --add-modules jdk.incubator.vector -XX:+IgnoreUnrecognizedVMOptions \
    "-XX:MaxVectorSize=$1" "$here/Datapath.java" 2>/dev/null \
    | sed -n 's/^lane_ops_per_ns=//p'
}
c16="$(probe 16)"; c32="$(probe 32)"; c64="$(probe 64)"
ratio() {
  awk -v a="$1" -v b="$2" 'BEGIN { if (a > 0 && b > 0) printf "%.2f", b / a; else print "-" }'
}
control="$(ratio "$c16" "$c32")"; wide="$(ratio "$c32" "$c64")"
line="${c16:--} / ${c32:--} / ${c64:--} lane-ops/ns at 128 / 256 / 512 bits;"
line="$line 256:128 = $control (control), 512:256 = $wide"
if [ "$control" = - ] || ! awk -v c="$control" 'BEGIN { exit !(c >= 1.50) }'; then
  echo "datapath: $line; unusable, the control is not about 2"
  exit 1
fi
echo "datapath: $line"
