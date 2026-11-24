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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code MathCompareBench} is what its Javadoc says: its {@code setUp} throws on an {@code inputs}
 * value it does not know, its arrays are those of {@code ExpBenchmark}, {@code LogBenchmark} and
 * {@code PowBenchmark} by raw bits, and its {@code StrictMath} and vfdlibm rows return {@code
 * StrictMath}'s bits. The {@code Math} rows promise 1 ulp, not {@code StrictMath}'s bits, so they
 * are held to 3 steps of {@code StrictMath}'s bits, with its sign (each of the two within 1 ulp of
 * the exact result, which is 3 steps apart at most where the spacing halves at a power of two).
 * Every row runs on an output array filled with a sentinel first, so that a lane a row leaves
 * unwritten fails rather than keeping an earlier row's result.
 */
class MathCompareBenchTest {

  /** A NaN of a payload no row computes, which an unwritten lane keeps. */
  private static final long SENTINEL = 0x7ff8_dead_beef_0001L;

  @Test
  void setUpThrowsOnAnUnknownInputsValue() {
    for (String inputs : new String[] {"tiny", "none", "nan", ""}) {
      MathCompareBench bad = new MathCompareBench();
      bad.inputs = inputs;
      assertThrows(IllegalArgumentException.class, bad::setUp, inputs);
    }
  }

  @Test
  void inputsAreTheBenchmarksAndRowsAreStrictMaths() throws ReflectiveOperationException {
    for (String inputs : new String[] {"common", "mixed"}) {
      MathCompareBench bench = new MathCompareBench();
      bench.inputs = inputs;
      bench.setUp();
      double[][] got = bench.inputs();

      ExpBenchmark exp = new ExpBenchmark();
      exp.inputs = inputs;
      exp.setUp();
      LogBenchmark log = new LogBenchmark();
      log.inputs = inputs;
      log.setUp();
      PowBenchmark pow = new PowBenchmark();
      pow.inputs = inputs;
      pow.setUp();
      double[] expIn = field(exp, "in");
      double[] logIn = field(log, "in");
      double[] powX = field(pow, "xs");
      double[] powY = field(pow, "ys");
      sameBits(expIn, got[0], inputs + " exp input");
      sameBits(logIn, got[1], inputs + " log input");
      sameBits(powX, got[2], inputs + " pow x");
      sameBits(powY, got[3], inputs + " pow y");

      double[] out = field(bench, "out");
      check(inputs + " exp", expIn, expIn, (x, y) -> StrictMath.exp(x),
          run(out, bench::expStrictMath), run(out, bench::expPreferred),
          run(out, bench::expMath));
      check(inputs + " log", logIn, logIn, (x, y) -> StrictMath.log(x),
          run(out, bench::logStrictMath), run(out, bench::logPreferred),
          run(out, bench::logMath));
      // Raw bits for pow too, not PairBits: no pair of PowBenchmark's has two NaNs.
      check(inputs + " pow", powX, powY, StrictMath::pow,
          run(out, bench::powStrictMath), run(out, bench::powPreferred),
          run(out, bench::powMath));
    }
  }

  /** A row's result, run on {@code out} filled with {@link #SENTINEL}. */
  private static double[] run(double[] out, Supplier<double[]> row) {
    Arrays.fill(out, Double.longBitsToDouble(SENTINEL));
    return row.get().clone();
  }

  /**
   * The StrictMath and vfdlibm rows by StrictMath's raw bits; the Math row with every lane
   * written, of StrictMath's sign and within 3 steps of its bits (a NaN for a NaN), which checks
   * its loop without asking for StrictMath's bits.
   */
  private static void check(String what, double[] xs, double[] ys, DoubleBinaryOperator strict,
      double[] strictRow, double[] vfdlibmRow, double[] mathRow) {
    for (int i = 0; i < xs.length; i++) {
      double want = strict.applyAsDouble(xs[i], ys[i]);
      assertEquals(bits(want), bits(strictRow[i]), what + " StrictMath row at " + i);
      assertEquals(bits(want), bits(vfdlibmRow[i]), what + " Preferred row at " + i);
      String where = what + " Math row at " + i + ": " + mathRow[i];
      assertTrue(bits(mathRow[i]) != SENTINEL, where);
      if (Double.isNaN(want)) {
        assertTrue(Double.isNaN(mathRow[i]), where);
      } else {
        // Same sign first, so that the distance of the bits cannot overflow.
        assertTrue((bits(mathRow[i]) < 0) == (bits(want) < 0), where);
        assertTrue(Math.abs(bits(mathRow[i]) - bits(want)) <= 3, where);
      }
    }
  }

  private static double[] field(Object bench, String name) throws ReflectiveOperationException {
    // The benchmarks keep their arrays private. The inputs are read here rather than through
    // the rows, since two rare inputs can share a result.
    Field f = bench.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return (double[]) f.get(bench);
  }

  private static void sameBits(double[] want, double[] got, String what) {
    assertEquals(want.length, got.length, what);
    for (int i = 0; i < want.length; i++) {
      assertEquals(bits(want[i]), bits(got[i]), what + " " + i);
    }
  }

  private static long bits(double x) {
    return Double.doubleToRawLongBits(x);
  }
}
