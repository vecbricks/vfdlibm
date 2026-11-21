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
 * sinh against {@code StrictMath.sinh}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#sinh}, unmasked and masked, on e_sinh.c's high-word tests, the special values,
 * sampled bit patterns over the whole domain, dense draws over each range the C treats
 * differently, both signs, every subset of a vector's lanes rare, and the published
 * hard-to-round cases with their negations.
 */
class SinhTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arm, by name, as a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.sinh", (x, live) -> live == null ? VFdlibm.sinh(x) : VFdlibm.sinh(x, live));
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertSinh(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::sinh, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertSinhMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::sinh, xs, seed++);
    }
  }

  /**
   * e_sinh.c in scalar Java, line by line: the first range with its two forms and its tiny
   * return, and, from 22 up, {@code StrictMath.sinh} itself, which is what the port finishes per
   * lane there. It matches {@code StrictMath.sinh} on every input the tests give it, so a hinge
   * or a fault run that uses it shows the C's result, not a guess.
   */
  static double cSinh(double x) {
    int jx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int ix = jx & 0x7fffffff;
    if (ix >= 0x7ff00000) {
      return x + x;
    }
    double h = jx < 0 ? -0.5 : 0.5;
    if (ix < Sinh.HX_LIMIT) {
      if (ix < Sinh.HX_TINY) {
        return x;
      }
      double t = StrictMath.expm1(Math.abs(x));
      if (ix < Sinh.HX_ONE) {
        return h * (2.0 * t - t * t / (t + Sinh.ONE));
      }
      return h * (t + t / (t + Sinh.ONE));
    }
    return StrictMath.sinh(x);
  }

  /**
   * Every threshold e_sinh.c branches on, with its neighbours, both signs: the high words of its
   * tiny test, its {@code |x| < 1} split, its 22, the filter of its exp ranges, the overflow
   * threshold and the infinities, 64 ulps each way, and the special values.
   */
  static double[] thresholds() {
    double overflow = Double.longBitsToDouble(0x408633ce_8fb9f87dL);
    double[] xs = Inputs.specials(22.0, -22.0, 1.0, -1.0, overflow, -overflow, 0x1p-28);
    int[] words = {
      Sinh.HX_TINY, Sinh.HX_ONE, Sinh.HX_LIMIT, 0x40862E42, 0x408633ce, 0x7ff00000
    };
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    return Inputs.concat(xs, Inputs.around(overflow, 64), Inputs.around(-overflow, 64));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertSinh(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertSinh(width.species(), Inputs.sampled(5000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-30, 30): both forms, the expm1 range and the exp range, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertSinh(width.species(), Inputs.uniform(5100L + width.ordinal(), DENSE, -30.0, 30.0));
  }

  /** Draws spread over the binades 2^-60 to 2^10, both signs: the tiny lanes and every form. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 5200L + 16L * width.ordinal();
    assertSinh(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, 10),
        Inputs.magnitudes(seed + 1, 1 << 16, -35, -20),
        Inputs.uniform(seed + 2, 1 << 16, -1.0, 1.0),
        Inputs.uniform(seed + 3, 1 << 16, 700.0, 712.0)));
  }

  /** Dense draws around the two forms' split at 1, and around 22 on both sides. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void formSplitAndLimit(VectorWidth width) {
    long seed = 5300L + 16L * width.ordinal();
    assertSinh(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.5, 2.0),
        Inputs.uniform(seed + 1, 1 << 16, -2.0, -0.5),
        Inputs.uniform(seed + 2, 1 << 16, 20.0, 24.0),
        Inputs.uniform(seed + 3, 1 << 16, -24.0, -20.0)));
  }

  /** The published hard-to-round inputs, and their negations: see {@link Inputs#hardCases}. */
  private static double[] hardCasesBothSigns() {
    double[] xs = Inputs.hardCases("sinh");
    double[] negated = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      negated[i] = -xs[i];
    }
    return Inputs.concat(xs, negated);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertSinh(width.species(), hardCasesBothSigns());
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(5400L, 4096),
        Inputs.uniform(5401L, 4096, -30.0, 30.0), Inputs.magnitudes(5402L, 4096, -60, 10),
        hardCasesBothSigns());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertSinhMasked(width.species(), maskedInputs(), 5403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * sinh's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(5500L, 1 << 14),
        Inputs.uniform(5501L, 1 << 14, -30.0, 30.0), Inputs.magnitudes(5502L, 1 << 14, -60, 10),
        hardCasesBothSigns());
    assertSinh(DoubleVector.SPECIES_MAX, xs);
    assertSinhMasked(DoubleVector.SPECIES_MAX, xs, 5503L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare (22 or more in
   * magnitude) and the others common, the subsets as {@link ExpTest#rareLaneSubsets} lists them
   * (every subset up to 16 lanes, sampled above): exactly one rare lane at each position, every
   * lane rare, and every mix between. The finish walks the rare mask's set bits, so a lane it
   * skipped or visited twice shows here. The rare inputs cycle through every kind: 22 and just
   * above, the exp range of both signs, the overflow window, overflow, the infinities and NaNs
   * of both signs. The common lanes run from -2.2 to 7.5, and include both zeros and a tiny one.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      22.0, -22.0, 30.0, -50.0, 709.7, -709.7, 710.2, -710.4758600739439, 800.0, -1.0e300,
      Double.NaN, Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double[] common = {0.0, -0.0, 0x1p-40, -0x1p-30};
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length]
            : (s + lane) % 5 == 0 ? common[(s / 5 + lane) % common.length] : 0.67 * lane - 2.2;
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
    assertSinh(species, xs);
    assertSinhMasked(species, xs, 5600L);
  }

  /**
   * The scalar transcription of e_sinh.c is {@code StrictMath.sinh} on the inputs the vector
   * tests use, so a hinge or a fault run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(5700L, 1 << 16),
        Inputs.uniform(5701L, 1 << 16, -30.0, 30.0), Inputs.magnitudes(5702L, 1 << 16, -60, 10),
        hardCasesBothSigns());
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.sinh(x)),
          Double.doubleToRawLongBits(cSinh(x)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.sinh(x, null));
  }

  /** The words are e_sinh.c's, and 0.5 is its {@code h} before the sign. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Sinh.ONE);
    assertBits(0x3fe00000_00000000L, Double.longBitsToDouble(Sinh.HALF_BITS));
    // e_sinh.c's literal words: lines 61, 62 and 65.
    assertEquals(0x40360000, Sinh.HX_LIMIT);
    assertEquals(0x3e300000, Sinh.HX_TINY);
    assertEquals(0x3ff00000, Sinh.HX_ONE);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of sinh's faults, with a check that must fail while it is planted: the vector forms
   * over {@link #faultRunInputs()} at every width the host has. The split moved to 2 shows in
   * the lanes of {@code [1, 2)} that round differently in the other form; {@code h} one ulp up
   * shows in every lane of the vector range; the 22 moved to 32 shows in the lanes of {@code
   * [22, 32)}. Each was sized on 2^22 draws before it was wired ({@link Sinh}).
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Sinh.SPLIT_MOVED, Sinh.HALF_ONE_ULP_OFF, Sinh.THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::sinh, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(5800L, 1 << 16, -2.0, 2.0),
        Inputs.uniform(5801L, 1 << 14, 22.0, 32.0),
        Inputs.uniform(5802L, 1 << 14, -32.0, -22.0));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertSinh(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
