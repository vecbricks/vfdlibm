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
    out.append(ProofFiles.rendered("ALL"));
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
}
