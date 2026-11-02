# SLEEF: bounds, tests and kernel structure

SLEEF is under the Boost Software License 1.0. Read for Varka on 15 October 2026 at commit
`7623d6c` (25 December 2025) of a shallow clone; its `CMakeLists.txt` says `SLEEF_VERSION 4.0.0`
(development, after 3.9). `CHANGELOG.adoc` names 3.8 "Fix oflow bound in log1p(f), exp and pow"
(#604, #606), so an older release's overflow thresholds may differ from the code below. Kernels:
`src/libm/sleefsimddp.c`, `src/common/dd.h`, `src/arch/helperadvsimd.h`; tests:
`src/libm-tester/`. **[V]** means the author read the code; **[I]** means inferred. Nothing was
built or run. Lane-op counts are hand counts of the non-AVX-512 path on an FMA target (one
vector instruction is one op), good to about 10 percent.

SLEEF is not fdlibm: its polynomials, reductions and use of FMA differ, so no SLEEF kernel is
bit-identical to `StrictMath`. What vfdlibm can take is how it states and tests a bound, and the
shape of its special-case handling.

## 1. What the tiers promise and how they are checked

* **Names [V].** `rename.h`: `xexp` is `Sleef_exp_u10`, `xlog_u1` is `Sleef_log_u10`, `xlog` is
  `Sleef_log_u35`; the two `hypot` tiers are separate kernels; `xfmod` has no tier.
* **Bounds the tests enforce [V].** `u05`: 0.5 (`tester2simddp.c`: `hypot_u05` and `fmod` fail
  above 0.5), so correct rounding up to the reference's precision; `u10`: 1.0 (exp, log_u1,
  pow, tanh, expm1, log10, log1p; `exp10` is allowed 1.09); `u15`: 1.5; `u35`: 3.5.
* **What a ULP is [V].** `testerutil.h` `countULP`: `|result - exact| / 2^(ilogb(exact)+1-53)`,
  floored at the subnormal quantum; NaN against NaN is 0, NaN against a number 10001, zero
  against nonzero 10000, signed-zero mismatch 10002 only when asked. The reference is the exact
  value (MPFR at 64 or 256 bits; binary128 in tester4), not another library.
* **So a SLEEF lane and a `StrictMath` lane can differ [I].** A result within 1.0 ulp of the
  exact value is one of its two neighbours; fdlibm's `exp` is also within 1 ulp, so a `u10` lane
  and fdlibm's can be one step apart (two if the exact value is a double). This is why vfdlibm
  cannot use `VectorOperators.EXP` and the others (`README.md`, "Why").
* **How the tests draw inputs [V].** None is exhaustive (`grep -i exhaust` over `src`, `docs`
  and the top-level files finds nothing). `tester2simddp.c` loops until 1000 failures on
  uniformly random 64-bit patterns (so most `exp` arguments saturate), with a few draws as
  neighbours of 0 and infinity. `tester.c` and `tester4simd.cpp` sweep fixed grids: `exp` over
  -10..10 at 0.002 and -1000..1000 at 1.1, and near `LOG_DBL_MAX` at 1e-5; `log` over 0.0001..
  10000, `2.1^i`, `DBL_MAX * 0.93^i`, `DBL_MIN * 0.996323^i`; `expm1` at `10^-d`; `pow` on a
  100 by 100 grid; `fmod` on two grids; and the list `ad` (NaN, +-inf, +-DBL_MAX, +-DBL_MIN,
  +-denorm_min, +-0, +-1, nextafter(+-1)). Signed zero is checked only on `ad` (tester4).
* **Lane independence [V].** Each test puts the argument in one random lane and random bits in
  the others (`memrand`), so a result that depends on a neighbour lane fails.
* **Special values follow C99 [V].** `tester.c` lines 841 and 844: "x is +1 and y is a NaN, the
  result is 1.0"; "y is 0 and x is a NaN, the result is 1.0". Java's `pow` differs on the first
  (`StrictMath.pow(1, NaN)` is NaN), so these cases belong in a `pow` port's fixed vectors.

## 2. Structure of the kernels (non-AVX-512 path, `sleefsimddp.c`)

* **`exp` u10, line 1404 [V].** `n = rint(x/ln2)`; `s = x - n*L2U - n*L2L` (two `vmla`; `L2U`
  has about 42 bits, so `n*L2U` is exact); Estrin `POLY10` for degrees 3 to 12, then Horner
  steps for 0.5, 1, 1; scale by `ldexp2` (multiplies by `2^(q/2)` and `2^(q-q/2)`); `x >
  LOG_DBL_MAX` gives +inf, `x < -1000` gives +0; NaN flows through. Count: 20 arithmetic ops,
  11 for the scaling, 4 for the clamps, about 35 in all. The special cases are clamps after
  the arithmetic, not a separate path: every lane takes the same instructions.
* **`log` u35, line 1358, and u10, line 1510 [V].** Subnormal rescale by `2^64` (and `e - 64`);
  `e = ilogb(d/0.75)`, `m = d * 2^-e` in [0.75, 1.5); `x = (m-1)/(m+1)`;
  `log d = e ln2 + 2x + x^3 t(x^2)` with `POLY7` in `x^2` (degree 15 in x). u35 does it in plain
  doubles with one division: about 40 ops. u10 does `m-1`, `m+1`, the division, `e ln2`, `2x`
  and the sum in double-double (`dd.h`): about 70 ops. Specials are selects: `+inf`, NaN or
  negative to NaN, zero to `-inf`. `log10` (2047) is the u10 shape with a dd multiply by
  2/ln10, about 8 ops more; `log1p` (2197) is the u10 shape on `m = fma(d, 2^-e, 2^-e - 1)`,
  which recovers the bits lost by `d + 1`.
