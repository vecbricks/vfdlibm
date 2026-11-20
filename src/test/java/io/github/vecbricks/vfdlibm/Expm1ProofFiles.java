/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static io.github.vecbricks.vfdlibm.ExpProofFiles.binade;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.body;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.hex;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.mant;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.near;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.pow2;
import static io.github.vecbricks.vfdlibm.ExpProofFiles.sign;
import static io.github.vecbricks.vfdlibm.ProofFiles.check;
import static io.github.vecbricks.vfdlibm.ProofFiles.equations;
import static io.github.vecbricks.vfdlibm.ProofFiles.lit;

/**
 * The proofs of expm1's lemmas ({@code PROOFS.md}, expm1, L10 and L11), rendered with the
 * constants {@link Expm1} holds, so a constant that moves without its proof fails {@link
 * ProofFilesTest}. Each method renders one file under {@code proofs/}; {@link ProofFiles#render}
 * lists them.
 */
final class Expm1ProofFiles {

  private Expm1ProofFiles() {
  }

  private static long bits(double d) {
    return Double.doubleToRawLongBits(d);
  }

  // ---------------------------------------------------------------------------------------------
  // expm1_reduction.smt2: L10

  /** L10's H14 witnesses: the first doubles from the top of the word where a free k differs. */
  private static final long[] WITNESSES = {0x3fd62e42_ffffffecL, 0xbfd62e42_fffffff3L};

  /** Controls: the word's top double, either sign, where a free k gives the C's double. */
  private static final long[] CONTROLS = {0x3fd62e42_ffffffffL, 0xbfd62e42_ffffffffL};

  /**
   * s_expm1.c's lines 184-188 on the reduced {@code x}: {@code hfx}, {@code hxs}, {@code r1},
   * {@code t} and {@code e}, one rounding at a time; returns {@code hxs} and {@code e}.
   */
  private static double[] hxsAndE(ExpProofFiles.Chain c, double x) {
    double hfx = c.op("hfx = 0.5*x", 0.5, '*', x);
    double hxs = c.op("hxs = x*hfx", x, '*', hfx);
    double p = c.op("hxs*Q5", hxs, '*', Expm1.Q5);
    p = c.op("Q4+hxs*Q5", Expm1.Q4, '+', p);
    p = c.op("hxs*(Q4+...)", hxs, '*', p);
    p = c.op("Q3+hxs*(...)", Expm1.Q3, '+', p);
    p = c.op("hxs*(Q3+...)", hxs, '*', p);
    p = c.op("Q2+hxs*(...)", Expm1.Q2, '+', p);
    p = c.op("hxs*(Q2+...)", hxs, '*', p);
    p = c.op("Q1+hxs*(...)", Expm1.Q1, '+', p);
    p = c.op("hxs*(Q1+...)", hxs, '*', p);
    double r1 = c.op("r1 = one+hxs*(...)", Expm1.ONE, '+', p);
    double t = c.op("t = 3.0-r1*hfx", 3.0, '-', c.op("r1*hfx", r1, '*', hfx));
    double n = c.op("r1-t", r1, '-', t);
    double d = c.op("6.0-x*t", 6.0, '-', c.op("x*t", x, '*', t));
    double e = c.op("e = hxs*(...)", hxs, '*', c.op("(r1-t)/(6.0-x*t)", n, '/', d));
    return new double[] {hxs, e};
  }

  /** s_expm1.c as written at a double of word 0x3fd62e42: k = 0 and line 189's return. */
  private static double asWritten(StringBuilder out, long xBits) {
    double x = Double.longBitsToDouble(xBits);
    var c = new ExpProofFiles.Chain(out, "k = 0 at " + hex(xBits));
    double[] he = hxsAndE(c, x);
    double inner = c.op("x*e-hxs", c.op("x*e", x, '*', he[1]), '-', he[0]);
    return c.op("x-(x*e-hxs)", x, '-', inner);
  }

