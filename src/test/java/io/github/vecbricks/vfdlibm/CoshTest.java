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
 * cosh against {@code StrictMath.cosh}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#cosh} (the default arm), {@link Cosh#coshSlice} and {@link Cosh#coshFinish},
 * unmasked and masked, on e_cosh.c's high-word tests, the special values, sampled bit patterns
 * over the whole domain, dense draws over each range the C treats differently, both signs, every
 * subset of a vector's lanes rare, and the published hard-to-round cases with their negations.
 */
class CoshTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arms, by name, each a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.cosh", (x, live) -> live == null ? VFdlibm.cosh(x) : VFdlibm.cosh(x, live));
    // Cosh.coshSlice is listed though VFdlibm.cosh runs it today, so that it stays tested if the
    // default changes; Nightly lists VFdlibm.cosh and Cosh.coshFinish only, and changes with it.
    arms.put("Cosh.coshSlice", Cosh::coshSlice);
    arms.put("Cosh.coshFinish", Cosh::coshFinish);
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertCosh(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::cosh, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertCoshMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::cosh, xs, seed++);
    }
  }

  /**
   * s_expm1.c's k == 0 path for a non-negative {@code x} below {@code 0.5*ln2}, lines 177-189,
   * in scalar Java.
   */
  private static double cExpm1Small(double x) {
    if (x < 0x1p-54) {
      double t = Expm1.HUGE + x;
      return x - (t - (Expm1.HUGE + x));
    }
    double hfx = 0.5 * x;
    double hxs = x * hfx;
    double r1 = Expm1.ONE + hxs * (Expm1.Q1 + hxs * (Expm1.Q2 + hxs * (Expm1.Q3
        + hxs * (Expm1.Q4 + hxs * Expm1.Q5))));
    double t = 3.0 - r1 * hfx;
    double e = hxs * ((r1 - t) / (6.0 - x * t));
    return x - (x * e - hxs);
  }

  /**
   * e_cosh.c in scalar Java, line by line, with the small range's {@code expm1} the unreduced
   * lines of s_expm1.c and {@code StrictMath.exp} for the others; from the overflow window up,
   * {@code StrictMath.cosh} itself, which is what the port finishes per lane there. It matches
   * {@code StrictMath.cosh} on every input the tests give it.
   */
  static double cCosh(double x) {
    int ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
    if (ix >= 0x7ff00000) {
      return x * x;
    }
    double ax = Math.abs(x);
    if (ix < Cosh.HX_SMALL) {
      double t = cExpm1Small(ax);
      double w = Cosh.ONE + t;
      if (ix < Cosh.HX_TINY) {
        return w;
      }
      return Cosh.ONE + (t * t) / (w + w);
    }
    if (ix < Cosh.HX_LIMIT) {
      double t = StrictMath.exp(ax);
      return Cosh.HALF * t + Cosh.HALF / t;
    }
    if (ix < Cosh.HX_FILTER) {
      return Cosh.HALF * StrictMath.exp(ax);
    }
    return StrictMath.cosh(x);
  }

  /**
   * Every threshold e_cosh.c branches on, with its neighbours, both signs: the high words of its
   * tiny test, of s_expm1.c's, its split at {@code 0.5*ln2}, its 22, the exp filter, the
   * overflow threshold and the infinities, 64 ulps each way, and the special values.
   */
  static double[] thresholds() {
    double overflow = Double.longBitsToDouble(0x408633ce_8fb9f87dL);
    double[] xs = Inputs.specials(22.0, -22.0, 0.5 * StrictMath.log(2.0), overflow, -overflow,
        0x1p-54, 0x1p-55);
    int[] words = {
      Cosh.HX_TINY, Expm1.HX_TINY, Cosh.HX_SMALL, Cosh.HX_SMALL - 1, Cosh.HX_LIMIT,
      Cosh.HX_FILTER, 0x408633ce, 0x7ff00000
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
    assertCosh(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertCosh(width.species(), Inputs.sampled(6000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-30, 30): the small range, both exp ranges, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertCosh(width.species(), Inputs.uniform(6100L + width.ordinal(), DENSE, -30.0, 30.0));
  }

  /** Draws spread over the binades 2^-60 to 2^10, both signs, and the small range densely. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 6200L + 16L * width.ordinal();
    assertCosh(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, 10),
        Inputs.magnitudes(seed + 1, 1 << 16, -60, -1),
        Inputs.uniform(seed + 2, 1 << 17, -0.5, 0.5),
        Inputs.uniform(seed + 3, 1 << 16, 700.0, 712.0)));
  }

  /** Dense draws around the small range's end at 0.5*ln2, and around 22 on both sides. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void splitsAndLimit(VectorWidth width) {
    long seed = 6300L + 16L * width.ordinal();
    assertCosh(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.3, 0.4),
        Inputs.uniform(seed + 1, 1 << 16, -0.4, -0.3),
        Inputs.uniform(seed + 2, 1 << 16, 20.0, 24.0),
        Inputs.uniform(seed + 3, 1 << 16, -24.0, -20.0)));
  }

  /** The published hard-to-round inputs, and their negations: see {@link Inputs#hardCases}. */
  private static double[] hardCasesBothSigns() {
    double[] xs = Inputs.hardCases("cosh");
    double[] negated = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      negated[i] = -xs[i];
    }
    return Inputs.concat(xs, negated);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertCosh(width.species(), hardCasesBothSigns());
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(6400L, 4096),
        Inputs.uniform(6401L, 4096, -30.0, 30.0), Inputs.magnitudes(6402L, 4096, -60, 10),
        hardCasesBothSigns());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertCoshMasked(width.species(), maskedInputs(), 6403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * cosh's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(6500L, 1 << 14),
        Inputs.uniform(6501L, 1 << 14, -30.0, 30.0), Inputs.magnitudes(6502L, 1 << 14, -60, 10),
        hardCasesBothSigns());
    assertCosh(DoubleVector.SPECIES_MAX, xs);
    assertCoshMasked(DoubleVector.SPECIES_MAX, xs, 6503L);
  }

  /**
   * One vector for every subset of a vector's lanes rare (from the overflow window up, as both
   * arms finish them), the subsets as {@link ExpTest#rareLaneSubsets} lists them (every subset up
   * to 16 lanes, sampled above): exactly one rare lane at each position, every lane rare, and
   * every mix between. The rare inputs cycle through the overflow window of both signs,
   * overflow, the infinities and NaNs of both signs. The common lanes run from -2.2 to 7.5, with
   * small-range lanes among them, both zeros and tiny ones (which only the finish arm finishes).
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      710.0, -710.2, 710.4758600739439, -710.4758600739440, 800.0, -1.0e300, Double.NaN,
      Double.longBitsToDouble(0xfff8_0000_0000_0001L), Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double[] common = {0.0, -0.0, 0x1p-40, -0x1p-30, 0.2, -0.3};
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length]
            : (s + lane) % 3 == 0 ? common[(s / 3 + lane) % common.length] : 0.67 * lane - 2.2;
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
    assertCosh(species, xs);
    assertCoshMasked(species, xs, 6600L);
  }

  /** The scalar transcription of e_cosh.c is {@code StrictMath.cosh} on the tests' inputs. */
  @Test
  void scalarTranscriptionIsStrictMath() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(6700L, 1 << 16),
        Inputs.uniform(6701L, 1 << 16, -30.0, 30.0), Inputs.magnitudes(6702L, 1 << 16, -60, 10),
        Inputs.uniform(6703L, 1 << 16, -0.5, 0.5), hardCasesBothSigns());
    for (double x : xs) {
      assertEquals(Double.doubleToRawLongBits(StrictMath.cosh(x)),
          Double.doubleToRawLongBits(cCosh(x)), Oracle.hex(x));
    }
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.cosh(x, null));
  }

  /** The words are e_cosh.c's, and the constants are its {@code one} and {@code half}. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Cosh.ONE);
    assertBits(0x3fe00000_00000000L, Cosh.HALF);
    // e_cosh.c's literal words: lines 62, 65, 70 and 76.
    assertEquals(0x3fd62e43, Cosh.HX_SMALL);
    assertEquals(0x3c800000, Cosh.HX_TINY);
    assertEquals(0x40360000, Cosh.HX_LIMIT);
    assertEquals(0x40862E42, Cosh.HX_FILTER);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // CoshKindsBench
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code CoshKindsBench}'s inputs are what its Javadoc says: at each share, {@code none} and
   * {@code small} differ at exactly {@code round(1024*percent/100)} places, the small ones are
   * in {@code (-0.34, 0.34)} and every other input has a magnitude in {@code [0.4, 20]}, the
   * counts of vectors holding a small lane are the ones quoted, every row returns {@code
   * StrictMath.cosh}'s bits, and a value it does not know is refused.
   */
  @Test
  void kindsBenchInputsAreSmallAtTheirPlaces() {
    int[][] counts = {{36, 35, 34}, {174, 134, 105}};
    double[] percents = {3.5, 17.0};
    for (int p = 0; p < percents.length; p++) {
      double[] none = kindsBench("none", percents[p]).inputs();
      double[] small = kindsBench("small", percents[p]).inputs();
      boolean[] place4 = new boolean[none.length / 4];
      boolean[] place8 = new boolean[none.length / 8];
      int places = 0;
      for (int i = 0; i < none.length; i++) {
        assertEquals(true, Math.abs(none[i]) >= 0.4 && Math.abs(none[i]) <= 20.0, "none " + i);
        if (Double.doubleToRawLongBits(none[i]) != Double.doubleToRawLongBits(small[i])) {
          assertEquals(true, Math.abs(small[i]) < 0.34, "small at " + i);
          places++;
          place4[i / 4] = true;
          place8[i / 8] = true;
        }
      }
      assertEquals(counts[p][0], places);
      assertEquals(counts[p][1], countTrue(place4));
      assertEquals(counts[p][2], countTrue(place8));
      assertKindsRows(kindsBench("none", percents[p]));
      assertKindsRows(kindsBench("small", percents[p]));
    }
    CoshKindsBench bad = new CoshKindsBench();
    bad.inputs = "common";
    bad.percent = 3.5;
    assertThrows(IllegalArgumentException.class, bad::setUp);
    bad.inputs = "small";
    bad.percent = 0.0;
    assertThrows(IllegalArgumentException.class, bad::setUp);
    bad.percent = 100.0;
    assertThrows(IllegalArgumentException.class, bad::setUp);
  }

  private static int countTrue(boolean[] flags) {
    int count = 0;
    for (boolean flag : flags) {
      count += flag ? 1 : 0;
    }
    return count;
  }

  private static CoshKindsBench kindsBench(String inputs, double percent) {
    CoshKindsBench bench = new CoshKindsBench();
    bench.inputs = inputs;
    bench.percent = percent;
    bench.setUp();
    return bench;
  }

  private static void assertKindsRows(CoshKindsBench bench) {
    double[] strict = bench.scalarStrictMath().clone();
    double[][] rows = {
      bench.slice256().clone(), bench.slicePreferred().clone(),
      bench.finish256().clone(), bench.finishPreferred().clone()
    };
    for (double[] row : rows) {
      for (int i = 0; i < strict.length; i++) {
        assertBits(Double.doubleToRawLongBits(strict[i]), row[i]);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of cosh's faults, with a check that must fail while it is planted: the vector forms
   * over {@link #faultRunInputs()} at every width the host has. {@code splitMoved} is read by
   * the slice arm only, as is {@code dropSmallTerm} (the finish arm never runs the small range),
   * {@code finishMissesSmall} by the finish arm only; the other two reach every arm. The
   * catchers name {@code Cosh.coshSlice} and {@code Cosh.coshFinish}, not {@code VFdlibm.cosh},
   * so they reach the code that reads the fault whichever arm {@code VFdlibm.cosh} runs; the
   * ordinary tests cover {@code VFdlibm.cosh} itself as an arm. Each was sized on
   * 2^22 draws before it was wired (see the fields of {@link Cosh}).
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    catchers.put(Cosh.SPLIT_MOVED, caughtBy("Cosh.coshSlice", Cosh.SPLIT_MOVED));
    catchers.put(Cosh.DROP_SMALL_TERM, caughtBy("Cosh.coshSlice", Cosh.DROP_SMALL_TERM));
    catchers.put(Cosh.FINISH_MISSES_SMALL,
        caughtBy("Cosh.coshFinish", Cosh.FINISH_MISSES_SMALL));
    for (String fault : new String[] {Cosh.HALF_ONE_ULP_OFF, Cosh.DROP_HALF_OVER_T}) {
      catchers.put(fault, () -> {
        for (String arm : ARMS.keySet()) {
          caughtBy(arm, fault).execute();
        }
      });
    }
    return catchers;
  }

  /** A check that the named arm fails over the fault run's inputs at every width. */
  private static Executable caughtBy(String name, String fault) {
    return () -> forEachWidth(species -> PlantedFaultTest.assertCaught(fault,
        () -> Oracle.assertUnary(name, species, x -> ARMS.get(name).apply(x, null),
            StrictMath::cosh, faultRunInputs())));
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(6800L, 1 << 16, -1.0, 1.0),
        Inputs.uniform(6801L, 1 << 16, -30.0, 30.0));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertCosh(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
