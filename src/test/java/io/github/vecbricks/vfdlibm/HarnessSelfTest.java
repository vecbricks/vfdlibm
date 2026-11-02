/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * Tests of the harness itself, before any function is ported: the oracle passes a correct vector
 * function at every width, and catches a one-ulp difference, a NaN-payload difference and a
 * sign-of-zero difference planted in any single lane.
 *
 * <p>The function under test is a stand-in: {@code StrictMath} applied lane by lane, which reads
 * {@link PlantedFault} the way a port will and, when a fault is planted, damages one lane.
 */
class HarnessSelfTest {

  private static final String ONE_ULP = "selftest.oneUlp";
  private static final String NAN_PAYLOAD = "selftest.nanPayload";
  private static final String ZERO_SIGN = "selftest.zeroSign";

  /** exp's overflow and underflow limits, as a port's test would pass them to the specials. */
  private static final double EXP_OVERFLOW = 7.09782712893383973096e+02;
  private static final double EXP_UNDERFLOW = -7.45133219101941108420e+02;

  /** The lane the stand-in damages when a fault is planted. */
  private static int faultLane;

  // ---------------------------------------------------------------------------------------------
  // The stand-in for a port

  /** StrictMath.exp lane by lane, with the planted faults applied to {@link #faultLane}. */
  private static DoubleVector exp(DoubleVector x) {
    double[] r = x.toArray();
    for (int lane = 0; lane < r.length; lane++) {
      r[lane] = StrictMath.exp(r[lane]);
    }
    damage(r);
    return DoubleVector.fromArray(x.species(), r, 0);
  }

  /** StrictMath.pow lane by lane, with the one-ulp fault applied to {@link #faultLane}. */
  private static DoubleVector pow(DoubleVector x, DoubleVector y) {
    double[] r = x.toArray();
    double[] e = y.toArray();
    for (int lane = 0; lane < r.length; lane++) {
      r[lane] = StrictMath.pow(r[lane], e[lane]);
    }
    damage(r);
    return DoubleVector.fromArray(x.species(), r, 0);
  }

