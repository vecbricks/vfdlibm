# vfdlibm: the plan

## 1. Goal

Vector API versions of fdlibm's double functions, each lane bit-identical to
`java.lang.StrictMath`, released as a small library under `io.github.vecbricks`.

## 2. How it grows: one function at a time, with Varka

The library is filled step by step. When Varka needs an exact vector form of a function, that
function is ported here and in Varka's kernel together, and the two are cross-checked:

1. **Port it here.** Port the function from fdlibm 5.3's C file (`e_exp.c`, `s_expm1.c`, ...) into
   a static method over `DoubleVector`, with that file's Sun notice in the source header. The C
   files are committed unchanged under `reference/fdlibm-5.3/`, and a port cites the one it follows.
   The port may compute differently from the C where a proof in `PROOFS.md` shows every result
   equal (section 6).
2. **Test it here** against `StrictMath`, lane by lane and bit for bit (section 4).
3. **Build Varka's side.** Varka's kernel emits the same sequence of operations as its own IR
   nodes. A large function body is not inlined by C2, so a call into the library from a kernel
   would box the vectors, and Varka uses this library as its reference rather than calling it.
4. **Cross-check the two.** Varka's tests run the library's method and the emitted kernel on the
   same inputs and require identical bits. Both are also checked against `StrictMath`.

Each function arrives in its own pull request here, paired with the Varka pull request that emits
it.

## 3. The functions, and the order they are expected in

The set is the functions Spark computes with fdlibm on every host:

- `StrictMath` calls: `exp`, `expm1`, `log`, `log10`, `log1p`, `pow`;
- `Math` methods that HotSpot does not replace with a stub on any host: `asin`, `acos`, `atan`,
  `atan2`, `sinh`, `cosh`, `hypot`.

That is thirteen functions, about 1,000 lines of fdlibm code in all; `pow` is the largest. Varka's
row order decides which comes first. `exp` is the first port and is done (`VFdlibm.exp`, from
`e_exp.c`); `sinh` and `cosh` build on it and on `expm1`, and `pow` on neither: `e_pow.c` has its
own logarithm and exponential and calls only `sqrt`, `fabs` and `scalbn`. `sinh` is done
(`VFdlibm.sinh`, from `e_sinh.c`, computing through `expm1`'s common path below 22 and finishing
the rest per lane), and so is `cosh` (`VFdlibm.cosh`, from `e_cosh.c`, through `exp`'s common path
and expm1's `k == 0` lines, with two arms for its small range: the default arm is the slice arm,
provisionally, until a benchmark host times them). `asin` is done too (`VFdlibm.asin`, from
`e_asin.c`, with `sqrt` as the Vector API's `SQRT`, one arm, and the lanes of `|x| >= 1` finished
per lane). `log`, `log10` and
`log1p` are done too (`VFdlibm.log`, from `e_log.c`, `VFdlibm.log10`, from `e_log10.c`, which
computes through log's common path, and `VFdlibm.log1p`, from `s_log1p.c`), and so is `expm1`
(`VFdlibm.expm1`, from `s_expm1.c`, whose reduction is exp's).

`tan`, `tanh` and `cbrt` are fdlibm on aarch64 but Intel LIBM stubs on x86, so a port of them
matches the JVM only on Arm. `sin` and `cos` are HotSpot's own stubs on both architectures, so
`Math.sin` and `Math.cos` are not fdlibm anywhere; a port of them matches `StrictMath` only. These
five are a later, optional set; the large-argument reduction of the trigonometric ones
(`__ieee754_rem_pio2`, `__kernel_rem_pio2`) stays a per-lane `StrictMath` call.

## 4. Testing

- **The oracle** is `StrictMath` on the same JVM, compared by `doubleToRawLongBits`.
- **Inputs:**
  - sampled bit patterns over the whole `double` domain;
  - dense sweeps around each threshold fdlibm branches on;
  - fdlibm's special cases (NaN, the infinities, `+-0.0`, subnormals, overflow and underflow
    edges);
  - published hard cases: inputs where the exact result lies very close to a rounding boundary, from
    Lefevre and Muller's worst-case tables (ARITH 15, 2001) and Lefevre's list of round-to-nearest
    hard cases, one resource per function under `src/test/resources/.../hard-cases/` (so far `exp`,
    `expm1`, `log`, `log10`, `log1p`, `sinh`, `cosh` and `asin`), loaded with
    `Inputs.hardCases("<function>")`. The inputs of Lefevre's list come out of
    `dev/testlibm-extract.py`, which rechecks each line with 400-digit arithmetic against exact
    values it derives itself (`sinh`'s and `cosh`'s files were written by it; it also derives
    `atan`, `asin` and `acos`, whose files come with their ports). A port that keeps fdlibm's
    operations, or departs from them only where `PROOFS.md` proves the result equal, matches
    `StrictMath` on them by construction; they sit where a dropped correction term or a
    reassociated sum, an error far below one ulp, is most likely to change the returned double.
- **Every check proves it can fail:** a flag plants a known fault (a dropped correction term, a
  constant off by one ulp, a branch threshold moved), and the suite must catch it. The flag is
  the system property `vfdlibm.fault`, read once into a `static final` in the main sources'
  `Faults` class. A port reads each of its faults through `Faults.is` once, into a `static final`
  constant of its own class, and tests that constant in its body: the vector body holds no call,
  and an unplanted fault folds away. The build runs each fault in a forked JVM of its own, where
  the tests tagged `fault` require that fault's checks to fail.
- **Every vector width:** each test runs at the preferred species and at 128, 256 and 512 bits
  where the host supports them. CI runs the suite on x86 at the drawn runner's widths (128 and
  256 bits, and 512 on an AVX-512 draw), on x86 as an AVX2 host (`-XX:UseAVX=2
  -XX:MaxVectorSize=32`, 128 and 256 bits on every draw), on x86 with `-XX:MaxVectorSize=16`
  (all of it at 128 bits) and on Arm (128 bits).
