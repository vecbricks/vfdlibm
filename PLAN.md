# vfdlibm: the plan

## 1. Goal

Vector API versions of fdlibm's double functions, each lane bit-identical to
`java.lang.StrictMath` (a two-NaN lane of a function of two arguments aside), released as a small
library under `io.github.vecbricks`.

## 2. How it grows: one function at a time, with Varka

The library is filled step by step. When Varka needs an exact vector form of a function, that
function is ported here and in Varka's kernel together, and the two are cross-checked:

1. **Port it here.** Port the function from fdlibm 5.3's C file (`e_exp.c`, `s_expm1.c`, ...) into
   a static method over `DoubleVector`, with that file's Sun notice in the source header. The C
   files are committed unchanged under `reference/fdlibm-5.3/`, and a port cites the one it follows.
   The port may compute differently from the C where a proof in `PROOFS.md` shows every result
   equal (section 6).
2. **Test it here** against `StrictMath`, lane by lane and bit for bit (section 4;
   a two-NaN lane of a function of two arguments by `PairBits`).
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
provisionally, until a host run decides, section 5). `asin` is done
too (`VFdlibm.asin`, from `e_asin.c`, with `sqrt` as the Vector API's `SQRT`, one arm, and the
lanes of `|x| >= 1` finished per lane), and so is `atan` (`VFdlibm.atan`, from `s_atan.c`, five
ranges through one division on blended operands, the lanes from `2^66` up finished per lane), and
so is `acos` (`VFdlibm.acos`, from `e_acos.c`, built as asin is), and so is `atan2`
(`VFdlibm.atan2`, from `e_atan2.c`, the first function of two arguments: the special lanes blended
over the general path, which calls `atan`'s core, no lane finished per lane, and two arms, the
blended one and the guarded one `VFdlibm` calls at every species, by the owner's decision of 22
November; section 5 below gives the case), and so is `hypot` (`VFdlibm.hypot`, from `e_hypot.c`,
every finite lane in the vector with the C's stale words after its `2^1022` scaling, one arm, and
the lanes of an infinity or a NaN finished per lane), and so is `pow` (`VFdlibm.pow`, from
`e_pow.c`, the last of the thirteen: its common path in the vector, a positive normal `x` that is
not 1 and a finite `y` of `iy <= 0x41e00000` that is not 0, +-1, 2 or 0.5, with a normal result,
and every other lane finished per lane, by the owner's decisions of 23 November 2026 on issue 34;
`PROOFS.md`, L19 and L20). So all thirteen are ported.
`log`, `log10` and `log1p` are done too (`VFdlibm.log`, from
`e_log.c`, `VFdlibm.log10`, from `e_log10.c`, which computes through log's common path, and
`VFdlibm.log1p`, from `s_log1p.c`), and so is `expm1` (`VFdlibm.expm1`, from `s_expm1.c`, whose
reduction is exp's).

`tan`, `tanh` and `cbrt` are fdlibm on aarch64 but Intel LIBM stubs on x86, so a port of them
matches the JVM only on Arm. `sin` and `cos` are HotSpot's own stubs on both architectures, so
`Math.sin` and `Math.cos` are not fdlibm anywhere; a port of them matches `StrictMath` only. These
five are a later, optional set; the large-argument reduction of the trigonometric ones
(`__ieee754_rem_pio2`, `__kernel_rem_pio2`) stays a per-lane `StrictMath` call.

## 4. Testing

- **The oracle** is `StrictMath` on the same JVM, compared by `doubleToRawLongBits`; for a function
  of two arguments, a lane of two NaNs with different bits is compared by `PairBits`, against the
  candidates the function declares (`PROOFS.md`, "The contract of a function of two arguments").
- **Inputs:**
  - sampled bit patterns over the whole `double` domain;
  - dense sweeps around each threshold fdlibm branches on;
  - fdlibm's special cases (NaN, the infinities, `+-0.0`, subnormals, overflow and underflow
    edges);
  - published hard cases: inputs where the exact result lies very close to a rounding boundary, from
    Lefevre and Muller's worst-case tables (ARITH 15, 2001) and Lefevre's list of round-to-nearest
    hard cases, one resource per function under `src/test/resources/.../hard-cases/` (so far `exp`,
    `expm1`, `log`, `log10`, `log1p`, `sinh`, `cosh`, `asin`, `atan` and `acos`), loaded with
    `Inputs.hardCases("<function>")`, and for a function of two arguments a file of pairs loaded
    with `Inputs.hardCasePairs` (`atan2`, derived from atan's inputs; `hypot`, computed by
    `dev/hypot-hard-cases.py` with exact integer arithmetic, and `pow`, self-computed the same way
    by `dev/pow-hard-cases.py` (exact results, ties, results near a midpoint and the doubles
    beside the result's thresholds); and beside each of those two a second file from CORE-MATH's
    published lists (MIT), `hypot-coremath` and `pow-coremath`, which `dev/coremath-hard-cases.py`
    takes by line number from `src/binary64/hypot/hypot.wc` and `src/binary64/pow/pow.wc` at a
    pinned commit and hash: their ties, the pairs nearest a midpoint or a double, and pow's exact
    results, issue #144). The
    inputs of Lefevre's list come out of `dev/testlibm-extract.py`, which rechecks each line with
    400-digit arithmetic against exact values it derives itself (`sinh`'s, `cosh`'s, `atan`'s,
    `asin`'s and `acos`'s files were written by it). A port that keeps fdlibm's
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
  log1p, sinh, cosh, atan, asin, acos, atan2, hypot and pow (atan2, hypot and pow, of two arguments,
  one argument at a time) with three low words each (every branch fdlibm decides on the high word)
  at every width but 128 bits on x86, where a rotating 1/32 slice a night covers it in 32 nights,
  and at 128 bits on Arm every night; it fuzzes from a fresh seed at every width, and runs every
  proof under both solvers; a failure opens an issue labelled `nightly-failure` with the mismatching
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
  rare lane, behind one `anyTrue`, as the guarded arms of `log1p` and `expm1` do. The readings
  are in `SKILLS.md`, port by port.
  For `exp` the per-lane finish allocates nothing at 256 bits, 512 bits and `SPECIES_MAX` of up to
  eight lanes in the laptop reading `bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`
  (allocation only, no timing; it boxed at `SPECIES_MAX` before its first eight lanes were written
  out with constant indices, `bench/results/max-finish-laptop-2026-11-20.txt`). That change is not
  free in time: the pair on the EPYC 7763 (`bench/results/exp-log10-pair-epyc7763-2026-11-21.txt`
  and its re-run `exp-log10-pair-rerun-epyc7763-2026-11-21.txt`) reads exp's two mixed finish rows
  7.4% to 9.3% slower by median and 8.3% to 8.9% by best (under its 10% threshold), log10's
  2.6% to 4.0% by median and 3.7% to 4.1% by best, and the `*Max` rows on mixed data faster, since
  they no longer box; the cause of the slowdown is not known, and only one AVX2 host was paired,
  with no AVX-512 host or other port.
  The host files read the two arms by minimums: on the EPYC 7763
  (`bench/results/exp-epyc7763-2026-11-20.txt`, two runs) the finish is 1.99 to 2.13 times faster
  than the blend on common data and 1.50 to 1.57 on mixed data, at 256 bits and the preferred
  species; on the Xeon Platinum 8573C
  (`bench/results/exp-xeon8573c-2026-11-21.txt`, one run) 1.23 to 1.37 on common data, a ratio that
  straddles 1.3x, and 1.70 to 1.84 on mixed data, at 256 bits, 512 bits and the preferred species.
  The code has changed since both runs (a fault constant and a local in the k conversion, and the
  finish's first eight lanes written out with constant indices). Of today's `Exp.java`, two runs on
  each host read finish over blend too, before and after #174 changed `Hypot.java`, which is under
  `ExpBenchmark` by `bench/record.py`'s rule (`exp-epyc7763-2026-11-23.txt` and
  `exp-epyc7763-2026-11-24.txt`, `exp-xeon8573c-2026-11-24.txt` and
  `exp-xeon8573c-2026-11-24-2.txt`): the blend over the finish 1.98 to 2.12 on common data and 1.37
  to 1.44 on mixed data on the EPYC, 1.25 to 1.42 and 1.70 to 1.81 on the Xeon, at every vector row
  of 256 bits or more.
  Which arm each function keeps follows the two rules issue #30 set on 21 November 2026: a tie
  within 1.3x on every host that has a file goes to the finish arm, except for `cosh`, whose
  per-lane set is not rare, so a host run decides its arm; and where hosts disagree, each species
  keeps the arm its own rows choose by that rule. The `*Max` rows of the host files of 3 to 21
  November for `exp`, `expm1`, `log`, `log10` and `log1p` (`exp-onediv-xeon8573c-2026-11-03.txt` and
  those of 20 and 21 November; `log1p-xeon8573c-2026-11-03.txt` and `expm1-xeon8573c-2026-11-04.txt`
  have no `*Max` row) time the finish that boxed at `SPECIES_MAX` before issue #37, so they are left
  out of both rules; a later run's `*Max` rows time today's finish. On the other rows no function
  needs rule 2 yet, so `VFdlibm` runs one arm at every species. Every default stays provisional
  until a host run revises it. Meanwhile `VFdlibm` calls the finish arm of `exp`, `log`, `log10`,
  `expm1` and `log1p`, the last two by rule 1 since the 8573C's second run of their code, which the
  owner's deferral of 21 November waited for: by minimums neither other arm is lower than the
  finish by more than 1.3x at any species, in both runs of `expm1-xeon8573c-2026-11-22.txt` and
  `log1p-xeon8573c-2026-11-22.txt` and of `expm1-epyc7763-2026-11-23.txt` and
  `log1p-epyc7763-2026-11-23.txt`, a tie, with the guarded arm lower inside 1.3x on some mixed rows;
  the slice arm of `cosh`, provisionally, as the rule's exception, whose arm a
  host run decides ([issue #30's
  comment](https://github.com/vecbricks/vfdlibm/issues/30#issuecomment-7091848602); its readings are
  under the list); and the guarded arm of `atan2`, which has no finish arm, so rule 1 does not pick
  between its two ([issue #30's corrected
  comment](https://github.com/vecbricks/vfdlibm/issues/30#issuecomment-7085437272)). The Xeon has
  timed atan2's arms in three runs (`atan2-xeon8573c-2026-11-22.txt`) and the EPYC in a
  run and two re-runs (`atan2-epyc7763-2026-11-22.txt`, whose third run reads blended over guarded
  at 1.27 at the preferred species on common data), and neither file gives them a direction, so
  guarded stays the
  default at every species by the owner's decision of 22 November.
  cosh's readings, by minimums: on `CoshBenchmark` the EPYC 7763's two runs agree, outside 1.3x, on
  the finish arm lower on common data (slice over finish 1.33 to 1.37), a direction, and give no
  direction on mixed data (`bench/results/cosh-epyc7763-2026-11-21.txt`); on `CoshKindsBench` the
  finish arm reads lower outside 1.3x with no small lane (1.46 to 1.70 on both hosts), lower inside
  1.3x at 3.5% of small lanes, and the slice arm lower inside 1.3x at 17%
  (`cosh-kinds-epyc7763-2026-11-21.txt`, `cosh-kinds-xeon8573c-2026-11-21.txt`). Of the code as it
  is now, each host has one `CoshBenchmark` run (`cosh-xeon8573c-2026-11-24.txt`,
  `cosh-epyc7763-2026-11-23.txt`): both read the finish arm lower on every row, slice over finish
  1.29 to 1.38 on the Xeon and 1.28 to 1.36 on the EPYC, no direction from one run; a second run on
  each host decides.

## 6. Rules

- Port from fdlibm 5.3's C sources only (netlib), as committed in `reference/fdlibm-5.3/`. Never
  copy from or translate OpenJDK's `FdLibm.java`, HotSpot's Intel LIBM stubs or glibc, which are
  GPL or LGPL.
- The result must be the same; the implementation may differ. Every lane stays bit-identical to
  `StrictMath` (a two-NaN lane of a function of two arguments aside), and a port may compute
  differently from the C only where a written proof, formal or informal, shows the result equal for
  every input. Informal: step by step, citing each IEEE property used and each hinge condition, with
  the input range where it holds and why the lanes are in it. Formal: machine-checked, following
  Varka's `sql/varka/proofs/` conventions, or reduced to a finite check by a theorem (for example,
  monotonicity of correctly rounded operations plus evaluation at the boundary doubles). Proofs live
  in `PROOFS.md` as numbered lemmas (statement, hypotheses, proof, and the test that checks the
  hinges, which must fail if a hinge were false), with a short Javadoc pointer at the code.
- No fused multiply-add, no reassociation, no division by a reciprocal: they change results in
  general, so no proof covers them as a rule. A specific use proven equal for every input would
  follow the proof rule above like any other different computation. No `Math.*` call inside a
  vector body except the per-lane `StrictMath` fallback.
- Java 25 only for 0.2.x, the Vector API module, no preview features. The Vector API is
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
  `cosh`, `AsinBenchmark` one for `asin`, `AtanBenchmark` one for `atan`, `AcosBenchmark` one for
  `acos`, `Atan2Benchmark` two for `atan2` (the blended and the guarded arm, over two arrays) and
  `HypotBenchmark` one for `hypot` (over two arrays; `hypot-epyc7763-2026-11-23.txt` reads it
  before #174 ordered its arguments, a run and its re-run: no direction against the scalar loop at
  the preferred species, and `finishMax` 1.6x slower than `finishPreferred` on common data in both;
  `hypot-epyc7763-2026-11-24.txt` reads it after #174, one run, the scalar loop lower on mixed data
  by 1.43 to 1.44, which wants a re-run, and `finishMax` at the level of the other rows), and
  `PowBenchmark` one for `pow` (the same; `pow-epyc7763-2026-11-23.txt` and
  `pow-xeon8573c-2026-11-24.txt` read it, one run each, its `rareOracle` row the allocation
  baseline).
  Each of the thirteen has rows at `SPECIES_MAX` (`*Max`), which the allocation check covers: the
  finish of `exp`, `expm1`, `log`, `log10` and `log1p` boxed there on mixed data until its first
  eight lanes were written out (`bench/results/max-finish-laptop-2026-11-20.txt`, then
  `bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`, a laptop reading of allocation only);
  no file reads the earlier finish of `sinh` or `cosh`, whose current rows the host files of 21
  November read (`bench/results/sinh-xeon8573c-2026-11-21.txt` and the like). `asin`, `atan` and
  `acos` were written with the new finish from the start (as `hypot` was since), and `atan2`
  finishes no lane per lane; the host files of 22 November read their rows
  (`bench/results/asin-xeon8573c-2026-11-22.txt`
  and the like). `ExpKindsBench` runs
  exp's two arms on one kind of rare input at a time (a NaN, an overflow, an underflow, a subnormal
  result), to find which kind costs an arm its time on mixed data (#41); it is named apart so that
  a run of
  `ExpBenchmark` and the allocation check's default regex leave it out, as `CoshKindsBench`, which
  has cosh's small-range shares, is. A function's body is larger than C2 inlines by default, so a
  call from a loop passes boxed vectors; the benchmarks are also run with
  `-XX:CompileCommand=inline,...` to measure the body as Varka emits it, inline.

### The 0.1.0 criteria

0.1.0 is the first release. It does not wait for Varka's first exact kernel: Varka pins the
released version, and the next function arrives as a patch or a minor.

- **What it holds:** the five ports, `exp`, `expm1`, `log`, `log1p` and `log10`, each in the
  forms `f(x)` and `f(x, live)`, each lane bit-identical to `StrictMath`.
- **The contract:** bit identity with `StrictMath` for every `double` input (a two-NaN lane of a
  function of two arguments aside: `AGENTS.md`, "Releases"), compared by raw bits at every vector
  width of 128 bits or more. It is the one thing no version changes. How a function reaches its bits
  (an arm) may change in a patch, since every arm gives the same results; a result that differs from
  `StrictMath`'s is a bug, fixed in a patch.
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