  private static void damage(double[] r) {
    double d = r[faultLane];
    if (PlantedFault.is(ONE_ULP)) {
      r[faultLane] = Math.nextUp(d);
    } else if (PlantedFault.is(NAN_PAYLOAD) && Double.isNaN(d)) {
      long other = 0x7ff8_0000_0000_0001L;
      if (Double.doubleToRawLongBits(d) == other) {
        other = 0x7ff8_0000_0000_0002L;
      }
      r[faultLane] = Double.longBitsToDouble(other);
    } else if (PlantedFault.is(ZERO_SIGN) && Double.doubleToRawLongBits(d) == 0L) {
      r[faultLane] = -0.0;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The oracle passes a correct function

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oraclePassesCorrectUnaryFunction(VectorWidth width) {
    double[] xs = Inputs.concat(
        Inputs.specials(EXP_OVERFLOW, EXP_UNDERFLOW),
        Inputs.sampled(1L, 4096),
        Inputs.around(1.0, 64));
    Oracle.assertUnary("exp", width.species(), HarnessSelfTest::exp, StrictMath::exp, xs);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oraclePassesCorrectBinaryFunction(VectorWidth width) {
    Inputs.Pairs p = Inputs.cross(
        Inputs.specials(), Inputs.concat(Inputs.specials(), Inputs.sampled(2L, 16)));
    Oracle.assertBinary(
        "pow", width.species(), HarnessSelfTest::pow, StrictMath::pow, p.x(), p.y());
  }

  // ---------------------------------------------------------------------------------------------
  // The oracle catches each kind of difference, in every lane

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oracleCatchesOneUlpInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = i * 0.25 - 8.0;
    }
    assertCaughtInEveryLane(species, ONE_ULP, xs);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oracleCatchesNanPayloadInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = Double.longBitsToDouble(Inputs.QUIET_NAN_BITS[i % Inputs.QUIET_NAN_BITS.length]);
    }
    assertCaughtInEveryLane(species, NAN_PAYLOAD, xs);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oracleCatchesSignOfZeroInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = i % 2 == 0 ? Double.NEGATIVE_INFINITY : -1000.0;
    }
    assertCaughtInEveryLane(species, ZERO_SIGN, xs);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void binaryOracleCatchesOneUlpInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = new double[2 * species.length()];
    double[] ys = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = 1.5 + i;
      ys[i] = 0.5 - i * 0.125;
    }
    for (int lane = 0; lane < species.length(); lane++) {
      faultLane = lane;
      AssertionFailedError e = PlantedFault.assertCaught(
          ONE_ULP,
          () -> Oracle.assertBinary(
              "pow", species, HarnessSelfTest::pow, StrictMath::pow, xs, ys));
      assertReports(e, lane, 2, xs.length);
    }
  }

  /** Plants {@code fault} in each lane in turn; each time the oracle must name that lane. */
  private static void assertCaughtInEveryLane(
      VectorSpecies<Double> species, String fault, double[] xs) {
    assertEquals(0, xs.length % species.length(), "inputs fill whole vectors");
    int vectors = xs.length / species.length();
    for (int lane = 0; lane < species.length(); lane++) {
      faultLane = lane;
      AssertionFailedError e = PlantedFault.assertCaught(
          fault,
          () -> Oracle.assertUnary("exp", species, HarnessSelfTest::exp, StrictMath::exp, xs));
      assertReports(e, lane, vectors, xs.length);
    }
    faultLane = 0;
  }

  private static void assertReports(AssertionFailedError e, int lane, int differing, int inputs) {
    String message = e.getMessage();
    assertTrue(message.contains(" lane " + lane + " "), message);
    assertTrue(message.endsWith("; " + differing + " of " + inputs + " inputs differ"), message);
  }

  // ---------------------------------------------------------------------------------------------
  // What a failure reports, and the switch itself

  @Test
  void failureReportsInputAndBothResultsInHex() {
    VectorSpecies<Double> species = VectorWidth.BITS_128.species();
    faultLane = 1;
    AssertionFailedError e = PlantedFault.assertCaught(
        ONE_ULP,
        () -> Oracle.assertUnary(
            "exp", species, HarnessSelfTest::exp, StrictMath::exp, new double[] {0.0, 1.0}));
    faultLane = 0;
    double want = StrictMath.exp(1.0);
    assertEquals(
        "exp at " + species + " lane 1 (input 1): x = 0x3ff0000000000000 (1.0): expected "
            + Oracle.hex(want) + ", got " + Oracle.hex(Math.nextUp(want))
            + "; 1 of 2 inputs differ",
        e.getMessage());
  }

  @Test
  void plantingFailsWhenTheCheckPasses() {
    VectorSpecies<Double> species = VectorWidth.BITS_128.species();
    double[] xs = {0.0, 1.0, 2.0, 3.0};
    AssertionFailedError e = assertThrows(
        AssertionFailedError.class,
        () -> PlantedFault.assertCaught(
            "selftest.notRead",
            () -> Oracle.assertUnary("exp", species, HarnessSelfTest::exp, StrictMath::exp, xs)));
    assertTrue(e.getMessage().startsWith("the check did not catch planted fault"), e.getMessage());
    assertFalse(PlantedFault.is("selftest.notRead"), "the fault is cleared afterwards");
  }

  @Test
  void oracleRejectsEmptyInputs() {
    assertThrows(
        AssertionFailedError.class,
        () -> Oracle.assertUnary(
            "exp", VectorWidth.BITS_128.species(), HarnessSelfTest::exp, StrictMath::exp,
            new double[0]));
  }

  // ---------------------------------------------------------------------------------------------
  // The input generators

  @Test
  void sampledIsReproducibleFromItsSeed() {
    assertArrayEquals(rawBits(Inputs.sampled(7L, 64)), rawBits(Inputs.sampled(7L, 64)));
    assertFalse(Arrays.equals(rawBits(Inputs.sampled(7L, 64)), rawBits(Inputs.sampled(8L, 64))));
  }

  @Test
  void aroundIsConsecutiveDoubles() {
    long one = Double.doubleToRawLongBits(1.0);
    assertArrayEquals(new long[] {one - 2, one - 1, one, one + 1, one + 2},
        rawBits(Inputs.around(1.0, 2)));
    assertArrayEquals(new double[] {-Double.MIN_VALUE, 0.0, Double.MIN_VALUE},
        Inputs.around(0.0, 1));
    double[] top = Inputs.around(Double.MAX_VALUE, 3);
    assertEquals(Double.POSITIVE_INFINITY, top[top.length - 1], "the sweep stops at infinity");
    assertEquals(5, top.length);
  }

  @Test
  void specialsCoverTheEdgeCases() {
    long[] bits = rawBits(Inputs.specials(EXP_OVERFLOW));
    for (double d : new double[] {
        0.0, -0.0, Double.MIN_VALUE, -Inputs.MAX_SUBNORMAL, Double.MIN_NORMAL, Double.MAX_VALUE,
        Double.NEGATIVE_INFINITY, Math.nextUp(EXP_OVERFLOW), Math.nextDown(EXP_OVERFLOW)}) {
      assertTrue(contains(bits, Double.doubleToRawLongBits(d)), Oracle.hex(d));
    }
    for (long nan : Inputs.QUIET_NAN_BITS) {
      assertTrue(contains(bits, nan), Long.toHexString(nan));
    }
    for (long nan : Inputs.SIGNALLING_NAN_BITS) {
      assertTrue(contains(bits, nan), Long.toHexString(nan));
    }
    assertNotEquals(
        Double.doubleToRawLongBits(Double.NaN), Inputs.QUIET_NAN_BITS[1], "a non-canonical NaN");
  }

  private static long[] rawBits(double[] xs) {
    return Arrays.stream(xs).mapToLong(Double::doubleToRawLongBits).toArray();
  }

  private static boolean contains(long[] bits, long b) {
    return Arrays.stream(bits).anyMatch(x -> x == b);
  }
}
