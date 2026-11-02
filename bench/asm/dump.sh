#!/bin/sh
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Dumps C2's code for AsmDriver's exp loop and counts its hot path, in one of two modes:
#   avx512  512 bits on the host's own flags (native AVX-512 on the owner's laptop)
#   avx2    256 bits compiled as an AVX2 host such as the EPYC 7763 (-XX:UseAVX=2
#           -XX:MaxVectorSize=32)
# Usage: bench/asm/dump.sh <avx512|avx2> <classes dir> <out dir> [hsdis dir]
# <classes dir> holds vfdlibm's compiled main classes (target/classes after `mvn compile`).
set -eu
mode=$1
classes=$2
out=$3
hsdis=${4:-$HOME/hsdis}
here=$(cd "$(dirname "$0")" && pwd)
case $mode in
  avx512) flags="-Dasm.bits=512" ;;
  avx2) flags="-Dasm.bits=256 -XX:UseAVX=2 -XX:MaxVectorSize=32" ;;
  *) echo "mode must be avx512 or avx2" >&2; exit 2 ;;
esac
mkdir -p "$out/driver"
javac --add-modules jdk.incubator.vector -cp "$classes" -d "$out/driver" "$here/AsmDriver.java"
# shellcheck disable=SC2086
LD_LIBRARY_PATH=$hsdis java --add-modules jdk.incubator.vector $flags \
  -XX:+UnlockDiagnosticVMOptions '-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*' \
  '-XX:CompileCommand=print,AsmDriver::loopExp' -XX:PrintAssemblyOptions=intel \
  -XX:-TieredCompilation -cp "$classes:$out/driver" AsmDriver > "$out/asm-$mode.txt" 2>&1
python3 "$here/census.py" "$out/asm-$mode.txt" > "$out/census-$mode.txt"
head -10 "$out/census-$mode.txt"
