/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.SplittableRandom;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * The hinges of the lemmas in {@code PROOFS.md}, each tested on the doubles where it would first
 * fail: a test here fails if its hinge were false. The end-to-end check, the lanes against {@code
 * StrictMath} at the fold boundaries, is {@code ExpTest.foldBoundaries}.
 */
class ProofsTest {

  private static final double TWO_M28 = Lanes.fromHi(Exp.HX_TINY);
  /** The first double above the k == 0 domain: high word 0x3fd62e43, low word 0. */
  private static final double REDUCED_MIN = Lanes.fromHi(Exp.HX_HALF_LN2 + 1);
  /** The last double of the near band: high word 0x3ff0a2b1, low word 0xffffffff. */
  private static final double NEAR_MAX =
      Double.longBitsToDouble(Lanes.fromHiBits(Exp.HX_THREE_HALVES_LN2) - 1);

  private static long bits(double d) {
    return Double.doubleToRawLongBits(d);
  }

  /** c = x - t*(P1+t*(P2+t*(P3+t*(P4+t*P5)))), t = x*x: e_exp.c line 146, scalar. */
  private static double c(double x) {
    double t = x * x;
    return x - t * (Exp.P1 + t * (Exp.P2 + t * (Exp.P3 + t * (Exp.P4 + t * Exp.P5))));
  }

  /** The fdlibm far form's argument to the int cast: invln2*x+halF[xsb]. */
  private static double kArg(double x) {
    return Exp.INVLN2 * x + (bits(x) < 0 ? -0.5 : 0.5);
  }

  /**
   * The k == 0 domain sampled: both ends, the neighbours of each, and seeded draws over every
   * binade between, both signs.
   */
  private static double[] kZeroDomain() {
    double hiEnd = Math.nextDown(REDUCED_MIN);
    double[] ends = Inputs.concat(Inputs.around(TWO_M28, 8), Inputs.around(hiEnd, 8));
    SplittableRandom random = new SplittableRandom(800L);
    double[] xs = new double[ends.length + (1 << 16)];
    System.arraycopy(ends, 0, xs, 0, ends.length);
    for (int i = ends.length; i < xs.length; i++) {
      xs[i] = Math.scalb(1.0 + random.nextDouble(), random.nextInt(-28, -1));
    }
    double[] both = new double[2 * xs.length];
    int next = 0;
    for (double x : xs) {
      if (Math.abs(x) >= TWO_M28 && Math.abs(x) < REDUCED_MIN) {
        both[next++] = x;
        both[next++] = -x;
      }
    }
    return Arrays.copyOf(both, next);
  }

  // L1: the k == 0 path is the general tail with hi = x, lo = +0, k = 0.

  /** L1 (a): with t = +0, hi = x - t*ln2HI and r = hi - t*ln2LO are x itself, bit for bit. */
  @Test
  void l1ZeroReductionLeavesX() {
    double t = 0.0;
    for (double x : kZeroDomain()) {
      double hi = x - t * Exp.LN2HI_0;
      double lo = t * Exp.LN2LO_0;
      assertEquals(bits(0.0), bits(lo));
      assertEquals(bits(x), bits(hi - lo), Oracle.hex(x));
    }
  }

  /**
   * L1 (b), (c): on the k == 0 domain c is in (-0.37, 0.37) with x's sign, q = (x*c)/(2.0-c) is
   * positive and normal, (x*c)/(c-2.0) is -q bit for bit, and the general tail equals line 147.
   */
  @Test
  void l1SignSymmetryAndPositiveQuotient() {
    for (double x : kZeroDomain()) {
      String at = Oracle.hex(x);
      double c = c(x);
      assertTrue(Math.abs(c) < 0.37, at);
      assertEquals(Math.signum(x), Math.signum(c), at);
      assertTrue(Math.abs(c) >= 0.94 * Math.abs(x), at);
      double q = (x * c) / (2.0 - c);
      assertTrue(q >= Double.MIN_NORMAL, at);
      assertEquals(bits(-q), bits((x * c) / (c - 2.0)), at);
      double general = 1.0 - ((0.0 - q) - x);
      double kZero = 1.0 - ((x * c) / (c - 2.0) - x);
      assertEquals(bits(kZero), bits(general), at);
    }
  }

  /**
   * L1 (d): k must be forced to 0 by the high-word test, not taken from the formula: at the top of
   * the k == 0 domain's high word, above 0.5 ln2, the formula gives k = 1.
   */
  @Test
  void l1KIsForcedByTheHighWordTest() {
    double x = Math.nextDown(REDUCED_MIN);
    assertEquals(Exp.HX_HALF_LN2, (int) (bits(x) >>> 32));
    assertEquals(1, (int) kArg(x));
    assertEquals(-1, (int) kArg(-x));
    // The first double of that word whose result the formula's k would change: the far form
    // with k = 1 is not exp there, the k == 0 tail is.
    double w = Double.longBitsToDouble(0x3fd62e42_fefa3a08L);
    assertEquals(1, (int) kArg(w));
    assertNotEquals(bits(StrictMath.exp(w)), bits(farForm(w, 1)));
    assertEquals(bits(StrictMath.exp(w)), bits(farForm(w, 0)));
  }

  /**
   * L1's sat check: the domain's top word changed by one, 0x3fd62e43, is refuted. The k == 0 tail
   * still gives exp at that word's low words 0 and 1, both signs, and first differs at low word 4
   * (positive) and 0x1f (negative).
   */
  @Test
  void l1DomainWidenedByAWordIsRefuted() {
    for (long b : new long[] {
      0x3fd62e43_00000000L, 0x3fd62e43_00000001L, 0xbfd62e43_00000000L, 0xbfd62e43_00000001L
    }) {
      double x = Double.longBitsToDouble(b);
      assertEquals(bits(StrictMath.exp(x)), bits(farForm(x, 0)), Oracle.hex(x));
    }
    for (long b : new long[] {0x3fd62e43_00000004L, 0xbfd62e43_0000001fL}) {
      double x = Double.longBitsToDouble(b);
      assertNotEquals(bits(StrictMath.exp(x)), bits(farForm(x, 0)), Oracle.hex(x));
    }
  }

  /** e_exp.c's far form and general tail for a given k, scalar, with the exponent add. */
  private static double farForm(double x, int k) {
    double t = k;
    double hi = x - t * Exp.LN2HI_0;
    double lo = t * Exp.LN2LO_0;
    double r = hi - lo;
    double c = c(r);
    double y = 1.0 - ((lo - (r * c) / (2.0 - c)) - hi);
    return Double.longBitsToDouble(bits(y) + ((long) k << 52));
  }

  // L2: the near band's form is the far form.

