# Proofs: where a port computes differently from fdlibm's C

Every lane of every function here is bit-identical to `StrictMath`. A port follows the C operation
by operation, except where a lemma below proves that a different computation gives the same
double for every input (`PLAN.md` section 6). Each lemma names its hypotheses (the input domain
and each hinge condition, with the range where it holds and why the lanes are in it), the IEEE 754
property it rests on, the proof, and the test that checks its hinges; that test fails if a hinge
were false. The code carries a pointer to the lemma.

The proofs are informal, or reduced to a finite check by a theorem. Each lemma ends with a line
saying how it would be machine-checked under the conventions of Varka's `sql/varka/proofs/`
(SMT-LIB over integers, a double as `M * 2^E`, rounding as the `jdouble.rne` relation to the
nearest double in a named binade, not SMT's floating-point theory; `unsat` checks are the proofs,
`sat` checks show the domain is not vacuous and that a constant changed by one is refuted), and
which constant change its `sat` check refutes. No proof is machine-checked yet.

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
- (H2) On the domain, `c` has `x`'s sign and `0.94|x| <= |c| < 0.37`. (The brief's `|c| < 0.35`
  is false: at `x = -0x3fd62e42_ffffffff`, `c = -0.36655...`.) Why: with `T = x*x <= 0.1202`, the
  bracket `P1 + T*(...)` lies in `(0.1663, 0.1667)`, so `fl(T * bracket) <= 0.0578 |x| (1 +
  2^-52)` and `c = fl(x - that)` keeps `x`'s sign with the stated bounds.
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

**Hinge tests.** `ProofsTest.l1ZeroReductionLeavesX` (step 1, over both ends of the domain, their
neighbours and 65536 seeded draws over every binade, both signs);
`ProofsTest.l1SignSymmetryAndPositiveQuotient` (H2, H3 and step 2 on the same inputs, and the two
tails equal); `ProofsTest.l1KIsForcedByTheHighWordTest` (H1: the formula gives `k = +-1` at the top
of the word, and at `0x3fd62e42_fefa3a08` the far form with `k = 1` is not exp). End to end:
`ExpTest.foldBoundaries` runs both arms against `StrictMath` at the words `0x3e300000 +- 1`,
`0x3fd62e42`, `0x3fd62e43` and `0x3ff0a2b1`, `0x3ff0a2b2`, both signs, low words 0, `0xffffffff`
and 1024 seeded, plus the 4096 doubles above `+-0.5 ln2`, at every width and `SPECIES_MAX`; with
the `k` forcing removed it fails.

**Encoding.** Steps 1-3: (R1) and (R3) as lemmas of the per-binade `jdouble.rne` relation over
one symbolic binade, `unsat`. H2 and H3 multiply unknowns (`x*c`, `T*bracket`), which Varka's
README says needs its own design: as a bound lemma per binade of `x` (the bracket's interval,
then `c` linear in `x` within a binade), then linear, `unsat`; until then they are sampled. The
`sat` check refutes the domain's top word changed by one, `0x3fd62e43`: `x = 0x3fd62e43_00000000`
is a C-reduced lane, and `k = 0` there gives a different double.

### L2. The near band's form is the far form

