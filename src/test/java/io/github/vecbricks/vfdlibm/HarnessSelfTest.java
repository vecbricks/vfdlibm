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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * Tests of the harness itself: the oracle passes a correct vector function at every width, and
 * catches a one-ulp difference, a NaN-payload difference and a sign-of-zero difference planted in
 * any single lane; the masked checks pass a correct masked form and catch one that zeroes or
 * computes its inactive lanes.
 *
 * <p>The function under test is a stand-in: {@code StrictMath} applied lane by lane, which reads
 * {@link Faults} the way a port does and, when a fault is planted, damages one lane. The fault
 * checks run in the fault runs ({@link PlantedFaultTest}), through {@link #faultCatchers}.
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
    if (Faults.is(ONE_ULP)) {
      r[faultLane] = Math.nextUp(d);
    } else if (Faults.is(NAN_PAYLOAD) && Double.isNaN(d)) {
      long other = 0x7ff8_0000_0000_0001L;
      if (Double.doubleToRawLongBits(d) == other) {
        other = 0x7ff8_0000_0000_0002L;
      }
      r[faultLane] = Double.longBitsToDouble(other);
    } else if (Faults.is(ZERO_SIGN) && Double.doubleToRawLongBits(d) == 0L) {
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

  /**
   * The self-test's faults, each with the check that must catch it. The checks run in a JVM with
   * that fault planted ({@link PlantedFaultTest}); the same inputs pass in the normal run, through
   * {@link #oraclePassesOnTheFaultRunsInputs}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    catchers.put(ONE_ULP, () -> {
      forEachWidth(species -> assertCaughtInEveryLane(species, ONE_ULP, steps(species)));
      forEachWidth(HarnessSelfTest::binaryOracleCatchesOneUlpInEveryLane);
      failureReportsInputAndBothResultsInHex();
    });
    catchers.put(NAN_PAYLOAD, () -> forEachWidth(
        species -> assertCaughtInEveryLane(species, NAN_PAYLOAD, nans(species))));
    catchers.put(ZERO_SIGN, () -> forEachWidth(
        species -> assertCaughtInEveryLane(species, ZERO_SIGN, zeroResults(species))));
    return catchers;
  }

  /** Runs {@code check} at each width the host has (wider ones are skipped, as in VectorWidth). */
  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }

  /** Ordinary inputs, four vectors' worth: a one-ulp fault shows in every lane. */
  private static double[] steps(VectorSpecies<Double> species) {
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = i * 0.25 - 8.0;
    }
    return xs;
  }

  /** NaNs with several payloads: exp returns them, so a payload fault shows in every lane. */
  private static double[] nans(VectorSpecies<Double> species) {
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = Double.longBitsToDouble(Inputs.QUIET_NAN_BITS[i % Inputs.QUIET_NAN_BITS.length]);
    }
    return xs;
  }

  /** Inputs whose exp is +0.0: a sign-of-zero fault shows in every lane. */
  private static double[] zeroResults(VectorSpecies<Double> species) {
    double[] xs = new double[4 * species.length()];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = i % 2 == 0 ? Double.NEGATIVE_INFINITY : -1000.0;
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void oraclePassesOnTheFaultRunsInputs(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = Inputs.concat(steps(species), nans(species), zeroResults(species));
    for (int lane = 0; lane < species.length(); lane++) {
      faultLane = lane;
      Oracle.assertUnary("exp", species, HarnessSelfTest::exp, StrictMath::exp, xs);
    }
    faultLane = 0;
    Oracle.assertUnary("exp", species, HarnessSelfTest::exp, StrictMath::exp,
        new double[] {0.0, 1.0});
    binaryInputs(species, (x, y) -> Oracle.assertBinary(
        "pow", species, HarnessSelfTest::pow, StrictMath::pow, x, y));
  }

  private static void binaryInputs(
      VectorSpecies<Double> species, BiConsumer<double[], double[]> check) {
    double[] xs = new double[2 * species.length()];
    double[] ys = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = 1.5 + i;
      ys[i] = 0.5 - i * 0.125;
    }
    check.accept(xs, ys);
  }

  private static void binaryOracleCatchesOneUlpInEveryLane(VectorSpecies<Double> species) {
    binaryInputs(species, (xs, ys) -> {
      for (int lane = 0; lane < species.length(); lane++) {
        faultLane = lane;
        AssertionFailedError e = PlantedFaultTest.assertCaught(
            ONE_ULP,
            () -> Oracle.assertBinary(
                "pow", species, HarnessSelfTest::pow, StrictMath::pow, xs, ys));
        assertReports(e, lane, 2, xs.length);
      }
      faultLane = 0;
    });
  }

  /** With {@code fault} planted, damages each lane in turn; each time the oracle names it. */
  private static void assertCaughtInEveryLane(
      VectorSpecies<Double> species, String fault, double[] xs) {
    assertEquals(0, xs.length % species.length(), "inputs fill whole vectors");
    int vectors = xs.length / species.length();
    for (int lane = 0; lane < species.length(); lane++) {
      faultLane = lane;
      AssertionFailedError e = PlantedFaultTest.assertCaught(
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
  // What a failure reports

  private static void failureReportsInputAndBothResultsInHex() {
    VectorSpecies<Double> species = VectorWidth.BITS_128.species();
    faultLane = 1;
    AssertionFailedError e = PlantedFaultTest.assertCaught(
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

  // ---------------------------------------------------------------------------------------------
  // The masked checks

  /** A correct masked form: StrictMath.exp on live lanes, the input elsewhere. */
  private static DoubleVector maskedExp(DoubleVector x, VectorMask<Double> live) {
    double[] r = x.toArray();
    for (int lane = 0; lane < r.length; lane++) {
      if (live.laneIsSet(lane)) {
        r[lane] = StrictMath.exp(r[lane]);
      }
    }
    return DoubleVector.fromArray(x.species(), r, 0);
  }

  /** A wrong masked form: writes 0.0 to inactive lanes. */
  private static DoubleVector maskedExpZeroingInactive(DoubleVector x, VectorMask<Double> live) {
    return maskedExp(x, live).blend(0.0, live.not());
  }

  /** A wrong masked form: computes every lane, live or not. */
  private static DoubleVector maskedExpComputingInactive(DoubleVector x, VectorMask<Double> l) {
    return exp(x);
  }

  /** A correct two-argument masked form: StrictMath.pow on live lanes, x elsewhere. */
  private static DoubleVector maskedPow(DoubleVector x, DoubleVector y, VectorMask<Double> live) {
    return x.blend(pow(x, y), live);
  }

  /** Inputs for the masked checks, with NaN payloads and -0.0 among them for the inactive lanes. */
  private static double[] maskedInputs() {
    return Inputs.concat(
        Inputs.specials(EXP_OVERFLOW, EXP_UNDERFLOW), Inputs.sampled(3L, 1024),
        Inputs.around(1.0, 16));
  }

  /** Inputs whose exp differs from the input, so computing an inactive lane always shows. */
  private static double[] movedByExp() {
    double[] xs = new double[64];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = i * 0.375 - 12.0 + (i == 32 ? 0.125 : 0.0);
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void maskedOraclePassesCorrectForms(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    Oracle.assertUnaryMasked(
        "exp", species, HarnessSelfTest::maskedExp, StrictMath::exp, maskedInputs(), 11L);
    Inputs.Pairs p = Inputs.cross(Inputs.specials(), Inputs.sampled(4L, 16));
    Oracle.assertBinaryMasked(
        "pow", species, HarnessSelfTest::maskedPow, StrictMath::pow, p.x(), p.y(), 12L);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void maskedOracleCatchesZeroInInactiveLanes(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    AssertionFailedError e = assertThrows(
        AssertionFailedError.class,
        () -> Oracle.assertUnaryMasked(
            "exp", species, HarnessSelfTest::maskedExpZeroingInactive, StrictMath::exp,
            movedByExp(), 13L));
    assertTrue(e.getMessage().contains(" inactive lane "), e.getMessage());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void maskedOracleCatchesComputedInactiveLanes(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    AssertionFailedError e = assertThrows(
        AssertionFailedError.class,
        () -> Oracle.assertUnaryMasked(
            "exp", species, HarnessSelfTest::maskedExpComputingInactive, StrictMath::exp,
            movedByExp(), 14L));
    assertTrue(e.getMessage().contains(" inactive lane "), e.getMessage());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void binaryMaskedOracleCatchesComputedInactiveLanes(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = movedByExp();
    double[] ys = new double[xs.length];
    Arrays.fill(ys, 3.0);
    assertThrows(
        AssertionFailedError.class,
        () -> Oracle.assertBinaryMasked(
            "pow", species, (x, y, live) -> pow(x, y), StrictMath::pow, xs, ys, 15L));
  }

  // ---------------------------------------------------------------------------------------------
  // The oracle's own guards

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

  /**
   * Each function's hard cases load whole and distinct: the counts are the resource headers'
   * (Lefevre and Muller's table rows, then Lefevre's list less duplicates and non-doubles), and
   * one row of each paper table, rechecked against the paper when the files were made, is there.
   */
  @Test
  void hardCasesLoadEachFunctionsFile() {
    Map<String, Integer> counts = Map.of(
        "exp", 2278, "expm1", 7578, "log", 2816, "log10", 1883, "log1p", 7550);
    counts.forEach((function, count) -> {
      long[] bits = rawBits(Inputs.hardCases(function));
      assertEquals(count.intValue(), bits.length, function);
      long distinct = Arrays.stream(bits).distinct().count();
      assertEquals(count.longValue(), distinct, function + " repeats an input");
    });
    // Table 4's last row, exp(1.1000001111010100...p2), and Table 5's last, log(1.0110001...p678).
    assertTrue(contains(rawBits(Inputs.hardCases("exp")), 0x40183d4bcdebb3f4L));
    assertTrue(contains(rawBits(Inputs.hardCases("log")), 0x6a562a88613629b6L));
    assertThrows(IllegalArgumentException.class, () -> Inputs.hardCases("tgamma"));
  }

  private static long[] rawBits(double[] xs) {
    return Arrays.stream(xs).mapToLong(Double::doubleToRawLongBits).toArray();
  }

  private static boolean contains(long[] bits, long b) {
    return Arrays.stream(bits).anyMatch(x -> x == b);
  }
}
