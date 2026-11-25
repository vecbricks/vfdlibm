# vfdlibm

fdlibm on the Java Vector API: math functions over `DoubleVector` lanes whose every lane is
bit-identical to `java.lang.StrictMath`, but for a two-NaN lane of `atan2`, `hypot` or `pow`.

**Status: 0.x.** All thirteen functions of the plan's set (`PLAN.md` section 3) are ported and
released in 0.3.0, as `io.github.vecbricks:vfdlibm`: `exp`, `expm1`, `log`, `log1p` and `log10`
since 0.1.0, `sinh`, `cosh`, `asin`, `atan`, `acos` and `atan2` since 0.2.0, and `hypot` and `pow`
in 0.3.0, `pow` with its common path in the vector and its other lanes finished per lane. Functions
are added one at a time, as [Varka](https://github.com/vecbricks/varka) needs them. A minor version
may break the API and a patch may not; adding a function is a patch. Bit identity with `StrictMath`
is the contract, and no version changes it (`CHANGELOG.md`); the one lane it excepts is a lane of
two NaNs with different bits in `atan2`, `hypot` or `pow`.

**Speedup over `StrictMath`** of the latest release, per host, from the committed results
files:

<!-- speedup-table: v0.3.0 -->

| Function | xeon8573c (AVX-512): common / mixed | epyc7763 (AVX2): common / mixed |
|---|---|---|
| `exp` | 6.72x / 3.49x | 4.29x / 2.62x |
| `expm1` | 4.03x / 3.03x | 1.90x / 1.74x |
| `log` | 4.12x / 2.38x | 2.44x / 1.97x |
| `log10` | 4.41x / 2.76x | 2.30x / 1.86x |
| `log1p` | 3.39x / 2.81x | 1.95x / 1.56x |
| `sinh` | 3.53x / 2.74x | 2.12x / 1.68x |
| `cosh` | 3.49x / 1.84x | 2.20x / 1.71x |
| `asin` | 2.31x / 2.08x | 2.07x / 1.52x |
| `atan` | 3.48x / 2.30x | 1.77x / 1.51x |
| `acos` | 2.21x / 1.90x | 1.99x / 1.36x |
| `atan2` | 2.72x / 2.08x | 1.72x / 1.55x |
| `hypot` | 2.16x / 1.18x | 1.25x / 0.70x |
| `pow` | 11.79x / 7.43x | 4.88x / 4.28x |

For vfdlibm 0.3.0 (tag v0.3.0). Each figure is `StrictMath` called once per lane (the
`scalarStrictMath` row) over the function's default arm at the host's preferred species (its
`<arm>Preferred` row), by minimum ns/op within one run; where a file holds several runs, the lowest
of their ratios is shown; from one run, a ratio under 1.4x is marked, and one under 1.3x says
"re-run needed". `common`: no lane takes a rare path; `mixed`: rare lanes run. The preferred species
is 512 bits on xeon8573c, 256 bits on epyc7763. "older code" and "newer code": the files of that
function and host time code older or newer than the release's; "not measured": there is no file. The
figures come from the newest file per function and host under [`bench/results/`](bench/results/)
that times the release's code; `bench/readme-table.py --write` makes this table, and CI checks it.

<!-- end of speedup-table -->

`hypot` on AVX2 is a known issue of 0.3.0: on the EPYC 7763 it is not shown to be faster than
`StrictMath`, and on `HypotBenchmark`'s mixed data, where about one pair in 16 takes a rare path of
fdlibm's C, it is slower, with the same results (`CHANGELOG.md`, "Known issues").

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
      <version>0.3.0</version>
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

Each function takes a vector of any species of 128 bits or more. Released in 0.3.0: `exp`,
`expm1`, `log`, `log10`, `log1p`, `sinh`, `cosh`, `asin`, `atan` and `acos`, and the three that take
two vectors, `atan2(y, x)`, `hypot(x, y)` and `pow(x, y)`. The system property `vfdlibm.fault`
plants a fault, for this project's tests only, and works in the shipped jar too: never set it,
including through `JAVA_TOOL_OPTIONS`; a JVM that has it set prints a warning to `System.err` once.
The property and the fault names are for this project's tests only: they are not part of the public
API or of the compatibility promise, and any release, a patch included, may rename or remove them.
With the property unset, the results are unchanged, and each lane still meets the contract below.

**Which species to pass.** Every species of 128 bits or more gives the same bits; what the body
allocates differs. The allocation check (`bench/alloc-check.py`, run by CI) covers 256 bits, the
preferred species and `SPECIES_MAX` (up to eight lanes) on x86, and the preferred species, 128
bits, on GitHub's Arm runner. The files under `bench/results/` read as allocating, and the check
does not cover: `exp` and `log` at 128 bits on x86, and every function but `atan2`, `hypot` and
`pow`, which have no 512-bit row, at 512 bits on an AVX2 host. `SPECIES_MAX` of today's `exp`,
`expm1`, `log`, `log10` and `log1p` was read on a laptop
(`max-finish-alloc-free-laptop-2026-11-21.txt`), and on a host for `exp` and `log10` at 256 bits by
the EPYC 7763 pair (`exp-log10-pair-epyc7763-2026-11-21.txt`
and its re-run), and under 0.05 bytes per op for `expm1`, `log`, `log10` and `log1p` at 512 bits
by the Xeon 8573C's files of 22 November and for `expm1` and `log1p` at 256 bits by the EPYC
7763's of 23 November; the other host files that have a
`*Max` row of those five (the Xeon's of 21 November for all five, the EPYC's of
20 November for `exp` and `log10` and of 21 November for all five, and
`exp-onediv-xeon8573c-2026-11-03.txt`) read the old finish. The host files of 21 November for
`sinh` and `cosh` and of 22 November for `asin`, `atan`, `acos` and `atan2` (`bench/results/`)
read their `*Max` rows as they are now, under 0.05 bytes per op. The Javadoc of `VFdlibm` gives
each reading with its file, and lists what is not measured: the speed of 512 bits against 256 on
the EPYC 9V74 draws that have AVX-512, and any port's speed on Arm.

## Supported JDKs

0.3.x supports **JDK 25 only**. The Vector API is incubating, and its API and C2's lowering of
it move between JDK releases, so what a newer JDK does with these functions has not been
measured here. The contract, bit identity with `StrictMath`, is tested on JDK 25 and on no
other: every CI row, the nightly sweep and the benchmarks run Zulu 25. The example above is run
as written, on JDK 25, every week by `.github/workflows/readme-check.yml`, with the version of
its dependency replaced by the latest release on Central.

## Why

`StrictMath` is specified as fdlibm's results, the same on every host, and Apache Spark computes
`exp`, `log`, `pow` and their relatives with it. The Vector API's own math operators
(`VectorOperators.EXP` and the others) call SVML or SLEEF. Those are accurate to about one unit in
the last place, but they are not fdlibm, so their lanes can differ from `StrictMath` in the last
bit. A vectorized engine that must return exactly what the JVM's scalar code returns needs
fdlibm's own sequence of operations, lane by lane. That is this library.

## What a function promises

- **Bit identity.** For every `double` input, each lane equals `StrictMath.f` on that input,
  compared by `Double.doubleToRawLongBits`. NaN payloads and signed zeros are included. For a
  function of two arguments (`atan2`, `pow`, `hypot`), a lane where both arguments are NaN with
  different bits equals one of the two arguments' bits with the quiet bit set (for `hypot`, the same
  with the sign bit cleared); which one is not specified, since `StrictMath`'s own choice varies.
