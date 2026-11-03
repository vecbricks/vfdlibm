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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * log10 against {@code StrictMath.log10}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * the public {@link VFdlibm#log10} (rare lanes finished per lane) and the all-blended arm {@link
 * Log10#log10Blended}, unmasked and masked, on e_log10.c's high-word tests and e_log.c's
 * significand words (the rescaled {@code x} keeps {@code x}'s significand, so log's branches
 * are taken at the same words), the special values, the powers of ten and of two, sampled bit
 * patterns over the whole domain, dense draws over every binade, every subset of a vector's
 * lanes rare, and the published hard-to-round cases.
 */
class Log10Test {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arms, by name, each a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.log10", (x, live) -> live == null ? VFdlibm.log10(x) : VFdlibm.log10(x, live));
    arms.put("Log10.log10Blended", Log10::log10Blended);
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertLog10(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::log10, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertLog10Masked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::log10, xs, seed++);
    }
  }

  /**
   * The doubles {@code 10^n} as Java parses {@code "1e<n>"}, for every {@code n} from -323 to
   * 308, each with its 4 neighbours either way: e_log10.c promises {@code log10(10^n) = n} for
   * {@code n} from 0 to 22, where {@code 10^n} is exact, and the others lie close to an integer
   * result.
   */
  static double[] powersOfTen() {
    double[] xs = new double[0];
    for (int n = -323; n <= 308; n++) {
      xs = Inputs.concat(xs, Inputs.around(Double.parseDouble("1e" + n), 4));
    }
    return xs;
  }

  /**
   * log's thresholds ({@link LogTest#thresholds}: e_log.c's significand words at a dozen
   * binades, every power of two, normal and subnormal, the neighbours of 1, 0 and the smallest
   * subnormal, and the specials), e_log10.c's own words with 64 neighbours each way ({@code hx}
   * at 0x00100000 and 0x7ff00000, the split of line 85 at 1.0, between the high words 0x3fefffff
   * and 0x3ff00000, the largest finite double), the largest subnormals' high word 0x000fffff with
   * low words 0, 1 and seeded random ones (near the largest subnormal a lane rescaled as if normal
   * rounds to the right result, so the neighbours of 0x00100000 miss a moved line 76), and the
   * powers of ten.
   */
  static double[] thresholds() {
    double[] xs = Inputs.concat(LogTest.thresholds(), powersOfTen());
    double[] edges = {
      Lanes.fromHi(Log10.HX_MIN_NORMAL), Lanes.fromHi(Log10.HX_NON_FINITE),
      Lanes.fromHi(0x3ff00000), Lanes.fromHi(0x3fefffff), Lanes.fromHi(0x3fe00000),
      Double.MAX_VALUE, Inputs.MAX_SUBNORMAL
    };
    for (double edge : edges) {
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 4));
    }
    long hi = Lanes.fromHiBits(Log10.HX_MIN_NORMAL - 1);
    SplittableRandom random = new SplittableRandom(1650L);
    double[] lastSubnormalWord = new double[10];
    lastSubnormalWord[0] = Double.longBitsToDouble(hi);
    lastSubnormalWord[1] = Double.longBitsToDouble(hi | 1L);
    for (int j = 2; j < lastSubnormalWord.length; j++) {
      lastSubnormalWord[j] = Double.longBitsToDouble(hi | (random.nextLong() & Lanes.LOW_WORD));
    }
    return Inputs.concat(xs, lastSubnormalWord);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertLog10(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertLog10(width.species(), Inputs.sampled(1700L + width.ordinal(), SAMPLED));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverEveryBinade(VectorWidth width) {
    assertLog10(width.species(), LogTest.positive(1800L + width.ordinal(), DENSE));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void insideTheBranchWords(VectorWidth width) {
    assertLog10(width.species(), LogTest.inWords(1900L + width.ordinal(), 1 << 18));
  }

  /** Near 1, where k steps between -1 and 0 and log10 is near 0; [0.5, 2); the subnormals. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void nearOneAndSubnormals(VectorWidth width) {
    assertLog10(width.species(), Inputs.concat(
        LogTest.nearOne(2000L + width.ordinal(), 1 << 16),
        Inputs.uniform(2100L + width.ordinal(), 1 << 16, 0.5, 2.0),
        Inputs.uniform(2200L + width.ordinal(), 1 << 16, 0.0, Double.MIN_NORMAL),
        subnormals(2300L + width.ordinal(), 1 << 16)));
  }

  /** {@code n} positive subnormals with their leading bit spread over all 52 positions. */
  static double[] subnormals(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      long bits = (random.nextLong() & 0x000f_ffff_ffff_ffffL) >>> random.nextInt(52);
      xs[i] = Double.longBitsToDouble(bits == 0 ? 1 : bits);
    }
    return xs;
  }

  /** The published hard-to-round inputs: see {@link Inputs#hardCases} and the resource header. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertLog10(width.species(), Inputs.hardCases("log10"));
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(2400L, 4096),
        LogTest.positive(2401L, 4096), LogTest.inWords(2402L, 4096), Inputs.hardCases("log10"));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertLog10Masked(width.species(), maskedInputs(), 2403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * log10's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(2500L, 1 << 14),
        LogTest.positive(2501L, 1 << 14), Inputs.hardCases("log10"));
    assertLog10(DoubleVector.SPECIES_MAX, xs);
    assertLog10Masked(DoubleVector.SPECIES_MAX, xs, 2502L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare and the others
   * common, the subsets as {@link ExpTest#rareLaneSubsets} lists them (every subset up to 16
   * lanes, sampled above): exactly one rare lane at each position, every lane rare, and every mix
   * between. The finish arm walks the rare mask's set bits, so a lane it skipped or visited twice
   * shows here. The rare inputs cycle through each of e_log10.c's rare paths: NaNs of both signs,
   * the infinities, zeros of both signs, negative numbers, and subnormals. The common lanes run
   * from 0.37 to above 5, both sides of line 85's split at 1.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      Double.NaN, Double.longBitsToDouble(0xfff8_0000_0000_0001L), 0.0, -0.0, -2.0,
      Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Inputs.MAX_SUBNORMAL,
      -Double.MIN_VALUE, 1.0e-310
    };
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length] : 0.37 + 0.31 * lane;
      }
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void everySubsetOfLanesRare(VectorWidth width) {
    assertEverySubsetOfLanesRare(width.species());
  }

  /** {@link #everySubsetOfLanesRare(VectorWidth)} at SPECIES_MAX, which VectorWidth skips. */
  @Test
  void everySubsetOfLanesRareAtMaxSpecies() {
    assertEverySubsetOfLanesRare(DoubleVector.SPECIES_MAX);
  }

  /** Every arm, unmasked and masked, on {@link #everySubsetOfLanesRare(int)}. */
  private static void assertEverySubsetOfLanesRare(VectorSpecies<Double> species) {
    double[] xs = everySubsetOfLanesRare(species.length());
    assertLog10(species, xs);
    assertLog10Masked(species, xs, 2600L);
  }

  /** e_log10.c's Note 1: log10(10^n) = n exactly for n from 0 to 22, through the vector code. */
  @Test
  void exactPowersOfTenGiveTheirExponent() {
    double[] xs = new double[23];
    for (int n = 0; n <= 22; n++) {
      xs[n] = Double.parseDouble("1e" + n);
      assertEquals((double) n, StrictMath.log10(xs[n]), "StrictMath.log10(1e" + n + ")");
    }
    assertLog10(DoubleVector.SPECIES_PREFERRED, xs);
    assertLog10(DoubleVector.SPECIES_MAX, xs);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.log10(x, null));
  }

  /** The constants parse to the hex e_log10.c gives for each. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x43500000_00000000L, Log10.TWO54);
    assertBits(0x3FDBCB7B_1526E50EL, Log10.IVLN10);
    assertBits(0x3FD34413_509F6000L, Log10.LOG10_2HI);
    assertBits(0x3D59FEF3_11F12B36L, Log10.LOG10_2LO);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults

  /**
   * log10's faults, each with the check that must catch it: both arms, at each width the host
   * has, over the thresholds and draws inside the branch words and over every binade. The same
   * inputs pass in the normal run, through {@link #faultRunInputsPass}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Log10.DROP_CORRECTION, Log10.CONSTANT_OFF_BY_ONE_ULP, Log10.THRESHOLD_MOVED,
        Log10.SUBNORMAL_THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::log10, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(thresholds(), LogTest.inWords(2700L, 1 << 14),
        LogTest.positive(2701L, 1 << 14));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertLog10(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
