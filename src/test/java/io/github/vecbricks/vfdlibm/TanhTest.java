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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
 * tanh against {@code StrictMath.tanh}, by raw bits, at every width and at {@code SPECIES_MAX}:
 * {@link VFdlibm#tanh}, unmasked and masked, on s_tanh.c's high-word tests, the special values,
 * subnormals, sampled bit patterns over the whole domain, dense draws over each range the C
 * treats differently, both signs, and every subset of a vector's lanes rare; and the hinge of
 * PROOFS.md's tanh section, that no lane whose {@code expm1} is kept is one of {@link
 * Expm1#expm1Rare}'s. No published hard cases: no file under {@code hard-cases/} is tanh's.
 */
class TanhTest {

  private static final int SAMPLED = 1 << 20;
  private static final int DENSE = 1 << 20;

  /** The arm, by name, as a masked form; a null mask computes every lane. */
  private static final Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>>
      ARMS = arms();

  private static Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms() {
    Map<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arms =
        new LinkedHashMap<>();
    arms.put(
        "VFdlibm.tanh", (x, live) -> live == null ? VFdlibm.tanh(x) : VFdlibm.tanh(x, live));
    return arms;
  }

  /** Every arm, unmasked, at {@code species}, over {@code xs}. */
  private static void assertTanh(VectorSpecies<Double> species, double[] xs) {
    ARMS.forEach((name, arm) -> Oracle.assertUnary(
        name, species, x -> arm.apply(x, null), StrictMath::tanh, xs));
  }

  /** Every arm, masked under seeded random masks, all-true and all-false, from {@code seed}. */
  private static void assertTanhMasked(VectorSpecies<Double> species, double[] xs, long seed) {
    for (Map.Entry<String, BiFunction<DoubleVector, VectorMask<Double>, DoubleVector>> arm :
        ARMS.entrySet()) {
      Oracle.assertUnaryMasked(
          arm.getKey(), species, arm.getValue(), StrictMath::tanh, xs, seed++);
    }
  }

  /**
   * s_tanh.c in scalar Java, line by line, its {@code expm1} being {@code StrictMath.expm1}. It
   * matches {@code StrictMath.tanh} on every input the tests give it, so a hinge or a fault run
   * that uses it shows the C's result, not a guess.
   */
  static double cTanh(double x) {
    int jx = (int) (Double.doubleToRawLongBits(x) >>> 32);
    int ix = jx & 0x7fffffff;
    if (ix >= Tanh.HX_NON_FINITE) {
      return jx >= 0 ? Tanh.ONE / x + Tanh.ONE : Tanh.ONE / x - Tanh.ONE;
    }
    double z;
    if (ix < Tanh.HX_LIMIT) {
      if (ix < Tanh.HX_TINY) {
        return x * (Tanh.ONE + x);
      }
      if (ix >= Tanh.HX_ONE) {
        double t = StrictMath.expm1(Tanh.TWO * Math.abs(x));
        z = Tanh.ONE - Tanh.TWO / (t + Tanh.TWO);
      } else {
        double t = StrictMath.expm1(-Tanh.TWO * Math.abs(x));
        z = -t / (t + Tanh.TWO);
      }
    } else {
      z = Tanh.ONE - Tanh.TINY;
    }
    return jx >= 0 ? z : -z;
  }

  /**
   * Every threshold s_tanh.c branches on, with its neighbours, both signs: the high words of its
   * tiny test, its {@code |x| >= 1} split, its 22 and the non-finite words, 64 ulps each way; the
   * words of {@code |x|} at which expm1's argument {@code +-2|x|} crosses expm1's own words (its
   * tiny test, its {@code 0.5 ln2} and {@code 1.5 ln2}); the double from which the second form
   * rounds to 1; and the special values.
   */
  static double[] thresholds() {
    double[] xs = Inputs.specials(22.0, -22.0, 1.0, -1.0, 0x1p-55, -0x1p-55, 2.0, -2.0);
    // Doubling adds 0x00100000 to a normal double's high word, so these are |x|'s words for
    // expm1's 2^-54 (which is HX_TINY's), 0.5 ln2 and 1.5 ln2.
    int[] words = {
      Tanh.HX_TINY, Tanh.HX_ONE, Tanh.HX_LIMIT, Tanh.HX_NON_FINITE,
      Expm1.HX_TINY - 0x00100000, Expm1.HX_HALF_LN2 - 0x00100000,
      Expm1.HX_HALF_LN2 + 1 - 0x00100000, Expm1.HX_THREE_HALVES_LN2 - 0x00100000
    };
    for (int word : words) {
      double edge = Lanes.fromHi(word);
      xs = Inputs.concat(xs, Inputs.around(edge, 64), Inputs.around(-edge, 64));
    }
    double one = firstSaturated();
    return Inputs.concat(xs, Inputs.around(one, 64), Inputs.around(-one, 64));
  }

  /**
   * The least positive double whose tanh is 1: the second form, {@code one - two/(t+two)}, rounds
   * to 1 from there on, below line 67's 22. Found by bisection on the C, which is monotone there.
   */
  static double firstSaturated() {
    long lo = Double.doubleToRawLongBits(1.0);
    long hi = Double.doubleToRawLongBits(22.0);
    while (hi - lo > 1) {
      long mid = (lo + hi) >>> 1;
      if (cTanh(Double.longBitsToDouble(mid)) == 1.0) {
        hi = mid;
      } else {
        lo = mid;
      }
    }
    return Double.longBitsToDouble(hi);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void thresholdsAndSpecials(VectorWidth width) {
    assertTanh(width.species(), thresholds());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledBitPatterns(VectorWidth width) {
    assertTanh(width.species(), Inputs.sampled(9000L + width.ordinal(), SAMPLED));
  }

  /** Uniform over (-30, 30): both forms, the saturated range, both signs. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void denseOverTheRanges(VectorWidth width) {
    assertTanh(width.species(), Inputs.uniform(9100L + width.ordinal(), DENSE, -30.0, 30.0));
  }

  /**
   * Draws spread over the binades 2^-1022 to 2^1023, and densely over 2^-60 to 2^10, both signs,
   * and the subnormals: the tiny lanes, both forms and the saturated ones.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void magnitudes(VectorWidth width) {
    long seed = 9200L + 16L * width.ordinal();
    assertTanh(width.species(), Inputs.concat(
        Inputs.magnitudes(seed, 1 << 18, -60, 10),
        Inputs.magnitudes(seed + 1, 1 << 16, -1022, 1023),
        Inputs.magnitudes(seed + 2, 1 << 16, -58, -50),
        Inputs.subnormals(seed + 3, 1 << 16),
        Inputs.uniform(seed + 4, 1 << 16, -1.0, 1.0)));
  }

  /** Dense draws around the two forms' split at 1, around 19 where tanh reaches 1, and 22. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void formSplitAndLimit(VectorWidth width) {
    long seed = 9300L + 16L * width.ordinal();
    assertTanh(width.species(), Inputs.concat(
        Inputs.uniform(seed, 1 << 16, 0.5, 2.0),
        Inputs.uniform(seed + 1, 1 << 16, -2.0, -0.5),
        Inputs.uniform(seed + 2, 1 << 16, 18.0, 24.0),
        Inputs.uniform(seed + 3, 1 << 16, -24.0, -18.0)));
  }

  private static double[] maskedInputs() {
    return Inputs.concat(thresholds(), Inputs.sampled(9400L, 4096),
        Inputs.uniform(9401L, 4096, -30.0, 30.0), Inputs.magnitudes(9402L, 4096, -60, 10),
        Inputs.subnormals(9404L, 1024));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertTanhMasked(width.species(), maskedInputs(), 9403L);
  }

  /**
   * SPECIES_MAX, which {@link VectorWidth} does not run: its shape is none of the fixed ones, so
   * tanh's int lanes are a fixed-shape species of another shape ({@link Lanes#intSpecies}).
   */
  @Test
  void maxSpecies() {
    double[] xs = Inputs.concat(thresholds(), Inputs.sampled(9500L, 1 << 14),
        Inputs.uniform(9501L, 1 << 14, -30.0, 30.0), Inputs.magnitudes(9502L, 1 << 14, -60, 10),
        Inputs.subnormals(9504L, 1 << 12));
    assertTanh(DoubleVector.SPECIES_MAX, xs);
    assertTanhMasked(DoubleVector.SPECIES_MAX, xs, 9503L);
  }

  /**
   * One vector for every subset of a vector's lanes, the subset's lanes rare (an infinity or a
   * NaN, the lanes finished per lane) and the others common, the subsets as {@link
   * ExpTest#rareLaneSubsets} lists them: exactly one rare lane at each position, every lane rare,
   * and every mix between. The finish walks the rare mask's set bits, so a lane it skipped or
   * visited twice shows here. The rare inputs cycle through both infinities and NaNs of both
   * signs, quiet and signalling; the common lanes include both zeros, a tiny lane, a subnormal,
   * saturated lanes of both signs and the largest double.
   */
  static double[] everySubsetOfLanesRare(int lanes) {
    double[] rare = {
      Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
      Double.longBitsToDouble(0xfff8_0000_0000_0001L),
      Double.longBitsToDouble(0x7ff0_0000_0000_0001L),
      Double.longBitsToDouble(0xfff0_0000_0000_0001L)
    };
    double[] common = {0.0, -0.0, 0x1p-60, -Double.MIN_VALUE, 25.0, -30.0, Double.MAX_VALUE};
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
    assertTanh(species, xs);
    assertTanhMasked(species, xs, 9600L);
  }

  /**
   * The scalar transcription of s_tanh.c is {@code StrictMath.tanh} on the inputs the vector
   * tests use, so a hinge or a fault run that reads it shows the C's result.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    List<String> first = new ArrayList<>();
    assertEquals(0, mismatches(gateInputs(1 << 16), first), String.join("\n", first));
  }

  /** The inputs of the JIT-mode gate: the thresholds and {@code draws} of each kind of draw. */
  static double[] gateInputs(int draws) {
    return Inputs.concat(thresholds(), Inputs.sampled(9710L, draws),
        Inputs.uniform(9711L, draws, -30.0, 30.0), Inputs.magnitudes(9712L, draws, -60, 10),
        Inputs.subnormals(9713L, draws >> 2));
  }

  /** How many of {@code xs} the transcription differs from {@code StrictMath.tanh} on. */
  static long mismatches(double[] xs, List<String> first) {
    long n = 0;
    for (double x : xs) {
      long want = Double.doubleToRawLongBits(StrictMath.tanh(x));
      long got = Double.doubleToRawLongBits(cTanh(x));
      if (want != got && n++ < 10) {
        first.add("tanh(" + Oracle.hex(x) + "): StrictMath " + Long.toHexString(want)
            + ", transcription " + Long.toHexString(got));
      }
    }
    return n;
  }

  /**
   * The gate in a JVM of its own, as {@code HypotTest}'s: {@code [draws [passes]]}, by default
   * 2^14 and 3. It prints the JVM's arguments and each pass's count of inputs and mismatches (the
   * first ten with their bits), and exits 1 if any pass has a mismatch.
   *
   * <pre>
   *   java -Xint --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
   *     io.github.vecbricks.vfdlibm.TanhTest 0x4000 1
   * </pre>
   */
  public static void main(String[] args) {
    int draws = args.length > 0 ? Integer.decode(args[0]) : 1 << 14;
    int passes = args.length > 1 ? Integer.parseInt(args[1]) : 3;
    System.out.println("jvm: " + ManagementFactory.getRuntimeMXBean().getInputArguments()
        + " | " + System.getProperty("java.vm.info"));
    double[] xs = gateInputs(draws);
    boolean failed = false;
    for (int pass = 1; pass <= passes; pass++) {
      List<String> first = new ArrayList<>();
      long n = mismatches(xs, first);
      System.out.println("pass " + pass + ": checked " + xs.length + ", mismatches " + n);
      first.forEach(m -> System.out.println("MISMATCH " + m));
      failed |= n > 0;
    }
    System.out.println(failed ? "result: FAILED" : "result: ok");
    System.exit(failed ? 1 : 0);
  }

  /**
   * The scalar transcription against {@code StrictMath.tanh} in a JVM per JIT mode (issue 162
   * asks it of every port of the later set), as {@link
   * HypotTest#scalarTranscriptionIsStrictMathInEveryJitMode} runs hypot's: every pass of every
   * mode passes, and each JVM had the mode's flag and says so in its {@code java.vm.info}. That C1
   * or C2 compiled either method is not checked. As hypot's, the children run only where the test
   * JVM has no width option.
   */
  @ParameterizedTest
  @EnumSource(HypotTest.JitMode.class)
  void scalarTranscriptionIsStrictMathInEveryJitMode(HypotTest.JitMode mode) throws Exception {
    HypotTest.assumeNoWidthOption();
    HypotTest.assertRanInModeAndPassed(mode,
        HypotTest.runInJvm(TanhTest.class, "tanh gate", mode, 1 << 14, 2));
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector x = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.tanh(x, null));
  }

  /** The constants and words are s_tanh.c's, and line 79's {@code one - tiny} is 1. */
  @Test
  void constantsAreTheSourcesBits() {
    assertBits(0x3ff00000_00000000L, Tanh.ONE);
    assertBits(0x40000000_00000000L, Tanh.TWO);
    assertEquals(1.0e-300, Tanh.TINY);
    assertBits(0x3ff00000_00000000L, Tanh.SATURATED);
    // s_tanh.c's literal words: lines 61, 67, 68 and 70.
    assertEquals(0x7ff00000, Tanh.HX_NON_FINITE);
    assertEquals(0x40360000, Tanh.HX_LIMIT);
    assertEquals(0x3c800000, Tanh.HX_TINY);
    assertEquals(0x3ff00000, Tanh.HX_ONE);
  }

  private static void assertBits(long want, double got) {
    assertEquals(Long.toHexString(want), Long.toHexString(Double.doubleToRawLongBits(got)));
  }

  // ---------------------------------------------------------------------------------------------
  // The hinge of PROOFS.md's tanh section: Expm1.expm1Core is StrictMath.expm1 on every lane
  // outside Expm1.expm1Rare, so tanh's lanes are the C's only if no lane whose t is kept has an
  // argument expm1Rare sets.
  // ---------------------------------------------------------------------------------------------

  /**
   * The first of {@code xs} whose {@code t} line 72 or 75 keeps, under the words given for lines
   * 67, 68 and 70, and whose expm1 argument ({@code two*|x|} from {@code oneWord} up, {@code
   * -two*|x|} below it, as {@link Tanh} picks it) is a lane of {@link Expm1#expm1Rare}; or null.
   * It runs the vector mask at {@code species}, the same entry {@code Tanh} nests beside.
   */
  static Double keptLaneWithARareArgument(VectorSpecies<Double> species, double[] xs,
      int tinyWord, int oneWord, int limitWord) {
    int lanes = species.length();
    double[] block = new double[lanes];
    for (int base = 0; base < xs.length; base += lanes) {
      double[] args = new double[lanes];
      for (int lane = 0; lane < lanes; lane++) {
        double x = base + lane < xs.length ? xs[base + lane] : 0.0;
        block[lane] = x;
        int ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
        args[lane] = ix >= oneWord ? Tanh.TWO * Math.abs(x) : -Tanh.TWO * Math.abs(x);
      }
      VectorMask<Double> rare = Expm1.expm1Rare(DoubleVector.fromArray(species, args, 0));
      for (int lane = 0; lane < lanes; lane++) {
        int ix = (int) (Double.doubleToRawLongBits(block[lane]) >>> 32) & 0x7fffffff;
        boolean kept = ix >= tinyWord && ix < limitWord;
        if (kept && rare.laneIsSet(lane)) {
          return block[lane];
        }
      }
    }
    return null;
  }

  /** The inputs of the hinge: every range edge, and draws over each range and past it. */
  private static double[] hingeInputs() {
    return Inputs.concat(thresholds(), Inputs.around(0x1p-55, 4096),
        Inputs.around(-0x1p-55, 4096), Inputs.around(1.0, 4096), Inputs.around(-1.0, 4096),
        Inputs.around(22.0, 4096), Inputs.around(-22.0, 4096),
        Inputs.magnitudes(9800L, 1 << 16, -60, 10), Inputs.uniform(9801L, 1 << 16, -400, 400),
        Inputs.around(0x1p-56, 64), Inputs.around(19.5, 64), Inputs.around(360.0, 64));
  }

  /**
   * The hinge holds: under s_tanh.c's own words no kept lane's argument is rare. Its arguments
   * are {@code -two*|x|} of {@code (-2, -2^-54]} and {@code two*|x|} of {@code [2, 44)}: not
   * below expm1's tiny word (doubling {@code 2^-55} gives expm1's {@code 2^-54}, as the words
   * say), not below {@code -56*ln2} and not above {@code o_threshold}, and finite.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void noKeptLaneHasARareExpm1Argument(VectorWidth width) {
    assertEquals(Expm1.HX_TINY, Tanh.HX_TINY + 0x00100000, "2 * 2^-55 is expm1's 2^-54");
    assertNull(keptLaneWithARareArgument(width.species(), hingeInputs(),
        Tanh.HX_TINY, Tanh.HX_ONE, Tanh.HX_LIMIT));
  }

  /**
   * The hinge's check fails when each of its three conditions is false: the tiny word one lower
   * (a kept argument below expm1's 2^-54), the split at 32 and not at 1 (a kept {@code -two*|x|}
   * below {@code -56*ln2}, from about 19.4 up), and the limit at 368 and not at 22 (a kept {@code
   * two*|x|} above {@code o_threshold}, from about 354.9 up).
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void falseHingesAreCaught(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = hingeInputs();
    assertNotNull(keptLaneWithARareArgument(species, xs,
        Tanh.HX_TINY - 1, Tanh.HX_ONE, Tanh.HX_LIMIT), "tiny word one lower");
    assertNotNull(keptLaneWithARareArgument(species, xs,
        Tanh.HX_TINY, 0x40400000, Tanh.HX_LIMIT), "split at 32");
    assertNotNull(keptLaneWithARareArgument(species, xs,
        Tanh.HX_TINY, Tanh.HX_ONE, 0x40770000), "limit at 368");
  }

  /**
   * The vector tie of the hinge: the arguments' lanes that the hinge says are never rare are
   * {@code StrictMath.expm1} in {@link Expm1#expm1Core}, at the hinge's own edges.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void coreIsStrictMathOnTheKeptArguments(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    double[] xs = Inputs.concat(Inputs.around(0x1p-55, 256), Inputs.around(1.0, 256),
        Inputs.around(22.0, 256), Inputs.magnitudes(9900L, 1 << 14, -55, 4));
    double[] args = new double[xs.length];
    int n = 0;
    for (double x : xs) {
      int ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
      if (ix >= Tanh.HX_TINY && ix < Tanh.HX_LIMIT) {
        args[n++] = ix >= Tanh.HX_ONE ? Tanh.TWO * Math.abs(x) : -Tanh.TWO * Math.abs(x);
      }
    }
    Oracle.assertUnary("Expm1.expm1Core", species, Expm1::expm1Core, StrictMath::expm1,
        Arrays.copyOf(args, n));
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of tanh's faults, with a check that must fail while it is planted: the vector forms
   * over {@link #faultRunInputs()} at every width the host has. The split moved to 2 shows in
   * the lanes of {@code [1, 2)}; the tiny word moved to {@code 2^-28} in the lanes of {@code
   * [2^-53, 2^-28)}; the 22 moved to 16 in the lanes of {@code [16, 19.06)}; the numerator {@code
   * two} one ulp up in some lanes of {@code [1, 19)}. Each was sized on 2^22 draws before it was
   * wired ({@link Tanh}).
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Tanh.SPLIT_MOVED, Tanh.TINY_MOVED, Tanh.THRESHOLD_MOVED, Tanh.TWO_ONE_ULP_OFF}) {
      catchers.put(fault, () -> forEachWidth(species -> ARMS.forEach((name, arm) ->
          PlantedFaultTest.assertCaught(fault, () -> Oracle.assertUnary(
              name, species, x -> arm.apply(x, null), StrictMath::tanh, faultRunInputs())))));
    }
    return catchers;
  }

  private static double[] faultRunInputs() {
    return Inputs.concat(
        thresholds(),
        Inputs.uniform(9810L, 1 << 16, -2.0, 2.0),
        Inputs.magnitudes(9811L, 1 << 14, -60, -20),
        Inputs.uniform(9812L, 1 << 14, 16.0, 22.0),
        Inputs.uniform(9813L, 1 << 14, -22.0, -16.0),
        Inputs.uniform(9814L, 1 << 16, 1.0, 19.0));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertTanh(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
