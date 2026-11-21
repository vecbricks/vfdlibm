/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BinaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The nightly's checks of a function of two arguments ({@code Sweep}, {@code Fuzz}, the mismatch
 * file, the pair hard-case reader), on a toy that no port computes: {@code toy(x, y) = x * 0.5 +
 * y}, whose vector form is exact and which has planted faults of the three shapes the nightly
 * must find or, said plainly, must not be asked to: a fault in a high word of the first argument,
 * one in a high word of the second, and one that needs a high word of each at once, which a sweep
 * of one argument draws a partner for and so meets with probability near 2^-32 a pair, and the
 * fuzz meets through its draws at both arguments' edges. A fault is the low bit of the result
 * flipped, a change every result shows, a NaN too.
 */
class PairHarnessTest {

  /** The high word of x where the first fault is. */
  private static final int X_WORD = 0x40100000;
  /**
   * The high word of y where the second fault is: not 0x3ff00000, which is 1.0's, a special value
   * of the sweep's partners.
   */
  private static final int Y_WORD = 0x3ff00004;

  /** Which fault a toy has. */
  private enum Fault { NONE, X_WORD_FAULT, Y_WORD_FAULT, BOTH_WORDS_FAULT, DEAD_LANES }

  private static final String[] NAMES = {
    "toy2", "toy2-x", "toy2-y", "toy2-xy", "toy2-dead"
  };

  @TempDir Path dir;

  @BeforeAll
  static void registerTheToys() {
    removeTheToys();
    for (Fault fault : Fault.values()) {
      Nightly.PairFunction.KNOWN.add(toy(NAMES[fault.ordinal()], fault));
    }
  }

  /** The toys are known to the nightly's lookup for this class only. */
  @AfterAll
  static void removeTheToys() {
    Nightly.PairFunction.KNOWN.removeIf(f -> f.id().startsWith("toy2"));
  }

  private static Nightly.PairFunction toy(String id, Fault fault) {
    BinaryOperator<DoubleVector> all = (x, y) -> vector(x, y, fault);
    Oracle.MaskedBinary masked = fault == Fault.DEAD_LANES
        // A masked form that clears the lanes that are not live, where the contract keeps x.
        ? (x, y, live) -> DoubleVector.zero(x.species()).blend(vector(x, y, fault), live)
        : (x, y, live) -> x.blend(vector(x, y, fault), live);
    Fuzz.Edges xEdges = new Fuzz.Edges(
        new int[] {X_WORD, 0x3ff00000, 0}, new int[0], false, new double[] {1.0, 4.0});
    Fuzz.Edges yEdges = new Fuzz.Edges(
        new int[] {Y_WORD, 0x40000000, 0}, new int[0], false, new double[] {1.0, 2.0});
    return new Nightly.PairFunction(id, PairHarnessTest::scalar,
        List.of(new Nightly.Arm2("Toy.masked", all, masked),
            new Nightly.Arm2("Toy.unmaskedOnly", all, null)),
        new Fuzz.PairEdges(xEdges, yEdges, Fuzz.PAIR_SPECIALS));
  }

  /**
   * The oracle. A NaN result is the canonical NaN: which operand's payload a NaN sum keeps is
   * the hardware's and the compiler's (it may commute an addition), which the toy is not about.
   */
  private static double scalar(double x, double y) {
    double r = x * 0.5 + y;
    return Double.isNaN(r) ? Double.NaN : r;
  }

  private static DoubleVector vector(DoubleVector x, DoubleVector y, Fault fault) {
    DoubleVector r = x.mul(0.5).add(y);
    r = r.blend(Double.NaN, r.test(VectorOperators.IS_NAN));
    VectorMask<Double> bad = switch (fault) {
      case NONE, DEAD_LANES -> null;
      case X_WORD_FAULT -> highWordIs(x, X_WORD);
      case Y_WORD_FAULT -> highWordIs(y, Y_WORD);
      case BOTH_WORDS_FAULT -> highWordIs(x, X_WORD).and(highWordIs(y, Y_WORD));
    };
    if (bad == null) {
      return r;
    }
    LongVector bits = (LongVector) r.viewAsIntegralLanes();
    DoubleVector flipped =
        (DoubleVector) bits.lanewise(VectorOperators.XOR, 1L).viewAsFloatingLanes();
    return r.blend(flipped, bad);
  }