  /**
   * s_expm1.c reduced with the formula's {@code k = +-1} at a double of word 0x3fd62e42: lines
   * 169-175 with that {@code k}, lines 184-188, then lines 191-196.
   */
  private static double reduced(StringBuilder out, long xBits) {
    double x0 = Double.longBitsToDouble(xBits);
    int k = x0 > 0 ? 1 : -1;
    var c = new ExpProofFiles.Chain(out, "k = " + k + " at " + hex(xBits));
    double t = k;
    double hi = c.op("hi = x-t*ln2_hi", x0, '-', c.op("t*ln2_hi", t, '*', Expm1.LN2_HI));
    double lo = c.op("lo = t*ln2_lo", t, '*', Expm1.LN2_LO);
    double x = c.op("x = hi-lo", hi, '-', lo);
    double cc = c.op("c = (hi-x)-lo", c.op("hi-x", hi, '-', x), '-', lo);
    double[] he = hxsAndE(c, x);
    double e = c.op("x*(e-c)-c", c.op("x*(e-c)", x, '*', c.op("e-c", he[1], '-', cc)), '-', cc);
    e = c.op("e -= hxs", e, '-', he[0]);
    if (k == -1) {
      return c.op("0.5*(x-e)-0.5", c.op("0.5*(x-e)", 0.5, '*', c.op("x-e", x, '-', e)), '-', 0.5);
    }
    if (x < -0.25) {
      double u = c.op("e-(x+0.5)", e, '-', c.op("x+0.5", x, '+', 0.5));
      return c.op("-2.0*(e-(x+0.5))", -2.0, '*', u);
    }
    double u = c.op("2.0*(x-e)", 2.0, '*', c.op("x-e", x, '-', e));
    return c.op("one+2.0*(x-e)", Expm1.ONE, '+', u);
  }

  /** The formula's {@code (int) fl(fl(x*invln2) + h)}, as the port and line 169 compute it. */
  private static int formulaK(double x) {
    return (int) (x * Expm1.INVLN2 + (x > 0 ? 0.5 : -0.5));
  }

  /**
   * The checks that a double of word 0x3fd62e42 is unreduced by the C's test, and that the
   * formula's {@code fl(fl(x*invln2) + h)} rounds there into binade 2^0 with x's sign, so its
   * cast is {@code +-1}; returns that k, which Java's own evaluation must agree with.
   */
  private static int formulaCast(StringBuilder out, long wb) {
    double x = Double.longBitsToDouble(wb);
    int s = x > 0 ? 1 : -1;
    if (formulaK(x) != s) {
      throw new IllegalStateException("the formula's k is not +-1 at " + hex(wb));
    }
    long mi = mant(Expm1.INVLN2);
    int ji = binade(Expm1.INVLN2);
    String at = "at " + hex(wb);
    check(out, at + ": hx is " + hex(Expm1.HX_HALF_LN2) + ", not above it: unreduced",
        "unsat", body("(assert (not (= (c.hx " + lit(wb) + ") " + Expm1.HX_HALF_LN2 + ")))"));
    int jx = binade(x);
    int jp = binade(x * Expm1.INVLN2);
    String prodA = "(* " + lit(s * mi) + " " + mant(x) + ")";
    String prodB = pow2(52 - ji + 52 - jx);
    String half = "(+ P " + pow2(51 - jp) + ")";
    String sumA = s > 0 ? half : "(- " + half + ")";
    String sumB = pow2(52 - jp);
    check(out, at + ": x*invln2 rounds into 2^" + jp + " only, with x's sign", "unsat", body(
        "(declare-const S2 Int) (declare-const M2 Int)",
        "(assert (or " + near(prodA, prodB, "S2", "M2", jp - 1),
        "  " + near(prodA, prodB, "S2", "M2", jp + 1),
        "  " + near(prodA, prodB, sign(-s), "M2", jp) + "))"));
    check(out, at + ": the sum with h rounds into 2^0, x's sign: k = " + s, "unsat",
        body("(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)",
            "(assert " + near(prodA, prodB, sign(s), "P", jp) + ")",
            "(assert (or " + near(sumA, sumB, "S2", "M2", -1),
            "  " + near(sumA, sumB, "S2", "M2", 1),
            "  " + near(sumA, sumB, sign(-s), "M2", 0) + "))"));
    check(out, "not vacuous: " + at + " the sum rounds into 2^0", "sat", body(
        "(declare-const P Int) (declare-const M2 Int)",
        "(assert " + near(prodA, prodB, sign(s), "P", jp) + ")",
        "(assert " + near(sumA, sumB, sign(s), "M2", 0) + ")"));
    return s;
  }

