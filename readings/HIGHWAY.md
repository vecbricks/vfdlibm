# Google Highway: bounds, tests and Java semantics

Highway is dual-licensed under the Apache License 2.0 or the BSD 3-Clause License, at the user's
choice (its `LICENSE`). Read for Varka on 15 October 2026 at commit `e8f6dfa` (15 October 2026);
cut here to the vector math parts. **[V]** means the author read the code; **[I]** means
inferred. Nothing was built or run; no number below was measured.

Highway's `contrib/math` is accurate to a few ulps against libm, not bit-identical to fdlibm, so
its kernels are not a source for vfdlibm. What carries over is how it states and checks a bound,
and where its semantics differ from Java's.

## 1. How Highway states and checks a math bound

* **The stated bound is a bit-pattern distance [V].** `hwy/tests/test_util.h` line 141,
  `ComputeUlpDelta`: equal values (so `-0` against `+0`, and infinities) give 0, two NaNs give
  0, else `max(bits) - min(bits)` as unsigned. Opposite signs give a huge count, so no tiny
  pair passes by straddling zero. vfdlibm's own check is stricter: raw bits equal, so `-0`
  against `+0` and two different NaN payloads both fail.
* **The test (`contrib/math/math_test-inl.h` `TestMath`) [V].** It strides over the bit
  patterns of a range (4000 samples per sign, `AdjustedReps`), puts one value in every lane
  with `Set`, reads lane 0, and compares with libm (`std::exp` etc.). It prints and asserts
  the maximum. `HWY_MATH_SWEEP_ALL` (off) sweeps every `float` pattern; `double` gets 1e8
  samples. A test that broadcasts one value reads every lane the same, so it cannot see a lane
  that depends on its neighbour [I].
* **Signed zero is tested apart, because the comparator hides it [V].** `math_test.cc`
  `TestSignedZero` compares bit patterns: the comment says the ULP comparator "treats +0 ==
  -0".
* **One number for every target and width [V].** The suite runs per supported target
  (`HWY_EXPORT_AND_TEST_P`) and per partial vector length (`ForPartialVectors`), but each
  function has one threshold, loosened for a target in a comment ("NEON is 3 instead of 2",
  `Log1p`; `ACosh32ULP()` is 8 on MinGW). Running every test at every width the host has, as
  vfdlibm's `VectorWidth` does, is the same idea; vfdlibm's threshold is zero everywhere.
* **The header bound is not always the tested bound, nor the tested range the stated one [V].**
  `Log` states 4 (`math-inl.h` line 2346 or so) and is tested at 1 (`math_test.cc`); `Pow` states 5
  and is tested at 8 (`kMaxErrorUlp`) over bases 2^-3 to 2^4 and exponents 2^-4 to 2^5 only;
  `Exp` states a range to +706 for `double` and is tested only to +104; `sin`, `cos` and `tan`
  state and test `[-39000, 39000]` only. A test's input range is part of its claim: vfdlibm's
  sampled bit patterns cover the whole `double` domain for that reason.
* **Stated bounds, `float` and `double` alike unless noted [V], header lines 1758 to 3066.**
  1: `exp`. 2: `acos`, `asin`, `log10`, `log1p`, `log2`, `exp2`. 3: `acosh`, `asinh`,
  `atan`, `atanh`, `sin`, `cos`. 4: `log`, `expm1`, `sinh`, `cosh`, `tanh`, `hypot`, `tan`
  (`double` 3). 5: `pow`. 6: `cbrt`. `atan2` states none and is tested by a table with an
  absolute tolerance of 1e-5 (`math_tan_test.cc` lines 138 to 184).

## 2. Where Highway differs from Java

1. **`pow` is C99's [V].** `Pow` forces the exponent to 0 when `|a| == 1` (`math-inl.h`
   lines 2508 to 2517), so `pow(1, NaN)` and `pow(-1, inf)` are 1. Java gives NaN for both,
   and a `pow` port's fixed vectors should hold them. Highway's `pow` takes `log2` in extra
   precision (`ExtPrecLog2ForPow`) and has none of fdlibm's exact-exponent branches [I].
2. **Float to integer pins down overflow, not NaN [V].** `quick_reference.md` line 2098:
   `ConvertTo` "returns the closest representable value if the input exceeds the destination
   range"; `ConvertInRangeTo` is implementation-defined. `x86_128-inl.h` line 13684 builds
   `ConvertTo` for `f32` to `i32` as one compare `v >= 2147483648.0f`, a blend with the
   maximum, over `cvttps2dq`: negative overflow needs no fix because the hardware answer is
   already the minimum [I, from the comment at line 5170 that out-of-range gives
   `LimitsMin`]. The test (`convert_test.cc` lines 1121 to 1143) ORs an all-ones mask over NaN
   lanes on both sides, so a NaN result is explicitly unspecified. Java's `(int)NaN` is 0 and a
   too-large value saturates; a vector `D2I` in vfdlibm needs neither fix only where, as in
   `exp`, the lanes it would get wrong are never used (`SKILLS.md`, "exp's k in int lanes").

## 3. Not worth taking

* **The kernels and their coefficients**: tuned to a libm reference, not fdlibm.
* **The `Fast*` kernels**: relative errors of 1e-5, not a bound in ulps.
* **Per-target dispatch** (`HWY_EXPORT`, a function-pointer table filled at first call): C2
  picks the instructions per species already.

Not read: the NEON and SVE paths and `f16_math`. The integer sections of Varka's note
(multiply, divide, 128-bit compares) are left out here, as vfdlibm has no integer functions.
