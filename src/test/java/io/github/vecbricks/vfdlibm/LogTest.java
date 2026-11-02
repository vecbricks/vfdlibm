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
import java.util.function.Consumer;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * log against {@code StrictMath.log}, by raw bits, at every width: the public {@link VFdlibm#log}
 * (rare lanes finished per lane) and the all-blended arm {@link Log#logBlended}, unmasked and
 * masked, on e_log.c's thresholds, the special values, sampled bit patterns over the whole domain,
 * dense draws over every binade, draws inside each high word the C branches on and the published
 * hard-to-round cases.
 */
class LogTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;
  /** Binary exponents the high-word edges are swept in: subnormal-adjacent, small, 1, large. */
  private static final int[] BINADES = {-1022, -1021, -600, -60, -2, -1, 0, 1, 2, 60, 600, 1023};
  /**
   * The significand high words (the C's {@code hx} after line 109) e_log.c branches at: both
   * sides of the {@code |f| < 2^-20} test of line 114 (0xffffd to 0x00002), of the sqrt(2) split
   * of line 110 (0x6a09b, 0x6a09c) and of the {@code i > 0} band of lines 124-131 (0x61479,
   * 0x6147a, 0x6b851, 0x6b852).
   */
  private static final int[] WORDS = {
    0xffffd, 0xffffe, 0xfffff, 0x00000, 0x00001, 0x00002, 0x6a09b, 0x6a09c, 0x61479, 0x6147a,
    0x6b851, 0x6b852
  };

  /** Both arms, unmasked, at {@code species}, over {@code xs}. */
  private static void assertLog(VectorSpecies<Double> species, double[] xs) {
    Oracle.assertUnary("VFdlibm.log", species, VFdlibm::log, StrictMath::log, xs);
    Oracle.assertUnary(
        "Log.logBlended", species, x -> Log.logBlended(x, null), StrictMath::log, xs);
  }

  /** The double {@code 2^e * 1.m} whose significand's high 20 bits are {@code hx}, low word 0. */
  private static double withWord(int e, int hx) {
    return Double.longBitsToDouble(((e + 1023L) << 52) | ((long) hx << 32));
  }

  /**
   * Every threshold e_log.c branches on, with 64 neighbours each way, in each of {@link
   * #BINADES}; every power of two, normal and subnormal (f == 0, so line 115); and the specials.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(Double.MIN_NORMAL, Inputs.MAX_SUBNORMAL, Double.MAX_VALUE);
    for (int e : BINADES) {
      for (int hx : WORDS) {
        xs = Inputs.concat(xs, Inputs.around(withWord(e, hx), 64));
      }
    }
    double[] powers = new double[2098];
    for (int e = -1074; e <= 1023; e++) {
      powers[e + 1074] = Math.scalb(1.0, e);
    }
    return Inputs.concat(xs, powers, Inputs.around(1.0, 64), Inputs.around(0.0, 64),
        Inputs.around(Double.MIN_VALUE, 64));
  }

  /**
   * {@code n} positive doubles with high significand word {@code hx} from {@link #WORDS}, random
   * low words, and binary exponents uniform over the normal range: dense draws inside each
   * branch's edge words.
   */
  static double[] inWords(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      long exponent = random.nextInt(1, 2047);
      long hx = WORDS[random.nextInt(WORDS.length)];
      xs[i] = Double.longBitsToDouble((exponent << 52) | (hx << 32) | (random.nextLong() >>> 32));
    }
    return xs;
  }

  /** {@code n} positive doubles with binary exponents uniform over the normal range. */
  static double[] positive(long seed, int n) {
    double[] xs = Inputs.magnitudes(seed, n, -1022, 1023);
    for (int i = 0; i < n; i++) {
      xs[i] = Math.abs(xs[i]);
    }
    return xs;
  }

  /** {@code n} doubles {@code 1 + d} for {@code |d|} spread over 2^-60 to 2^-1: f near 0. */
  static double[] nearOne(long seed, int n) {
    double[] xs = Inputs.magnitudes(seed, n, -60, -1);
    for (int i = 0; i < n; i++) {
      xs[i] = 1.0 + xs[i];
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertLog(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertLog(width.species(), Inputs.sampled(700L + width.ordinal(), SAMPLED));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverEveryBinade(VectorWidth width) {
    assertLog(width.species(), positive(800L + width.ordinal(), DENSE));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void insideTheBranchWords(VectorWidth width) {
    assertLog(width.species(), inWords(900L + width.ordinal(), 1 << 18));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void nearOneAndSubnormals(VectorWidth width) {
    assertLog(width.species(), Inputs.concat(
        nearOne(1000L + width.ordinal(), 1 << 16),
        Inputs.uniform(1100L + width.ordinal(), 1 << 16, 0.5, 2.0),
        Inputs.uniform(1200L + width.ordinal(), 1 << 16, 0.0, Double.MIN_NORMAL)));
  }

  /** The published hard-to-round inputs: see {@link Inputs#hardCases} and the resource header. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertLog(width.species(), Inputs.hardCases("log"));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(1300L, 4096),
        positive(1301L, 4096), inWords(1302L, 4096), Inputs.hardCases("log"));
    Oracle.assertUnaryMasked("VFdlibm.log", species, VFdlibm::log, StrictMath::log, xs, 1303L);
    Oracle.assertUnaryMasked(
        "Log.logBlended", species, Log::logBlended, StrictMath::log, xs, 1304L);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.log(x, null));
  }

  /** The constants parse to the hex e_log.c gives for each. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3fe62e42_fee00000L, Log.LN2_HI);
    assertBits(0x3dea39ef_35793c76L, Log.LN2_LO);
    assertBits(0x43500000_00000000L, Log.TWO54);
    assertBits(0x3FE55555_55555593L, Log.LG1);
    assertBits(0x3FD99999_9997FA04L, Log.LG2);
    assertBits(0x3FD24924_94229359L, Log.LG3);
    assertBits(0x3FCC71C5_1D8E78AFL, Log.LG4);
    assertBits(0x3FC74664_96CB03DEL, Log.LG5);
    assertBits(0x3FC39A09_D078C69FL, Log.LG6);
    assertBits(0x3FC2F112_DF3E5244L, Log.LG7);
    assertBits(0x3fd55555_55555555L, Log.THIRD);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults

  /**
   * log's faults, each with the check that must catch it: both arms, at each width the host has,
   * over the thresholds and draws inside the branch words and over every binade. The same inputs
   * pass in the normal run, through {@link #faultRunInputsPass}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Log.DROP_CORRECTION, Log.CONSTANT_OFF_BY_ONE_ULP, Log.THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> {
        PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
            "VFdlibm.log", species, VFdlibm::log, StrictMath::log, faultRunInputs()));
        PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
            "Log.logBlended", species, x -> Log.logBlended(x, null), StrictMath::log,
            faultRunInputs()));
      }));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(thresholds(), inWords(1400L, 1 << 14), positive(1401L, 1 << 14));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertLog(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
