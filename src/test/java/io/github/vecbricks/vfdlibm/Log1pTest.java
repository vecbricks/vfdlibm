/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * cLog1p below transcribes fdlibm 5.3's s_log1p.c (reference/fdlibm-5.3/s_log1p.c), which
 * carries this notice:
 *
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * log1p against {@code StrictMath.log1p}, by raw bits, at every width: the public {@link
 * VFdlibm#log1p} and each of {@code Log1p}'s arms ({@link Log1p#log1pBlended}, {@link
 * Log1p#log1pGuarded}, {@link Log1p#log1pFinish}), unmasked and masked, on s_log1p.c's
 * thresholds, the special values, sampled bit patterns over the whole domain, dense draws over
 * each range the C treats differently and the published hard-to-round cases.
 */
class Log1pTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;
  private static final double TWO52 = 0x1p52;
  private static final double TWO53 = 0x1p53;

  /** The arms, by name, each a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.log1p", (x, live) -> live == null ? VFdlibm.log1p(x) : VFdlibm.log1p(x, live));
    arms.put("Log1p.log1pBlended", Log1p::log1pBlended);
    arms.put("Log1p.log1pGuarded", Log1p::log1pGuarded);
    arms.put("Log1p.log1pFinish", Log1p::log1pFinish);
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertLog1p(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::log1p, xs));
  }

  /**
   * Every threshold s_log1p.c branches on, with its 64 neighbours each way, and the specials.
   * The thresholds on {@code hx} are the doubles whose high word is the C's constant; the ones on
   * {@code hu}, the high word of {@code u = 1+x}, are swept through {@code x = u - 1} at every
   * binary exponent of {@code u} from 2^-60 to 2^60: the powers of two (where {@code hu == 0}),
   * the significands just below them (where the halved form's {@code hu} reaches 0) and the
   * {@code sqrt(2)} split at {@code 0x6a09e}.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(-1.0, TWO53);
    double[] edges = {
      Lanes.fromHi(Log1p.HX_BELOW_SQRT2_M1), Lanes.fromHi(Log1p.HX_BELOW_SQRT2_M1 - 1),
      -Lanes.fromHi(Log1p.HX_NEGATIVE_K_ZERO & 0x7fffffff),
      -Lanes.fromHi((Log1p.HX_NEGATIVE_K_ZERO & 0x7fffffff) + 1),
      Lanes.fromHi(Log1p.AX_TINY), -Lanes.fromHi(Log1p.AX_TINY),
      Lanes.fromHi(Log1p.AX_TINIEST), -Lanes.fromHi(Log1p.AX_TINIEST),
      Lanes.fromHi(Log1p.HX_HUGE), TWO52, -1.0, 0.0, Math.sqrt(2.0) - 1.0, Math.sqrt(0.5) - 1.0
    };
    for (double edge : edges) {
      xs = Inputs.concat(xs, Inputs.around(edge, 64));
    }
    for (int e = -60; e <= 60; e++) {
      int exponentWord = (e + 1023) << 20;
      double power = Lanes.fromHi(exponentWord);
      double[] us = {
        power, Lanes.fromHi(exponentWord | 0xffffd), Lanes.fromHi(exponentWord | Log1p.HU_SQRT2)
      };
      for (double u : us) {
        xs = Inputs.concat(xs, Inputs.around(u - 1.0, 16));
      }
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertLog1p(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertLog1p(width.species(), Inputs.sampled(700L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-1, 4), which holds both k = 0 ranges and both reductions around them. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseNearZero(VectorWidth width) {
    assertLog1p(width.species(),
        Inputs.uniform(800L + width.ordinal(), DENSE, Math.nextUp(-1.0), 4.0));
  }

  /** Every magnitude from 2^-60 to 2^1023 and the band from 2^52 to 2^53 where u = 1+x ends. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    assertLog1p(width.species(), Inputs.concat(
        Inputs.magnitudes(900L + width.ordinal(), 1 << 18, -60, 1023),
        Inputs.uniform(910L + width.ordinal(), 1 << 16, TWO52, 2.0 * TWO53)));
  }

  /** Uniform draws over (-1, -2^-30), where x+1 loses bits to rounding and the correction acts. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void negative(VectorWidth width) {
    assertLog1p(width.species(), Inputs.concat(
        Inputs.uniform(1000L + width.ordinal(), 1 << 16, Math.nextUp(-1.0), -0x1p-30),
        Inputs.magnitudes(1010L + width.ordinal(), 1 << 16, -60, -1)));
  }

  /**
   * Draws within 2^-21 of each power of two of {@code u = 1+x}, from 2^-30 to 2^60, where {@code
   * |f| < 2^-20} and the C takes its {@code hu == 0} path.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void nearPowersOfTwo(VectorWidth width) {
    double[] xs = new double[0];
    for (int e = -30; e <= 60; e++) {
      double power = Math.scalb(1.0, e);
      double half = Math.scalb(1.0, e - 21);
      xs = Inputs.concat(xs, Inputs.uniform(
          1100L + 97L * e + width.ordinal(), 1 << 10, power - half - 1.0, power + half - 1.0));
    }
    assertLog1p(width.species(), xs);
  }

  /** The published hard-to-round inputs: see {@link Inputs#hardCases} and the resource header. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertLog1p(width.species(), Inputs.hardCases("log1p"));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = Inputs.concat(
        thresholds(), Inputs.sampled(1200L, 4096), Inputs.uniform(1201L, 4096, -1.0, 4.0),
        Inputs.hardCases("log1p"));
    long seed = 1202L;
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::log1p, xs, seed++);
    }
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * log1p's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(1300L, 1 << 14),
        Inputs.uniform(1301L, 1 << 14, Math.nextUp(-1.0), 4.0));
    assertLog1p(DoubleVector.SPECIES_MAX, xs);
    long seed = 1302L;
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), DoubleVector.SPECIES_MAX, arm.getValue(), StrictMath::log1p, xs, seed++);
    }
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare and the others
   * common, the subsets as {@link ExpTest#rareLaneSubsets} lists them (every subset up to 16
   * lanes, sampled above): exactly one rare lane at each position, every lane rare, and every mix
   * between. The finish arm walks the rare mask's set bits, so a lane it skipped or visited twice
   * shows here, and the guarded arm's one test sees every mix. The rare inputs cycle through each
   * of s_log1p.c's rare paths: NaNs of both signs, the infinities, -1, below -1, {@code |x| <
   * 2^-54}, {@code 2^-54 <= |x| < 2^-29}, and {@code 1+x} a power of two ({@code hu == 0}). The
   * common lanes have {@code 1+x} in [0.8, 6.4], never within 2^-20 of a power of two.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      Double.NaN, Double.longBitsToDouble(0xfff8_0000_0000_0001L), -1.0, -2.0,
      Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0x1p-60, -0x1p-40, 1.0, 3.0, -0.5
    };
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length] : 0.37 * lane - 0.2;
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
    assertLog1p(species, xs);
    long seed = 1400L;
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::log1p, xs, seed++);
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.log1p(x, null));
  }

  /** The constants parse to the hex s_log1p.c gives for each. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3fe62e42_fee00000L, Log1p.LN2_HI);
    assertBits(0x3dea39ef_35793c76L, Log1p.LN2_LO);
    assertBits(0x43500000_00000000L, Log1p.TWO54);
    assertBits(0x3FE55555_55555593L, Log1p.LP1);
    assertBits(0x3FD99999_9997FA04L, Log1p.LP2);
    assertBits(0x3FD24924_94229359L, Log1p.LP3);
    assertBits(0x3FCC71C5_1D8E78AFL, Log1p.LP4);
    assertBits(0x3FC74664_96CB03DEL, Log1p.LP5);
    assertBits(0x3FC39A09_D078C69FL, Log1p.LP6);
    assertBits(0x3FC2F112_DF3E5244L, Log1p.LP7);
    // The decimal of line 156 is not a hex constant in the C; it rounds to 2/3's nearest double.
    assertBits(Double.doubleToRawLongBits(2.0 / 3.0), Log1p.TWO_THIRDS);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // The C, in scalar Java

  /** {@code __HI(x)}. */
  private static int hi(double x) {
    return (int) (Double.doubleToRawLongBits(x) >>> 32);
  }

  /** {@code __HI(u) = hi}. */
  private static double withHi(double u, int hi) {
    return Double.longBitsToDouble(
        ((long) hi << 32) | (Double.doubleToRawLongBits(u) & Lanes.LOW_WORD));
  }

  /**
   * s_log1p.c's {@code log1p}, lines 109 to 164, line by line in scalar Java, with the port's
   * constants. With {@code generalPathOnly}, an input of the {@code hu == 0} path whose {@code f}
   * is not zero skips lines 156 to 158 and takes the general polynomial path, lines 160 to 164:
   * the computation a port that folded the short path away would make. {@link Log1pShortPath}
   * compares the two.
   */
  static double cLog1p(double x, boolean generalPathOnly) {
    double hfsq;
    double f = 0;
    double c = 0;
    double s;
    double z;
    double r;
    double u;
    int k;
    int hx;
    int hu = 0;
    int ax;

    hx = hi(x);
    ax = hx & 0x7fffffff;

    k = 1;
    if (hx < Log1p.HX_BELOW_SQRT2_M1) {
      if (ax >= Log1p.AX_MINUS_ONE) {
        if (x == -1.0) {
          return -Log1p.TWO54 / Log1p.ZERO;
        } else {
          // The C's (x-x)/(x-x): an invalid operation, whose NaN's bits the C leaves to the
          // hardware; StrictMath.log1p gives Double.NaN for every such x, as the port does
          // (SKILLS.md, "An invalid operation's NaN is not the hardware's").
          return Double.NaN;
        }
      }
      if (ax < Log1p.AX_TINY) {
        if (Log1p.TWO54 + x > Log1p.ZERO && ax < Log1p.AX_TINIEST) {
          return x;
        } else {
          return x - x * x * 0.5;
        }
      }
      if (hx > 0 || hx <= Log1p.HX_NEGATIVE_K_ZERO) {
        k = 0;
        f = x;
        hu = 1;
      }
    }
    if (hx >= Log1p.HX_NON_FINITE) {
      return x + x;
    }
    if (k != 0) {
      if (hx < Log1p.HX_HUGE) {
        u = 1.0 + x;
        hu = hi(u);
        k = (hu >> 20) - 1023;
        c = (k > 0) ? 1.0 - (u - x) : x - (u - 1.0);
        c /= u;
      } else {
        u = x;
        hu = hi(u);
        k = (hu >> 20) - 1023;
        c = 0;
      }
      hu &= 0x000fffff;
      if (hu < Log1p.HU_SQRT2) {
        u = withHi(u, hu | 0x3ff00000);
      } else {
        k += 1;
        u = withHi(u, hu | 0x3fe00000);
        hu = (0x00100000 - hu) >> 2;
      }
      f = u - 1.0;
    }
    hfsq = 0.5 * f * f;
    if (hu == 0) {
      if (f == Log1p.ZERO) {
        if (k == 0) {
          return Log1p.ZERO;
        } else {
          c += k * Log1p.LN2_LO;
          return k * Log1p.LN2_HI + c;
        }
      }
      if (!generalPathOnly) {
        r = hfsq * (1.0 - Log1p.TWO_THIRDS * f);
        if (k == 0) {
          return f - r;
        } else {
          return k * Log1p.LN2_HI - ((r - (k * Log1p.LN2_LO + c)) - f);
        }
      }
    }
    s = f / (2.0 + f);
    z = s * s;
    r = z * (Log1p.LP1 + z * (Log1p.LP2 + z * (Log1p.LP3 + z * (Log1p.LP4 + z * (Log1p.LP5 + z
        * (Log1p.LP6 + z * Log1p.LP7))))));
    if (k == 0) {
      return f - (hfsq - s * (hfsq + r));
    } else {
      return k * Log1p.LN2_HI - ((hfsq - (s * (hfsq + r) + (k * Log1p.LN2_LO + c))) - f);
    }
  }

  /** Whether {@link #cLog1p} reaches line 156 at {@code x}: {@code hu == 0} with f not zero. */
  static boolean takesShortPath(double x) {
    int hx = hi(x);
    if (hx < Log1p.HX_BELOW_SQRT2_M1
        && ((hx & 0x7fffffff) >= Log1p.AX_MINUS_ONE || (hx & 0x7fffffff) < Log1p.AX_TINY
            || hx > 0 || hx <= Log1p.HX_NEGATIVE_K_ZERO)) {
      return false;
    }
    if (hx >= Log1p.HX_NON_FINITE) {
      return false;
    }
    double u = hx < Log1p.HX_HUGE ? 1.0 + x : x;
    int hu = hi(u) & 0x000fffff;
    boolean lowZero = (int) Double.doubleToRawLongBits(u) == 0;
    return hu < Log1p.HU_SQRT2 ? hu == 0 && !lowZero : (0x00100000 - hu) >> 2 == 0;
  }

  /**
   * The scalar transcription {@link #cLog1p} is the oracle's computation, bit for bit, on every
   * input this suite has: the thresholds, the hard cases, sampled bit patterns, the draws near
   * the powers of two of {@code 1+x} where the {@code hu == 0} path runs, and negative x.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.hardCases("log1p"), loneNaNInputs(),
        Inputs.sampled(4900L, 1 << 16), Inputs.uniform(4901L, 1 << 16, Math.nextUp(-1.0), 3.0),
        Inputs.magnitudes(4902L, 1 << 16, -60, 60), Log1pShortPath.nearPowersOfTwo(4903L, 1 << 8));
    int shortPath = 0;
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.log1p(x)),
          Double.doubleToRawLongBits(cLog1p(x, false)), Oracle.hex(x));
      if (takesShortPath(x)) {
        shortPath++;
      }
    }
    assertTrue(shortPath > 1 << 12, "too few inputs on the hu == 0 path: " + shortPath);
  }

  /**
   * The {@code hu == 0} path is not the general path: at these inputs s_log1p.c as written is
   * {@code StrictMath.log1p} and the same C sent down its general polynomial path is the next
   * double. So no lemma folds line 156's path into lines 160 to 164 (PROOFS.md, step 5 of issue
   * 36): the fold is not exact.
   *
   * <p>How they were found, with the counts, in {@value Log1pShortPath#RESULTS}: every double whose
   * raw bits lie in the window of {@code 1+x} around 2, {@code [0x3feffffa_00000000,
   * 0x3ff00002_00000000)}, 2^35 doubles, gave 56 differences, and the window around 0.5,
   * {@code [0xbfdffffe_00000000, 0xbfe00001_80000000)}, 15,032,385,536 doubles, gave 20, each
   * pair of results through {@link #cLog1p} with and without {@code generalPathOnly}. These four
   * are among those printed there: two of {@code k = 1}, one of them above 1 ({@code 1+x} just
   * above 2, {@code hu == 0} directly) and one below ({@code 1+x} just below 2, {@code hu =
   * (0x00100000-hu)>>2} is 0); and two of {@code k = -1}, the same two ways at 0.5.
   */
  @Test
  void shortPathIsNotTheGeneralPath() {
    for (long bits : new long[] {0x3feffffd_05263833L, 0x3ff00001_6fcd0162L,
        0xbfe00001_2cac6e81L, 0xbfdffffe_92ae1567L}) {
      double x = Double.longBitsToDouble(bits);
      long want = Double.doubleToRawLongBits(StrictMath.log1p(x));
      assertTrue(takesShortPath(x), Oracle.hex(x));
      assertEquals(want, Double.doubleToRawLongBits(cLog1p(x, false)), Oracle.hex(x));
      assertNotEquals(want, Double.doubleToRawLongBits(cLog1p(x, true)), Oracle.hex(x));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults

  /**
   * log1p's faults, each with the check that must catch it: every arm, at each width the host
   * has, over the thresholds, dense draws over (-1, 4) and draws from 2^52 to 2^53. The same
   * inputs pass in the normal run, through {@link #faultRunInputsPass}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Log1p.DROP_CORRECTION, Log1p.CONSTANT_OFF_BY_ONE_ULP, Log1p.THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::log1p, faultRunInputs())))));
    }
    // The guarded arm's own fault reaches only that arm, and shows only in a vector whose sole
    // rare lanes are positive NaNs (an infinity is an hu == 0 lane too, so it stays rare).
    catchers.put(Log1p.GUARD_MISSES_NON_FINITE, () -> forEachWidth(species ->
        PlantedFaultTest.assertCaught(Log1p.GUARD_MISSES_NON_FINITE, () -> Oracle.assertUnary(
            "Log1p.log1pGuarded", species, x -> Log1p.log1pGuarded(x, null), StrictMath::log1p,
            loneNaNInputs()))));
    return catchers;
  }

  /**
   * Common inputs, 1+x in [1.6, 1.9] and so never within 2^-20 of a power of two, with every 16th
   * replaced by {@code Double.NaN}: at every width some vector's only rare lane is a NaN.
   */
  private static double[] loneNaNInputs() {
    double[] xs = Inputs.uniform(1302L, 1 << 12, 0.6, 0.9);
    for (int i = 5; i < xs.length; i += 16) {
      xs[i] = Double.NaN;
    }
    return xs;
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void loneNaNInputsPass(VectorWidth width) {
    assertLog1p(width.species(), loneNaNInputs());
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(1300L, 1 << 16, Math.nextUp(-1.0), 4.0),
        Inputs.uniform(1301L, 1 << 12, TWO52, TWO53));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertLog1p(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
