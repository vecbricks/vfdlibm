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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
}
