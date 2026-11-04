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
 * expm1 against {@code StrictMath.expm1}, by raw bits, at every width and at {@code
 * SPECIES_MAX}: the public {@link VFdlibm#expm1} and each of {@code Expm1}'s arms ({@link
 * Expm1#expm1Blended}, {@link Expm1#expm1Guarded}, {@link Expm1#expm1Finish}), unmasked and
 * masked, on s_expm1.c's high-word tests, the doubles where its {@code k} steps and so its
 * return form changes, the special values, sampled bit patterns over the whole domain, dense
 * draws over each range the C treats differently, the fold boundaries of {@code PROOFS.md}'s
 * L10 and L11, and every subset of a vector's lanes rare.
 */
class Expm1Test {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;
  private static final double LN2 = StrictMath.log(2.0);

  /** The arms, by name, each a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.expm1", (x, live) -> live == null ? VFdlibm.expm1(x) : VFdlibm.expm1(x, live));
    arms.put("Expm1.expm1Blended", Expm1::expm1Blended);
    arms.put("Expm1.expm1Guarded", Expm1::expm1Guarded);
    arms.put("Expm1.expm1Finish", Expm1::expm1Finish);
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertExpm1(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::expm1, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertExpm1Masked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::expm1, xs, seed++);
    }
  }

  /**
   * s_expm1.c in scalar Java, line by line, for the hinge tests of {@code ProofsTest}: the C's
   * own two reductions and its {@code k == 0} return, none of the port's folds. With {@code
   * unforcedK}, a lane of word {@code 0x3fd62e42} whose far-form {@code k} is not 0 is reduced
   * as if line 162's test held, which is what the port would do without forcing {@code k = 0}
   * (PROOFS.md, L10). It matches {@code StrictMath.expm1} on every input the tests give it.
   */
  static double cExpm1(double x, boolean unforcedK) {
    int hx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int xsb = hx & 0x80000000;
    hx &= 0x7fffffff;
    if (hx >= Expm1.HX_56_LN2) {
      if (hx >= Expm1.HX_FILTER) {
        if (hx >= Expm1.HX_NON_FINITE) {
          if (((hx & 0xfffff) | (int) Double.doubleToRawLongBits(x)) != 0) {
            return x + x;
          }
          return xsb == 0 ? x : -1.0;
        }
        if (x > Expm1.O_THRESHOLD) {
          return Expm1.HUGE * Expm1.HUGE;
        }
      }
      if (xsb != 0 && x + Expm1.TINY < 0.0) {
        return Expm1.TINY - Expm1.ONE;
      }
    }
    double hi;
    double lo;
    double c = 0.0;
    int k;
    int farK = (int) (Expm1.INVLN2 * x + (xsb == 0 ? 0.5 : -0.5));
    if (hx > Expm1.HX_HALF_LN2 || (unforcedK && hx == Expm1.HX_HALF_LN2 && farK != 0)) {
      if (hx < Expm1.HX_THREE_HALVES_LN2 && hx > Expm1.HX_HALF_LN2) {
        if (xsb == 0) {
          hi = x - Expm1.LN2_HI;
          lo = Expm1.LN2_LO;
          k = 1;
        } else {
          hi = x + Expm1.LN2_HI;
          lo = -Expm1.LN2_LO;
          k = -1;
        }
      } else {
        k = farK;
        double t = k;
        hi = x - t * Expm1.LN2_HI;
        lo = t * Expm1.LN2_LO;
      }
      x = hi - lo;
      c = (hi - x) - lo;
    } else if (hx < Expm1.HX_TINY) {
      double t = Expm1.HUGE + x;
      return x - (t - (Expm1.HUGE + x));
    } else {
      k = 0;
    }
    double hfx = 0.5 * x;
    double hxs = x * hfx;
    double r1 = Expm1.ONE + hxs * (Expm1.Q1 + hxs * (Expm1.Q2 + hxs * (Expm1.Q3 + hxs
        * (Expm1.Q4 + hxs * Expm1.Q5))));
    double t = 3.0 - r1 * hfx;
    double e = hxs * ((r1 - t) / (6.0 - x * t));
    if (k == 0) {
      return x - (x * e - hxs);
    }
    e = (x * (e - c) - c);
    e -= hxs;
    if (k == -1) {
      return 0.5 * (x - e) - 0.5;
    }
    if (k == 1) {
      return x < -0.25 ? -2.0 * (e - (x + 0.5)) : Expm1.ONE + 2.0 * (x - e);
    }
    double y;
    if (k <= -2 || k > Expm1.K_FAR) {
      y = Expm1.ONE - (e - x);
      return addHi(y, k << 20) - Expm1.ONE;
    }
    if (k < Expm1.K_SPLIT) {
      y = withHi(Expm1.ONE, 0x3ff00000 - (0x200000 >> k)) - (e - x);
    } else {
      y = x - (e + withHi(Expm1.ONE, (0x3ff - k) << 20));
      y += Expm1.ONE;
    }
    return addHi(y, k << 20);
  }

  /** {@code __HI(y) += add}, the C's int sum modulo 2^32 in the high word. */
  private static double addHi(double y, int add) {
    long bits = Double.doubleToRawLongBits(y);
    int hi = (int) (bits >>> 32) + add;
    return Double.longBitsToDouble(((long) hi << 32) | (bits & Lanes.LOW_WORD));
  }

  /** {@code __HI(y) = hi}. */
  private static double withHi(double y, int hi) {
    return Double.longBitsToDouble(
        ((long) hi << 32) | (Double.doubleToRawLongBits(y) & Lanes.LOW_WORD));
  }

  /** The double {@code (k + 1/2) ln2}, where the far form's {@code k} steps from k to k+1. */
  private static double kStep(int k) {
    return (k + 0.5) * LN2;
  }

  /**
   * Every threshold s_expm1.c branches on, with its neighbours, and the specials: the high words
   * of its filter, reduction and tiny tests, both signs, with 64 neighbours of each, the
   * overflow threshold, {@code 56*ln2}, and the 16 neighbours of {@code (k+1/2) ln2} for every
   * {@code k} from -58 to 1025, where {@code k} steps and the return form changes at -1/-2, 1/2,
   * 19/20 and 56/57; and the reduced {@code x = -0.25} split of the {@code k == 1} form, at
   * {@code x} about {@code ln2 - 0.25}.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(Expm1.O_THRESHOLD, -Expm1.O_THRESHOLD, 56 * LN2, -56 * LN2);
    int[] words = {
      Expm1.HX_56_LN2, Expm1.HX_FILTER, Expm1.HX_NON_FINITE, Expm1.HX_HALF_LN2,
      Expm1.HX_HALF_LN2 + 1, Expm1.HX_THREE_HALVES_LN2, Expm1.HX_TINY
    };
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    for (int k = -58; k <= 1025; k++) {
      xs = Inputs.concat(xs, Inputs.around(kStep(k), 8));
    }
    // The k == 1 lanes whose reduced x = hi - lo is near -0.25: x = ln2_hi - 0.25 + lo.
    double split = Expm1.LN2_HI - 0.25;
    return Inputs.concat(xs, Inputs.around(split, 256), Inputs.around(-split, 64),
        Inputs.around(Expm1.O_THRESHOLD, 64));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertExpm1(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertExpm1(width.species(), Inputs.sampled(3000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-45, 45), every return form and the filter's -1.0 below -56 ln2. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheForms(VectorWidth width) {
    assertExpm1(width.species(), Inputs.uniform(3100L + width.ordinal(), DENSE, -45.0, 45.0));
  }

  /**
   * Every magnitude from 2^-60 to 2^10, both signs, the unreduced band below 0.5 ln2 densely,
   * and the large positive x up to the overflow threshold, where k reaches 1024.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 3200L + 16L * width.ordinal();
    assertExpm1(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, 10),
        Inputs.magnitudes(seed + 1, 1 << 16, -60, -1),
        Inputs.uniform(seed + 2, 1 << 16, -0.5 * LN2, 0.5 * LN2),
        Inputs.uniform(seed + 3, 1 << 16, 38.0, Expm1.O_THRESHOLD)));
  }

  /** Dense draws over the k bands whose form changes: 19 to 21 and 55 to 58 times ln2. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void formSplits(VectorWidth width) {
    long seed = 3300L + 16L * width.ordinal();
    assertExpm1(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 19.0 * LN2, 21.0 * LN2),
        Inputs.uniform(seed + 1, 1 << 16, 55.0 * LN2, 58.0 * LN2),
        Inputs.uniform(seed + 2, 1 << 16, -3.0 * LN2, -0.5 * LN2),
        Inputs.uniform(seed + 3, 1 << 16, 0.5 * LN2, 3.0 * LN2)));
  }

  /** The high words where PROOFS.md's L10 and L11 hinge: the tiny, k == 0 and near-band edges. */
  static final int[] FOLD_WORDS = {
    Expm1.HX_TINY - 1, Expm1.HX_TINY, Expm1.HX_HALF_LN2, Expm1.HX_HALF_LN2 + 1,
    Expm1.HX_THREE_HALVES_LN2 - 1, Expm1.HX_THREE_HALVES_LN2
  };

  /**
   * Every high word of {@link #FOLD_WORDS}, both signs, with the low words 0 and 0xffffffff and
   * 1024 seeded ones, and the 4096 doubles either side of {@code +-0.5 ln2}, where the far
   * form's formula gives {@code k = +-1} on lanes the C does not reduce (PROOFS.md, L10).
   */
  static double[] foldBoundaries() {
    SplittableRandom random = new SplittableRandom(3400L);
    double[] xs = new double[FOLD_WORDS.length * 2 * 1026];
    int next = 0;
    for (int h : FOLD_WORDS) {
      for (long sign : new long[] {0L, Long.MIN_VALUE}) {
        long high = Lanes.fromHiBits(h) | sign;
        xs[next++] = Double.longBitsToDouble(high);
        xs[next++] = Double.longBitsToDouble(high | Lanes.LOW_WORD);
        for (int i = 0; i < 1024; i++) {
          xs[next++] = Double.longBitsToDouble(high | (random.nextLong() & Lanes.LOW_WORD));
        }
      }
    }
    double halfLn2 = 0.5 * LN2;
    return Inputs.concat(xs, Inputs.around(halfLn2, 4096), Inputs.around(-halfLn2, 4096));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void foldBoundaries(VectorWidth width) {
    assertFoldBoundaries(width.species());
  }

  @Test
  void foldBoundariesAtMaxSpecies() {
    assertFoldBoundaries(DoubleVector.SPECIES_MAX);
  }

  /** Every arm over {@link #foldBoundaries()} at {@code species}, unmasked and masked. */
  private static void assertFoldBoundaries(VectorSpecies<Double> species) {
    double[] xs = foldBoundaries();
    assertExpm1(species, xs);
    assertExpm1Masked(species, xs, 3500L);
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(3600L, 4096),
        Inputs.uniform(3601L, 4096, -45.0, 45.0), Inputs.magnitudes(3602L, 4096, -60, 10));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertExpm1Masked(width.species(), maskedInputs(), 3603L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * expm1's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(3700L, 1 << 14),
        Inputs.uniform(3701L, 1 << 14, -45.0, 45.0), Inputs.magnitudes(3702L, 1 << 14, -60, 10));
    assertExpm1(DoubleVector.SPECIES_MAX, xs);
    assertExpm1Masked(DoubleVector.SPECIES_MAX, xs, 3703L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare and the others
   * common, the subsets as {@link ExpTest#rareLaneSubsets} lists them (every subset up to 16
   * lanes, sampled above): exactly one rare lane at each position, every lane rare, and every mix
   * between. The finish arm walks the rare mask's set bits, so a lane it skipped or visited twice
   * shows here, and the guarded arm's one test sees every mix. The rare inputs cycle through each
   * of s_expm1.c's early returns: NaNs of both signs, the infinities, {@code x < -56 ln2} near
   * and far from it, {@code x > o_threshold} near and far, and {@code |x| < 2^-54} with both
   * zeros. The common lanes run from -2.2 to 7, several k forms.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      Double.NaN, Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY, -745.0, -1.0e300, Math.nextUp(Expm1.O_THRESHOLD), 1.0e300,
      0x1p-60, -0x1p-56, 0.0, -0.0
    };
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length] : 0.61 * lane - 2.2;
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
    assertExpm1(species, xs);
    assertExpm1Masked(species, xs, 3800L);
  }

  /**
   * The scalar transcription {@link #cExpm1} is the oracle's computation on the inputs the
   * hinge tests feed it, so a hinge test that runs it shows the C's result, not a guess.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), foldBoundaries(), Inputs.sampled(3900L, 1 << 16),
        Inputs.uniform(3901L, 1 << 16, -45.0, 45.0));
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.expm1(x)),
          Double.doubleToRawLongBits(cExpm1(x, false)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.expm1(x, null));
  }

  /** The constants parse to the hex s_expm1.c gives for each, and its words are the C's. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x40862E42_FEFA39EFL, Expm1.O_THRESHOLD);
    assertBits(0x3fe62e42_fee00000L, Expm1.LN2_HI);
    assertBits(0x3dea39ef_35793c76L, Expm1.LN2_LO);
    assertBits(0x3ff71547_652b82feL, Expm1.INVLN2);
    assertBits(0xBFA11111_111110F4L, Expm1.Q1);
    assertBits(0x3F5A01A0_19FE5585L, Expm1.Q2);
    assertBits(0xBF14CE19_9EAADBB7L, Expm1.Q3);
    assertBits(0x3ED0CFCA_86E65239L, Expm1.Q4);
    assertBits(0xBE8AFDB7_6E09C32DL, Expm1.Q5);
    assertBits(Double.doubleToRawLongBits(1.0e+300), Expm1.HUGE);
    assertBits(Double.doubleToRawLongBits(1.0e-300), Expm1.TINY);
    assertBits(0x3ff00000_00000000L, Expm1.ONE);
    assertBits(0x3fe00000_00000000L, Double.longBitsToDouble(Expm1.HALF_BITS));
    // s_expm1.c's literal words, lines 146-177 and 197-203.
    assertEquals(0x4043687A, Expm1.HX_56_LN2);
    assertEquals(0x40862E42, Expm1.HX_FILTER);
    assertEquals(0x7ff00000, Expm1.HX_NON_FINITE);
    assertEquals(0x3fd62e42, Expm1.HX_HALF_LN2);
    assertEquals(0x3FF0A2B2, Expm1.HX_THREE_HALVES_LN2);
    assertEquals(0x3c900000, Expm1.HX_TINY);
    assertEquals(56, Expm1.K_FAR);
    assertEquals(20, Expm1.K_SPLIT);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults

  /**
   * expm1's faults, each with the check that must catch it: every arm, at each width the host
   * has, over the thresholds and dense draws over the forms and the k = 56 band. The same inputs
   * pass in the normal run, through {@link #faultRunInputsPass}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Expm1.DROP_CORRECTION, Expm1.CONSTANT_OFF_BY_ONE_ULP, Expm1.THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::expm1, faultRunInputs())))));
    }
    // The guarded arm's own fault reaches only that arm, and shows only in a vector whose sole
    // rare lanes are the filter's finite ones, far enough out that k saturates.
    catchers.put(Expm1.GUARD_MISSES_FILTER, () -> forEachWidth(species ->
        PlantedFaultTest.assertCaught(Expm1.GUARD_MISSES_FILTER, () -> Oracle.assertUnary(
            "Expm1.expm1Guarded", species, x -> Expm1.expm1Guarded(x, null), StrictMath::expm1,
            loneFilterInputs()))));
    return catchers;
  }

  /**
   * Common inputs from (-3, 3) with every 16th replaced by {@code -1e300} or {@code 1e300} in
   * turn: at every width some vector's only rare lane is one of them.
   */
  private static double[] loneFilterInputs() {
    double[] xs = Inputs.uniform(4000L, 1 << 12, -3.0, 3.0);
    for (int i = 5; i < xs.length; i += 16) {
      xs[i] = (i / 16) % 2 == 0 ? -1.0e300 : 1.0e300;
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void loneFilterInputsPass(VectorWidth width) {
    assertExpm1(width.species(), loneFilterInputs());
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(4100L, 1 << 16, -3.0, 3.0),
        Inputs.uniform(4101L, 1 << 14, 55.5 * LN2, 56.5 * LN2));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertExpm1(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
