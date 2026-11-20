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
#      this checkout, and NOTICE names Sun Microsystems: compiled classes carry no notice. A
#      sources jar or a Javadoc jar beside the jar (<name>-sources.jar, <name>-javadoc.jar, as
#      the release profile builds them) must hold the same two files.
#   2. Its manifest names the module, Automatic-Module-Name: io.github.vecbricks.vfdlibm, and the
#      jar has no module-info.class beside that name.
#   3. Its classes are the ones of this checkout's src/main/java, each source file's class and
#      the classes nested in it, all of them and no other; a benchmark, a test or a dependency's
#      class in the jar fails here, though the tests and benchmarks share the package. The
#      main sources are taken to be what they are today, one package with one top-level type a
#      file; a second package or a second type in a file fails here until this check learns it.
#      Nothing else is in the jar but the manifest, the two licence files and Maven's
#      META-INF/maven/.
#   4. A consumer compiled against the jar alone, with the module jdk.incubator.vector, runs and
#      finds every lane of every public function equal to StrictMath's by raw bits, in the plain
#      and the masked form, at the preferred species. It finds the functions by name through
#      reflection, and fails if VFdlibm has a public method, by name and number of parameters,
#      that it did not call, or if the jar has another public class. It is compiled and run
#      twice: with the jar on the class path, and as a module that requires
#      io.github.vecbricks.vfdlibm with the jar on the module path.
#
# The consumer is no test of a function: the suite is. It shows that the jar loads and links for
# somebody who has nothing else of this project. It needs a JDK that compiles --release 25 (jar,
# javac and java: all three from JAVA_HOME if that is set, else from PATH) and cmp, and writes
# under a temporary directory it removes; a JDK that is too old is "could not run". The JVM's
# own options variables (JAVA_TOOL_OPTIONS and the like) apply to the consumer as to any JVM. Exit
# status: 0 when every check passed, 1 when one failed (each failure is printed, and every check
# runs), 2 when it could not run.
set -uo pipefail

MODULE=io.github.vecbricks.vfdlibm
RELEASE=25
PACKAGE_DIR=io/github/vecbricks/vfdlibm
VECTOR_MODULE=jdk.incubator.vector

die() { echo "jar-check: $*" >&2; exit 2; }

case "${1:-}" in
  -h|--help) sed -n '/^# Checks the library/,/^[^#]/p' "$0" | sed '$d'; exit 0 ;;
