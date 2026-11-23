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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shape of {@code hard-cases/atan2.txt}, which {@code dev/testlibm-extract.py atn atan2}
 * derives from the {@code atn} lines of Lefevre's data: 12 pairs for each of its inputs
 * {@code t}, {@code (+-t * 2^j, +-2^j)} for {@code j} in 0, 1, -5, with {@code |y / x| = t}
 * exactly. The extractor checks the data; this checks that the committed file has the shape the
 * extractor gives, in the order it gives it, so a hand edit shows. And the claim of {@code
 * hard-cases/hypot.txt}, which {@code dev/hypot-hard-cases.py} computes: every pair's exact
 * {@code sqrt(x*x + y*y)} is a double or lies within {@code 2^-40} ulp of a midpoint. And the
 * claim of {@code hard-cases/pow.txt}, which {@code dev/pow-hard-cases.py} computes: every pair's
 * exact {@code |x|**y} is a double, a midpoint, within {@code 2^-28} ulp of one, or beside a
 * rounding threshold of the result, checked here with {@code BigInteger} arithmetic of its own.
 */
class HardCasePairsTest {

  private static final int[] SCALES = {0, 1, -5};
  private static final int INPUTS = 1735;

  @Test
  void theAtan2FileIsTheTwelvePairsOfEachInput() {
    Inputs.Pairs pairs = Inputs.hardCasePairs("atan2");
    int n = pairs.x().length;
    assertEquals(INPUTS * 12, n);
    Set<Double> seen = new HashSet<>();
    for (int group = 0; group < INPUTS; group++) {
      double t = Double.NaN;
      int at = group * 12;
      for (int scale = 0; scale < 3; scale++) {
        for (int k = 0; k < 4; k++) {
          // x is +2^j then -2^j, and for each y is +|t| * 2^j then -|t| * 2^j
          // the file's first column is atan2's first argument, y: Pairs.x() is that column
          double y = pairs.x()[at];
          double x = pairs.y()[at];
          double power = Math.scalb(1.0, SCALES[scale]);
          assertEquals((k < 2 ? 1.0 : -1.0) * power, x, "x of pair " + at);
          assertEquals(k % 2 == 0, y > 0, "the sign of y of pair " + at);
          double ratio = Math.abs(y / x);
          if (Double.isNaN(t)) {
            t = ratio;
          }
          assertEquals(t, ratio, "|y/x| of pair " + at);
          // exact: scaling by a power of two cannot round
          assertEquals(Math.abs(y), Math.scalb(t, SCALES[scale]), "|y| of pair " + at);
          at++;
        }
      }
      // inside e_atan2.c's range where atan(|y/x|) is taken for every sign of x: |k| <= 60
      assertTrue(t > 0x1p-60 && t < 0x1p60, "t of group " + group);
      assertTrue(seen.add(t), "t of group " + group + " is a repeat");
    }
    assertEquals(INPUTS, seen.size());
  }