  /**
   * {@code expm1_reduction.smt2}, lemma L10: the constants of the reduction are exp's (H13), the
   * unreduced lanes keep {@code x} with {@code c = +0} (step 3), and the high-word test, not the
   * formula, forces {@code k = 0} there (H14), each rounding of both computations evaluated at the
   * two witnesses.
   */
  static String reduction() {
    double bottom = Double.longBitsToDouble(Lanes.fromHiBits(Expm1.HX_TINY));
    double top = Double.longBitsToDouble(Lanes.fromHiBits(Expm1.HX_HALF_LN2 + 1) - 1);
    long lnHiUp = bits(Expm1.LN2_HI_UP);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; expm1's reduction is exp's, and its unreduced lanes keep x with c = +0 (PROOFS.md, L10).
        ;
        ; s_expm1.c (reference/fdlibm-5.3) reduces hx > 0x%x by a near and a far form (lines
        ; 162-175) and leaves k = 0 below it (line 181), never reading c there. The port (Expm1)
        ; computes on every lane k = (int) fl(fl(x*invln2) + h), with +0.0 as the cast's argument
        ; where hx <= 0x%x, then t = k, hi = x - fl(t*ln2_hi), lo = fl(t*ln2_lo), r = hi - lo
        ; and c = (hi - r) - lo.
        ;
        ; What is proven:
        ;   - Section A (H13): Expm1's invln2, ln2_hi, ln2_lo, -ln2_lo, the bits of 0.5 and the
        ;     two words of the reduction, rendered from Expm1, are Exp's invln2, ln2HI[0],
        ;     ln2LO[0], ln2LO[1], HALF_BITS and words, rendered from Exp, bit for bit; so
        ;     exp_near_band.smt2 (L2) and exp_half_sign.smt2 (L5), rendered from Exp's, are about
        ;     expm1's reduction too. Expm1's LN2_HI_UP, the value the planted fault
        ;     expm1.constantOffByOneUlp uses, is ln2_hi one ulp up and is not Exp's.
        ;   - Section B (step 3 of the proof), t = +0: the sign of an exact zero (IEEE 754 6.3)
        ;     as z.sub, z.add and z.mul, pinned to the JVM over every pair of signed zeros and
        ;     over +-0 times Expm1's ln2_hi and ln2_lo; so +0 * ln2_hi and +0 * ln2_lo are +0. A
        ;     nonzero normal x less +0 is x: jdouble.near admits the double for itself in any
        ;     binade and no other double of its binade or the next ones, every normal binade at
        ;     once. So hi = x, lo = +0 and r = x, and for a zero x of either sign likewise. Then
        ;     hi - r is x - x, +0 (the rule for like signs, pinned at sampled doubles while
        ;     rendering, or a zero difference of like-signed zeros), and c = +0 - (+0) = +0.
        ;     With t = -0 the first step fails at x = -0. The lanes' ends, %s and
        ;     %s, are rendered from Expm1's words, decoded and admitted.
        ;   - Section C (H14): the two witnesses %s and %s
        ;     have the high word 0x%x, so line 162's test leaves them unreduced; there the
        ;     formula's fl(fl(x*invln2) + h) rounds into binade 2^0 with x's sign, so its cast is
        ;     +-1, not 0. Then each rounding of the C as written (k = 0, line 189's return) and of
        ;     the C reduced with that k (lines 169-175, 184-188 and 191-196) is evaluated: for
        ;     every operation, the exact result on the two doubles Java computed before it rounds
        ;     to the double Java computed and to no other double in its binade or the next ones.
        ;     The two results differ. At the word's top double, either sign, the formula's cast
        ;     is +-1 as well and the same two chains end in one double: the witnesses are not an
        ;     accident of the chains.
        ;
        ; What is not machine-checked:
        ;   - fl(x - (-ln2_hi)) = fl(x + ln2_hi), step 2's use of (R4): both are the rounding of
        ;     one rational in this encoding, so there is no check to write.
        ;   - That StrictMath.expm1 is the k = 0 computation at the witnesses: the oracle is in
        ;     no solver (ProofsTest.l10KIsForcedByTheHighWordTest).
        ;   - That the witnesses are the first from the word's top, and how many doubles of the
        ;     word differ: a scalar run of the C's transcription (PROOFS.md, H14).
        ;   - A subnormal x less +0: the lanes of step 3 that are read, hx >= 0x%x, are
        ;     normal, and the lanes below return line 179's form of x itself.
        ;   - That D2I truncates toward zero, so a double of binade 2^0 casts to +-1, that D2I of
        ;     +0.0 is 0 and I2D of 0 is +0.0 (JLS 5.1.3, 5.1.2); that a value rounds to no double
        ;     in a binade further off than the next one (the arithmetic of rationals); and that
        ;     the port computes these steps: the code's text.
        ;
        ; Constants. Section A is tight to every constant it names: a changed one fails its
        ; check. No claim of section B is tight to ln2_hi or ln2_lo beyond their sign and
        ; finiteness. Section C's cast holds for a range of invln2 (its margin is that of
        ; exp_zero_reduction.smt2's section C), and its chains evaluate whatever Q1..Q5 Expm1
        ; holds and compare the results with no source value, so with a Q moved by one ulp the
        ; file renders anew and passes as long as the witnesses still differ and the controls
        ; still agree. Expm1Test.constantsAreTheSourcesBits pins them all to the source's hex,
        ; and ProofFilesTest this file to its rendering.
        ;
        ; The logic is ALL: section B's products S * M * sn are nonlinear. Section C's checks
        ; carry their own non-vacuity: each asserts that the named double is not admitted, or
        ; another one is, so a jdouble.near that admitted nothing would make it sat.
        """).formatted(Expm1.HX_HALF_LN2, Expm1.HX_HALF_LN2, hex(bits(bottom)), hex(bits(top)),
            hex(WITNESSES[0]), hex(WITNESSES[1]), Expm1.HX_HALF_LN2, Expm1.HX_TINY));
    out.append(ProofFiles.rendered("ALL"));

    out.append("""

        ; Section A: H13. Each constant as its raw bits, or the word itself; Expm1's on the left,
        ; Exp's on the right.
        """);
    record Same(String name, String exps, String value, long expm1, long exp) {}
    for (Same c : new Same[] {
        new Same("invln2", "invln2", hex(bits(Expm1.INVLN2)), bits(Expm1.INVLN2),
            bits(Exp.INVLN2)),
        new Same("ln2_hi", "ln2HI[0]", hex(bits(Expm1.LN2_HI)), bits(Expm1.LN2_HI),
            bits(Exp.LN2HI_0)),
        new Same("ln2_lo", "ln2LO[0]", hex(bits(Expm1.LN2_LO)), bits(Expm1.LN2_LO),
            bits(Exp.LN2LO_0)),
        new Same("-ln2_lo", "ln2LO[1]", hex(bits(-Expm1.LN2_LO)), bits(-Expm1.LN2_LO),
            bits(Exp.LN2LO_1)),
        new Same("HALF_BITS", "HALF_BITS", hex(Expm1.HALF_BITS), Expm1.HALF_BITS,
            Exp.HALF_BITS),
        new Same("HX_HALF_LN2", "HX_HALF_LN2", hex(Expm1.HX_HALF_LN2), Expm1.HX_HALF_LN2,
            Exp.HX_HALF_LN2),
        new Same("HX_THREE_HALVES_LN2", "HX_THREE_HALVES_LN2", hex(Expm1.HX_THREE_HALVES_LN2),
            Expm1.HX_THREE_HALVES_LN2, Exp.HX_THREE_HALVES_LN2)}) {
      check(out, "expm1's " + c.name() + ", " + c.value() + ", is exp's " + c.exps(), "unsat",
          body("(assert (not (= " + lit(c.expm1()) + " " + lit(c.exp()) + ")))"));
    }
    check(out, "refuted: LN2_HI_UP, " + hex(lnHiUp) + ", the fault's, is not exp's ln2HI[0]",
        "sat",
        body("(assert (not (= " + lit(lnHiUp) + " " + lit(bits(Exp.LN2HI_0)) + ")))"));
    check(out, "that value is ln2_hi with M one more, same sign and binade", "unsat", body(
        "(assert (not (and (= (jdouble.fromBits.M " + lit(lnHiUp) + ") (+ 1 (jdouble.fromBits.M "
            + lit(bits(Expm1.LN2_HI)) + ")))",
        "  (= (jdouble.fromBits.E " + lit(lnHiUp) + ") (jdouble.fromBits.E "
            + lit(bits(Expm1.LN2_HI)) + "))",
        "  (= (jdouble.fromBits.S " + lit(lnHiUp) + ") 1))))"));

    out.append("""

        ; Section B: step 3, with t = +0. The sign of an exact zero, true for -0. z.sub sx sy is
        ; the sign of x - y and z.add sx sy that of x + y for zeros x and y; z.mul is a zero
        ; product's.
        """);
    out.append(ProofFiles.ZERO_SIGNS);
    ProofFiles.requireLikeSignedDifferencesArePositiveZero();
    equations(out, "the zero signs are Java's, and +-0 * ln2_hi, ln2_lo",
        ProofFiles.zeroSignEquations(Expm1.LN2_HI, Expm1.LN2_LO));
    long hiBits = bits(Expm1.LN2_HI);
    long loBits = bits(Expm1.LN2_LO);
    check(out, "ln2_hi and ln2_lo are positive and finite, as the zero product needs", "unsat",
        body("(assert (not (and (= (jdouble.fromBits.S " + lit(hiBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(hiBits) + ") 972)",
            "  (= (jdouble.fromBits.S " + lit(loBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(loBits) + ") 972))))"));
    check(out, "t = +0: t*ln2_hi and t*ln2_lo are +0", "unsat", body(
        "(assert (z.mul false false))"));
    out.append("""

        ; hi = x - t*ln2_hi and r = hi - lo, for a zero x of sign sx: both keep it. For a nonzero
        ; x they are x by the checks after these. Then c = (hi - r) - lo: hi - r is x - x, which
        ; is +0 for a nonzero x (eq, the rule for like signs, Java's x - x while rendering) and
        ; z.sub sx sx for a zero x; less lo = +0.
        (define-fun hi.sign ((sx Bool)) Bool (z.sub sx (z.mul false false)))
        (define-fun r.sign ((sx Bool)) Bool (z.sub (hi.sign sx) (z.mul false false)))
        (define-fun c.sign ((eq Bool) (sx Bool)) Bool
          (z.sub (ite eq false (z.sub (hi.sign sx) (r.sign sx))) (z.mul false false)))
        """);
    check(out, "a zero x, either sign: hi = x - (+0) and r = hi - (+0) are x", "unsat", body(
        "(declare-const sx Bool)",
        "(assert (not (and (= (hi.sign sx) sx) (= (r.sign sx) sx))))"));
    check(out, "c = (hi - r) - lo is +0, x nonzero or a zero of either sign", "unsat", body(
        "(declare-const eq Bool) (declare-const sx Bool)", "(assert (c.sign eq sx))"));
    check(out, "refuted: with t = -0, hi = x - t*ln2_hi is not x for some zero x", "sat", body(
        "(declare-const sx Bool)",
        "(assert (not (= (z.sub sx (z.mul true false)) sx)))"));
    check(out, "that zero is x = -0: -0 - (-0) is +0", "unsat", body(
        "(assert (not (and (z.mul true false) (not (z.sub true (z.mul true false))))))"));
    out.append("""

        ; A nonzero normal x less +0 is x: the exact difference is the double S * M * 2^E, a / b
        ; with a = S * M * sn and b = sd.
        """);
    ProofFiles.nonzeroLessZero(out);
    out.append("""

        ; The ends of the lanes step 3 is read on, from Expm1's words: normal doubles, each one
        ; admitted for itself in its binade.
        """);
    for (double e : new double[] {bottom, top}) {
      long eBits = bits(e);
      String what = (e == bottom ? "the bottom, " : "the top, ") + hex(eBits);
      check(out, what + ": S = 1, binade 2^" + binade(e) + ", M = " + mant(e), "unsat", body(
          "(assert (not (and (= (jdouble.fromBits.S " + lit(eBits) + ") 1)",
          "  (= (jdouble.fromBits.E " + lit(eBits) + ") " + lit(binade(e) - 52) + ")",
          "  (= (jdouble.fromBits.M " + lit(eBits) + ") " + mant(e) + "))))"));
      check(out, "not vacuous: " + what + " less +0 is admitted as itself", "sat", body(
          "(declare-const M2 Int)",
          "(assert " + near(Long.toString(mant(e)), pow2(52 - binade(e)), "1", "M2", binade(e))
              + ")",
          "(assert (= M2 " + mant(e) + "))"));
    }

    out.append("""

        ; Section C: H14. The C's hx, its high word of |x| (lines 140-143).
        (define-fun c.hx ((b Int)) Int (jint.and.low (l2i (jlong.shr b 32)) 2147483648))
        """);
    for (long wb : WITNESSES) {
      out.append("\n; The witness " + hex(wb) + ".\n");
      int k = formulaCast(out, wb);
      long written = bits(asWritten(out, wb));
      long free = bits(reduced(out, wb));
      if (written == free) {
        throw new IllegalStateException("not a witness: " + hex(wb));
      }
      check(out, "at " + hex(wb) + ": k = 0 gives " + hex(written) + ", k = " + k + " another",
          "unsat", body("(assert (= " + lit(written) + " " + lit(free) + "))"));
    }
    for (long wb : CONTROLS) {
      out.append("\n; The control " + hex(wb) + ", the word's top double.\n");
      formulaCast(out, wb);
      long written = bits(asWritten(out, wb));
      long free = bits(reduced(out, wb));
      if (written != free) {
        throw new IllegalStateException("not a control: " + hex(wb));
      }
      check(out, "not a witness: at " + hex(wb) + " both give " + hex(written), "unsat",
          body("(assert (not (= " + lit(written) + " " + lit(free) + ")))"));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // expm1_k_zero_return.smt2: L11

  /**
   * {@code expm1_k_zero_return.smt2}, lemma L11: with {@code c = +0}, {@code fl(e - c)} is
   * {@code e} and {@code fl(v - c)} is {@code v}, so the k != 0 inner term is line 189's.
   */
  static String kZeroReturn() {
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append("""
        ; expm1's k == 0 return through the k != 0 inner term, with c = +0 (PROOFS.md, L11).
        ;
        ; s_expm1.c (reference/fdlibm-5.3) returns x - (x*e-hxs) when k == 0 (line 189) and
        ; starts from e = (x*(e-c)-c); e -= hxs when k != 0 (lines 191-192). The port (Expm1)
        ; computes E = fl(fl(fl(r*fl(e - c)) - c) - hxs) on every lane, where on the k == 0 lanes
        ; r = x and c = +0 (L10, expm1_reduction.smt2's section B). The claim: with c = +0,
        ; fl(e - c) = e and fl(v - c) = v for v = fl(r*e), so E = fl(fl(r*e) - hxs), line 189's
        ; inner term from the same operands.
        ;
        ; What is proven:
        ;   - Section A: the sign of an exact zero (IEEE 754 6.3), z.sub, z.add and z.mul,
        ;     pinned to the JVM over every pair of signed zeros. A zero e less +0 keeps its sign,
        ;     either sign; so does a zero v; and the product r*fl(e - (+0)) has the sign r*e has.
        ;     With c = -0 the step fails at e = -0, where e - c is +0: the (R3) step as stated,
        ;     for every e, needs c = +0. No lane has a zero e (below), so on the lanes this is
        ;     not the lemma's hinge.
        ;   - Section B: a nonzero normal double less +0 is itself: jdouble.near admits it for
        ;     itself in any binade, and no other double of its binade or the next ones, every
        ;     normal binade at once. That is e - (+0) = e and v - (+0) = v for normal e and v.
        ;     The hinge, H15, is that c is a zero: a double less one step of its binade is
        ;     another double, so with a nonzero c the two steps fail.
        ;
        ; What is not machine-checked:
        ;   - That e and v = fl(r*e) are normal or zero on the lanes, never subnormal: for
        ;     2^-54 <= |x| < 0.35, hxs is at least 2^-109 and e and r*e are of the order of x^2
        ;     and x^3 times constants near one, far above 2^-1022. Those bounds rest on the
        ;     polynomial and are sampled (ProofsTest.l11KZeroReturnIsTheInnerTermsForm).
        ;   - That c = +0 on those lanes: L10, whose step 3 is expm1_reduction.smt2's section B.
        ;   - That a value rounds to no double in a binade further off than the next one: the
        ;     arithmetic of rationals, as in sign_symmetry.smt2.
        ;   - That the port computes these forms, and line 189's last operation from them: the
        ;     code's text.
        ;
        ; Constants: none of Expm1's is used. The claim is about c alone; a nonzero c refutes
        ; it, and c = -0 its step at a zero e.
        ;
        ; The logic is ALL: section B's products S * M * sn are nonlinear.
        """);
    out.append(ProofFiles.rendered("ALL"));
    out.append("""

        ; Section A: the sign of an exact zero, true for -0. z.sub sx sy is the sign of x - y and
        ; z.add sx sy that of x + y for zeros x and y; z.mul is a zero product's.
        """);
    out.append(ProofFiles.ZERO_SIGNS);
    equations(out, "the zero signs are Java's", ProofFiles.zeroSignEquations());
    check(out, "a zero e less c = +0 is e, either sign; so a zero v less c is v", "unsat", body(
        "(declare-const se Bool)", "(assert (not (= (z.sub se false) se)))"));
    check(out, "a zero product r*(e - c) has the sign of r*e, c = +0, either signs", "unsat",
        body("(declare-const sr Bool) (declare-const se Bool)",
            "(assert (not (= (z.mul sr (z.sub se false)) (z.mul sr se))))"));
    check(out, "refuted: with c = -0, e - c is not e for some zero e", "sat", body(
        "(declare-const se Bool)", "(assert (not (= (z.sub se true) se)))"));
    check(out, "that zero is e = -0: -0 - (-0) is +0, and with c = +0 it stays -0", "unsat",
        body("(assert (not (and (not (z.sub true true)) (z.sub true false))))"));
    out.append("""

        ; Section B: a nonzero normal double less +0 is itself. The exact difference is the double
        ; S * M * 2^E, a / b with a = S * M * sn and b = sd.
        """);
    ProofFiles.nonzeroLessZero(out);
    return out.toString();
  }
}
