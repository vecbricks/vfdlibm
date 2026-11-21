# Proofs: where a port computes differently from fdlibm's C

Every lane of every function here is bit-identical to `StrictMath`. A port follows the C operation
by operation, except where a lemma below proves that a different computation gives the same
double for every input (`PLAN.md` section 6). Each lemma names its hypotheses (the input domain
and each hinge condition, with the range where it holds and why the lanes are in it), the IEEE 754
property it rests on, the proof, and the test that checks its hinges; that test fails if a hinge
were false. The code carries a pointer to the lemma. A port with no departure of its own
(log10, log1p) has a section saying so; log10's names the lemmas it rests on through `log`,
log1p's holds a counter-example, a statement and not a proof of a fold.

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
arm, the guarded arm and the public one to fail on the witnesses with it planted, at every
width. The finish arm finishes the `hu == 0` lanes with `StrictMath.log1p`, so it cannot see the
fold and is not asked to. The nightly's fuzz is very unlikely to find this fault either (its
inputs that differ are about one in `2^29` of a window), so
`NightlyTest.fuzzCatchesThePlantedFault` leaves it out. With the fold planted the rest of
`Log1pTest` passes: no other case reaches a witness.

**Encoding.** None. The two results are Java's evaluation of the transcription, not a solver's,
and the search is a program, so nothing here is machine-checked in `proofs/`; an evaluation of
the chain like L4's is not written.

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
other port body.

**Checks.** `constantsAreTheSourcesBits` checks that the six words, `one`, `atanhi`, `atanlo` and
`aT` are the C's. `AtanTest` compares `VFdlibm.atan` with `StrictMath.atan` at every width and
`SPECIES_MAX`, unmasked and masked, at those words (and `0x7ff00000`) and their neighbours, on
sampled bit patterns, dense draws, the published hard cases and their negations, and with every
subset of a vector's lanes rare; `scalarTranscriptionIsStrictMath` checks that `cAtan`, s_atan.c
in scalar Java, is `StrictMath.atan`. The planted faults are `atan.dropLo` (`atanlo` dropped),
`atan.splitMoved` (the `19/16` word moved to 2, which moves a compare) and `atan.hiOneUlpOff`
(`atanhi[1]` one ulp up). No fault reaches the compares at `0x3fdc0000`, `0x3fe60000`,
`0x40038000`, `0x3e200000` or `0x44100000`, and a compare moved without changing a result is
not caught by a test of results; no test claims to catch those.
