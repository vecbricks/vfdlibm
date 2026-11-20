#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Checks the library's jar from outside, as a consumer has it:
#
#   dev/jar-check.sh [<jar>]
#
# <jar> defaults to the one jar under target/ that is not a sources, javadoc or tests jar, so
# after `mvn -B verify` (or `mvn -B -DskipTests package`) it needs no argument; a jar downloaded
# from Central is checked by naming it. The checks:
#
#   1. The jar holds META-INF/LICENSE and META-INF/NOTICE, byte for byte the files at the root of
#      this checkout, and NOTICE names Sun Microsystems: compiled classes carry no notice.
#   2. Its manifest names the module, Automatic-Module-Name: io.github.vecbricks.vfdlibm, and the
#      jar has no module-info.class beside that name.
#   3. Every class in it is in io/github/vecbricks/vfdlibm/, and VFdlibm.class is there; a
#      benchmark, a test or a dependency's class in the jar fails here. Nothing else is in it but
#      the manifest, the two licence files and Maven's META-INF/maven/.
#   4. A consumer compiled against the jar alone, with the module jdk.incubator.vector, runs and
#      finds every lane of every public function equal to StrictMath's by raw bits, in the plain
#      and the masked form, at the preferred species. It is compiled and run twice: with the jar
#      on the class path, and as a module that requires io.github.vecbricks.vfdlibm with the jar
#      on the module path.
#
# The consumer is no test of a function: the suite is. It shows that the jar loads and links for
# somebody who has nothing else of this project. It needs a JDK (jar, javac, java; JAVA_HOME's if
# that is set, else PATH's) and cmp, and writes under a temporary directory it removes. Exit
# status: 0 when every check passed, 1 when one failed (each failure is printed, and every check
# runs), 2 when it could not run.
set -uo pipefail

MODULE=io.github.vecbricks.vfdlibm
PACKAGE_DIR=io/github/vecbricks/vfdlibm
VECTOR_MODULE=jdk.incubator.vector

die() { echo "jar-check: $*" >&2; exit 2; }

case "${1:-}" in
  -h|--help) sed -n '/^# Checks the library/,/^[^#]/p' "$0" | sed '$d'; exit 0 ;;
esac
[ $# -le 1 ] || die "usage: dev/jar-check.sh [<jar>]"

root="$(cd "$(dirname "$0")/.." && pwd)" || die "cannot find the checkout"

tool() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1" ]; then
    echo "$JAVA_HOME/bin/$1"
  else
    command -v "$1"
  fi
}
JAR="$(tool jar)" || die "no jar on PATH and none under JAVA_HOME"
JAVAC="$(tool javac)" || die "no javac on PATH and none under JAVA_HOME"
JAVA="$(tool java)" || die "no java on PATH and none under JAVA_HOME"

