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
 * atan2 against {@code StrictMath.atan2}, by {@link PairBits#matches} (raw bits, except a lane of
 * two NaNs with different bits), at every width and at {@code SPECIES_MAX}: {@link
 * VFdlibm#atan2}, {@link Atan2#atan2Blended} and {@link Atan2#atan2Guarded}, unmasked and masked,
 * on every pair of special values, on e_atan2.c's tests of {@code k = (iy-ix)>>20} and of {@code
 * x = 1.0}, on sampled bit patterns, dense draws over each quadrant, every subset of a vector's
 * lanes special, and the pairs of {@code hard-cases/atan2.txt}. The first argument is {@code y}:
 * {@code Inputs.Pairs.x()} is the first column, the {@code y} of {@code atan2(y, x)}, and {@code
 * Inputs.Pairs.y()} the second, its {@code x}, everywhere below.
 */
class Atan2Test {

  private static final PairBits.Candidates OPERANDS = PairBits.Candidates.OPERANDS;
  private static final int SAMPLED = 1 << 19;

  /** The arm as a masked form of two arguments; a null mask computes every lane. */
  interface Arm {
    DoubleVector apply(DoubleVector y, DoubleVector x, VectorMask<Double> live);
  }

  private static final Map<String, Arm> ARMS = arms();

