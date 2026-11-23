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
 * {@code sqrt(x*x + y*y)} is a double or lies within {@code 2^-40} ulp of a midpoint.
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

  @Test
  void aFileOfPairsIsNotReadAsOneArgument() {
    assertThrows(IllegalArgumentException.class, () -> Inputs.hardCases("atan2"));
  }
}
