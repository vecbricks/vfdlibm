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
import static io.github.vecbricks.vfdlibm.ProofFiles.check;
import static io.github.vecbricks.vfdlibm.ProofFiles.lit;

import java.util.ArrayList;
import java.util.List;

/**
 * The proofs of log's lemmas ({@code PROOFS.md}, log, L6 to L9), rendered with the constants
 * {@link Log} holds, so a constant that moves without its proof fails {@link ProofFilesTest}.
 * Each method renders one file under {@code proofs/}; {@link ProofFiles#render} lists them.
 */
final class LogProofFiles {

  private LogProofFiles() {
  }

  private static final long P52 = 1L << 52;

  // ---------------------------------------------------------------------------------------------
  // log_zero_dk.smt2: L6

  /** The lowest binade log_zero_dk.smt2 proves "B - (+0) is B, +0 - v is -v" in. */
  private static final int ZERO_DK_LOWEST = -110;
  /** And the highest. */
  private static final int ZERO_DK_HIGHEST = -1;

  /** An SMT-LIB boolean literal. */
  private static String bool(boolean b) {
    return b ? "true" : "false";
  }

  /** Whether a double's sign bit is set: a zero's sign as the file's booleans hold it. */
  private static boolean negative(double d) {
    return Double.doubleToRawLongBits(d) < 0;
  }