  /**
   * L2: at the band's boundary doubles the far form's argument lies in (1, 2) in magnitude, with
   * the margins PROOFS.md quotes; by monotonicity so does every double between, and k = +-1.
   * One word outside the band at either end the bound fails (the sat checks).
   */
  @Test
  void l2BandBoundariesGiveKOfOne() {
    double lo = kArg(REDUCED_MIN);
    double hi = kArg(NEAR_MAX);
    assertTrue(lo - 1.0 >= 1.37e-9, "lower margin " + (lo - 1.0));
    assertTrue(2.0 - hi >= 3.39e-7, "upper margin " + (2.0 - hi));
    assertTrue(lo < 1.0 + 1.38e-9 && 2.0 - hi < 3.40e-7, "margins as quoted");
    for (double x : new double[] {REDUCED_MIN, NEAR_MAX}) {
      assertEquals(1, (int) kArg(x));
      assertEquals(-1, (int) kArg(-x));
      assertEquals(bits(-kArg(x)), bits(kArg(-x)));
    }
    // The band widened by a word: the lower word's first double gives k = 0, and past the top
    // word, x = 0x3ff0a2b2ffffffff, the far form gives k = 2, not the near form's 1.
    assertEquals(0, (int) kArg(Lanes.fromHi(Exp.HX_HALF_LN2)));
    long aboveTop = Lanes.fromHiBits(Exp.HX_THREE_HALVES_LN2) | 0xffffffffL;
    assertEquals(2, (int) kArg(Double.longBitsToDouble(aboveTop)));
  }

  /** L2: inside the band, k = +-1 and the far form's hi, lo and k are the near form's. */
  @Test
  void l2FarFormIsNearForm() {
    assertEquals(bits(Exp.LN2HI_1), bits(-Exp.LN2HI_0));
    assertEquals(bits(Exp.LN2LO_1), bits(-Exp.LN2LO_0));
    double[] xs = Inputs.concat(Inputs.around(REDUCED_MIN, 8), Inputs.around(NEAR_MAX, 8),
        Inputs.uniform(802L, 1 << 16, REDUCED_MIN, NEAR_MAX));
    for (double ax : xs) {
      if (ax < REDUCED_MIN || ax > NEAR_MAX) {
        continue;
      }
      for (double x : new double[] {ax, -ax}) {
        String at = Oracle.hex(x);
        int xsb = x < 0 ? 1 : 0;
        int k = (int) kArg(x);
        assertEquals(1 - 2 * xsb, k, at);
        double t = k;
        double hiFar = x - t * Exp.LN2HI_0;
        double loFar = t * Exp.LN2LO_0;
        double hiNear = x - (xsb == 0 ? Exp.LN2HI_0 : Exp.LN2HI_1);
        double loNear = xsb == 0 ? Exp.LN2LO_0 : Exp.LN2LO_1;
        assertEquals(bits(hiNear), bits(hiFar), at);
        assertEquals(bits(loNear), bits(loFar), at);
      }
    }
  }

  // L3: the high-word compares as one signed 64-bit compare.

  /**
   * L3's hinges: absBits clears every sign bit, NaNs included, so each lane is a non-negative
   * long; every exp threshold h is in [0, 0x7fffffff), so h << 32 and h << 32 - 1 do not wrap.
   * Without the cleared sign a sign-set NaN would miss the filter.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l3AbsBitsAreNonNegative(VectorWidth width) {
    var species = width.species();
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(803L, 1 << 12));
    int n = species.length();
    for (int base = 0; base + n <= xs.length; base += n) {
      LongVector bits = Lanes.bits(DoubleVector.fromArray(species, xs, base));
      long[] abs = Lanes.absBits(bits).toArray();
      for (int i = 0; i < n; i++) {
        assertTrue(abs[i] >= 0, Oracle.hex(xs[base + i]));
        assertEquals(bits(xs[base + i]) & Long.MAX_VALUE, abs[i]);
      }
    }
    for (int h : new int[] {
        Exp.HX_FILTER, Exp.HX_HALF_LN2, Exp.HX_THREE_HALVES_LN2, Exp.HX_TINY, Exp.HX_NON_FINITE}) {
      assertTrue(h >= 0 && h < 0x7fffffff);
      assertTrue(Lanes.fromHiBits(h) - 1 >= -1 && Lanes.fromHiBits(h + 1) > 0);
    }
    double negativeNan = Double.longBitsToDouble(0xfff8_0000_0000_0000L);
    LongVector raw = Lanes.bits(DoubleVector.broadcast(species, negativeNan));
    assertFalse(Lanes.hiAtLeast(raw, Exp.HX_FILTER).anyTrue(), "raw bits, sign set");
    VectorMask<Long> cleared = Lanes.hiAtLeast(Lanes.absBits(raw), Exp.HX_FILTER);
    assertTrue(cleared.allTrue(), "sign cleared");
  }

  // L4: the tiny lanes are not folded.

  /**
   * L4: at x = 5 * 2^-53, 1 + x is a tie that rounds to even, StrictMath.exp's result, while the
   * general tail with k = 0 rounds the larger x + q up past it.
   */
  @Test
  void l4TinyLanesCannotBeFolded() {
    double x = 5 * 0x1p-53;
    assertTrue(Math.abs(x) < TWO_M28);
    double c = c(x);
    double q = (x * c) / (2.0 - c);
    double folded = 1.0 - ((0.0 - q) - x);
    assertEquals(bits(0x1.0000000000002p0), bits(1.0 + x));
    assertEquals(bits(1.0 + x), bits(StrictMath.exp(x)));
    assertEquals(bits(0x1.0000000000003p0), bits(folded));
    assertNotEquals(bits(StrictMath.exp(x)), bits(folded));
  }

  // L5: halF[xsb] from the sign bit.

  /** L5: 0.5 with x's sign bit is halF[xsb], for both signs and the signed zeros and NaNs. */
  @Test
  void l5HalfFromSignBit() {
    for (double x : Inputs.specials()) {
      long half = (bits(x) & Long.MIN_VALUE) | bits(0.5);
      assertEquals(bits(bits(x) < 0 ? -0.5 : 0.5), half, Oracle.hex(x));
    }
  }

  // L6: log's k == 0 forms are its k != 0 forms with dk = +0.

  /**
   * log's lines 109-113 for one double, scalar, as the C computes them: {@code {xn, f, k}} with
   * {@code xn} the normalized {@code x} or {@code x/2} and {@code f = xn - 1.0}. Any bit pattern
   * is accepted; for a rare one the values are what the vector body computes and discards.
   */
  private static double[] logReduce(double x) {
    long b = bits(x);
    int hx = (int) (b >> 32);
    int k = (hx >> 20) - 1023;
    hx &= 0x000fffff;
    int i = (hx + Log.SQRT2_SPLIT) & 0x100000;
    long hi = (hx | (i ^ 0x3ff00000)) & 0xffffffffL;
    double xn = Double.longBitsToDouble((hi << 32) | (b & 0xffffffffL));
    k += i >> 20;
    return new double[] {xn, xn - 1.0, k};
  }

  /** The three non-exact returns of e_log.c's k != 0 forms with the given dk, scalar. */
  private static double[] kNonZeroForms(double f, double dk) {
    double s = f / (2.0 + f);
    double z = s * s;
    double w = z * z;
    double t1 = w * (Log.LG2 + w * (Log.LG4 + w * Log.LG6));
    double t2 = z * (Log.LG1 + w * (Log.LG3 + w * (Log.LG5 + w * Log.LG7)));
    double r = t2 + t1;
    double hfsq = 0.5 * f * f;
    double rSmall = f * f * (0.5 - Log.THIRD * f);
    double dkHi = dk * Log.LN2_HI;
    double dkLo = dk * Log.LN2_LO;
    return new double[] {
      dkHi - ((s * (f - r) - dkLo) - f),
      dkHi - ((hfsq - (s * (hfsq + r) + dkLo)) - f),
      dkHi - ((rSmall - dkLo) - f),
      dkHi + dkLo
    };
  }

