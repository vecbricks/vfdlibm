# Working in vfdlibm, for agents and people alike

vfdlibm ports fdlibm's double functions to the Java Vector API, each lane bit-identical to
`java.lang.StrictMath`. `PLAN.md` is the plan and the rules; read it before changing anything.

## The rules that matter most

- **Licence.** Port from Sun's fdlibm 5.3 C sources only, and keep each file's Sun notice. Never
  read OpenJDK's `FdLibm.java`, HotSpot's math stubs or glibc in order to write code here; they are
  GPL or LGPL. `StrictMath` is the oracle, called from tests, never a source to copy.
- **Bit identity.** A function is done when every test compares its lanes with `StrictMath` by
  `Double.doubleToRawLongBits` and passes, at every vector width the host has.
- **fdlibm's order of operations.** No fused multiply-add, no reassociation, no algebraic
  shortcuts: the rounding of every step must be fdlibm's.

## Build and test

- `mvn -B verify` builds and runs the tests; the Vector API module is added by `pom.xml`.
- One test: `mvn -q test -Dtest=ExpTest`.
- Widths: `-Dvfdlibm.test.jvmArgs=-XX:MaxVectorSize=32` skips the 512-bit cases, as GitHub's x86
  runners do; `=16` runs everything at 128 bits, as the Arm runners do.
- Planted faults: a port reads each of its faults once, into a `static final` constant of its own
  class (`static final boolean DROPPING_CORRECTION = Faults.is("exp.dropCorrection")`), and tests
  that constant where the fault would go wrong. The vector body then holds no call, which C2 could
  refuse to inline and so box the vectors, and an unplanted fault folds away. The fault comes from
  `-Dvfdlibm.fault=<name>`, fixed for the JVM's life, so `verify` runs the normal suite once
  and then one forked surefire execution per fault, running only the tests tagged `fault`.
  A new fault goes in `PlantedFaultTest.CATCHERS` with the check that must catch it, and gets an
  execution in `pom.xml`; `PlantedFaultTest` fails if either is missing.
- The harness is in `src/test/java`: `Oracle` compares lanes with `StrictMath` by raw bits,
  `Inputs` makes the inputs, `VectorWidth` runs a test at 128, 256 and 512 bits and the preferred
  species, and `PlantedFault` plants a fault a check must catch. `HarnessSelfTest` tests them.
- A width wider than the host's vectors is skipped. To see an AVX2 host's skips on an AVX-512
  one: `mvn -B test -Dvfdlibm.test.jvmArgs=-XX:MaxVectorSize=32`. That keeps AVX-512's
  instructions at 256 bits, though: to make C2 compile as on an AVX2 host (GitHub's x86 runners,
  the EPYC 7763), pass `-XX:UseAVX=2`, and check `-prof gc` and `-XX:+PrintIntrinsics` under it.
  Without AVX512DQ, C2 lowers no `D2L`, `L2D`, `L2F` or `F2L`; such a convert boxes there.
- Benchmarks are JMH, in `src/jmh/java`; `verify` compiles them. Run them with
  `mvn -B -Pjmh test-compile exec:exec`, passing JMH options as `-Djmh.args="..."`, e.g.
  `-Djmh.args="StrictMathExp -p lanes=4"`.

## Changes

Changes go through pull requests on branches, never straight to `main`. Each function comes in its
own PR, paired with the Varka PR that emits it. Commit messages and PR descriptions end with
`Generated-by: <tool> (<model and version>)` when AI tooling was used (Apache's convention); no
`Co-Authored-By`.
