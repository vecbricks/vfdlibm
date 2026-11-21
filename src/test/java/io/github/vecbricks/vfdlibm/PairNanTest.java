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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * {@link PairBits}, the one comparison of a lane of a function of two arguments, and that it can
 * fail. Toys that are the pair harness's {@code x * 0.5 + y} with one change on one kind of lane:
 * a result that is exactly one argument's bits quieted passes on a lane of two different NaNs, and
 * a canonical NaN, a NaN of a third payload, the wrong sign, or one payload bit off on any other
 * kind of lane is caught, by {@code Oracle}'s asserts, masked and not, and by the nightly's pair
 * check, whose mismatch file replays the lane.
 */
class PairNanTest {

  private static final long QUIET = PairBits.QUIET;
  private static final long CANONICAL = 0x7ff8000000000000L;

  // NaNs of both signs, quiet and signalling, none of them the canonical NaN.
  private static final long A = 0x7ff8000000000123L;
  private static final long B = 0xfff4000000000456L;
  private static final long C = 0x7ff0000000000789L;
  private static final long D = 0xfff8000000000abcL;

  /** One kind of lane each: two NaNs that differ, two that are the same, one, and none. */
  private static final long[][] LANES = {
    {A, B}, {B, A}, {C, D}, {D, C}, {A, D}, {C, A}, {B, C}, {D, B},
    {A, A}, {B, B},
    {A, bits(1.5)}, {bits(1.5), B}, {C, bits(-2.0)}, {bits(3.0), D},
    {bits(1.0), bits(2.0)},
    {bits(Double.POSITIVE_INFINITY), bits(Double.NEGATIVE_INFINITY)},
  };

  /** What a toy returns on a lane of one kind; the lanes of the other kinds are the base's. */
  enum Toy {
    /** The base: what the hardware's sum gives. */
    BASE(false),
    /** Two different NaNs give the first argument, quieted. */
    FIRST(false),
    /** Two different NaNs give the second argument, quieted. */
    SECOND(false),
    /** Two different NaNs give the canonical NaN. */
    CANONICAL_NAN(true),
    /** Two different NaNs give a NaN of a payload neither has. */
    THIRD_PAYLOAD(true),
    /** Two different NaNs give the first argument, quieted, with the other sign. */
    OTHER_SIGN(true),
    /** Two different NaNs give the first argument's bits as they are, not quieted. */
    NOT_QUIETED(true),
    /** Two NaNs of the same bits give the canonical NaN. */
    IDENTICAL_CANONICAL(true),
    /** One NaN argument gives a result that differs in the lowest payload bit. */
    ONE_NAN_BIT(true),
    /** Arguments that are not NaN give a result that differs in the lowest bit. */
    FINITE_BIT(true);

    private final boolean caught;

    Toy(boolean caught) {
      this.caught = caught;
    }
  }

  @TempDir Path dir;

  private static long bits(double d) {
    return Double.doubleToRawLongBits(d);
  }

  private static double[] column(int c) {
    return Arrays.stream(LANES).mapToDouble(l -> Double.longBitsToDouble(l[c])).toArray();
  }

  /** The toy's vector function: the pair harness's toy with one change. */
  private static DoubleVector apply(Toy toy, DoubleVector vx, DoubleVector vy) {
    double[] r = PairHarnessTest.vector(vx, vy, PairHarnessTest.Fault.NONE).toArray();
    double[] x = vx.toArray();
    double[] y = vy.toArray();
    for (int lane = 0; lane < r.length; lane++) {
      long xb = bits(x[lane]);
      long yb = bits(y[lane]);
      long rb = bits(r[lane]);
      boolean differ = PairBits.bothNaNWithDifferentBits(xb, yb);
      boolean identical = PairBits.isNaN(xb) && xb == yb;
      boolean one = PairBits.isNaN(xb) != PairBits.isNaN(yb);
      boolean none = !PairBits.isNaN(xb) && !PairBits.isNaN(yb);
      r[lane] = Double.longBitsToDouble(switch (toy) {
        case BASE -> rb;
        case FIRST -> differ ? xb | QUIET : rb;
        case SECOND -> differ ? yb | QUIET : rb;
        case CANONICAL_NAN -> differ ? CANONICAL : rb;
        case THIRD_PAYLOAD -> differ ? (xb | QUIET) ^ 2 : rb;
        case OTHER_SIGN -> differ ? (xb | QUIET) ^ (1L << 63) : rb;
        case NOT_QUIETED -> differ ? xb : rb;
        case IDENTICAL_CANONICAL -> identical ? CANONICAL : rb;
        case ONE_NAN_BIT -> one ? rb ^ 1 : rb;
        case FINITE_BIT -> none ? rb ^ 1 : rb;
      });
    }
    return DoubleVector.fromArray(vx.species(), r, 0);
  }

