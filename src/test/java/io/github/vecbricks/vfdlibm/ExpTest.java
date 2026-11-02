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
import java.util.stream.LongStream;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * exp against {@code StrictMath.exp}, by raw bits, at every width: the public {@link VFdlibm#exp}
 * (rare lanes finished per lane) and the all-blended arm {@link Exp#expBlended}, unmasked and
 * masked, on e_exp.c's thresholds, the special values, sampled bit patterns over the whole domain,
 * dense draws over the finite-result range and the published hard-to-round cases.
 */
class ExpTest {

  private static final double LN2 = 6.93147180559945309417e-01;
  /** -1021.5 ln2, near where k turns from -1021 to -1022 and the subnormal scale-back begins. */
  private static final double K_BELOW_NORMAL_EDGE = -1021.5 * LN2;
  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** Both arms, unmasked, at {@code species}, over {@code xs}. */
  private static void assertExp(VectorSpecies<Double> species, double[] xs) {
    Oracle.assertUnary("VFdlibm.exp", species, VFdlibm::exp, StrictMath::exp, xs);
    Oracle.assertUnary(
        "Exp.expBlended", species, x -> Exp.expBlended(x, null), StrictMath::exp, xs);
  }

  /** Every threshold e_exp.c branches on, with its 64 neighbours each way, and the specials. */
  static double[] thresholds() {
    double[] xs = Inputs.specials(Exp.O_THRESHOLD, Exp.U_THRESHOLD);
    double[] edges = {
      Lanes.fromHi(Exp.HX_FILTER), Lanes.fromHi(Exp.HX_HALF_LN2 + 1),
      Lanes.fromHi(Exp.HX_HALF_LN2), Lanes.fromHi(Exp.HX_THREE_HALVES_LN2),
      Lanes.fromHi(Exp.HX_TINY), 0.5 * LN2, 1.5 * LN2, Exp.O_THRESHOLD, -Exp.U_THRESHOLD
    };
    for (double edge : edges) {
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    return Inputs.concat(xs, Inputs.around(K_BELOW_NORMAL_EDGE, 64), Inputs.around(0.0, 64));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertExp(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertExp(width.species(), Inputs.sampled(100L + width.ordinal(), SAMPLED));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheFiniteResultRange(VectorWidth width) {
    assertExp(width.species(), Inputs.uniform(200L + width.ordinal(), DENSE, -746.0, 710.0));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void smallArguments(VectorWidth width) {
    assertExp(width.species(), Inputs.concat(
        Inputs.uniform(300L + width.ordinal(), 1 << 16, -2.0, 2.0),
        Inputs.magnitudes(400L + width.ordinal(), 1 << 16, -60, 0)));
  }

  /** The published hard-to-round inputs: see {@link Inputs#hardCases} and the resource header. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedHardCases(VectorWidth width) {
    assertExp(width.species(), Inputs.hardCases("exp"));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = Inputs.concat(
        thresholds(), Inputs.sampled(500L, 4096), Inputs.uniform(501L, 4096, -746.0, 710.0),
        Inputs.hardCases("exp"));
    Oracle.assertUnaryMasked("VFdlibm.exp", species, VFdlibm::exp, StrictMath::exp, xs, 502L);
    Oracle.assertUnaryMasked(
        "Exp.expBlended", species, Exp::expBlended, StrictMath::exp, xs, 503L);
  }

  /** Up to this many lanes {@link #rareLaneSubsets} lists every subset, 2^16 vectors at most. */
  static final int ALL_SUBSETS_UP_TO_LANES = 16;

  /**
   * The subsets of a vector's lanes that {@link #everySubsetOfLanesRare(int)} makes rare, as bit
   * masks: every subset up to {@link #ALL_SUBSETS_UP_TO_LANES} lanes. Above it (a SPECIES_MAX of
   * 2048 bits has 32 double lanes, where {@code 1 << lanes} overflows an int) the empty and the
   * full set, each single lane, each lane alone left common, each adjacent pair, and 4096 seeded
   * random subsets.
   */
  static long[] rareLaneSubsets(int lanes) {
    if (lanes <= ALL_SUBSETS_UP_TO_LANES) {
      long[] subsets = new long[1 << lanes];
      for (int subset = 0; subset < subsets.length; subset++) {
        subsets[subset] = subset;
      }
      return subsets;
    }
    long all = lanes == Long.SIZE ? -1L : (1L << lanes) - 1;
    LongStream.Builder subsets = LongStream.builder();
    subsets.add(0L).add(all);
    for (int lane = 0; lane < lanes; lane++) {
      subsets.add(1L << lane).add(all & ~(1L << lane));
      if (lane + 1 < lanes) {
        subsets.add(3L << lane);
      }
    }
    SplittableRandom random = new SplittableRandom(505L);
    for (int i = 0; i < 4096; i++) {
      subsets.add(random.nextLong() & all);
    }
    return subsets.build().toArray();
  }

  /**
   * One vector for every subset of a vector's lanes ({@link #rareLaneSubsets}), the subset's lanes
   * rare and the others common: exactly one rare lane at each position, every lane rare, and every
   * mix between. The per-lane finish walks the rare mask's set bits, so a lane it skipped or
   * visited twice shows here. The rare inputs cycle through each kind the finish handles: NaN, the
   * infinities, overflow, underflow and the subnormal scale-back.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      Double.NaN, 800.0, -750.0, -708.5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
      K_BELOW_NORMAL_EDGE
    };
    long[] subsets = rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    int next = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        xs[s * lanes + lane] = isRare ? rare[next++ % rare.length] : 0.37 * lane - 1.0;
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

  /** The cap's other branch, which no host's double lane count reaches yet. */
  @Test
  void rareLaneSubsetsAboveTheCap() {
    for (int lanes : new int[] {ALL_SUBSETS_UP_TO_LANES + 1, 32, Long.SIZE}) {
      long all = lanes == Long.SIZE ? -1L : (1L << lanes) - 1;
      long[] subsets = rareLaneSubsets(lanes);
      assertEquals(2 + 3 * lanes - 1 + 4096, subsets.length);
      assertEquals(0L, subsets[0]);
      assertEquals(all, subsets[1]);
      for (long subset : subsets) {
        assertEquals(0L, subset & ~all);
      }
    }
  }

  private static void assertEverySubsetOfLanesRare(VectorSpecies<Double> species) {
    double[] xs = everySubsetOfLanesRare(species.length());
    assertExp(species, xs);
    Oracle.assertUnaryMasked("VFdlibm.exp", species, VFdlibm::exp, StrictMath::exp, xs, 504L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * exp's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(600L, 1 << 14),
        Inputs.uniform(601L, 1 << 14, -746.0, 710.0));
    assertExp(DoubleVector.SPECIES_MAX, xs);
    Oracle.assertUnaryMasked(
        "VFdlibm.exp", DoubleVector.SPECIES_MAX, VFdlibm::exp, StrictMath::exp, xs, 602L);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.exp(x, null));
  }

  /** The constants parse to the hex e_exp.c gives for each. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x01700000_00000000L, Exp.TWOM1000);
    assertBits(0x40862E42_FEFA39EFL, Exp.O_THRESHOLD);
    assertBits(0xc0874910_D52D3051L, Exp.U_THRESHOLD);
    assertBits(0x3fe62e42_fee00000L, Exp.LN2HI_0);
    assertBits(0xbfe62e42_fee00000L, Exp.LN2HI_1);
    assertBits(0x3dea39ef_35793c76L, Exp.LN2LO_0);
    assertBits(0xbdea39ef_35793c76L, Exp.LN2LO_1);
    assertBits(0x3ff71547_652b82feL, Exp.INVLN2);
    assertBits(0x3FC55555_5555553EL, Exp.P1);
    assertBits(0xBF66C16C_16BEBD93L, Exp.P2);
    assertBits(0x3F11566A_AF25DE2CL, Exp.P3);
    assertBits(0xBEBBBD41_C5D26BF1L, Exp.P4);
    assertBits(0x3E663769_72BEA4D0L, Exp.P5);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults

  /**
   * exp's faults, each with the check that must catch it: both arms, at each width the host has,
   * over the thresholds and dense draws from the finite-result range. The same inputs pass in the
   * normal run, through {@link #faultRunInputsPass}.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Exp.DROP_CORRECTION, Exp.CONSTANT_OFF_BY_ONE_ULP, Exp.THRESHOLD_MOVED}) {
      catchers.put(fault, () -> forEachWidth(species -> {
        PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
            "VFdlibm.exp", species, VFdlibm::exp, StrictMath::exp, faultRunInputs()));
        PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
            "Exp.expBlended", species, x -> Exp.expBlended(x, null), StrictMath::exp,
            faultRunInputs()));
      }));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(thresholds(), Inputs.uniform(600L, 1 << 16, -746.0, 710.0));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertExp(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
