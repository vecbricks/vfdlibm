# vfdlibm

fdlibm on the Java Vector API: math functions over `DoubleVector` lanes whose every lane is
bit-identical to `java.lang.StrictMath`.

**Status: 0.x.** `exp`, `expm1`, `log`, `log1p` and `log10` are ported and released:
`io.github.vecbricks:vfdlibm` 0.1.0 is on Maven Central. Functions are added one at a time, as
[Varka](https://github.com/vecbricks/varka) needs them. A minor version may break the API and a
patch may not; adding a function is a patch. Bit identity with `StrictMath` is the contract, and
no version changes it (`CHANGELOG.md`).

## Use

A client that depends on the released jar, from Maven Central. Its `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>client</artifactId>
  <version>1</version>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.github.vecbricks</groupId>
      <artifactId>vfdlibm</artifactId>
      <version>0.1.0</version>
    </dependency>
  </dependencies>
</project>
```

and `src/main/java/example/Main.java`, which calls `VFdlibm.exp` on the lanes of an array, in
both forms (`f(x)` and `f(x, live)`), and compares with `StrictMath`:

```java
package example;

import io.github.vecbricks.vfdlibm.VFdlibm;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

public final class Main {
  private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

  public static void main(String[] args) {
    double[] xs = {0.5, 1.0, 2.0, -1.0, 10.0, 700.0, 710.0, Double.NaN, 0.0, -0.0, 1e-300, 3.5};
    double[] ys = new double[xs.length];

    int i = 0;
    for (; i < SPECIES.loopBound(xs.length); i += SPECIES.length()) {
      VFdlibm.exp(DoubleVector.fromArray(SPECIES, xs, i)).intoArray(ys, i);
    }
    for (; i < xs.length; i++) { // the tail the species does not fill, one lane at a time
      ys[i] = StrictMath.exp(xs[i]);
    }

    boolean same = true;
    for (int k = 0; k < SPECIES.loopBound(xs.length); k++) { // the lanes VFdlibm computed
      long got = Double.doubleToRawLongBits(ys[k]);
      same &= got == Double.doubleToRawLongBits(StrictMath.exp(xs[k]));
    }
    System.out.println("exp(" + xs[0] + ") = " + ys[0] + " at " + SPECIES.vectorBitSize()
        + " bits; the computed lanes have StrictMath's bits: " + same);

    // Only the lanes set in the mask are computed; the others keep the input's bits.
    DoubleVector x = DoubleVector.fromArray(SPECIES, xs, 0, SPECIES.indexInRange(0, xs.length));
    VectorMask<Double> live = VectorMask.fromLong(SPECIES, 0b1);
    DoubleVector z = VFdlibm.exp(x, live);
    System.out.println("masked: lane 0 is " + z.lane(0) + ", lane 1 is " + z.lane(1));
  }
}
```

The Vector API is still incubating, and the JVM does not resolve an incubating module unless it
is told to, so **at run time** a client adds the module: `--add-modules jdk.incubator.vector`
on the class path, as here (without it the run fails with `NoClassDefFoundError:
jdk/incubator/vector/DoubleVector`; on the JDK this was checked with, 25.0.5, a plain `javac`
needed no flag for a class-path build, and passing it there is harmless). The example assumes
the host's preferred species is 128 bits or more, which the library requires of every vector
it is given. The class path comes from Maven:

```sh
mvn -q compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=cp.txt
java --add-modules jdk.incubator.vector -cp "target/classes:$(cat cp.txt)" example.Main
```

which prints, on a host with 512-bit vectors (the width is the host's), and the JVM's own
warning that an incubator module is in use:

```
exp(0.5) = 1.6487212707001282 at 512 bits; the computed lanes have StrictMath's bits: true
masked: lane 0 is 1.6487212707001282, lane 1 is 1.0
```

On the module path, the client names the module instead of passing the flag: a
`src/main/java/module-info.java` that requires the jar's module,
`io.github.vecbricks.vfdlibm`, and the Vector API's:

```java
module example {
  requires io.github.vecbricks.vfdlibm;
  requires jdk.incubator.vector;
}
```

built and run with no `--add-modules` at all (without the second `requires`, `javac` says that
the package `jdk.incubator.vector` is not visible):

```sh
mvn -q clean compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=cp.txt
java --module-path "target/classes:$(cat cp.txt)" -m example/example.Main
```

Each function takes a vector of any species of 128 bits or more. Ported so far: `exp`,
`expm1`, `log`, `log10` and `log1p`. The system property `vfdlibm.fault` plants a fault, for
this project's tests only, and works in the shipped jar too: never set it, including through
`JAVA_TOOL_OPTIONS`; a JVM that has it set prints a warning to `System.err` once. The property
and the fault names are for this project's tests only: they are not part of the public API or of
the compatibility promise, and any release, a patch included, may rename or remove them. With the
property unset, the results are unchanged, and each lane still equals `StrictMath`'s.

**Which species to pass.** Every species of 128 bits or more gives the same bits; what the body
allocates differs. The allocation check (`bench/alloc-check.py`, run by CI) covers 256 bits and
the preferred species on x86, and the preferred species, 128 bits, on GitHub's Arm runner. The
files under `bench/results/` read as allocating, and the check does not cover: `exp` and `log`
at 128 bits on x86, every function at 512 bits on an AVX2 host, and `SPECIES_MAX` for `exp`,
`log` and `log10` on mixed data (a laptop file, and host files for `exp` and `log10`). The
Javadoc of `VFdlibm` gives each reading with its file, and lists what is not measured: the speed
of 512 bits against 256 on the EPYC 9V74 draws that have AVX-512, and any port's speed on Arm.

## Supported JDKs

0.1.x supports **JDK 25 only**. The Vector API is incubating, and its API and C2's lowering of
it move between JDK releases, so what a newer JDK does with these functions has not been
measured here. The contract, bit identity with `StrictMath`, is tested on JDK 25 and on no
other: every CI row, the nightly sweep and the benchmarks run Zulu 25. The example above was run as
written on JDK 25 against 0.1.0 from Central; no job here repeats that.

## Why

`StrictMath` is specified as fdlibm's results, the same on every host, and Apache Spark computes
`exp`, `log`, `pow` and their relatives with it. The Vector API's own math operators
(`VectorOperators.EXP` and the others) call SVML or SLEEF. Those are accurate to about one unit in
the last place, but they are not fdlibm, so their lanes can differ from `StrictMath` in the last
bit. A vectorized engine that must return exactly what the JVM's scalar code returns needs
fdlibm's own sequence of operations, lane by lane. That is this library.

## What a function promises

- **Bit identity.** For every `double` input, each lane equals `StrictMath.f` on that input,
  compared by `Double.doubleToRawLongBits`. NaN payloads and signed zeros are included.
- **fdlibm's operations, in fdlibm's order.** Only IEEE `+ - * /`, `sqrt` and bit operations. There
  is no fused multiply-add and no reassociation, so the rounding of every step is fdlibm's.
- **Rare paths, exact too.** Lanes on a rare path (huge arguments, subnormals, special cases) are
  either computed in the vector, every branch blended (in every vector, or only in a vector that
  holds a rare lane), or finished by `StrictMath` per lane; the result is exact either way. The
  choice is made per function from benchmarks, and stays provisional until the function's runs on
  the benchmark hosts (the AVX-512 Xeon Platinum 8573C and Xeon 6973P-C first, the EPYC 9V45 when
  drawn, then the AVX2 EPYC 7763; `HARDWARE.md`): the laptop's numbers do not agree at every
  width.

## Testing

Each function is checked against `StrictMath` on the same JVM:
- exhaustively where a range is small enough;
- by sampled bit patterns over the whole domain;
- with fdlibm's thresholds and the known hard cases as fixed vectors;
- every night (`.github/workflows/nightly.yml`), by a sweep of all 2^32 high words, three low
  words each, and by fuzzing from a fresh seed, every arm at every width the runner has; x86's
  128 bits, which run lane by lane there, are swept a 1/32 slice a night, and Arm's 128 bits
  whole every night (`SKILLS.md`, "The nightly sweep and fuzz").

## Licence

Apache-2.0. The functions port Sun's fdlibm 5.3 C sources, whose notice `NOTICE` preserves. They
are never translated from OpenJDK's `FdLibm.java`, which is GPLv2 with the Classpath exception;
`StrictMath` is used only as the oracle the tests compare against.