- **Nightly:** `.github/workflows/nightly.yml` sweeps every high word of exp, expm1, log, log10,
  log1p, sinh and cosh (asin is in `Nightly`'s table and not yet in the night's list) with three
  low words each (every branch fdlibm decides on the high word) at
  every width but 128 bits on x86, where a rotating 1/32 slice a night covers it in 32 nights, and
  at 128 bits on Arm every night; it fuzzes from a fresh seed at every width, and runs every proof
  under both solvers; a failure opens an issue labelled `nightly-failure` with the mismatching
  inputs, ready for a `hard-cases` file (`SKILLS.md`), and a dispatch that plants a fault, to test
  that alert, opens one labelled `nightly-fault-test`.
- **Masked forms:** a masked form is checked under seeded random masks, all-true and all-false;
  active lanes must equal `StrictMath` and inactive lanes must keep the input's raw bits (for a
  two-argument function, the first argument's).

## 5. API shape

- **One final class** `VFdlibm`, with static methods such as `DoubleVector exp(DoubleVector x)`.
- **Masks:** each method takes the lanes as they are. A masked variant leaves inactive lanes
  untouched and never lets them select a slow path.
- **Branches:** fdlibm's branches on the argument's range become lane masks. The common paths are
  computed for every lane and blended. A rare path is finished per lane by `StrictMath`, so the
  vector form never trades exactness for speed. Whether a given branch is blended or finished per
  lane is decided by measurement and recorded in the function's pull request. A rare path that is
  only a few vector operations can instead be computed and blended only for a vector that holds a
  rare lane, behind one `anyTrue`, as `log1p` does. The readings are in `SKILLS.md`, port by port.
  For `exp` the per-lane finish allocates nothing at 256 and 512 bits (and at `SPECIES_MAX` of up to
  eight lanes, since its first eight lanes are written out with constant indices; it boxed there
  before, `bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`), and the host files read the
  two arms by minimums: on the EPYC 7763 (`bench/results/exp-epyc7763-2026-11-20.txt`, two runs) the
  finish is 1.99 to 2.13 times faster than the blend on common data and 1.50 to 1.57 on mixed data,
  at 256 bits and the preferred species; on the Xeon Platinum 8573C
  (`bench/results/exp-xeon8573c-2026-11-21.txt`, one run) 1.23 to 1.37 on common data, a ratio that
  straddles 1.3x, and 1.70 to 1.84 on mixed data, at 256 bits, 512 bits and the preferred species.
  The code has changed since both runs (a fault constant and a local in the k conversion), so no run
  has timed today's `exp`. Which arm each function keeps stays provisional until the 8573C has a
  second run, the other AVX-512 hosts a first, and a pair exists, and the rules for a tie within
  1.3x and for hosts that disagree are set (issue #30); meanwhile `VFdlibm` calls the finish arm of
  `exp`, `log` and `log10` and the guarded arm of `expm1` and `log1p` (`HARDWARE.md`).

## 6. Rules

- Port from fdlibm 5.3's C sources only (netlib), as committed in `reference/fdlibm-5.3/`. Never
  copy from or translate OpenJDK's `FdLibm.java`, HotSpot's Intel LIBM stubs or glibc, which are
  GPL or LGPL.
- The result must be the same; the implementation may differ. Every lane stays bit-identical to
  `StrictMath`, and a port may compute differently from the C only where a written proof, formal
  or informal, shows the result equal for every input. Informal: step by step, citing each IEEE
  property used and each hinge condition, with the input range where it holds and why the lanes
  are in it. Formal: machine-checked, following Varka's `sql/varka/proofs/` conventions, or
  reduced to a finite check by a theorem (for example, monotonicity of correctly rounded
  operations plus evaluation at the boundary doubles). Proofs live in `PROOFS.md` as numbered
  lemmas (statement, hypotheses, proof, and the test that checks the hinges, which must fail if a
  hinge were false), with a short Javadoc pointer at the code.
- No fused multiply-add, no reassociation, no division by a reciprocal: they change results in
  general, so no proof covers them as a rule. A specific use proven equal for every input would
  follow the proof rule above like any other different computation. No `Math.*` call inside a
  vector body except the per-lane `StrictMath` fallback.
- Java 25 only for 0.1.x, the Vector API module, no preview features. The Vector API is
  incubating and C2's lowering of it moves between JDK releases, so no other JDK is measured,
  and bit identity is tested on 25 alone (every workflow pins Zulu 25); there is no CI row
  for a newer JDK (the owner's decision of 21 November 2026, closing #38's step 5).
- Lines at most 100 columns; ASCII only, except for string literals that need it.
- Commits and pull requests end with `Generated-by: <tool> (<model>)` when AI tooling was used.

## 7. Open

- Benchmarks (JMH under `src/jmh`): `ExpBenchmark` measures `exp`'s two arms against the scalar
  `StrictMath.exp` loop at each vector width, and `LogBenchmark` does the same for `log`; each
  function adds its own benchmark the same way. `Log10Benchmark` does the same for `log10`, at 256
  and 512 bits, the preferred species and `SPECIES_MAX`. `Log1pBenchmark` does the same for `log1p`,
  with a third arm that blends the rare paths only when a vector holds a rare lane; `Expm1Benchmark`
  has the same three arms for `expm1`, `SinhBenchmark` one for `sinh`, `CoshBenchmark` two for
  `cosh` and `AsinBenchmark` one for `asin`. Each of the eight has rows at `SPECIES_MAX`
  (`*Max`), which the allocation check covers: the first seven ports' finish boxed there on mixed
  data until its first eight lanes were written out
  (`bench/results/max-finish-laptop-2026-11-20.txt`, then
  `bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`; neither has an asin row, whose
  finish was written in the new shape from the start). `ExpKindsBench` runs exp's two arms
  on one kind of rare input at a time (a NaN, an overflow, an underflow, a subnormal result), to
  find which kind costs an arm its time on mixed data (#41); it is named apart so that a run of
  `ExpBenchmark` and the allocation check's default regex leave it out, as `CoshKindsBench`, which
  has cosh's small-range shares, is. A function's body is larger than C2 inlines by default, so a
  call from a loop passes boxed vectors; the benchmarks are also run with
  `-XX:CompileCommand=inline,...` to measure the body as Varka emits it, inline.

### The 0.1.0 criteria

0.1.0 is the first release. It does not wait for Varka's first exact kernel: Varka pins the
released version, and the next function arrives as a patch or a minor.

- **What it holds:** the five ports, `exp`, `expm1`, `log`, `log1p` and `log10`, each in the
  forms `f(x)` and `f(x, live)`, each lane bit-identical to `StrictMath`.
- **The contract:** bit identity with `StrictMath` for every `double` input, compared by raw
  bits at every vector width of 128 bits or more. It is the one thing no version changes. How a
  function reaches its bits (an arm) may change in a patch, since every arm gives the same
  results; a result that differs from `StrictMath`'s is a bug, fixed in a patch.
- **The version promise (0.x):** a minor may break the public API, a patch may not; adding a
  function is a patch (`AGENTS.md`, "Releases").
- **What must hold on the commit that is tagged:** `mvn -B clean verify` with every test; the
  nightly's last sweep and fuzz green (looked at by the owner before the tag; the dry run does
  not gate on it); the proofs under both solvers; the allocation check; `dev/jar-check.sh
  --release` on the jars, which holds the licence files, the module name and a consumer on the
  class path and the module path; a `## [0.1.0]` changelog section.
- **What a client needs:** the jar's module is `io.github.vecbricks.vfdlibm`; the Vector API is
  incubating and the JVM does not resolve it unless told to, so a class-path client adds
  `--add-modules jdk.incubator.vector` at run time (a plain `javac` needed none on 25.0.5, and
  passing it is harmless), and a modular client `requires jdk.incubator.vector` and needs no
  flag at all.
- **How it is cut:** by the owner from a tag (`dev/release.sh`, then `mvn -B -Prelease deploy`);
  `release.yml` is a dry run only.
