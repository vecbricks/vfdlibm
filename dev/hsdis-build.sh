#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Builds hsdis, the disassembler plugin HotSpot loads to print compiled code, so that the
# assembly check (ExpAsmTest, SKILLS.md "The assembly check") can read what C2 emits for a port's
# arms. The idea is Varka's dev/varka_hsdis_build.sh; this script is written for vfdlibm.
#
#   dev/hsdis-build.sh                    # fetch the two pinned source files, build, check
#   dev/hsdis-build.sh --src <jdk tree>   # take them from a JDK source tree instead
#   dev/hsdis-build.sh --capstone-source  # build Capstone too, even if the system's would do
#   dev/hsdis-build.sh --check <dir>      # only check that HotSpot loads <dir>'s hsdis
#
# hsdis is a tool and nothing of it is vfdlibm's: its source is OpenJDK's, under the GPL, and is
# never committed here, and neither is the library built from it; both stay under target/, which
# git ignores and no jar holds. The source is two files of the jdk-25-ga tag of openjdk/jdk,
# src/utils/hsdis/capstone/hsdis-capstone.c and src/utils/hsdis/hsdis.h, fetched one by one and
# checked against the SHA-256 pinned below; they are compiled, not read. A product JVM finds
# hsdis-<arch>.so on LD_LIBRARY_PATH; no JDK is built.
#
# The disassembler behind them is Capstone (BSD), version 5 or later. The system's is used when
# its capstone.h says 5 or more (apt install libcapstone-dev, Ubuntu 25.04 and later), and the
# build takes gcc and a second. Otherwise, or with --capstone-source, Capstone's pinned release is
# fetched, checked against its SHA-256 and built with cmake under target/hsdis/capstone, for this
# host's architecture only, and linked in statically. Version 4 will not do: Ubuntu 24.04's
# 4.0.2, which GitHub's ubuntu-latest has, printed an AVX-512 body of exp at 256 bits with
# ".byte 0x62" and garbage after it, a "call" among the garbage (SKILLS.md, "The assembly check").
#
# The library goes to target/hsdis/hsdis-amd64.so on x86-64 and target/hsdis/hsdis-aarch64.so on
# Arm (or --out <dir>). Then the check runs: a JVM with LD_LIBRARY_PATH=<dir> prints one compiled
# method of a two-line class of this script's own, and the build passes only if the output holds
# a [Disassembly] section with an instruction line in it. Without a disassembler HotSpot prints
# raw hex instead and does not fail, so the exit status of that JVM would prove nothing.
set -euo pipefail
usage() { sed -n '/^# Builds hsdis/,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

tag=jdk-25-ga
# SHA-256 of the two files at $tag.
sha_c=e805c9bb6a9bff3de2ac59870b00409b350e8dc147b77336e5e6f7e6b5d76b74
sha_h=a7fe8a90a1dbe55bd9ba08e82ee6b519965f7f2457f0f588c960b8b1b2d2e976
# Capstone's release, and the SHA-256 of GitHub's archive of its tag.
capstone=5.0.7
sha_capstone=6427a724726d161d1e05fb49fff8cd0064f67836c04ffca3c11d6d859e719caa
root=$(cd "$(dirname "$0")/.." && pwd)
src=""
out="$root/target/hsdis"
check_only=""
capstone_source=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --src) src="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --capstone-source) capstone_source=yes; shift ;;
    --check) check_only="$2"; shift 2 ;;
    *) usage ;;
  esac
done

case "$(uname -m)" in
  x86_64) arch=amd64; cs_arch=CS_ARCH_X86; cs_mode=CS_MODE_64 ;;
  aarch64) arch=aarch64; cs_arch=CS_ARCH_ARM64; cs_mode=CS_MODE_ARM ;;
  *) echo "hsdis-build: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

# Passes when a JVM given $1 on LD_LIBRARY_PATH prints real instructions for a compiled method.
check_loads() {
  local dir=$1 work
  if [ ! -f "$dir/hsdis-$arch.so" ]; then
    echo "hsdis-build: no hsdis-$arch.so in $dir" >&2
    return 1
  fi
  work=$(mktemp -d)
  cat > "$work/HsdisCheck.java" << 'EOF'
public class HsdisCheck {
  static int twice(int x) { return x + x; }
  public static void main(String[] args) {
    int s = 0;
    for (int i = 0; i < 20000; i++) s += twice(i);
    System.out.println(s);
  }
}
EOF
  LD_LIBRARY_PATH="$dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$java_home/bin/java" -Xbatch \
    -XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=quiet \
    -XX:CompileCommand=print,HsdisCheck::twice "$work/HsdisCheck.java" > "$work/out.txt" 2>&1 \
    || { cat "$work/out.txt" >&2; rm -rf "$work"; return 1; }
  # An instruction line inside [Disassembly]: an address, a tab-separated mnemonic that a hex
  # word cannot be (HotSpot's raw dump also has the 0x<address>: shape).
  if awk '/^\[Disassembly\]/ { inside = 1 } /^\[\/Disassembly\]/ { inside = 0 }
          inside && /^ *0x[0-9a-f]+: +(push|pushq|sub|subq|mov|movl|movq|ret|retq|stp|ldr|add)[ \t]/ { found = 1 }
          END { exit found ? 0 : 1 }' "$work/out.txt"; then
    rm -rf "$work"
    return 0
  fi
  echo "hsdis-build: HotSpot did not disassemble with $dir/hsdis-$arch.so; its output:" >&2
  head -40 "$work/out.txt" >&2
  rm -rf "$work"
  return 1
}