  /**
   * {@code log_zero_dk.smt2}, lemma L6: the k != 0 forms with dk = +0 are the k == 0 forms. The
   * signs of exact zeros, pinned to the JVM over every pair of signed zeros, carry the zero cases;
   * the nonzero case is round to nearest's sign symmetry (sign_symmetry.smt2) and the exactness
   * of B - (+0) and +0 - v, checked here per binade.
   */
  static String zeroDk() {
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's k == 0 forms are its k != 0 forms with dk = +0 (PROOFS.md, L6).
        ;
        ; e_log.c (reference/fdlibm-5.3) returns f-B when k == 0 and
        ; dk*ln2_hi-((B-dk*ln2_lo)-f) when k != 0, for B = s*(f-R) (lines 136-137) and B = R
        ; (lines 118-119); for the band (lines 133-134) the k != 0 form adds dk*ln2_lo to
        ; s*(hfsq+R) inside B = hfsq-s*(hfsq+R); and for f == 0 it returns zero against
        ; dk*ln2_hi+dk*ln2_lo (lines 115-116). The port (Log) computes the k != 0 forms on every
        ; lane, with dk = +0 where k == 0. The claim: fl(+0 - fl(fl(B - (+0)) - f)) = fl(f - B)
        ; for every double B and every finite f that is not -0, and the band's and f == 0's forms
        ; likewise.
        ;
        ; What is proven:
        ;   - Section A: the sign of an exact zero (IEEE 754 6.3), as three functions of the
        ;     operands' sign bits, z.sub, z.add and z.mul, each pinned to the JVM over all four
        ;     pairs of signed zeros, and the product of +0 with ln2_hi and ln2_lo (rendered from
        ;     Java). Over them, every case where B - f is exactly zero: B = f nonzero, whose
        ;     difference is +0 under the same rule (pinned at sampled doubles), or B and f zeros
        ;     of any signs with f not -0: the fold's sign is the C's. With f = -0 allowed, the
        ;     solver finds B = +0, f = -0, where they differ (the hinge, H9). The band's step, hfsq
        ;     - (shr + (+0)) against hfsq - shr for a zero shr and the zero hfsq a zero f gives,
        ;     either sign (H10); and +0 + +0 = +0, the f == 0 form.
        ;   - Section B: the nonzero case. That fl(B - f) = -fl(f - B) is sign_symmetry.smt2's
        ;     section A, for every rational and every binade. Here: a nonzero double, either sign,
        ;     is admitted by jdouble.near for itself in any binade at once, and rounds to no other
        ;     double in its own binade or the next ones, for every binade from 2^%d to 2^%d, so
        ;     B - (+0) = B and +0 - v = -v for every such B and v.
        ;   - Section C: xn, whose high word is hx|(i^0x3ff00000) for hx < 2^20 and i either 0 or
        ;     0x100000 (lines 109-111), has sign 1 and binade 2^-1 or 2^0, for every low word: so
        ;     xn is in [0.5, 2), and xn = 1 exactly at one pattern. Its bits are built over
        ;     SMT-LIB's bit-vectors, Java's | and ^ on ints being bvor and bvxor (JLS 15.22.1),
        ;     then read as a double over java.smt2's integers. ln2_hi and ln2_lo are positive and
        ;     finite.
        ;
        ; What is not machine-checked:
        ;   - That f = xn - 1.0 is exact (Sterbenz's lemma, R6) and zero only at xn = 1, where it
        ;     is +0: the steps from section C to H9. ProofsTest.l6FIsNeverNegativeZero checks the
        ;     difference exactly, by BigDecimal, over sampled patterns of every kind.
        ;   - That the values of B and v lie in the binades of section B: on the k == 0 lanes |f|
        ;     is in [2^-53, 0.415) or zero, B is about f^2/2 or smaller than f, and v is about
        ;     log(1+f); these bounds, like exp's H2, rest on the polynomial and are sampled.
        ;   - That the sign of a nonzero product of like signs is positive (hfsq not -0 for f
        ;     nonzero), and that a rounding of a nonzero value gives a nonzero double where the
        ;     values are normal: zero is not in the prelude.
        ;   - That a value rounds to no double in a binade further off than the next one: the
        ;     arithmetic of rationals, as in sign_symmetry.smt2.
        ;   - That D2I is not used and I2D of 0, or L7's conversion, gives +0.0 (JLS 5.1.2,
        ;     log_exact_dk.smt2), and that the port computes these forms: the code's text.
        ;
        ; Constants: ln2_hi and ln2_lo are rendered from Log; no claim here is tight to their
        ; values beyond their sign and finiteness, so none is refuted by changing it by one.
        ; LogTest.constantsAreTheSourcesBits pins them to the source's hex, and ProofFilesTest
        ; this file to its rendering.
        ;
        ; The logic is ALL, as for exp_zero_reduction.smt2's per-binade checks.
        """).formatted(ZERO_DK_LOWEST, ZERO_DK_HIGHEST));
    out.append(ProofFiles.rendered("ALL"));

    out.append("""

        ; Section A: the sign of an exact zero, true for -0. z.sub sx sy is the sign of x - y and
        ; z.add sx sy that of x + y for zeros x and y; z.mul is a zero product's.
        (define-fun z.sub ((sx Bool) (sy Bool)) Bool (and sx (not sy)))
        (define-fun z.add ((sx Bool) (sy Bool)) Bool (and sx sy))
        (define-fun z.mul ((sx Bool) (sy Bool)) Bool (xor sx sy))
        """);
    var zeros = new ArrayList<String>();
    for (double a : new double[] {0.0, -0.0}) {
      for (double b : new double[] {0.0, -0.0}) {
        String sa = bool(negative(a));
        String sb = bool(negative(b));
        zeros.add("(= (z.sub " + sa + " " + sb + ") " + bool(negative(a - b)) + ")");
        zeros.add("(= (z.add " + sa + " " + sb + ") " + bool(negative(a + b)) + ")");
        zeros.add("(= (z.mul " + sa + " " + sb + ") " + bool(negative(a * b)) + ")");
      }
      zeros.add("(= (z.mul " + bool(negative(a)) + " false) " + bool(negative(a * Log.LN2_HI))
          + ")");
      zeros.add("(= (z.mul " + bool(negative(a)) + " false) " + bool(negative(a * Log.LN2_LO))
          + ")");
    }
    // x - x is +0 for a nonzero x, either sign: the rule for like signs, at sampled doubles.
    for (double x : new double[] {1.0, -1.0, 0.4142, -0.2929, 0x1p-53, -0x1p-1074, 1e300}) {
      if (negative(x - x)) {
        throw new IllegalStateException("x - x is -0 at " + x);
      }
    }
    equations(out, "the zero signs are Java's, and +-0 * ln2_hi, ln2_lo", zeros);
    out.append("""

        ; The fold's zero cases. B - f exactly zero: either B = f nonzero (eq), whose difference
        ; is +0 under the rule for like signs (Java's x - x above), or B and f zeros of signs sb
        ; and sf. B - (+0) keeps B's zero sign (z.sub sb false is sb). v = B - f; the fold is
        ; +0 - v; the C is f - B.
        (define-fun v.sign ((eq Bool) (sb Bool) (sf Bool)) Bool
          (ite eq false (z.sub (z.sub sb false) sf)))
        (define-fun fold.sign ((eq Bool) (sb Bool) (sf Bool)) Bool
          (z.sub false (v.sign eq sb sf)))
        (define-fun c.sign ((eq Bool) (sb Bool) (sf Bool)) Bool (ite eq false (z.sub sf sb)))
        """);
    check(out, "B - (+0) keeps a zero B's sign, either sign", "unsat", body(
        "(declare-const sb Bool)", "(assert (not (= (z.sub sb false) sb)))"));
    check(out, "B - f exactly zero, f not -0: the fold's zero is the C's", "unsat",
        body("(declare-const eq Bool) (declare-const sb Bool) (declare-const sf Bool)",
            "(assert (or eq (not sf)))",
            "(assert (not (= (fold.sign eq sb sf) (c.sign eq sb sf))))"));
    check(out, "refuted: with f = -0 allowed the fold differs (B = +0, f = -0)", "sat", body(
        "(declare-const sb Bool) (declare-const sf Bool)",
        "(assert (not (= (fold.sign false sb sf) (c.sign false sb sf))))"));
    check(out, "f = -0 and B = +0 is that case: the C gives -0, the fold +0", "unsat", body(
        "(assert (not (and (c.sign false false true) (not (fold.sign false false true)))))"));
    check(out, "a zero f's hfsq = (0.5 * f) * f is +0, either sign of f (H10)", "unsat", body(
        "(declare-const sf Bool)", "(assert (z.mul (z.mul false sf) sf))"));
    check(out, "band: hfsq - (shr + (+0)) is hfsq - shr, shr and hfsq zeros, hfsq +0", "unsat",
        body("(declare-const ss Bool)",
            "(assert (not (= (z.sub false (z.add ss false)) (z.sub false ss))))"));
    check(out, "refuted: with hfsq = -0 the band's step differs (shr = -0)", "sat", body(
        "(declare-const ss Bool)",
        "(assert (not (= (z.sub true (z.add ss false)) (z.sub true ss))))"));
    check(out, "f == 0: dk*ln2_hi + dk*ln2_lo with dk = +0 is +0, the C's zero", "unsat", body(
        "(assert (z.add (z.mul false false) (z.mul false false)))"));

    out.append("""

        ; Section B: a nonzero double less +0 is itself, and +0 less a nonzero double v is -v. The
        ; exact difference is the double S * M * 2^E or its negation, a / b with a = S * M * sn
        ; and b = sd.
        """);
    check(out, "a double, either sign, is a double jdouble.near admits for itself, any binade",
        "unsat", body("(declare-const S Int) (declare-const M Int)",
            "(declare-const sn Int) (declare-const sd Int)",
            "(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))",
            "(assert (not (jdouble.near (* S M sn) sd S M sn sd)))"));
    for (int j = ZERO_DK_LOWEST; j <= ZERO_DK_HIGHEST; j++) {
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
    check(out, "refuted: a double less one step of its binade 2^-1 is itself", "sat", body(
        "(declare-const M Int) (declare-const M2 Int)", "(assert (jdouble.M.in M))",
        "(assert " + near("(- M 1)", pow2(53), "1", "M2", -1) + ")",
        "(assert (not (= M2 M)))"));

    out.append("""

        ; Section C: xn's bits, over bit-vectors, for every 20-bit hx, either i and every low
        ; word lo: the top 12 bits (sign and biased exponent) are 0x3fe or 0x3ff, and xn is 1.0
        ; only for hx = 0, lo = 0 and i = 0. Then, over java.smt2's integers, every long in
        ; [0x3fe00000_00000000, 0x40000000_00000000) is a double of sign 1 and binade 2^-1 or 2^0.
        (define-fun xn.bits ((hx (_ BitVec 32)) (i (_ BitVec 32)) (lo (_ BitVec 32)))
          (_ BitVec 64) (concat (bvor hx (bvxor i #x3ff00000)) lo))
        """);
    String dom = "(and (bvult hx #x00100000) (or (= i #x00000000) (= i #x00100000)))";
    String vars = "(declare-const hx (_ BitVec 32)) (declare-const i (_ BitVec 32))"
        + " (declare-const lo (_ BitVec 32))";
    check(out, "xn's sign and biased exponent are 0x3fe or 0x3ff", "unsat", body(vars,
        "(assert " + dom + ")",
        "(assert (not (or (= ((_ extract 63 52) (xn.bits hx i lo)) #x3fe)"
            + " (= ((_ extract 63 52) (xn.bits hx i lo)) #x3ff))))"));
    check(out, "xn = 1 only at hx = 0, lo = 0, i = 0", "unsat", body(vars,
        "(assert " + dom + ")", "(assert (= (xn.bits hx i lo) #x3ff0000000000000))",
        "(assert (not (and (= hx #x00000000) (= lo #x00000000) (= i #x00000000))))"));
    check(out, "not vacuous: some xn is 1", "sat", body(vars, "(assert " + dom + ")",
        "(assert (= (xn.bits hx i lo) #x3ff0000000000000))"));
    check(out, "those bits are doubles of sign 1 in binade 2^-1 or 2^0",
        "unsat", body("(declare-const b Int)",
            "(assert (and (<= " + lit(0x3fe0000000000000L) + " b) (< b "
                + lit(0x4000000000000000L) + ")))",
            "(assert (not (and (= (jdouble.fromBits.S b) 1)",
            "  (or (= (jdouble.fromBits.E b) (- 53)) (= (jdouble.fromBits.E b) (- 52))))))"));
    long hiBits = Double.doubleToRawLongBits(Log.LN2_HI);
    long loBits = Double.doubleToRawLongBits(Log.LN2_LO);
    check(out, "ln2_hi and ln2_lo are positive and finite, as the zero product needs", "unsat",
        body("(assert (not (and (= (jdouble.fromBits.S " + lit(hiBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(hiBits) + ") 972)",
            "  (= (jdouble.fromBits.S " + lit(loBits) + ") 1)"
                + " (< (jdouble.fromBits.E " + lit(loBits) + ") 972))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // log_exact_dk.smt2: L7

  /**
   * {@code log_exact_dk.smt2}, lemma L7: {@code fromBits(k + DK_MAGIC_BITS) - DK_MAGIC} is the
   * double k, exactly, for every long k in [-2^51, 2^51).
   */
  static String exactDk() {
    long magicBits = Log.DK_MAGIC_BITS;
    long m0 = mant(Log.DK_MAGIC);
    int jm = binade(Log.DK_MAGIC);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's dk = (double)k without L2D: an add to the bits and an exact subtraction
        ; (PROOFS.md, L7).
        ;
        ; e_log.c (reference/fdlibm-5.3) converts its int k to double, dk = (double)k (lines
        ; 115, 118 and 122). The port (Log) holds k in a long lane, and C2 lowers no L2D on AVX2,
        ; so it computes dk = fromBits(k + DK_MAGIC_BITS) - DK_MAGIC, with DK_MAGIC = 0x1.8p52
        ; and DK_MAGIC_BITS = %s its bits, both rendered from Log. The claim: for
        ; every long k in [-2^51, 2^51), that is the double whose value is k, which for k = 0 is
        ; +0.
        ;
        ; What is proven: DK_MAGIC's bits are DK_MAGIC (S = 1, binade 2^%d,
        ; M = %d, ulp 1); for every k in the domain, k + DK_MAGIC_BITS (jlong.add,
        ; so a wrap would show) has S = 1, E = 0 and M = %d + k, the double
        ; 0x1.8p52 + k; so the exact difference less DK_MAGIC is the integer k; and for k in
        ; each binade 2^0 to 2^51, either sign, k rounds to the double S * |k| * 2^(52 - j) *
        ; 2^(j - 52) whose value is k, and to no other double in that binade or the next ones.
        ; The domain is tight: at k = 2^51 the bits carry into the exponent and k = 2^51 + 1
        ; gives 2^51 + 2; and DK_MAGIC_BITS + 1 gives k + 1.
        ;
        ; What is not: that the double named is Java's (double)k, which JLS 5.1.2 makes exact for
        ; |k| <= 2^53 (ProofsTest.l7ExactConversionIsI2D compares the bits); that for k = 0 the
        ; exact zero difference x - x is +0 (IEEE 754 6.3, pinned in log_zero_dk.smt2's section
        ; A only for like-signed zeros, here stated); that a value rounds to no double in a binade
        ; further off than the next one (the arithmetic of rationals); that log's k lies in the
        ; domain, which log_long_words.smt2 proves for every lane; and that the port computes
        ; this, the code's text.
        ;
        ; The logic is ALL: the per-binade checks are those of exp_zero_reduction.smt2.
        """).formatted(hex(magicBits), jm, m0, m0));
    out.append(ProofFiles.rendered("ALL"));
    check(out, "DK_MAGIC_BITS = " + hex(magicBits) + " is 0x1.8p52: S = 1, E = 0, M = 3 * 2^51",
        "unsat", body("(assert (not (and (= (jdouble.fromBits.S " + lit(magicBits) + ") 1)",
            "  (= (jdouble.fromBits.E " + lit(magicBits) + ") 0)",
            "  (= (jdouble.fromBits.M " + lit(magicBits) + ") " + m0 + ")",
            "  (= " + m0 + " (* 3 " + pow2(51) + ")))))"));
    String dom = "(and (<= (- " + pow2(51) + ") k) (< k " + pow2(51) + "))";
    String kb = "(jlong.add k " + lit(magicBits) + ")";
    check(out, "for k in [-2^51, 2^51), k + DK_MAGIC_BITS is the double 0x1.8p52 + k", "unsat",
        body("(declare-const k Int)", "(assert " + dom + ")",
            "(assert (not (and (= (jdouble.fromBits.S " + kb + ") 1)"
                + " (= (jdouble.fromBits.E " + kb + ") 0)",
            "  (= (jdouble.fromBits.M " + kb + ") (+ " + m0 + " k)))))"));
    for (int j = 0; j <= 51; j++) {
      for (int s : new int[] {1, -1}) {
        if (j == 51 && s > 0) {
          continue;
        }
        String kd = s > 0 ? "(and (<= " + pow2(j) + " k) (< k " + pow2(j + 1) + "))"
            : "(and (< (- " + pow2(j + 1) + ") k) (<= k (- " + pow2(j) + ")))";
        String abs = s > 0 ? "k" : "(- k)";
        String m = "(* " + abs + " " + pow2(52 - j) + ")";
        check(out, "k in " + (s > 0 ? "" : "-") + "2^" + j + ": the exact k rounds to the double"
            + " k, no other", "unsat", body(
                "(declare-const k Int) (declare-const S2 Int) (declare-const M2 Int)",
                "(assert " + kd + ")",
                "(assert (or (not " + near("k", "1", ExpProofFiles.sign(s), m, j) + ")",
                "  " + ExpProofFiles.roundsToOther("k", "1", s, m, j) + "))"));
      }
    }
    out.append("""

        ; The domain and the constant are tight. At k = 2^51 the sum carries into the exponent:
        ; still k, by luck of the carry; one more and the double's ulp is 2, so the result is
        ; k + 1. And DK_MAGIC_BITS + 1 is 0x1.8p52 + 1: at k = 0 the result is 1, not 0.
        """);
    long top = (1L << 51) + 1;
    long topBits = top + magicBits;
    check(out, "refuted: k = 2^51 + 1 gives 2^53 + 2, so k + 1 after the subtraction",
        "sat", body("(assert (and (= (jdouble.fromBits.E " + lit(topBits) + ") 1)"
                + " (= (jdouble.fromBits.M " + lit(topBits) + ") " + (P52 + 1) + ")",
            "  (not (= (- (* 2 (jdouble.fromBits.M " + lit(topBits) + ")) " + m0 + ") "
                + top + "))))"));
    check(out, "refuted: DK_MAGIC_BITS + 1 at k = 0 gives 1, not 0", "sat", body(
        "(assert (and (= (jdouble.fromBits.E " + lit(magicBits + 1) + ") 0)",
        "  (not (= (- (jdouble.fromBits.M " + lit(magicBits + 1) + ") " + m0 + ") 0))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Bit-vectors for L8 and L9

  /** A 64-bit bit-vector literal. */
  private static String bv(long v) {
    return String.format("#x%016x", v);
  }

  /** A 32-bit bit-vector literal. */
  private static String bv(int v) {
    return String.format("#x%08x", v);
  }

  /** Lines of a file's header on Java over bit-vectors, shared by L8's and L9's. */
  private static final String JAVA_AS_BV = """
      ; Java's int and long arithmetic is two's complement modulo 2^32 and 2^64 (JLS 4.2.2), so
      ; each operator here is its SMT-LIB bit-vector counterpart: + - as bvadd, bvsub; & | ^ as
      ; bvand, bvor, bvxor (JLS 15.22.1); << >> >>> by a constant count as bvshl, bvashr,
      ; bvlshr (JLS 15.19); a signed compare as bvslt and its kin; (int) of a long as the low 32
      ; bits, extract; (long) of an int as sign_extend (JLS 5.1.3, 5.1.2). The definitions are
      ; pinned to the JVM: a ground check per sampled bit pattern compares each C and port
      ; definition with the value Java computed while rendering. Java's integers are used here
      ; rather than java.smt2's: mixing the two through int2bv left both solvers at unknown for
      ; a minute, where over bit-vectors alone they decide each check at once.
      """;

  /** Sampled bit patterns for the ground checks: the specials and the words log branches at. */
  private static long[] samples() {
    var xs = new ArrayList<Long>();
    for (double d : new double[] {0.0, -0.0, Double.MIN_VALUE, Double.MIN_NORMAL, 0.5, 1.0,
        Math.sqrt(2), Math.sqrt(2) / 2, 3.0, 1e300, Double.MAX_VALUE, Double.POSITIVE_INFINITY,
        Double.NaN, -1.0, Double.NEGATIVE_INFINITY}) {
      xs.add(Double.doubleToRawLongBits(d));
    }
    for (int h : new int[] {0x3ff6147a, 0x3ff61479, 0x3fe6b851, 0x3fe6b852, 0x3fe6a09c,
        0x3ff6a09b, 0x3feffffe, 0x3ff00001, 0x000fffff, 0x7fefffff, 0x80100000, 0xfff80000}) {
      xs.add(((long) h << 32) | 0x12345678L);
    }
    xs.add(0x0010000000000000L - 1);
    xs.add(0x7ff0000000000000L - 1);
    xs.add(Long.MIN_VALUE);
    xs.add(-1L);
    return xs.stream().mapToLong(Long::longValue).toArray();
  }

  // ---------------------------------------------------------------------------------------------
  // log_long_words.smt2: L8

  /** The C's words for one bit pattern, as Java ints: {hx, hxm, i, newhi, k, kSub, small, band}. */
  private static int[] cWords(long b) {
    int hx = (int) (b >> 32);
    int hxm = hx & 0x000fffff;
    int i = (hxm + Log.SQRT2_SPLIT) & 0x100000;
    int newhi = hxm | (i ^ 0x3ff00000);
    int k = ((hx >> 20) - 1023) + (i >> 20);
    int kSub = (-54 + ((hx >> 20) - 1023)) + (i >> 20);
    int small = ((0x000fffff & (2 + hxm)) < 3) ? 1 : 0;
    int band = (((hxm - Log.HX_BAND_LO) | (Log.HX_BAND_HI - hxm)) > 0) ? 1 : 0;
    return new int[] {hx, hxm, i, newhi, k, kSub, small, band};
  }

  /** The port's words for one bit pattern, as Log computes them: {hx, hxm, i, newhi, kBits}. */
  private static long[] portWords(long b) {
    long hx = b >>> 32;
    long hxm = hx & 0x000fffff;
    long i = (hxm + Log.SQRT2_SPLIT) & 0x100000;
    long newhi = hxm | (i ^ 0x3ff00000);
    long kBits = ((hx >>> 20) + (Log.DK_MAGIC_BITS - 1023)) + (i >>> 20);
    return new long[] {hx, hxm, i, newhi, kBits};
  }

  /**
   * {@code log_long_words.smt2}, lemma L8: the port's long-lane words are the C's int words on
   * every lane with the sign bit clear, and its k lies in [-1077, 3073] on every lane.
   */
  static String longWords() {
    long magic = Log.DK_MAGIC_BITS;
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's int words in long lanes (PROOFS.md, L8).
        ;
        ; e_log.c (reference/fdlibm-5.3) reads hx = __HI(x) as a signed int and computes, lines
        ; 104-131: k = (hx>>20)-1023 (less 54 first for a subnormal), hx &= 0x000fffff,
        ; i = (hx+0x%x)&0x100000, the new high word hx|(i^0x3ff00000), k += (i>>20), the test
        ; (0x000fffff&(2+hx))<3 and the band test ((hx-0x%x)|(0x%x-hx)) > 0. The port (Log)
        ; computes them on longs: HX = bits >>> 32, kBits = (HX >>> 20) + (DK_MAGIC_BITS - 1023)
        ; [- 54] + (I >>> 20), and the rest as the C writes it, on longs. The words are rendered
        ; from Log.
        ;
        """).formatted(Log.SQRT2_SPLIT, Log.HX_BAND_LO, Log.HX_BAND_HI));
    out.append(JAVA_AS_BV);
    out.append("""
        ;
        ; What is proven:
        ;   - for every long bits with the sign bit clear (every lane whose result is kept: a
        ;     positive x, and the scaled subnormal), HX is the C's hx widened; the masked word,
        ;     i and the new high word are the C's widened; kBits is the C's k widened plus
        ;     DK_MAGIC_BITS, with and without the subnormal's -54; and the two tests agree. So the
        ;     C's int arithmetic, which these checks take modulo 2^32 as Java does, has no
        ;     overflow on these lanes to differ on: the long values, taken modulo 2^64, are equal.
        ;   - for every long bits, either sign, kBits - DK_MAGIC_BITS lies in [-1077, 3073], inside
        ;     log_exact_dk.smt2's domain, so dk is exact on every lane, kept or not.
        ;
        ; What is not: that __HI(x) is the high int of the raw bits (the prelude's reading of the
        ; macro), that the port writes the new high word's low 32 bits back as the C's __HI
        ; assignment does, and that it computes this: the code's text.
        ;
        ; The sat checks refute the sign hypothesis (a negative bits gives HX >= 2^31, not the C's
        ; negative hx), and SQRT2_SPLIT, HX_BAND_LO and HX_BAND_HI changed by one.
        ;
        ; The logic is ALL: dev/prove.sh inserts java.smt2's integer prelude, unused here.
        """);
    out.append(ProofFiles.rendered("ALL"));
    out.append(("""

        ; The C, on the signed int hx = __HI(x), and the port, on the long bits.
        (define-fun c.hx ((b (_ BitVec 64))) (_ BitVec 32) ((_ extract 63 32) b))
        (define-fun c.hxm ((b (_ BitVec 64))) (_ BitVec 32) (bvand (c.hx b) #x000fffff))
        (define-fun c.i ((b (_ BitVec 64)) (split (_ BitVec 32))) (_ BitVec 32)
          (bvand (bvadd (c.hxm b) split) #x00100000))
        (define-fun c.newhi ((b (_ BitVec 64))) (_ BitVec 32)
          (bvor (c.hxm b) (bvxor (c.i b %1$s) #x3ff00000)))
        (define-fun c.k ((b (_ BitVec 64)) (k0 (_ BitVec 32))) (_ BitVec 32)
          (bvadd (bvadd k0 (bvsub (bvashr (c.hx b) #x00000014) #x000003ff))
                 (bvashr (c.i b %1$s) #x00000014)))
        (define-fun c.small ((b (_ BitVec 64))) Bool
          (bvslt (bvand #x000fffff (bvadd #x00000002 (c.hxm b))) #x00000003))
        (define-fun c.band ((b (_ BitVec 64)) (lo (_ BitVec 32)) (hi (_ BitVec 32))) Bool
          (bvsgt (bvor (bvsub (c.hxm b) lo) (bvsub hi (c.hxm b))) #x00000000))
        (define-fun widen ((v (_ BitVec 32))) (_ BitVec 64) ((_ sign_extend 32) v))
        (define-fun p.hx ((b (_ BitVec 64))) (_ BitVec 64) (bvlshr b #x0000000000000020))
        (define-fun p.hxm ((b (_ BitVec 64))) (_ BitVec 64) (bvand (p.hx b) #x00000000000fffff))
        (define-fun p.i ((b (_ BitVec 64)) (split (_ BitVec 64))) (_ BitVec 64)
          (bvand (bvadd (p.hxm b) split) #x0000000000100000))
        (define-fun p.newhi ((b (_ BitVec 64))) (_ BitVec 64)
          (bvor (p.hxm b) (bvxor (p.i b %2$s) #x000000003ff00000)))
        (define-fun p.kbits ((b (_ BitVec 64)) (k0 (_ BitVec 64))) (_ BitVec 64)
          (bvadd (bvadd (bvadd (bvlshr (p.hx b) #x0000000000000014) %3$s) k0)
                 (bvlshr (p.i b %2$s) #x0000000000000014)))
        (define-fun p.small ((b (_ BitVec 64))) Bool
          (bvslt (bvand (bvadd (p.hxm b) #x0000000000000002) #x00000000000fffff)
                 #x0000000000000003))
        (define-fun p.band ((b (_ BitVec 64)) (lo (_ BitVec 64)) (hi (_ BitVec 64))) Bool
          (bvsgt (bvor (bvsub (p.hxm b) lo) (bvsub hi (p.hxm b))) #x0000000000000000))
        """).formatted(bv(Log.SQRT2_SPLIT), bv((long) Log.SQRT2_SPLIT), bv(magic - 1023)));
    String lo32 = bv(Log.HX_BAND_LO);
    String hi32 = bv(Log.HX_BAND_HI);
    String lo64 = bv((long) Log.HX_BAND_LO);
    String hi64 = bv((long) Log.HX_BAND_HI);
    String split32 = bv(Log.SQRT2_SPLIT);
    String split64 = bv((long) Log.SQRT2_SPLIT);
    var pins = new ArrayList<String>();
    for (long b : samples()) {
      int[] c = cWords(b);
      long[] p = portWords(b);
      String x = bv(b);
      pins.add("(= (c.hx " + x + ") " + bv(c[0]) + ") (= (c.hxm " + x + ") " + bv(c[1]) + ")");
      pins.add("(= (c.i " + x + " " + split32 + ") " + bv(c[2]) + ") (= (c.newhi " + x + ") "
          + bv(c[3]) + ")");
      pins.add("(= (c.k " + x + " #x00000000) " + bv(c[4]) + ") (= (c.k " + x + " #xffffffca) "
          + bv(c[5]) + ")");
      pins.add("(= (c.small " + x + ") " + bool(c[6] == 1) + ") (= (c.band " + x + " " + lo32
          + " " + hi32 + ") " + bool(c[7] == 1) + ")");
      pins.add("(= (p.hx " + x + ") " + bv(p[0]) + ") (= (p.hxm " + x + ") " + bv(p[1]) + ")");
      pins.add("(= (p.i " + x + " " + split64 + ") " + bv(p[2]) + ") (= (p.newhi " + x + ") "
          + bv(p[3]) + ")");
      pins.add("(= (p.kbits " + x + " #x0000000000000000) " + bv(p[4]) + ")");
    }
    equations(out, "the C's and the port's words are Java's at sampled bits", pins);
    String pos = "(assert (bvsge b #x0000000000000000))";
    String b64 = "(declare-const b (_ BitVec 64))";
    check(out, "bits >= 0: HX, the masked word, i and the new high word are the C's", "unsat",
        body(b64, pos,
            "(assert (not (and (= (p.hx b) (widen (c.hx b))) (= (p.hxm b) (widen (c.hxm b)))",
            "  (= (p.i b " + split64 + ") (widen (c.i b " + split32 + ")))",
            "  (= (p.newhi b) (widen (c.newhi b))))))"));
    for (int k0 : new int[] {0, -54}) {
      check(out, "bits >= 0: kBits is the C's k" + (k0 == 0 ? "" : ", -54 first,")
          + " plus DK_MAGIC_BITS", "unsat", body(b64, pos,
              "(assert (not (= (p.kbits b " + bv((long) k0) + ") (bvadd (widen (c.k b " + bv(k0)
                  + ")) " + bv(magic) + "))))"));
    }
    check(out, "bits >= 0: the |f| < 2^-20 test and the band test are the C's", "unsat", body(
        b64, pos, "(assert (not (and (= (p.small b) (c.small b))",
        "  (= (p.band b " + lo64 + " " + hi64 + ") (c.band b " + lo32 + " " + hi32 + ")))))"));
    for (int k0 : new int[] {0, -54}) {
      check(out, "every long: kBits - DK_MAGIC_BITS is in [-1077, 3073]" + (k0 == 0 ? ""
          : ", with the -54"), "unsat", body(b64,
              "(define-fun k () (_ BitVec 64) (bvsub (p.kbits b " + bv((long) k0) + ") "
                  + bv(magic) + "))",
              "(assert (not (and (bvsle " + bv(-1077L) + " k) (bvsle k " + bv(3073L) + "))))"));
    }
    out.append("""

        ; The sign hypothesis is needed, and each word is tight.
        """);
    check(out, "refuted: for a negative bits HX is not the C's hx", "sat", body(b64,
        "(assert (bvslt b #x0000000000000000))",
        "(assert (not (= (p.hx b) (widen (c.hx b)))))"));
    check(out, "refuted: SQRT2_SPLIT + 1 = 0x" + Integer.toHexString(Log.SQRT2_SPLIT + 1)
        + " changes i", "sat", body(b64, pos,
            "(assert (not (= (p.i b " + bv((long) Log.SQRT2_SPLIT + 1) + ") (widen (c.i b "
                + split32 + ")))))"));
    check(out, "refuted: HX_BAND_LO - 1 changes the band test", "sat", body(b64, pos,
        "(assert (not (= (p.band b " + bv((long) Log.HX_BAND_LO - 1) + " " + hi64 + ") (c.band b "
            + lo32 + " " + hi32 + "))))"));
    check(out, "refuted: HX_BAND_HI + 1 changes the band test", "sat", body(b64, pos,
        "(assert (not (= (p.band b " + lo64 + " " + bv((long) Log.HX_BAND_HI + 1) + ") (c.band b "
            + lo32 + " " + hi32 + "))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // log_range_compare.smt2: L9

  /** The port's biased compare, lo <= bits < hi, as Log writes it. */
  private static boolean biased(long b, long lo, long hi) {
    return b + (Long.MIN_VALUE - lo) < Long.MIN_VALUE + (hi - lo);
  }

  /**
   * {@code log_range_compare.smt2}, lemma L9: Log's one biased signed compare of the bits is
   * the C's high-word tests for the common lanes and for the blended arm's subnormals.
   */
  static String rangeCompare() {
    long lo = Log.COMMON_LO;
    long hi = Log.COMMON_HI;
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's common and subnormal lanes by one compare of the bits (PROOFS.md, L9).
        ;
        ; e_log.c (reference/fdlibm-5.3) tests the signed int hx = __HI(x): hx < 0x%08x
        ; (line 100) and hx >= 0x%08x (line 107) leave the common lanes, and on the first branch
        ; ((hx&0x7fffffff)|lx) == 0 (line 101) and hx < 0 (line 103) leave the subnormals. The port
        ; (Log's body) tests lo <= bits < hi on the raw bits as bits + (Long.MIN_VALUE - lo) <
        ; Long.MIN_VALUE + (hi - lo), signed, with [lo, hi) = [COMMON_LO, COMMON_HI) =
        ; [%s, %s) for the common lanes
        ; and [1, COMMON_LO) for the subnormals, rendered from Log.
        ;
        """).formatted(Log.HX_MIN_NORMAL, Log.HX_NON_FINITE, hex(lo), hex(hi)));
    out.append(JAVA_AS_BV);
    out.append("""
        ;
        ; What is proven, for every long bits (every double, NaNs of either sign included): the
        ; biased compare is lo <= bits < hi for every lo <= hi; the common range is the C's two
        ; tests, and the subnormal range the C's three. The sat checks refute the compare without
        ; the bias (a negative bits passes it) and COMMON_LO and COMMON_HI moved by one.
        ;
        ; What is not: that __HI(x) and __LO(x) are the raw bits' high int and low word (the
        ; prelude's reading of fdlibm's macros), and that the port compares with these bounds,
        ; the code's text.
        ;
        ; The logic is ALL: dev/prove.sh inserts java.smt2's integer prelude, unused here.
        """);
    out.append(ProofFiles.rendered("ALL"));
    out.append("""

        ; The biased compare, and the C's tests on hx = __HI(x) signed and lx = __LO(x).
        (define-fun in.range ((b (_ BitVec 64)) (lo (_ BitVec 64)) (hi (_ BitVec 64))) Bool
          (bvslt (bvadd b (bvsub #x8000000000000000 lo)) (bvadd #x8000000000000000 (bvsub hi lo))))
        (define-fun c.hx ((b (_ BitVec 64))) (_ BitVec 32) ((_ extract 63 32) b))
        (define-fun c.lx ((b (_ BitVec 64))) (_ BitVec 32) ((_ extract 31 0) b))
        """);
    String common = "(and (not (bvslt (c.hx b) " + bv(Log.HX_MIN_NORMAL) + "))"
        + " (not (bvsge (c.hx b) " + bv(Log.HX_NON_FINITE) + ")))";
    String sub = "(and (bvslt (c.hx b) " + bv(Log.HX_MIN_NORMAL) + ") (not (= (bvor (bvand"
        + " (c.hx b) #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))";
    var pins = new ArrayList<String>();
    for (long b : samples()) {
      int hx = (int) (b >> 32);
      int lx = (int) b;
      boolean cCommon = !(hx < Log.HX_MIN_NORMAL) && !(hx >= Log.HX_NON_FINITE);
      boolean cSub = hx < Log.HX_MIN_NORMAL && ((hx & 0x7fffffff) | lx) != 0 && !(hx < 0);
      String x = bv(b);
      pins.add("(= (in.range " + x + " " + bv(lo) + " " + bv(hi) + ") "
          + bool(biased(b, lo, hi)) + ") (= (in.range " + x + " " + bv(1L) + " " + bv(lo) + ") "
          + bool(biased(b, 1, lo)) + ")");
      pins.add("(= (let ((b " + x + ")) " + common + ") " + bool(cCommon) + ")");
      pins.add("(= (let ((b " + x + ")) " + sub + ") " + bool(cSub) + ")");
    }
    equations(out, "the compare and the C's tests are Java's at sampled bits", pins);
    String b64 = "(declare-const b (_ BitVec 64))";
    check(out, "the biased compare is lo <= bits < hi, every long and lo <= hi", "unsat", body(
        b64, "(declare-const lo (_ BitVec 64)) (declare-const hi (_ BitVec 64))",
        "(assert (bvsle lo hi))",
        "(assert (not (= (in.range b lo hi) (and (bvsle lo b) (bvslt b hi)))))"));
    check(out, "the common range is the C's: not hx < 0x" + Integer.toHexString(Log.HX_MIN_NORMAL)
        + " and not hx >= 0x" + Integer.toHexString(Log.HX_NON_FINITE), "unsat", body(b64,
            "(assert (not (= (in.range b " + bv(lo) + " " + bv(hi) + ") " + common + ")))"));
    check(out, "the subnormal range [1, COMMON_LO) is the C's three tests", "unsat", body(b64,
        "(assert (not (= (in.range b " + bv(1L) + " " + bv(lo) + ") " + sub + ")))"));
    out.append("""

        ; Each part is needed.
        """);
    check(out, "refuted: without the bias a negative bits passes", "sat", body(b64,
        "(assert (and (bvslt (bvsub b " + bv(lo) + ") " + bv(hi - lo) + ") (not " + common
            + ")))"));
    check(out, "refuted: COMMON_LO - 1 as the bottom", "sat", body(b64,
        "(assert (not (= (in.range b " + bv(lo - 1) + " " + bv(hi) + ") " + common + ")))"));
    check(out, "refuted: COMMON_HI + 1 as the top", "sat", body(b64,
        "(assert (not (= (in.range b " + bv(lo) + " " + bv(hi + 1) + ") " + common + ")))"));
    return out.toString();
  }

  /** One check that every equation holds: unsat for the negation of their conjunction. */
  private static void equations(StringBuilder out, String what, List<String> eqs) {
    var lines = new ArrayList<String>();
    lines.add("(assert (not (and");
    for (String e : eqs) {
      lines.addAll(ProofFiles.wrap("  " + e));
    }
    lines.add(")))");
    check(out, what + ", on " + eqs.size() + " operand sets", "unsat", lines);
  }
}