esac
[ $# -le 1 ] || die "usage: dev/jar-check.sh [<jar>]"

root="$(cd "$(dirname "$0")/.." && pwd)" || die "cannot find the checkout"

# One JDK for all three tools: JAVA_HOME's when it is set, with no falling back to PATH's.
tool() {
  if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/$1" ] || die "JAVA_HOME is $JAVA_HOME, which has no bin/$1"
    echo "$JAVA_HOME/bin/$1"
  else
    command -v "$1" || die "no $1 on PATH, and JAVA_HOME is not set"
  fi
}
JAR="$(tool jar)" || exit 2
JAVAC="$(tool javac)" || exit 2
JAVA="$(tool java)" || exit 2
# The line of -version that holds the version: JAVA_TOOL_OPTIONS puts a notice before it.
version_of() { "$1" -version 2>&1 | grep -m 1 -E '^(javac|openjdk|java) ' ; }
"$JAVAC" --release "$RELEASE" -version > /dev/null 2>&1 \
  || die "$JAVAC does not compile --release $RELEASE: $(version_of "$JAVAC")"
feature="$("$JAVA" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *java\.specification\.version = \([0-9]*\).*/\1/p' | head -1)"
[ -n "$feature" ] && [ "$feature" -ge "$RELEASE" ] \
  || die "$JAVA does not run Java $RELEASE classes: $(version_of "$JAVA")"

if [ $# -eq 1 ]; then
  jar="$1"
else
  found=()
  for candidate in "$root"/target/*.jar; do
    case "$candidate" in
      *-sources.jar|*-javadoc.jar|*-tests.jar) ;;
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
# Both go on a class path and a module path, which a colon would split.
case "$jar$work" in
  *:*) die "a colon in the jar's path or the temporary directory's: $jar, $work" ;;
esac

failures=0
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }
ok() { echo "ok    $*"; }

echo "jar-check: $jar"
echo "jar-check: $(version_of "$JAVA")"

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
if [ -f "$work/unpacked/META-INF/NOTICE" ]; then
  if grep -q "Sun Microsystems" "$work/unpacked/META-INF/NOTICE"; then
    ok "META-INF/NOTICE carries Sun's notice"
  else
    fail "META-INF/NOTICE does not name Sun Microsystems"
  fi
fi
for kind in sources javadoc; do
  sibling="${jar%.jar}-$kind.jar"
  [ -f "$sibling" ] || continue
  mkdir "$work/$kind" || die "cannot unpack"
  (cd "$work/$kind" && "$JAR" xf "$sibling" META-INF/LICENSE META-INF/NOTICE) \
    || die "cannot unpack $sibling"
  for name in LICENSE NOTICE; do
    if cmp -s "$root/$name" "$work/$kind/META-INF/$name"; then
      ok "the $kind jar's META-INF/$name is this checkout's $name"
    else
      fail "the $kind jar has no META-INF/$name, or not this checkout's"
    fi
  done
done

# 2. The module's name. A manifest line ends in CR LF.
manifest="$work/unpacked/META-INF/MANIFEST.MF"
if [ ! -f "$manifest" ]; then
  fail "the jar has no META-INF/MANIFEST.MF"
elif tr -d '\r' < "$manifest" > "$work/manifest" \
    && grep -qxF "Automatic-Module-Name: $MODULE" "$work/manifest"; then
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
# A class belongs if it is <Name>.class or <Name>$<nested>.class for a source file
# src/main/java/<package>/<Name>.java: the tests and the benchmarks are in the same package, so
# the directory alone would not tell them apart.
sources=()
for source in "$root/src/main/java/$PACKAGE_DIR"/*.java; do
  [ -f "$source" ] && sources+=("$(basename "$source" .java)")
done
[ ${#sources[@]} -gt 0 ] || die "no sources under $root/src/main/java/$PACKAGE_DIR"
grep '\.class$' "$work/listing" > "$work/classes"
strangers=""
while IFS= read -r class; do
  name="${class#"$PACKAGE_DIR"/}"
  name="${name%.class}"
  top="${name%%\$*}"
  known=0
  if [ "$class" = "$PACKAGE_DIR/$name.class" ] && [ "${name#*/}" = "$name" ]; then
    for source in "${sources[@]}"; do
      [ "$top" = "$source" ] && known=1
    done
  fi
  [ "$known" -eq 1 ] || strangers="$strangers$class"$'\n'
done < "$work/classes"
missing=""
for source in "${sources[@]}"; do
  grep -qx "$PACKAGE_DIR/$source.class" "$work/classes" || missing="$missing$source.java"$'\n'
done
if [ -n "$strangers" ]; then
  fail "classes that no file of src/main/java/$PACKAGE_DIR/ compiles to:"
  printf '%s' "$strangers" | sed 's/^/        /'
fi
if [ -n "$missing" ]; then
  fail "sources of src/main/java/$PACKAGE_DIR/ with no class in the jar:"
  printf '%s' "$missing" | sed 's/^/        /'
fi
if [ -z "$strangers$missing" ]; then
  ok "$(grep -c . "$work/classes") classes, the ones of the ${#sources[@]} files of src/main/java"
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
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

  private static final Set<String> CALLED = new TreeSet<>();
  private static int lanes;
  private static int wrong;

  /** VFdlibm's public static method of that name and those parameters, noted as called. */
  private static Method function(String name, Class<?>... parameters) throws Exception {
    Method method = VFdlibm.class.getMethod(name, parameters);
    CALLED.add(name + "/" + parameters.length);
    return method;
  }

  /** Checks f(x) and f(x, live), found by name, against the oracle. */
  private static void check(String name, DoubleUnaryOperator oracle) throws Exception {
    Method plain = function(name, DoubleVector.class);
    Method masked = function(name, DoubleVector.class, VectorMask.class);
    int length = SPECIES.length();
    double[] in = new double[length];
    boolean[] live = new boolean[length];
    for (int from = 0; from < INPUTS.length; from += length) {
      for (int i = 0; i < length; i++) {
        in[i] = INPUTS[(from + i) % INPUTS.length];
      }
      DoubleVector x = DoubleVector.fromArray(SPECIES, in, 0);
      double[] got = ((DoubleVector) plain.invoke(null, x)).toArray();
      for (int i = 0; i < length; i++) {
        same(name, in[i], got[i], oracle.applyAsDouble(in[i]));
      }
      // Three masks, each leaving out another third of the lanes, so that every input is live
      // in two of them whatever the lane count.
      for (int out = 0; out < 3; out++) {
        for (int i = 0; i < length; i++) {
          live[i] = (from + i) % 3 != out;
        }
        VectorMask<Double> mask = VectorMask.fromArray(SPECIES, live, 0);
        double[] gotMasked = ((DoubleVector) masked.invoke(null, x, mask)).toArray();
        for (int i = 0; i < length; i++) {
          // Only the live lanes: what a lane that is not live holds is the suite's to check.
          if (live[i]) {
            same(name + " (masked)", in[i], gotMasked[i], oracle.applyAsDouble(in[i]));
          }
        }
      }
    }
  }

  /** The public static methods of VFdlibm that were not called, as name/number of parameters. */
  private static Set<String> uncalled() {
    Set<String> names = new TreeSet<>();
    for (Method method : VFdlibm.class.getDeclaredMethods()) {
      int modifiers = method.getModifiers();
      if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) {
        names.add(method.getName() + "/" + method.getParameterCount());
      }
    }
    names.removeAll(CALLED);
    return names;
  }

  /** The jar's public classes other than VFdlibm, from the class names in the arguments. */
  private static Set<String> otherPublicClasses(String[] args) throws Exception {
    Set<String> names = new TreeSet<>();
    for (int i = 1; i < args.length; i++) {
      Class<?> type = Class.forName(args[i], false, VFdlibm.class.getClassLoader());
      if (Modifier.isPublic(type.getModifiers()) && type != VFdlibm.class) {
        names.add(args[i]);
      }
    }
    return names;
  }

  private static void same(String name, double in, double got, double expected) {
    lanes++;
    if (Double.doubleToRawLongBits(got) != Double.doubleToRawLongBits(expected)) {
      wrong++;
      System.out.printf("%s(%s): got %016x, StrictMath %016x%n", name, Double.toString(in),
          Double.doubleToRawLongBits(got), Double.doubleToRawLongBits(expected));
    }
  }

  /**
   * The first argument is the module the library must be in, empty for the unnamed module; the
   * rest are the names of the jar's classes.
   */
  public static void main(String[] args) throws Exception {
    check("exp", StrictMath::exp);
    check("expm1", StrictMath::expm1);
    check("log", StrictMath::log);
    check("log10", StrictMath::log10);
    check("log1p", StrictMath::log1p);
    Module module = VFdlibm.class.getModule();
    String in = module.isNamed() ? module.getName() : "";
    System.out.printf("%d lanes at %d bits, %d wrong; the library is in %s%n", lanes,
        SPECIES.vectorBitSize(), wrong, in.isEmpty() ? "the unnamed module" : "module " + in);
    if (!args[0].equals(in)) {
      System.out.printf("expected the library in module \"%s\"%n", args[0]);
      System.exit(1);
    }
    if (!uncalled().isEmpty()) {
      System.out.printf("VFdlibm has public methods this consumer does not call: %s; "
          + "add them to dev/jar-check.sh%n", uncalled());
      System.exit(1);
    }
    Set<String> others = otherPublicClasses(args);
    if (!others.isEmpty()) {
      System.out.printf("public classes this consumer does not know: %s%n", others);
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

# The jar's classes as Java names, for the consumer to ask each whether it is public.
names=()
while IFS= read -r class; do
  class="${class%.class}"
  names+=("${class//\//.}")
done < "$work/classes"

# What javac and java print, less their warning that an incubating module is in use: those
# lines whole and no other, so that an error which names the module is still shown.
show() {
  grep -v -x \
      -e "warning: \(\[incubating\] \)\?using incubating module(s): $VECTOR_MODULE" \
      -e "1 warning" \
      -e "WARNING: Using incubator modules: $VECTOR_MODULE" "$work/log" \
    | sed 's/^/        /'
}

if "$JAVAC" --release "$RELEASE" --add-modules "$VECTOR_MODULE" -cp "$jar" -d "$work/classpath" \
    "$work/src/consumer/Consumer.java" > "$work/log" 2>&1 \
  && "$JAVA" --add-modules "$VECTOR_MODULE" -cp "$jar:$work/classpath" consumer.Consumer "" \
    "${names[@]}" >> "$work/log" 2>&1; then
  ok "a consumer on the class path gets StrictMath's bits"
else
  fail "the consumer on the class path:"
fi
show

if "$JAVAC" --release "$RELEASE" --module-path "$jar" -d "$work/modulepath" \
    "$work/src/module-info.java" "$work/src/consumer/Consumer.java" > "$work/log" 2>&1 \
  && "$JAVA" --module-path "$jar:$work/modulepath" -m consumer/consumer.Consumer "$MODULE" \
    "${names[@]}" >> "$work/log" 2>&1; then
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