- **fdlibm's operations, in fdlibm's order.** Only IEEE `+ - * /`, `sqrt` and bit operations. There
  is no fused multiply-add and no reassociation, so the rounding of every step is fdlibm's.
- **Rare paths, exact too.** Lanes on a rare path (huge arguments, subnormals, special cases) are
  either computed in the vector, every branch blended (in every vector, or only in a vector that
  holds a rare lane), or finished by `StrictMath` per lane; the result is exact either way. The
  choice is made per function from benchmarks, and stays provisional. The functions with more than
  one arm, `exp`, `expm1`, `log`, `log10`, `log1p`, `cosh` and `atan2`, each have files from the
  Xeon Platinum 8573C and the EPYC 7763 (`bench/results/`), and each file's reading is in
  `SKILLS.md`; where two arms are within 1.3x of each other no direction is read, and where the
  hosts disagree the files say so. The rules are set (issue #30): a tie within 1.3x on every host
  that has a file goes to the finish arm, except for `cosh`, whose arm a host run decides, and where
  hosts disagree each species keeps its own arm (no function needs that yet). By that rule `expm1`
  and `log1p` run the finish arm: their files of today's code (`expm1-xeon8573c-2026-11-22.txt`, a
  run and a re-run, `expm1-epyc7763-2026-11-23.txt`, the same, and the same for `log1p`) read no
  arm lower than the finish by more than 1.3x at any species in any run, a tie and not a speed-up.
  The
  `*Max` rows of the host files of 3 to 21 November for `exp`, `expm1`, `log`, `log10` and `log1p`
  (`exp-onediv-xeon8573c-2026-11-03.txt` and those of 20 and 21 November;
  `log1p-xeon8573c-2026-11-03.txt` and `expm1-xeon8573c-2026-11-04.txt` have no `*Max` row) time the
  finish that boxed before issue #37 and are left out of the rules. `atan2` has no finish arm, so
  the rule does not pick between its two; neither host's file gives them a direction after a run and
  a re-run each, and its guarded arm stays the default at every species by the owner's decision.
  Every default stays provisional until a host run revises it; `PLAN.md` section 5 gives each
  function's case. The laptop's numbers do not agree at every width.

## Testing

Each function is checked against `StrictMath` on the same JVM:
- exhaustively where a range is small enough;
- by sampled bit patterns over the whole domain;
- with fdlibm's thresholds and the known hard cases as fixed vectors;
- every night (`.github/workflows/nightly.yml`), by a sweep of all 2^32 high words, three low words
  each (of one argument a night for a function of two arguments, the two alternating by default:
  `pair-passes`), and by fuzzing from a fresh seed, every arm at every width the runner has; x86's
  128 bits, which run lane by lane there, are swept a 1/32 slice a night, and Arm's 128 bits whole
  every night (`SKILLS.md`, "The nightly sweep and fuzz").

## Licence

Apache-2.0. The functions port Sun's fdlibm 5.3 C sources, whose notice `NOTICE` preserves. They
are never translated from OpenJDK's `FdLibm.java`, which is GPLv2 with the Classpath exception;
`StrictMath` is used only as the oracle the tests compare against.
