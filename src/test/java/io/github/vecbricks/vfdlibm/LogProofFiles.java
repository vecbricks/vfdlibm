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
import java.util.function.LongBinaryOperator;

/**
 * The proofs of log's lemmas ({@code PROOFS.md}, log, L6 to L10), rendered with the constants
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
        ;     xn is in [0.5, 2), and xn = 1 exactly at one pattern. ln2_hi and ln2_lo are positive
        ;     and finite.
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
        ;   - That D2I is not used and I2D of 0, or L8's conversion, gives +0.0 (JLS 5.1.2,
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

    out.append(BITWISE);
    out.append("""

        ; Section C: xn's bits, for every 20-bit hx, either i and every low word lo: sign 1, and
        ; binade 2^-1 (E = -53) or 2^0 (E = -52); xn = 1 (M = 2^52, E = -52) only for hx = 0 and
        ; lo = 0 with i = 0x100000, the lanes 1.0 itself reduces to.
        (define-fun xn.bits ((hx Int) (i Int) (lo Int)) Int
          (jlong.or (jlong.shl (i2l (jint.or hx (jint.xor i 1072693248))) 32) lo))
        """);
    String dom = "(and (<= 0 hx) (< hx 1048576) (or (= i 0) (= i 1048576)) (<= 0 lo)"
        + " (< lo 4294967296))";
    check(out, "xn has sign 1 and binade 2^-1 or 2^0: it lies in [0.5, 2)", "unsat", body(
        "(declare-const hx Int) (declare-const i Int) (declare-const lo Int)",
        "(assert " + dom + ")",
        "(assert (not (and (= (jdouble.fromBits.S (xn.bits hx i lo)) 1)",
        "  (or (= (jdouble.fromBits.E (xn.bits hx i lo)) (- 53))"
            + " (= (jdouble.fromBits.E (xn.bits hx i lo)) (- 52))))))"));
    check(out, "xn = 1 only at hx = 0, lo = 0, i = 0x100000", "unsat", body(
        "(declare-const hx Int) (declare-const i Int) (declare-const lo Int)",
        "(assert " + dom + ")",
        "(assert (= (jdouble.fromBits.M (xn.bits hx i lo)) " + P52 + "))",
        "(assert (= (jdouble.fromBits.E (xn.bits hx i lo)) (- 52)))",
        "(assert (not (and (= hx 0) (= lo 0) (= i 1048576))))"));
    check(out, "not vacuous: some xn is 1", "sat", body(
        "(declare-const hx Int) (declare-const i Int) (declare-const lo Int)",
        "(assert " + dom + ")",
        "(assert (= (xn.bits hx i lo) " + lit(Double.doubleToRawLongBits(1.0)) + "))"));
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
  // Java's &, | and ^ on ints and longs, over bit-vectors

  /**
   * {@code &}, {@code |} and {@code ^} on ints and longs, which java.smt2 lacks: SMT-LIB's
   * bit-vector operators on the two's-complement pattern ({@code int2bv}), read back as a signed
   * value, as exp_half_sign.smt2 does. {@link #bitwiseCheck} pins them to the JVM in each file.
   */
  private static final String BITWISE = """

      ; Java's &, | and ^ on longs and ints: bit-vector operators on the two's-complement pattern,
      ; read back signed. Pinned to the JVM below.
      (define-fun long.of ((r (_ BitVec 64))) Int
        (- (bv2nat r) (ite (bvslt r #x0000000000000000) 18446744073709551616 0)))
      (define-fun int.of ((r (_ BitVec 32))) Int
        (- (bv2nat r) (ite (bvslt r #x00000000) 4294967296 0)))
      (define-fun jlong.and ((a Int) (b Int)) Int
        (long.of (bvand ((_ int2bv 64) a) ((_ int2bv 64) b))))
      (define-fun jlong.or ((a Int) (b Int)) Int
        (long.of (bvor ((_ int2bv 64) a) ((_ int2bv 64) b))))
      (define-fun jlong.xor ((a Int) (b Int)) Int
        (long.of (bvxor ((_ int2bv 64) a) ((_ int2bv 64) b))))
      (define-fun jint.and ((a Int) (b Int)) Int
        (int.of (bvand ((_ int2bv 32) a) ((_ int2bv 32) b))))
      (define-fun jint.or ((a Int) (b Int)) Int
        (int.of (bvor ((_ int2bv 32) a) ((_ int2bv 32) b))))
      (define-fun jint.xor ((a Int) (b Int)) Int
        (int.of (bvxor ((_ int2bv 32) a) ((_ int2bv 32) b))))
      """;

  private static final long[] BIT_LONGS = {Long.MIN_VALUE, -(1L << 32) - 5, -1048576, -1, 0, 1,
      0x6147a, 0x3ff00000, Long.MAX_VALUE};
  private static final int[] BIT_INTS = {Integer.MIN_VALUE, -1048576, -7, -1, 0, 1, 0x6147a,
      0x3ff00000, Integer.MAX_VALUE};
  /** The right operands: the masks and words log uses, and the extremes. */
  private static final long[] BIT_RIGHTS = {0x100000, 0x3ff00000, -1048576, -1, Integer.MIN_VALUE};

  /** One check that each of {@link #BITWISE}'s six operators is Java's on boundary operands. */
  private static void bitwiseCheck(StringBuilder out) {
    var eqs = new ArrayList<String>();
    for (String[] op : new String[][] {{"and", "&"}, {"or", "|"}, {"xor", "^"}}) {
      LongBinaryOperator f = switch (op[1]) {
        case "&" -> (a, b) -> a & b;
        case "|" -> (a, b) -> a | b;
        default -> (a, b) -> a ^ b;
      };
      for (long a : BIT_LONGS) {
        for (long b : BIT_RIGHTS) {
          eqs.add("(= (jlong." + op[0] + " " + lit(a) + " " + lit(b) + ") "
              + lit(f.applyAsLong(a, b)) + ")");
        }
      }
      for (int a : BIT_INTS) {
        for (long rb : BIT_RIGHTS) {
          int b = (int) rb;
          eqs.add("(= (jint." + op[0] + " " + lit(a) + " " + lit(b) + ") "
              + lit((int) f.applyAsLong(a, b)) + ")");
        }
      }
    }
    equations(out, "jlong and jint &, |, ^ are Java's", eqs);
  }

  // ---------------------------------------------------------------------------------------------
  // log_exact_dk.smt2: L8

  /**
   * {@code log_exact_dk.smt2}, lemma L8: {@code fromBits(k + DK_MAGIC_BITS) - DK_MAGIC} is the
   * double k, exactly, for every long k in [-2^51, 2^51).
   */
  static String exactDk() {
    long magicBits = Log.DK_MAGIC_BITS;
    long m0 = mant(Log.DK_MAGIC);
    int jm = binade(Log.DK_MAGIC);
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's dk = (double)k without L2D: an add to the bits and an exact subtraction
        ; (PROOFS.md, L8).
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
        ; 2^(j - 52) whose value is k, and to no other double in that binade or the next ones. The domain is tight: at k = 2^51 the bits carry into
        ; the exponent and k = 2^51 + 1 gives 2^51 + 2; and DK_MAGIC_BITS + 1 gives k + 1.
        ;
        ; What is not: that the double named is Java's (double)k, which JLS 5.1.2 makes exact for
        ; |k| <= 2^53 (ProofsTest.l8ExactConversionIsI2D compares the bits); that for k = 0 the
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
  // log_long_words.smt2: L9

  /**
   * {@code log_long_words.smt2}, lemma L9: the port's long-lane words are the C's int words on
   * every lane with the sign bit clear, and its k lies in [-1077, 3073] on every lane.
   */
  static String longWords() {
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's int words in long lanes (PROOFS.md, L9).
        ;
        ; e_log.c (reference/fdlibm-5.3) reads hx = __HI(x) as a signed int and computes, lines
        ; 104-131: k = (hx>>20)-1023 (less 54 first for a subnormal), hx &= 0x000fffff,
        ; i = (hx+0x%x)&0x100000, the new high word hx|(i^0x3ff00000), k += (i>>20), the test
        ; (0x000fffff&(2+hx))<3 and the band test ((hx-0x%x)|(0x%x-hx)) > 0. The port (Log)
        ; computes them on longs: HX = bits >>> 32, kBits = (HX >>> 20) + (DK_MAGIC_BITS - 1023)
        ; [- 54] + (I >>> 20), and the rest as the C writes it, on longs. The words are rendered
        ; from Log.
        ;
        ; What is proven, each Java operator as java.smt2 defines it, and &, |, ^ over bit-vectors
        ; (pinned to the JVM here):
        ;   - for every long bits with the sign bit clear (every lane whose result is kept: a
        ;     positive x, and the scaled subnormal), HX is the C's hx widened, the masked word,
        ;     i and the new high word are the C's widened, kBits is the C's k widened plus
        ;     DK_MAGIC_BITS, with and without the subnormal's -54, and the two tests agree;
        ;   - for every long bits, either sign, kBits - DK_MAGIC_BITS lies in [-1077, 3073], inside
        ;     log_exact_dk.smt2's domain, so dk is exact on every lane, kept or not.
        ; The C's int arithmetic is java.smt2's, which wraps; the checks above show the long
        ; values equal to the wrapped ones, and every one lies far inside 32 bits, so the C's
        ; signed arithmetic has no overflow to differ on.
        ;
        ; What is not: that __HI(x) is the high int of the raw bits (the prelude's reading of the
        ; macro), that Lanes.withHi writes the low 32 bits of the new high word as the C's __HI
        ; assignment does, and that the port computes this: the code's text.
        ;
        ; The sat checks refute the sign hypothesis (a negative bits gives HX >= 2^31, not the C's
        ; negative hx), and SQRT2_SPLIT, HX_BAND_LO and HX_BAND_HI changed by one.
        ;
        ; The logic is ALL: the file mixes bit-vectors with java.smt2's integers.
        """).formatted(Log.SQRT2_SPLIT, Log.HX_BAND_LO, Log.HX_BAND_HI));
    out.append(ProofFiles.rendered("ALL"));
    out.append(BITWISE);
    bitwiseCheck(out);
    long magic = Log.DK_MAGIC_BITS;
    out.append(("""

        ; The C, on the signed int hx = __HI(x), and the port, on the long bits.
        (define-fun c.hx ((b Int)) Int (l2i (jlong.shr b 32)))
        (define-fun c.hxm ((b Int)) Int (jint.and.low (c.hx b) 1048576))
        (define-fun c.i ((b Int) (split Int)) Int
          (jint.and (jint.add (c.hxm b) split) 1048576))
        (define-fun c.newhi ((b Int)) Int
          (jint.or (c.hxm b) (jint.xor (c.i b %1$d) 1072693248)))
        (define-fun c.k ((b Int) (k0 Int)) Int
          (jint.add (jint.add k0 (jint.sub (jint.shr (c.hx b) 20) 1023))
                    (jint.shr (c.i b %1$d) 20)))
        (define-fun c.small ((b Int)) Bool (< (jint.and.low (jint.add 2 (c.hxm b)) 1048576) 3))
        (define-fun c.band ((b Int) (lo Int) (hi Int)) Bool
          (> (jint.or (jint.sub (c.hxm b) lo) (jint.sub hi (c.hxm b))) 0))
        (define-fun p.hx ((b Int)) Int (jlong.ushr b 32))
        (define-fun p.hxm ((b Int)) Int (jlong.and.low (p.hx b) 1048576))
        (define-fun p.i ((b Int) (split Int)) Int
          (jlong.and (jlong.add (p.hxm b) split) 1048576))
        (define-fun p.newhi ((b Int)) Int
          (jlong.or (p.hxm b) (jlong.xor (p.i b %1$d) 1072693248)))
        (define-fun p.kbits ((b Int) (k0 Int)) Int
          (jlong.add (jlong.add (jlong.add (jlong.ushr (p.hx b) 20) %2$s) k0)
                     (jlong.ushr (p.i b %1$d) 20)))
        (define-fun p.small ((b Int)) Bool (< (jlong.and.low (jlong.add (p.hxm b) 2) 1048576) 3))
        (define-fun p.band ((b Int) (lo Int) (hi Int)) Bool
          (> (jlong.or (jlong.sub (p.hxm b) lo) (jlong.sub hi (p.hxm b))) 0))
        """).formatted(Log.SQRT2_SPLIT, lit(magic - 1023)));
    String pos = "(assert (and (jlong.in b) (<= 0 b)))";
    check(out, "bits >= 0: HX, the masked word, i and the new high word are the C's", "unsat",
        body("(declare-const b Int)", pos,
            "(assert (not (and (= (p.hx b) (i2l (c.hx b))) (= (p.hxm b) (i2l (c.hxm b)))",
            "  (= (p.i b " + Log.SQRT2_SPLIT + ") (i2l (c.i b " + Log.SQRT2_SPLIT + ")))",
            "  (= (p.newhi b) (i2l (c.newhi b))))))"));
    for (int k0 : new int[] {0, -54}) {
      check(out, "bits >= 0: kBits is the C's k" + (k0 == 0 ? "" : ", -54 first,")
          + " plus DK_MAGIC_BITS", "unsat", body("(declare-const b Int)", pos,
              "(assert (not (= (p.kbits b " + lit(k0) + ") (jlong.add (i2l (c.k b " + lit(k0)
                  + ")) " + lit(magic) + "))))"));
    }
    check(out, "bits >= 0: the |f| < 2^-20 test and the band test are the C's", "unsat", body(
        "(declare-const b Int)", pos,
        "(assert (not (and (= (p.small b) (c.small b))",
        "  (= (p.band b " + Log.HX_BAND_LO + " " + Log.HX_BAND_HI + ") (c.band b " + Log.HX_BAND_LO
            + " " + Log.HX_BAND_HI + ")))))"));
    for (int k0 : new int[] {0, -54}) {
      check(out, "every long: kBits - DK_MAGIC_BITS is in [-1077, 3073]" + (k0 == 0 ? ""
          : ", with the -54"), "unsat", body("(declare-const b Int)", "(assert (jlong.in b))",
              "(define-fun k () Int (- (p.kbits b " + lit(k0) + ") " + lit(magic) + "))",
              "(assert (not (and (<= (- 1077) k) (<= k 3073))))"));
    }
    out.append("""

        ; The sign hypothesis is needed, and each word is tight.
        """);
    check(out, "refuted: for a negative bits HX is not the C's hx", "sat", body(
        "(declare-const b Int)", "(assert (and (jlong.in b) (< b 0)))",
        "(assert (not (= (p.hx b) (i2l (c.hx b)))))"));
    check(out, "refuted: SQRT2_SPLIT + 1 = 0x" + Integer.toHexString(Log.SQRT2_SPLIT + 1)
        + " changes i", "sat", body("(declare-const b Int)", pos,
            "(assert (not (= (p.i b " + (Log.SQRT2_SPLIT + 1) + ") (i2l (c.i b "
                + Log.SQRT2_SPLIT + ")))))"));
    check(out, "refuted: HX_BAND_LO - 1 changes the band test", "sat", body(
        "(declare-const b Int)", pos,
        "(assert (not (= (p.band b " + (Log.HX_BAND_LO - 1) + " " + Log.HX_BAND_HI + ") (c.band b "
            + Log.HX_BAND_LO + " " + Log.HX_BAND_HI + "))))"));
    check(out, "refuted: HX_BAND_HI + 1 changes the band test", "sat", body(
        "(declare-const b Int)", pos,
        "(assert (not (= (p.band b " + Log.HX_BAND_LO + " " + (Log.HX_BAND_HI + 1) + ") (c.band b "
            + Log.HX_BAND_LO + " " + Log.HX_BAND_HI + "))))"));
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // log_range_compare.smt2: L10

  /**
   * {@code log_range_compare.smt2}, lemma L10: {@code Log.inRange}'s one biased signed compare
   * is the C's high-word tests for the common lanes and for the blended arm's subnormals.
   */
  static String rangeCompare() {
    long lo = Log.COMMON_LO;
    long hi = Log.COMMON_HI;
    var out = new StringBuilder(ProofFiles.LICENSE);
    out.append(("""
        ; log's common and subnormal lanes by one compare of the bits (PROOFS.md, L10).
        ;
        ; e_log.c (reference/fdlibm-5.3) tests the signed int hx = __HI(x): hx < 0x%08x
        ; (line 100) and hx >= 0x%08x (line 107) leave the common lanes, and on the first branch
        ; ((hx&0x7fffffff)|lx) == 0 (line 101) and hx < 0 (line 103) leave the subnormals. The port
        ; (Log.inRange) tests lo <= bits < hi on the raw bits as bits + (Long.MIN_VALUE - lo) <
        ; Long.MIN_VALUE + (hi - lo), signed, with [lo, hi) = [COMMON_LO, COMMON_HI) =
        ; [%s, %s) for the common lanes
        ; and [1, COMMON_LO) for the subnormals, rendered from Log.
        ;
        ; What is proven, for every long bits (every double, NaNs of either sign included), each
        ; Java operator as java.smt2 defines it and | over bit-vectors (pinned to the JVM here):
        ; inRange is lo <= bits < hi for every lo <= hi; the common range is the C's two tests,
        ; and the subnormal range the C's three. The sat checks refute the compare without the
        ; bias (a negative bits passes it) and COMMON_LO and COMMON_HI moved by one.
        ;
        ; What is not: that __HI(x) and __LO(x) are the raw bits' high int and low word (the
        ; prelude's reading of fdlibm's macros), and that the port calls inRange with these
        ; bounds, the code's text.
        ;
        ; The logic is ALL: the file mixes bit-vectors with java.smt2's integers.
        """).formatted(Log.HX_MIN_NORMAL, Log.HX_NON_FINITE, hex(lo), hex(hi)));
    out.append(ProofFiles.rendered("ALL"));
    out.append(BITWISE);
    bitwiseCheck(out);
    out.append("""

        ; Log.inRange, and the C's tests on hx = __HI(x) signed and lx = __LO(x) unsigned.
        (define-fun in.range ((b Int) (lo Int) (hi Int)) Bool
          (< (jlong.add b (jlong.sub (- 9223372036854775808) lo))
             (jlong.add (- 9223372036854775808) (jlong.sub hi lo))))
        (define-fun c.hx ((b Int)) Int (l2i (jlong.shr b 32)))
        (define-fun c.lx ((b Int)) Int (jlong.and.low b 4294967296))
        """);
    check(out, "inRange(bits, lo, hi) is lo <= bits < hi, every long and lo <= hi", "unsat",
        body("(declare-const b Int) (declare-const lo Int) (declare-const hi Int)",
            "(assert (and (jlong.in b) (jlong.in lo) (jlong.in hi) (<= lo hi)))",
            "(assert (not (= (in.range b lo hi) (and (<= lo b) (< b hi)))))"));
    String common = "(and (not (< (c.hx b) " + Log.HX_MIN_NORMAL + ")) (not (>= (c.hx b) "
        + Log.HX_NON_FINITE + ")))";
    check(out, "the common range is the C's: not hx < 0x" + Integer.toHexString(Log.HX_MIN_NORMAL)
        + " and not hx >= 0x" + Integer.toHexString(Log.HX_NON_FINITE), "unsat", body(
            "(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (not (= (in.range b " + lit(lo) + " " + lit(hi) + ") " + common + ")))"));
    String sub = "(and (< (c.hx b) " + Log.HX_MIN_NORMAL + ") (not (= (jint.or (jint.and.low"
        + " (c.hx b) 2147483648) (l2i (c.lx b))) 0)) (not (< (c.hx b) 0)))";
    check(out, "the subnormal range [1, COMMON_LO) is the C's three tests", "unsat", body(
        "(declare-const b Int)", "(assert (jlong.in b))",
        "(assert (not (= (in.range b 1 " + lit(lo) + ") " + sub + ")))"));
    out.append("""

        ; Each part is needed.
        """);
    check(out, "refuted: without the bias a negative bits passes",
        "sat", body("(declare-const b Int)", "(assert (jlong.in b))",
            "(assert (and (< (jlong.sub b " + lit(lo) + ") " + lit(hi - lo) + ") (not " + common
                + ")))"));
    check(out, "refuted: COMMON_LO - 1 as the bottom", "sat", body(
        "(declare-const b Int)", "(assert (jlong.in b))",
        "(assert (not (= (in.range b " + lit(lo - 1) + " " + lit(hi) + ") " + common + ")))"));
    check(out, "refuted: COMMON_HI + 1 as the top", "sat", body(
        "(declare-const b Int)", "(assert (jlong.in b))",
        "(assert (not (= (in.range b " + lit(lo) + " " + lit(hi + 1) + ") " + common + ")))"));
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