  private static Map<String, Arm> arms() {
    Map<String, Arm> arms = new LinkedHashMap<>();
    arms.put("VFdlibm.atan2", (y, x, live) ->
        live == null ? VFdlibm.atan2(y, x) : VFdlibm.atan2(y, x, live));
    arms.put("Atan2.atan2Blended", Atan2::atan2Blended);
    arms.put("Atan2.atan2Guarded", Atan2::atan2Guarded);
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over the pairs {@code p}, {@code y} first. */
  private static void assertAtan2(VectorSpecies<Double> species, Inputs.Pairs p) {
    ARMS.forEach((name, arm) -> Oracle.assertPair(name, species, OPERANDS,
        (y, x) -> arm.apply(y, x, null), StrictMath::atan2, p.x(), p.y()));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertAtan2Masked(
      VectorSpecies<Double> species, Inputs.Pairs p, long seed) {
    for (Map.Entry<String, Arm> arm : ARMS.entrySet()) {
      Oracle.assertPairMasked(arm.getKey(), species, OPERANDS, arm.getValue()::apply,
          StrictMath::atan2, p.x(), p.y(), seed++);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The C in scalar Java
  // ---------------------------------------------------------------------------------------------

  /** e_atan2.c in scalar Java, line by line, with atan the transcription of s_atan.c. */
  static double cAtan2(double y, double x) {
    return cAtan2(y, x, Atan2.K_MAX, Atan2.K_MIN);
  }

  /** {@link #cAtan2(double, double)} with the two thresholds of {@code k} given, to move them. */
  static double cAtan2(double y, double x, int kMax, int kMin) {
    long xb = Double.doubleToRawLongBits(x);
    long yb = Double.doubleToRawLongBits(y);
    int hx = (int) (xb >>> 32);
    int lx = (int) xb;
    int ix = hx & 0x7fffffff;
    int hy = (int) (yb >>> 32);
    int ly = (int) yb;
    int iy = hy & 0x7fffffff;
    // Lines 71-73: a NaN in either argument.
    if (((ix | ((lx | -lx) >>> 31)) > 0x7ff00000) || ((iy | ((ly | -ly) >>> 31)) > 0x7ff00000)) {
      return x + y;
    }
    if (((hx - 0x3ff00000) | lx) == 0) {
      return AtanTest.cAtan(y);
    }
    int m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
    if ((iy | ly) == 0) {
      return switch (m) {
        case 0, 1 -> y;
        case 2 -> Atan2.PI + Atan2.TINY;
        default -> -Atan2.PI - Atan2.TINY;
      };
    }
    if ((ix | lx) == 0) {
      return hy < 0 ? -Atan2.PI_O_2 - Atan2.TINY : Atan2.PI_O_2 + Atan2.TINY;
    }
    if (ix == 0x7ff00000) {
      if (iy == 0x7ff00000) {
        return switch (m) {
          case 0 -> Atan2.PI_O_4 + Atan2.TINY;
          case 1 -> -Atan2.PI_O_4 - Atan2.TINY;
          case 2 -> 3.0 * Atan2.PI_O_4 + Atan2.TINY;
          default -> -3.0 * Atan2.PI_O_4 - Atan2.TINY;
        };
      }
      return switch (m) {
        case 0 -> Atan2.ZERO;
        case 1 -> -Atan2.ZERO;
        case 2 -> Atan2.PI + Atan2.TINY;
        default -> -Atan2.PI - Atan2.TINY;
      };
    }
    if (iy == 0x7ff00000) {
      return hy < 0 ? -Atan2.PI_O_2 - Atan2.TINY : Atan2.PI_O_2 + Atan2.TINY;
    }
    int k = (iy - ix) >> 20;
    double z;
    if (k > kMax) {
      z = Atan2.PI_O_2 + 0.5 * Atan2.PI_LO;
    } else if (hx < 0 && k < kMin) {
      z = 0.0;
    } else {
      z = AtanTest.cAtan(Math.abs(y / x));
    }
    return switch (m) {
      case 0 -> z;
      case 1 -> Double.longBitsToDouble(Double.doubleToRawLongBits(z) ^ Long.MIN_VALUE);
      case 2 -> Atan2.PI - (z - Atan2.PI_LO);
      default -> (z - Atan2.PI_LO) - Atan2.PI;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Inputs
  // ---------------------------------------------------------------------------------------------

  static Inputs.Pairs concatPairs(Inputs.Pairs... parts) {
    double[][] ys = new double[parts.length][];
    double[][] xs = new double[parts.length][];
    for (int i = 0; i < parts.length; i++) {
      ys[i] = parts[i].x();
      xs[i] = parts[i].y();
    }
    return new Inputs.Pairs(Inputs.concat(ys), Inputs.concat(xs));
  }

  /** Every pair of the special values, the thresholds of k's exponents and 2^66 among them. */
  static Inputs.Pairs specialPairs() {
    double[] specials = Inputs.specials(0x1p60, 0x1p61, -0x1p60, 0x1p66, 2.0, -2.0, 0.5);
    return Inputs.cross(specials, specials);
  }

  /**
   * Pairs whose exponents differ by -70 to 70, both signs, so that {@code k} runs across both of
   * its thresholds and the lanes either side of each: {@code y}'s exponent is {@code x}'s plus
   * the difference, the mantissas random.
   */
  static Inputs.Pairs kPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] ys = new double[n];
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      int xe = random.nextInt(-900, 901);
      int ye = xe + random.nextInt(-70, 71);
      xs[i] = Math.scalb(1.0 + random.nextDouble(), xe) * (random.nextBoolean() ? 1 : -1);
      ys[i] = Math.scalb(1.0 + random.nextDouble(), ye) * (random.nextBoolean() ? 1 : -1);
    }
    return new Inputs.Pairs(ys, xs);
  }

  /**
   * The pairs the fuzz draws at the differences of high words that e_atan2.c's {@code k} tests,
   * from the nightly's own edges ({@link Nightly.PairFunction#ATAN2}), a share of them at
   * {@code iy - ix = 61 * 2^20} and the word below, {@code -60 * 2^20} and the word below.
   */
  static Inputs.Pairs gapPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] pair = new double[2];
    double[] ys = new double[n];
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      Fuzz.drawPair(random, Nightly.PairFunction.ATAN2.edges(), pair);
      ys[i] = pair[0];
      xs[i] = pair[1];
    }
    return new Inputs.Pairs(ys, xs);
  }

  /**
   * {@code x = +-1.0}, the lane the C answers with {@code atan(y)}, beside {@code y} from {@code
   * 2^50} up past {@code 2^66}, both signs, and the neighbours of 1.0.
   */
  static Inputs.Pairs onePairs(long seed) {
    double[] xs = {1.0, -1.0, Math.nextUp(1.0), Math.nextDown(1.0)};
    double[] ys = Inputs.concat(
        Inputs.magnitudes(seed, 1 << 12, 50, 70), Inputs.magnitudes(seed + 1, 1 << 10, -70, 0),
        Inputs.specials(0x1p60, 0x1p61, 0x1p66));
    return Inputs.cross(ys, xs);
  }

