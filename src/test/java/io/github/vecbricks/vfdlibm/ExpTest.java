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
import jdk.incubator.vector.VectorMask;
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
   * masks: every subset up to {@link #ALL_SUBSETS_UP_TO_LANES} lanes. The cap bounds the test's
   * size, since the count doubles with each lane (and {@code 1 << lanes} overflows an int from 31
   * lanes on). Above it, the empty and the full set, each single lane, each lane alone left
   * common, each adjacent pair, and 4096 seeded random subsets. No exp test reaches that branch:
   * the widest species exp accepts is 1024 bits, 16 double lanes, since {@link Lanes#intSpecies}
   * throws above it, so even a 2048-bit SPECIES_MAX (32 lanes) never runs exp here. {@link
   * #rareLaneSubsetsAboveTheCap} tests the branch directly, ready for a wider species.
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

  /**
   * The cap's sampled branch, which no exp test reaches: exp accepts at most 1024 bits, 16 double
   * lanes, the cap itself ({@link #rareLaneSubsets}). The expected masks are checked by their bit
   * counts and lanes, not rebuilt the way the branch builds them.
   */
  @Test
  void rareLaneSubsetsAboveTheCap() {
    for (int lanes : new int[] {ALL_SUBSETS_UP_TO_LANES + 1, 32, Long.SIZE}) {
      long[] subsets = rareLaneSubsets(lanes);
      assertEquals(2 + 3 * lanes - 1 + 4096, subsets.length);
      assertEquals(0L, subsets[0]);
      assertEquals(lanes, Long.bitCount(subsets[1]));
      assertEquals(lanes, Long.SIZE - Long.numberOfLeadingZeros(subsets[1]));
      int next = 2;
      for (int lane = 0; lane < lanes; lane++) {
        long single = subsets[next++];
        assertEquals(1, Long.bitCount(single), "single at lane " + lane);
        assertEquals(lane, Long.numberOfTrailingZeros(single), "single at lane " + lane);
        long complement = subsets[next++];
        assertEquals(lanes - 1, Long.bitCount(complement), "complement of lane " + lane);
        assertEquals(0L, complement >>> lane & 1L, "complement of lane " + lane);
        assertEquals(subsets[1], complement | single, "complement of lane " + lane);
        if (lane + 1 < lanes) {
          long pair = subsets[next++];
          assertEquals(2, Long.bitCount(pair), "pair at lane " + lane);
          assertEquals(1L, pair >>> lane & 1L, "pair at lane " + lane);
          assertEquals(1L, pair >>> (lane + 1) & 1L, "pair at lane " + lane);
        }
      }
      for (long subset : subsets) {
        assertEquals(0L, subset & ~subsets[1]);
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

  /** The high words where PROOFS.md's folds hinge: the tiny, k == 0 and near-band edges. */
  static final int[] FOLD_WORDS = {
    Exp.HX_TINY - 1, Exp.HX_TINY, Exp.HX_HALF_LN2, Exp.HX_HALF_LN2 + 1,
    Exp.HX_THREE_HALVES_LN2 - 1, Exp.HX_THREE_HALVES_LN2
  };

  /**
   * Every high word of {@link #FOLD_WORDS}, both signs, with the low words 0 and 0xffffffff and
   * 1024 seeded ones, and the doubles just above 0.5 ln2: the inputs where lemmas L1 and L2 of
   * PROOFS.md would first break.
   */
  static double[] foldBoundaries() {
    SplittableRandom random = new SplittableRandom(700L);
    double[] xs = new double[FOLD_WORDS.length * 2 * 1026];
    int next = 0;
    for (int h : FOLD_WORDS) {
      for (long sign : new long[] {0L, Long.MIN_VALUE}) {
        long high = Lanes.fromHiBits(h) | sign;
        xs[next++] = Double.longBitsToDouble(high);
        xs[next++] = Double.longBitsToDouble(high | 0xffffffffL);
        for (int i = 0; i < 1024; i++) {
          xs[next++] = Double.longBitsToDouble(high | (random.nextLong() & 0xffffffffL));
        }
      }
    }
    // The part of word 0x3fd62e42 above 0.5 ln2, where the formula alone would give k = +-1
    // and about one result in 14 would differ (PROOFS.md, L1): its first 4096 doubles.
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

  /** Both arms over {@link #foldBoundaries()} at {@code species}, unmasked and masked. */
  private static void assertFoldBoundaries(VectorSpecies<Double> species) {
    double[] xs = foldBoundaries();
    assertExp(species, xs);
    Oracle.assertUnaryMasked("VFdlibm.exp", species, VFdlibm::exp, StrictMath::exp, xs, 701L);
    Oracle.assertUnaryMasked(
        "Exp.expBlended", species, Exp::expBlended, StrictMath::exp, xs, 705L);
  }

  /**
   * {@link Exp#expCore} with {@link Exp#expRare}'s lanes finished by {@code StrictMath.exp} is
   * exp, and {@code expRare} sets exactly the lanes of e_exp.c's filter and of its subnormal
   * scale-back, computed here per lane from the C.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void coreAndRareMaskAreTheFinishArm(VectorWidth width) {
    assertCoreAndRare(width.species());
  }

  @Test
  void coreAndRareMaskAreTheFinishArmAtMaxSpecies() {
    assertCoreAndRare(DoubleVector.SPECIES_MAX);
  }

  private static void assertCoreAndRare(VectorSpecies<Double> species) {
    int n = species.length();
    double[] xs = Inputs.concat(thresholds(), foldBoundaries(), everySubsetOfLanesRare(n),
        Inputs.sampled(702L, 1 << 14), Inputs.uniform(703L, 1 << 14, -746.0, 710.0));
    for (int base = 0; base + n <= xs.length; base += n) {
      DoubleVector x = DoubleVector.fromArray(species, xs, base);
      DoubleVector core = Exp.expCore(x);
      VectorMask<Double> rare = Exp.expRare(x);
      for (int i = 0; i < n; i++) {
        double xi = xs[base + i];
        String at = Oracle.hex(xi);
        assertEquals(cRare(xi), rare.laneIsSet(i), "rare lane at " + at);
        double got = rare.laneIsSet(i) ? StrictMath.exp(xi) : core.lane(i);
        assertBits(Double.doubleToRawLongBits(StrictMath.exp(xi)), got);
      }
    }
  }

  /** e_exp.c's rare paths for one double: the filter, or a reduced lane with k < -1021. */
  private static boolean cRare(double x) {
    long hx = (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffffL;
    if (hx >= Exp.HX_FILTER) {
      return true;
    }
    if (hx <= Exp.HX_HALF_LN2) {
      return false;
    }
    int k = hx < Exp.HX_THREE_HALVES_LN2 ? (x < 0 ? -1 : 1)
        : (int) (Exp.INVLN2 * x + (x < 0 ? -0.5 : 0.5));
    return k < Exp.K_MIN_NORMAL;
  }

  /**
   * The {@code finishPairBoxed} rows of {@code ExpBenchmark} return {@link VFdlibm#exp}'s bits,
   * on the benchmark's own common and mixed inputs and on vectors of every rare-lane subset.
   */
  @Test
  void finishPairRowsMatchVFdlibmExp() {
    for (String inputs : new String[] {"common", "mixed"}) {
      ExpBenchmark bench = new ExpBenchmark();
      bench.inputs = inputs;
      bench.setUp();
      assertPairRows(bench);
    }
    int lanes = DoubleVector.SPECIES_PREFERRED.length();
    double[] subsets = everySubsetOfLanesRare(lanes);
    double[] xs = new double[ExpBenchmark.N];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = subsets[i % subsets.length];
    }
    ExpBenchmark bench = new ExpBenchmark();
    bench.setInputs(xs);
    assertPairRows(bench);
  }

  private static void assertPairRows(ExpBenchmark bench) {
    double[] want = bench.finish256().clone();
    double[] strict = bench.scalarStrictMath().clone();
    double[] pair256 = bench.finishPairBoxed256().clone();
    double[] pairPreferred = bench.finishPairBoxedPreferred().clone();
    for (int i = 0; i < want.length; i++) {
      assertBits(Double.doubleToRawLongBits(strict[i]), want[i]);
      assertBits(Double.doubleToRawLongBits(want[i]), pair256[i]);
      assertBits(Double.doubleToRawLongBits(want[i]), pairPreferred[i]);
    }
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

  /**
   * Exp's int constants are e_exp.c's literals: the high words |x| is compared with (lines 117,
   * 118, 128, 129 and 139) and the k of line 149. PROOFS.md's L3 renders the C's side of its
   * proof at each high word from these constants, so this test is what ties that side to the C.
   */
  @Test
  void intWordsAreTheSources() {
    assertEquals(0x40862E42, Exp.HX_FILTER);
    assertEquals(0x7ff00000, Exp.HX_NON_FINITE);
    assertEquals(0x3fd62e42, Exp.HX_HALF_LN2);
    assertEquals(0x3FF0A2B2, Exp.HX_THREE_HALVES_LN2);
    assertEquals(0x3e300000, Exp.HX_TINY);
    assertEquals(-1021, Exp.K_MIN_NORMAL);
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