  /** The same returns' k == 0 forms, scalar, as e_log.c writes them. */
  private static double[] kZeroForms(double f) {
    double s = f / (2.0 + f);
    double z = s * s;
    double w = z * z;
    double t1 = w * (Log.LG2 + w * (Log.LG4 + w * Log.LG6));
    double t2 = z * (Log.LG1 + w * (Log.LG3 + w * (Log.LG5 + w * Log.LG7)));
    double r = t2 + t1;
    double hfsq = 0.5 * f * f;
    double rSmall = f * f * (0.5 - Log.THIRD * f);
    return new double[] {f - s * (f - r), f - (hfsq - s * (hfsq + r)), f - rSmall, Log.ZERO};
  }

  /**
   * The k == 0 lanes, [sqrt(2)/2, sqrt(2)): both ends and their neighbours (the ones outside
   * have k = +-1 and are skipped where k == 0 is the hypothesis), 1.0 and its
   * neighbours, each high word's edges near 1.0, and seeded draws.
   */
  private static double[] logKZeroDomain() {
    double lo = Lanes.fromHi(0x3fe6a09c);
    double hi = Lanes.fromHi(0x3ff6a09c);
    SplittableRandom random = new SplittableRandom(810L);
    double[] draws = new double[1 << 16];
    for (int i = 0; i < draws.length; i++) {
      draws[i] = random.nextDouble(lo, hi);
    }
    return Inputs.concat(Inputs.around(lo, 64), Inputs.around(hi, 64), Inputs.around(1.0, 4096),
        Inputs.around(Lanes.fromHi(0x3ff00001), 64), Inputs.around(Lanes.fromHi(0x3fefffff), 64),
        draws);
  }

  /**
   * L6 (H8): dk is +0 on the k == 0 lanes, by I2D of 0 and by the exact conversion of L7 alike,
   * and +0 times ln2_hi or ln2_lo is +0. Had dk been -0, both products would be -0, and the f ==
   * 0 lane's {@code dk*ln2_hi+dk*ln2_lo} would be -0, not the C's zero.
   */
  @Test
  void l6ZeroDkIsPositiveZero() {
    int k = 0;
    double dk = k;
    assertEquals(0L, bits(dk));
    assertEquals(0L, bits(dk * Log.LN2_HI));
    assertEquals(0L, bits(dk * Log.LN2_LO));
    assertEquals(0L, bits(dk * Log.LN2_HI + dk * Log.LN2_LO));
    double negative = -0.0;
    assertEquals(Long.MIN_VALUE, bits(negative * Log.LN2_HI + negative * Log.LN2_LO));
  }