  /**
   * A subnormal {@code x} beside a {@code y} of moderate exponent: {@code k} then understates the
   * gap of the exponents (the subnormal's high word has no exponent), so {@code t = |y/x|} can be
   * {@code 2^66} or more with no shortcut taken, and the lane takes atan's constant.
   */
  static Inputs.Pairs subnormalPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] ys = new double[n];
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      long mantissa = random.nextLong() & 0x000fffffffffffffL;
      xs[i] = Double.longBitsToDouble(mantissa | (random.nextBoolean() ? Long.MIN_VALUE : 0L));
      ys[i] = Math.scalb(1.0 + random.nextDouble(), random.nextInt(-1022, 10))
          * (random.nextBoolean() ? 1 : -1);
    }
    return new Inputs.Pairs(ys, xs);
  }

  static Inputs.Pairs sampledPairs(long seed, int n) {
    return new Inputs.Pairs(Inputs.sampled(seed, n), Inputs.sampled(seed + 1, n));
  }

  /** Dense draws over both quadrants' worth of signs, and over the unit square. */
  static Inputs.Pairs densePairs(long seed) {
    return concatPairs(
        new Inputs.Pairs(Inputs.uniform(seed, 1 << 16, -6.0, 6.0),
            Inputs.uniform(seed + 1, 1 << 16, -6.0, 6.0)),
        new Inputs.Pairs(Inputs.uniform(seed + 2, 1 << 15, -1.0, 1.0),
            Inputs.uniform(seed + 3, 1 << 15, -1.0, 1.0)),
        new Inputs.Pairs(Inputs.magnitudes(seed + 4, 1 << 15, -60, 60),
            Inputs.magnitudes(seed + 5, 1 << 15, -60, 60)));
  }

  private static Inputs.Pairs hardCasePairs() {
    return Inputs.hardCasePairs("atan2");
  }

  private static Inputs.Pairs everythingSmall() {
    return concatPairs(specialPairs(), kPairs(8100L, 1 << 14), gapPairs(8101L, 1 << 14),
        onePairs(8102L), subnormalPairs(8105L, 1 << 14), sampledPairs(8103L, 1 << 12),
        densePairs(8104L), hardCasePairs());
  }

  // ---------------------------------------------------------------------------------------------
  // The tests
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void specialValues(VectorWidth width) {
    assertAtan2(width.species(), specialPairs());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsOfK(VectorWidth width) {
    assertAtan2(width.species(), concatPairs(kPairs(8200L + width.ordinal(), SAMPLED >> 2),
        gapPairs(8210L + width.ordinal(), SAMPLED >> 2)));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void xIsOne(VectorWidth width) {
    assertAtan2(width.species(), onePairs(8300L + width.ordinal()));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void subnormalX(VectorWidth width) {
    assertAtan2(width.species(), subnormalPairs(8350L + width.ordinal(), SAMPLED >> 2));
  }

  /**
   * L14's hinge: {@code atan2(y, 1.0)} is {@code atan(y)}, by the oracle's bits and by the port's
   * own, at every width, on atan's thresholds, sampled values and the hard cases of atan.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void xOneIsAtanOfY(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] ys = Inputs.concat(AtanTest.thresholds(), Inputs.sampled(8360L, 1 << 16),
        Inputs.magnitudes(8361L, 1 << 14, -70, 70), Inputs.uniform(8362L, 1 << 14, -6.0, 6.0));
    for (int i = 0; i + species.length() <= ys.length; i += species.length()) {
      DoubleVector y = DoubleVector.fromArray(species, ys, i);
      double[] got = VFdlibm.atan2(y, DoubleVector.broadcast(species, 1.0)).toArray();
      double[] atan = VFdlibm.atan(y).toArray();
      for (int lane = 0; lane < got.length; lane++) {
        double v = ys[i + lane];
        if (!Double.isNaN(v)) {
          assertEquals(Double.doubleToRawLongBits(StrictMath.atan(v)),
              Double.doubleToRawLongBits(got[lane]), "atan2(y, 1.0) of " + Oracle.hex(v));
          assertEquals(Double.doubleToRawLongBits(atan[lane]),
              Double.doubleToRawLongBits(got[lane]), "the port's atan of " + Oracle.hex(v));
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertAtan2(width.species(), sampledPairs(8400L + 2L * width.ordinal(), SAMPLED));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheQuadrants(VectorWidth width) {
    assertAtan2(width.species(), densePairs(8500L + 8L * width.ordinal()));
  }

  /**
   * Pairs of one high word with both low words random ({@link Inputs#sharedWordPairs}), which
   * found hypot's order of the arguments (PROOFS.md, L21), at the words of 0, 1 and the
   * infinities among others.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sharedHighWords(VectorWidth width) {
    assertAtan2(width.species(), Inputs.sharedWordPairs(8560L + width.ordinal(), 1 << 17,
        0x00000000, 0x3ff00000, 0x44100000, 0x7fefffff));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedPairs(VectorWidth width) {
    assertAtan2(width.species(), hardCasePairs());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertAtan2Masked(width.species(), everythingSmall(), 8600L);
  }

  /** SPECIES_MAX, which {@link VectorWidth} does not run: its int species is another shape. */
  @Test
  void maxSpecies() {
    Inputs.Pairs p = everythingSmall();
    assertAtan2(DoubleVector.SPECIES_MAX, p);
    assertAtan2Masked(DoubleVector.SPECIES_MAX, p, 8700L);
  }

  /** Pairs, one per kind of special lane, that the subsets below put in a vector's lanes. */
  private static final double[][] SPECIAL_LANES = {
    {Double.NaN, 1.0}, {1.0, Double.NaN}, {Double.NaN, Double.NaN},
    {Double.longBitsToDouble(0xfff4_0000_0000_0456L),
      Double.longBitsToDouble(0x7ff8_0000_0000_0123L)},
    {0.0, 1.0}, {-0.0, -2.0}, {0.0, 0.0}, {-0.0, -0.0}, {3.0, 0.0}, {-3.0, -0.0},
    {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
    {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
    {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
    {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY},
    {5.0, Double.POSITIVE_INFINITY}, {-5.0, Double.NEGATIVE_INFINITY},
    {Double.POSITIVE_INFINITY, 7.0}, {Double.NEGATIVE_INFINITY, -7.0},
  };

  /** Lanes that take no special path: both quadrant signs, the shortcuts, {@code x = 1.0}. */
  private static final double[][] GENERAL_LANES = {
    {1.0, 2.0}, {-1.0, 2.0}, {1.0, -2.0}, {-1.0, -2.0}, {0x1p100, 3.0}, {-0x1p100, -3.0},
    {3.0, 0x1p100}, {-3.0, -0x1p100}, {0x1p70, 1.0}, {-0x1p70, 1.0}, {0x1p-80, 1.0},
  };

  /**
   * One vector for every subset of a vector's lanes ({@link ExpTest#rareLaneSubsets}), the
   * subset's lanes special and the others general, so that a vector holds special lanes beside
   * lanes the core computes, in every position and mix.
   */
  static Inputs.Pairs everySubsetOfLanesSpecial(int lanes) {
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] ys = new double[subsets.length * lanes];
    double[] xs = new double[ys.length];
    int special = 0;
    int general = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isSpecial = (subsets[s] >>> lane & 1L) != 0;
        double[] pair = isSpecial ? SPECIAL_LANES[special++ % SPECIAL_LANES.length]
            : GENERAL_LANES[general++ % GENERAL_LANES.length];
        ys[s * lanes + lane] = pair[0];
        xs[s * lanes + lane] = pair[1];
      }
    }
    return new Inputs.Pairs(ys, xs);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void everySubsetOfLanesSpecial(VectorWidth width) {
    assertEverySubset(width.species());
  }

  @Test
  void everySubsetOfLanesSpecialAtMaxSpecies() {
    assertEverySubset(DoubleVector.SPECIES_MAX);
  }

  private static void assertEverySubset(VectorSpecies<Double> species) {
    Inputs.Pairs p = everySubsetOfLanesSpecial(species.length());
    assertAtan2(species, p);
    assertAtan2Masked(species, p, 8800L);
  }

  /**
   * The scalar transcription of e_atan2.c is {@code StrictMath.atan2} on the inputs the vector
   * tests use (a lane of two NaNs with different bits by {@link PairBits}), so a hinge or a fault
   * run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    Inputs.Pairs p = everythingSmall();
    for (int i = 0; i < p.x().length; i++) {
      double y = p.x()[i];
      double x = p.y()[i];
      assertTrue(PairBits.matches(OPERANDS, y, x, StrictMath.atan2(y, x), cAtan2(y, x)),
          Oracle.hex(y) + ", " + Oracle.hex(x));
    }
  }

  /**
   * Moved by one the two shortcuts of {@code k} change no result: wherever they are taken, {@code
   * pi_o_2+0.5*pi_lo} and {@code 0.0} are the bits of the C's own {@code atan(fabs(y/x))} path
   * (PROOFS.md, atan2), so no test of results sees them move by one. Moved by ten they are seen,
   * in the lanes of {@code k} 51 to 60 and -60 to -51: that is what the two faults plant.
   */
  @Test
  void theShortcutsAreSeenMovedByTenAndNotByOne() {
    Inputs.Pairs p = concatPairs(kPairs(8900L, 1 << 18), gapPairs(8901L, 1 << 18));
    long[] seen = new long[4];
    int[][] moves = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    long tenHigh = 0;
    long tenLow = 0;
    for (int i = 0; i < p.x().length; i++) {
      double y = p.x()[i];
      double x = p.y()[i];
      double want = StrictMath.atan2(y, x);
      for (int m = 0; m < 4; m++) {
        double moved = cAtan2(y, x, Atan2.K_MAX + moves[m][0], Atan2.K_MIN + moves[m][1]);
        if (!PairBits.matches(OPERANDS, y, x, want, moved)) {
          seen[m]++;
        }
      }
      tenHigh += PairBits.matches(OPERANDS, y, x, want,
          cAtan2(y, x, Atan2.K_MAX - 10, Atan2.K_MIN)) ? 0 : 1;
      tenLow += PairBits.matches(OPERANDS, y, x, want,
          cAtan2(y, x, Atan2.K_MAX, Atan2.K_MIN + 10)) ? 0 : 1;
    }
    for (int m = 0; m < 4; m++) {
      assertEquals(0L, seen[m], "a shortcut moved by one, variant " + m);
    }
    assertTrue(tenHigh > 0 && tenLow > 0, "moved by ten: " + tenHigh + ", " + tenLow);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector v = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.atan2(v, v, null));
  }

  /** The constants are e_atan2.c's, by the hexadecimal words its comments give. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3FE921FB54442D18L, Atan2.PI_O_4);
    assertBits(0x3FF921FB54442D18L, Atan2.PI_O_2);
    assertBits(0x400921FB54442D18L, Atan2.PI);
    assertBits(0x3CA1A62633145C07L, Atan2.PI_LO);
    assertEquals(1.0e-300, Atan2.TINY);
    assertEquals(60, Atan2.K_MAX);
    assertEquals(-60, Atan2.K_MIN);
    // pi+tiny and pi_o_2+tiny are the constants themselves; 3.0*pi_o_4+tiny is a double of its own
    assertBits(0x400921FB54442D18L, Atan2.PI_P);
    assertBits(0x3FF921FB54442D18L, Atan2.PI_O_2_P);
    assertBits(0x4002D97C7F3321D2L, Atan2.THREE_PI_O_4_P);
    assertBits(0x3FF921FB54442D18L, Atan2.Z_HIGH);
    // atanhi[3]+atanlo[3] (what atan returns from 2^66 up) and 2^66, s_atan.c's word 0x44100000
    assertBits(0x3FF921FB54442D18L, Atan2.ATAN_BIG);
    assertEquals(0x1p66, Atan2.ATAN_BIG_FROM);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of atan2's faults, with a check that must fail while it is planted: the vector form
   * over {@link #faultRunInputs()} at every width the host has, every arm. The two shortcuts are
   * moved by ten, as the test above says. Each was sized on draws before it was wired; the sizes
   * are in {@code SKILLS.md}, "What the atan2 port taught".
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Atan2.K_HIGH_MOVED, Atan2.K_LOW_MOVED, Atan2.LOW_SHORTCUT_FOR_POSITIVE_X,
        Atan2.INF_ORDER_SWAPPED, Atan2.DROP_PI_LO, Atan2.ZERO_SIGN_OF_X, Atan2.DIAGONAL_INF,
        Atan2.NO_SIGN_FLIP}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> {
            Inputs.Pairs p = faultRunInputs();
            Oracle.assertPair(name, species, OPERANDS, (y, x) -> arm.apply(y, x, null),
                StrictMath::atan2, p.x(), p.y());
          }))));
    }
    return catchers;
  }

  private static Inputs.Pairs faultRunInputs() {
    return concatPairs(specialPairs(), kPairs(8950L, 1 << 16), densePairs(8951L));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertAtan2(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