  private static VectorMask<Double> highWordIs(DoubleVector v, int word) {
    LongVector bits = (LongVector) v.viewAsIntegralLanes();
    return bits.lanewise(VectorOperators.LSHR, 32)
        .compare(VectorOperators.EQ, word & 0xffffffffL).cast(v.species());
  }

  /** The 4096 high words from {@code word}, as a {@code --part}. */
  private static String part(int word) {
    return (word >>> 12) + "/" + (1L << 20);
  }

  private int sweep(String function, int word, int argument, Path out) {
    return Sweep.run(new String[] {
      "--function", function, "--part", part(word), "--argument", "" + argument,
      "--seed", "0x5eed", "--threads", "2", "--out", out.toString()});
  }

  private int fuzz(String function, Path out) {
    return Fuzz.run(new String[] {
      "--function", function, "--seed", "0x5eed", "--draws", "1000000",
      "--stop-after", "1", "--seconds", "600", "--threads", "2", "--out", out.toString()});
  }

  @Test
  void aCorrectPairFunctionPassesBothPassesAndTheFuzz() throws IOException {
    for (int argument = 1; argument <= 2; argument++) {
      Path out = dir.resolve("sweep" + argument + ".txt");
      assertEquals(Nightly.OK, sweep("toy2", X_WORD, argument, out));
      List<String> lines = Files.readAllLines(out);
      assertEquals(Inputs.PAIR_HEADER, lines.get(0));
      assertTrue(lines.get(1).startsWith("# sweep toy2 "), lines.get(1));
      assertEquals("# total mismatches: 0 {}", lines.get(lines.size() - 1));
    }
    assertEquals(Nightly.OK, Fuzz.run(new String[] {
      "--function", "toy2", "--seed", "0x5eed", "--seconds", "600", "--draws", "65536",
      "--threads", "2", "--out", dir.resolve("fuzz.txt").toString()}));
  }

  @Test
  void theSweepOfTheFirstArgumentFindsAFaultInItsHighWordAndReplaysFromTheSeed()
      throws IOException {
    Path out = dir.resolve("x.txt");
    assertEquals(Nightly.MISMATCH, sweep("toy2-x", X_WORD, 1, out));
    List<String> lines = Files.readAllLines(out);
    assertEquals(Inputs.PAIR_HEADER, lines.get(0));
    // The three low words of the high word, each with its partner: 3 pairs, each in the arm that
    // is masked (unmasked and, under one random mask, masked) and in the one that is not.
    String total = lines.get(lines.size() - 1);
    assertTrue(total.startsWith("# total mismatches: "), total);

    // The pairs of the file are inputs as they stand: both words, no seed.
    Inputs.Pairs pairs = Inputs.readPairs(lines, "the mismatch file");
    assertTrue(pairs.x().length >= 3);
    for (int i = 0; i < pairs.x().length; i++) {
      assertEquals(X_WORD, (int) (Double.doubleToRawLongBits(pairs.x()[i]) >>> 32));
    }
    Nightly.PairFunction good = (Nightly.PairFunction) Nightly.subject("toy2");
    Nightly.PairFunction bad = (Nightly.PairFunction) Nightly.subject("toy2-x");
    for (int i = 0; i < pairs.x().length; i++) {
      assertTrue(Double.doubleToRawLongBits(good.oracle().applyAsDouble(pairs.x()[i], pairs.y()[i]))
          != Double.doubleToRawLongBits(
              bad.arms().get(0).all().apply(DoubleVector.broadcast(
                  DoubleVector.SPECIES_PREFERRED, pairs.x()[i]),
                  DoubleVector.broadcast(DoubleVector.SPECIES_PREFERRED, pairs.y()[i])).lane(0)));
    }

    // The replay line of the header, run again, finds the same mismatches.
    String replay = lines.stream().filter(l -> l.startsWith("# replay: Sweep ")).findFirst()
        .orElseThrow().substring("# replay: Sweep ".length());
    assertTrue(replay.contains("--argument 1"), replay);
    List<String> args = new ArrayList<>(Arrays.asList(replay.split(" ")));
    Path again = dir.resolve("again.txt");
    args.addAll(List.of("--threads", "2", "--out", again.toString()));
    assertEquals(Nightly.MISMATCH, Sweep.run(args.toArray(new String[0])));
    List<String> second = Files.readAllLines(again);
    // The count, not the order the kinds are listed in, which depends on the threads.
    String last = second.get(second.size() - 1);
    assertEquals(total.substring(0, total.indexOf('{')), last.substring(0, last.indexOf('{')));
    assertEquals(
        lines.stream().filter(l -> l.startsWith("0x")).sorted().toList(),
        second.stream().filter(l -> l.startsWith("0x")).sorted().toList());
  }

