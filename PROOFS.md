# Proofs: where a port computes differently from fdlibm's C

Every lane of every function here is bit-identical to `StrictMath`. A port follows the C operation
by operation, except where a lemma below proves that a different computation gives the same
double for every input (`PLAN.md` section 6). Each lemma names its hypotheses (the input domain
and each hinge condition, with the range where it holds and why the lanes are in it), the IEEE 754
property it rests on, the proof, and the test that checks its hinges; that test fails if a hinge
were false. The code carries a pointer to the lemma.

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
is tight to: not `invln2` and `P1..P5`, as L2's **Encoding** says; `proofs/README.md`).
Each lemma ends with an **Encoding** paragraph naming its file and saying which steps are
machine-checked and which stay informal. The files' constants are rendered from `Exp` and
`Lanes` by `ProofFiles`, so `ProofFilesTest` fails when one moves without its proof; `dev/prove.sh
--solver both` runs them under Z3 and cvc5.

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
- **(R4) Correct rounding of the basic operations.** `+ - * /` return `fl` of the exact result;
  with no fused multiply-add, each Java operation is one such rounding, as each C operation is.

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
`Exp.expBlended`), unmasked and masked, against `StrictMath` at the words `0x3e300000 +- 1`,
`0x3fd62e42`, `0x3fd62e43` and `0x3ff0a2b1`, `0x3ff0a2b2`, both signs, low words 0, `0xffffffff`
and 1024 seeded, plus the 4096 doubles above `+-0.5 ln2`, at every width and `SPECIES_MAX`; with
the `k` forcing removed it fails.

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
`DoubleVector` forms, which now route through these).

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
tie.** `ExpTest.foldBoundaries` covers the words `0x3e300000 +- 1` on both sides of the tiny
lanes' edge, and the threshold cases (`ExpTest.thresholds()`) the 64 neighbours of `2^-28`:
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
(L6). Line numbers in this section are `reference/fdlibm-5.3/e_log.c`'s, and `hx` is the C's
`int hx`.

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
- (H8) `dk` is `+0` on exactly the `k == 0` lanes: `I2D` of 0 is `+0.0` (JLS 5.1.2), as is L8's
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
`-0` for (d)); `ProofsTest.l6FIsNeverNegativeZero` (H9 over the specials, sampled bit patterns,
the subnormals and the `k == 0` domain: `xn` in `[0.5, 2)`, `f` finite and not `-0`, zero
exactly at `xn = 1`, and `f = xn - 1` exactly, by `BigDecimal`);
`ProofsTest.l6FoldedFormsAreTheKZeroForms` (all four forms over the `k == 0` domain's ends, the
neighbours of 1.0 and 65536 draws, each form on every lane, not only its own; H10, and `shr` not
`-0`); `ProofsTest.l6HingesRefuted` (at `f = -0, B = +0` the fold is not the C, and with `hfsq =
shr = -0`, H10 false, `shr + (+0)` changes the difference). **Vector tie.** `LogTest.foldBoundaries`
and `foldBoundariesAtMaxSpecies` run both arms (`VFdlibm.log` and `Log.logBlended`), unmasked and
masked, against `StrictMath` at the high words of the sqrt(2) split on both `k == 0` binades
(`0x3fe6a09b`, `0x3fe6a09c`, `0x3ff6a09b`, `0x3ff6a09c`), the band's edges inside them
(`0x3ff61479`, `0x3ff6147a`, `0x3fe6b851`, `0x3fe6b852`) and the `|f| < 2^-20` words around 1.0
(`0x3feffffd` to `0x3ff00002`), low words 0, `0xffffffff` and 1024 seeded, plus the 4096 doubles
each side of 1.0 and 64 each side of sqrt(2) and sqrt(2)/2, at every width and `SPECIES_MAX`.
