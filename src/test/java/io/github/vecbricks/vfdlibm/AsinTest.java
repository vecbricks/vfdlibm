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
 * asin against {@code StrictMath.asin}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#asin}, unmasked and masked, on e_asin.c's high-word tests, the special values,
 * sampled bit patterns over the whole domain, dense draws over each range the C treats
 * differently, both signs, every subset of a vector's lanes rare, and the published
 * hard-to-round cases with their negations.
 */
class AsinTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arm, by name, as a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.asin", (x, live) -> live == null ? VFdlibm.asin(x) : VFdlibm.asin(x, live));
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertAsin(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::asin, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertAsinMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::asin, xs, seed++);
    }
  }

  /**
   * e_asin.c in scalar Java, line by line, the rare lanes included: exactly {@code +-1}, and
   * {@code (x-x)/(x-x)} above it. It matches {@code StrictMath.asin} on every input the tests
   * give it, so a hinge or a fault run that uses it shows the C's result, not a guess. The
   * {@code __LO(w) = 0} is the AND of the bits with {@link Asin#CLEAR_LOW_WORD}.
   */
  static double cAsin(double x) {
    int hx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int ix = hx & 0x7fffffff;
    int lo = (int) Double.doubleToRawLongBits(x);
    if (ix >= Asin.HX_ONE) {
      if (((ix - Asin.HX_ONE) | lo) == 0) {
        return x * Asin.PIO2_HI + x * Asin.PIO2_LO;
      }
      return (x - x) / (x - x);
    } else if (ix < Asin.HX_HALF) {
      if (ix < Asin.HX_TINY) {
        if (1.000e+300 + x > Asin.ONE) {
          return x;
        }
      }
      double t = x * x;
      double p = t * (Asin.PS0 + t * (Asin.PS1 + t * (Asin.PS2 + t * (Asin.PS3
          + t * (Asin.PS4 + t * Asin.PS5)))));
      double q = Asin.ONE + t * (Asin.QS1 + t * (Asin.QS2 + t * (Asin.QS3 + t * Asin.QS4)));
      double w = p / q;
      return x + x * w;
    }
    double w = Asin.ONE - Math.abs(x);
    double t = w * 0.5;
    double p = t * (Asin.PS0 + t * (Asin.PS1 + t * (Asin.PS2 + t * (Asin.PS3
        + t * (Asin.PS4 + t * Asin.PS5)))));
    double q = Asin.ONE + t * (Asin.QS1 + t * (Asin.QS2 + t * (Asin.QS3 + t * Asin.QS4)));
    double s = StrictMath.sqrt(t);
    if (ix >= Asin.HX_SPLIT) {
      w = p / q;
      t = Asin.PIO2_HI - (2.0 * (s + s * w) - Asin.PIO2_LO);
    } else {
      w = Double.longBitsToDouble(Double.doubleToRawLongBits(s) & Asin.CLEAR_LOW_WORD);
      double c = (t - w * w) / (s + w);
      double r = p / q;
      p = 2.0 * s * r - (Asin.PIO2_LO - 2.0 * c);
      q = Asin.PIO4_HI - 2.0 * w;
      t = Asin.PIO4_HI - (p - q);
    }
    return hx > 0 ? t : -t;
  }

  /**
   * Every threshold e_asin.c branches on, with its neighbours, both signs: the high words of its
   * tiny test, its {@code |x| < 0.5} split, its 0.975, its 1 and the infinities, 64 ulps each
   * way, and the special values.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(
        1.0, -1.0, 0.5, -0.5, 0.975, -0.975, 0x1p-27, -0x1p-27);
    int[] words = {Asin.HX_TINY, Asin.HX_HALF, Asin.HX_SPLIT, Asin.HX_ONE, 0x7ff00000};
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    // 0.975 itself, which is not at the start of its word, and its neighbours.
    return Inputs.concat(xs, Inputs.around(0.975, 64), Inputs.around(-0.975, 64));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertAsin(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertAsin(width.species(), Inputs.sampled(7000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-1, 1): the three ranges below 1, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertAsin(width.species(), Inputs.uniform(7100L + width.ordinal(), DENSE, -1.0, 1.0));
  }

  /** Draws spread over the binades 2^-60 to 2^0, both signs: the tiny lanes and every form. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 7200L + 16L * width.ordinal();
    assertAsin(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, -1),
        Inputs.magnitudes(seed + 1, 1 << 16, -35, -20),
        Inputs.uniform(seed + 2, 1 << 16, 0.97, 1.0),
        Inputs.uniform(seed + 3, 1 << 16, -1.0, -0.97)));
  }

  /** Dense draws around the split at 0.5 and around 0.975, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void splitsAndLimit(VectorWidth width) {
    long seed = 7300L + 16L * width.ordinal();
    assertAsin(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.4, 0.6),
        Inputs.uniform(seed + 1, 1 << 16, -0.6, -0.4),
        Inputs.uniform(seed + 2, 1 << 16, 0.95, 1.0),
        Inputs.uniform(seed + 3, 1 << 16, -1.0, -0.95)));
  }

  /** The published hard-to-round inputs, and their negations: see {@link Inputs#hardCases}. */
  private static double[] hardCasesBothSigns() {
    double[] xs = Inputs.hardCases("asin");
    double[] negated = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      negated[i] = -xs[i];
    }
    return Inputs.concat(xs, negated);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertAsin(width.species(), hardCasesBothSigns());
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(7400L, 4096),
        Inputs.uniform(7401L, 4096, -1.0, 1.0), Inputs.magnitudes(7402L, 4096, -60, -1),
        hardCasesBothSigns());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertAsinMasked(width.species(), maskedInputs(), 7403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * asin's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(7500L, 1 << 14),
        Inputs.uniform(7501L, 1 << 14, -1.0, 1.0), Inputs.magnitudes(7502L, 1 << 14, -60, -1),
        hardCasesBothSigns());
    assertAsin(DoubleVector.SPECIES_MAX, xs);
    assertAsinMasked(DoubleVector.SPECIES_MAX, xs, 7503L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare (1 or more in
   * magnitude, or NaN) and the others common, the subsets as {@link ExpTest#rareLaneSubsets}
   * lists them (every subset up to 16 lanes, sampled above): exactly one rare lane at each
   * position, every lane rare, and every mix between. The finish walks the rare mask's set bits,
   * so a lane it skipped or visited twice shows here. The rare inputs cycle through every kind:
   * exactly 1 and -1, the doubles next to them, above 1, huge, the infinities and NaNs of both
   * signs. The common lanes include both zeros, a tiny one and one in each range.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      1.0, -1.0, Math.nextUp(1.0), -Math.nextUp(1.0), 2.0, -3.0, 1.0e300, -1.0e300, Double.NaN,
      Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double[] common = {0.0, -0.0, 0x1p-40, -0x1p-30, 0.3, -0.7, 0.99, -0.98};
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length]
            : (s + lane) % 5 == 0 ? common[(s / 5 + lane) % common.length]
            : Math.sin(0.67 * lane + s);
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

  /** The arm, unmasked and masked, on {@link #everySubsetOfLanesRare(int)}. */
  private static void assertEverySubsetOfLanesRare(VectorSpecies<Double> species) {
    double[] xs = everySubsetOfLanesRare(species.length());
    assertAsin(species, xs);
    assertAsinMasked(species, xs, 7600L);
  }

  /**
   * The scalar transcription of e_asin.c is {@code StrictMath.asin} on the inputs the vector
   * tests use, the rare lanes included (the C's own {@code (x-x)/(x-x)} and its {@code +-1}), so
   * a hinge or a fault run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(7700L, 1 << 16),
        Inputs.uniform(7701L, 1 << 16, -1.0, 1.0), Inputs.magnitudes(7702L, 1 << 16, -60, -1),
        hardCasesBothSigns());
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.asin(x)),
          Double.doubleToRawLongBits(cAsin(x)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.asin(x, null));
  }

  /** The constants are e_asin.c's: its comments give each one's bits, and so do these. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Asin.ONE);
    assertBits(0x3FF921FB_54442D18L, Asin.PIO2_HI);
    assertBits(0x3C91A626_33145C07L, Asin.PIO2_LO);
    assertBits(0x3FE921FB_54442D18L, Asin.PIO4_HI);
    assertBits(0x3FC55555_55555555L, Asin.PS0);
    assertBits(0xBFD4D612_03EB6F7DL, Asin.PS1);
    assertBits(0x3FC9C155_0E884455L, Asin.PS2);
    assertBits(0xBFA48228_B5688F3BL, Asin.PS3);
    assertBits(0x3F49EFE0_7501B288L, Asin.PS4);
    assertBits(0x3F023DE1_0DFDF709L, Asin.PS5);
    assertBits(0xC0033A27_1C8A2D4BL, Asin.QS1);
    assertBits(0x40002AE5_9C598AC8L, Asin.QS2);
    assertBits(0xBFE6066C_1B8D0159L, Asin.QS3);
    assertBits(0x3FB3B8C5_B12E9282L, Asin.QS4);
    // e_asin.c's literal words: lines 80, 85, 86 and 101, and line 106's mask.
    assertEquals(0x3ff00000, Asin.HX_ONE);
    assertEquals(0x3fe00000, Asin.HX_HALF);
    assertEquals(0x3e400000, Asin.HX_TINY);
    assertEquals(0x3FEF3333, Asin.HX_SPLIT);
    assertEquals(0xFFFFFFFF00000000L, Asin.CLEAR_LOW_WORD);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of asin's faults, with a check that must fail while it is planted: the vector form over
   * {@link #faultRunInputs()} at every width the host has. Each was sized on 2^22 draws before it
   * was wired ({@link Asin}; the counts are in the PR and in {@code SKILLS.md}).
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Asin.DROP_PIO2_LO, Asin.SPLIT_MOVED, Asin.SKIP_LOW_CLEAR}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::asin, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(7800L, 1 << 16, -1.0, 1.0),
        Inputs.uniform(7801L, 1 << 14, 0.5, 1.0),
        Inputs.uniform(7802L, 1 << 14, -1.0, -0.5));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertAsin(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