  @Test
  void theSecondPassFindsAFaultInTheSecondArgumentsHighWord() {
    assertEquals(Nightly.MISMATCH, sweep("toy2-y", Y_WORD, 2, dir.resolve("y2.txt")));
    // The first pass draws the second argument's values as partners: of high word Y_WORD once in
    // about 2^32 draws, so it passes a slice it was not asked about.
    assertEquals(Nightly.OK, sweep("toy2-y", X_WORD, 1, dir.resolve("y1.txt")));
  }

  @Test
  void theFuzzFindsAFaultThatNeedsAHighWordOfEachArgumentAndTheSweepsDoNot() {
    assertEquals(Nightly.MISMATCH, fuzz("toy2-xy", dir.resolve("fuzz-xy.txt")));
    assertEquals(Nightly.OK, sweep("toy2-xy", X_WORD, 1, dir.resolve("xy1.txt")));
    assertEquals(Nightly.OK, sweep("toy2-xy", Y_WORD, 2, dir.resolve("xy2.txt")));
  }

  @Test
  void theFuzzFindsTheFirstAndSecondFaultsToo() {
    assertEquals(Nightly.MISMATCH, fuzz("toy2-x", dir.resolve("fuzz-x.txt")));
    assertEquals(Nightly.MISMATCH, fuzz("toy2-y", dir.resolve("fuzz-y.txt")));
  }

  @Test
  void aMaskedFormThatDoesNotKeepTheFirstArgumentFails() throws IOException {
    Path out = dir.resolve("dead.txt");
    assertEquals(Nightly.MISMATCH, sweep("toy2-dead", 0x3ff00000, 1, out));
    List<String> lines = Files.readAllLines(out);
    String total = lines.get(lines.size() - 1);
    assertTrue(total.contains("Toy.masked masked="), total);
    assertTrue(!total.contains("unmasked="), total);
    assertTrue(!total.contains("Toy.unmaskedOnly"), total);
  }

  @Test
  void pairMismatchesAreWrittenAsPairs() throws IOException {
    Path out = dir.resolve("m.txt");
    try (Nightly.Mismatches m = new Nightly.Mismatches(out, "a header\nreplay: x", "ctx", 2)) {
      m.add("Toy.masked", -1, 2, 0x3ff0000000000000L, 0x4000000000000000L, 0x4005bf0a8b145769L,
          0x4005bf0a8b14576aL);
      m.add("Toy.masked", 0x5, 0, 0x4000000000000000L, 0x3ff0000000000000L, 0x401d8e64b8d4ddaeL,
          0L);
    }
    assertEquals(List.of(
        "# arity: 2",
        "# a header",
        "# replay: x",
        "# MISMATCH ctx arm=Toy.masked masked=false lane=2 x=0x3ff0000000000000"
            + " y=0x4000000000000000 expected=0x4005bf0a8b145769 actual=0x4005bf0a8b14576a",
        "0x3ff0000000000000 0x4000000000000000",
        "# MISMATCH ctx arm=Toy.masked masked=true mask=0x5 lane=0 x=0x4000000000000000"
            + " y=0x3ff0000000000000 expected=0x401d8e64b8d4ddae actual=0x0000000000000000",
        "0x4000000000000000 0x3ff0000000000000",
        "# total mismatches: 2 {Toy.masked unmasked=1, Toy.masked masked=1}"),
        Files.readAllLines(out));
    Inputs.Pairs pairs = Inputs.readPairs(Files.readAllLines(out), "m.txt");
    assertArrayEquals(new double[] {1.0, 2.0}, pairs.x());
    assertArrayEquals(new double[] {2.0, 1.0}, pairs.y());
  }