  /**
   * Every pair of {@code hard-cases/hypot.txt} is what its header says, by exact arithmetic on
   * {@code s = x*x + y*y}: either the square of a double near {@code StrictMath.hypot}'s result
   * (an exact result), or within {@code 2^-40} ulp of a midpoint between two doubles next to it,
   * the distance being {@code |s - m*m| / (2m)} in units of the result's ulp; and the file holds
   * the 8400 pairs, the 300 bases at 14 scales in both orders, with no pair repeated.
   */
  @Test
  void theHypotFileIsOfExactResultsAndNearMidpoints() {
    Inputs.Pairs pairs = Inputs.hardCasePairs("hypot");
    int n = pairs.x().length;
    assertEquals(8400, n);
    MathContext context = new MathContext(60);
    BigDecimal bound = BigDecimal.ONE.divide(BigDecimal.valueOf(1L << 40));
    Set<String> seen = new HashSet<>();
    int exact = 0;
    for (int i = 0; i < n; i++) {
      double x = pairs.x()[i];
      double y = pairs.y()[i];
      String where = "pair " + i + ": " + Oracle.hex(x) + ", " + Oracle.hex(y);
      assertTrue(seen.add(Oracle.hex(x) + " " + Oracle.hex(y)), where + " is a repeat");
      BigDecimal s = new BigDecimal(x).pow(2).add(new BigDecimal(y).pow(2));
      double r = StrictMath.hypot(x, y);
      boolean isExact = false;
      for (double t : new double[] {Math.nextDown(r), r, Math.nextUp(r)}) {
        isExact |= new BigDecimal(t).pow(2).compareTo(s) == 0;
      }
      if (isExact) {
        exact++;
        continue;
      }
      // the midpoints of the three gaps on each side of r, from its neighbours, so that the
      // narrower gap below a power of two is taken as it is; each distance in that gap's width
      double[] doubles = {
        Math.nextDown(Math.nextDown(Math.nextDown(r))), Math.nextDown(Math.nextDown(r)),
        Math.nextDown(r), r, Math.nextUp(r), Math.nextUp(Math.nextUp(r)),
        Math.nextUp(Math.nextUp(Math.nextUp(r)))
      };
      BigDecimal best = null;
      for (int k = 0; k + 1 < doubles.length; k++) {
        BigDecimal lo = new BigDecimal(doubles[k]);
        BigDecimal gap = new BigDecimal(doubles[k + 1]).subtract(lo);
        BigDecimal m = lo.add(gap.divide(BigDecimal.valueOf(2)));
        BigDecimal d = s.subtract(m.pow(2)).abs()
            .divide(m.multiply(BigDecimal.valueOf(2)).multiply(gap), context);
        best = best == null || d.compareTo(best) < 0 ? d : best;
      }
      assertTrue(best.compareTo(bound) < 0, where + ": " + best + " ulp from a midpoint");
    }
    // the exact family: 40 bases at 14 scales, in both orders
    assertEquals(40 * 14 * 2, exact);
  }

  /** The counts of each claim in {@code hard-cases/pow.txt}, as its header says. */
  private static final int POW_EXACT = 3091;
  private static final int POW_TIES = 899;
  private static final int POW_NEAR = 66;
  private static final int POW_BOUND = 112;
  private static final int POW_NEAR_BITS = 28;

  /**
   * The rounding thresholds of pow's result, each {@code {P, q}} for {@code P * 2^q}: {@code
   * 2^1024 - 2^970} (at and above it the result is infinite), {@code 2^-1022 - 2^-1075} (the
   * midpoint of the largest subnormal and the smallest normal) and {@code 2^-1075} (half the
   * smallest subnormal).
   */
  private static final long[][] POW_THRESHOLDS = {
    {(1L << 54) - 1, 970}, {(1L << 53) - 1, -1075}, {1, -1075}
  };

  /** A positive finite double's raw bits as {@code {M, k}}, its value {@code M * 2^k}. */
  private static long[] mantissaAndExponent(long bits) {
    long e = bits >>> 52;
    long f = bits & ((1L << 52) - 1);
    assertTrue(e < 0x7ff, "not finite: " + Long.toHexString(bits));
    return e == 0 ? new long[] {f, -1074} : new long[] {f | 1L << 52, e - 1075};
  }

  /** The sign of {@code |x|**n - P * 2^q}, for the double of raw bits {@code bits} and n != 0. */
  private static int powerAgainst(long bits, int n, long p, long q) {
    long[] mk = mantissaAndExponent(bits);
    BigInteger m = BigInteger.valueOf(mk[0]).pow(Math.abs(n));
    long shift = mk[1] * n;
    BigInteger left;
    BigInteger right;
    long e;
    if (n > 0) {
      // M^n * 2^(k n) against P * 2^q
      left = m;
      right = BigInteger.valueOf(p);
      e = shift - q;
    } else {
      // 1 / (M^|n| * 2^(k |n|)) against P * 2^q, that is 1 against P * M^|n| * 2^(q - k n)
      left = BigInteger.ONE;
      right = BigInteger.valueOf(p).multiply(m);
      e = -(q - shift);
    }
    if (e >= 0) {
      left = left.shiftLeft((int) e);
    } else {
      right = right.shiftLeft((int) -e);
    }
    return left.compareTo(right);
  }

