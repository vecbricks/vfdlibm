# Proofs: where a port computes differently from fdlibm's C

Every lane of every function here is bit-identical to `StrictMath`, but for a two-NaN lane of a
function of two arguments ("The contract of a function of two arguments"). A port follows the C
operation by operation, except where a lemma below proves that a different computation gives the
same double for every input (`PLAN.md` section 6). Each lemma names its hypotheses (the input domain
and each hinge condition, with the range where it holds and why the lanes are in it), the IEEE 754
property it rests on, the proof, and the test that checks its hinges; that test fails if a hinge
were false. The code carries a pointer to the lemma. A port with no departure of its own (log10,
log1p) has a section saying so; log10's names the lemmas it rests on through `log` and holds
L17, on Varka's form of it, and log1p's holds L12, a counter-example (a statement and not a proof
of a fold), and L18, on Varka's form of it.

The tests are of two kinds, and each lemma names both. Hinge tests in `ProofsTest` mostly check a
lemma's hypotheses on scalar Java, where each operation is the same single rounding as a vector
lane's (R4); they prove the arithmetic, not that the vector code does it. The vector tie runs the
port's own vector code against `StrictMath` at the lemma's boundaries, so a port that strayed
from the proven computation fails there.

The proofs are informal, or reduced to a finite check by a theorem, and parts of them are
machine-checked in `proofs/` under the conventions of Varka's `sql/varka/proofs/` (SMT-LIB over
integers, a double as `M * 2^E`, rounding as the `jdouble.near` relation to the nearest double in
a named binade, not SMT's floating-point theory; `unsat` checks are the proofs, `sat` checks show
the domain is not vacuous and that a constant changed by one is refuted, for the constants a claim
is tight to: not `invln2` and `P1..P5`, as L2's **Encoding** says, nor expm1's `Q1..Q5`, and
not L10's section A, a ground comparison of two rendered constants; `proofs/README.md`).
Each lemma ends with an **Encoding** paragraph naming its file and saying which steps are
machine-checked and which stay informal. The files' constants are rendered from `Exp`, `Lanes`,
`Log` and `Expm1` by `ProofFiles`, so `ProofFilesTest` fails when one moves without its proof;
`dev/prove.sh --solver both` runs them under Z3 and cvc5.

Notation: `fl(v)` is `v` rounded to the nearest double, ties to even (Java's and fdlibm's only
rounding mode; the JLS fixes it, and fdlibm assumes it). `hx` is the high word of `|x|`, as
`e_exp.c` computes it (`__HI(x) & 0x7fffffff`). Line numbers are `reference/fdlibm-5.3/e_exp.c`'s.

IEEE properties used:

- **(R1) Sign symmetry.** Round to nearest, ties to even, is symmetric: `fl(-v) = -fl(v)` for every
  real `v`, since the set of doubles is symmetric about 0 and so is the tie rule. Hence `fl(a - b)
  = -fl(b - a)` and `fl(a / -b) = -fl(a / b)`, bit for bit, whenever the result is not zero.
- **(R2) Monotonicity.** `v <= w` implies `fl(v) <= fl(w)`. So `x -> fl(a * x)` for a constant
  `a > 0` and `u -> fl(u + b)` are non-decreasing, and so is their composition.
- **(R3) Exact operations.** `fl(+-1 * v) = +-v`; `fl(+0 * v) = +0` for finite `v > 0`; `x - (+0) =
  x` for every `x`, `-0` included; `+0 - v = -v` for every `v != 0`.
- **(R4) Correct rounding of the basic operations.** `+ - * /` and `sqrt` return `fl` of the exact
  result; with no fused multiply-add, each Java operation is one such rounding, as each C
  operation is. For `sqrt` the sources are the JDK 25 API documents (not its sources):
  `Math.sqrt` and `StrictMath.sqrt` return "the double value closest to the true mathematical
  square root", `NaN` for a `NaN` or a negative argument, `+infinity` for `+infinity` and the
  argument itself for either zero; and a vector's `VectorOperators.SQRT`, `DoubleVector.sqrt()`,
  is "conforming to the specification of `Math.sqrt(a)`" on each lane. The C calls `sqrt` in
  e_asin.c (`s = sqrt(t)`, line 100) e_acos.c (`s = sqrt(z)`, lines 89 and 95) and
  e_hypot.c (`w = sqrt(...)`, lines 99 and 108), and does
  not define it: fdlibm 5.3's e_sqrt.c is not
  among the files in `reference/`, and no claim is made here about it. The claim is the
  oracle's: a lane of the operator is `StrictMath.sqrt`'s result. That the C's `sqrt` is that
  function is not shown here; a port that uses the operator shows it by its tests against
  `StrictMath`. What the documents leave open, and (R4) does not claim: the payload and sign of
  a `NaN` result. `SqrtTest` pins the
  lane's raw bits against `StrictMath.sqrt` on the boundary doubles (the zeros, every power of
  two and its neighbours, the squares of the integers to 4096 and theirs, the subnormals, the
  smallest normal, the largest double, the infinities, `NaN`s of several payloads, negative
  numbers) and, unmasked, 8.7 million random doubles of both signs at 128, 256 and 512 bits and
  the preferred species (a width the host lacks is skipped there). The masked form is checked
  on the boundary doubles and 2^19 random bit patterns at those widths; `SPECIES_MAX` and
  the three fixed widths, whether the host has them or not, on the boundary doubles and 2^18
  random bit patterns, masked and unmasked (a width above the host's runs in Java there). That
  the operator lowers to a vector instruction and does not box is a separate fact, read from
  `bench/alloc-check.py` on `SqrtBenchmark` (unmasked only; no row for the masked form).

## The contract of a function of two arguments

Each lane of a function of two arguments equals `StrictMath`'s result by
`Double.doubleToRawLongBits`, except on a lane where both arguments are NaN and their bits differ.
There the result must be one of two candidates, all 64 bits, and which one is not specified. The
candidates are the two arguments' bits with the quiet bit set (`atan2`, `pow`: declared
`OPERANDS`), or the same with the sign cleared (`hypot`: `MAGNITUDES`). Each function declares
one on its `Nightly.PairFunction`, and there is no default. The oracle's own bits must be
candidates too, so a function declared the wrong way fails on the first lane where the two
declarations differ. Every other lane keeps the raw-bit rule, for all three functions: a NaN made
from arguments that are not both NaN, one NaN argument with a finite one, and two NaNs with
identical bits.

The exception is there because `StrictMath.atan2` returns `x + y` on two NaNs (as the C of `pow`
does, reading `reference/fdlibm-5.3`), and which operand's payload a sum of two NaNs keeps is
fixed neither by IEEE 754 nor by the JLS. The C of `hypot` adds the absolute values of its
arguments, and `StrictMath.hypot` returned a result with the sign cleared in every pair of every
mode of the probe, so its candidates are the magnitudes.

The probes (JDK 25.0.5.1, x86-64, 65,536 random pairs a mode, issue 33) found on two NaNs with
different bits that `StrictMath.atan2`, `hypot` and `pow` each returned exactly one candidate and
never anything else, and that which one changed with the mode: `-Xint`, C1 only and C2 only each
returned one fixed argument's payload once warmed up, and default tiering moved from one to the
other. For `hypot` that argument is not the NaN with the larger high word that `e_hypot.c`
orders its operands by, so no port or test may assume either. A raw-bit comparison with
`StrictMath` fails by the tier the oracle's call is in.

`PairBits.matches` is the one comparison of such a lane, used by `Oracle`'s pair asserts and the
nightly's pair check. `PairNanTest` shows that it fails for a canonical NaN, a NaN of a third
payload, the other sign, a result not quieted, a result with the sign kept where `MAGNITUDES` is
declared, a function declared the wrong way, one that declares nothing, and one payload bit off on
a lane that is not two different NaNs. A function of one argument keeps the raw-bit rule on every
lane: its NaN results were the same in every mode in the same probe.

## exp (`Exp.java`, from `e_exp.c`)

The C reduces `|x| > 0.5 ln2` in two forms (lines 129-136), the near form for `|x| < 1.5 ln2` and
the far form above it, and leaves `|x| <= 0.5 ln2` unreduced with its own tail, line 147. The port
computes the far form and line 148's tail for every lane: L1 and L2 prove that this is the C's
result on the other two paths. The tiny lanes, `|x| < 2^-28`, keep the C's `one + x` (L4).

### L1. The k == 0 path is the general tail with hi = x, lo = +0, k = 0

**Statement.** Let `x` be a double with `0x3e300000 <= hx <= 0x3fd62e42`, that is `2^-28 <= |x| <
X1`, `X1 = 0x3fd62e43_00000000` (about 0.3465735912). Set `k = 0`, `t = +0.0`, `hi = x - fl(t *
ln2HI[0])`, `lo = fl(t * ln2LO[0])`, `r = hi - lo`, `c` from `r` by line 146, and `y = one - ((lo -
(r*c)/(2.0-c)) - hi)` with `k << 52` added to its bits. Then `y` is, bit for bit, line 147's
`one-((x*c)/(c-2.0)-x)` with `c` from `x`.

**Hypotheses.**
- (H1) `k` is forced to 0 by the C's own test, line 128's `hx > 0x3fd62e42` false (the port
  replaces the cast's argument by `+0.0` on those lanes, so `D2I` gives 0 and `I2D` gives `+0.0`).
  The formula alone is not enough: at `x = 0x3fd62e42_ffffffff`, inside this domain but above 0.5
  ln2, `fl(fl(invln2*x)+0.5) = 1.0000000013739174` and the cast gives 1; of the 34311202 doubles of
  word `0x3fd62e42` (both signs) where it gives `+-1`, 2383195 would return a different result,
  the first at `0x3fd62e42_fefa3a08`.
- (H2) On the domain, `c` has `x`'s sign and `0.94|x| <= |c| < 0.37`. (`|c| < 0.35` would be
  false: at `x = -0x3fd62e42_ffffffff`, `c = -0.36655...`.) Why: `T = fl(x*x) <= x^2 (1 + 2^-53)
  <= 0.1202`; the computed bracket `P1 + T*(...)`, after its eight roundings (four products, four
  sums, each a relative error of at most `2^-53` by (R4)), lies in `(0.1663, 0.1667)`, whose width
  absorbs them; and the product with `T` is one more rounding. With `|x| < 0.3466`, that gives
  `0 < fl(T * bracket) <= 0.1667 * 0.3466 |x| (1 + 2^-53)^2 <= 0.0578 |x| (1 + 2^-51)`. Then
  `c = fl(x - that)`, a last rounding: for `x > 0`, `c >= 0.9422 x (1 - 2^-50) > 0.94 x`; for
  `x < 0`, `|c| <= 1.0578 |x| (1 + 2^-50) < 0.3667`. Each margin (about 0.002 and 0.003) is
  some `2^40` times the rounding terms.
- (H3) `q = fl(fl(x*c) / fl(2.0-c))` is positive and normal: `x*c > 0` by H2, `|x*c| >= 0.94 *
  2^-56`, far above `2^-1022`, so `fl(x*c) > 0`; `2 - c` is in `(1.63, 2.37)`, so `q > 0` and no
  underflow.

**Proof.**
1. By (R3), `fl(+0 * ln2HI[0]) = +0`, `hi = x - (+0) = x`, `lo = fl(+0 * ln2LO[0]) = +0` and `r =
   x - (+0) = x`. So `t = x*x` and `c` are line 146's, computed from the same `x`.
2. `fl(c - 2.0) = -fl(2.0 - c)` by (R1), and it is not zero since `c != 2` (H2). Then `fl(fl(x*c) /
   fl(c-2.0)) = fl(fl(x*c) / -fl(2.0-c)) = -q` by (R1). So line 147's inner term is `-q`.
3. Line 148 with `lo = +0`: `fl(+0 - q) = -q` by (R3), since `q != 0` (H3). Then `fl(-q - hi) =
   fl(-q - x)`, line 147's next step, and `one - ` that is the same final rounding.
4. The scale-back adds `0 << 52 = 0` to `y`'s bits, and `0 >= -1021`, so neither the subnormal
   scale-back nor the finish takes the lane; the C returns line 147's value with no scale-back.
   With H1, the lanes the port treats this way are exactly the C's `k == 0` lanes; the tiny lanes
   (`hx < 0x3e300000`) also get `k = 0` but are replaced by `one + x` afterwards, as in the C.

(If H3 failed with `q = +0`, step 3 would give `+0` where the C has `-0`; the result would still
be equal, since `+-0 - x` is `-x` for `x != 0` and `one - (+-0)` is `one`, but the lemma does not
rely on it.)

**Hinge tests (scalar).** `ProofsTest.l1ZeroReductionLeavesX` (step 1, over both ends of the
domain, their neighbours and 65536 seeded draws over every binade, both signs);
`ProofsTest.l1SignSymmetryAndPositiveQuotient` (H2, H3 and step 2 on the same inputs, and the two
tails equal); `ProofsTest.l1KIsForcedByTheHighWordTest` (H1: the formula gives `k = +-1` at the top
of the word, and at `0x3fd62e42_fefa3a08` the far form with `k = 1` is not exp);
`ProofsTest.l1DomainWidenedByAWordIsRefuted` (the `sat` case below). **Vector tie.**
`ExpTest.foldBoundaries` and `foldBoundariesAtMaxSpecies` run both arms (`VFdlibm.exp` and
`Exp.expBlended`), unmasked and masked, against `StrictMath` at the words `0x3e2fffff`,
`0x3e300000`, `0x3fd62e42`, `0x3fd62e43`, `0x3ff0a2b1` and `0x3ff0a2b2`, both signs, low words 0,
`0xffffffff` and 1024 seeded, plus the 4096 doubles each side of `+-0.5 ln2`, at every width
and `SPECIES_MAX`. With the `k` forcing removed, the planted fault `exp.dropKForcing`
(`Exp.DROP_K_FORCING`), eight `ExpTest` cases fail at each width they run:
`thresholdsAndSpecials`, `foldBoundaries`, `foldBoundariesAtMaxSpecies`, `masked`,
`maxSpecies`, `faultRunInputsPass`, and `coreAndRareMaskAreTheFinishArm` with its
`AtMaxSpecies` form. 18 of `thresholds()`' 2620 inputs differ, the first in array order
`0x3fd62e42_ffffffcb`, and 571 of `foldBoundaries`' 28698, the first `0x3fd62e42_fefa3a08`,
which is H1's. That list is a run of `-Dtest=ExpTest` with the fault, which CI does not make;
what CI checks is `ExpTest.faultCatchers`, which requires both arms to fail at each width the
host has on `faultRunInputs()`.

**Encoding.** `proofs/exp_zero_reduction.smt2`, partly machine-checked. Checked: step 1's and
step 3's `x - (+0) = x` and `+0 - q = -q` (the exact value is the double or its negation, which
`jdouble.near` admits in every binade at once, and admits no other double in its binade or the
ones next to it, either sign, for every binade from `2^-60` to `2^-2`; the domain's ends,
rendered from `Exp`'s words, lie in `2^-28` and `2^-2`); that `ln2HI[0]` and `ln2LO[0]` are
positive and finite; step 4's scale-back, `y`'s bits plus `0 << 52` for every long, and `k = 0`
not below `K_MIN_NORMAL`; H1's witness, that at the domain's top double `0x3fd62e42_ffffffff` the
formula's `s` rounds into `[1, 2)`, so the cast would give 1; and the refutation below, every
rounding evaluated. Step 2 is `proofs/sign_symmetry.smt2`'s, for `c` in `[-2, 1]`. Not checked,
and informal above: H2 and H3, which bound the polynomial's eight roundings and multiply unknowns
(`x*c`, `T*bracket`), so they are sampled by `ProofsTest`; with them the hypothesis of step 2 and
the binades of `q` (`2^-59` to `2^-4`) that the check covers; the sign of the zero product
`fl(+0 * v) = +0` (IEEE 754 6.3; zero is not in the prelude); that a value rounds to no double in
a binade further off than the next (the arithmetic of rationals); the casts of `+0.0` and the
blend that puts it on exactly the unreduced lanes, which are the code's text. The file refutes the
domain's top word changed by one, `0x3fd62e43`, whose lanes the C reduces: there the `k = 0` tail
first gives a different double at `x = 0x3fd62e43_00000004` (positive) and `x =
0xbfd62e43_0000001f` (negative), shown by evaluating both computations one rounding at a time
and an `unsat` check that the results are equal. The word's
low words 0 and 1 are not witnesses: there the tail gives exactly `StrictMath.exp`
(`0x3ff6a09e66dbc8da` for the positive, `0x3fe6a09e6622aec0` for the negative). Over the whole
word, 313095227 positive and 301477648 negative doubles differ (counted by an exhaustive scalar
run, no fused multiply-add). `ProofsTest.l1DomainWidenedByAWordIsRefuted` asserts the four
non-witnesses and the two witnesses.

### L2. The near band's form is the far form

