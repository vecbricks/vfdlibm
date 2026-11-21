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
 * atan against {@code StrictMath.atan}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#atan}, unmasked and masked, on s_atan.c's high-word tests, the special values,
 * sampled bit patterns over the whole domain, dense draws over each range the C treats
 * differently, both signs, every subset of a vector's lanes rare, and the published
 * hard-to-round cases with their negations.
 */
class AtanTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arm, by name, as a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.atan", (x, live) -> live == null ? VFdlibm.atan(x) : VFdlibm.atan(x, live));
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertAtan(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::atan, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertAtanMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::atan, xs, seed++);
    }
  }

  /**
   * s_atan.c in scalar Java, line by line: the five ranges with their reductions, the one
   * polynomial and its two result forms, and, from {@code 2^66} up, the C's constant with
   * {@code x}'s sign and its NaN rule ({@code x+x}), which the port does not compute but
   * finishes per lane with {@code StrictMath.atan}. It matches {@code StrictMath.atan} on every
   * input the tests give it, so a hinge or a fault run that uses it shows the C's result, not a
   * guess.
   */
  static double cAtan(double x) {
    return cAtan(x, WORDS);
  }

  /** The six words the C compares {@code ix} against, in the order the transcription reads them. */
  static final int[] WORDS = {
    Atan.HX_TINY, Atan.HX_NO_REDUCTION, Atan.HX_ID0, Atan.HX_ID1, Atan.HX_ID2, Atan.HX_BIG
  };

  /** {@link #cAtan(double)} with the compare words given, so that a test can move one. */
  static double cAtan(double x, int[] words) {
    int hx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int ix = hx & 0x7fffffff;
    if (ix >= words[5]) {
      // Lines 97-102: x+x for a NaN, else atanhi[3]+atanlo[3] with x's sign.
      if (ix > 0x7ff00000 || (ix == 0x7ff00000 && (int) Double.doubleToRawLongBits(x) != 0)) {
        return x + x;
      }
      return hx > 0 ? Atan.ATAN_HI[3] + Atan.ATAN_LO[3] : -Atan.ATAN_HI[3] - Atan.ATAN_LO[3];
    }
    int id;
    if (ix < words[1]) {
      if (ix < words[0]) {
        return x;
      }
      id = -1;
    } else {
      x = Math.abs(x);
      if (ix < words[3]) {
        if (ix < words[2]) {
          id = 0;
          x = (2.0 * x - Atan.ONE) / (2.0 + x);
        } else {
          id = 1;
          x = (x - Atan.ONE) / (x + Atan.ONE);
        }
      } else {
        if (ix < words[4]) {
          id = 2;
          x = (x - 1.5) / (Atan.ONE + 1.5 * x);
        } else {
          id = 3;
          x = -1.0 / x;
        }
      }
    }
    double[] aT = Atan.AT;
    double z = x * x;
    double w = z * z;
    double s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
    double s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
    if (id < 0) {
      return x - x * (s1 + s2);
    }
    z = Atan.ATAN_HI[id] - ((x * (s1 + s2) - Atan.ATAN_LO[id]) - x);
    return hx < 0 ? -z : z;
  }

  /**
   * Every threshold s_atan.c branches on, with its neighbours, both signs: the high words of its
   * tiny test, its four range splits and its {@code 2^66}, 64 ulps each way, and the special
   * values.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(
        0.4375, -0.4375, 0.6875, 1.1875, 2.4375, 1.0, -1.0, 0.5, 1.5, 0x1p66, -0x1p66, 0x1p-29);
    int[] words = {
      Atan.HX_TINY, Atan.HX_NO_REDUCTION, Atan.HX_ID0, Atan.HX_ID1, Atan.HX_ID2, Atan.HX_BIG,
      0x7ff00000
    };
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertAtan(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertAtan(width.species(), Inputs.sampled(7000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-6, 6): the no-reduction range and the four reductions, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertAtan(width.species(), Inputs.uniform(7100L + width.ordinal(), DENSE, -6.0, 6.0));
  }

  /** Draws spread over the binades 2^-60 to 2^70, both signs: tiny lanes, every range, huge. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 7200L + 16L * width.ordinal();
    assertAtan(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, 70),
        Inputs.magnitudes(seed + 1, 1 << 16, -35, -20),
        Inputs.uniform(seed + 2, 1 << 16, -1.0, 1.0),
        Inputs.magnitudes(seed + 3, 1 << 16, 60, 70)));
  }

  /** Dense draws inside each range, so each reduction's own operands are exercised at length. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void eachRange(VectorWidth width) {
    long seed = 7300L + 16L * width.ordinal();
    assertAtan(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.4375, 0.6875),
        Inputs.uniform(seed + 1, 1 << 16, 0.6875, 1.1875),
        Inputs.uniform(seed + 2, 1 << 16, 1.1875, 2.4375),
        Inputs.uniform(seed + 3, 1 << 16, 2.4375, 1000.0),
        Inputs.uniform(seed + 4, 1 << 16, -1000.0, -0.4375),
        Inputs.uniform(seed + 5, 1 << 16, -0.4375, 0.4375)));
  }

  /**
   * Draws over the two words either side of each compare's word, {@code word - 1} and {@code
   * word}, both signs, with random low words: the lanes a compare moved by one word would send
   * to the other branch.
   */
  static double[] gapInputs(int word, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[1 << 16];
    for (int i = 0; i < xs.length; i++) {
      long hi = (long) (word - (i & 1)) << 32;
      long sign = (i & 2) == 0 ? 0L : Long.MIN_VALUE;
      xs[i] = Double.longBitsToDouble(sign | hi | (random.nextLong() & 0xffffffffL));
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void wordsOfEachCompare(VectorWidth width) {
    for (int k = 0; k < WORDS.length; k++) {
      assertAtan(width.species(), gapInputs(WORDS[k], 7900L + k));
    }
  }

  /**
   * The draws of {@link #gapInputs} see a compare moved by one word, in either direction, for
   * the four compares at 7/16, 11/16, 19/16 and 39/16: the scalar transcription with that word
   * moved differs from {@code StrictMath.atan} on them. They see none for the compares at
   * {@code 2^-29} and {@code 2^66}: moved by one word, the lanes of the word between give the
   * same bits, so no test of results can see those two move (PROOFS.md, atan).
   */
  @Test
  void gapDrawsSeeAMovedCompareWhereOneCanBeSeen() {
    for (int k = 0; k < WORDS.length; k++) {
      for (int delta = -1; delta <= 1; delta += 2) {
        int[] moved = WORDS.clone();
        moved[k] += delta;
        long differing = 0;
        for (double x : gapInputs(WORDS[k], 7900L + k)) {
          if (Double.doubleToRawLongBits(cAtan(x, moved))
              != Double.doubleToRawLongBits(StrictMath.atan(x))) {
            differing++;
          }
        }
        if (k == 0 || k == 5) {
          assertEquals(0L, differing, "compare " + k + " moved by " + delta);
        } else {
          assertTrue(differing > 0, "compare " + k + " moved by " + delta + " is not seen");
        }
      }
    }
  }

  /** The published hard-to-round inputs, and their negations: see {@link Inputs#hardCases}. */
  private static double[] hardCasesBothSigns() {
    double[] xs = Inputs.hardCases("atan");
    double[] negated = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      negated[i] = -xs[i];
    }
    return Inputs.concat(xs, negated);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertAtan(width.species(), hardCasesBothSigns());
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(7400L, 4096),
        Inputs.uniform(7401L, 4096, -6.0, 6.0), Inputs.magnitudes(7402L, 4096, -60, 70),
        hardCasesBothSigns());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertAtanMasked(width.species(), maskedInputs(), 7403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * atan's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(7500L, 1 << 14),
        Inputs.uniform(7501L, 1 << 14, -6.0, 6.0), Inputs.magnitudes(7502L, 1 << 14, -60, 70),
        hardCasesBothSigns());
    assertAtan(DoubleVector.SPECIES_MAX, xs);
    assertAtanMasked(DoubleVector.SPECIES_MAX, xs, 7503L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare ({@code 2^66} or
   * more in magnitude, an infinity or a NaN) and the others common, the subsets as {@link
   * ExpTest#rareLaneSubsets} lists them (every subset up to 16 lanes, sampled above): exactly
   * one rare lane at each position, every lane rare, and every mix between. The finish walks the
   * rare mask's set bits, so a lane it skipped or visited twice shows here. The rare inputs cycle
   * through every kind: {@code 2^66} and just above, huge values of both signs, the infinities
   * and NaNs of both signs. The common lanes cycle through every range, both signs, and include
   * both zeros and a tiny one.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      0x1p66, -0x1p66, 0x1p67, -1.0e30, 1.0e300, -1.0e300, Double.NaN,
      Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double[] common = {0.0, -0.0, 0x1p-40, -0x1p-30};
    double[] ranges = {0.1, 0.5, 0.9, 2.0, 5.0, -0.5, -3.0, -0.2};
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length]
            : (s + lane) % 5 == 0 ? common[(s / 5 + lane) % common.length]
            : ranges[(s + lane) % ranges.length];
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
    assertAtan(species, xs);
    assertAtanMasked(species, xs, 7600L);
  }

  /**
   * The scalar transcription of s_atan.c is {@code StrictMath.atan} on the inputs the vector
   * tests use, so a hinge or a fault run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(7700L, 1 << 16),
        Inputs.uniform(7701L, 1 << 16, -6.0, 6.0), Inputs.magnitudes(7702L, 1 << 16, -60, 70),
        hardCasesBothSigns());
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.atan(x)),
          Double.doubleToRawLongBits(cAtan(x)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.atan(x, null));
  }

  /** The words and the coefficients are s_atan.c's, by the hexadecimal words its comments give. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Atan.ONE);
    // s_atan.c's literal words: lines 97, 103, 104, 110, 111 and 116.
    assertEquals(0x44100000, Atan.HX_BIG);
    assertEquals(0x3fdc0000, Atan.HX_NO_REDUCTION);
    assertEquals(0x3e200000, Atan.HX_TINY);
    assertEquals(0x3ff30000, Atan.HX_ID1);
    assertEquals(0x3fe60000, Atan.HX_ID0);
    assertEquals(0x40038000, Atan.HX_ID2);
    long[] hi = {0x3FDDAC670561BB4FL, 0x3FE921FB54442D18L, 0x3FEF730BD281F69BL,
        0x3FF921FB54442D18L};
    long[] lo = {0x3C7A2B7F222F65E2L, 0x3C81A62633145C07L, 0x3C7007887AF0CBBDL,
        0x3C91A62633145C07L};
    long[] at = {0x3FD555555555550DL, 0xBFC999999998EBC4L, 0x3FC24924920083FFL,
        0xBFBC71C6FE231671L, 0x3FB745CDC54C206EL, 0xBFB3B0F2AF749A6DL, 0x3FB10D66A0D03D51L,
        0xBFADDE2D52DEFD9AL, 0x3FA97B4B24760DEBL, 0xBFA2B4442C6A6C2FL, 0x3F90AD3AE322DA11L};
    for (int i = 0; i < 4; i++) {
      assertBits(hi[i], Atan.ATAN_HI[i]);
      assertBits(lo[i], Atan.ATAN_LO[i]);
    }
    for (int i = 0; i < at.length; i++) {
      assertBits(at[i], Atan.AT[i]);
    }
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of atan's faults, with a check that must fail while it is planted: the vector form
   * over {@link #faultRunInputs()} at every width the host has. {@code atanlo} dropped shows in
   * every reduced range, the 19/16 split moved to 2 in the lanes of {@code [19/16, 2)} that round
   * differently in the other form, and {@code atanhi[1]} one ulp up in the lanes of {@code
   * [11/16, 19/16)}. Each was sized on 2^22 draws before it was wired; the sizes are in {@code
   * SKILLS.md}, "What the atan port taught".
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Atan.DROP_LO, Atan.SPLIT_MOVED, Atan.HI_ONE_ULP_OFF}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::atan, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(7800L, 1 << 16, -3.0, 3.0),
        Inputs.uniform(7801L, 1 << 14, 1.1875, 2.0),
        Inputs.uniform(7802L, 1 << 14, -2.0, -1.1875));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertAtan(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