if [ -n "$check_only" ]; then
  check_loads "$check_only"
  echo "HotSpot loads $check_only/hsdis-$arch.so"
  exit 0
fi

command -v gcc > /dev/null || { echo "hsdis-build: gcc not found" >&2; exit 1; }
[ -f "$java_home/include/jni.h" ] \
  || { echo "hsdis-build: no jni.h under $java_home/include" >&2; exit 1; }
mkdir -p "$out"

# The system's Capstone if it is 5 or later, else the pinned release built here.
system_major=$(sed -n 's/^#define CS_API_MAJOR \([0-9]*\).*/\1/p' \
  /usr/include/capstone/capstone.h 2> /dev/null || true)
if [ -z "$capstone_source" ] && [ "${system_major:-0}" -ge 5 ]; then
  echo "using the system's Capstone (API $system_major)"
  cs_include=/usr/include/capstone
  cs_link=(-lcapstone)
else
  echo "building Capstone $capstone (the system's: ${system_major:-none})"
  command -v cmake > /dev/null || { echo "hsdis-build: cmake not found" >&2; exit 1; }
  cs="$out/capstone"
  rm -rf "$cs"
  mkdir -p "$cs"
  curl -fsSL --retry 3 -o "$cs/capstone.tar.gz" \
    "https://github.com/capstone-engine/capstone/archive/refs/tags/$capstone.tar.gz"
  echo "$sha_capstone  $cs/capstone.tar.gz" | sha256sum -c --quiet - \
    || { echo "hsdis-build: Capstone's archive does not match its pinned SHA-256" >&2; exit 1; }
  tar -xzf "$cs/capstone.tar.gz" -C "$cs"
  cmake -S "$cs/capstone-$capstone" -B "$cs/build" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCAPSTONE_BUILD_TESTS=OFF -DCAPSTONE_BUILD_CSTOOL=OFF -DCAPSTONE_ARCHITECTURE_DEFAULT=OFF \
    -DCAPSTONE_X86_SUPPORT=ON -DCAPSTONE_ARM64_SUPPORT=ON > "$cs/cmake.log" 2>&1 \
    || { cat "$cs/cmake.log" >&2; exit 1; }
  cmake --build "$cs/build" -j "$(nproc)" > "$cs/build.log" 2>&1 \
    || { tail -40 "$cs/build.log" >&2; exit 1; }
  cs_include="$cs/capstone-$capstone/include/capstone"
  cs_link=("$cs/build/libcapstone.a")
fi

hsdis="$out/src"
rm -rf "$hsdis"
mkdir -p "$hsdis/capstone"
if [ -n "$src" ]; then
  cp "$src/src/utils/hsdis/capstone/hsdis-capstone.c" "$hsdis/capstone/"
  cp "$src/src/utils/hsdis/hsdis.h" "$hsdis/"
else
  base="https://raw.githubusercontent.com/openjdk/jdk/$tag/src/utils/hsdis"
  curl -fsSL --retry 3 -o "$hsdis/capstone/hsdis-capstone.c" "$base/capstone/hsdis-capstone.c"
  curl -fsSL --retry 3 -o "$hsdis/hsdis.h" "$base/hsdis.h"
fi
# A tree given with --src may be another version: its files are used, and a mismatch is said.
status=0
echo "$sha_c  $hsdis/capstone/hsdis-capstone.c" | sha256sum -c --quiet - || status=$?
echo "$sha_h  $hsdis/hsdis.h" | sha256sum -c --quiet - || status=$?
if [ "$status" -ne 0 ]; then
  if [ -z "$src" ]; then
    echo "hsdis-build: the fetched files do not match the SHA-256 pinned for $tag" >&2
    exit 1
  fi
  echo "hsdis-build: $src's files are not $tag's; building them anyway" >&2
fi

# A shared object against Capstone, told the host's architecture and mode; jni.h for hsdis.h.
gcc -shared -fPIC -O2 -I"$hsdis" -I"$cs_include" \
  -I"$java_home/include" -I"$java_home/include/linux" \
  -DCAPSTONE_ARCH="$cs_arch" -DCAPSTONE_MODE="$cs_mode" \
  -o "$out/hsdis-$arch.so" "$hsdis/capstone/hsdis-capstone.c" "${cs_link[@]}"
echo "built $out/hsdis-$arch.so"
check_loads "$out"
echo "HotSpot loads it; the assembly check finds it in target/hsdis (or set VFDLIBM_HSDIS_DIR=$out)"