if [ $# -eq 1 ]; then
  jar="$1"
else
  found=()
  for candidate in "$root"/target/*.jar; do
    case "$candidate" in
      *-sources.jar|*-javadoc.jar|*-tests.jar|*-test-sources.jar) ;;
      *) [ -f "$candidate" ] && found+=("$candidate") ;;
    esac
  done
  [ ${#found[@]} -eq 1 ] \
    || die "expected one jar under target/, found ${#found[@]}; build it or name the jar"
  jar="${found[0]}"
fi
[ -f "$jar" ] || die "no such jar: $jar"
jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"

work="$(mktemp -d)" || die "cannot make a temporary directory"
trap 'rm -rf "$work"' EXIT

failures=0
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }
ok() { echo "ok    $*"; }

echo "jar-check: $jar"
"$JAVA" -version 2>&1 | sed -n '1s/^/jar-check: /p'

"$JAR" tf "$jar" > "$work/listing" || die "cannot list $jar"
mkdir "$work/unpacked" || die "cannot unpack"
(cd "$work/unpacked" && "$JAR" xf "$jar") || die "cannot unpack $jar"

# 1. The licence files.
for name in LICENSE NOTICE; do
  if ! grep -qx "META-INF/$name" "$work/listing"; then
    fail "META-INF/$name is not in the jar"
  elif ! cmp -s "$root/$name" "$work/unpacked/META-INF/$name"; then
    fail "META-INF/$name differs from this checkout's $name"
  else
    ok "META-INF/$name is this checkout's $name"
  fi
done
if grep -q "Sun Microsystems" "$work/unpacked/META-INF/NOTICE" 2>/dev/null; then
  ok "META-INF/NOTICE carries Sun's notice"
else
  fail "META-INF/NOTICE does not name Sun Microsystems"
fi

# 2. The module's name. A manifest line ends in CR LF.
manifest="$work/unpacked/META-INF/MANIFEST.MF"
if tr -d '\r' < "$manifest" 2>/dev/null | grep -qx "Automatic-Module-Name: $MODULE"; then
  ok "the manifest says Automatic-Module-Name: $MODULE"
else
  fail "the manifest has no line Automatic-Module-Name: $MODULE"
fi
if grep -q '\(^\|/\)module-info\.class$' "$work/listing"; then
  fail "the jar has a module-info.class beside its Automatic-Module-Name"
else
  ok "no module-info.class"
fi

# 3. The classes, and what else is there.
classes="$(grep -c '\.class$' "$work/listing")"
strangers="$(grep '\.class$' "$work/listing" | grep -v "^$PACKAGE_DIR/[^/]*\.class$")"
if [ -n "$strangers" ]; then
  fail "classes outside $PACKAGE_DIR/:"
  echo "$strangers" | sed 's/^/        /'
elif ! grep -qx "$PACKAGE_DIR/VFdlibm.class" "$work/listing"; then
  fail "$PACKAGE_DIR/VFdlibm.class is not in the jar"
else
  ok "$classes classes, all in $PACKAGE_DIR/"
fi
others="$(grep -v '/$' "$work/listing" | grep -v '\.class$' \
  | grep -v -x -e META-INF/MANIFEST.MF -e META-INF/LICENSE -e META-INF/NOTICE \
  | grep -v "^META-INF/maven/")"
if [ -n "$others" ]; then
  fail "files that are neither classes, the manifest, the licence files nor Maven's metadata:"
  echo "$others" | sed 's/^/        /'
else
  ok "nothing else but the manifest, the licence files and META-INF/maven/"
fi

# 4. The consumer, on the class path and as a module.
mkdir -p "$work/src/consumer" "$work/classpath" "$work/modulepath" || die "cannot write"
cat > "$work/src/consumer/Consumer.java" <<'JAVA'
package consumer;

import io.github.vecbricks.vfdlibm.VFdlibm;
import java.util.function.DoubleUnaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/** Uses the jar as a consumer would, and compares its lanes with StrictMath by raw bits. */
public final class Consumer {

  private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

  /** Common values, the functions' edges, and values some function is not defined on. */
  private static final double[] INPUTS = {
    0.5, 1.0, -1.0, 2.5, 1e-3, 37.25, 0.0, -0.0, 700.0, -700.0, 710.0, -746.0, 1e-300, 1e300,
    -0.25, -0.999, 1e-20, -1e-20, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN,
    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.6931471805599453,
  };

  private interface Plain {
    DoubleVector apply(DoubleVector x);
  }

  private interface Masked {
    DoubleVector apply(DoubleVector x, VectorMask<Double> live);
  }

  private static int lanes;
  private static int wrong;

  private static void check(String name, Plain plain, Masked masked, DoubleUnaryOperator oracle) {
    int length = SPECIES.length();
    double[] in = new double[length];
    boolean[] live = new boolean[length];
    for (int from = 0; from < INPUTS.length; from += length) {
      for (int i = 0; i < length; i++) {
        in[i] = INPUTS[(from + i) % INPUTS.length];
        live[i] = (from + i) % 3 != 0;
      }
      DoubleVector x = DoubleVector.fromArray(SPECIES, in, 0);
      double[] got = plain.apply(x).toArray();
      double[] gotMasked = masked.apply(x, VectorMask.fromArray(SPECIES, live, 0)).toArray();
      for (int i = 0; i < length; i++) {
        double expected = oracle.applyAsDouble(in[i]);
        same(name, in[i], got[i], expected);
        // Only the live lanes: what a lane that is not live holds is the suite's to check.
        if (live[i]) {
          same(name + " (masked)", in[i], gotMasked[i], expected);
        }
      }
    }
  }

  private static void same(String name, double in, double got, double expected) {
    lanes++;
    if (Double.doubleToRawLongBits(got) != Double.doubleToRawLongBits(expected)) {
      wrong++;
      System.out.printf("%s(%s): got %016x, StrictMath %016x%n", name, Double.toString(in),
          Double.doubleToRawLongBits(got), Double.doubleToRawLongBits(expected));
    }
  }

  /** The one argument is the module the library must be in, empty for the unnamed module. */
  public static void main(String[] args) {
    check("exp", VFdlibm::exp, VFdlibm::exp, StrictMath::exp);
    check("expm1", VFdlibm::expm1, VFdlibm::expm1, StrictMath::expm1);
    check("log", VFdlibm::log, VFdlibm::log, StrictMath::log);
    check("log10", VFdlibm::log10, VFdlibm::log10, StrictMath::log10);
    check("log1p", VFdlibm::log1p, VFdlibm::log1p, StrictMath::log1p);
    Module module = VFdlibm.class.getModule();
    String in = module.isNamed() ? module.getName() : "";
    System.out.printf("%d lanes at %d bits, %d wrong; the library is in %s%n", lanes,
        SPECIES.vectorBitSize(), wrong, in.isEmpty() ? "the unnamed module" : "module " + in);
    if (!args[0].equals(in)) {
      System.out.printf("expected the library in module \"%s\"%n", args[0]);
      System.exit(1);
    }
    System.exit(wrong == 0 && lanes > 0 ? 0 : 1);
  }

  private Consumer() {}
}
JAVA
cat > "$work/src/module-info.java" <<JAVA
module consumer {
  requires $MODULE;
  requires $VECTOR_MODULE;
}
JAVA

# What javac and java print, less their warning that an incubating module is in use.
show() { grep -v "incubator" "$work/log" | sed 's/^/        /'; }

if "$JAVAC" --release 25 --add-modules "$VECTOR_MODULE" -cp "$jar" -d "$work/classpath" \
    "$work/src/consumer/Consumer.java" > "$work/log" 2>&1 \
  && "$JAVA" --add-modules "$VECTOR_MODULE" -cp "$jar:$work/classpath" consumer.Consumer "" \
    >> "$work/log" 2>&1; then
  ok "a consumer on the class path gets StrictMath's bits"
else
  fail "the consumer on the class path:"
fi
show

if "$JAVAC" --release 25 --module-path "$jar" -d "$work/modulepath" \
    "$work/src/module-info.java" "$work/src/consumer/Consumer.java" > "$work/log" 2>&1 \
  && "$JAVA" --module-path "$jar:$work/modulepath" -m consumer/consumer.Consumer "$MODULE" \
    >> "$work/log" 2>&1; then
  ok "a consumer module that requires $MODULE gets StrictMath's bits"
else
  fail "the consumer on the module path:"
fi
show

if [ "$failures" -ne 0 ]; then
  echo "jar-check: $failures failed"
  exit 1
fi
echo "jar-check: passed"
