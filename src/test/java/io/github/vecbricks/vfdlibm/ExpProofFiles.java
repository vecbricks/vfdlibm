/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static io.github.vecbricks.vfdlibm.ProofFiles.check;
import static io.github.vecbricks.vfdlibm.ProofFiles.lit;
import static io.github.vecbricks.vfdlibm.ProofFiles.wrap;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The proofs of exp's lemmas ({@code PROOFS.md}, exp), rendered with the constants {@link Exp}
 * and {@link Lanes} hold, so a constant that moves without its proof fails {@link
 * ProofFilesTest}. Each method renders one file under {@code proofs/}; {@link ProofFiles#render}
 * lists them.
 */
final class ExpProofFiles {

  private ExpProofFiles() {
  }

  /** 2^k as an SMT-LIB literal. */
  private static String pow2(int k) {
    return lit(BigInteger.ONE.shiftLeft(k));
  }

  /** A hex literal of a long, for comments and echo lines. */
  private static String hex(long v) {
    return String.format("0x%08x_%08x", v >>> 32, v & Lanes.LOW_WORD);
  }

  private static String hex(int v) {
    return String.format("0x%08x", v);
  }

  /** The body lines of a check, each wrapped to the column limit. */
  private static List<String> body(String... lines) {
    var out = new ArrayList<String>();
    for (String l : lines) {
      out.addAll(wrap(l));
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // exp_highword_compare.smt2: L3

  /**
   * {@code exp_highword_compare.smt2}, lemma L3: each of the port's high-word compares, one signed
   * 64-bit compare of {@code bits & Long.MAX_VALUE}, is fdlibm's integer compare of {@code hx},
   * for every bit pattern of a double, NaNs of either sign included.
   */
  static String highwordCompare() {
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append("""
        ; exp's high-word compares are one signed 64-bit compare of |x|'s bits (PROOFS.md, L3).
        ;
        ; e_exp.c (reference/fdlibm-5.3) reads hx = __HI(x) & 0x7fffffff, the high word of |x|,
        ; and compares it with constant words: hx >= 0x40862E42 (the filter), hx >= 0x7ff00000
        ; (non-finite), hx > 0x3fd62e42 (reduced) and hx < 0x3e300000 (tiny). The port
        ; (Lanes.hiAtLeast, hiBelow, hiAbove) instead compares absBits = bits & Long.MAX_VALUE,
        ; as a signed long, with fromHiBits(h) - 1 or fromHiBits(h), fromHiBits(h) being
        ; (h & 0xffffffffL) << 32. The claim: for every long bits - every double, NaNs of either
        ; sign and every payload included - and every h in [0, 0x7fffffff], the port's compare
        ; and the C's agree; hiAbove(h) is hiAtLeast(h + 1) for h < 0x7fffffff.
        ;
        ; What is proven: the claim for symbolic bits and h, each Java operator as java.smt2
        ; defines it (java_check.smt2 pins them to the JVM, the masks used here among them); then
        ; the claim at each word Exp compares with, rendered from Exp, so a word moved out of the
        ; range fails here. The C's hx is unsigned and its words are int literals, compared
        ; unsigned; both lie in [0, 2^31), where that compare is the integer one. What is not:
        ; that __HI(x) is the high int of the raw bits is the prelude's reading of fdlibm's
        ; macro, and that Lanes and Exp call these compares with these words is the code's text.
        """);
    out.append(ProofFiles.rendered("QF_NIA"));
    out.append("""

        ; Lanes.absBits: bits & Long.MAX_VALUE, the low 63 bits.
        (define-fun abs.bits ((b Int)) Int (jlong.and.low b 9223372036854775808))
        ; e_exp.c lines 112 and 114: hx = __HI(x) & 0x7fffffff.
        (define-fun c.hx ((b Int)) Int (jint.and.low (l2i (jlong.shr b 32)) 2147483648))
        ; Lanes.fromHiBits: (h & 0xffffffffL) << 32, h an int widened to long.
        (define-fun from.hi ((h Int)) Int (jlong.shl (jlong.and.low (i2l h) 4294967296) 32))
        ; Lanes.hiAtLeast, hiBelow and hiAbove on absBits.
        (define-fun hi.at.least ((ab Int) (h Int)) Bool (> ab (jlong.sub (from.hi h) 1)))
        (define-fun hi.below ((ab Int) (h Int)) Bool (< ab (from.hi h)))
        (define-fun hi.above ((ab Int) (h Int)) Bool (hi.at.least ab (jint.add h 1)))
        (define-fun h.in ((h Int)) Bool (and (<= 0 h) (<= h 2147483647)))
        """);
    check(out, "absBits is non-negative and its high word is the C's hx, every long", "unsat",
        body("(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (not (and (<= 0 (abs.bits b))"
                + " (= (jlong.ushr (abs.bits b) 32) (c.hx b)))))"));
    check(out, "hiAtLeast(h) is hx >= h, every long and every h in [0, 0x7fffffff]", "unsat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h)))",
            "(assert (not (= (hi.at.least (abs.bits b) h) (>= (c.hx b) h))))"));
    check(out, "hiBelow(h) is hx < h, every long and every h in [0, 0x7fffffff]", "unsat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h)))",
            "(assert (not (= (hi.below (abs.bits b) h) (< (c.hx b) h))))"));
    check(out, "hiAbove(h) is hx > h, every long and every h in [0, 0x7fffffff)", "unsat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h) (< h 2147483647)))",
            "(assert (not (= (hi.above (abs.bits b) h) (> (c.hx b) h))))"));

    // The words Exp compares with, each in the range of the claim and the compare at it the C's.
    record Use(String name, int word, String port, String c, boolean above) {}
    var uses = List.of(
        new Use("HX_FILTER", Exp.HX_FILTER, "hi.at.least", ">=", false),
        new Use("HX_NON_FINITE", Exp.HX_NON_FINITE, "hi.at.least", ">=", false),
        new Use("HX_HALF_LN2", Exp.HX_HALF_LN2, "hi.above", ">", true),
        new Use("HX_TINY", Exp.HX_TINY, "hi.below", "<", false));
    for (Use u : uses) {
      String range = u.above() ? "(and (h.in h) (< h 2147483647))" : "(h.in h)";
      check(out, u.name() + " = " + hex(u.word()) + ": the port's compare is hx " + u.c() + " "
          + hex(u.word()), "unsat", body(
              "(declare-const b Int) (define-fun h () Int " + u.word() + ")",
              "(assert (jlong.in b))",
              "(assert (not (and " + range + " (= (" + u.port() + " (abs.bits b) h) (" + u.c()
                  + " (c.hx b) h)))))"));
    }
    check(out, "every NaN and infinity, either sign, is a filter and a non-finite lane", "unsat",
        body("(declare-const b Int)",
            "(assert (and (jlong.in b) (= (jdouble.fromBits.E b) 972)))",
            "(assert (not (and (hi.at.least (abs.bits b) " + Exp.HX_FILTER + ")"
                + " (hi.at.least (abs.bits b) " + Exp.HX_NON_FINITE + "))))"));

    out.append("""

        ; The claim rests on each part of the compare: with the comparand changed by one, with
        ; hiAbove's + 1 dropped, or on the raw bits with the sign left set, a double is told
        ; apart.
        """);
    check(out, "refuted: hiAtLeast with comparand fromHiBits(h), not minus one", "sat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h)))",
            "(assert (not (= (> (abs.bits b) (from.hi h)) (>= (c.hx b) h))))"));
    check(out, "refuted: hiBelow with comparand fromHiBits(h) - 1", "sat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h)))",
            "(assert (not (= (< (abs.bits b) (jlong.sub (from.hi h) 1)) (< (c.hx b) h))))"));
    check(out, "refuted: hiAbove(h) as hiAtLeast(h), the + 1 dropped", "sat",
        body("(declare-const b Int) (declare-const h Int)",
            "(assert (and (jlong.in b) (h.in h) (< h 2147483647)))",
            "(assert (not (= (hi.at.least (abs.bits b) h) (> (c.hx b) h))))"));
    long nan = 0xfff80000_00000000L;
    check(out, "refuted: the raw bits of the NaN " + hex(nan) + ", unmasked, miss the filter",
        "sat", body("(define-fun b () Int " + lit(nan) + ")",
            "(assert (and (>= (c.hx b) " + Exp.HX_FILTER + ")"
                + " (not (hi.at.least b " + Exp.HX_FILTER + "))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // exp_half_sign.smt2: L5

  /** A 64-bit bit-vector literal of a long. */
  private static String bv(long v) {
    return String.format("#x%016x", v);
  }

  /**
   * {@code exp_half_sign.smt2}, lemma L5: {@code (bits & Long.MIN_VALUE) | HALF_BITS} is the bits
   * of {@code halF[xsb]}, e_exp.c's table entry, for every double.
   */
  static String halfSign() {
    long h0 = Double.doubleToRawLongBits(0.5);
    long h1 = Double.doubleToRawLongBits(-0.5);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append("""
        ; exp's halF[xsb] is 0.5 with x's sign bit (PROOFS.md, L5).
        ;
        ; e_exp.c (reference/fdlibm-5.3) takes halF[xsb] from its table halF[2] = {0.5, -0.5}, with
        ; xsb = (hx >> 31) & 1 for the unsigned hx = __HI(x) (lines 112-113). The port (Exp)
        ; computes the double whose bits are (bits & Long.MIN_VALUE) | HALF_BITS. The claim: for
        ; every long bits, that is the raw bits of the table's entry at xsb, and they are the
        ; doubles 0.5 and -0.5.
        ;
        ; What is proven: & and | as SMT-LIB's bit-vector operators on the 64-bit two's-complement
        ; pattern of bits (int2bv), read back as a signed long, so no definition of & or | is
        ; written here; xsb as the C computes it, over java.smt2's int operators; and the two
        ; entries' S, M and E as java.smt2 reads raw bits (jdouble.fromBits, pinned to the JVM by
        ; java_check.smt2), with M * 2^E exactly 1/2. HALF_BITS is rendered from Exp, and the
        ; table's raw bits from Java's 0.5 and -0.5, the C's literals. What is not: that the port
        ; reinterprets those bits as the lane's double (DoubleVector's viewAsFloatingLanes) is the
        ; code's text.
        ;
        ; The logic is ALL, not QF_NIA: the file mixes bit-vectors with java.smt2's integers.
        """);
    out.append(ProofFiles.rendered("ALL"));
    out.append(("""

        ; The port's halF bits: (bits & Long.MIN_VALUE) | HALF_BITS over bit-vectors, read signed.
        (define-fun port.half ((b Int) (half (_ BitVec 64))) Int
          (let ((r (bvor (bvand ((_ int2bv 64) b) #x8000000000000000) half)))
            (- (bv2nat r) (ite (bvslt r #x0000000000000000) 18446744073709551616 0))))
        ; e_exp.c line 113: xsb = (hx >> 31) & 1, hx = __HI(x) unsigned, so the shift is >>>.
        (define-fun c.xsb ((b Int)) Int (jint.and.low (jint.ushr (l2i (jlong.shr b 32)) 31) 2))
        ; The table halF[2] = {0.5, -0.5}, as raw bits, at xsb.
        (define-fun c.half ((b Int)) Int (ite (= (c.xsb b) 1) %s %s))
        """).formatted(lit(h1), lit(h0)));
    check(out, "HALF_BITS = " + hex(Exp.HALF_BITS) + ": the port's bits are halF[xsb]'s, every"
        + " long", "unsat", body("(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (not (= (port.half b " + bv(Exp.HALF_BITS) + ") (c.half b))))"));
    check(out, "the C's xsb is 1 exactly when the bits are negative, the sign bit set", "unsat",
        body("(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (not (= (= (c.xsb b) 1) (< b 0))))"));
    check(out, "halF's entries are the doubles 0.5 and -0.5: S, M, E and M * 2^E = 1 / 2", "unsat",
        body("(assert (not (and",
            "  (= (jdouble.fromBits.S " + lit(h0) + ") 1) (= (jdouble.fromBits.S " + lit(h1)
                + ") (- 1))",
            "  (= (jdouble.fromBits.M " + lit(h0) + ") " + (1L << 52) + ")"
                + " (= (jdouble.fromBits.M " + lit(h1) + ") " + (1L << 52) + ")",
            "  (= (jdouble.fromBits.E " + lit(h0) + ") (- 53))"
                + " (= (jdouble.fromBits.E " + lit(h1) + ") (- 53))",
            "  (= (* 2 " + (1L << 52) + ") " + pow2(53) + "))))"));
    long neg = Double.doubleToRawLongBits(-1.0);
    long pos = Double.doubleToRawLongBits(1.0);
    check(out, "not vacuous: -1.0's bits give -0.5's and 1.0's give 0.5's", "sat",
        body("(assert (and (= (port.half " + lit(neg) + " " + bv(Exp.HALF_BITS) + ") " + lit(h1)
            + ")", "  (= (port.half " + lit(pos) + " " + bv(Exp.HALF_BITS) + ") " + lit(h0)
            + ")))"));
    out.append("""

        ; The constant changed by one, and the sign mask moved to the next bit down: some double
        ; tells each apart from the table.
        """);
    check(out, "refuted: HALF_BITS + 1 = " + hex(Exp.HALF_BITS + 1) + " as the constant", "sat",
        body("(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (not (= (port.half b " + bv(Exp.HALF_BITS + 1) + ") (c.half b))))"));
    check(out, "refuted: the mask 0x40000000_00000000, not the sign bit", "sat",
        body("(declare-const b Int)", "(assert (jlong.in b))",
            "(define-fun r () (_ BitVec 64) (bvor (bvand ((_ int2bv 64) b) #x4000000000000000) "
                + bv(Exp.HALF_BITS) + "))",
            "(assert (not (= (- (bv2nat r) (ite (bvslt r #x0000000000000000)"
                + " 18446744073709551616 0)) (c.half b))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Doubles as S, M and a binade, for the proofs below

  private static final long P52 = 1L << 52;

  /** The M of a normal double: its 52 stored bits under the implicit 2^52. */
  private static long mant(double d) {
    return (Double.doubleToRawLongBits(d) & (P52 - 1)) | P52;
  }

  /** The binade of a normal double: j with 2^j <= |d| < 2^(j+1). */
  private static int binade(double d) {
    return Math.getExponent(d);
  }

  /**
   * {@code (jdouble.near a b S M sn sd)}: {@code a / b} rounds to {@code S * M * 2^(j - 52)}, the
   * double of binade {@code j} with sign {@code s} and significand {@code m}, each a term.
   */
  private static String near(String a, String b, String s, String m, int j) {
    int unit = j - 52;
    String scale = pow2(Math.abs(unit));
    return "(jdouble.near " + a + " " + b + " " + s + " " + m + " " + (unit >= 0 ? scale : "1")
        + " " + (unit >= 0 ? "1" : scale) + ")";
  }

  /** {@code -t} for a term, {@code t} for sign 1. */
  private static String signed(int sign, String t) {
    return sign > 0 ? t : "(- " + t + ")";
  }

  private static String sign(int sign) {
    return sign > 0 ? "1" : "(- 1)";
  }

  /**
   * That the exact double {@code s * m * 2^(j - 52)}, as a rational over a power of two, rounds
   * to no double but itself in its binade and in the binades on either side, either sign: the
   * disjunction an {@code unsat} check refutes, over declared {@code S2} and {@code M2}.
   */
  private static String roundsToOther(String a, String b, int s, String m, int j) {
    return "(or (and " + near(a, b, "S2", "M2", j) + " (not (and (= S2 " + sign(s) + ") (= M2 "
        + m + "))))\n  " + near(a, b, "S2", "M2", j - 1) + "\n  " + near(a, b, "S2", "M2", j + 1)
        + ")";
  }

  // ---------------------------------------------------------------------------------------------
  // Ground evaluation: one operation on two doubles at a time

  /**
   * A computation evaluated one rounding at a time: each {@link #op} computes the result in Java
   * and renders a check that the exact result of the operation on the two doubles rounds to that
   * double, of its sign and binade, and to no other double in that binade or the two next to it.
   * The operands are the doubles as Java holds them, so each check is ground and linear; a chain
   * of them evaluates a formula with every intermediate double checked rather than trusted.
   */
  private static final class Chain {
    private final StringBuilder out;
    private final String at;

    Chain(StringBuilder out, String at) {
      this.out = out;
      this.at = at;
    }

    double op(String name, double a, char op, double b) {
      double q = switch (op) {
        case '+' -> a + b;
        case '-' -> a - b;
        case '*' -> a * b;
        case '/' -> a / b;
        default -> throw new IllegalArgumentException("no such operation: " + op);
      };
      if (q == 0 || !Double.isFinite(q) || Math.abs(q) < Double.MIN_NORMAL) {
        throw new IllegalArgumentException(name + " is not a normal double: " + q);
      }
      ProofFiles.Exact x = ProofFiles.Exact.of(a);
      ProofFiles.Exact y = ProofFiles.Exact.of(b);
      BigInteger num = switch (op) {
        case '+' -> x.num().multiply(y.den()).add(y.num().multiply(x.den()));
        case '-' -> x.num().multiply(y.den()).subtract(y.num().multiply(x.den()));
        case '*' -> x.num().multiply(y.num());
        default -> x.num().multiply(y.den());
      };
      BigInteger den = op == '/' ? x.den().multiply(y.num()) : x.den().multiply(y.den());
      if (den.signum() < 0) {
        num = num.negate();
        den = den.negate();
      }
      int s = q < 0 ? -1 : 1;
      String m = Long.toString(mant(q));
      int j = binade(q);
      check(out, at + ": " + name + " = " + hex(Double.doubleToRawLongBits(q)), "unsat",
          body("(declare-const S2 Int) (declare-const M2 Int)",
              "(assert (or (not " + near(lit(num), lit(den), sign(s), m, j) + ")",
              "  " + roundsToOther(lit(num), lit(den), s, m, j) + "))"));
      return q;
    }

    /**
     * e_exp.c's lines 145-146 and the port's tail, on r: t = r*r, c = r - t*(P1+t*(P2+t*(P3+
     * t*(P4+t*P5)))), then 2.0 - c and the quotient q = (r*c)/(2.0-c); returns c and q.
     */
    double[] cAndQ(double r) {
      double t = op("t = r*r", r, '*', r);
      double p = op("t*P5", t, '*', Exp.P5);
      p = op("P4+t*P5", p, '+', Exp.P4);
      p = op("t*(P4+t*P5)", t, '*', p);
      p = op("P3+t*(...)", p, '+', Exp.P3);
      p = op("t*(P3+...)", t, '*', p);
      p = op("P2+t*(...)", p, '+', Exp.P2);
      p = op("t*(P2+...)", t, '*', p);
      p = op("P1+t*(...)", p, '+', Exp.P1);
      p = op("t*(P1+...)", t, '*', p);
      double c = op("c = r - t*(...)", r, '-', p);
      double rc = op("r*c", r, '*', c);
      double d = op("2.0-c", 2.0, '-', c);
      double q = op("q = (r*c)/(2.0-c)", rc, '/', d);
      return new double[] {c, q};
    }
  }

  // ---------------------------------------------------------------------------------------------
  // exp_near_band.smt2: L2

  /**
   * {@code exp_near_band.smt2}, lemma L2: on the C's near band the far form's {@code k} is
   * {@code +-1}, for every double of the band, and its {@code t*ln2HI[0]} and {@code t*ln2LO[0]}
   * are the near form's {@code ln2HI[xsb]} and {@code ln2LO[xsb]}.
   */
  static String nearBand() {
    double x1 = Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_HALF_LN2 + 1));
    double x2 = Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_THREE_HALVES_LN2) - 1);
    long mi = mant(Exp.INVLN2);
    int ji = binade(Exp.INVLN2);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; On exp's near band the far form is the near form (PROOFS.md, L2).
        ;
        ; e_exp.c (reference/fdlibm-5.3) reduces 0.5 ln2 < |x| < 1.5 ln2, hx in [0x%x,
        ; 0x%x], by its near form, k = 1 - 2*xsb, hi = x - ln2HI[xsb], lo = ln2LO[xsb]
        ; (line 130), and the port (Exp) by the far form for every lane: k = (int) s with
        ; s = fl(fl(invln2 * x) + halF[xsb]), t = k, hi = x - fl(t * ln2HI[0]),
        ; lo = fl(t * ln2LO[0]). The band is X1 <= |x| <= X2, X1 = %s and
        ; X2 = %s, rendered from Exp's words; invln2, ln2HI and ln2LO from Exp.
        ;
        ; What is proven, for every double of the band of either sign, with no appeal to
        ; monotonicity: x is S * Mx * 2^(j - 52) in one of the band's binades, with Mx symbolic
        ; and bounded by X1 and X2 in the end binades; invln2 * x is the exact rational
        ; S * M(invln2) * Mx / 2^n, which rounds into the binades listed for it and no other next
        ; to them; then s, the sum with halF[xsb] = S / 2 (exp_half_sign.smt2), rounds into binade
        ; 2^0 with sign S and into no binade next to it, so (int) s = S, the near form's k. Every
        ; binade pair is reached by some x, or the check says it is not. And t * ln2HI[0] for
        ; t = +-1 is exact, the double +-ln2HI[0], whose bits for t = -1 are ln2HI[1]'s, and
        ; likewise for ln2LO: so hi and lo are the near form's operations on the same operands.
        ;
        ; The unsat checks hold for every double jdouble.near admits for a value, so they need
        ; no proof that it admits only one; the sat checks at the band's ends name the binade a
        ; value rounds into, and rest for that on java_check.smt2, which refuses both neighbours
        ; of every double it checks.
        ;
        ; What is not: that a value rounds to no double in a binade further off than the next
        ; one rests on the arithmetic of rationals (a double of binade j is rounded to only from
        ; within half its step of [2^j, 2^(j+1)), and the values here lie in [0.5, 2)); and (int)
        ; truncating toward zero (JLS 5.1.3, D2I), I2D of +-1 being exact and both forms then
        ; sharing every later operation are the code's text.
        ;
        ; The logic is ALL, not QF_NIA: under QF_NIA cvc5 answers unknown to some checks, and
        ; under ALL both solvers decide the file in well under a second.
        """).formatted(Exp.HX_HALF_LN2 + 1, Exp.HX_THREE_HALVES_LN2 - 1, hex(
            Double.doubleToRawLongBits(x1)), hex(Double.doubleToRawLongBits(x2))));
    out.append(ProofFiles.rendered("ALL"));

    int jpLo = binade(x1 * Exp.INVLN2);
    int jpHi = binade(x2 * Exp.INVLN2);
    for (int s : new int[] {1, -1}) {
      String sx = s > 0 ? "x > 0" : "x < 0";
      for (int jx = binade(x1); jx <= binade(x2); jx++) {
        long lo = jx == binade(x1) ? mant(x1) : P52;
        long hi = jx == binade(x2) ? mant(x2) : 2 * P52 - 1;
        String mx = "(and (<= " + lo + " Mx) (<= Mx " + hi + "))";
        String prodA = "(* " + lit(s * mi) + " Mx)";
        String prodB = pow2(52 - ji + 52 - jx);
        out.append("\n; " + sx + " in binade 2^" + jx + ": Mx in [" + lo + ", " + hi + "].\n");
        var others = new ArrayList<String>();
        for (int jp = jpLo - 1; jp <= jpHi + 1; jp++) {
          if (jp < jpLo || jp > jpHi) {
            others.add(near(prodA, prodB, "S2", "M2", jp));
          } else {
            others.add(near(prodA, prodB, sign(-s), "M2", jp));
          }
        }
        check(out, sx + ", binade 2^" + jx + ": invln2 * x rounds only into binades 2^" + jpLo
            + " to 2^" + jpHi + ", sign S", "unsat", body(
                "(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)",
                "(assert " + mx + ")", "(assert (or " + String.join("\n  ", others) + "))"));
        for (int jp = jpLo; jp <= jpHi; jp++) {
          String sumA = signed(s, "(+ P " + pow2(51 - jp) + ")");
          String sumB = pow2(52 - jp);
          String inP = near(prodA, prodB, sign(s), "P", jp);
          String what = sx + " in 2^" + jx + ", invln2 * x in 2^" + jp;
          check(out, what + ": s is in 2^0, sign S, and (int) s = S", "unsat", body(
              "(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int)"
                  + " (declare-const M2 Int)",
              "(assert " + mx + ")", "(assert " + inP + ")",
              "(assert (or " + near(sumA, sumB, "S2", "M2", -1),
              "  " + near(sumA, sumB, "S2", "M2", 1),
              "  " + near(sumA, sumB, sign(-s), "M2", 0),
              "  (and " + near(sumA, sumB, sign(s), "M2", 0) + " (not (= (div M2 " + P52
                  + ") 1)))))"));
          double xa = Math.scalb(1.0, jx);
          double xb = Math.nextDown(Math.scalb(1.0, jx + 1));
          xa = Math.max(xa, x1);
          xb = Math.min(xb, x2);
          boolean reached = binade(xa * Exp.INVLN2) <= jp && jp <= binade(xb * Exp.INVLN2);
          check(out, what + (reached ? ": reached" : ": not reached"), reached ? "sat" : "unsat",
              body("(declare-const Mx Int) (declare-const P Int)", "(assert " + mx + ")",
                  "(assert " + inP + ")"));
        }
      }
    }

    out.append("""

        ; The band's ends are its own: at both boundary doubles, either sign, the chain above is
        ; satisfiable with s in binade 2^0. Then the band widened by a word at either end is
        ; refuted: at the next word down s rounds below 1, so k = 0, and at the top of the next
        ; word up it rounds to 2 or more, so k = 2 - neither the near form's k.
        """);
    record End(String what, double x, int js, String expect) {}
    var ends = List.of(new End("X1, the band's bottom", x1, 0, "sat"),
        new End("X2, the band's top", x2, 0, "sat"),
        new End("refuted: the word below the band, " + hex(Exp.HX_HALF_LN2) + "_00000000,", Double
            .longBitsToDouble(Lanes.fromHiBits(Exp.HX_HALF_LN2)), -1, "sat"),
        new End("refuted: the word above, " + hex(Lanes.fromHiBits(Exp.HX_THREE_HALVES_LN2 + 1)
            - 1) + ",", Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_THREE_HALVES_LN2 + 1)
                - 1), 1, "sat"));
    for (End e : ends) {
      for (int s : new int[] {1, -1}) {
        int jx = binade(e.x());
        int jp = binade(e.x() * Exp.INVLN2);
        String prodA = lit(s * mi * 1) + " " + mant(e.x());
        String sumA = signed(s, "(+ P " + pow2(51 - jp) + ")");
        check(out, e.what() + (s > 0 ? " +" : " -") + ": s rounds into 2^" + e.js(), e.expect(),
            body("(declare-const P Int) (declare-const M2 Int)",
                "(assert " + near("(* " + prodA + ")", pow2(52 - ji + 52 - jx), sign(s), "P", jp)
                    + ")",
                "(assert " + near(sumA, pow2(52 - jp), sign(s), "M2", e.js()) + ")"));
      }
    }

    out.append("""

        ; t * ln2HI[0] and t * ln2LO[0] for t = +-1: the exact product is the double +-c itself,
        ; which rounds to no other double, and the one t = -1 gives has the bits of the source's
        ; ln2HI[1] or ln2LO[1] (Exp's LN2HI_1, LN2LO_1).
        """);
    record Pair(String name, double c0, double c1) {}
    for (Pair p : List.of(new Pair("ln2HI", Exp.LN2HI_0, Exp.LN2HI_1),
        new Pair("ln2LO", Exp.LN2LO_0, Exp.LN2LO_1))) {
      long m = mant(p.c0());
      int j = binade(p.c0());
      String b = pow2(52 - j);
      for (int s : new int[] {1, -1}) {
        check(out, (s > 0 ? "+1" : "-1") + " * " + p.name() + "[0] rounds to "
            + (s > 0 ? "itself" : "its negation") + " only", "unsat", body(
                "(declare-const S2 Int) (declare-const M2 Int)",
                "(assert " + roundsToOther(lit(s * m), b, s, Long.toString(m), j) + ")"));
      }
      long raw1 = Double.doubleToRawLongBits(p.c1());
      check(out, p.name() + "[1] = " + hex(raw1) + " is -" + p.name() + "[0]: its S, M, E",
          "unsat", body("(assert (not (and (= (jdouble.fromBits.S " + lit(raw1) + ") (- 1))",
              "  (= (jdouble.fromBits.M " + lit(raw1) + ") " + m + ")",
              "  (= (jdouble.fromBits.E " + lit(raw1) + ") " + lit(j - 52) + "))))"));
      check(out, "refuted: " + p.name() + "[1] with M one more", "sat", body(
          "(assert (not (= (jdouble.fromBits.M " + lit(raw1 + 1) + ") " + m + ")))"));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // exp_zero_reduction.smt2: L1

  /**
   * The binades {@code exp_zero_reduction.smt2} proves "a double less +0, or +0 less a double, is
   * that double or its negation" in: from q's lowest, {@code 2^-60}, below H3's informal bound
   * {@code q > 0.94 * 2^-56 / 2.37 > 2^-59}, to the domain's top binade, 2^-2.
   */
  private static final int ZERO_REDUCTION_LOWEST = -60;

  /**
   * {@code exp_zero_reduction.smt2}, lemma L1: the hinges of the k == 0 path that are IEEE facts
   * rather than bounds of the polynomial - x - (+0) = x, +0 - q = -q, the scale-back adding 0 -
   * and H1's witness, that the formula alone would give k = 1 at the top of the domain.
   */
  static String zeroReduction() {
    double bottom = Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_TINY));
    double top = Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_HALF_LN2 + 1) - 1);
    int jBottom = binade(bottom);
    int jTop = binade(top);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; exp's k == 0 path is the general tail with hi = x, lo = +0, k = 0 (PROOFS.md, L1): the
        ; hinges that are IEEE facts, not bounds on the polynomial.
        ;
        ; e_exp.c (reference/fdlibm-5.3) returns one-((x*c)/(c-2.0)-x) for hx in [0x%x,
        ; 0x%x] (line 147), and the port (Exp) computes the general tail there with k = 0 and
        ; t = +0: hi = x - fl(t*ln2HI[0]), lo = fl(t*ln2LO[0]), r = hi - lo,
        ; y = one-((lo-(r*c)/(2.0-c))-hi), and y's bits plus k << 52. The domain's ends,
        ; %s and %s, are rendered from Exp's words.
        ;
        ; What is proven:
        ;   - Section A: a nonzero double less +0 is itself and +0 less a double q is -q, in
        ;     the PROOFS.md steps 1 and 3: the exact value is the double (or its negation),
        ;     which jdouble.near admits in every binade at once, and admits no other double in
        ;     its own binade or the ones next to it, for every double, either sign, of the binades
        ;     2^%d to 2^%d. Those cover the domain's x, binades 2^%d to 2^%d (pinned below from
        ;     Exp's words), and q, whose binades from 2^-59 to 2^-4 follow from H2 and H3's bounds.
        ;   - Section B: the scale-back adds 0 << 52 = 0 to y's bits, for every long, and k = 0
        ;     is not below Exp's K_MIN_NORMAL, so neither the subnormal scale-back nor the finish
        ;     takes the lane (step 4); with k = 1 the bits would move.
        ;   - Section C: H1's witness. At the domain's top double the formula's
        ;     s = fl(fl(invln2 * x) + 0.5) rounds into binade 2^0, so (int) s = 1, not 0: the
        ;     high-word test, not the formula, forces k = 0 (exp_highword_compare.smt2 proves
        ;     that test).
        ;   - Section D: the domain widened by a word is refuted. At one double of the next word
        ;     up per sign, every rounding of the k == 0 tail and of the C's near form is
        ;     evaluated, and the two results differ.
        ;
        ; What is not machine-checked:
        ;   - H2 and H3, the bounds 0.94|x| <= |c| < 0.37 with c of x's sign, and q positive and
        ;     normal: they bound the polynomial's eight roundings, products of unknowns, and stay
        ;     the informal proof of PROOFS.md with ProofsTest's samples. So q's binades rest on
        ;     them, and so does step 2's hypothesis, c in [-2, 1], under which sign_symmetry.smt2
        ;     proves fl(c - 2.0) = -fl(2.0 - c) and the quotient's sign symmetry.
        ;   - fl(+0 * v) = +0 for a positive finite v, IEEE 754's sign rule for an exact zero
        ;     (section 6.3): zero is not in the prelude. Section A checks only that ln2HI[0] and
        ;     ln2LO[0] are positive and finite, which the rule needs.
        ;   - That D2I of +0.0 is 0 and I2D of 0 is +0.0 (JLS 5.1.3, 5.1.2), that the port puts
        ;     +0.0 in the cast's argument exactly on the lanes the high-word test leaves out, and
        ;     that the C's steps after these are the port's on the same operands: the code's text.
        ;   - That jdouble.near admits no double in a binade further off than the next one: the
        ;     arithmetic of rationals, as in sign_symmetry.smt2.
        ;
        ; The logic is ALL, not QF_NIA: under QF_NIA cvc5 answers unknown to some of section A's
        ; per-binade checks (2^-58, 2^-55, ...), and under ALL it decides the file in a second.
        ; Section C's and D's checks carry their own non-vacuity: each asserts that the named
        ; double is not admitted, or another one is, so a jdouble.near that admitted nothing
        ; would make it sat.
        """).formatted(Exp.HX_TINY, Exp.HX_HALF_LN2,
            hex(Double.doubleToRawLongBits(bottom)), hex(Double.doubleToRawLongBits(top)),
            ZERO_REDUCTION_LOWEST, jTop, jBottom, jTop));
    out.append(ProofFiles.rendered("ALL"));

    out.append("""

        ; Section A: x - (+0) = x and +0 - q = -q. The exact difference is the double S * M * 2^E
        ; or its negation, a / b with a = S * M * sn and b = sd.
        """);
    check(out, "a double, either sign, is a double jdouble.near admits for itself, any binade",
        "unsat", body("(declare-const S Int) (declare-const M Int)",
            "(declare-const sn Int) (declare-const sd Int)",
            "(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))",
            "(assert (not (jdouble.near (* S M sn) sd S M sn sd)))"));
    for (int j = ZERO_REDUCTION_LOWEST; j <= jTop; j++) {
      String a = "(ite (= S 1) M (- M))";
      String b = pow2(52 - j);
      check(out, "a double of binade 2^" + j + ", either sign, rounds to no other double",
          "unsat", body("(declare-const S Int) (declare-const M Int)",
              "(declare-const S2 Int) (declare-const M2 Int)",
              "(assert (and (jdouble.S.in S) (jdouble.M.in M)))",
              "(assert (or (and " + near(a, b, "S2", "M2", j)
                  + " (not (and (= S2 S) (= M2 M))))",
              "  " + near(a, b, "S2", "M2", j - 1),
              "  " + near(a, b, "S2", "M2", j + 1) + "))"));
    }
    long botBits = Double.doubleToRawLongBits(bottom);
    long topBits = Double.doubleToRawLongBits(top);
    check(out, "the domain's ends " + hex(botBits) + ", " + hex(topBits) + " are in 2^" + jBottom
        + ", 2^" + jTop, "unsat", body(
            "(assert (not (and (= (jdouble.fromBits.E " + lit(botBits) + ") " + lit(jBottom - 52)
                + ")",
            "  (= (jdouble.fromBits.E " + lit(topBits) + ") " + lit(jTop - 52) + "))))"));
    long hiBits = Double.doubleToRawLongBits(Exp.LN2HI_0);
    long loBits = Double.doubleToRawLongBits(Exp.LN2LO_0);
    check(out, "ln2HI[0] and ln2LO[0] are positive and finite, as the zero product needs", "unsat",
        body("(assert (not (and (= (jdouble.fromBits.S " + lit(hiBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(hiBits) + ") 972)",
            "  (= (jdouble.fromBits.S " + lit(loBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(loBits) + ") 972))))"));
    check(out, "not vacuous: the double 1.5 * 2^" + jTop + " admits itself in its binade", "sat",
        body("(declare-const M2 Int)",
            "(assert " + near(lit(3 * (P52 / 2)), pow2(52 - jTop), "1", "M2", jTop) + ")"));
    check(out, "refuted: a double less 2^" + (jTop - 52) + ", one step of its binade, is itself",
        "sat", body("(declare-const M Int) (declare-const M2 Int)",
            "(assert (jdouble.M.in M))",
            "(assert " + near("(- M 1)", pow2(52 - jTop), "1", "M2", jTop) + ")",
            "(assert (not (= M2 M)))"));

    out.append("""

        ; Section B: the scale-back with k = 0.
        """);
    check(out, "y's bits plus 0 << 52 are y's bits, every long", "unsat", body(
        "(declare-const y Int)", "(assert (jlong.in y))",
        "(assert (not (= (jlong.add y (jlong.shl 0 52)) y)))"));
    check(out, "k = 0 is not below K_MIN_NORMAL = " + Exp.K_MIN_NORMAL + ", the scale-back's"
        + " plain path", "unsat", body("(assert (< 0 " + lit(Exp.K_MIN_NORMAL) + "))"));
    check(out, "refuted: with k = 1 the scale-back moves some y's bits", "sat", body(
        "(declare-const y Int)", "(assert (jlong.in y))",
        "(assert (not (= (jlong.add y (jlong.shl 1 52)) y)))"));

    out.append("""

        ; Section C: H1's witness, at the domain's top double x: the formula's k is 1. The
        ; product rounds into its binade (or its neighbours, which are refused), then the sum
        ; with 0.5 rounds into binade 2^0, and not below it.
        """);
    long mi = mant(Exp.INVLN2);
    int ji = binade(Exp.INVLN2);
    int jp = binade(top * Exp.INVLN2);
    String prodA = "(* " + mi + " " + mant(top) + ")";
    String prodB = pow2(52 - ji + 52 - jTop);
    String sumA = "(+ P " + pow2(51 - jp) + ")";
    String sumB = pow2(52 - jp);
    check(out, "at the top, invln2 * x rounds into 2^" + jp + " only, positive", "unsat", body(
        "(declare-const S2 Int) (declare-const M2 Int)",
        "(assert (or " + near(prodA, prodB, "S2", "M2", jp - 1),
        "  " + near(prodA, prodB, "S2", "M2", jp + 1),
        "  " + near(prodA, prodB, "(- 1)", "M2", jp) + "))"));
    check(out, "at the top, s rounds into 2^0, so (int) s = 1, and not into 2^-1, k = 0", "unsat",
        body("(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)",
            "(assert " + near(prodA, prodB, "1", "P", jp) + ")",
            "(assert (or " + near(sumA, sumB, "S2", "M2", -1),
            "  " + near(sumA, sumB, "S2", "M2", 1),
            "  " + near(sumA, sumB, "(- 1)", "M2", 0) + "))"));
    check(out, "not vacuous: at the top, s rounds into 2^0", "sat", body(
        "(declare-const P Int) (declare-const M2 Int)",
        "(assert " + near(prodA, prodB, "1", "P", jp) + ")",
        "(assert " + near(sumA, sumB, "1", "M2", 0) + ")"));
    out.append("""

        ; Section D: the domain widened by a word is refuted. At the next word up, 0x%x, the C
        ; reduces (its near form, k = +-1), and there the k == 0 tail gives another double: at
        ; the two first witnesses ProofsTest found, one per sign, each rounding of both
        ; computations is evaluated, then the C's scale-back, and the two results compared.
        """.formatted(Exp.HX_HALF_LN2 + 1));
    for (long wb : new long[] {Lanes.fromHiBits(Exp.HX_HALF_LN2 + 1) + 4,
        Lanes.fromHiBits(Exp.HX_HALF_LN2 + 1) + 0x1f | Long.MIN_VALUE}) {
      double x = Double.longBitsToDouble(wb);
      int xsb = x < 0 ? 1 : 0;
      var tail = new Chain(out, "k = 0 tail at " + hex(wb));
      double r0 = tail.op("r = x - (+0)", x, '-', 0.0);
      double q0 = tail.cAndQ(r0)[1];
      double u0 = tail.op("+0 - q", 0.0, '-', q0);
      double y0 = tail.op("y", 1.0, '-', tail.op("(+0 - q) - x", u0, '-', r0));
      var c = new Chain(out, "the C at " + hex(wb));
      double hi = c.op("hi = x - ln2HI[xsb]", x, '-', xsb == 0 ? Exp.LN2HI_0 : Exp.LN2HI_1);
      double lo = xsb == 0 ? Exp.LN2LO_0 : Exp.LN2LO_1;
      double r1 = c.op("r = hi - lo", hi, '-', lo);
      double q1 = c.cAndQ(r1)[1];
      double u1 = c.op("lo - q", lo, '-', q1);
      double y1 = c.op("y", 1.0, '-', c.op("(lo - q) - hi", u1, '-', hi));
      int k = 1 - 2 * xsb;
      long port = Double.doubleToRawLongBits(y0);
      long cBits = Double.doubleToRawLongBits(y1);
      check(out, "refuted at " + hex(wb) + ": the k = 0 tail is not the C's y + (" + k
          + " << 52)", "unsat", body("(assert (= " + lit(port) + " (jlong.add " + lit(cBits)
              + " (jlong.shl " + lit(k) + " 52))))"));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // exp_tiny_counterexample.smt2: L4

  /**
   * {@code exp_tiny_counterexample.smt2}, lemma L4: at {@code x = 5 * 2^-53} the C's {@code one +
   * x} and the general tail with k = 0 are different doubles, each rounding of both evaluated;
   * at {@code 4 * 2^-53} they agree, so the witness is not an accident of the chain.
   */
  static String tinyCounterexample() {
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append("""
        ; exp's tiny lanes cannot be folded into the k == 0 tail: a counter-example (PROOFS.md,
        ; L4).
        ;
        ; e_exp.c (reference/fdlibm-5.3) returns one + x for |x| < 2^-28 (line 140). Folding
        ; those lanes into L1's tail, with k = 0, hi = x and lo = +0, would compute
        ; one - ((+0 - q) - x). The claim: at x = 5 * 2^-53 the two are different doubles; one + x
        ; is a tie that rounds to even, and the tail adds q first and rounds up past it.
        ;
        ; What is proven: each rounding of both computations, evaluated: for every operation, the
        ; exact result of the operation on the two doubles Java computed before it rounds to the
        ; double Java computed, and to no other double in that binade or the binades next to it;
        ; then the two results differ. The tie in one + x is one of those checks. At x =
        ; 4 * 2^-53, the witness moved by one unit, both computations give the same double. The
        ; constants P1..P5 are rendered from Exp. What is not: the subtraction from +0 of a
        ; nonzero q giving exactly -q is the same as any other rounding here, but that
        ; StrictMath.exp is one + x at the witness is ProofsTest's, since the oracle is not in a
        ; solver; and a double further off than the next binade is excluded by the arithmetic of
        ; rationals, as in sign_symmetry.smt2. Each check carries its own non-vacuity: it asserts
        ; that the double Java computed is not admitted, or another one is, so a jdouble.near that
        ; admitted nothing would make it sat.
        """);
    out.append(ProofFiles.rendered("QF_NIA"));
    long tinyX = Double.doubleToRawLongBits(5 * 0x1p-53);
    check(out, "x = " + hex(tinyX) + " is a tiny lane: hx < HX_TINY = " + hex(Exp.HX_TINY),
        "unsat", body("(assert (not (< (jint.and.low (l2i (jlong.shr " + lit(tinyX)
            + " 32)) 2147483648) " + Exp.HX_TINY + ")))"));
    for (int m : new int[] {5, 4}) {
      double x = m * 0x1p-53;
      String at = m + "*2^-53";
      out.append("\n; x = " + at + ".\n");
      var c = new Chain(out, "one + x at " + at);
      double one = c.op("one + x", 1.0, '+', x);
      var tail = new Chain(out, "tail at " + at);
      double q = tail.cAndQ(x)[1];
      double u = tail.op("+0 - q", 0.0, '-', q);
      double y = tail.op("y", 1.0, '-', tail.op("(+0 - q) - x", u, '-', x));
      long a = Double.doubleToRawLongBits(one);
      long b = Double.doubleToRawLongBits(y);
      if (m == 5) {
        check(out, at + ": one + x, " + hex(a) + ", is not the tail, " + hex(b),
            "unsat", body("(assert (= " + lit(a) + " " + lit(b) + "))"));
      } else {
        check(out, "not a witness: at " + at + ", one + x and the tail are both " + hex(a),
            "unsat", body("(assert (not (= " + lit(a) + " " + lit(b) + ")))"));
      }
    }
    return out.toString();
  }
}
