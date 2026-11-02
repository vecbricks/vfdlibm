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
`e_exp.c`); `pow`, `sinh` and `cosh` build on it. `log` is done too (`VFdlibm.log`, from
`e_log.c`).

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
  - published hard cases: inputs where the exact result lies very close to a rounding boundary,
    from Lefevre and Muller's worst-case tables (ARITH 15, 2001) and Lefevre's list of
    round-to-nearest hard cases, one resource per function under
    `src/test/resources/.../hard-cases/` (so far `exp`, `log`, `log10` and `log1p`), loaded with
    `Inputs.hardCases("<function>")`. A port that keeps fdlibm's operations, or departs from
    them only where `PROOFS.md` proves the result equal, matches `StrictMath` on them by
    construction; they sit where a dropped correction term or a reassociated sum, an
    error far below one ulp, is most likely to change the returned double.
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
  lane is decided by measurement and recorded in the function's pull request. For `exp` the
  question is open again: on the EPYC 7763 the per-lane finish as first merged was 1.57x faster
  than the blend on common data and 1.64x slower on mixed data, where its fallback allocated;
  the fallback no longer allocates at 256 and 512 bits (it still boxes at `SPECIES_MAX` on
  mixed data), and the EPYC run of that change decides (`SKILLS.md`).

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
- Java 25, the Vector API module, no preview features.
- Lines at most 100 columns; ASCII only, except for string literals that need it.
- Commits and pull requests end with `Generated-by: <tool> (<model>)` when AI tooling was used.

## 7. Open

- Benchmarks (JMH under `src/jmh`): `ExpBenchmark` measures `exp`'s two arms against the scalar
  `StrictMath.exp` loop at each vector width, and `LogBenchmark` does the same for `log`; each
  function adds its own benchmark the same way.
  A function's body is larger than C2 inlines by default, so a call from a loop passes boxed
  vectors; the benchmarks are also run with `-XX:CompileCommand=inline,...` to measure the body
  as Varka emits it, inline.
- The first release is cut once Varka's first exact kernel lands and needs a pinned version.