**Statement.** For a double `x` with `0x3fd62e43 <= hx <= 0x3ff0a2b1` (line 129's band, `X1 <= |x|
<= X2`, `X2 = 0x3ff0a2b1_ffffffff`), the far form `k = (int) fl(fl(invln2*x) + halF[xsb])`, `t =
k`, `hi = x - fl(t*ln2HI[0])`, `lo = fl(t*ln2LO[0])` gives `k = 1 - 2*xsb`, `hi = x - ln2HI[xsb]`
and `lo = ln2LO[xsb]`, bit for bit: the near form, line 130.

**Hypotheses.**
- (H4) The band's boundary doubles: `s(X1) = fl(fl(invln2*X1) + 0.5) = 1.0000000013739176`
  (`s - 1 = 1.374e-9`) and `s(X2) = 1.9999996601564510` (`2 - s = 3.398e-7`). Recomputed here; the
  brief's `[1 + 1.3e-9, 2 - 3.4e-7]` is the same interval, rounded.
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

**Hinge tests.** `ProofsTest.l2BandBoundariesGiveKOfOne` (H4 at both boundary doubles and their
negatives, with the margins, and the two `sat` cases below); `ProofsTest.l2FarFormIsNearForm` (H5,
and the far form's `k`, `hi`, `lo` against the near form's at 65536 uniform draws over the band
and 8 neighbours of each end, both signs); `ExpTest.foldBoundaries` end to end.

**Encoding.** By (R2) the claim reduces to the two boundary doubles: the products `invln2 * X1`
and `invln2 * X2` are exact rationals of known binades (`[0.5, 1)` and `[1, 2)`), the sums land in
`[1, 2)`, one `jdouble.rne` step each; `unsat` for `(int) s != 1`, and (R1) for the negatives.
Linear, since every factor but the rounding is a constant. The `sat` checks refute the band
widened by a word at either end: at `hx = 0x3fd62e42`, `x = 0x3fd62e42_00000000` gives `k = 0`;
past `0x3ff0a2b1`, `x = 0x3ff0a2b2_ffffffff` gives `k = 2`, not the near form's 1.

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

**Hinge tests.** `ProofsTest.l3AbsBitsAreNonNegative` (H6 on the specials, sign-set NaNs among
them, and sampled patterns; H7 for each exp threshold; and that the raw bits of `0xfff8...`, sign
set, would miss the filter); `LanesTest.highWordCompares` (the three compares against the C's
integer compare, at every width, on the specials, sampled patterns and both sides of each word,
through the `DoubleVector` forms, which now route through these).

**Encoding.** Over integers, `b = hx * 2^32 + lo` with the bounds above, linear; `unsat` for each
direction. The `sat` check refutes the comparand changed by one, `b > h * 2^32` for `hx >= h`:
`b = h * 2^32` (`lo = 0`) is a model.

### L4. The tiny lanes cannot be folded (a counter-example, not a fold)

**Statement.** For `|x| < 2^-28` the C returns `one + x` (line 140). Folding these lanes into L1's
tail (`k = 0`, `one - ((+0 - q) - x)`) is not bit-identical.

**Counter-example.** `x = 5 * 2^-53 = 0x1.4p-51`. `1 + x = 1 + 2.5 ulp(1)` is a tie and rounds to
even, `0x1.0000000000002p0`, which is `StrictMath.exp(x)`. The tail adds `q`, about `x^2 / 2 > 0`,
first: `fl(-q - x) = -fl(x + q)`, and `x + q` lies above the tie, so `one - ` it rounds up to
`0x1.0000000000003p0`. Of `x = m * 2^-53` for `m < 64`, 15 differ this way (`m = 5, 9, 13, ...`).

**Test.** `ProofsTest.l4TinyLanesCannotBeFolded` asserts the three doubles.

**Encoding.** A single `sat` check: the witness `x` is a model of "folded tail != one + x".

### L5. halF[xsb] from the sign bit

**Statement.** `halF[xsb]` (0.5 or -0.5) is the double whose bits are
`(bits(x) & 0x80000000_00000000) | 0x3fe00000_00000000`.

**Proof.** `0.5` is `0x3fe00000_00000000` and `-0.5` differs from it in the sign bit alone (IEEE
754 sign-magnitude encoding); `xsb` is that bit of `x` (line 113). The port so replaces the C's
table lookup (a compare and a blend of two constants) with an `and` and an `or`.

**Test.** `ProofsTest.l5HalfFromSignBit`, on the specials of both signs.

**Encoding.** Over bit-vectors, two cases of the sign bit, `unsat`; the `sat` check refutes
`0x3fe00000_00000001` as the constant.
