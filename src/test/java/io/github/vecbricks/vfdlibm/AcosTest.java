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
 * acos against {@code StrictMath.acos}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#acos}, unmasked and masked, on e_acos.c's high-word tests, the special values,
 * sampled bit patterns over the whole domain, dense draws over each range the C treats
 * differently, both signs (acos is not odd: the two signs reach two different forms above 0.5),
 * every subset of a vector's lanes rare, and the published hard-to-round cases with their
 * negations.
 */
class AcosTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arm, by name, as a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.acos", (x, live) -> live == null ? VFdlibm.acos(x) : VFdlibm.acos(x, live));
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertAcos(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::acos, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertAcosMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::acos, xs, seed++);
    }
  }

  /**
   * e_acos.c in scalar Java, line by line, the rare lanes included: exactly {@code +-1}, and
   * {@code (x-x)/(x-x)} above it. It matches {@code StrictMath.acos} on every input the tests
   * give it, so a hinge or a fault run that uses it shows the C's result, not a guess. The
   * {@code __LO(df) = 0} is the AND of the bits with {@link Acos#CLEAR_LOW_WORD}.
   */
  static double cAcos(double x) {
    int hx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int ix = hx & 0x7fffffff;
    int lo = (int) Double.doubleToRawLongBits(x);
    if (ix >= Acos.HX_ONE) {
      if (((ix - Acos.HX_ONE) | lo) == 0) {
        if (hx > 0) {
          return 0.0;
        }
        return Acos.PI + 2.0 * Acos.PIO2_LO;
      }
      return (x - x) / (x - x);
    }
    if (ix < Acos.HX_HALF) {
      if (ix <= Acos.HX_TINY) {
        return Acos.PIO2_HI + Acos.PIO2_LO;
      }
      double z = x * x;
      double p = z * (Acos.PS0 + z * (Acos.PS1 + z * (Acos.PS2 + z * (Acos.PS3
          + z * (Acos.PS4 + z * Acos.PS5)))));
      double q = Acos.ONE + z * (Acos.QS1 + z * (Acos.QS2 + z * (Acos.QS3 + z * Acos.QS4)));
      double r = p / q;
      return Acos.PIO2_HI - (x - (Acos.PIO2_LO - x * r));
    } else if (hx < 0) {
      double z = (Acos.ONE + x) * 0.5;
      double p = z * (Acos.PS0 + z * (Acos.PS1 + z * (Acos.PS2 + z * (Acos.PS3
          + z * (Acos.PS4 + z * Acos.PS5)))));
      double q = Acos.ONE + z * (Acos.QS1 + z * (Acos.QS2 + z * (Acos.QS3 + z * Acos.QS4)));
      double s = StrictMath.sqrt(z);
      double r = p / q;
      double w = r * s - Acos.PIO2_LO;
      return Acos.PI - 2.0 * (s + w);
    } else {
      double z = (Acos.ONE - x) * 0.5;
      double s = StrictMath.sqrt(z);
      double df = Double.longBitsToDouble(Double.doubleToRawLongBits(s) & Acos.CLEAR_LOW_WORD);
      double c = (z - df * df) / (s + df);
      double p = z * (Acos.PS0 + z * (Acos.PS1 + z * (Acos.PS2 + z * (Acos.PS3
          + z * (Acos.PS4 + z * Acos.PS5)))));
      double q = Acos.ONE + z * (Acos.QS1 + z * (Acos.QS2 + z * (Acos.QS3 + z * Acos.QS4)));
      double r = p / q;
      double w = r * s + c;
      return 2.0 * (df + w);
    }
  }

  /**
   * Every threshold e_acos.c branches on, with its neighbours, both signs: the high words of its
   * constant return, its {@code |x| < 0.5} split, its 1 and the infinities, 64 ulps each way, and
   * the special values.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(1.0, -1.0, 0.5, -0.5, 0x1p-57, -0x1p-57);
    int[] words = {Acos.HX_TINY, Acos.HX_TINY + 1, Acos.HX_HALF, Acos.HX_ONE, 0x7ff00000};
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    // 2^-57 itself and its neighbours: the constant return is the compare ix <= 0x3c600000.
    return Inputs.concat(xs, Inputs.around(0x1p-57, 64), Inputs.around(-0x1p-57, 64));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertAcos(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertAcos(width.species(), Inputs.sampled(8000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-1, 1): the three ranges below 1, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertAcos(width.species(), Inputs.uniform(8100L + width.ordinal(), DENSE, -1.0, 1.0));
  }

  /**
   * Draws spread over the binades 2^-70 to 2^0, both signs: the constant return, the lanes just
   * above it, and every form.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 8200L + 16L * width.ordinal();
    assertAcos(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -70, -1),
        Inputs.magnitudes(seed + 1, 1 << 16, -60, -50),
        Inputs.uniform(seed + 2, 1 << 16, 0.97, 1.0),
        Inputs.uniform(seed + 3, 1 << 16, -1.0, -0.97)));
  }

  /** Dense draws around the split at 0.5 on both sides, both signs, and near 1. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void splitsAndLimit(VectorWidth width) {
    long seed = 8300L + 16L * width.ordinal();
    assertAcos(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.4, 0.6),
        Inputs.uniform(seed + 1, 1 << 16, -0.6, -0.4),
        Inputs.uniform(seed + 2, 1 << 16, 0.95, 1.0),
        Inputs.uniform(seed + 3, 1 << 16, -1.0, -0.95)));
  }

  /** The published hard-to-round inputs, and their negations: see {@link Inputs#hardCases}. */
  private static double[] hardCasesBothSigns() {
    double[] xs = Inputs.hardCases("acos");
    double[] negated = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      negated[i] = -xs[i];
    }
    return Inputs.concat(xs, negated);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertAcos(width.species(), hardCasesBothSigns());
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(8400L, 4096),
        Inputs.uniform(8401L, 4096, -1.0, 1.0), Inputs.magnitudes(8402L, 4096, -70, -1),
        hardCasesBothSigns());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertAcosMasked(width.species(), maskedInputs(), 8403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * acos's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(8500L, 1 << 14),
        Inputs.uniform(8501L, 1 << 14, -1.0, 1.0), Inputs.magnitudes(8502L, 1 << 14, -70, -1),
        hardCasesBothSigns());
    assertAcos(DoubleVector.SPECIES_MAX, xs);
    assertAcosMasked(DoubleVector.SPECIES_MAX, xs, 8503L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare (1 or more in
   * magnitude, or NaN) and the others common, the subsets as {@link ExpTest#rareLaneSubsets}
   * lists them (every subset up to 16 lanes, sampled above): exactly one rare lane at each
   * position, every lane rare, and every mix between. The finish walks the rare mask's set bits,
   * so a lane it skipped or visited twice shows here. The rare inputs cycle through every kind:
   * exactly 1 and -1, the doubles next to them, above 1, huge, the infinities and NaNs of both
   * signs. The common lanes include both zeros, a constant-return lane and one in each range
   * and of each sign.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      1.0, -1.0, Math.nextUp(1.0), -Math.nextUp(1.0), 2.0, -3.0, 1.0e300, -1.0e300, Double.NaN,
      Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double[] common = {0.0, -0.0, 0x1p-60, -0x1p-58, 0.3, -0.7, 0.99, -0.98};
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
    assertAcos(species, xs);
    assertAcosMasked(species, xs, 8600L);
  }

  /**
   * The scalar transcription of e_acos.c is {@code StrictMath.acos} on the inputs the vector
   * tests use, the rare lanes included (the C's own {@code (x-x)/(x-x)} and its {@code +-1}), so
   * a hinge or a fault run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(8700L, 1 << 16),
        Inputs.uniform(8701L, 1 << 16, -1.0, 1.0), Inputs.magnitudes(8702L, 1 << 16, -70, -1),
        hardCasesBothSigns());
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.acos(x)),
          Double.doubleToRawLongBits(cAcos(x)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.acos(x, null));
  }

  /** The constants are e_acos.c's: its comments give each one's bits, and so do these. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Acos.ONE);
    assertBits(0x400921FB_54442D18L, Acos.PI);
    assertBits(0x3FF921FB_54442D18L, Acos.PIO2_HI);
    assertBits(0x3C91A626_33145C07L, Acos.PIO2_LO);
    assertBits(0x3FC55555_55555555L, Acos.PS0);
    assertBits(0xBFD4D612_03EB6F7DL, Acos.PS1);
    assertBits(0x3FC9C155_0E884455L, Acos.PS2);
    assertBits(0xBFA48228_B5688F3BL, Acos.PS3);
    assertBits(0x3F49EFE0_7501B288L, Acos.PS4);
    assertBits(0x3F023DE1_0DFDF709L, Acos.PS5);
    assertBits(0xC0033A27_1C8A2D4BL, Acos.QS1);
    assertBits(0x40002AE5_9C598AC8L, Acos.QS2);
    assertBits(0xBFE6066C_1B8D0159L, Acos.QS3);
    assertBits(0x3FB3B8C5_B12E9282L, Acos.QS4);
    // e_acos.c's literal words: lines 71, 78 and 79, and line 97's mask.
    assertEquals(0x3ff00000, Acos.HX_ONE);
    assertEquals(0x3fe00000, Acos.HX_HALF);
    assertEquals(0x3c600000, Acos.HX_TINY);
    assertEquals(0xFFFFFFFF00000000L, Acos.CLEAR_LOW_WORD);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of acos's faults, with a check that must fail while it is planted: the vector form over
   * {@link #faultRunInputs()} at every width the host has. Each was sized on 2^22 draws before it
   * was wired ({@link Acos}; the counts are in {@code SKILLS.md}).
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Acos.DROP_PIO2_LO, Acos.HALF_MOVED, Acos.SKIP_LOW_CLEAR, Acos.TINY_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::acos, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(8800L, 1 << 16, -1.0, 1.0),
        Inputs.uniform(8801L, 1 << 14, 0.5, 1.0),
        Inputs.uniform(8802L, 1 << 14, -1.0, -0.5),
        Inputs.magnitudes(8803L, 1 << 14, -60, -50));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertAcos(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
