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

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * {@code ExpOperatorBench} is what its Javadoc says: its {@code setUp} throws on an {@code inputs}
 * value it does not know, its arrays are {@code ExpBenchmark}'s by raw bits, and its scalar and
 * vfdlibm rows return {@code StrictMath.exp}'s bits. The operator's rows promise {@code Math}'s
 * accuracy, not {@code StrictMath}'s bits, so they are held to 2 ulps of {@code StrictMath.exp}
 * (each of the two within 1 ulp of the exact result), not to its bits.
 */
class ExpOperatorBenchTest {

  @Test
  void setUpThrowsOnAnUnknownInputsValue() {
    for (String inputs : new String[] {"tiny", "none", "nan", ""}) {
      ExpOperatorBench bad = new ExpOperatorBench();
      bad.inputs = inputs;
      assertThrows(IllegalArgumentException.class, bad::setUp, inputs);
    }
  }

  @Test
  void inputsAreExpBenchmarksAndRowsAreStrictMaths() throws ReflectiveOperationException {
    // ExpBenchmark keeps its inputs private; they are read here rather than through its rows,
    // since two rare inputs can share a result (any overflow gives infinity).
    Field in = ExpBenchmark.class.getDeclaredField("in");
    in.setAccessible(true);
    for (String inputs : new String[] {"common", "mixed"}) {
      ExpBenchmark want = new ExpBenchmark();
      want.inputs = inputs;
      want.setUp();
      ExpOperatorBench bench = new ExpOperatorBench();
      bench.inputs = inputs;
      bench.setUp();
      double[] xs = (double[]) in.get(want);
      double[] got = bench.inputs();
      assertEquals(xs.length, got.length);
      for (int i = 0; i < xs.length; i++) {
        assertEquals(bits(xs[i]), bits(got[i]), inputs + " input " + i);
      }
      double[][] rows = {
        bench.scalarStrictMath().clone(), bench.finish256().clone(),
        bench.finishPreferred().clone(), bench.finishMax().clone()
      };
      for (int r = 0; r < rows.length; r++) {
        for (int i = 0; i < xs.length; i++) {
          assertEquals(bits(StrictMath.exp(xs[i])), bits(rows[r][i]), inputs + " row " + r
              + " at " + i);
        }
      }
      // The operator's rows: every lane written, within 2 ulps of StrictMath.exp (a NaN for a
      // NaN), which checks each loop's stride and species without asking for StrictMath's bits.
      double[][] operator = {
        bench.operator256().clone(), bench.operatorPreferred().clone(),
        bench.operatorMax().clone()
      };
      for (int r = 0; r < operator.length; r++) {
        for (int i = 0; i < xs.length; i++) {
          double strict = StrictMath.exp(xs[i]);
          double op = operator[r][i];
          String where = inputs + " operator row " + r + " at " + i + ": " + op;
          if (Double.isNaN(strict)) {
            assertEquals(true, Double.isNaN(op), where);
          } else {
            assertEquals(true, Math.abs(bits(op) - bits(strict)) <= 2, where);
          }
        }
      }
    }
  }

  private static long bits(double x) {
    return Double.doubleToRawLongBits(x);
  }
}