  @Test
  void theRuleOnEachKindOfLane() {
    long q = QUIET;
    // two NaNs with different bits: either argument's bits, quieted, all 64 bits
    assertTrue(PairBits.matches(A, B, 0L, A | q));
    assertTrue(PairBits.matches(A, B, 0L, B | q));
    assertTrue(PairBits.matches(C, D, 0L, C | q));
    assertFalse(PairBits.matches(A, B, A | q, CANONICAL));
    assertFalse(PairBits.matches(A, B, A | q, (A | q) ^ 1));
    assertFalse(PairBits.matches(A, B, A | q, (A | q) ^ (1L << 63)));
    assertFalse(PairBits.matches(C, D, C | q, C), "a signalling NaN is quieted");
    // the same bits: the oracle's, raw
    assertTrue(PairBits.matches(A, A, A, A));
    assertFalse(PairBits.matches(A, A, A, CANONICAL));
    assertFalse(PairBits.matches(C, C, C | q, C));
    // one NaN: the oracle's, raw, though the NaN argument's bits would be accepted on a pair
    assertTrue(PairBits.matches(A, bits(1.0), A, A));
    assertFalse(PairBits.matches(A, bits(1.0), A, A ^ 1));
    assertFalse(PairBits.matches(bits(1.0), B, B | q, (B | q) ^ 1));
    // no NaN: raw, a NaN made from infinities included
    assertTrue(PairBits.matches(bits(1.0), bits(2.0), bits(2.5), bits(2.5)));
    assertFalse(PairBits.matches(bits(1.0), bits(2.0), bits(2.5), bits(2.5) + 1));
    assertFalse(PairBits.matches(bits(Double.POSITIVE_INFINITY), bits(Double.NEGATIVE_INFINITY),
        CANONICAL, CANONICAL | (1L << 63)));
    // a NaN's bits are never taken for a number's: infinity is not a NaN
    assertFalse(PairBits.isNaN(bits(Double.POSITIVE_INFINITY)));
    assertTrue(PairBits.isNaN(0xfff0000000000001L));
    // one pair that quieting makes alike is still a pair of different bits
    assertTrue(PairBits.matches(C, C | q, 0L, C | q));
    assertFalse(PairBits.matches(C, C | q, 0L, C));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void theAssertsOfTheOracleCatchWhatTheRuleRefuses(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    for (Toy toy : Toy.values()) {
      BinaryOperator<DoubleVector> all = (x, y) -> apply(toy, x, y);
      Oracle.MaskedBinary masked = (x, y, live) -> x.blend(apply(toy, x, y), live);
      Runnable plain = () -> Oracle.assertPair(
          toy.name(), species, all, PairHarnessTest::scalar, column(0), column(1));
      Runnable maskedCheck = () -> Oracle.assertPairMasked(
          toy.name(), species, masked, PairHarnessTest::scalar, column(0), column(1), 7);
      for (Runnable check : List.of(plain, maskedCheck)) {
        if (toy.caught) {
          assertThrows(AssertionFailedError.class, check::run, toy + " at " + species);
        } else {
          check.run();
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void theRawAssertKeepsEveryLaneRawAndOnlyThePairAssertRelaxesTwoNaNs(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    BinaryOperator<DoubleVector> first = (x, y) -> apply(Toy.FIRST, x, y);
    // the oracle's bits on two different NaNs are those of the sum, which FIRST changes on a lane
    // where the sum keeps the second argument; the canonical NaN is neither
    BinaryOperator<DoubleVector> canonical = (x, y) -> apply(Toy.CANONICAL_NAN, x, y);
    Oracle.assertPair("first", species, first, PairHarnessTest::scalar, column(0), column(1));
    assertThrows(AssertionFailedError.class, () -> Oracle.assertBinary(
        "canonical", species, canonical, PairHarnessTest::scalar, column(0), column(1)));
    double[] xs = {Double.longBitsToDouble(A), Double.longBitsToDouble(A)};
    double[] ys = {Double.longBitsToDouble(B), Double.longBitsToDouble(B)};
    // a function that returns B's bits quieted where the oracle returns A's: raw says no
    BinaryOperator<DoubleVector> second = (x, y) -> apply(Toy.SECOND, x, y);
    DoubleBinaryOperator wantsFirst = (x, y) -> Double.longBitsToDouble(bits(x) | QUIET);
    assertThrows(AssertionFailedError.class,
        () -> Oracle.assertBinary("second", species, second, wantsFirst, xs, ys));
    Oracle.assertPair("second", species, second, wantsFirst, xs, ys);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void aLaneThatIsNotLiveIsStillTheFirstArgumentsRawBits(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    // quiets x on every lane, the lanes that are not live included
    Oracle.MaskedBinary quietsX = (x, y, live) -> {
      double[] r = x.toArray();
      double[] ok = apply(Toy.BASE, x, y).toArray();
      for (int lane = 0; lane < r.length; lane++) {
        r[lane] = Double.longBitsToDouble(
            live.laneIsSet(lane) ? bits(ok[lane]) : bits(r[lane]) | QUIET);
      }
      return DoubleVector.fromArray(species, r, 0);
    };
    assertThrows(AssertionFailedError.class, () -> Oracle.assertPairMasked(
        "quietsX", species, quietsX, PairHarnessTest::scalar, column(0), column(1), 7));
  }

  private long mismatches(Toy toy, VectorSpecies<Double> species, double[] xs, double[] ys,
      Path file) throws IOException {
    Fuzz.Edges edges =
        new Fuzz.Edges(new int[] {0x3ff00000}, new int[0], false, new double[] {1.0});
    Nightly.PairFunction f = new Nightly.PairFunction("toy2-nan-" + toy.name().toLowerCase(),
        PairHarnessTest::scalar,
        List.of(new Nightly.Arm2("Toy.masked", (x, y) -> apply(toy, x, y),
            (x, y, live) -> x.blend(apply(toy, x, y), live))),
        new Fuzz.PairEdges(edges, edges, Fuzz.PAIR_SPECIALS));
    try (Nightly.Mismatches sink = new Nightly.Mismatches(file, "header\nreplay: x", "ctx", 2)) {
      f.newChecker(species, 5, sink).check(xs, ys, xs.length, 0);
      return sink.count();
    }
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void theNightlysPairCheckCatchesWhatTheRuleRefusesAndItsFileReplaysIt(VectorWidth width)
      throws IOException {
    VectorSpecies<Double> species = width.species();
    for (Toy toy : Toy.values()) {
      Path file = dir.resolve(toy + "-" + width + ".txt");
      long found = mismatches(toy, species, column(0), column(1), file);
      assertEquals(toy.caught, found > 0, toy + " at " + species + ": " + found);
      if (!toy.caught) {
        continue;
      }
      // the lanes the file holds, run again, are found again
      Inputs.Pairs pairs = Inputs.readPairs(Files.readAllLines(file), file.toString());
      int lanes = species.length();
      int n = (pairs.x().length + lanes - 1) / lanes * lanes;
      double[] xs = Arrays.copyOf(pairs.x(), n);
      double[] ys = Arrays.copyOf(pairs.y(), n);
      for (int i = pairs.x().length; i < n; i++) {
        xs[i] = xs[pairs.x().length - 1];
        ys[i] = ys[pairs.x().length - 1];
      }
      Path again = dir.resolve("replay-" + toy + "-" + width + ".txt");
      assertTrue(mismatches(toy, species, xs, ys, again) > 0,
          "the replay of " + toy + " at " + species);
    }
  }
}