  /**
   * Every pair of {@code hard-cases/pow.txt} is what its header says, checked in the header's
   * order on the exact {@code |x|**y}: a power of two {@code x} whose power is a power of two in
   * the doubles' range is exact; for a positive integer {@code y}, the result's place on the grid
   * of the doubles at it (its spacing {@code 2^(top-52)}, or {@code 2^-1074} below the normals)
   * makes it exact, a tie or near (within {@code 2^-28} ulp of a midpoint); otherwise, for any
   * integer {@code y}, one of the thresholds lies between the results at {@code x}'s two
   * neighbouring doubles. Each claim's count is the header's, and no pair repeats.
   */
  @Test
  void thePowFileIsOfExactTiesNearMidpointsAndThresholds() {
    Inputs.Pairs pairs = Inputs.hardCasePairs("pow");
    int n = pairs.x().length;
    assertEquals(POW_EXACT + POW_TIES + POW_NEAR + POW_BOUND, n);
    Set<String> seen = new HashSet<>();
    int exact = 0;
    int ties = 0;
    int near = 0;
    int bound = 0;
    for (int i = 0; i < n; i++) {
      double x = pairs.x()[i];
      double y = pairs.y()[i];
      String where = "pair " + i + ": " + Oracle.hex(x) + ", " + Oracle.hex(y);
      assertTrue(seen.add(Oracle.hex(x) + " " + Oracle.hex(y)), where + " is a repeat");
      long bits = Double.doubleToRawLongBits(x) & Long.MAX_VALUE;
      long[] mk = mantissaAndExponent(bits);
      if (Long.bitCount(mk[0]) == 1) {
        long a = mk[1] + 63 - Long.numberOfLeadingZeros(mk[0]);
        BigDecimal t = new BigDecimal(y).multiply(BigDecimal.valueOf(a));
        if (t.signum() == 0 || t.stripTrailingZeros().scale() <= 0) {
          long power = t.longValueExact();
          if (power >= -1074 && power <= 1023) {
            exact++;
            continue;
          }
        }
      }
      assertEquals(Math.rint(y), y, where + ": y is no integer and x**y no power of two");
      assertTrue(Math.abs(y) <= 1100, where);
      int e = (int) y;
      if (e > 0) {
        BigInteger value = BigInteger.valueOf(mk[0]).pow(e);
        long s = mk[1] * e;
        long top = value.bitLength() - 1 + s;
        if (top <= 1023) {
          long ulp = Math.max(top - 52, -1074);
          if (s >= ulp) {
            exact++;
            continue;
          }
          int width = (int) (ulp - s);
          BigInteger tail = value.and(BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE));
          BigInteger half = BigInteger.ONE.shiftLeft(width - 1);
          if (tail.signum() == 0) {
            exact++;
            continue;
          }
          if (tail.equals(half)) {
            ties++;
            continue;
          }
          if (tail.subtract(half).abs().shiftLeft(POW_NEAR_BITS)
              .compareTo(BigInteger.ONE.shiftLeft(width)) < 0) {
            near++;
            continue;
          }
        }
      }
      boolean straddles = false;
      for (long[] threshold : POW_THRESHOLDS) {
        straddles |= powerAgainst(bits - 1, e, threshold[0], threshold[1])
            != powerAgainst(bits + 1, e, threshold[0], threshold[1]);
      }
      assertTrue(straddles, where + ": no claim holds");
      bound++;
    }
    assertEquals(POW_EXACT, exact, "exact");
    assertEquals(POW_TIES, ties, "ties");
    assertEquals(POW_NEAR, near, "near");
    assertEquals(POW_BOUND, bound, "beside a threshold");
  }

  @Test
  void aFileOfPairsIsNotReadAsOneArgument() {
    assertThrows(IllegalArgumentException.class, () -> Inputs.hardCases("atan2"));
  }
}
