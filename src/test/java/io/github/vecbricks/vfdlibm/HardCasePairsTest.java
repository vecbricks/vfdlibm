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

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shape of {@code hard-cases/atan2.txt}, which {@code dev/testlibm-extract.py atn atan2}
 * derives from the {@code atn} lines of Lefevre's data: 12 pairs for each of its inputs
 * {@code t}, {@code (+-t * 2^j, +-2^j)} for {@code j} in 0, 1, -5, with {@code |y / x| = t}
 * exactly. The extractor checks the data; this checks that the committed file has the shape the
 * extractor gives, in the order it gives it, so a hand edit shows.
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

  @Test
  void aFileOfPairsIsNotReadAsOneArgument() {
    assertThrows(IllegalArgumentException.class, () -> Inputs.hardCases("atan2"));
  }
}