  /**
   * L6 (H9): f = xn - 1.0 is never -0, nor a NaN or infinite, on any lane, rare ones included:
   * xn's high word has a clear sign and the exponent of [0.5, 2), the subtraction is exact
   * (Sterbenz) and zero only at xn = 1.0, where it is +0. Over the specials, sampled patterns
   * of every kind and the branch words' edges.
   */
  @Test
  void l6FIsNeverNegativeZero() {
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(811L, 1 << 16),
        logKZeroDomain(), Inputs.uniform(812L, 1 << 12, 0.0, Double.MIN_NORMAL));
    for (double x : xs) {
      double[] red = logReduce(x);
      double xn = red[0];
      double f = red[1];
      String at = Oracle.hex(x);
      assertTrue(xn >= 0.5 && xn < 2.0, at);
      assertTrue(Double.isFinite(f), at);
      assertTrue(bits(f) != Long.MIN_VALUE, at);
      assertEquals(xn == 1.0, f == 0.0, at);
      assertEquals(new BigDecimal(xn).subtract(BigDecimal.ONE),
          new BigDecimal(f), at);
    }
  }

  /**
   * L6: on the k == 0 lanes every k != 0 form with dk = +0 gives the k == 0 form's bits, each
   * form on every lane (not only the lanes that keep it), so the identity is checked wherever
   * the vector body computes it; form (d)'s {@code +0 + +0} is the C's zero on every lane, the
   * f == 0 ones and the rest. And the band's {@code s*(hfsq+R)} is never -0 there, nor is any
   * lane's hfsq (H10).
   */
  @Test
  void l6FoldedFormsAreTheKZeroForms() {
    for (double x : logKZeroDomain()) {
      double[] red = logReduce(x);
      String at = Oracle.hex(x);
      boolean kZero = x >= Lanes.fromHi(0x3fe6a09c) && x < Lanes.fromHi(0x3ff6a09c);
      assertEquals(kZero, (int) red[2] == 0, at);
      if (!kZero) {
        continue;
      }
      double f = red[1];
      double[] folded = kNonZeroForms(f, 0.0);
      double[] c = kZeroForms(f);
      for (int form = 0; form < c.length; form++) {
        assertEquals(bits(c[form]), bits(folded[form]), at + " form " + form);
      }
      double hfsq = 0.5 * f * f;
      assertTrue(bits(hfsq) >= 0, at);
      double s = f / (2.0 + f);
      double z = s * s;
      double w = z * z;
      double r = z * (Log.LG1 + w * (Log.LG3 + w * (Log.LG5 + w * Log.LG7)))
          + w * (Log.LG2 + w * (Log.LG4 + w * Log.LG6));
      double shr = s * (hfsq + r);
      assertTrue(f == 0.0 || shr != 0.0, at);
      assertTrue(bits(shr) != Long.MIN_VALUE, at);
    }
  }

  /**
   * L6's hinge, refuted: with f = -0, the case H9 excludes, the fold is not the C. For B = +0 the
   * C's k == 0 form {@code f - B} is -0 and the k != 0 form with dk = +0 gives +0; and with dk =
   * -0 (H8 false) the f == 0 lane would give -0 for the C's zero.
   */
  @Test
  void l6HingesRefuted() {
    double f = -0.0;
    double b = 0.0;
    double dk = 0.0;
    assertEquals(Long.MIN_VALUE, bits(f - b));
    assertEquals(0L, bits(dk * Log.LN2_HI - ((b - dk * Log.LN2_LO) - f)));
    double hfsq = -0.0;
    double shr = -0.0;
    // H10 false: hfsq = -0 with s*(hfsq+R) = -0 makes shr + (+0) a different operand.
    assertNotEquals(bits(hfsq - shr), bits(hfsq - (shr + 0.0)));
  }

  // L7: dk without L2D.

  /** L7's conversion, as Log computes it. */
  private static double exactDk(long k) {
    return Double.longBitsToDouble(k + Log.DK_MAGIC_BITS) - Log.DK_MAGIC;
  }

  /**
   * L7: fromBits(k + DK_MAGIC_BITS) - DK_MAGIC has the bits of Java's (double)k, I2D's and the
   * C's cast, for every k log can reach, [-1077, 3073], and at the domain's ends and sampled
   * longs in [-2^51, 2^51); +0.0 at k = 0. Just outside the domain, at 2^51 + 1 and -2^51 - 1,
   * and with the constant's bits one more, it is not k: the hinges, refuted.
   */
  @Test
  void l7ExactConversionIsI2D() {
    assertEquals(bits(Log.DK_MAGIC), Log.DK_MAGIC_BITS);
    assertEquals(0L, bits(exactDk(0)));
    for (long k = -1077; k <= 3073; k++) {
      assertEquals(bits((double) k), bits(exactDk(k)), Long.toString(k));
    }
    SplittableRandom random = new SplittableRandom(820L);
    long top = 1L << 51;
    for (int n = 0; n < 1 << 16; n++) {
      long k = random.nextLong(-top, top);
      assertEquals(bits((double) k), bits(exactDk(k)), Long.toString(k));
    }
    for (long k : new long[] {-top, -top + 1, top - 1, top}) {
      assertEquals(bits((double) k), bits(exactDk(k)), Long.toString(k));
    }
    assertNotEquals(bits((double) (top + 1)), bits(exactDk(top + 1)));
    assertNotEquals(bits((double) (-top - 1)), bits(exactDk(-top - 1)));
    assertNotEquals(0L,
        bits(Double.longBitsToDouble(Log.DK_MAGIC_BITS + 1) - Log.DK_MAGIC));
  }

  // L8: the C's int words in long lanes.

  /**
   * L8: for every bit pattern with the sign clear, the long-lane words Log computes are the C's
   * int words, widened, and kBits is the C's k plus DK_MAGIC_BITS (with the subnormal's -54
   * too); for every pattern, either sign, k lies in [-1077, 3073]. A negative pattern's high word
   * is not the C's: the sign hypothesis, refuted.
   */
  @Test
  void l8LongWordsAreTheCsInts() {
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(821L, 1 << 16),
        logKZeroDomain(), Inputs.uniform(822L, 1 << 12, 0.0, Double.MIN_NORMAL));
    for (double x : xs) {
      for (double v : new double[] {x, x * Log.TWO54}) {
        long b = bits(v);
        String at = Oracle.hex(v);
        long hx = b >>> 32;
        long hxm = hx & 0x000fffff;
        long i = (hxm + Log.SQRT2_SPLIT) & 0x100000;
        long newhi = hxm | (i ^ 0x3ff00000);
        long kBits = ((hx >>> 20) + (Log.DK_MAGIC_BITS - 1023)) + (i >>> 20);
        long k = kBits - Log.DK_MAGIC_BITS;
        // The blended arm's subnormal lanes subtract 54 more, so its k is at least -1077, L7's
        // lower end.
        assertTrue(k >= -1023 && k <= 3073, at);
        int chx = (int) (b >> 32);
        if (b < 0) {
          assertNotEquals((long) chx, hx, at);
          continue;
        }
        int chxm = chx & 0x000fffff;
        int ci = (chxm + Log.SQRT2_SPLIT) & 0x100000;
        assertEquals((long) chx, hx, at);
        assertEquals((long) chxm, hxm, at);
        assertEquals((long) ci, i, at);
        assertEquals((long) (chxm | (ci ^ 0x3ff00000)), newhi, at);
        assertEquals((long) (((chx >> 20) - 1023) + (ci >> 20)), k, at);
        assertEquals(((0x000fffff & (2 + chxm)) < 3), ((hxm + 2) & 0x000fffff) < 3, at);
        assertEquals(((chxm - Log.HX_BAND_LO) | (Log.HX_BAND_HI - chxm)) > 0,
            ((hxm - Log.HX_BAND_LO) | (Log.HX_BAND_HI - hxm)) > 0, at);
      }
    }
  }

  // L9: the common and subnormal lanes by one biased compare.

  /** L9's compare, as Log writes it. */
  private static boolean biased(long b, long lo, long hi) {
    return b + (Long.MIN_VALUE - lo) < Long.MIN_VALUE + (hi - lo);
  }

  /**
   * L9: the biased compare is the C's tests for the common lanes and the subnormals, on the
   * specials, sampled patterns and both sides of each bound; without the bias a negative
   * pattern would pass as common (the hinge, refuted).
   */
  @Test
  void l9RangeComparesAreTheCs() {
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(823L, 1 << 16));
    long[] edges = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, Log.COMMON_LO - 1, Log.COMMON_LO,
        Log.COMMON_HI - 1, Log.COMMON_HI, Log.COMMON_HI + 1, Long.MIN_VALUE + Log.COMMON_LO};
    long[] all = new long[xs.length + edges.length];
    for (int n = 0; n < xs.length; n++) {
      all[n] = bits(xs[n]);
    }
    System.arraycopy(edges, 0, all, xs.length, edges.length);
    for (long b : all) {
      int hx = (int) (b >> 32);
      int lx = (int) b;
      String at = Long.toHexString(b);
      boolean common = !(hx < Log.HX_MIN_NORMAL) && !(hx >= Log.HX_NON_FINITE);
      boolean sub = hx < Log.HX_MIN_NORMAL && ((hx & 0x7fffffff) | lx) != 0 && !(hx < 0);
      assertEquals(common, biased(b, Log.COMMON_LO, Log.COMMON_HI), at);
      assertEquals(sub, biased(b, 1, Log.COMMON_LO), at);
    }
    long negative = Double.doubleToRawLongBits(-1.0);
    assertTrue(negative - Log.COMMON_LO < Log.COMMON_HI - Log.COMMON_LO);
    assertFalse(biased(negative, Log.COMMON_LO, Log.COMMON_HI));
  }

  // L10: expm1's reduction is exp's, with exp's folds.

  /**
   * expm1's k == 0 domain sampled, 2^-54 <= |x| <= the top of word 0x3fd62e42: both ends, the
   * neighbours of each, and seeded draws over every binade between, both signs.
   */
  private static double[] expm1KZeroDomain() {
    double lowEnd = Lanes.fromHi(Expm1.HX_TINY);
    double hiEnd = Math.nextDown(Lanes.fromHi(Expm1.HX_HALF_LN2 + 1));
    double[] ends = Inputs.concat(Inputs.around(lowEnd, 8), Inputs.around(hiEnd, 8));
    SplittableRandom random = new SplittableRandom(900L);
    double[] xs = new double[ends.length + (1 << 16)];
    System.arraycopy(ends, 0, xs, 0, ends.length);
    for (int i = ends.length; i < xs.length; i++) {
      xs[i] = Math.scalb(1.0 + random.nextDouble(), random.nextInt(-54, -1));
    }
    double[] both = new double[2 * xs.length];
    int next = 0;
    for (double x : xs) {
      if (Math.abs(x) >= lowEnd && Math.abs(x) <= hiEnd) {
        both[next++] = x;
        both[next++] = -x;
      }
    }
    return Arrays.copyOf(both, next);
  }

  /**
   * L10 (a), the hinge that carries exp's L2 and L5 over: expm1's invln2, ln2_hi, ln2_lo, the
   * bits of 0.5 and the two words of its reduction are exp's, bit for bit; and the C's near form
   * for a negative x, {@code x + ln2_hi} and {@code -ln2_lo}, is exp's {@code x - ln2HI[1]} and
   * {@code ln2LO[1]}, over the band's ends and seeded draws.
   */
  @Test
  void l10ReductionIsExps() {
    assertEquals(bits(Exp.INVLN2), bits(Expm1.INVLN2));
    assertEquals(bits(Exp.LN2HI_0), bits(Expm1.LN2_HI));
    assertEquals(bits(Exp.LN2LO_0), bits(Expm1.LN2_LO));
    assertEquals(Exp.HALF_BITS, Expm1.HALF_BITS);
    assertEquals(Exp.HX_HALF_LN2, Expm1.HX_HALF_LN2);
    assertEquals(Exp.HX_THREE_HALVES_LN2, Expm1.HX_THREE_HALVES_LN2);
    assertEquals(bits(Exp.LN2LO_1), bits(-Expm1.LN2_LO));
    double[] xs = Inputs.concat(Inputs.around(REDUCED_MIN, 8), Inputs.around(NEAR_MAX, 8),
        Inputs.uniform(901L, 1 << 16, REDUCED_MIN, NEAR_MAX));
    for (double ax : xs) {
      if (ax < REDUCED_MIN || ax > NEAR_MAX) {
        continue;
      }
      double x = -ax;
      int k = (int) kArg(x);
      assertEquals(-1, k, Oracle.hex(x));
      double t = k;
      assertEquals(bits(x + Expm1.LN2_HI), bits(x - t * Expm1.LN2_HI), Oracle.hex(x));
      assertEquals(bits(-Expm1.LN2_LO), bits(t * Expm1.LN2_LO), Oracle.hex(x));
    }
  }

  /**
   * L10 (b): with k forced to 0, t = +0, so hi = x, lo = +0, r = hi - lo = x and c = (hi-r)-lo
   * = +0, bit for bit, over expm1's k == 0 domain, both signs.
   */
  @Test
  void l10UnreducedLanesKeepXWithZeroCorrection() {
    double t = 0.0;
    for (double x : expm1KZeroDomain()) {
      String at = Oracle.hex(x);
      double hi = x - t * Expm1.LN2_HI;
      double lo = t * Expm1.LN2_LO;
      double r = hi - lo;
      assertEquals(bits(x), bits(hi), at);
      assertEquals(bits(0.0), bits(lo), at);
      assertEquals(bits(x), bits(r), at);
      assertEquals(bits(0.0), bits((hi - r) - lo), at);
    }
  }

  /**
   * H14: k must be forced to 0 by the high-word test. At the top of word 0x3fd62e42 the
   * formula gives k = +-1, and at the first such doubles from the top whose result it changes,
   * {@code 0x3fd62e42_ffffffec} and {@code 0xbfd62e42_fffffff3}, the C reduced with that k is not
   * StrictMath.expm1, while the C as written is.
   */
  @Test
  void l10KIsForcedByTheHighWordTest() {
    for (long b : Expm1ProofFiles.witnesses()) {
      double x = Double.longBitsToDouble(b);
      String at = Oracle.hex(x);
      assertEquals(x > 0 ? 1 : -1, (int) kArg(x), at);
      assertEquals(bits(StrictMath.expm1(x)), bits(Expm1Test.cExpm1(x, false)), at);
      assertNotEquals(bits(StrictMath.expm1(x)), bits(Expm1Test.cExpm1(x, true)), at);
    }
    // Above each, to the top of the word, the unforced k still gives the right double: these
    // are the first witnesses, so the test of the word's top doubles alone would miss them.
    for (long w : Expm1ProofFiles.witnesses()) {
      for (long low = 0xffffffffL; low > (w & Lanes.LOW_WORD); low--) {
        double x = Double.longBitsToDouble((w & ~Lanes.LOW_WORD) | low);
        assertEquals(bits(StrictMath.expm1(x)), bits(Expm1Test.cExpm1(x, true)), Oracle.hex(x));
      }
    }
  }

  // L11: expm1's k == 0 return through the k != 0 inner term.

  /**
   * L11: on the k == 0 domain, with c = +0 (L10 (b)), the inner term (r*(e-c)-c)-hxs is line
   * 189's r*e-hxs bit for bit, so r minus it is the C's k == 0 return; and e - (+0) and v - (+0)
   * are e and v, -0 included.
   */
  @Test
  void l11KZeroReturnIsTheInnerTermsForm() {
    double c = 0.0;
    assertEquals(bits(-0.0), bits(-0.0 - c));
    for (double r : expm1KZeroDomain()) {
      String at = Oracle.hex(r);
      double hfx = 0.5 * r;
      double hxs = r * hfx;
      double r1 = 1.0 + hxs * (Expm1.Q1 + hxs * (Expm1.Q2 + hxs * (Expm1.Q3 + hxs
          * (Expm1.Q4 + hxs * Expm1.Q5))));
      double t = 3.0 - r1 * hfx;
      double e = hxs * ((r1 - t) / (6.0 - r * t));
      // The encoding checks e - (+0) and v - (+0) for zeros and for normal doubles; that e
      // and v = r*e are normal here, never subnormal, is this bound.
      assertTrue(Math.abs(e) >= Double.MIN_NORMAL, at);
      assertTrue(Math.abs(r * e) >= Double.MIN_NORMAL, at);
      assertEquals(bits(e), bits(e - c), at);
      assertEquals(bits(r * e), bits(r * (e - c) - c), at);
      double inner = (r * (e - c) - c) - hxs;
      assertEquals(bits(r * e - hxs), bits(inner), at);
      assertEquals(bits(r - (r * e - hxs)), bits(r - inner), at);
      assertEquals(bits(StrictMath.expm1(r)), bits(r - inner), at);
    }
  }

  // L13: a blend of branches computes the C's branch on each lane.

  /** The compare of the models below: {@code ix < 0x3ff00000}, the high word of |x| below 1. */
  private static final int L13_WORD = 0x3ff00000;

  /** e_sinh.c lines 65-66's shape in scalar Java, as the C writes it. */
  private static double l13SinhShape(double x, double t) {
    int ix = (int) (bits(x) >>> 32) & 0x7fffffff;
    if (ix < L13_WORD) {
      return 2.0 * t - t * t / (t + 1.0);
    }
    return t + t / (t + 1.0);
  }

  /** The two branches of {@link #l13SinhShape}, each computed whole. */
  private static double l13SinhFirst(double t) {
    return 2.0 * t - t * t / (t + 1.0);
  }

  private static double l13SinhSecond(double t) {
    return t + t / (t + 1.0);
  }

  /** A branch that is NaN where the other is not: {@code t + 1} below 1, {@code sqrt(t)} above. */
  private static double l13SqrtShape(double x, double t) {
    int ix = (int) (bits(x) >>> 32) & 0x7fffffff;
    if (ix < L13_WORD) {
      return t + 1.0;
    }
    return Math.sqrt(t);
  }

  private static VectorMask<Double> l13Below(DoubleVector x, int word) {
    IntVector ix = Lanes.hiInt(x, Lanes.intSpecies(x.species())).and(0x7fffffff);
    return ix.compare(VectorOperators.LT, word).cast(x.species());
  }

  /** The port's shape: operands blended by the mask, one shared division, the sums blended. */
  private static DoubleVector l13SinhVector(DoubleVector x, DoubleVector t, int word) {
    VectorMask<Double> below = l13Below(x, word);
    DoubleVector q = t.blend(t.mul(t), below).div(t.add(1.0));
    return t.add(q).blend(t.mul(2.0).sub(q), below);
  }

  /** The select as arithmetic, {@code first*m + second*(1-m)}: the shape H4 excludes. */
  private static DoubleVector l13SinhArithmetic(DoubleVector x, DoubleVector t) {
    VectorMask<Double> below = l13Below(x, L13_WORD);
    DoubleVector m = DoubleVector.zero(x.species()).blend(1.0, below);
    DoubleVector qFirst = t.mul(t).div(t.add(1.0));
    DoubleVector qSecond = t.div(t.add(1.0));
    DoubleVector first = t.mul(2.0).sub(qFirst);
    DoubleVector second = t.add(qSecond);
    return first.mul(m).add(second.mul(m.neg().add(1.0)));
  }

  private static DoubleVector l13SqrtVector(DoubleVector x, DoubleVector t, int word) {
    return t.lanewise(VectorOperators.SQRT).blend(t.add(1.0), l13Below(x, word));
  }

  private static DoubleVector l13SqrtArithmetic(DoubleVector x, DoubleVector t) {
    VectorMask<Double> below = l13Below(x, L13_WORD);
    DoubleVector m = DoubleVector.zero(x.species()).blend(1.0, below);
    return t.add(1.0).mul(m).add(t.lanewise(VectorOperators.SQRT).mul(m.neg().add(1.0)));
  }

  /** x on both sides of the compare, with every neighbour of 1.0's word, both signs. */
  private static double[] l13Xs() {
    double[] near = Inputs.concat(Inputs.around(1.0, 8), Inputs.around(Math.nextDown(1.0), 8));
    double[] xs = Inputs.concat(near, Inputs.around(Lanes.fromHi(L13_WORD - 1), 4),
        Inputs.around(Lanes.fromHi(L13_WORD + 1), 4),
        new double[] {0.0, 1e-300, 0.25, 0.5, 2.0, 22.0, 1e300, Double.POSITIVE_INFINITY,
            Double.NaN});
    double[] both = new double[2 * xs.length];
    for (int i = 0; i < xs.length; i++) {
      both[2 * i] = xs[i];
      both[2 * i + 1] = -xs[i];
    }
    return both;
  }

  /** t with a finite and an infinite discarded value, a NaN one, the zeros, and draws. */
  private static double[] l13Ts() {
    double[] fixed = {
      -1.0, -4.0, -2.0, -0.5, 0.0, -0.0, 1e-300, 0.5, 1.0, 3.0, 1e155, 1e200, Double.MAX_VALUE,
      Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Double.MIN_VALUE
    };
    return Inputs.concat(fixed, Inputs.uniform(1300L, 48, -3.0, 3.0));
  }

  /** Non-vacuity: the inputs do exercise what the lemma says it survives. */
  @Test
  void l13InputsAreNotVacuous() {
    Inputs.Pairs p = Inputs.cross(l13Xs(), l13Ts());
    int branchesDiffer = 0;
    int discardedInfinite = 0;
    int discardedNaN = 0;
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double t = p.y()[i];
      int ix = (int) (bits(x) >>> 32) & 0x7fffffff;
      double first = l13SinhFirst(t);
      double second = l13SinhSecond(t);
      boolean takesFirst = ix < L13_WORD;
      if ((ix == L13_WORD || ix == L13_WORD - 1) && bits(first) != bits(second)) {
        branchesDiffer++;
      }
      double kept = takesFirst ? first : second;
      double discarded = takesFirst ? second : first;
      if (Double.isFinite(kept) && Double.isInfinite(discarded)) {
        discardedInfinite++;
      }
      double sqrtKept = takesFirst ? t + 1.0 : Math.sqrt(t);
      double sqrtDiscarded = takesFirst ? Math.sqrt(t) : t + 1.0;
      if (!Double.isNaN(sqrtKept) && Double.isNaN(sqrtDiscarded)) {
        discardedNaN++;
      }
    }
    assertTrue(branchesDiffer > 100, "branches differ in bits at the compare: " + branchesDiffer);
    assertTrue(discardedInfinite > 100, "kept finite, discarded infinite: " + discardedInfinite);
    assertTrue(discardedNaN > 100, "kept not NaN, discarded NaN: " + discardedNaN);
  }

  /**
   * L13: the operand blend with one shared division is the C's branch on every lane, at every
   * width, including the lanes where the other branch's value is infinite.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l13SinhShapedBlendIsTheCsBranch(VectorWidth width) {
    Inputs.Pairs p = Inputs.cross(l13Xs(), l13Ts());
    Oracle.assertBinary("l13 sinh shape", width.species(),
        (x, t) -> l13SinhVector(x, t, L13_WORD), ProofsTest::l13SinhShape, p.x(), p.y());
  }

  /** L13: a discarded branch whose value is NaN does not reach the lane. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l13DiscardedNaNDoesNotLeak(VectorWidth width) {
    Inputs.Pairs p = Inputs.cross(l13Xs(), l13Ts());
    Oracle.assertBinary("l13 sqrt shape", width.species(),
        (x, t) -> l13SqrtVector(x, t, L13_WORD), ProofsTest::l13SqrtShape, p.x(), p.y());
  }

  /** The sinh shape with H2 false: the shared division is by {@code t}, not {@code t+1}. */
  private static DoubleVector l13WrongOperand(DoubleVector x, DoubleVector t) {
    VectorMask<Double> below = l13Below(x, L13_WORD);
    DoubleVector q = t.blend(t.mul(t), below).div(t);
    return t.add(q).blend(t.mul(2.0).sub(q), below);
  }

  /** The sinh shape with H2 false: {@code 2.0*t - q} reassociated as {@code t + (t - q)}. */
  private static DoubleVector l13Reassociated(DoubleVector x, DoubleVector t) {
    VectorMask<Double> below = l13Below(x, L13_WORD);
    DoubleVector q = t.blend(t.mul(t), below).div(t.add(1.0));
    return t.add(q).blend(t.add(t.sub(q)), below);
  }

  /** A control must fail by the lanes differing, not by an empty or mismatched input. */
  private static void assertRefuted(org.junit.jupiter.api.function.Executable check) {
    AssertionFailedError e = assertThrows(AssertionFailedError.class, check);
    assertTrue(e.getMessage().contains(" differ"), e.getMessage());
  }

  /**
   * L13's controls: each way the lemma's hinges could be false fails the checks above. The
   * compare moved one word either way (H1) shows at the words next to 1.0's; the select as
   * arithmetic (H4) turns a discarded infinity or NaN into a NaN in the kept lane.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l13FalseHingesAreCaught(VectorWidth width) {
    Inputs.Pairs p = Inputs.cross(l13Xs(), l13Ts());
    var species = width.species();
    assertRefuted(() -> Oracle.assertBinary("l13 word + 1", species,
        (x, t) -> l13SinhVector(x, t, L13_WORD + 1), ProofsTest::l13SinhShape, p.x(), p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 word - 1", species,
        (x, t) -> l13SinhVector(x, t, L13_WORD - 1), ProofsTest::l13SinhShape, p.x(), p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 arithmetic", species,
        ProofsTest::l13SinhArithmetic, ProofsTest::l13SinhShape, p.x(), p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 sqrt word + 1",
        species, (x, t) -> l13SqrtVector(x, t, L13_WORD + 1), ProofsTest::l13SqrtShape, p.x(),
        p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 sqrt arithmetic",
        species, ProofsTest::l13SqrtArithmetic, ProofsTest::l13SqrtShape, p.x(), p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 wrong operand", species,
        ProofsTest::l13WrongOperand, ProofsTest::l13SinhShape, p.x(), p.y()));
    assertRefuted(() -> Oracle.assertBinary("l13 reassociated", species,
        ProofsTest::l13Reassociated, ProofsTest::l13SinhShape, p.x(), p.y()));
    // H3 can fail: an integer division on a discarded lane throws, a float one never does.
    var ints = Lanes.intSpecies(species);
    assertThrows(ArithmeticException.class,
        () -> IntVector.broadcast(ints, 1).div(IntVector.zero(ints)));
  }

  // L15: expm1's words in long lanes, as Varka emits them.

  /** L15: Expm1's thresholds of k are the C's literals, 56 (line 197) and 20 (line 203). */
  @Test
  void l15ThresholdsAreTheCs() {
    assertEquals(56, Expm1.K_FAR);
    assertEquals(20, Expm1.K_SPLIT);
  }

  /**
   * L15 (a), (b): for every k from -1100 to 1100 whose form reads a word, the long word is
   * the C's int word widened, and the double it heads is the one the C's form means, 1-2^-k for
   * {@code 2 <= k < 20} and 2^-k for {@code 20 <= k <= 56}, the C's literals; and at a k where
   * the int and the long shifts differ, k = 40, the lane's form reads the other word (H16).
   */
  @Test
  void l15LongWordsAreExpm1sInts() {
    // The far, k == 1, k == 0 and k == -1 lanes read t = one, whose word heads 1.0, for every k.
    assertEquals(bits(1.0), Lanes.fromHiBits(0x3ff00000));
    assertEquals(bits(1.0), 0x3ff00000L << 32);
    int read = 0;
    for (int k = -1100; k <= 1100; k++) {
      long kl = k;
      String at = "k = " + k;
      if (k >= 2 && k < 20) {
        int word = 0x3ff00000 - (0x200000 >> k);
        long longWord = 0x3ff00000L - (0x200000L >>> kl);
        assertEquals((long) word, longWord, at);
        assertEquals(bits(1.0 - Math.scalb(1.0, -k)), longWord << 32, at);
        assertEquals(Lanes.fromHiBits(word), longWord << 32, at);
        read++;
      } else if (k >= 20 && k <= 56) {
        int word = (0x3ff - k) << 20;
        long longWord = (0x3ffL - kl) << 20;
        assertEquals((long) word, longWord, at);
        assertEquals(bits(Math.scalb(1.0, -k)), longWord << 32, at);
        assertEquals(Lanes.fromHiBits(word), longWord << 32, at);
        read++;
      }
    }
    assertEquals(55, read);
    // k = 40, a lane of the high form, which does not read the mid word: an int shift takes the
    // count modulo 32 and a long one modulo 64, so the words differ there, and H16 is needed.
    assertNotEquals((long) (0x200000 >> 40), 0x200000L >>> 40L);
  }

  /**
   * L15 (d): the C's {@code __HI(y) += (k<<20)} is {@code yBits + ((long) k << 52)}: the low word
   * kept, no carry into the high word, and the high word the int sum modulo 2^32, the carry out
   * of bit 63 dropped. Over every k from -1100 to 1100 and high words that wrap either way.
   */
  @Test
  void l15ExponentAddIsTheCsHighWordAdd() {
    SplittableRandom random = new SplittableRandom(1500L);
    long[] ys = new long[1 << 10];
    int[] his = {0, 0x3fe00000, 0x3ff00000, 0x7fefffff, 0x7fffffff, 0x80000000, 0xbff00000,
        0xffffffff};
    int[] los = {0, 1, 0x7fffffff, 0x80000000, 0xffffffff};
    int n = 0;
    for (int hi : his) {
      for (int lo : los) {
        ys[n++] = ((long) hi << 32) | (lo & 0xffffffffL);
      }
    }
    while (n < ys.length) {
      ys[n++] = random.nextLong();
    }
    for (int k = -1100; k <= 1100; k++) {
      for (long y : ys) {
        int hi = (int) (y >>> 32);
        int lo = (int) y;
        long want = ((long) (hi + (k << 20)) << 32) | (lo & 0xffffffffL);
        assertEquals(want, y + ((long) k << 52), () -> "y = " + Long.toHexString(y));
      }
    }
  }

  /** The C's k for x on the lanes it reduces (lines 162 and 170), and 0 on the others (L10). */
  private static int l15K(double x) {
    int hx = (int) (bits(x) >>> 32) & 0x7fffffff;
    return hx > Expm1.HX_HALF_LN2 ? (int) (Expm1.INVLN2 * x + (x < 0 ? -0.5 : 0.5)) : 0;
  }

  /** A lane the C returns early from (lines 146-158 and 177), whose result Expm1 replaces. */
  private static boolean l15Rare(double x) {
    int hx = (int) (bits(x) >>> 32) & 0x7fffffff;
    return hx >= Expm1.HX_FILTER && !(x <= Expm1.O_THRESHOLD)
        || x < 0 && hx >= Expm1.HX_56_LN2 || hx < Expm1.HX_TINY;
  }

  /**
   * Inputs at every k the lanes kept can take, -56 to 1024 (L10's domain, its two ends checked
   * here), several per k, shuffled so that a vector mixes the forms of k.
   */
  private static double[] l15EveryK() {
    double lowest = -Math.nextDown(Lanes.fromHi(Expm1.HX_56_LN2));
    assertFalse(l15Rare(lowest));
    assertTrue(l15Rare(Math.nextDown(lowest)));
    assertEquals(-56, l15K(lowest));
    assertFalse(l15Rare(Expm1.O_THRESHOLD));
    assertTrue(l15Rare(Math.nextUp(Expm1.O_THRESHOLD)));
    assertEquals(1024, l15K(Expm1.O_THRESHOLD));
    // ln2 rounded; any value near it puts (k + d) * ln2 at k for these offsets d. At k = 0 the
    // offset 0 would give x = 0, a tiny lane, so that one input is 0.2 instead.
    double ln2 = 0x1.62e42fefa39efp-1;
    double[] offsets = {-0.49, -0.3, -0.1, 0.0, 0.1, 0.3, 0.49};
    double[] xs = new double[1081 * offsets.length + 2];
    int n = 0;
    xs[n++] = lowest;
    xs[n++] = Expm1.O_THRESHOLD;
    for (int k = -56; k <= 1024; k++) {
      for (double d : offsets) {
        double x = k == 0 && d == 0.0 ? 0.2 : (k + d) * ln2;
        xs[n++] = Math.min(Math.max(x, lowest), Expm1.O_THRESHOLD);
      }
    }
    boolean[] seen = new boolean[1081];
    for (double x : xs) {
      int k = l15K(x);
      assertFalse(l15Rare(x), Oracle.hex(x));
      assertTrue(k >= -56 && k <= 1024, Oracle.hex(x));
      seen[k + 56] = true;
    }
    for (int k = -56; k <= 1024; k++) {
      assertTrue(seen[k + 56], "no input at k = " + k);
    }
    SplittableRandom random = new SplittableRandom(1501L);
    for (int i = xs.length - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      double swap = xs[i];
      xs[i] = xs[j];
      xs[j] = swap;
    }
    return xs;
  }

  /**
   * L15's H16 on Expm1's own masks: Expm1.expm1Blended, the body Varka's kernel emits, against
   * StrictMath by raw bits on every k from -56 to 1024, with the forms mixed within a vector. A
   * mask that kept a word on a lane whose form does not read it (the mid word at k = 40, the high
   * word on a far lane) fails here wherever that word differs from the lane's own. The int mid
   * word equals one's on a far lane with k mod 32 >= 22, so a mid word kept there would pass.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l15MasksKeepEachWordOnItsLanes(VectorWidth width) {
    Oracle.assertUnary("l15 expm1Blended", width.species(), x -> Expm1.expm1Blended(x, null),
        StrictMath::expm1, l15EveryK());
  }

  // L17: log10's words in long lanes, as Varka emits them.

  /** The C's k of e_log10.c's lines 81 and 84 for the signed high word hx, scaled or not. */
  private static int l17CK(int hx, int scaled) {
    return (hx >> 20) - 1023 - 54 * scaled;
  }

  /**
   * L17 (a), (b): a loop over every exponent field, 0 to 0x7ff, scaled and not, which is every
   * value K takes: the long K, I and y against the C's int k, i and I2D, K in [-1077, 1024]; a
   * scaled subnormal's field is 3 to 54; and a loop over every significand word for both I and
   * five exponent fields: the long new high word against the C's, below 2^31.
   */
  @Test
  void l17LongWordsAreLog10sInts() {
    for (int field = 0; field <= 0x7ff; field++) {
      for (int scaled = 0; scaled < 2; scaled++) {
        int chx = field << 20;
        long hx = Integer.toUnsignedLong(chx);
        long k = (hx >>> 20) - 1023 - 54 * scaled;
        int ck = l17CK(chx, scaled);
        String at = "field " + field + ", scaled " + scaled;
        assertTrue(k >= -1077 && k <= 1024, at);
        assertEquals((long) ck, k, at);
        long i = k >>> 63;
        int ci = (int) (((long) ck & 0x80000000L) >>> 31);
        assertEquals((long) ci, i, at);
        double y = Double.longBitsToDouble(k + i + Log.DK_MAGIC_BITS) - Log.DK_MAGIC;
        assertEquals(bits((double) (ck + ci)), bits(y), at);
      }
    }
    // A scaled lane is a subnormal times 2^54: its field is 3 (2^-1020) to 54 (below 2^-968).
    assertEquals(3, (int) (bits(Double.MIN_VALUE * Log10.TWO54) >>> 52));
    assertEquals(54, (int) (bits(Math.nextDown(Double.MIN_NORMAL) * Log10.TWO54) >>> 52));
    for (int field : new int[] {0, 1, 0x3ff, 0x7fe, 0x7ff}) {
      for (int m = 0; m <= 0xfffff; m++) {
        int chx = (field << 20) | m;
        long hx = Integer.toUnsignedLong(chx);
        for (int ci = 0; ci < 2; ci++) {
          long i = ci;
          long word = (hx & 0x000fffff) | ((0x3ffL - i) << 20);
          int cword = (chx & 0x000fffff) | ((0x3ff - ci) << 20);
          if (word != (long) cword || word < 0 || word >= 1L << 31) {
            assertEquals((long) cword, word, "field " + field + ", m " + m + ", i " + i);
            assertTrue(word >= 0 && word < 1L << 31, "word " + Long.toHexString(word));
          }
        }
      }
    }
  }

  /**
   * L17 (c): the signed long compares on the raw bits are the C's {@code hx >= 0x7ff00000} and
   * {@code hx < 0}, on every high word within 2^16 of the words where an answer changes or the
   * int wraps (0, 0x7ff00000, 0x7fffffff and 0x80000000), with five low words each, and on
   * 2^20 sampled patterns.
   */
  @Test
  void l17RareComparesAreTheCs() {
    long nonFinite = Lanes.fromHiBits(Log10.HX_NON_FINITE) - 1;
    long[] los = {0L, 1L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL};
    SplittableRandom random = new SplittableRandom(1702L);
    int checked = 0;
    for (int edge : new int[] {0, Log10.HX_NON_FINITE, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      for (int d = -(1 << 16); d <= 1 << 16; d++) {
        int h = edge + d;
        for (long lo : los) {
          long b = ((long) h << 32) | lo;
          assertEquals(h >= Log10.HX_NON_FINITE, b > nonFinite, Long.toHexString(b));
          assertEquals(h < 0, b < 0, Long.toHexString(b));
          checked++;
        }
      }
    }
    for (int n = 0; n < 1 << 20; n++) {
      long b = random.nextLong();
      int h = (int) (b >> 32);
      assertEquals(h >= Log10.HX_NON_FINITE, b > nonFinite, Long.toHexString(b));
      assertEquals(h < 0, b < 0, Long.toHexString(b));
    }
    assertEquals(4 * ((1 << 17) + 1) * los.length, checked);
  }

  /**
   * L17's H18: the C answers every lane with the sign bit set early, a NaN or -inf for -0, so a
   * negative lane's words are never kept; this fails if the C kept one. And on some of those
   * lanes the long K is not the C's k, so H18 is needed.
   */
  @Test
  void l17NegativeLanesAreAnsweredEarly() {
    boolean kDiffers = false;
    for (double x : l17SignSet()) {
      long b = bits(x);
      double got = StrictMath.log10(x);
      assertTrue(x == 0.0 ? bits(got) == bits(Double.NEGATIVE_INFINITY) : Double.isNaN(got),
          Oracle.hex(x));
      long k = ((b >>> 32) >>> 20) - 1023;
      kDiffers |= k != l17CK((int) (b >> 32), 0);
    }
    assertTrue(kDiffers);
  }

  /**
   * Patterns with the sign bit set: one at every exponent field, the specials, 2^16 sampled
   * patterns and 2^12 subnormals, each with its sign set.
   */
  private static double[] l17SignSet() {
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(1700L, 1 << 16),
        Inputs.uniform(1701L, 1 << 12, 0.0, Double.MIN_NORMAL));
    double[] out = new double[0x800 + xs.length];
    for (int field = 0; field <= 0x7ff; field++) {
      long significand = field * 0x9e3779b97f4aL & 0xf_ffff_ffff_ffffL;
      out[field] = Double.longBitsToDouble(Long.MIN_VALUE | ((long) field << 52) | significand);
    }
    for (int j = 0; j < xs.length; j++) {
      out[0x800 + j] = Double.longBitsToDouble(bits(xs[j]) | Long.MIN_VALUE);
    }
    return out;
  }

  /**
   * L17's H18 on Log10's own masks: Log10.log10Blended, whose blends Varka's kernel emits in the
   * same order, against StrictMath by raw bits on the sign-set patterns, each beside its
   * positive twin so that a vector mixes rare and kept lanes. A kernel that kept a sign-set
   * lane's general form, its rare blend dropped or placed before the general one, fails here.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void l17NegativeLanesAreBlendedOver(VectorWidth width) {
    double[] signSet = l17SignSet();
    double[] xs = new double[2 * signSet.length];
    for (int j = 0; j < signSet.length; j++) {
      xs[2 * j] = signSet[j];
      xs[2 * j + 1] = Math.abs(signSet[j]);
    }
    Oracle.assertUnary("l17 log10Blended", width.species(), x -> Log10.log10Blended(x, null),
        StrictMath::log10, xs);
  }
}