**Statement.** For a double `x` with `0x3fd62e43 <= hx <= 0x3ff0a2b1` (line 129's band, `X1 <= |x|
<= X2`, `X2 = 0x3ff0a2b1_ffffffff`), the far form `k = (int) fl(fl(invln2*x) + halF[xsb])`, `t =
k`, `hi = x - fl(t*ln2HI[0])`, `lo = fl(t*ln2LO[0])` gives `k = 1 - 2*xsb`, `hi = x - ln2HI[xsb]`
and `lo = ln2LO[xsb]`, bit for bit: the near form, line 130.

**Hypotheses.**
- (H4) The band's boundary doubles: `s(X1) = fl(fl(invln2*X1) + 0.5) = 1.0000000013739176`
  (`s - 1 = 1.374e-9`) and `s(X2) = 1.9999996601564510` (`2 - s = 3.398e-7`), so every `s(x)` of
  the band lies in `[1 + 1.3e-9, 2 - 3.4e-7]`.
- (H5) `ln2HI[1]` and `ln2LO[1]` are `-ln2HI[0]` and `-ln2LO[0]` bit for bit (the source's hex,
  checked by `ExpTest.constantsAreTheSourcesBits`).

**Proof.**
1. For `x > 0`, `s(x)` is non-decreasing in `x` by (R2) (a product with the positive constant
   `invln2`, then a sum with 0.5). So `s(X1) <= s(x) <= s(X2)`, and by H4 `1 < s(x) < 2`. Java's
   `(int)`, and `D2I`, truncate toward zero (JLS 5.1.3) as the C's cast does, so `k = 1`.
2. For `x < 0`, `fl(invln2*x) = -fl(invln2*|x|)` and `halF[1] = -0.5`, so `s(x) = fl(-(fl(invln2*
   |x|) + 0.5)) = -s(|x|)` by (R1), in `(-2, -1)`, and `k = -1`. So `k = 1 - 2*xsb`.
3. `t = k = +-1` exactly (`I2D`). `fl(t * ln2HI[0]) = +-ln2HI[0]` by (R3), which is `ln2HI[xsb]`
   by H5; so `hi = x - ln2HI[xsb]`, the same operation on the same operands as line 130.
   Likewise `lo = fl(t * ln2LO[0]) = ln2LO[xsb]`.
4. From there both compute `x = hi - lo`, line 146 and, since `k != 0`, line 148, and the
   scale-back with `k = +-1 >= -1021`: the same operations.

The planted fault `exp.constantOffByOneUlp` (ln2HI[0] one ulp up) now reaches the band's lanes
too, through `t*ln2HI[0]`; all three planted faults are still caught.

**Hinge tests (scalar).** `ProofsTest.l2BandBoundariesGiveKOfOne` (H4 at both boundary doubles
and their negatives, with the margins, and the two `sat` cases below);
`ProofsTest.l2FarFormIsNearForm` (H5, and the far form's `k`, `hi`, `lo` against the near form's
at 65536 uniform draws over the band and 8 neighbours of each end, both signs). **Vector tie.**
`ExpTest.foldBoundaries` and `foldBoundariesAtMaxSpecies`, as for L1, at both ends of the band.

**Encoding.** `proofs/exp_near_band.smt2`, machine-checked for every double of the band, both
signs, without (R2): `x` is `S * Mx * 2^(j-52)` in each of the band's binades `2^-2`, `2^-1`,
`2^0`, with `Mx` symbolic and bounded by `X1` and `X2` (rendered from `Exp`'s words); the product
with `invln2` is then a constant times `Mx`, linear, and rounds only into the binades `[0.5, 1)`
and `[1, 2)` with `x`'s sign; for each pair of binades the sum with `halF[xsb] = S/2`
(`exp_half_sign.smt2`) rounds into `[1, 2)` with `x`'s sign, so `(int) s = S`, `unsat` against
every other neighbouring binade; each pair is shown reached (`sat`) or unreachable (`unsat`).
Step 3's exactness, `+-1 * ln2HI[0]` and `+-1 * ln2LO[0]` rounding to `+-ln2HI[0]` and
`+-ln2LO[0]` and no other double, and H5, that the `t = -1` results have `LN2HI_1`'s and
`LN2LO_1`'s bits, are checked too. Not checked: that a value rounds to no double in a binade
further off than the next (the arithmetic of rationals), `D2I` truncating and `I2D` of `+-1` being
exact (JLS), and step 4, that both forms then run the same operations, which is the code's text.
The ends `X1` and `X2` are decoded from their bits (`S = 1`, binades `2^-2` and `2^0`, `M =
6243314785320960` and `4682485015248895`), so the binades and the bounds on `Mx` are checked, not
only rendered. The `sat` checks refute the band widened by a word at either end: at `hx =
0x3fd62e42`, `x = 0x3fd62e42_00000000` gives `k = 0`; past `0x3ff0a2b1`, `x =
0x3ff0a2b2_ffffffff` gives `k = 2`, not the near form's 1; both signs; and `ln2HI[1]` and
`ln2LO[1]` with `M` one more. `invln2` changed by one ulp is not refuted, and cannot be: the claim
holds for a range of `invln2`, since H4's margins (`1.37e-9` and `3.4e-7`) are some `1.8e7` of
its ulps below it and `1.5e9` above (a scalar search), so with `invln2` moved by one the file
renders anew and every check still holds. The same goes for `invln2` in L1's section C, and for
`P1..P5` in the evaluated chains of L1's section D and of L4, which use whatever value `Exp`
holds and compare their results with no source value. These constants are held by
`ExpTest.constantsAreTheSourcesBits`, which pins them to the source's hex, and by
`ProofFilesTest`, which fails when a committed file differs from its rendering.

### L3. A high-word compare is one signed 64-bit compare of |x|'s bits

**Statement.** Let `b = bits(x) & 0x7fffffff_ffffffff` (`Lanes.absBits`) and `0 <= h <=
0x7fffffff`. Then `hx >= h` iff `b > h * 2^32 - 1`, and `hx < h` iff `b < h * 2^32`, both as
signed 64-bit compares; `hx > h` is `hx >= h + 1` for `h < 0x7fffffff`. This holds for every `x`,
NaNs of either sign included.

**Hypotheses.**
- (H6) `b`'s sign bit is clear, so `0 <= b < 2^63` as a signed long. The port clears it with an
  `and`, not with `lanewise(ABS)`, so no assumption about how a NaN's sign is treated is needed.
- (H7) `h * 2^32 - 1` and `h * 2^32` lie in `[-1, 2^63)`, so neither wraps (every `h` exp uses is
  below `0x7fffffff`, so `h + 1` does not wrap either).

**Proof.** `b = hx * 2^32 + lo` with `0 <= lo < 2^32` and `hx = b >>> 32`, which is the C's `hx`.
If `hx >= h`, `b >= h * 2^32`. If `hx < h`, `hx <= h - 1` and `b <= (h - 1) * 2^32 + 2^32 - 1 = h
* 2^32 - 1`. So `hx >= h` iff `b >= h * 2^32` iff `b > h * 2^32 - 1` over the integers, and by H6
and H7 the signed compare is the integer compare. No IEEE property is used: the C compares
integers too, and a NaN is the integer it is (`hx >= 0x7ff00000`). The double compare it replaces,
`!(|x| < fromHi(h))`, needed a negation for NaN lanes, which AVX2 lowers as a compare, an
all-ones vector and an xor; this is one `vpcmpgtq`.

**Hinge tests (vector).** Both run the vector code. `ProofsTest.l3AbsBitsAreNonNegative` (H6
on the specials, sign-set NaNs among them, and sampled patterns; H7 for each exp threshold; and
that the raw bits of `0xfff8...`, sign set, would miss the filter);
`LanesTest.highWordCompares` (the three compares against the C's integer compare, at every
width, on the specials, sampled patterns and both sides of each word, through the
`DoubleVector` forms, which now route through these); `ExpTest.intWordsAreTheSources` (each of
the four high words rendered into the file's C side below is e_exp.c's literal; the same test
pins L2's `HX_THREE_HALVES_LN2` and L1's `K_MIN_NORMAL`).

**Encoding.** `proofs/exp_highword_compare.smt2`, machine-checked over integers: for every long
`bits` and every `h` in `[0, 0x7fffffff]`, `absBits` is non-negative with the C's `hx` as its high
word, and `hiAtLeast`, `hiBelow` and `hiAbove` (for `h < 0x7fffffff`) are the C's `hx >= h`, `hx <
h` and `hx > h`, each Java operator (`&`, `>>`, `<<`, `-`, `l2i`) as `java.smt2` defines it; then
the same at each of `Exp`'s words, rendered, and that every NaN and infinity, either sign, is a
filter and non-finite lane. The `sat` checks refute the comparand changed by one (`b > h * 2^32`
for `hx >= h`; `b = h * 2^32` is a model), `hiBelow`'s comparand less one, `hiAbove`'s `+ 1`
dropped, and the raw bits of the sign-set NaN `0xfff80000_00000000` compared unmasked, which miss
the filter. Left informal: that the C's compares of the unsigned `hx` with its int-literal words
are the integer compares the file encodes, since both lie in `[0, 2^31)` (argued in the file's
header); that `__HI(x)` is the high int of the raw bits (the prelude's reading of fdlibm's
macro); and that `Lanes` and `Exp` call these compares with these words, the code's text.

### L4. The tiny lanes cannot be folded (a counter-example, not a fold)

**Statement.** For `|x| < 2^-28` the C returns `one + x` (line 140). Folding these lanes into L1's
tail (`k = 0`, `one - ((+0 - q) - x)`) is not bit-identical.

**Counter-example.** `x = 5 * 2^-53 = 0x1.4p-51`. `1 + x = 1 + 2.5 ulp(1)` is a tie and rounds to
even, `0x1.0000000000002p0`, which is `StrictMath.exp(x)`. The tail adds `q`, about `x^2 / 2 > 0`,
first: `fl(-q - x) = -fl(x + q)`, and `x + q` lies above the tie, so `one - ` it rounds up to
`0x1.0000000000003p0`. Of `x = m * 2^-53` for `m < 64`, 15 differ this way (`m = 5, 9, 13, ...`).

**Test (scalar).** `ProofsTest.l4TinyLanesCannotBeFolded` asserts the three doubles. **Vector
tie.** `ExpTest.foldBoundaries` covers the words `0x3e2fffff` and `0x3e300000`, on both sides of the
tiny lanes' edge, and the threshold cases (`ExpTest.thresholds()`) the 64 neighbours of `2^-28`:
the port's `one + x` there is checked against `StrictMath`.

**Encoding.** `proofs/exp_tiny_counterexample.smt2`, machine-checked: at `x = 5 * 2^-53`
(checked to be a tiny lane, `hx < 0x3e300000`), every rounding of `one + x` and of the folded tail
is evaluated (the exact result of each operation on the two doubles before it rounds to the
double Java computed, and to no other double in its binade or the ones next to it; the tie in
`one + x` among them), and the two results differ, `unsat` for their equality; at `4 * 2^-53` the
same evaluation gives one double for both, so the witness moved by one is not one. Not checked:
that `StrictMath.exp(x)` is `one + x` there (the oracle is not in a solver; `ProofsTest` asserts
it), and doubles further off than the next binade (the arithmetic of rationals).

### L5. halF[xsb] from the sign bit

**Statement.** `halF[xsb]` (0.5 or -0.5) is the double whose bits are
`(bits(x) & 0x80000000_00000000) | 0x3fe00000_00000000`.

**Proof.** `0.5` is `0x3fe00000_00000000` and `-0.5` differs from it in the sign bit alone (IEEE
754 sign-magnitude encoding); `xsb` is that bit of `x` (line 113). The port so replaces the C's
table lookup (a compare and a blend of two constants) with an `and` and an `or`.

**Test (scalar).** `ProofsTest.l5HalfFromSignBit`, on the specials of both signs. **Vector
tie.** Every `ExpTest` case of a reduced lane, both signs: a wrong `halF` moves `k` there.

**Encoding.** `proofs/exp_half_sign.smt2`, machine-checked: for every long `bits`, `(bits &
Long.MIN_VALUE) | HALF_BITS` (as bit-vector `and` and `or` on the 64-bit pattern, read back as a
signed long; `HALF_BITS` rendered from `Exp`) is the raw bits of the table's `halF[xsb]`, with
`xsb` as line 113 computes it; and the two entries are the doubles 0.5 and -0.5. The `sat` checks
refute `0x3fe00000_00000001` as the constant and a mask one bit below the sign. That the lane
reinterprets those bits as its double is the code's text.

## log (`Log.java`, from `e_log.c`)

e_log.c reduces `x` to `xn = 1 + f` in `[sqrt(2)/2, sqrt(2))` and `k` (lines 108-113), then
returns one of four forms: `f == 0` (lines 115-116), `|f| < 2^-20` (117-119), the `i > 0` band
with `hfsq` (132-134) and the rest (136-137). Each comes in a `k == 0` version and a `k != 0`
version. The port computes the `k != 0` version on every lane, with `dk = +0` where `k == 0`
(L6). It holds the C's `int` words in the long lanes of the bits (L8), converts `k` to `dk`
without `L2D`, which boxes on AVX2 (L7), and finds the common and subnormal lanes with one
integer compare each (L9). Line numbers in this section are `reference/fdlibm-5.3/e_log.c`'s,
and `hx` is the C's `int hx`.

Two more IEEE properties are used here:

- **(R5) The sign of an exact zero (IEEE 754 6.3).** Under round to nearest, a sum `x + y` of
  operands of opposite signs, or a difference `x - y` of operands of like signs, that is exactly
  zero is `+0`; for two zeros, `x - y` is `-0` only for `x = -0, y = +0`, and `x + y` is `-0`
  only for `x = y = -0`; a zero product has the exclusive or of its operands' signs. So `v +
  (+0) = v` for every `v != -0`.
- **(R6) Sterbenz's lemma.** If `y / 2 <= x <= 2y` for doubles `x, y > 0`, then `x - y` is a
  double, so `fl(x - y) = x - y` exactly.

### L6. The k == 0 forms are the k != 0 forms with dk = +0

**Statement.** For doubles `B` and `f`, `f` finite and not `-0`, `fl(+0 - fl(fl(B - (+0)) - f))
= fl(f - B)`, bit for bit. Hence on the C's `k == 0` lanes, where `dk = +0` and so `dk*ln2_hi =
dk*ln2_lo = +0`:

- (a) line 137's `dk*ln2_hi-((s*(f-R)-dk*ln2_lo)-f)` is line 136's `f-s*(f-R)`;
- (b) line 134's `dk*ln2_hi-((hfsq-(s*(hfsq+R)+dk*ln2_lo))-f)` is line 133's
  `f-(hfsq-s*(hfsq+R))`;
- (c) line 119's `dk*ln2_hi-((R-dk*ln2_lo)-f)` is line 118's `f-R`;
- (d) line 116's `dk*ln2_hi+dk*ln2_lo` is line 115's `zero`.

**Hypotheses.**
- (H8) `dk` is `+0` on exactly the `k == 0` lanes: `I2D` of 0 is `+0.0` (JLS 5.1.2), as is L7's
  conversion there (an exact zero difference, R5); and `fl(+0 * c) = +0` for `c = ln2_hi`,
  `ln2_lo`, both positive and finite (R5). With `dk = -0` both products would be `-0`, and (d)
  would return `-0` for the C's `zero`.
- (H9) `f` is finite and never `-0`, on every lane, rare ones included. Line 111 writes `xn`'s
  high word as `hx|(i^0x3ff00000)` with `hx` masked to 20 bits by line 109 and `i` either 0 or
  `0x100000`, so `xn`'s sign bit is clear and its biased exponent is `0x3ff` or `0x3fe`: `xn` is
  in `[0.5, 2)`. Then `f = fl(xn - 1.0)` is exact by (R6), and zero only at `xn = 1`, where it is
  `+0` (R5). So `f` lies in `[-0.5, 1)`, whatever bits `x` has.
- (H10) `hfsq = fl(fl(0.5*f)*f)` is never `-0`: `fl(0.5*f)` has `f`'s sign (a zero included) and
  the product of two operands of one sign is positive or `+0` (R5).

**Proof.**
1. `fl(B - (+0)) = B` for every `B`, `-0` included (R3).
2. Let `v = fl(B - f)`. If `B - f` is not exactly zero, `v = -fl(f - B)`, nonzero, by (R1), and
   `fl(+0 - v) = -v = fl(f - B)` by (R3). If `B - f` is exactly zero, `B` and `f` are the same
   real: for `B = f != 0`, `v = +0`, `+0 - (+0) = +0` and `f - B = +0` (R5); for two zeros, `f =
   +0` (H9's case), and `B = +0` gives `v = +0`, then `+0`, with `f - B = +0`, while `B = -0`
   gives `v = -0`, then `+0 - (-0) = +0`, with `f - B = +0 - (-0) = +0` (R5). That is the
   statement. Its one counter-case among zeros is `f = -0, B = +0`, where the C's `f - B` is
   `-0` and the fold gives `+0`; H9 excludes it.
3. (a) and (c): by H8 both products are `+0`; step 1 removes `- dk*ln2_lo`, and step 2 with `B =
   s*(f-R)` or `B = R`, and `f` from H9, gives the C's `k == 0` result.
4. (b): `dk*ln2_lo = +0` (H8). If `shr = s*(hfsq+R)` is not `-0`, `shr + (+0) = shr` (R5); if it
   is, `shr + (+0) = +0`, and `hfsq - (+0) = hfsq = hfsq - (-0)`, since `hfsq` is not `-0` (H10).
   Either way `hfsq - (shr + dk*ln2_lo)` is the C's `B = hfsq - shr`, and step 2 finishes as in
   (a). (On the band's lanes `shr` is not `-0` at all, since `|f| > 0.28` there and `hfsq + R >
   0`; the proof does not need it, and `ProofsTest` samples it.)
5. (d): `+0 + (+0) = +0` (R5), and the C's `zero` is `+0.0`.

So the port drops the `k == 0` compare and its four blends; the `f == 0` and `|f| < 2^-20` lanes
are still told apart by the C's own tests (line 114 and `f == zero`), and the band by `i > 0`.
The planted faults `log.dropCorrection` and `log.constantOffByOneUlp` change `dk*ln2_lo` and
`dk*ln2_hi`, which are `+0` on the `k == 0` lanes either way, so they bite on the `k != 0` lanes,
as before; `log.thresholdMoved` moves the sqrt(2) split. All three are still caught.

**Hinge tests (scalar).** `ProofsTest.l6ZeroDkIsPositiveZero` (H8, and that `dk = -0` would give
`-0` for (d)); `ProofsTest.l6FIsNeverNegativeZero` (H9 over the specials, sampled bit patterns, the
subnormals and the `k == 0` domain: `xn` in `[0.5, 2)`, `f` finite and not `-0`, zero exactly at `xn
= 1`, and `f = xn - 1` exactly, by `BigDecimal`); `ProofsTest.l6FoldedFormsAreTheKZeroForms` (all
four forms over the `k == 0` domain's ends, the neighbours of 1.0 and 65536 draws, each form on
every lane, not only its own, so (d)'s `+0` is the C's `zero` on every lane too; H10, and `shr` not
`-0`); `ProofsTest.l6HingesRefuted` (at `f = -0, B = +0` the fold is not the C, and with `hfsq = shr
= -0`, H10 false, `shr + (+0)` changes the difference). **Vector tie.** `LogTest.foldBoundaries` and
`foldBoundariesAtMaxSpecies` run both arms (`VFdlibm.log` and `Log.logBlended`), unmasked and
masked, against `StrictMath` at the high words of the sqrt(2) split on both `k == 0` binades
(`0x3fe6a09b`, `0x3fe6a09c`, `0x3ff6a09b`, `0x3ff6a09c`), the band's edges inside them
(`0x3ff61479`, `0x3ff6147a`, `0x3fe6b851`, `0x3fe6b852`) and the `|f| < 2^-20` words around 1.0
(`0x3feffffd` to `0x3ff00002`), low words 0, `0xffffffff` and 1024 seeded, plus the 4096 doubles
each side of 1.0 and 64 each side of sqrt(2) and sqrt(2)/2, at every width and `SPECIES_MAX`.

**Encoding.** `proofs/log_zero_dk.smt2`, partly machine-checked. Checked: the signs of exact zeros
(R5) as three functions of the operands' sign bits, each pinned to the JVM over all four pairs of
signed zeros and over `+-0 * ln2_hi`, `+-0 * ln2_lo`; over them, step 2's zero cases (every
combination of `B` and `f` zeros with `f` not `-0`, and `B = f` nonzero), with a `sat` check that
allowing `f = -0` gives the counter-case `B = +0, f = -0`; H10's zero case and step 4's `shr + (+0)`
with a `sat` refutation at `hfsq = -0`; step 5's `+0 + (+0) = +0`; the nonzero steps 1 and 2's `B -
(+0) = B` and `+0 - v = -v` (the exact value is the double or its negation, which `jdouble.near`
admits in every binade at once, and admits no other double in its binade or the ones next to it,
either sign, every normal binade at once, the lowest one's next below being the subnormals); H9's
first half, that `xn`'s bits built from any 20-bit `hx`, either `i` and any low word have sign 0 and
biased exponent `0x3fe` or `0x3ff` (over bit-vectors) and so are a double in `[0.5, 2)` (over the
prelude's integers), equal to 1.0 at one pattern only; and that `ln2_hi` and `ln2_lo` are positive
and finite. Step 2's `fl(B - f) = -fl(f - B)` is `proofs/sign_symmetry.smt2`'s section A. Not
checked, and informal above: Sterbenz's lemma for `f = xn - 1.0` (sampled exactly by `BigDecimal` in
`ProofsTest`); that `B` and `v`, where nonzero, are normal (on the `k == 0` lanes `|f|` is in
`[2^-53, 0.415)` or zero, `B` about `f^2/2` or smaller than `|f|`, `v` about `log(1+f)`, all far
above `2^-1022`: bounds on the polynomial, sampled, as exp's H2); a nonzero product's sign (H10 for
nonzero `f`); that a value rounds to no double in a binade further off than the next (the arithmetic
of rationals); and that the port computes these forms, the code's text.

### L7. dk = (double)k without L2D

**Statement.** For every long `k` with `-2^51 <= k < 2^51`, `fl(fromBits(k + B52) - C52) =
(double) k` bit for bit, where `C52 = 0x1.8p52` (`Log.DK_MAGIC`) and `B52 =
0x43380000_00000000` its bits (`Log.DK_MAGIC_BITS`). At `k = 0` it is `+0.0`.

**Hypotheses.**
- (H11) `k` is in the domain on every lane: L8 bounds the port's `k` to `[-1077, 3073]` for
  every bit pattern, kept lanes or not.

**Proof.** `C52 = 1.5 * 2^52` lies in binade `2^52`, whose ulp is 1, with significand field `2^51`.
Adding `k` to the bits adds `k` to that field, and for `-2^51 <= k < 2^51` the field stays in
`[0, 2^52)`, so no carry reaches the exponent: `fromBits(k + B52)` is the double `1.5 * 2^52 +
k`, an integer in `[2^52, 2^53)`. Its difference with `C52` is the integer `k`, exact by (R6)
(both lie in `[2^52, 2^53)`, within a factor of two), and a double since `|k| < 2^53`; for `k =
0` it is `x - x = +0` (R5). Java's `(double) k` of a long with `|k| <= 2^53` is exact (JLS
5.1.2), so the two are the same double. The C's `(double)k` of its `int k` is that double too,
since L8 makes the port's `k` the C's.

At `k = 2^51` the field carries into the exponent, giving `2^53`, and the difference is still
`2^51`; at `k = 2^51 + 1` it is `2^53 + 2`, so the result is `k + 1`. The domain is therefore
stated half-open, and it covers log's `k` with room to spare.

**Hinge tests (scalar).** `ProofsTest.l7ExactConversionIsI2D`: the constant's bits; every `k`
in `[-1077, 3073]`, 65536 draws over the domain and its ends, against Java's `(double) k` by
bits, `+0.0` at 0; and the refutations at `2^51 + 1`, `-2^51 - 1` and with `B52 + 1`. **Vector
tie.** Every `LogTest` case: a wrong `dk` moves every `k != 0` result.

**Encoding.** `proofs/log_exact_dk.smt2`, machine-checked over the prelude's integers: `B52`
decodes to `S = 1, E = 0, M = 3 * 2^51`; for every `k` in the domain, `k + B52` (`jlong.add`, so
a wrap would show) decodes to `S = 1, E = 0, M = 3 * 2^51 + k`; and for `k` in each binade `2^0`
to `2^51`, either sign, the exact difference `k` rounds to the double whose value is `k` and to
no other in its binade or the next. The `sat` checks refute `k = 2^51 + 1` and `B52 + 1`. Not
checked: that this double is Java's `(double) k` (JLS 5.1.2; `ProofsTest` compares the bits),
the zero case's `+0` (R5, stated), the binades further off than the next (the arithmetic of
rationals), and that the port computes this, the code's text.

### L8. The C's int words in long lanes

**Statement.** Let `b` be a lane's bits (`x`'s, or the scaled subnormal's in the blended arm)
with the sign bit clear, `hx = (int) (b >> 32)` the C's signed high word. The port computes
`HX = b >>> 32`, `HXM = HX & 0xfffff`, `I = (HXM + 0x95f64) & 0x100000`, the new high word `HXM |
(I ^ 0x3ff00000)`, `kBits = ((HX >>> 20) + (B52 - 1023)) [- 54] + (I >>> 20)`, the test
`((HXM + 2) & 0xfffff) < 3` and the band test `((HXM - 0x6147a) | (0x6b851 - HXM)) > 0`, all on
longs. Then each is the C's int value of lines 104-131 widened to a long, `kBits` is the C's `k`
plus `B52`, and the two tests are the C's. For every `b`, either sign, `kBits - B52` lies in
`[-1077, 3073]`.

**Hypotheses.**
- (H12) The sign bit of `b` is clear on every lane whose result is kept: the common lanes are
  positive (L9), and the blended arm's subnormals, positive, are scaled by `2^54` before the
  high word is read. A negative lane's `HX` is `2^32` more than the C's negative `hx`; such
  lanes are rare, their results replaced, and only the bound on `k` is needed there.

**Proof.** With the sign bit clear, `b >> 32` lies in `[0, 2^31)`, so `(int)` keeps its value and
`b >>> 32` is the same long (JLS 15.19). The mask keeps 20 bits, so `HXM` is in `[0, 2^20)`;
`HXM + 0x95f64 < 2^21`; `I` is 0 or `2^20`; the new high word is below `2^30`; `HX >>> 20` is in
`[0, 2^11)`; and the band's two differences lie in `(-2^20, 2^20)`. No value leaves 32 bits, so
the C's int operations, which have no overflow on these values, and the long operations give the
same integers; `|` of two longs sign-extended from ints is the sign extension of the ints' `|`.
The shifts are logical where the C's are arithmetic, which agree on non-negative values: `HX`
and `I` are never negative as longs. `kBits` adds `B52 - 1023` as one constant where the C
subtracts 1023 from `k` and L7 adds `B52`: integer addition modulo `2^64` is associative and
commutative, and the values are far from wrapping. For every `b`, `HX < 2^32`, so `HX >>> 20 <
4096` and `k = (HX >>> 20) - 1023 + (I >>> 20)` is in `[-1023, 3073]`, `[-1077, 3019]` with the
`-54`.

The port so drops `L2I` (`vpmovqd`), `I2L` (`vpmovsxdq`), `I2D` and the int-to-double mask
casts; and since AVX2 has no 64-bit arithmetic shift, writing the C's `>>` as `>>>` keeps each
shift one instruction there.

**Hinge tests (scalar).** `ProofsTest.l8LongWordsAreTheCsInts`: over the specials, sampled patterns
of every kind, the `k == 0` domain and the subnormals, each also scaled by `2^54`, the port's words
against the C's ints and the bound on `k` for every pattern; for a negative pattern the high words
differ (H12 refuted); `LogTest.intWordsAreTheSources` (`0x95f64`, `0x6147a` and `0x6b851`, which the
file's C side takes from `Log`, are e_log.c's literals). **Vector tie.** Every `LogTest` case runs
the vector words; `LogTest.foldBoundaries` and `insideTheBranchWords` sit on the split's and the
band's edges.

**Encoding.** `proofs/log_long_words.smt2`, machine-checked over SMT-LIB's bit-vectors, Java's
int and long operators being two's-complement bit-vector operators (the header lists each), every
definition pinned by ground checks to the values Java computes at sampled bit patterns: for every
`b` with the sign clear, the high word, masked word, `i`, new high word, `kBits` with and without
the `-54`, and both tests; for every `b`, the bound on `k`. The `sat` checks refute H12 and
`0x95f64`, `0x6147a` and `0x6b851` changed by one. Not checked: that `__HI(x)` is the high int of
the bits (the macro's reading), and that the port writes the new high word's low 32 bits back as
the C's `__HI(x) =` does and computes these words, the code's text. Over the prelude's integers
with `int2bv` for `&`, `|` and `^`, as exp's L5 is written, both solvers answered `unknown` to
these checks after a minute; over bit-vectors alone they decide the file at once.

### L9. The common and subnormal lanes by one compare of the bits

**Statement.** For every long `b` and longs `lo <= hi`, `b + (Long.MIN_VALUE - lo) <
Long.MIN_VALUE + (hi - lo)`, as a signed compare with Java's wrapping `+` and `-`, holds iff
`lo <= b < hi`. With `[lo, hi) = [0x00100000_00000000, 0x7ff00000_00000000)` it is the C's
common lanes, `!(hx < 0x00100000) && !(hx >= 0x7ff00000)` for the signed `hx` (lines 100, 107);
with `[1, 0x00100000_00000000)` it is the blended arm's subnormals, `hx < 0x00100000` and not
`((hx&0x7fffffff)|lx) == 0` and not `hx < 0` (lines 100-104).

**Proof.** `lo <= b < hi` iff `b - lo`, taken modulo `2^64` as an unsigned number, is below `hi -
lo` (the interval does not wrap since `lo <= hi`). Adding `2^63` modulo `2^64` maps the unsigned
order onto the signed one, so the unsigned compare is the signed compare of both sides plus
`Long.MIN_VALUE`. For the bounds: the signed `hx` of `b` and `b` itself order alike against a
word with a zero low word (L3's argument, on the raw bits instead of `|x|`'s), so `hx >=
0x00100000` iff `b >= 0x00100000_00000000` and `hx < 0x7ff00000` iff `b < 0x7ff00000_00000000`;
`hx < 0` iff `b < 0`; and `((hx&0x7fffffff)|lx) == 0` with `hx >= 0` iff `b == 0`. No IEEE
property is used: every NaN, of either sign, falls outside both ranges as in the C. The double
compares this replaces were two per range and an `and`; this is an add and one compare, both
integer, on bits the body reads anyway.

**Hinge tests (scalar).** `ProofsTest.l9RangeComparesAreTheCs`: the specials, sampled patterns and
both sides of each bound against the C's tests; without the bias `-1.0` would pass as common.
`LogTest.intWordsAreTheSources` pins `0x00100000` and `0x7ff00000`, which the file's C side takes
from `Log`, and the bounds built from them. **Vector tie.** `LogTest.thresholdsAndSpecials` and
`nearOneAndSubnormals` (the specials, every power of two down to the subnormals, the neighbours of
`MIN_NORMAL`, both arms).

**Encoding.** `proofs/log_range_compare.smt2`, machine-checked over bit-vectors as L8's file is,
the definitions pinned to Java at sampled patterns: the biased compare is `lo <= b < hi` for
every `b` and every `lo <= hi`, and the two ranges are the C's tests for every `b`. The `sat`
checks refute the compare without the bias and each bound moved by one. Not checked: that
`__HI(x)` and `__LO(x)` are the bits' high int and low word, and that the port compares with
these bounds, the code's text.

## expm1 (`Expm1.java`, from `s_expm1.c`)

s_expm1.c reduces `|x| > 0.5 ln2` the way e_exp.c does, in a near form for `|x| < 1.5 ln2`
(lines 163-167) and the far form above it (169-172), and leaves `|x| <= 0.5 ln2` unreduced with
`k = 0` (line 181); after the polynomial it returns `x - (x*e-hxs)` for `k == 0` (line 189) and
one of six forms of `e = (x*(e-c)-c)-hxs` otherwise (191-212). The port computes the far form
on every lane and lets `k == 0` lanes through it (L10), and returns the `k == 0` lanes' result
from the shared `e` (L11). Everything else is the C's operations in the C's order; the six
`k != 0` forms are each computed and blended. `Expm1.expm1Core`, the finish arm's body in a
fourth mode without its rare block, adds no departure: its lanes outside `expm1Rare` run the same
operations, so L10 and L11 cover them as they cover the arms. Line numbers in this section are
`reference/fdlibm-5.3/s_expm1.c`'s, and `hx` is the C's high word of `|x|`. In the C `hx` is
`unsigned`; every compare follows `hx &= 0x7fffffff`, so both sides lie in `[0, 2^31)` and the
port's signed `int` compares are the C's (the integers are the same, and so is their order).

### L10. expm1's reduction is exp's, with L1, L2 and L5

**Statement.** For every double `x` that lines 146-158 do not return early (`hx < 0x7ff00000`,
`x <= o_threshold`, and `x > 0` or `hx < 0x4043687A`), let the port compute `k = (int)
fl(fl(x*invln2) + h)` with `h` the double of bits `(bits(x) & 0x80000000_00000000) |
0x3fe00000_00000000` when `hx > 0x3fd62e42`, and `k = (int) +0.0 = 0` otherwise; `t = k`, `hi =
x - fl(t*ln2_hi)`, `lo = fl(t*ln2_lo)`, `r = hi - lo` and `c = (hi - r) - lo`. Then (a) on the
C's near band, `0x3fd62e42 < hx < 0x3FF0A2B2`, `k`, `hi`, `lo`, `r` and `c` are lines 164-167's
and 174-175's, bit for bit; (b) on `hx <= 0x3fd62e42`, `k = 0`, `r = x` and `c = +0`; (c) above
the band, up to `o_threshold`, they are lines 169-175's. (Lanes the C returns early from may get
any `k`, `D2I` saturating when `fl(fl(x*invln2) + h)` truncates outside `int`'s range, about
`|x| >= 2^31 ln2`; their results are replaced.)

**Hypotheses.**
- (H13) s_expm1.c's `invln2`, `ln2_hi` and `ln2_lo` are e_exp.c's `invln2`, `ln2HI[0]` and
  `ln2LO[0]`, bit for bit, and its band words `0x3fd62e42` and `0x3FF0A2B2` are e_exp.c's (the
  constants are lines 119-121 of s_expm1.c and the words lines 162-163, against e_exp.c's tables
  and its lines 128-129; `Expm1Test.constantsAreTheSourcesBits` pins Expm1's constants and
  words to the source, `ExpTest.constantsAreTheSourcesBits` Exp's constants and
  `ExpTest.intWordsAreTheSources` Exp's words, and `ProofsTest.l10ReductionIsExps` pins one to
  the other). So L2 and L5, which use
  nothing but those values and words, hold for expm1's lanes as written.
- (H14) `k` is forced to 0 on the unreduced lanes by line 162's own test, as in L1's H1: at the
  top of word `0x3fd62e42` the formula gives `k = +-1`, and the C reduced with that `k` returns
  a different double first at `0x3fd62e42_ffffffec` and `0xbfd62e42_fffffff3` (scanning down
  from the word's top double; 80272 positive and 118783 negative doubles of the word's top
  2^20 differ, an exhaustive scalar run of a transcription of the C).

**Proof.**
1. (c) is the C's far form, operation by operation, with `h` for `(xsb==0)?0.5:-0.5` by L5
   (line 141's `xsb`, `hx&0x80000000`, and line 113's of e_exp.c, `(hx>>31)&1`, are both the
   sign bit of the high word, and s_expm1.c tests it only against zero; the C's ternary and
   e_exp.c's `halF[xsb]` are the same two doubles) and `x*invln2` for `invln2*x`, the same
   rounding of the same exact product.
2. (a): L2 gives `k = 1 - 2*xsb`, `hi = x - ln2HI[xsb]` and `lo = ln2LO[xsb]` (H13). For `xsb =
   0` that is line 165, `hi = x - ln2_hi, lo = ln2_lo, k = 1`. For `xsb = 1`, `ln2HI[1] =
   -ln2_hi` bit for bit (L2's H5), and `fl(x - (-ln2_hi)) = fl(x + ln2_hi)` by (R4), both being
   the rounding of the same real `x + ln2_hi`: line 167's `hi`; `lo = ln2LO[1] = -ln2_lo` is line
   167's `lo`, the negation exact (IEEE 754 sign-magnitude). From there `r` and `c` are lines
   174-175 on the same operands.
3. (b): `k = 0` by H14, `t = +0` (`I2D` of 0, JLS 5.1.2), `fl(+0 * ln2_hi) = +0` and `fl(+0 *
   ln2_lo) = +0` (R3, both constants positive and finite), so `hi = x - (+0) = x` and `lo = +0`
   and `r = x - (+0) = x` (R3, `-0` included). Then `hi - r = x - x = +0`, an exact zero
   difference of like signs (R5; `x` is finite, `|x| < 0.35`), and `+0 - (+0) = +0` (R5). The C
   keeps `x` and `k = 0` there and never reads `c`; the port reads `c` only in L11.

The tiny lanes, `hx < 0x3c900000`, also get `k = 0` and `r = x` and are then replaced by line
179's `x - (t-(huge+x))`, computed as the C writes it. The planted fault
`expm1.constantOffByOneUlp` (`ln2_hi` one ulp up) reaches every reduced lane through
`t*ln2_hi`, the band's lanes included, and is caught.

**Hinge tests (scalar).** `ProofsTest.l10ReductionIsExps` (H13, and the C's negative near form
against the far form's `hi` and `lo`, at both ends of the band and 65536 seeded draws);
`ProofsTest.l10UnreducedLanesKeepXWithZeroCorrection` (step 3's `hi`, `lo`, `r` and `c`, by bits,
over both ends of `2^-54 <= |x| <= 0x3fd62e42_ffffffff`, the whole word of `0.5 ln2` (about
`0.3466`), their neighbours inside that range and 65536 draws over the binades `2^-54` to
`2^-2`, those above that top dropped, both signs); `ProofsTest.l10KIsForcedByTheHighWordTest`
(H14: `k = +-1` from the formula at the two witnesses, where the C reduced with it,
`Expm1Test.cExpm1(x, true)`, is not `StrictMath.expm1`, the C as written is, and the doubles
above each witness to the word's top double, where the unforced `k` happens to give the same
double, are checked to be so). The
transcription `Expm1Test.cExpm1` is itself checked against `StrictMath.expm1`
(`Expm1Test.scalarTranscriptionIsStrictMath`). Statement (c) has no scalar test of its own: it
is step 1, the C's far form operation by operation, and only the vector tie below runs its
lanes.
**Vector tie.** `Expm1Test.foldBoundaries` and `foldBoundariesAtMaxSpecies` run every arm,
unmasked and masked, against `StrictMath` at the words `0x3c8fffff`, `0x3c900000`,
`0x3fd62e42`, `0x3fd62e43`, `0x3ff0a2b1` and `0x3ff0a2b2`, both signs, low words 0,
`0xffffffff` and 1024 seeded, plus the 4096 doubles each side of `+-0.5 ln2`, at every width
and `SPECIES_MAX`. For (c), the far form above `1.5 ln2`, the cases at every width are
`Expm1Test.magnitudes` (draws spread over the binades `2^-60` to `2^10`, both signs, and `2^16`
draws from 38 to `o_threshold`, where `k` reaches 1024),
`formSplits` (the bands of `k` where the form changes, 19 to 21 and 55 to 58 times `ln2`, and
`+-0.5` to `+-3 ln2`), `denseOverTheForms` (`-45` to `45`), `sampledBitPatterns` and the
published hard cases.

**Encoding.** `proofs/expm1_reduction.smt2`, rendered from `Expm1`'s constants and words by
`Expm1ProofFiles`. Machine-checked there: H13, each of `Expm1`'s five values and two words
against `Exp`'s as ground terms (section A), so the machine-checked parts of L2
(`proofs/exp_near_band.smt2`) and L5 (`proofs/exp_half_sign.smt2`), rendered from `Exp`, check
steps 1 and 2 for expm1 as they stand;
step 3 (section B): the zero products, `x - (+0) = x` for a zero `x` of either sign and for
every nonzero normal double, every binade at once, and `c = +0`; with `t = -0` the step fails
at `x = -0` alone, a lane whose `r` nothing reads, so that check refutes the step as stated for
every `x`, not on the lanes L11 uses; and H14 (section C): at the two witnesses the high word is
`0x3fd62e42`, the formula's sum rounds into binade 2^0, so its cast is `+-1`, and each rounding
of the C as written and of the C reduced with that `k` is evaluated, the two results differing,
while at the word's top double of either sign the cast is `+-1` too and the same two chains end
in one double. Informal above: `fl(x - (-a)) = fl(x + a)` (R4), one rational in this encoding,
so with no check to write; that `x - x = +0` for a nonzero `x` (R5), pinned at sampled doubles
while rendering, as in L6's file; that `StrictMath.expm1` is the forced computation at the
witnesses (a test, the oracle being in no solver), that they are the first from the word's top
and how many doubles differ (a scalar run); a subnormal `x` less `+0`, which no lane reads;
that a value rounds to no double in a binade further off than the next (the arithmetic of
rationals); the casts of `+0.0` and of a double in binade 2^0; that `k` is forced to 0 on every
lane of the word `0x3fd62e42` and below, of which section C checks four doubles (the formula's
`k` would be wrong at the witnesses, the C as written is the oracle's) and the rest is the code's
blend, which `Expm1Test.foldBoundaries` samples (1026 low words per sign at each of its words,
a sparse sample of the word's `2^32` doubles, and 4096 doubles each side of `+-0.5 ln2`, which
is `0x3fd62e42_fefa39ef`, about 17 million doubles below the witnesses at the word's top) and
`Expm1Test.thresholds()` touches (8 doubles each side of `0.5 ln2`, and 64 around the first
doubles of this word and of the next, `0x3fd62e43_00000000`, whose 64 neighbours below are the
top of this word); and `l10KIsForcedByTheHighWordTest` scans the scalar transcription from each
witness to the word's top and finds the forcing not needed there, which says nothing of the
port. The port's blend has a planted fault, `expm1.dropKForcing` (`Expm1.DROP_K_FORCING`),
which gives every lane the formula's `k`: with it planted `Expm1Test`'s `thresholdsAndSpecials`,
`foldBoundaries`, `foldBoundariesAtMaxSpecies`, `masked`, `maxSpecies` and `faultRunInputsPass`
fail, at each width they run: 13 of `thresholds()`' 20935 inputs differ, 11 among the 64 below the
word's top (the first in array order is `0x3fd62e42_ffffffcb`) and 2 at `0.5 ln2`,
`0x3fd62e42_fefa39f7`, the eighth double above `0.5 ln2`, and `0xbfd62e42_fefa39f0`, the first
above it in magnitude; and 822 of `foldBoundaries`' 28698, the first in array order
`0x3fd62e42_fefa39f7`. That list is a run of `-Dtest=Expm1Test` with the fault, which CI does
not make; what CI checks is `Expm1Test.faultCatchers`, which requires every arm to fail at each
width the host has on `faultRunInputs()`. Informal besides: that the port computes these steps,
the code's text.

### L11. The k == 0 return is x - e with e = (x*(e-c)-c)-hxs and c = +0

**Statement.** On the lanes with `k == 0` and `hx >= 0x3c900000`, where L10 gives `r = x` and
`c = +0`, let `e` be line 188's and `E = fl(fl(fl(r*fl(e - c)) - c) - hxs)`, lines 191-192. Then
`E = fl(fl(r*e) - hxs)`, and `fl(r - E)` is line 189's `x - (x*e-hxs)`, bit for bit.

**Hypotheses.**
- (H15) `c = +0` on those lanes (L10, step 3).

**Proof.** `fl(e - (+0)) = e` for every `e`, `-0` included (R3), so `fl(r*fl(e - c)) = fl(r*e)`;
`fl(fl(r*e) - (+0)) = fl(r*e)` (R3 again); so `E` is `fl(fl(r*e) - hxs)`, line 189's inner
term computed from the same `r = x`, `e` and `hxs`, and `fl(r - E)` is its last operation. No
other property is used. The port so computes line 189's result as the same `r - E` that lines
193 and 196 start from (`x-e`), dropping a product and a difference.

**Hinge tests (scalar).** `ProofsTest.l11KZeroReturnIsTheInnerTermsForm`, over the `k == 0`
domain as L10's: `e - c` and `r*(e-c) - c` against `e` and `r*e` by bits, `E` against `r*e -
hxs`, `r - E` against line 189's return and against `StrictMath.expm1`; with L10's step-3 test
for H15. **Vector tie.** As L10's: every `Expm1Test` case of a `k == 0` lane, and the fold
boundaries.

**Encoding.** `proofs/expm1_k_zero_return.smt2`, rendered by `Expm1ProofFiles`. Machine-checked
there: both (R3) steps, `e - (+0) = e` and `v - (+0) = v`, for a zero of either sign and for
every nonzero normal double, every binade at once; that the zero product `r*fl(e - c)` has the
sign `r*e` has; and H15 as a hinge: a double less one step of its binade is another double
(checked in binade 2^-1, and alike in the lanes' binades by the arithmetic of rationals), so
with a `c` of one step of `e`'s binade the first step fails. With
`c = -0` the step fails only at `e = -0`, which no lane has, so that check refutes (R3) as
stated for every `e`, not the lemma on its lanes. The lemma itself needs no bound on `e` or
`fl(r*e)`: (R3) holds for every double. The solver's "a double less +0 is itself" check covers
the normal doubles and the zeros, and that `e` and `fl(r*e)` are nonzero and normal on the
lanes (bounds on the polynomial, sampled by the hinge test, which asserts it) is what puts the
lanes' values under it. Informal above: that bound, the binades further off than the next, H15
itself (L10), and the code's text.

### L15. expm1's words in long lanes, as Varka emits them

Varka's emitted kernel (`VarkaMathPort.expm1`, in vecbricks/varka, not in this repository)
computes `Expm1.expm1Blended`'s operations with the C's `int` words in long lanes, as `Log` holds
its words (L8), since a kernel's high-word tests are L3's compares of `|x|`'s bits and its `k` is
the long `I2L` of the `D2I`. The doubles are the same operations in the same order; this lemma
covers the words. `Expm1` here keeps those words in `IntVector` lanes, so (a) to (c) are about
Varka's kernel only; (d), the exponent add, is also `Expm1`'s own (and `Lanes.addExponent`'s).

**Statement.** Let `k` be the C's `int k` (from L10's reduction) widened to a long.
- (a) On a lane with `2 <= k < 20`, the long `0x3ff00000 - (0x200000 >>> k)` is the C's
  `0x3ff00000 - (0x200000>>k)` widened; on a lane with `20 <= k <= 56`, the long `(0x3ff - k) <<
  20` is the C's `(0x3ff-k)<<20` widened.
- (b) For every long `w` whose low 32 bits are the C's word `W`, `w << 32` is the bits of the
  double whose high word is `W` and whose low word is 0, the C's `t = one; __HI(t) = W`.
- (c) The tests of `k`, `k <= -2`, `k > 56`, `2 <= k < 20`, `k == 1`, `k == -1` and `k == 0`, are
  the C's on the long.
- (d) For every double `y` and every `k` with `|k| < 2^11`, `bits(y) + (k << 52)` (a long add)
  has `y`'s low word and the high word `__HI(y) + (k<<20)` modulo 2^32: the C's `__HI(y) +=
  (k<<20)` (lines 199, 206 and 211), wherever the C's `int` add does not overflow.

**Hypotheses.**
- (H16) The words are read only on the lanes whose form uses them: the kernel picks `t`'s word
  by the masks of `k`, and a word computed on any other lane is never kept, whatever the shift
  count there. It is needed: at `k = 40`, a lane of the `20 <= k <= 56` form, `0x200000 >> 40`
  (an `int` shift, count modulo 32) and `0x200000 >>> 40` (a long one, modulo 64) differ.
- (H17) `-56 <= k <= 1024` on every lane whose result is kept. L10's domain is the lanes the C
  does not return early from: `x <= o_threshold`, and `hx < 0x4043687A` for a negative `x`. The
  formula `(int) fl(fl(x*invln2) + h)` is non-decreasing in `x` on each sign (R2, and the
  truncation), and it gives -56 at the lowest such `x` and 1024 at `o_threshold`.

**Proof.**
1. (a) For `2 <= k < 20`, `0x200000 >> k` is a positive int below `2^20`, so the arithmetic
   and the logical shift agree (JLS 15.19) and the difference lies in `(0x3fe00000, 0x3ff00000)`,
   inside 32 bits. For `20 <= k <= 56`, `0x3ff - k` lies in `[0x3c7, 0x3eb]` and its shift by 20
   in `[0x3c700000, 0x3eb00000]`, below `2^31`, so no int overflows and the long is the int
   widened.
2. (b) `w << 32` keeps `w`'s low 32 bits as the high word and shifts the rest out, with zeros
   below, so the bits above `W` in `w` are irrelevant.
3. (c) `k` is an `int` widened, so each compare with an int constant has the int compare's answer
   (JLS 5.6).
4. (d) `k << 52`, for `|k| < 2^11`, is `(k<<20) * 2^32` modulo 2^64, with `k<<20` the C's int (no
   overflow for `|k| < 2^11`): its low 32 bits are zero. Adding it to `bits(y)` leaves the low
   word, and no carry passes from the low word into the high one. The high 32 bits get `k<<20`
   added modulo 2^32, since a carry out of bit 63 is dropped by the long add. That is the C's
   `int` add on the high word wherever that add does not overflow (and its two's-complement wrap
   where it does).

No IEEE property is used but R2, in H17. With H16, (a) to (c) give the C's `t` on each lane that
reads it, and (d) the C's scaled `y`; H17 puts every kept lane's `k` in the range the steps cover.

**Hinge tests (scalar).** `ProofsTest.l15ThresholdsAreTheCs`: `Expm1.K_FAR` and `K_SPLIT` are the
C's 56 and 20. `ProofsTest.l15LongWordsAreExpm1sInts` (a, b): for every `k` from -1100 to 1100
whose form reads a word, by the C's literal bounds (55 of them), the long word against the C's int
word and the double it heads against `1 - 2^-k` or `2^-k`; `one`'s word against 1.0, once; and
the two shifts at `k = 40`, where they differ. `ProofsTest.l15ExponentAddIsTheCsHighWordAdd`
(d): for every `k` from -1100 to 1100 and 1024 `y` bit patterns (high words 0, `0x3fe00000`,
`0x3ff00000`, `0x7fefffff`, `0x7fffffff`, `0x80000000`, `0xbff00000` and `0xffffffff`, which
wrap either way, against low words 0, 1, `0x7fffffff`, `0x80000000` and `0xffffffff`, and seeded
draws), the long add against the C's int add on the high word. (c) has no scalar test: comparing
an int with its widening cannot fail; the vector test below runs every form.
**Vector tie, here.** `ProofsTest.l15MasksKeepEachWordOnItsLanes` checks H16 on `Expm1`'s own
masks, which Varka's kernel emits: `Expm1.expm1Blended`, at every width, against
`StrictMath.expm1` by raw bits on 7569 inputs that cover every `k` from -56 to 1024 (each input's
`k` and that it is no early-return lane are asserted, and H17's two end doubles with them),
shuffled so that a vector mixes the forms, `k == -1`, `0` and `1` among them. A mask that kept a
word on a lane whose form does not read it fails there wherever that word differs from the
lane's own (the int mid word is one's on a far lane with `k mod 32 >= 22`, so not there):
blending the mid word over `k == 40` made the 7 inputs at `k = 40` differ, and keeping the high
word on the far lanes 7163 of the 7569 (each at every width, mutations made by hand and
reverted). That Varka's kernel uses
these masks and its long words is not checked here. **Vector tie, in Varka.** Where L15's own
claim, the kernel, is checked: Varka's `VarkaMathPortSuite` (vecbricks/varka PR #1629) runs the
emitted `VarkaMathPort.expm1` against `VFdlibm.expm1` and `Expm1.expm1Blended` by raw bits, on
2^24 sampled inputs and the fixed vectors at each `k` form's edge.

**Encoding.** None, no machine-checked file: the steps are integer ranges and identities, checked
by the Java loops above over `-1100 <= k <= 1100`, which holds every `k` of H17, `-56 <= k <=
1024`; the bounds themselves rest on L10's domain and the monotonicity of the formula, the end
doubles asserted by `l15MasksKeepEachWordOnItsLanes`. Informal: that Varka's kernel is
`expm1Blended`'s operations with these long words, which only Varka's vector tie checks.

## log10 (`Log10.java`, from `e_log10.c`)

e_log10.c reads `k` from the high word, scaling a subnormal by `2^54` first (lines 76-82); sets
`i = 1` when `k < 0` (85); rewrites the high word to `(hx&0x000fffff)|((0x3ff-i)<<20)` (86-88),
which puts `x` in `[1, 2)` or `[0.5, 1)`; and returns `(y*log10_2lo + ivln10*log(x)) +
y*log10_2hi` with `y = (double)(k+i)` and `log` e_log.c's `__ieee754_log` (87-90). The port
computes these operations in the C's order, with no fused multiply-add and no reassociation, and
**needs no lemma of its own**. Where its text is not the C's:

- The C's `int` words stay `int` lanes, every test on them the C's signed `int` compare, and `y`
  is an exact `I2D` (`|k+i|` is far below `2^53`), a vector instruction on every AVX2 host: no
  step like L7 or L8 is taken.
- `ivln10*log(x)` is written `log(x)*ivln10`: the same exact product, rounded once, so the same
  double (R4); the sums are in the C's order.
- The C's early returns (lines 77-79 and 83) are selected by masks whose tests are the C's (the
  blended arm), or finished per lane by `StrictMath.log10` after the vector body (the finish
  arm).

**What it rests on.** The `log` of line 89 is `Log.logCore(xn)` for the rescaled `xn`: Log's own
body, the same lines, with the rare paths and the rare-lane finish left out. So the lemmas of
log hold for log10's lanes as they stand:

- L6, the `k == 0` forms as the `k != 0` forms with `dk = +0`. Its H9 gives `f` finite and never
  `-0` for any `xn` in `[0.5, 2)`, which every lane's is.
- L7, `dk = (double)k` without `L2D`, with H11 from L8's bound on `k`.
- L8, the C's int words in long lanes. Its H12, the sign bit of the argument clear, is supplied
  by log10's rescale: the high word `(hx&0x000fffff)|((0x3ff-i)<<20)` has a clear sign bit and
  the exponent field `0x3ff` or `0x3fe` whatever `x` is, so every lane's argument is a positive
  normal finite double in `[0.5, 2)`, a NaN, an infinity, a zero or a negative `x` included.
  These are the common lanes of `log` (e_log.c's tests at lines 100 and 107 false), where
  `logCore` is the C's `__ieee754_log`. For a lane log10 returns early on, `logCore` still
  computes the `log` of its rescaled argument, and the rare return replaces it.

L9 is not used: the compare that finds `log`'s common lanes feeds a rare-lane mask that `logCore`
never reads, and the subnormal compare belongs to the blended arm of `log`, which `logCore` is
not.

**Hinge tests.** Those of L6 to L8 (`ProofsTest.l6ZeroDkIsPositiveZero`,
`l6FIsNeverNegativeZero`, `l6FoldedFormsAreTheKZeroForms`, `l6HingesRefuted`,
`l7ExactConversionIsI2D`, `l8LongWordsAreTheCsInts`), which run on `Log`'s scalar words. The
hypothesis that is log10's, that its argument is a positive normal finite double in `[0.5, 2)`,
has no scalar test of its own: it follows from line 86's expression as the port writes it. On
a common lane of log10 a lane outside `logCore`'s domain would take its unspecified value and
so fail where that differs from `StrictMath.log10`; on a rare lane the result is replaced, so
that lane's argument is checked by nothing, and nothing in its result depends on it.
**Vector tie.** `Log10Test`, against `StrictMath.log10` by bits, at every width, both arms:
e_log10.c's high-word tests and the split of line 85 at 1.0 (`thresholdsAndSpecials`,
`insideTheBranchWords`), e_log.c's significand words, the subnormals and `[0.5, 2)`
(`nearOneAndSubnormals`), sampled bit patterns, dense draws over every binade and the
published hard cases. The cases run unmasked at the `VectorWidth` shapes, with these
exceptions: `masked` runs the arms masked, at those shapes, on its own inputs; `maxSpecies`
runs `SPECIES_MAX`, unmasked and masked; `everySubsetOfLanesRare` (at the shapes and at
`SPECIES_MAX`) runs every subset of a vector's lanes rare, unmasked and masked; and
`exactPowersOfTenGiveTheirExponent` runs the powers of ten at the preferred species and
`SPECIES_MAX`.

**Encoding.** No file of its own. `proofs/log_zero_dk.smt2` (L6), `proofs/log_exact_dk.smt2` (L7)
and `proofs/log_long_words.smt2` (L8) are rendered from `Log`'s constants and check these steps
for log10 as they stand, `logCore` being the same body. Informal here: that `xn`'s sign bit and
exponent field are as above (the expression of line 86, read off the code), that the write-back
of the new high word, `I2L` then a shift left by 32, is the C's `__HI(x) =` because that word is
never negative (so sign extension and zero extension agree, as in L8's proof), the
commutativity of the product, and that the port computes these steps, the code's text.

### L17. log10's words in long lanes, as Varka emits them

Varka's emitted kernel (`VarkaMathPort.log10`, in vecbricks/varka, not in this repository)
computes `Log10.log10Blended`'s doubles in the same order, with the C's `int` words in long
lanes, as `Log` holds its own (L8), so the kernel needs no `I2L` or `L2I`; its `log` of the
rescaled lane is `Log.logCore`'s nodes. `Log10` here keeps those words in `IntVector` lanes and
takes `y` by an exact `I2D`, so this lemma is about Varka's kernel only, and the section above,
that `Log10` needs no lemma of its own, still holds. The subnormal mask, `0 < x < MIN_NORMAL`,
is the same IEEE compare in both.

**Statement.** Let `b` be a lane's bits after the subnormal scaling (`xs` of line 80) and `s` 1
on a scaled lane, 0 otherwise. Write `hx = (int) (b >> 32)` for the C's signed high word, `k =
(hx >> 20) - 1023 - 54s` and `i = ((unsigned)k&0x80000000)>>31` for the C's, and `HX = b >>> 32`
for the long one.
- (a) If `b`'s sign bit is clear, the long `K = (HX >>> 20) - 1023 - 54s` is `k` widened, the
  long `I = K >>> 63` is `i` widened, and the long `(HX & 0xfffff) | ((0x3ff - I) << 20)` is the
  C's new `hx` of line 86 widened, a value in `[0, 2^31)`, so the long shift by 32 that writes
  it back is the C's `__HI(x) = hx`.
- (b) For every `K` with `-1077 <= K <= 1024` and `I = K >>> 63`, `fromBits(K + I +
  0x43380000_00000000) - 0x1.8p52` is the double `K + I`, the C's `(double)(k+i)` (L7).
- (c) For every `b`, either sign, the signed long compares `b > 0x7ff00000_00000000 - 1` and `b <
  0` are the C's `hx >= 0x7ff00000` and `hx < 0` (lines 83 and 79).

**Hypotheses.**
- (H18) The sign bit of `b` is clear on every lane whose result is kept. A lane with it set is
  `x < 0`, `-0` or a NaN with the sign bit set, which the C answers at line 79 with
  `(x-x)/zero` (a NaN) or at line 78 with `-two54/zero` (`-inf`), and the kernel blends those
  two answers over every earlier result.
  It is needed: for a negative `b`, `HX >>> 20` holds the sign bit and `K` is not the C's `k`.

**Proof.**
1. (a) With the sign clear, `b >> 32 = b >>> 32 = HX` lies in `[0, 2^31)`, so `HX` is `hx`
   widened and `HX >>> 20` is the C's `hx >> 20` (JLS 15.19: on a non-negative value the two
   shifts agree). `HX >>> 20` lies in `[0, 0x7ff]`, so `K` lies in `[-1077, 1024]` and is `k`
   widened. A long's bit 63 is its sign, so `K >>> 63` is 1 exactly when `K < 0`, and so is the
   C's `i` for the int `k`. `HX & 0xfffff` keeps the bits that `hx & 0x000fffff` keeps, and
   `0x3ff - I` is `0x3ff` or `0x3fe`, whose shift by 20 is below `2^30`: the OR lies in `[0,
   2^31)`, the int has no overflow, and the long is the int widened. A non-negative word below
   `2^31` shifted left by 32 is the C's high word with zero low bits, which the kernel ORs over
   `b`'s low word, as L8's proof does.
2. (b) `K + I` lies in `[-1076, 1024]`, inside L7's `[-2^51, 2^51)`, so the add of the bits of
   `0x1.8p52` and the subtract of `0x1.8p52` are exact (L7), and give the same double as the
   exact `I2D` of the int `k + i`.
3. (c) `b = hx * 2^32 + lo` with `0 <= lo < 2^32` as signed longs, so for any int `h`, `hx >= h`
   iff `b >= h * 2^32`, which for `h = 0x7ff00000` is `b > 0x7ff00000_00000000 - 1`; and `hx < 0`
   iff `b < 0`. This is L9's argument on the raw bits.

No IEEE property is used but L7's exact conversion. With H18, (a) and (b) give the C's words and
`y` on every kept lane; (c) holds on every lane, so the rare lanes are the C's.

**Hinge tests (scalar).** `ProofsTest.l17LongWordsAreLog10sInts` (a, b): a loop over every
exponent field from 0 to `0x7ff`, scaled and not, which by step 1 is every value `K` takes (the
words depend on `b` only through `HX >>> 20` and `HX & 0xfffff`), checks `K`, `I` and `y`
against the C's int `k`, `i` and `I2D`, and `K` in `[-1077, 1024]`; it also checks, from the
smallest and the largest subnormal, that a scaled lane's field is 3 to 54, so a kept lane's `K`
is in `[-1074, 1023]` (field `0x7ff`, `K = 1024`, is `+inf` or a NaN, always blended over). A
second loop runs every significand word from 0 to `0xfffff`, for both `I`, the long new high word
against the C's and below `2^31`; the exponent field is masked away in both, so it needs no
loop. `ProofsTest.l17RareComparesAreTheCs` (c): the two long compares against
the C's on every high word within `2^16` of 0, `0x7ff00000`, `0x7fffffff` and `0x80000000` (where
an answer changes or the int wraps), with five low words each, and on 2^20 sampled patterns.
`ProofsTest.l17NegativeLanesAreAnsweredEarly` is a witness for H18 and the oracle's own check,
not a hinge test: on every sign-set pattern of a set of specials, sampled patterns and every
exponent field, `StrictMath.log10` is a NaN, or `-inf` for `-0`, as the C's early returns give.
On every one of those patterns the long `K` is not the C's `k`, which shows H18 is needed: the
long field holds the sign bit, so `K` is at least 1025, while the C's `k` is at most -1024; this
holds for every input, so no test checks it. The checks of H18 are
`l17NegativeLanesAreBlendedOver` below and Varka's suite. **Vector tie, here.**
`ProofsTest.l17NegativeLanesAreBlendedOver` checks H18 on `Log10`'s own masks, whose blends
Varka's kernel emits in the same order: `Log10.log10Blended`, at every width, against
`StrictMath.log10` by raw bits on those sign-set patterns, each beside its positive twin so that
a vector mixes rare and kept lanes; a rare blend dropped or placed before the general form fails
there. **Vector tie, in Varka.** Where L17's own claim, the kernel, is checked: Varka's
`VarkaMathPortSuite` (vecbricks/varka#1645) runs the emitted `VarkaMathPort.log10` against
`VFdlibm.log10` and `Log10.log10Blended` by raw bits, on 2^24 sampled inputs and the fixed
vectors of e_log.c's and e_log10.c's words. That Varka's kernel uses these long words is not
checked here.

**Encoding.** None, no machine-checked file: (a) is an integer identity checked by the Java
loops above over every exponent field and every significand word; (c) is step 3's argument,
checked at its boundaries and on samples, not over every word; and (b) is L7's file,
`proofs/log_exact_dk.smt2`, over a range that holds `[-1076, 1024]`. Informal: that Varka's
kernel is `log10Blended`'s operations with these long words, which only Varka's vector tie checks.

## log1p (`Log1p.java`, from `s_log1p.c`)

s_log1p.c returns early for `x <= -1`, `|x| < 2^-29` and a non-finite `x` (lines 113-124 and
128), takes `k = 0` and `f = x` for `-0.2929 < x < 0.41422` (125-126), and otherwise writes
`1+x = 2^k * (1+f)` with `u = 1.0+x` and the correction `c` (129-150). Then `hu == 0` selects
one of two ways to finish: for `|f| < 2^-20` the short path, `R = hfsq*(1.0-0.66666666666666666*f)`
(lines 153-159), and otherwise the general polynomial path, `s = f/(2.0+f)` and the degree-14
`R` in `s` (160-164). The port turns the C's branches into lane masks and computes each form
with the C's operations in the C's order, with no fused multiply-add and no reassociation (the
Javadoc of `Log1p`). **No lemma proves a departure, because it has none, and no lemma proves
the `hu == 0` path:** the path is not redundant. The port keeps it as the C writes it, because
the search below found that folding it into the general path is not exact.

### L12. The hu == 0 path cannot be folded (a counter-example, not a fold)

**Statement.** The short path (lines 156-158, the lanes with `hu == 0` and `f != 0`) and the
general path (lines 160-164) do not give the same double for every input: sending those lanes
down the general path, as a port that dropped the short path would, is not bit-identical.

**Counter-example.** Four of the inputs found, `C` the C as written, which is `StrictMath.log1p`
at each, and `general` the same C with lines 156-158 skipped:

| `x` | `1+x` | `k` | `C` | `general` |
| :--- | :--- | ---: | :--- | :--- |
| `0x3feffffd_05263833` | just below 2 | 1 | `0x3fe62e41_818d4d28` | `0x3fe62e41_818d4d27` |
| `0x3ff00001_6fcd0162` | just above 2 | 1 | `0x3fe62e44_6ec73310` | `0x3fe62e44_6ec7330f` |
| `0xbfe00001_2cac6e81` | just below 0.5 | -1 | `0xbfe62e45_58532d03` | `0xbfe62e45_58532d04` |
| `0xbfdffffe_92ae1567` | just above 0.5 | -1 | `0xbfe62e41_91a8577b` | `0xbfe62e41_91a8577c` |

(`hu == 0` is reached by line 148's `hu = (0x00100000-hu)>>2` below a power of two and by
`hu == 0` directly above it, so the four cover both normalisations.) The short path stops
the series of `log1p(f)` at `f^3/3`, the general one does not, and the two round differently
on about one input in `2^29` of these windows: of every double `x` whose raw bits lie
in `[0x3feffffa_00000000, 0x3ff00002_00000000)` (`1+x` in `[2 - 3*2^-20, 2 + 2^-19)`, `2^35`
doubles) 56, and of every `x` whose raw bits lie in `[0xbfdffffe_00000000, 0xbfe00001_80000000)`
(`1+x` in `(0.5 - 3*2^-22, 0.5 + 2^-21]`, 15,032,385,536 doubles) 20. The `hu == 0` inputs near
0.5 are `1+x` in `[0.5 - 3*2^-22, 0.5 + 2^-21)`, so the second range leaves out the input
`x = -(0.5 + 3*2^-22)` and holds two inputs that are not on the path, `x = -(0.5 - 2^-21)`
(raw bits `0xbfdffffe_00000000`) and the next double, whose `1+x` rounds to `0.5 + 2^-21`, where
`hu = 1`. The windows of the other powers of two of `1+x` are not searched. The counts, the
sixteen differences each pass printed, the commands and the host are in
`bench/results/log1p-hu0-search-laptop-2026-11-20.txt`; the two passes ran a scratch program
kept in that file as text, not `Log1pShortPath`, which found exactly the witness in each of two
slices of `2^24` doubles around one.

**Test (scalar).** `Log1pTest.shortPathIsNotTheGeneralPath` asserts the four rows, each
through `Log1pTest.cLog1p`, a transcription of s_log1p.c checked against `StrictMath.log1p`
(`Log1pTest.scalarTranscriptionIsStrictMath`); it fails if the two paths agreed there.
**Vector tie.** `Log1pTest.shortPathWitnessesThroughEveryArm`: the four rows and their neighbours
one ulp each way, at every position of a vector, through every arm of `Log1p` (the public one,
the blended, the guarded and the finish arm), at every width the host has, unmasked and masked,
by raw bits against `StrictMath.log1p`. The random cases (`nearPowersOfTwo` and the rest) draw
the inputs that differ about once in `2^29` and would not see a fold; this test does not rely
on a draw. **Planted fault.** `log1p.foldShortPath` (`Log1p.FOLD_SHORT_PATH`) sends the lanes
with `hu == 0` and `f != 0` down the general path, as a port that dropped the short path would,
and keeps lines 154-155's `f == 0` returns; `Log1pTest.faultCatchers` requires the blended
arm and the guarded arm to fail on the witnesses with it planted, at every width. The finish
arm, which the public one is, finishes the `hu == 0` lanes with `StrictMath.log1p`, so it cannot
see the fold and is not asked to. The nightly's fuzz is very unlikely to find this fault either (its
inputs that differ are about one in `2^29` of a window), so
`NightlyTest.fuzzCatchesThePlantedFault` leaves it out. With the fold planted the rest of
`Log1pTest` passes: no other case reaches a witness.

**Encoding.** None. The two results are Java's evaluation of the transcription, not a solver's,
and the search is a program, so nothing here is machine-checked in `proofs/`; an evaluation of
the chain like L4's is not written.

### L18. log1p's signed compares and words in long lanes, as Varka emits them

Varka's emitted kernel (`VarkaMathPort.log1p`, in vecbricks/varka, not in this repository)
computes `Log1p.log1pBlended`'s doubles in the same order, with the C's `int` words in long
lanes, as `Log` holds its own (L8): the tests of the signed `hx` are signed compares of the raw
bits, those of `ax` are L3's compares of `|x|`'s bits, `u`'s words are read by logical shifts, and
`k` is a long lane whose `(double)k` is L7's exact conversion. `Log1p` here keeps those words in
`IntVector` lanes, so this lemma is about Varka's kernel only, and the section above, that
`Log1p` itself has no departure to prove, still holds.

**Statement.** Write `b` for a lane's raw bits and `hx = (int) (b >> 32)` for the C's signed high
word.
- (a) For every int `h`, `hx < h` iff the signed long compare `b < h * 2^32` holds, and for every
  `h > Integer.MIN_VALUE`, `hx >= h` iff `b > h * 2^32 - 1`; `h * 2^32` is `Lanes.fromHiBits(h)`
  as a signed long. So `hx < 0x3FDA827A` (line 113), `hx > 0` (`hx >= 1`), `hx <= 0xbfd2bec3`
  (`hx < 0xbfd2bec4`, line 125), `hx >= 0x7ff00000` (line 128) and `hx >= 0x43400000` (line 130)
  are each one signed compare of `b`.
- (b) Let `u` be `1.0+x`, or `x` for `x >= 2^53` (the C's `u` of lines 131 and 137), `HU =
  bits(u) >>> 32` and `K = (HU >>> 20) - 1023`, computed on every lane. On every lane that reaches
  lines 129-150 (`x > -1`, finite, `|x| >= 2^-29`, and not on line 126's `k = 0` path, so `x <=
  -0.2929` or `x >= 0.41422`), `HU` is the C's `hu = __HI(u)` widened, `K` is the C's `k` of
  lines 133 and 139, `K + 1` is line 146's, and the long forms of `hu & 0x000fffff`, `hu <
  0x6a09e`, `hu|0x3ff00000`, `hu|0x3fe00000` and `(0x00100000-hu)>>2` (the last by a logical
  shift) are the C's ints and compares widened. A word `w` so formed, shifted left by 32 and ORed
  over `bits(u)`'s low word, is the C's `__HI(u) = w`. Every `K` there, after line 146, lies in
  `[-53, 1024]`. On every bit pattern, kept lane or not, `K` and `K + 1` lie in `[-1023, 3073]`,
  since `HU < 2^32`; that is inside L7's domain, which discharges L7's H11 for this kernel.

**Hypotheses.**
- (H19) The words of (b) are kept only on lanes with `x > -1`. A lane with `x <= -1` (`-inf`
  among them) or a NaN of either sign is answered early by the C: `-two54/zero` (`-inf`) for `x
  == -1`, `(x-x)/(x-x)` (a NaN) for the other `x <= -1` and the NaNs with the sign set (lines
  114-116), and `x+x` for the NaNs with it clear (line 128); the kernel blends those answers over
  every other result. It is needed: for `x < -1`, `u` is negative, `HU` holds the sign bit and
  is at least `2^31`, where the C's `hu` is negative, so `HU` is not `hu` widened.
- (H20) The words of (b) are kept only on lanes that reach lines 129-150. On line 126's lanes
  (`hx < 0x3FDA827A`, `hx > 0` or `hx <= 0xbfd2bec3`, so `-0.2929 < x < 0.41422`) the kernel
  blends `k = 0`, `f = x` and `hu = 1` over the words computed from `u`, as the C sets them
  there and never computes `u`; on the tiny lanes (`|x| < 2^-29`, lines 118-123) and the
  non-finite ones (line 128) the C's early answers are blended over every other result. It is
  needed: at `x = 2^-25`, a line-126 lane, `u = 1 + 2^-25` has `HU & 0x000fffff = 0` and `K =
  0`, so a kernel that kept the words computed from `u` there, even with `f = x` blended, would
  take the `hu == 0` short path of lines 153-158 with `k = 0` and `f = x`, a path the C never
  takes (L12's rows are `k = 1` and `k = -1` lanes, so they do not cover it). At `2^-25` the
  short path happens to give the C's bits; at `x = 0x3ea5a84a_dfba2358` (`1+x` in `(1, 1 +
  2^-20)`) and `x = 0xbeaa4106_df3cfdd0` (`1+x` in `(1 - 2^-20, 1)`), where the kept `K` and
  `hu` are 0 too, its result is one ulp off the C's, `0x3ea5a84a_6a776be3` against
  `0x3ea5a84a_6a776be2` and `0xbeaa4107_8b8e7e29` against `0xbeaa4107_8b8e7e2a`.

**Proof.**
1. (a) As signed longs, `b = hx * 2^32 + lo` with `0 <= lo < 2^32`, and `h * 2^32` lies in
   `[-2^63, 2^63)`. If `hx < h`, then `hx <= h - 1` and `b <= (h - 1) * 2^32 + 2^32 - 1 < h *
   2^32`; if `hx >= h`, `b >= h * 2^32`. `(h & 0xffffffffL) << 32` keeps `h`'s 32 bits as the
   high word, which is `h * 2^32` modulo `2^64`, its two's-complement value. The second compare
   is the first negated, and `h * 2^32 - 1` does not wrap for `h > Integer.MIN_VALUE`. This is
   L3's argument on the raw bits, without the sign mask, for any `h`.
2. (b) On a lane that reaches lines 129-150, `x > -1`; for `x < 2^53`, `x >= nextUp(-1) = -1 +
   2^-53`, so `u = fl(1 + x) >= fl(2^-53)
   = 2^-53` by R2; for `x >= 2^53`, `u = x`. So `u > 0` and its sign bit is clear: `HU` lies in
   `[0, 2^31)`, is `hu` widened, and the logical shifts of `HU` are the C's arithmetic ones on
   `hu` (JLS 15.19). `u`'s exponent field lies in `[0x3ca, 0x7ff]`, so `K` lies in `[-53, 1024]`;
   line 146's `K + 1` is taken only when `hu & 0x000fffff >= 0x6a09e`, which field `0x7ff` has
   only for a NaN `u`, and a NaN `u` comes only from a NaN `x`; so after it `K` is at most `0x7fe
   - 1023 + 1 = 1024`. `hu & 0x000fffff` is the same 20 bits in both; ORed with `0x3ff00000` or
   `0x3fe00000` it lies in `[0, 2^30)`, and `0x00100000 - (hu & 0x000fffff)` lies in `(0, 2^20]`,
   positive, so the logical and the arithmetic shift by 2 agree. A word in `[0, 2^31)` shifted
   left by 32 is the high word with zero low bits, as in L15 (b) and L8's proof. On any bit
   pattern, `HU` lies in `[0, 2^32)`, so `HU >>> 20` lies in `[0, 0xfff]` and `K` and `K + 1` in
   `[-1023, 3073]`.

No IEEE property is used but R2, in (b)'s bound on `u`, and L7's exact conversion. With H19 and
H20, (b) gives the C's `k`, `u` and `hu` on every lane that reaches lines 129-150, and H20's
blends give the C's `k = 0`, `f = x` and `hu = 1` on line 126's lanes; (a) holds on every lane,
so the masks are the C's.

**Hinge tests (scalar).** `ProofsTest.l18SignedComparesAreTheCs` (a): for each of s_log1p.c's words
above and the int range's ends, the two long compares against the C's int compares on every high
word within `2^16` of that word, with five low words each, and on 2^20 sampled patterns against
every one of those words. `ProofsTest.l18LongWordsAreLog1psInts` (b): a loop over every exponent
field from 0 to `0x7ff` with the sign bit clear and set, read from `u`'s raw bits by a logical
shift as the kernel reads them, checks that `HU` and `K` are the C's `hu` and `k` exactly when the
sign is clear, so a reading that matched the C on a sign-set word (one H19 keeps out) fails there;
with the logical shift replaced by hand by an arithmetic one (and reverted), it failed at the first
sign-set word, `0x800`. On every pattern it checks `K` and `K + 1` in `[-1023, 3073]`. With the
sign clear, which by step 2 is every value `HU >>> 20` takes on a kept lane (the words depend on
`HU` only through its field and its significand word), it checks that `K + 1` is the C's and that
`K` and `K + 1` lie in `[-53, 1024]` from field `0x3ca` (below `0x7ff` for `K + 1`); these are
arithmetic restatements of `K = field - 1023` and cannot fail, so only the sign-set half of the
loop can. It checks that `1.0 + nextUp(-1.0)` has field `0x3ca`, the smallest `u`, and that field
`0x7ff` reaches line 146 only for a NaN. On the lanes above -1 among 2^17 sampled inputs, 2^14
drawn over line 126's band and nineteen edges (from `nextUp(-1)` to `+inf`, `2^-25` and the tiny
band's among them), it checks H20: it runs the C's control flow on int words (the tiny and
non-finite lanes answered early, `k = 0` and `hu = 1` on line 126's lanes, lines 129-149
otherwise) against the kernel's long words with its blends, the final `k` and `hu` of each lane
that is not answered early, and where lines 129-150 run, that `u`'s sign is clear, `HU` is `hu`
and `K` is in `[-53, 1024]`. With the kernel's `hu = 1` blend on line 126's lanes dropped by hand
(and reverted), it failed at `x = -0.25`, `hu` 131072 against the C's 1. And a loop over
every significand word from 0 to `0xfffff` checks the long `hu & 0x000fffff`, the compare with
`0x6a09e`, both ORed words, the quarter word and the rebuilt bits of `u` against the C's ints, an
integer identity, since the mask drops the sign.

`ProofsTest.l18KeptWordsOnLine126LanesAreNotTheCs` is H20's witness, not a hinge test: no change
to `Log1p` or to Varka's kernel can make it fail, since it checks the test's own model of a
kernel that broke H20. Lines 152-164 from the C's `k = 0`, `f = x` and `hu = 1` give
`StrictMath.log1p`'s bits on the two witnesses, `2^-25` and 2^12 sampled lanes of line 126's
band, and from the C's words of lines 129-150 on L12's four rows (the short path with `k = 1` and
`k = -1`) and the lanes of 2^13 sampled inputs that reach those lines, so its finish is the C's
on both paths; the same model's words without line 126's two blends have `K = 0` and `hu = 0`
at `2^-25` and at each witness, and with `f = x` the witnesses' results are the bits H20 quotes,
one ulp off `StrictMath`'s, and `2^-25`'s are equal. **Vector tie, here.**
`ProofsTest.l18Line126WitnessesThroughEveryArm` runs the witnesses, their neighbours and `2^-25`,
each at every lane position among line-126 lanes, through every arm of `Log1p` at every width,
unmasked and masked, against `StrictMath.log1p` by raw bits. The finish arm, which the public one
is, cannot see a dropped `hu = 1` blend: the lane gets `hu == 0`, joins the rare mask and is
finished by `StrictMath.log1p`, as for L12's fold; the blended and the guarded arm see it. With
`Log1p`'s `hu = 1` blend on line 126's lanes dropped by hand (and reverted), the public arm passed
and the blended arm, the next one checked, failed at every width, 48 of its 448 inputs differing.

`ProofsTest.l18EarlyLanesAreAnsweredEarly` (H19): every lane of a set with `!(x > -1)` (one pattern
at every exponent field from `0x3ff` up with the sign set, `-1` and its neighbours below, the
specials and sampled patterns that are not above -1, the NaNs of both signs) meets the C's
early-return test of line 114 or line 128, and `StrictMath.log1p` answers it `-inf` at `-1` and a
NaN elsewhere; and on every non-NaN lane of the set below -1, `HU` is not the C's `hu` widened,
which shows H19 is needed. It is a witness for H19 and the oracle's own check, not a hinge test:
it runs s_log1p.c's scalar tests and `StrictMath` on inputs not above -1 by construction, so it
checks the C, not that a kernel keeps words only on lanes above -1. The checks of H19 are
`l18EarlyLanesAreBlendedOver` below and Varka's suite. **Vector tie, here.**
`ProofsTest.l18EarlyLanesAreBlendedOver` checks H19 on `Log1p`'s own masks, whose blends Varka's
kernel emits in the same order: `Log1p.log1pBlended`, at every width, against `StrictMath.log1p` by
raw bits on that set, each lane beside a kept twin above -1 (its magnitude, or 0.25 for a NaN or
`-inf`, whose magnitude `+inf` is answered early too), so that a vector mixes early and kept
lanes. With the `x <= -1` blend dropped by hand from `Log1p.log1p` (and reverted), it failed at
every width, 17447 of its 34946 inputs differing. **Vector tie, in Varka.** Where L18's own claim,
the kernel, is checked: Varka's `VarkaMathPortSuite` (vecbricks/varka, the pull request that adds
`VarkaMathPort.log1p`, branch `varka-345e-log1p-r`) runs the emitted `VarkaMathPort.log1p` against
`VFdlibm.log1p` and `Log1p.log1pBlended` by raw bits, on 2^24 sampled inputs and the fixed vectors
of s_log1p.c's words and L12's witnesses, and holds its two signed compares to (a)'s definitions.
That Varka's kernel uses these compares and long words is not checked here.

**Encoding.** None, no machine-checked file: (b) is an integer identity checked by the Java loops
above over every exponent field and every significand word, with the bound on `K` from step 2
(R2 and the smallest `u`), and H20 is checked on samples, not over every input; (a) is step 1's
argument, checked at its boundaries and on samples, not over every word. Informal: that Varka's
kernel is `log1pBlended`'s operations with these compares and long words, which only Varka's
vector tie checks.

## Blends (the argument sinh and cosh share)

### L13. A blend of branches computes the C's branch on each lane

The C picks a branch with an `if` on the input. A vector has no per-lane branch, so a port
computes the branches' operations on every lane and a blend keeps each lane's. There are two
forms: blending results (each branch whole, then the blend), and blending operands (the operand
two branches give one operation, picked first, then one operation: sinh's division by `t+one`
with numerator `t*t` or `t`). This lemma covers both.

**Hypotheses**, for each lane with input `x`:

- **H1, selection.** The mask lane is set exactly when the C's condition holds on `x`: the same
  predicate, the same constant, the same words (the high word `ix`, say). The compare is exact (an
  integer compare, or a compare of doubles, which rounds nothing). In an `else if` chain the
  masks are nested as the chain is, so each lane takes the first branch whose condition holds.
- **H2, operands.** In a lane whose branch is `B`, each operation that reaches the result has as
  operands what `B`'s same operation has in the C: either the result of an earlier operation of
  that lane, or an operand picked by an operand blend under H1's mask where `B` has it. The
  operations are the C's, in the C's order: no fused multiply-add, no reassociation.
- **H3, totality.** Each operation the port computes on a lane whose branch discards it is total:
  it returns a double, `NaN` or infinite included, and throws, traps and writes nothing. Java's
  floating-point operations are (`+ - * /`, `sqrt`, compares, conversions of doubles and bit
  operations: JLS 4.2.4 and 15.17.2 give infinity or `NaN` for a division by zero, with no
  exception). Not total: an integer vector's division or remainder (a zero lane throws), and an
  array load or store indexed by a discarded lane's value. A port may use neither on a lane it
  discards.
- **H4, select.** A blend returns one operand's bits in each lane. It is a select and not
  arithmetic: `a*m + b*(1-m)` is not one, since `0*infinity` and `0*NaN` are `NaN`.

**Statement.** Under H1 to H4 each lane's result equals, bit for bit, the C's result on that
lane's input, each operation being the correct rounding of (R4), except in a lane where a kept
operation returns a `NaN`, whether it had a `NaN` operand or makes one (`inf - inf`, `inf/inf`,
`0*inf`, `sqrt` of a negative): the lemma claims that such a lane is a `NaN`, and not its payload
or sign, which IEEE 754 and the JLS leave open. (The model's tests below compare those lanes by
raw bits too, as every test does, and pin what the hosts that run them return.)

**Proof.** Fix a lane. By H1 the C takes exactly one branch `B` there, the port's masks name the
same one. Take `B`'s operations in the C's order. The first one's operands are, by H2, the C's
(inputs, constants, or an operand blend, which by H4 returns the bits of the operand `B` has and
by H1 is the one picked). If an operation has the C's operand bits in the C's order, its result
is the C's, since by (R4) it is one rounding of the exact result, a function of the operands
alone; so the second operation has the C's operands, and so on to the last, whose result is the
lane's. The other branches' operations are computed in the lane too: by H3 they have no effect
but their own value, and by H4 no blend of a kept value reads them. A value they take, `NaN` or
infinite, never is an operand of a kept operation. So the lane holds `B`'s result. Where a
kept operation returns a `NaN` the payload is not fixed by IEEE 754 or the JLS, which is the
exception in the statement (sinh and cosh finish their `NaN` lanes per lane, so none of their
kept results is made by a blend). QED.

**Not covered.** (1) That a port's compare is the C's, which is H1 and stays with each function's
test (`SinhTest`, `CoshTest`, at the thresholds and their neighbours). (2) A moved compare in a
range where the branches agree in bits, which no test sees and no result shows; cosh's section
says so. (3) The masked forms' `x.blend(result, live)`, a select under the caller's mask and not
the C's condition. (4) That C2 lowers a blend without boxing, read from `bench/alloc-check.py`.
(5) That a port's code has the shape of the model: the tests run models and not `Sinh` or `Cosh`,
so a port that left H2 or H4 behind would still pass them, and only its vector tie would fail.

**Hinge tests** (`ProofsTest`, at 128, 256, 512 bits and the preferred species). They run a model
of the shape, not the ports' code: sinh's two forms with one shared division
(`l13SinhShapedBlendIsTheCsBranch`) and a branch that is `NaN` where the other is not, `sqrt(t)`
against `t+1` (`l13DiscardedNaNDoesNotLeak`), against their scalar form written as the C writes
it, on `x` at the words next to `1.0`'s and `t` including `-1`, `1e200`, the infinities and
`NaN`. `l13InputsAreNotVacuous` counts the lanes where the branches differ in bits at the
compare, where a kept value is finite and the discarded one infinite, and where a discarded one
is `NaN`. `l13FalseHingesAreCaught` requires the check to fail when the compare's word is moved by
one either way (H1) and when the select is arithmetic (H4). Two more controls
show H2's failing in the model (the shared division by `t` and not `t+1`; `2t - q` as `t + (t -
q)`, a reassociation) and one that H3 can fail in the Vector API (an integer vector division by a
zero lane throws), though no test of L13 runs a port: a port's wrong operand or reassociated sum
is caught only by its own vector tie, and an H3 failure by the port not running at all.
Each port's own instance of H1 is that tie as before.

**Encoding.** No file: an induction on the operations of the branch, informal, not
machine-checked.

## sinh (`Sinh.java`, from `e_sinh.c`)

e_sinh.c returns `x + x` for a non-finite `x` (line 56), `x` itself below `2^-28` (62-63), and for
`|x| < 22` with `t = expm1(|x|)`: `h*(2.0*t - t*t/(t+one))` below `|x| = 1` (65) and
`h*(t + t/(t+one))` from it on (66), `h` being `0.5` with `x`'s sign (58-59). From `22` up it
needs `exp` (70-81). The port computes the lines below 22 in the C's order, with no fused
multiply-add and no reassociation, and **needs no lemma of its own** beyond L13's blend
argument. Where its text is not the C's:

- `h` is `0.5` with `x`'s sign bit, the bits of `0.5` or'ed with `x`'s sign bit, as exp's `halF`
  is: that is exp's L5, for the same two doubles.
- The two forms share one division, which is a transcription and no departure. Each lane takes
  exactly one of the C's two branches, the one `ix < 0x3ff00000` picks (line 65, `h*(2.0*t -
  t*t/(t+one))`) or the other (line 66, `h*(t + t/(t+one))`), and the port computes in that lane
  the operations of that branch and no others that reach the result, in the C's order: the
  numerator `t*t` or `t` is picked by the same test, the division is by `t+one` in both, and the
  C reads `t*t/(t+one)` as `(t*t)/(t+one)`. The sum after it is `2.0*t - q` or `t + q`, picked
  by the same test, and the last operation is the product with `h`. Each operation is one IEEE
  operation on the same operands as the C's, so its result is the C's (R4); the values the other
  branch's operations take in a lane are computed and never kept. This is L13, instantiated: H1
  is the C's compare on the same high word (`belowOne`, and `tiny` for the return of `x`), H2 is
  the order just given, H3 holds since every operation on a lane is total (IEEE operations on
  doubles, compares, and int and long bit operations and reinterpretations: no division of
  integers, no indexed load), and H4 since each blend is a `blend`. The port has no hinge
  beyond L13's, and the one that could be false is H1, the test, which is the C's, made on the
  same high word.
- The lanes of `|x| >= 22`, the infinities and the NaNs, which the C answers with `exp`, are
  finished per lane by `StrictMath.sinh`, which is the C: no computation of the port is in them.

**What it rests on.** The `expm1` of line 64 is `Expm1.expm1Core(|x|)`: expm1's own body, the same
lines, with the rare paths and the rare-lane finish left out. So the lemmas of expm1 hold for
sinh's lanes as they stand:

- L10, expm1's reduction as exp's: the far form on every lane, with the near band's `k`, `hi` and
  `lo` exact (exp's L2), the unreduced lanes forced to `k = 0` (exp's L1) and `(xsb==0)?0.5:-0.5`
  as `0.5` with the sign bit (exp's L5). Its domain holds for every `|x|` of `[2^-28, 22)`: `k`
  stays between 0 and 32, far from the lanes L10 sets aside.
- L11, the `k == 0` return as `x - e`, for the lanes of `|x| <= 0.5*ln2`.

What `expm1Core` leaves unspecified, the lanes of `Expm1.expm1Rare`, sinh never reads: the
non-finite lanes and `|x| >= 22` are finished per lane, `|x| < 2^-54` is below `2^-28`, whose
result is `x`, and `x < -56*ln2` and `x > o_threshold` do not occur for `|x|` (the argument is
non-negative) below 22.

**Hinge tests.** Those of L10 and L11 (`ProofsTest.l10*`, `l11*`), which run on `Expm1`'s scalar
words. The vector tie is `SinhTest`: `VFdlibm.sinh` against `StrictMath.sinh` at every width and
`SPECIES_MAX`, unmasked and masked, on the thresholds of line 61, 62 and 65 and their
neighbours, sampled bit patterns, dense draws of `(-30, 30)` and of `[20, 24]`, the magnitudes
from `2^-60` to `2^10`, the published hard cases and their negations, and every subset of a
vector's lanes rare (`everySubsetOfLanesRare`); `scalarTranscriptionIsStrictMath` checks that
`cSinh`, e_sinh.c in scalar Java, is `StrictMath.sinh`; and
`Expm1Test.coreAndRareMaskAreTheFinishArm` checks the mask and the core.

**Encoding.** No file of its own. `proofs/expm1_k_zero_return.smt2` (L11) and the files of L10 and
exp's lemmas check their steps for sinh as they stand, `expm1Core` being the same body. Informal
here: that the port's blends take each form on exactly the C's lanes, and that it computes these
steps, the code's text.

## cosh (`Cosh.java`, from `e_cosh.c`)

e_cosh.c returns `x*x` for a non-finite `x` (line 59); for `|x| < 0.5*ln2` (`ix < 0x3fd62e43`,
line 62) with `t = expm1(|x|)` and `w = 1+t`, `w` below `2^-55` (65) and `1 + t*t/(w+w)` above
it (66); up to `22` `half*t + half/t` with `t = exp(|x|)` (70-72); up to `o_threshold`'s word
`0x40862E42` `half*exp(|x|)` (76); and then `exp(half*|x|)` squared with `half` (80-88). The port
computes the lines below `o_threshold`'s word in the C's order, with no fused multiply-add and no
reassociation, and **needs no lemma of its own** beyond L13's blend argument. Where its text is
not the C's:

- The `expm1(|x|)` of line 63 is s_expm1.c's own `k == 0` path written out (lines 177-189): for
  `|x| <= 0.5*ln2` the C reduces nothing, so its `expm1` there is the tiny return (`|x| < 2^-54`,
  lines 177-179) or the polynomial's `x - (x*e-hxs)` (184-189), and the port transcribes them
  with `Expm1`'s constants, each operation as the C has it. It is not `Expm1`'s body, which
  rewrites the `k == 0` return as `x - e` (L11): neither L10 nor L11 is used. The finish arm
  computes none of it: it finishes these lanes with `StrictMath.cosh`.
- The lanes share one division, which is a transcription and no departure, as in sinh's section:
  each lane takes exactly one of the C's branches, picked by the same compare of `ix`, and
  computes that branch's operations in the C's order. The numerator and denominator are `t*t`
  and `w+w` in the small range and `half` and `exp(|x|)` above it, the quotient is `(t*t)/(w+w)`
  or `half/t`, and the sum after it, `1+q`, `w`, `half*t + q` or `half*t`, is picked by the same
  test. Each operation is one IEEE operation on the same operands as the C's (R4); the values
  the other branches' operations take in a lane are computed and never kept. This is L13,
  instantiated as in sinh's section (H3 and H4 as there), with no hinge beyond L13's: the one
  that could be false is H1, the tests, which are the C's, made on the same high word. What
  checks them is narrower than the tests are: the
  four words equal the C's literals (`constantsAreTheSourcesBits`), and `CoshTest` compares
  every arm with `StrictMath.cosh` at the thresholds and their neighbours. `cosh.splitMoved`
  is the only planted fault that moves a compare's constant (the slice arm's `HX_SMALL`);
  `cosh.finishMissesSmall` drops a mask. Sizing `cosh.dropHalfOverT` observed
  that dropping `half/t` changes no result above about 18.7; this is an observation from that
  sizing, not a result proven here, and nothing relies on it. Other compares may also move
  without changing a result, and no test claims to catch those.
- The lanes from `0x40862E42` up, the infinities and the NaNs are finished per lane by
  `StrictMath.cosh`, which is the C.

**What it rests on.** The `exp` of lines 71 and 76 is `Exp.expCore(|x|)`: exp's own body, the
same lines, with the rare paths and the rare-lane finish left out, which is `StrictMath.exp` in
every lane outside `Exp.expRare`. So exp's lemmas hold for cosh's lanes as they stand: L2, the
near band's form as the far form, and L5, `halF[xsb]` from the sign bit, whose argument here is
non-negative. L1, the `k == 0` path, is not used: every lane whose `expCore` value is kept has
`|x| >= 0.5*ln2` and is reduced, and the unreduced lanes of the small range get values nobody
keeps. A lane of `0.5*ln2 <= |x| < 709.78` is none of `expRare`'s: its `k` is not below `-1021`
and its high word is below the filter's.

**Hinge tests.** Those of L2 and L5 (`ProofsTest`), which run on `Exp`'s scalar words. The
vector tie is `CoshTest`: `VFdlibm.cosh` and both arms against `StrictMath.cosh` at every width
and `SPECIES_MAX`, unmasked and masked, on the thresholds of lines 62, 65, 70 and 76 and their
neighbours, sampled bit patterns, dense draws of `(-30, 30)`, of `(-0.5, 0.5)` and of `[20, 24]`,
the magnitudes from `2^-60` to `2^10`, the published hard cases and their negations, and every
subset of a vector's lanes rare; `scalarTranscriptionIsStrictMath` checks that `cCosh`, e_cosh.c
with s_expm1.c's lines in scalar Java, is `StrictMath.cosh`.

**Encoding.** No file of its own. The files of exp's lemmas check their steps for cosh as they
stand, `expCore` being the same body. Informal here: that the port's blends take each branch on
exactly the C's lanes, and that it computes these steps, the code's text.

## asin (`Asin.java`, from `e_asin.c`)

e_asin.c reads `hx = __HI(x)` and `ix = hx&0x7fffffff` (lines 78-79). For `ix >= 0x3ff00000` it
returns `x*pio2_hi + x*pio2_lo` for exactly `+-1` and `(x-x)/(x-x)` above (80-84). Below that, for
`|x| < 0.5` (`ix < 0x3fe00000`, line 85) it returns `x` below `2^-27` (86-87) and otherwise `x+x*w`
with `t = x*x`, `w = p/q` (89-93); from `0.5` up it takes `t = (one-fabs(x))*0.5`, the same `p`
and `q` in `t`, and `s = sqrt(t)` (96-100), then above `0.975` (`ix >= 0x3FEF3333`, line 101)
`pio2_hi-(2.0*(s+s*w)-pio2_lo)` with `w = p/q` (102-103), and below it `pio4_hi-(p-q)` with the
split root (105-111); the sign is applied last (113). The port computes the lines below `|x| = 1`
in the C's order, with no fused multiply-add and no reassociation, and **needs no lemma of its
own** beyond L13's blend argument and (R4)'s `sqrt`. Where its text is not the C's:

- The braces of lines 86-89 close the tiny test before its `else`, so that `else` belongs to
  `if(ix<0x3e400000)` and governs the one statement `t = x*x;` (line 89); `p`, `q`, `w` and the
  return (90-93) are not in it, though the indentation says so, and run for every lane that
  reaches them. The test inside the braces, `huge+x>one`, is true for every `|x| < 2^-27`
  (`1e300 + x` is above 1 for zeros and subnormals too), so those lanes return `x`; the other
  lanes of `|x| < 0.5` take `t = x*x` and then the same `p`, `q` and `w`. The port returns `x`
  in the tiny lanes by the same compare, and `AsinTest.cAsin` transcribes the C's structure.
- `w = s; __LO(w) = 0` (lines 105-106) is the AND of the lane's 64 bits with
  `0xFFFFFFFF00000000`, which is what clearing the low word of an IEEE double is: the port does
  the C's own operation on the bits, an exact one, and no arithmetic replaces it. So there is
  nothing to prove about it: the claim is that the port clears the word the C clears.
  `asin.skipLowClear` is the fault that omits it.
- The three ranges below 1 share one pass: `t` is `x*x` or `(one-fabs(x))*0.5` by the C's
  compare, and the polynomials `p`, `q` and the division `p/q` are then the same operations in
  all three (lines 90-92, 98-99 and 102 or 108), on `t`. The split range's second division,
  `(t-w*w)/(s+w)`, is computed on every lane and kept by the lanes of `0.5 <= |x| <= 0.975`.
- The sign is `hx>0 ? t : -t`: the port blends `t` and its negation, which is exact, under the
  mask `hx > 0` on the same word.
- The lanes of `|x| >= 1`, the infinities and the NaNs, which the C answers with its first branch,
  are finished per lane by `StrictMath.asin`, which is the C: no computation of the port is in
  them.

**L13 for asin.** H1 (selection) holds: every mask is the C's compare on the same `ix` or `hx`
(`ix < 0x3ff00000`, `< 0x3fe00000`, `< 0x3e400000`, `>= 0x3FEF3333`, `hx > 0`). H2 (operands)
holds for the blend of `t`, the one operand two branches give the polynomials, and for the sums,
which are the C's in its order. H3 (totality) holds: every operation is an IEEE operation on
doubles, a compare, a bit operation or a reinterpretation, with no integer division and no
indexed load, so a discarded lane's `sqrt` of a negative `t` or a `NaN` is a value and nothing
else. H4 (select) holds: each blend is a `blend`. Not covered: that H1's words are the C's is
`AsinTest`'s (`constantsAreTheSourcesBits`, and the thresholds and their neighbours), which
catches `asin.splitMoved` (moved to `0x3FFF3333`, above every lane of the range). No committed
fault covers a word moved by one. Once, by hand, with `0x3FEF3334` in the compare in place of
`0x3FEF3333`, `AsinTest.thresholdsAndSpecials` failed, at `0.975`'s own word, whose lanes are
the only ones that move; the port's code having
L13's shape is `AsinTest`'s tie and not L13's tests, which run a model; and (R4)'s `sqrt` is the
operator's, tested by `SqrtTest`, with the C's own `sqrt` not shown there.

**Hinge tests.** The vector tie is `AsinTest`: `VFdlibm.asin` against `StrictMath.asin` at every
width and `SPECIES_MAX`, unmasked and masked, on the thresholds of lines 80, 85, 86 and 101 and
their neighbours, sampled bit patterns, dense draws of `(-1, 1)`, of `(-0.6, -0.4)`, `(0.4, 0.6)`
and `(0.95, 1)` and their negations, the magnitudes from `2^-60` to `1`, the published hard cases
(1655 inputs, one of them 1) and their negations, and every subset of a vector's lanes rare
(`everySubsetOfLanesRare`); `scalarTranscriptionIsStrictMath` checks that `cAsin`, e_asin.c in
scalar Java, is `StrictMath.asin`. L13's own tests are `ProofsTest.l13*`.

**Encoding.** No file of its own: nothing here is a fold. Informal here: that the port's blends
take each branch on exactly the C's lanes, and that it computes these steps, the code's text.

## atan (`Atan.java`, from `s_atan.c`)

s_atan.c returns, from `|x| >= 2^66`, `x + x` for a NaN and `atanhi[3] + atanlo[3]` with `x`'s
sign otherwise (lines 97-102); below `7/16` it reduces nothing, and below `2^-29` it returns `x`
(103-107). From `7/16` up it divides once, `(2.0*x-one)/(2.0+x)` below `11/16`, `(x-one)/(x+one)`
below `19/16`, `(x-1.5)/(one+1.5*x)` below `39/16` and `-1.0/x` above (109-121). One polynomial
in the reduced `x` follows (124-128), and the result is `x - x*(s1+s2)` with no reduction and
`atanhi[id] - ((x*(s1+s2) - atanlo[id]) - x)`, negated for `hx < 0`, with one (129-132). The port
computes the lines below `2^66` in the C's order, with no fused multiply-add and no
reassociation, and **needs no lemma of its own** beyond L13's blend argument. Where its text is
not the C's:

- The four reductions share one division, which is a transcription and no departure, as in
  sinh's and cosh's sections. Each lane takes exactly one of the C's
  branches, picked by the compares of `ix` of lines 103, 110, 111 and 116, and the port computes
  in that lane the operations of that branch in the C's order: the numerator and the
  denominator are that range's own operands (`2.0*x-one` over `2.0+x`, `x-one` over `x+one`,
  `x-1.5` over `one+1.5*x`, `-1.0` over `x`), picked by the same compares, so the division is
  the C's division of the C's operands (R4). A lane below `7/16` takes no branch with a
  division: its quotient is computed and not kept, and its reduced value is `x` itself. The
  values the other branches' operations take in a lane are computed and never kept. This is
  L13, instantiated: H1 is the C's compares on the same high word, nested as the C's `if`
  chain is (`noReduction`, then `id0`, `idUpTo1` and `idUpTo2`, which blend the operands from
  the last range down), H2 is the order just given, with the operand blend before the one
  division, H3 holds since every operation on a lane is total (IEEE operations on doubles,
  compares, and int and long bit operations and reinterpretations: no division of integers, no
  indexed load), and H4 since each blend is a `blend`. The port has no hinge beyond L13's, and
  the one that could be false is H1, the tests, which are the C's, made on the same high word.
- The polynomial, `atanhi[id]` and `atanlo[id]` are selected by the same compares, and the one
  polynomial is evaluated on the lane's reduced value, which is `x` below `7/16` and the quotient
  above, as in the C. The sign of line 132 is a negation, exact, picked by `hx < 0`; the result
  below `7/16` has no sign step in the C and none here.
- The lanes from `0x44100000` up, the infinities and the NaNs are finished per lane by
  `StrictMath.atan`, which is the C.

**What it rests on.** (R4) for `+ - * /`, and nothing of exp's or expm1's: the port calls no
other port body. L13's hinge tests model a two-way blend; atan's four-range nesting is covered
by `AtanTest` only.

**Checks.** `constantsAreTheSourcesBits` checks that the six words, `one`, `atanhi`, `atanlo` and
`aT` are the C's. `AtanTest` compares `VFdlibm.atan` with `StrictMath.atan` at every width and
`SPECIES_MAX`, unmasked and masked, at those words (and `0x7ff00000`) and their neighbours, on
sampled bit patterns, dense draws, the published hard cases and their negations, and with every
subset of a vector's lanes rare; `scalarTranscriptionIsStrictMath` checks that `cAtan`, s_atan.c
in scalar Java, is `StrictMath.atan`. The planted faults are `atan.dropLo` (`atanlo` dropped),
`atan.splitMoved` (the `19/16` word moved to 2, which moves a compare) and `atan.hiOneUlpOff`
(`atanhi[1]` one ulp up).

What is checked of each compare, and what is not. `wordsOfEachCompare` draws, at every width,
over the word of each compare and the word below it. For the four compares at `0x3fdc0000`,
`0x3fe60000`, `0x3ff30000` and `0x40038000`, a move by one word, either way, changes many
results of that word's lanes: a throwaway program (not committed) counted 295796 and 295321,
546013 and 546557, 237329 and 237381, and 382326 and 381390 changed of 2^22 draws, for the
move down and up of each, and `gapDrawsSeeAMovedCompareWhereOneCanBeSeen` shows on the scalar
transcription, with the word moved, that those draws differ from `StrictMath.atan`. No fault
planted in the port moves those compares by one word, though; `atan.splitMoved` moves the one
at `0x3ff30000` by more. For the compares at `0x3e200000` and `0x44100000` it is the other
way: moved by one word, the lanes of the word between give the same bits (0 of 2^22 draws
changed, and the test pins that), so no test of results sees those two move, and they are not
checked. That a move by more than one word is seen was not measured, except for the
`0x3ff30000` one.

## acos (`Acos.java`, from `e_acos.c`)

e_acos.c reads `hx = __HI(x)` and `ix = hx&0x7fffffff` (lines 69-70). For `ix >= 0x3ff00000` it
returns `0.0` for exactly `1`, `pi+2.0*pio2_lo` for exactly `-1` and `(x-x)/(x-x)` above (71-76).
Below that, for `|x| < 0.5` (`ix < 0x3fe00000`, line 78) it returns `pio2_hi+pio2_lo` for `ix <=
0x3c600000` (79) and otherwise `pio2_hi - (x - (pio2_lo-x*r))` with `z = x*x`, `r = p/q` (80-84);
for `hx < 0` (`x <= -0.5`: the C's comment says `x < -0.5`, but `-0.5` takes this branch, line
85) with `z = (one+x)*0.5`, `s = sqrt(z)` and `r = p/q` it returns `pi - 2.0*(s+w)`, `w =
r*s-pio2_lo` (86-92); and otherwise (94-103, `x >= 0.5`: the C's comment says
`x > 0.5`, but `+0.5` takes this branch) with `z =
(one-x)*0.5`, `s = sqrt(z)`, `df` the root with its low word cleared and `c = (z-df*df)/(s+df)`
it returns `2.0*(df+w)`, `w = r*s+c`. The port computes the lines below `|x| = 1` in the C's
order, with no fused multiply-add and no reassociation, and **needs no lemma of its own** beyond
L13's blend argument and (R4)'s `sqrt`; the asin section says the same of its own blends,
and what it says of the low-word clear, of the shared polynomials and of the rare lanes holds
here for the same reasons. Where acos's text is not the C's:

- `__LO(df) = 0` (lines 96-97) is the AND of the lane's 64 bits with `0xFFFFFFFF00000000`, the
  C's own operation on the bits, exact, as in asin's `__LO(w) = 0`: nothing to prove, the claim is
  that the port clears the word the C clears. `acos.skipLowClear` is the fault that omits it.
- The three ranges below 1 share one pass: `z` is `x*x`, `(one+x)*0.5` or `(one-x)*0.5` by the
  C's compares, and `p`, `q` and `r = p/q` are then the same operations in all three (lines
  81-83, 87-88 and 90, 99-101), on `z`; `s = sqrt(z)` is `VectorOperators.SQRT`. The second
  division, `(z-df*df)/(s+df)`, is computed on every lane and kept by the lanes of `x >= 0.5`.
- `pio2_hi+pio2_lo` (line 79) is the one constant of the port's source, `PIO2_HI + PIO2_LO`: a
  Java constant expression of two doubles is evaluated as IEEE 754 addition, the one rounding the
  C's run-time sum has (R4).
- The lanes of `|x| >= 1`, the infinities and the NaNs, which the C answers with its first
  branch, are finished per lane by `StrictMath.acos`, which is the C: no computation of the port
  is in them.

**L13 for acos.** H1 (selection) holds: every mask is the C's compare on the same `ix` or `hx` (`ix
< 0x3ff00000`, `ix < 0x3fe00000`, `ix <= 0x3c600000`, `hx < 0`). The `<=` of the constant return is
the C's text. A scalar script run once, not committed, found the first form's result equal to the
constant's in 2000 random draws of each binade from `2^-80` to `2^-56`, and different from `2^-55`
up (213 of 2000 there); that is all the evidence, and no test of the port tells the compare from `<`
or from a neighbouring word. `acos.tinyMoved` moves the word to `0x3cd00000`, far enough to show. H2
(operands) holds for the blend of `z`, the one operand the three ranges give the polynomials, and
for the sums, which are the C's in its order. H3 (totality) holds: every operation is an IEEE
operation on doubles, a compare, a bit operation or a reinterpretation, with no integer division and
no indexed load, so a discarded lane's `sqrt` of a negative `z` or a `NaN` is a value and nothing
else. H4 (select) holds: each blend is a `blend`. Not covered: that H1's words are the C's is
`AcosTest`'s (`constantsAreTheSourcesBits`, and the thresholds and their neighbours), which catches
`acos.halfMoved` (moved to `0x3fe80000`) and `acos.tinyMoved` (moved to `0x3cd00000`) and has no
fault for a word moved by one (for the constant return's word, see above); the port's code having
L13's shape is `AcosTest`'s tie and not L13's tests, which run a model; and (R4)'s `sqrt` is the
operator's, tested by `SqrtTest`, with the C's own `sqrt` not shown there.

**Hinge tests.** The vector tie is `AcosTest`: `VFdlibm.acos` against `StrictMath.acos` at every
width and `SPECIES_MAX`, unmasked and masked, on the thresholds of lines 71, 78 and 79 and their
neighbours, sampled bit patterns, dense draws of `(-1, 1)`, of `(-0.6, -0.4)`, `(0.4, 0.6)`,
`(0.95, 1)` and `(-1, -0.95)`, the magnitudes from `2^-70` to `1`, the published hard cases (1569
inputs, all positive) and their negations, which reach the branch of `x < -0.5`, and every subset
of a vector's lanes rare (`everySubsetOfLanesRare`); `scalarTranscriptionIsStrictMath` checks that
`cAcos`, e_acos.c in scalar Java, is `StrictMath.acos`. L13's own tests are `ProofsTest.l13*`.

**Encoding.** No file of its own: nothing here is a fold. Informal here: that the port's blends
take each branch on exactly the C's lanes, and that it computes these steps, the code's text.

## atan2 (`Atan2.java`, from `e_atan2.c`)

**What the core's contract is, read in `Atan.java` before calling it.** `Atan.atan(x, live)` takes
a vector of any doubles and a mask or null. A null mask computes every lane; otherwise only live
lanes are computed, an inactive lane comes back as the argument's lane, untouched, and never takes
the per-lane finish (`rare` is and-ed with `live`). It does its own sign handling: it reduces
`|x|`, negates for `hx < 0`, and returns `x` below `7/16` with no reduction, so for a
non-negative argument it is `atan` of it by the C's operations. The lanes it leaves to a per-lane
`StrictMath.atan` are `|x| >= 2^66`, the infinities and the NaNs. `atan2` calls it with `t =
fabs(y/x)` and the mask `t < 2^66` (and live), so a NaN, an infinity or a huge `t` never
reaches the finish. The lanes of `t >= 2^66` that the general path keeps (`x = 1.0` beside a huge
`y`, and a subnormal `x`, whose high word has no exponent, so that `k` understates the gap: `x =
2^-1074`, `y = 2^-1000` is `t = 2^74` with `k = 23`) take `atanhi[3] + atanlo[3]`, which is what
s_atan.c returns there for a positive argument (lines 97-102), from the same two arrays `Atan`
holds. The port copies none of `Atan`'s code.

e_atan2.c returns `x + y` for a NaN in either argument (lines 71-73), `atan(y)` for `x = 1.0` (74),
`y` or `+-(pi + tiny)` for `y = +-0` by the signs (78-85), `+-(pi_o_2 + tiny)` for `x = +-0` (87),
the diagonals `+-(pi_o_4 + tiny)` and `+-(3.0*pi_o_4 + tiny)`, or `+-0` and `+-(pi + tiny)`, for `x
= +-inf` (90-106), and `+-(pi_o_2 + tiny)` for `y = +-inf` (108). The rest take `k = (iy-ix)>>20`
(111), the result `z = pi_o_2 + 0.5*pi_lo` for `k > 60`, `z = 0.0` for `hx < 0` and `k < -60`, and
`z = atan(fabs(y/x))` otherwise (112-114), and the quadrant by `m` (115-122): `z`, `z` with its sign
flipped, `pi - (z - pi_lo)`, `(z - pi_lo) - pi`. The port computes every lane's general path and
every special lane, and blends the special lanes over it from the last of the C's tests to the
first, so that the first that matches wins as in the C. It **needs one lemma of its own, L14 below,
for `x = 1.0`**, and otherwise L13's blend argument: H1 is the C's tests as exact classifications of
the doubles (a compare with 0.0 for a zero, `IS_INFINITE`, `IS_NAN`, a compare with 1.0, and the C's
own compares of `k` and of the sign words), in the C's order of priority, which is the order of the
blends; H2 is the order of the operations in each lane (one division `y/x`, then atan's, then the
quadrant's); H3 holds since every operation on a lane is total (IEEE operations, compares, int
shifts, blends: no integer division, no indexed load); and H4 since each blend is a `blend`. The
values that a lane overwritten by a special blend takes in the general path (`y/0` is an infinity,
`0/0` a NaN) are never read. Where its text is not the C's:

- `x = 1.0` is not a lane of its own, which L14 proves equal to the C's `atan(y)`. What the C's
  early return also does is to skip the `k > 60` shortcut, which the port does too
  (`kHigh.and(xOne.not())`).
- The quadrants use `z.neg()` for `__HI(z) ^= 0x80000000`: an IEEE negate is the sign-bit flip,
  `-0.0` included. `z - pi_lo` is computed once for the two quadrants that compute it, the same
  operation on the same operands.
- `pi + tiny`, `pi_o_2 + tiny`, `3.0*pi_o_4 + tiny` and their negatives are `static final`
  expressions in Java of the same operations, so none is assumed to equal another constant;
  `constantsAreTheSourcesBits` checks the words.
- `k` is computed on the high words as `int` lanes, as the C does.
- A lane of two NaNs is `x + y` in the vector, which is one of the arguments quieted, a candidate
  of `OPERANDS` (the contract of a function of two arguments, above). One NaN beside a finite
  value is `x + y` too: the NaN quieted with its sign, which `StrictMath.atan2` gives in every
  mode of the probe (issue 33).

### L14. x = 1.0 through the general path is atan(y)

**Statement.** For `x = 1.0` and a `y` that is not a NaN, the port's lane is the C's `atan(y)`, bit
for bit, with `-0.0`, the infinities and the lanes of `|y| >= 2^66` included.

**Hypotheses.** (a) `y/1.0` is `y` (division by one is exact: IEEE division is correctly rounded
and the quotient is a double); `fabs` clears the sign. (b) s_atan.c is odd: `atan(-y) = -atan(y)`
bit for bit. (c) The port's `z` for `|y|` is s_atan.c's `atan(|y|)` by the C's operations (atan's
section), or `atanhi[3] + atanlo[3]` from `2^66` up, which is the C's line 100 for a positive
argument. (d) `y = +-inf` is a special lane, `+-(pi_o_2 + tiny)`, and `pi_o_2 + tiny` is the
bits of `atanhi[3] + atanlo[3]`.

**Proof.** By (a) the port's `t` is `|y|`, and the `k > 60` shortcut is off for `x = 1.0`, so
by (c) `z = atan(|y|)` (for `y` finite and not zero; for `y = +-0` the lane is the special lane,
which returns `y`, and `atan(+-0)` is `+-0` since s_atan.c returns `x` below `2^-29`). `m` is 0
for `y > 0` and 1 for `y < 0` (`x = 1.0` is positive), so the port returns `z` or `-z`, the sign
flip being exact. (b) holds on each branch of s_atan.c: below `7/16` the C computes `x -
x*(s1+s2)` on the signed `x`, where `s1` and `s2` are polynomials in `z = x*x` and `w = z*z` (even
in `x`) and `x*(s1+s2)` is odd, so the result is odd by (R1) (the rounding of `fl(-v) = -fl(v)`
at each operation); below `2^-29` it returns `x`; from `7/16` up it computes on `fabs(x)` and
negates for `hx < 0`; from `2^66` it returns `+-(atanhi[3] + atanlo[3])`. So `atan(y) = -atan(|y|)`
for `y < 0`, which is what the port returns. For `y = +-inf` the special lane gives `+-(pi_o_2 +
tiny)`, which by (d) is the C's `+-(atanhi[3] + atanlo[3])`. QED.

**What checks it.** (a) and (c) are the port's own tests: `Atan2Test.xOneIsAtanOfY` checks at every
width that `atan2(y, 1.0)` has the bits of both `StrictMath.atan(y)` and `VFdlibm.atan(y)` on
atan's thresholds, sampled values, a spread of magnitudes and dense draws, the infinities and both
zeros included (a NaN is the NaN lane); `constantsAreTheSourcesBits` checks the words of `pi_o_2`,
`pi_o_2 + tiny` and `atanhi[3] + atanlo[3]`, which (d) is about. A fault in (b) or (c) would
show there and in `xIsOne`, which draws `x = +-1.0` and its neighbours beside `|y|` from `2^50`
to `2^70`.

**The two shortcuts of `k` are redundant in bits, which tests cannot see move by one.** Measured on
200000 draws of `t` in each binade from `2^54` up, not proved, and by a program that is not
committed, `StrictMath.atan(t)` is the bits of `pi_o_2 + 0.5*pi_lo` in every one, and for `t` below
`2^-55` the quadrant results with `z = atan(t)` and with `z = 0.0` are the same bits. They differ in
58213 of 200000 draws at `2^52` and in all at `2^50`, and the quadrant sums differ from `t` near
`2^-52` up. So `k > 60` and `hx < 0 && k < -60`, moved by one, change no result (0 of 2^19 draws in
`theShortcutsAreSeenMovedByTenAndNotByOne`), and neither does the `x = 1.0` exception. The port
follows the C anyway, and no fault is planted at them by one. The two faults `atan2.kHighMoved` and
`atan2.kLowMoved` move the thresholds by ten, to `k > 50` and `k < -50`, where `atan(t)` is not yet
`pi_o_2` (`t` near `2^50`) or the quadrant sum is not yet the same (`t` near `2^-50`), and a test of
results sees them. That is a statement about the C's values, checked by draws and not proved; the
port's correctness does not rest on it.

**What it rests on.** (R4) for `+ - * /` and the negate, (R1) for L14, L13, atan's own section for
the core, and the contract of a function of two arguments for the lane of two NaNs.

**Checks.** `constantsAreTheSourcesBits`, and `Atan2Test`: `VFdlibm.atan2`, `Atan2.atan2Blended`
and `Atan2.atan2Guarded` against `StrictMath.atan2` by `PairBits.matches` at every width and
`SPECIES_MAX`, unmasked and masked, on every pair of the special values (the infinities, the zeros,
the NaNs of both signs and several payloads, the subnormals, `+-1` and the neighbours of `2^60`
and `2^66`); pairs whose exponents differ by -70 to 70, so that `k` crosses both thresholds; pairs
drawn at the differences of high words the fuzz lists for atan2; `x = +-1.0` beside `|y|` from
`2^50` to `2^70`; a subnormal `x` beside a `y` of moderate exponent; sampled bit patterns;
dense draws; the 20,820 pairs of `hard-cases/atan2.txt`; and every subset of a vector's lanes
special beside lanes the core computes.
`scalarTranscriptionIsStrictMath` checks that `cAtan2`, e_atan2.c in scalar Java, is
`StrictMath.atan2` on those inputs. The planted faults are `atan2.kHighMoved`,
`atan2.kLowMoved`, `atan2.lowShortcutForPositiveX` (the shortcut without `hx < 0`),
`atan2.infOrderSwapped` (`y` infinite wins over `x` infinite), `atan2.dropPiLo`, `atan2.zeroSignOfX`
(`x < 0` by value for `y = +-0`, so `-0.0` is positive), `atan2.diagonalInf` (`pi_o_4` in every
quadrant) and `atan2.noSignFlip`.

## hypot (`Hypot.java`, from `e_hypot.c`)

e_hypot.c reads `ha` and `hb`, the high words of `|x|` and `|y|` (lines 58-59), swaps the
arguments where `hb > ha`, by the words only, so that with equal words `b` may be the larger
(60), and writes the words back, clearing the signs (61-62). It returns `a+b` where `ha-hb >
0x3c00000` (63). Above `2^500` (`ha > 0x5f300000`) it returns the infinity or NaN rule of lines
66-70 for `ha >= 0x7ff00000` and otherwise takes `0x25800000` from both words, `k = 600` (73-75).
Then, on `hb` as it now is, for `hb < 0x20b00000` (77): a subnormal or zero `b` (`hb <=
0x000fffff`) returns `a` for `b = 0` (79) and otherwise multiplies `a` and `b` by `2^1022`, `k
-= 1022`, leaving `ha` and `hb` as they were (80-84); any other `b` adds `0x25800000` to both
words, `k -= 600` (86-90). With `w = a-b`, `w > b` takes `sqrt(t1*t1-(b*(-b)-t2*(a+t1)))` over
`t1`, `a`'s high word with a zero low word (96-99), and the rest `sqrt(t1*y1-(w*(-w)-(t1*y2+t2*b)))`
over `a = a+a`, `b`'s and `a`'s words split the same way (101-108). For `k != 0` the result is
`t1*w` with `t1 = 1.0` and `k<<20` added to its high word (110-113).

The port computes every finite lane in the vector by the C's operations in the C's order, with
no fused multiply-add and no reassociation, and finishes the lanes of `ha >= 0x7ff00000` (an
infinity or a NaN in either argument) per lane with `StrictMath.hypot`, which is the C. It
**needs no lemma of its own** beyond L13's blend argument and (R4)'s `sqrt`; L16 below is a
counter-example, not a fold: it shows that the stale words the C reads are not optional. Where
its text is not the C's:

- The swap is two blends of the arguments and two of the words, under the C's own compare `hb >
  ha` of the int words, and `__HI(a) = ha` is the AND of `a`'s bits with `0x7fffffffffffffff`:
  `ha` is `a`'s own high word with the sign bit cleared, so writing it back clears the sign bit
  and changes nothing else, which is what the AND does.
- Every other `__HI(v) = word` is the C's own operation on the bits: the int word widened by
  `I2L` and shifted left by 32 (the shift drops the sign extension), or-ed with `v`'s low word;
  `t = 0; __HI(t) = word` is the shifted word alone. Line 112's `__HI(t1) += (k<<20)` on `t1 =
  1.0`, whose low word is zero, is the word `(k<<20) + 0x3ff00000` as an int lane, then shifted
  the same way.
- The words and the values are separate vectors, as in the C: the scalings by `2^-600` and
  `2^600` change the words and write them back into the values, and the scaling by `2^1022`
  multiplies the values and leaves the words, so lines 97, 103 and 106 read the stale words on
  those lanes as the C does (L16).
- Line 77's test is on `hb` after line 73's subtraction, line 78's is nested in it, and `k` is an
  int lane added to as the C adds to it, so a lane that took both scalings would have both; the
  port does not assume that none does.
- Line 79's `(hb|__LO(b))==0` is the test that `b`'s 64 bits are zero, which is the same predicate
  (`hb` being `b`'s high word there: the subnormal branch has not changed it), and-ed with the
  subnormal branch's mask.
- The two forms of the medium path are computed on every lane, their radicands blended by the
  C's `w > b` and the one `VectorOperators.SQRT` taken of the blend: an operand blend (L13, H2),
  so each lane takes the root of its own form's radicand, by (R4) the C's `sqrt`.
- The returns before the medium path are blends over it from the last to the first: line 79's
  `a` (the value at that line), then line 63's `a+b` of the unscaled `a` and `b`; the rare lanes
  are written last, by the finish.

**L13 for hypot.** H1 (selection) holds: every mask is the C's compare on the same int words (`hb
> ha`, `ha-hb > 0x3c00000`, `ha > 0x5f300000`, `hb < 0x20b00000`, `hb <= 0x000fffff`, `k != 0`)
or on the same doubles (`w > b`, `b`'s bits zero), nested as the C nests them. H2 (operands)
holds for the operand blends (the swap, the scaled values and words, the radicand), and every
operation is the C's in its order. H3 (totality) holds: IEEE operations, compares, int lane
arithmetic and shifts, conversions and blends, with no integer division and no indexed load, so
the values a discarded lane takes (an infinite `t1*t1` beside a huge `a`, a NaN radicand) are
values and nothing else. H4 (select) holds: each blend is a `blend`. The lanes the rare mask
holds are never read: each is overwritten by `StrictMath.hypot` on the lane's own arguments.

**The lane of two NaNs** is finished per lane, so it is `StrictMath.hypot`'s own result, a
candidate of `MAGNITUDES` (the contract of a function of two arguments, above). One NaN beside a
finite value or an infinity is finished too, so its bits are the oracle's by construction.

### L16. The stale words of the 2^1022 scaling cannot be refreshed (a counter-example, not a fold)

**Statement.** After lines 80-84 multiply `a` and `b` by `2^1022`, the C keeps `ha` and `hb`, the
words of the unscaled values, and lines 97, 103 and 106 split the scaled values at those stale
words. Reading the words again from the scaled values, as a port that kept words and values
together would, is not bit-identical.

**Counter-example.** Four of the pairs found, `C` the C as written, which is `StrictMath.hypot` at
each, and `refreshed` the same C with `ha = __HI(a)`, `hb = __HI(b)` after line 84; the first two
take the form `w <= b`, the last two `w > b`:

| `x` | `y` | `C` | `refreshed` |
| :--- | :--- | :--- | :--- |
| `0x000db0d1_96a90b9a` | `0x800d2733_8d43c3cf` | `0x0012fc39_ebdadea2` | `0x0012fc39_ebdadea3` |
| `0x000e6d42_94dc0a1e` | `0x000b85f9_2b97b424` | `0x001276c9_661f63f5` | `0x001276c9_661f63f6` |
| `0x80715ef1_f174dc02` | `0x000c253f_97a162e2` | `0x00715f35_e084bb17` | `0x00715f35_e084bb16` |
| `0x80000000_091e440e` | `0x0009982a_438c0f4b` | `0x0009982a_438c0f50` | `0x0009982a_438c0f4f` |

On those lanes the stale `t1` is the leading part of the unscaled `a`, about `2^-1022` times the
scaled `a`, so `t2 = a-t1` is nearly all of `a`, and the sums of lines 99 and 108 round at other
places than they would with the scaled words. How far either result is from the exact value is
not measured here; that they are different doubles is the claim. Of 1,048,576 pairs of
`HypotTest.subnormalPairs` (seed 42), 914,446 took the `2^1022` path and 42,876 differ (33,515 of the form `w > b`, 9,361 of `w <= b`), counted by a scratch
program over `HypotTest.cHypot`, not committed. So the port keeps the words apart from the values
and reads them stale, as the C does.

**Test (scalar).** `HypotTest.staleWordWitnesses` asserts the four rows through `cHypot`, e_hypot.c
in scalar Java checked against `StrictMath.hypot` (`scalarTranscriptionIsStrictMath`, in every JIT
mode), and that each takes the `2^1022` path; it fails if the refreshed words gave the C's bits
there. `theInputsSeeAChangedTranscription` requires the refreshed variant to differ on the gate's
inputs. **Vector tie.** `HypotTest.staleWordWitnessesInEveryLane` puts each witness at every lane
of a vector beside lanes of the other ways out of the C, at every width the host has, unmasked and
masked; `theStaleWordsAreRead` requires the port's lanes to equal `cHypot` as written on 65,536
subnormal-`b` pairs per width, and the refreshed variant to differ from `StrictMath` on some of
them. **Planted fault.** `hypot.refreshedWords` (`Hypot.REFRESHED_WORDS`) reads the words again
from the scaled values after line 84; `HypotTest.faultCatchers` requires the port to fail on the
fault run's inputs with it planted, at every width.

**Encoding.** None: the results are Java's evaluation of the transcription, and the count is a
program's, so nothing here is machine-checked in `proofs/`.

**What it rests on.** (R4) for `+ - * /` and `sqrt` (the operator's, tested by `SqrtTest`), L13,
and the contract of a function of two arguments for the lane of two NaNs, which the finish takes.

**Checks.** `HypotTest.constantsAreTheSourcesBits` (the words and `2^1022`), and `HypotTest`:
`VFdlibm.hypot` against `StrictMath.hypot` by `PairBits.matches` at every width and
`SPECIES_MAX`, unmasked and masked, on every pair of the special values (the words the C tests
with low words 0, 1 and all ones, both signs, the zeros, the infinities, NaNs of both signs and
several payloads); Pythagorean triples across the scalings; the fuzz's draws at the C's words and
at the gaps of line 63; pairs at each scaling's word with the other within line 63's gap; a
subnormal `b` beside `a` from subnormal up past the ratio; equal high words with either low word
larger; `a` near `2b`, either side of `w > b`; NaNs beside NaNs, infinities and finite values;
sampled bit patterns, Varka's baseline range and every exponent; the 8,400 pairs of
`hard-cases/hypot.txt`; and every subset of a vector's lanes rare beside lanes of every way out
of the C. The planted faults are `hypot.dropEarlyReturn` (line 63's return dropped),
`hypot.refreshedWords` (L16), `hypot.dropT2` (line 99's `t2*(a+t1)` dropped) and
`hypot.dropY2` (line 108's `t1*y2` dropped).

## pow (`Pow.java`, from `e_pow.c`)

e_pow.c returns early for a zero `y` (line 119), a NaN (122-124), the special `y` of lines
147-164 (the infinities, +-1, 2, and 0.5 for `x >= +0`), the special `x` of lines 168-180 (the
zeros, the infinities and +-1, by their words with a zero low word), a negative `x` with a
non-integer `y` (185) and `|y| > 2^31` (191-207, which either returns or takes a short log for `x`
beside 1). Otherwise it computes `log2(x)` as `t1 + t2` (208-256: a subnormal `x` scaled by 2^53 at
212-213, the significand in one of three intervals at 218-220, `ss = (ax-bp[k])/(ax+bp[k])` and a
polynomial in `ss^2`), `z = y*log2(x)` as `p_h + p_l` over `y` split at its high word (259-263),
returns overflow or underflow from `z`'s words (266-278), and computes `2^z` as `2^n * 2^(z-n)`
(282-307), sending a subnormal result to `scalbn` (306) and otherwise adding `n` to the high word
(307); line 308 multiplies by the sign `s`.

The port computes the common path alone in the vector (the owner's decisions of 23 November 2026
on issue 34) and finishes every other lane per lane with `StrictMath.pow`, which is the C. The
common path's operations are the C's, in the C's order, with no fused multiply-add and no
reassociation; its choices are blends under the C's own compares (L13): `bp[k]`, `dp_h[k]` and
`dp_l[k]` are operand blends of the C's constants (so `dp_h[0] = +0.0` and `dp_l[0] = +0.0` are
still added, as the C adds them), `k<<18` is the blend of 0 and `0x40000`, line 220's `n += 1`
and `ix -= 0x00100000` are blends of the int lanes, lines 285-293 are blends under `i >
0x3fe00000` (their shifts, by per-lane counts, take garbage counts on the other lanes, which
the Vector API masks to five bits and which are not kept, L13's H3), and line 291's sign is a
blend under `j < 0`. Every `__LO(v) = 0` is an AND of `v`'s bits with the high word's mask, every
`__HI(v) = word` an int word widened by `I2L` and shifted left by 32, or-ed with `v`'s low word
(none, for `t_h` of line 231 and `t` of line 289, which start from `zero`), and `(double)n` is
`I2D`, exact. Two things are not the C's text: the guard (L19), and line 307 with line 308 (L20).

### L19. The guard admits only lanes on which the common path is the C

**Statement.** Let a lane be admitted when none of these holds, the vector's rare mask being
their union (and-ed with `live` in the masked form):

1. `hx < 0x00100000` or `hx >= 0x7ff00000`, `hx` being `__HI(x)` as a signed `int` (so every
   negative `x`, `-0` included, `+0`, the positive subnormals, `+inf` and the positive NaNs);
2. `x` is `1.0`, by its 64 bits;
3. `y` is a zero (`(iy|ly) == 0`, its bits with the sign cleared being zero);
4. `iy > 0x41e00000`, line 191's own test (which holds for the infinities and the NaNs too);
5. `y`'s 64 bits are those of 1, -1, 2 or 0.5;
6. line 266's `j >= 0x40900000` or line 272's `(j&0x7fffffff) >= 0x4090cc00`, on `j = __HI(z)` of
   line 264, each taken as a whole (the lanes of `z` exactly 1024 or -1075 that the C does not
   return from are finished too);
7. line 306's `(j>>20) <= 0`, on `j` of line 305.

On an admitted lane the C takes no return before line 308, takes line 208's branch and not line
212's, does not take line 306, and has `s = one`; so the C's result is line 307's `z` times `s`,
and every operation the vector computes on the lane is the C's, so the lane is the C's result
(by L13 and L20). A lane that is not admitted is overwritten by `StrictMath.pow` on its own
arguments.

**Hypotheses.** The input domain is every pair of doubles. The hinge conditions are the seven
tests, each the C's compare on the C's words (H1 of L13), or a test of 64 bits that is the same
predicate as the C's word tests: 5 is line 147's `ly == 0` with line 156's `iy == 0x3ff00000`
(`+-1`), line 159's `hy == 0x40000000` (`+2`, since `hy` keeps the sign) and line 160's `hy ==
0x3fe00000` (`+0.5`); 3 is line 119's; 2 is line 169's `ix == 0x3ff00000` with line 168's `lx ==
0` for a positive `x`.

**Proof.** Fix an admitted lane. By 1, `0x00100000 <= hx < 0x7ff00000`, so `x` is positive,
finite and normal, and `ix = hx`, `lx` any. By 3, line 119 does not return. No argument is NaN
(1 for `x`; 4, since a NaN `y` has `iy >= 0x7ff00000 > 0x41e00000`), so line 122 does not.
`hx >= 0`, so lines 132-144 leave `yisint = 0`. Line 147 with `ly == 0`: `iy == 0x7ff00000` is
excluded by 4, `iy == 0x3ff00000` (then `y` is +-1) by 5, `hy == 0x40000000` (`y = 2`) by 5, and
`hy == 0x3fe00000` (`y = 0.5`) returns since `hx >= 0`, and is excluded by 5; so lines 148-164
do not return. Line 168 with `lx == 0`: `ix` is neither `0x7ff00000` nor 0 by 1, and `ix ==
0x3ff00000` with `lx == 0` is `x = 1.0`, excluded by 2; so lines 169-179 do not return. Line 182
gives `n = (hx>>31)+1 = 1`, so `(n|yisint) = 1` and line 185 does not return, and `(n|(yisint-1)) =
1|-1 = -1 != 0`, so line 188 leaves `s = one`. By 4 line 191's test is false and the `else` of
line 208 is taken, and since `ix >= 0x00100000` line 212's is not. The lane then runs lines
210-265 as the vector does. By 6 neither line 266's nor line 272's branch is entered, so lines
267-278 do not return. Lines 282-305 run as the vector does, and by 7 line 306's test is false,
so line 307 is taken. QED.

Two parts are **redundant**, kept because the C returns early there and not because the common
path would be wrong: on `x = 1.0` (2) and on a zero `y` (3) the common path's operations give
`1.0`, the C's result, on every pair drawn (with `x = 1`, `ss` and every term after it are zeros
and `z` is `+-0`; with `y` a zero, `z` is a zero). The proof does not use that. Every other part
is a hinge: on pairs that part alone keeps out, the common path differs from `StrictMath.pow` on
some (one run of `l19EachPartOfTheGuardIsAHingeOrNamedRedundant`, pairs differing of pairs drawn:
a negative `x` 59,285 of 64,197, `+0` or a subnormal `x` 15,726 of 16,323, `+inf` or a NaN `x`
2,677 of 2,677, `|y|` past 2^31 beside `x` within 2^-40 of 1 42 of 5,952, the special `y` 457 of
7,821, `z` out of bounds 10,249 of 10,367, a subnormal result 19,707 of 19,707; `x = 1` 0 of 4,255
and a zero `y` 0 of 4,166).

**Test (scalar).** `PowTest.l19TheGuardAdmitsOnlyTheCommonPath` runs the C as written (`cPow`,
which the gate holds equal to `StrictMath.pow` in every JIT mode) on the gate's inputs and the
guard's boundaries, and on every admitted pair asserts that the C took only the common path's ways
out (the three intervals, either side of `|z| = 0.5` and line 307; none of the returns, no
subnormal `x`, no short log, no scalbn, no negative result) and that the common path transcribed
alone (`commonPath`, lines 210-307 with every return left out) is the C's result, bit for bit.
`l19EachPartOfTheGuardIsAHingeOrNamedRedundant` draws pairs on which one part alone holds and
requires the common path to differ from `StrictMath.pow` on some of them, for each part but the
two redundant ones, on which it requires no difference; so a guard without a hinge part fails it.
**Vector tie.** `PowTest.guardBoundaries` runs the guard's boundaries (the words of the smallest
normal, the largest finite and 1 and their neighbours, either sign, beside `y` at `0x41e00000`
with three low words, at the special values, their neighbours, -2 and -0.5) and the pairs of each
part alone through `VFdlibm.pow` at every width, unmasked and masked;
`lanesAreTheTranscription` requires every admitted lane to equal `commonPath` bit for bit; and
`everySubsetOfLanesRare` puts a lane of each part beside common lanes in every position. **Planted
faults.** `pow.guardAdmitsNegativeX` tests `ix` instead of `hx` in part 1, and
`pow.guardMissesSubnormal` drops part 7; `PowTest.faultCatchers` requires each to fail.

**Encoding.** None: a case analysis of the C's tests, informal, not machine-checked.

### L20. Line 307 is `__HI(z) = j`, and line 308's `s*z` is `z`

**Statement.** On an admitted lane (L19) the vector's result, `z` with its high word replaced by
`j` of line 305, is the C's `s*z` after line 307.

**Proof.** Line 307's `__HI(z) += (n<<20)` sets `z`'s high word to `__HI(z) + (n<<20)` modulo
2^32 and keeps its low word; line 304-305's `j` is that same `int` sum, so writing `j` as the high
word is the same operation. By L19 `s = one`, and `fl(1 * z) = z` for every `z` (R3), so line 308
returns `z`. QED.

**Test.** The vector tie of L19 (`lanesAreTheTranscription` compares the lanes with `commonPath`,
which writes line 307 as the C does and returns `z` without `s`); `pow.guardAdmitsNegativeX`
shows that a lane with `s = -one` let through gives other bits (a negative `x` with an odd `y`).

**Encoding.** None.

**The lane of two NaNs** is finished per lane, so it is `StrictMath.pow`'s own result, a candidate
of `OPERANDS`; one NaN beside anything else is finished too.

**Checks.** `PowTest`: `VFdlibm.pow` against `StrictMath.pow` by `PairBits.matches` (`OPERANDS`)
and against `cPow` at every width and `SPECIES_MAX`, unmasked and masked, on every pair of the
special values, the exact powers, the fuzz's draws at the C's words and points, negative `x` beside
integer and half-integer `y`, huge `y` beside `x` near 1, `y*log2(x)` near 1024, -1075, the
subnormal results and +-0.5, pairs either side of `MIN_NORMAL`, NaN pairs, sampled bits, Varka's
baseline range and every exponent, the guard's boundaries, and every subset of a vector's lanes
rare, and the 4,168 pairs of `hard-cases/pow.txt` (exact results, ties, results within 2^-28 ulp
of a midpoint and the doubles beside the result's thresholds, computed by
`dev/pow-hard-cases.py`). The constants are pinned to the C's hexadecimal
(`constantsAreTheSourcesBits`, `portConstantsAreTheSources`).
