# vfdlibm

fdlibm on the Java Vector API: math functions over `DoubleVector` lanes whose every lane is
bit-identical to `java.lang.StrictMath`.

**Status: pre-alpha.** The repository, build, licence and plan exist. Functions are added one at
a time, as [Varka](https://github.com/vecbricks/varka) needs them. Nothing is published yet.

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
- **Rare paths, finished exactly.** Lanes on a path the vector form does not take (huge arguments,
  subnormals, special cases) are computed by `StrictMath` per lane, so the result stays exact.

## Testing

Each function is checked against `StrictMath` on the same JVM:
- exhaustively where a range is small enough;
- by sampled bit patterns over the whole domain;
- with fdlibm's thresholds and the known hard cases as fixed vectors.

## Licence

Apache-2.0. The functions port Sun's fdlibm 5.3 C sources, whose notice `NOTICE` preserves. They
are never translated from OpenJDK's `FdLibm.java`, which is GPLv2 with the Classpath exception;
`StrictMath` is used only as the oracle the tests compare against.