  @Test
  void aFileTakesTheMismatchesOfItsArityOnly() {
    Path out = dir.resolve("arity.txt");
    try (Nightly.Mismatches m = new Nightly.Mismatches(out, "h", "ctx")) {
      assertThrows(IllegalStateException.class, () -> m.add("a", -1, 0, 1L, 2L, 3L, 4L));
    }
    try (Nightly.Mismatches m = new Nightly.Mismatches(out, "h", "ctx", 2)) {
      assertThrows(IllegalStateException.class, () -> m.add("a", -1, 0, 1L, 3L, 4L));
    }
    assertThrows(IllegalArgumentException.class,
        () -> new Nightly.Mismatches(out, "h", "ctx", 3));
  }

  @Test
  void theHeaderTellsTheArityToTheReaders() {
    Inputs.Pairs pairs = Inputs.hardCasePairs("toy2");
    assertArrayEquals(new double[] {1.0, 0.5, -0.0}, pairs.x());
    assertArrayEquals(new double[] {2.0, 0x1p-1074, Double.NaN}, pairs.y());
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Inputs.hardCases("toy2"));
    assertTrue(e.getMessage().contains("hardCasePairs"), e.getMessage());
    for (String bad : new String[] {
      "0x3ff0000000000000 0x4000000000000000",
      "# arity: 2\n0x3ff0000000000000",
      "# arity: 2\n0x3ff0000000000000 0x4000000000000000 0x4000000000000000",
      "# arity: 2\n0x3ff0000000000000 4000000000000000",
      "# arity: 2\n# nothing"}) {
      assertThrows(RuntimeException.class,
          () -> Inputs.readPairs(List.of(bad.split("\n")), "bad"), bad);
    }
  }

  @Test
  void aSweepOfOneArgumentRefusesTheOptionAndAPairSweepTakesOneOrTwo() {
    String out = dir.resolve("o.txt").toString();
    assertEquals(Nightly.ERROR, Sweep.run(new String[] {
      "--function", "exp", "--argument", "1", "--seed", "1", "--out", out}));
    assertEquals(Nightly.ERROR, Sweep.run(new String[] {
      "--function", "toy2", "--argument", "3", "--seed", "1", "--out", out}));
    // The message of an unknown function lists the pair functions too.
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Nightly.subject("nope"));
    assertTrue(e.getMessage().startsWith(
        "unknown function 'nope'; known: exp, expm1, log, log10, log1p, sinh, cosh"),
        e.getMessage());
    assertTrue(e.getMessage().contains(", toy2"), e.getMessage());
  }

  /** The partner is a function of the seed, the argument and the index alone, in four kinds. */
  @Test
  void partnersAreReproducibleAndOfFourKinds() {
    double[] specials = Fuzz.PAIR_SPECIALS;
    int special = 0;
    int same = 0;
    int near = 0;
    int otherArgument = 0;
    int n = 200_000;
    for (long i = 0; i < n; i++) {
      double swept = Double.longBitsToDouble(Nightly.mix(i));
      double p = Sweep.partner(0x5eed, 1, i, swept, specials);
      assertEquals(Double.doubleToRawLongBits(p),
          Double.doubleToRawLongBits(Sweep.partner(0x5eed, 1, i, swept, specials)));
      if (Double.doubleToRawLongBits(p)
          != Double.doubleToRawLongBits(Sweep.partner(0x5eed, 2, i, swept, specials))) {
        otherArgument++;
      }
      int ep = (int) (Double.doubleToRawLongBits(p) >>> 52 & 0x7ff);
      int es = (int) (Double.doubleToRawLongBits(swept) >>> 52 & 0x7ff);
      for (double s : specials) {
        if (Math.abs(p) == s || Double.isNaN(p) && Double.isNaN(s)) {
          special++;
          break;
        }
      }
      if (ep == es) {
        same++;
      }
      if (Math.abs(ep - es) <= 64) {
        near++;
      }
    }
    // A quarter each of specials, the same exponent, within 64, and random bits (which are
    // within 64 of a random exponent about 6% of the time, as are the specials).
    assertTrue(otherArgument > 0.9 * n, "the other argument's partners differ: " + otherArgument);
    assertEquals(0.25, special / (double) n, 0.01);
    assertTrue(same / (double) n > 0.25 && same / (double) n < 0.30, "same " + same);
    assertTrue(near / (double) n > 0.5 && near / (double) n < 0.58, "near " + near);
  }
}
