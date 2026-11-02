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
- Widths: `-Dvfdlibm.test.jvmArgs=-XX:MaxVectorSize=32` skips the 512-bit cases, as an AVX2
  host does (GitHub's x86 runners are a mix of AVX2 and AVX-512 hosts); `=16` runs everything at
  128 bits, as the Arm runners do.
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
  instructions at 256 bits, though: to make C2 compile as on an AVX2 host such as the EPYC
  7763, pass `-XX:UseAVX=2`, and check `-prof gc` and `-XX:+PrintIntrinsics` under it.
  Without AVX512DQ, C2 lowers no `D2L`, `L2D`, `L2F` or `F2L`; such a convert boxes there.
- Benchmarks are JMH, in `src/jmh/java`; `verify` compiles them. Run them with
  `mvn -B -Pjmh test-compile exec:exec`, passing JMH options as `-Djmh.args="..."`, e.g.
  `-Djmh.args="StrictMathExp -p lanes=4"`.
- A port's benchmark must pass the allocation check, which CI runs: `bench/alloc-check.py
  --mvn <mvn>` (add `--jvm-args "-XX:UseAVX=2 -XX:MaxVectorSize=32"` to compile as an AVX2 host).
  It covers each vector arm at 256 bits and at the preferred species if the class is named
  `<Function>Benchmark`, the methods `<arm>256` and `<arm>Preferred`, and common data is the
  `@Param` `inputs=common`; see `SKILLS.md`.

## Lessons

`SKILLS.md` holds what earlier ports learned about JMH, C2 and the Vector API: how to tell a
boxed body from a vector one, which conversions and masks lower at which width, and where
headline numbers come from. Read it before writing or benchmarking a port, and add to it what a
port teaches.

## Measurements, not adjectives

- Any performance claim, in a comment, a document or a pull request, traces to a committed
  results file under `bench/results/`. "Faster" with no file behind it is not written.
- A results file opens with its provenance: what was run and why, the commit, JDK, CPU, vector
  flags, kernel, load average at start and end, the JMH options and the command, then each row's
  minimum, average and maximum and JMH's summary table with `gc.alloc.rate.norm`. The existing
  files are the template. A file from the laptop says it is not a headline.
- The method is fixed: `-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc`, the body inlined with
  `-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*`, on an otherwise idle machine.
  The arms of a comparison run in one JMH run. A claim resting on a ratio under about 1.3x is
  re-run and compared by minimums before it is written down, and the file keeps both runs.
- Headline numbers come from the EPYC 7763 run of `.github/workflows/benchmark.yml`, dispatched
  with `bench/dispatch-epyc.sh`; never relax its CPU check.
- When a change moves committed numbers, measure again and commit the new file, then requote
  every document from it, rather than patching quoted figures one by one.

## Papers are transcribed, not paraphrased from memory

`papers/` holds mechanical transcriptions of the third-party papers this work reads closely, so
a port or a pull request can cite a section, a constant or a measurement with the argument at
hand. They carry their own authors and licence and are not vfdlibm's documentation: nothing in
`papers/` is under this project's Apache licence, compiled or shipped. And they are lossy, since
a PDF's typography carries part of every formula, so **when a constant, an exponent or an
inequality decides something, read the PDF, not the transcription**. Each file opens with what
its conversion lost. See `papers/README.md` before adding one. Notes on other vector math
libraries go in `readings/`, with each source's licence named; never notes on GPL or LGPL code.

## Changes

Changes go through pull requests on branches, never straight to `main`. Each function comes in its
own PR, paired with the Varka PR that emits it. Commit messages and PR descriptions end with
`Generated-by: <tool> (<model and version>)` when AI tooling was used (Apache's convention); no
`Co-Authored-By`.
