#!/bin/sh
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Dumps C2's code for AsmDriver's exp loop as an AVX2 host compiles it and counts its hot path.
# Usage: bench/asm/dump.sh <classes dir> <out dir> [hsdis dir]
# <classes dir> holds vfdlibm's compiled main classes (target/classes after `mvn compile`).
set -eu
classes=$1
out=$2
hsdis=${3:-$HOME/hsdis}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out/driver"
javac --add-modules jdk.incubator.vector -cp "$classes" -d "$out/driver" "$here/AsmDriver.java"
LD_LIBRARY_PATH=$hsdis java --add-modules jdk.incubator.vector -XX:UseAVX=2 -XX:MaxVectorSize=32 \
  -XX:+UnlockDiagnosticVMOptions '-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*' \
  '-XX:CompileCommand=print,AsmDriver::loopExp' -XX:PrintAssemblyOptions=intel \
  -XX:-TieredCompilation -cp "$classes:$out/driver" AsmDriver > "$out/asm-avx2.txt" 2>&1
python3 "$here/census.py" "$out/asm-avx2.txt" > "$out/census.txt"
head -9 "$out/census.txt"
