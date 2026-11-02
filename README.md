# vfdlibm

fdlibm on the Java Vector API: math functions over `DoubleVector` lanes whose every lane is
bit-identical to `java.lang.StrictMath`.

**Status: pre-alpha.** The repository, build, licence and plan exist. Functions are added one at
a time, as [Varka](https://github.com/vecbricks/varka) needs them. Nothing is published yet.

## Use

```java
DoubleVector y = VFdlibm.exp(DoubleVector.fromArray(DoubleVector.SPECIES_PREFERRED, xs, i));
DoubleVector z = VFdlibm.exp(y, live); // only the lanes set in live; the others keep y's bits
```

Each method takes a vector of any species of 128 bits or more. Ported so far: `exp`, `log`,
`log10` and `log1p`.

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