* **`pow`, line 1596 [V].** `logk` (a full double-double log, about 90 ops) times `y` in dd,
  then `expk` (a double-double reduction and polynomial with the same scaling, about 75 ops),
  plus `visint`/`visodd` (one rint each) and about 10 selects: roughly 200 ops [I].
* **`hypot` u05, line 2383 [V].** dd `min/max`, dd square, `+1`, dd square root, times `max`;
  subnormal rescale by `2^54`; inf overrides NaN, zero `min` returns `max`. u35 is `max *
  sqrt(fma(t, t, 1))`: about 8 ops, no dd.
* **`tanh` u10, 1693; `expm1`, 2038 [V].** Both sit on `expk2`, a dd `exp`: `tanh = (e - 1/e) /
  (e + 1/e)` in dd (1 above 18.714973875); `expm1 = expk2(a) - 1` in dd (fixed overflow and
  underflow cut-offs, `-0.0` kept). Not fdlibm's formulas.
* **Specials of `pow` [V].** `y == +-inf` follows `(|x|-1) * sign(y)`: below 0 gives +0, equal
  gives 1, above gives +inf. A negative `x` with a non-integer `y` multiplies by NaN. The last
  select, `y == 0 || x == 1` gives **1**, comes after the NaN or-in, so `pow(1, NaN)`,
  `pow(NaN, 0)` and `pow(-1, +-inf)` are 1: C99, and unlike Java. `pow(+-0, y)` and
  `pow(+-inf, y)` take their sign from an odd integer `y` (`visodd`).

## 3. FMA, non-FMA, and bits

* **Two code paths exist [V].** `vmla` is fused only where the helper says so
  (`helperadvsimd.h` line 378: `CONFIG == 1`); `ENABLE_FMA_DP` selects one set of dd routines
  (`dd.h` lines 190 to 236), otherwise another that splits operands with `vupper` (Veltkamp).
  The file sets `FP_CONTRACT OFF`, so every fusion is explicit.
* **Do they give the same bits? Not stated, and not tested between the two [V].** Each variant
  is tested against the exact value. What is tested for bit identity is the FMA family:
  `tester3.c` writes a SHA-256 over 200001-point grids (`exp` -1000..1000, `log` 1e-300..1e14
  by bit pattern, `pow` over 451 by 451, also `hypot`, `fmod`, `tanh`, `expm1`) and compares it
  with **one file**, `hash_finz.txt`, for every ISA target. The SSE2 helper emulates `vfma` in
  software (`helpersse2.h` lines 181 to 189), so a CPU without FMA still gets the fused bits:
  one bit pattern per function across ISAs. A mul-plus-add `vmla` rounds twice, so the non-FMA
  build will differ somewhere [I].
* **For vfdlibm [I].** The same contract, one bit pattern on every host, is vfdlibm's, reached
  the other way: no FMA anywhere (`PLAN.md` section 6), so nothing depends on the host having
  one. A digest over fixed grids, with the argument in a random lane, is a cheap cross-host
  check to add beside the `StrictMath` comparison.

## 4. What does not vectorise without a branch

* **`fmod`/`remainder` loops** with an all-lane exit (`vtestallones`) and up to 21 passes;
  one slow lane holds the others.
* **`log1p` calls a whole second kernel**: `if (!LIKELY(vtestallones(d <= LOG1P_BOUND))) r =
  select(.., xlog_u1(d))`, for `d > 1e307` (line 2239 [V]). The rare lanes are found with one
  all-lanes test and the second kernel runs only when one is set: the shape of vfdlibm's
  per-lane finish, with a vector kernel in place of `StrictMath`.
* **AVX-512 `log` uses `getexp`, `getmant`, `fixup`**, with no Vector API equivalent; a Vector
  API kernel takes the integer path (exponent bits; `ldexp3` adds to the exponent field), as
  `Lanes.addExponent` does.
* **`rint` and `trunc` want hardware rounding** (`FULL_FP_ROUNDING`); otherwise `vrint2` is
  `(d + c) - c` with `c = +-2^52`. Branch-free and expressible in Java, which never reassociates.
* **`ldexp2` and `ilogb2k` use half-width `vint`** with 64-bit lanes, as vfdlibm's `exp` holds
  `k` in an int species at half the bits (`SKILLS.md`, "exp's k in int lanes").

## 5. Worth taking

1. **The random-lane filler**: put the argument in one lane and random bits in the others, so a
   lane that reads its neighbour fails. vfdlibm's tests fill whole vectors with inputs; a draw
   with one input among random patterns would catch a cross-lane bug the full vectors can hide.
2. **The list `ad`** and the C99 `pow` cases as fixed vectors, with Java's answers.
3. **A digest across hosts** over fixed grids, as section 3 says.
4. **Cost shape** for comparison: `exp` about 35 ops, `log` u35 about 40, u10 about 70, `pow`
   about 200.

## 6. Not worth taking

Any constant, polynomial or reduction (not fdlibm's, so not bit-identical; and under SLEEF's
licence), the dd towers, the AVX-512 `getexp` paths, `gencoef`, DFT, quad and single precision.
Not read: SVE and RVV helpers, `sinh` and `cosh`.
