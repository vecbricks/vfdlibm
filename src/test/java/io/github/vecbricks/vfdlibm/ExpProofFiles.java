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
}
