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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BinaryOperator;
import jdk.incubator.vector.DoubleVector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The fuzz's draws at differences between the two arguments' high words
 * ({@link Fuzz.PairEdges#wordGaps}), for a function whose branch is on {@code iy - ix}, as
 * {@code e_atan2.c}'s: {@code k = (iy - ix) >> 20} with {@code k > 60} iff {@code iy - ix >=
 * 61 * 2^20}, and {@code k < -60} iff {@code iy - ix < -60 * 2^20}. The four gaps are the two
 * thresholds and the word below each. A toy with a fault at exactly one gap is found by the fuzz
 * at every width when the gaps are listed, and not found within the same draws when they are not.
 */
class FuzzWordGapsTest {

  private static final int ABOVE = 61 << 20;
  private static final int BELOW = -60 << 20;
  /** The words the thresholds of e_atan2.c's {@code k} split: each side of each. */
  private static final int[] GAPS = {ABOVE, ABOVE - 1, BELOW, BELOW - 1};

  @TempDir Path dir;

  private static final Fuzz.Edges EDGES =
      new Fuzz.Edges(new int[] {0x3ff00000}, new int[0], false, new double[] {1.0});

  private static int word(double d) {
    return (int) (Double.doubleToRawLongBits(d) >>> 32) & 0x7fffffff;
  }

  @BeforeAll
  static void registerTheToys() {
    removeTheToys();
    for (int i = 0; i < GAPS.length; i++) {
      Nightly.PairFunction.KNOWN.add(toy("toy2-gap" + i, GAPS[i], GAPS));
      Nightly.PairFunction.KNOWN.add(toy("toy2-nogap" + i, GAPS[i], new int[0]));
    }
  }

  @AfterAll
  static void removeTheToys() {
    Nightly.PairFunction.KNOWN.removeIf(f -> f.id().startsWith("toy2-gap")
        || f.id().startsWith("toy2-nogap"));
  }

  /** The pair harness's toy with its result's low bit flipped where {@code iy - ix == gap}. */
  private static Nightly.PairFunction toy(String id, int gap, int[] listed) {
    BinaryOperator<DoubleVector> all = (x, y) -> {
      double[] r = PairHarnessTest.vector(x, y, PairHarnessTest.Fault.NONE).toArray();
      double[] xs = x.toArray();
      double[] ys = y.toArray();
      for (int lane = 0; lane < r.length; lane++) {
        if (word(xs[lane]) - word(ys[lane]) == gap) {
          r[lane] = Double.longBitsToDouble(Double.doubleToRawLongBits(r[lane]) ^ 1L);
        }
      }
      return DoubleVector.fromArray(x.species(), r, 0);
    };
    return new Nightly.PairFunction(id, PairHarnessTest::scalar, PairBits.Candidates.OPERANDS,
        List.of(new Nightly.Arm2(
            "Toy.masked", all, (x, y, live) -> x.blend(all.apply(x, y), live))),
        new Fuzz.PairEdges(EDGES, EDGES, Fuzz.PAIR_SPECIALS, listed));
  }

  @Test
  void everyGapIsDrawnAtAboutItsShare() {
    Fuzz.PairEdges edges = new Fuzz.PairEdges(EDGES, EDGES, Fuzz.PAIR_SPECIALS, GAPS);
    SplittableRandom random = new SplittableRandom(0x5eedL);
    double[] pair = new double[2];
    int[] hits = new int[GAPS.length];
    int draws = 200_000;
    for (int i = 0; i < draws; i++) {
      Fuzz.drawPair(random, edges, pair);
      int d = word(pair[0]) - word(pair[1]);
      for (int g = 0; g < GAPS.length; g++) {
        if (d == GAPS[g]) {
          hits[g]++;
        }
      }
    }
    // one pair in ten, a quarter of them at each gap: 5000 of 200,000
    for (int g = 0; g < GAPS.length; g++) {
      assertTrue(hits[g] > 4000 && hits[g] < 6000, "gap " + GAPS[g] + " hit " + hits[g]);
    }
  }

  @Test
  void aFunctionWithoutGapsIsNotDrawnAtThemAndKeepsItsStream() {
    Fuzz.PairEdges without = new Fuzz.PairEdges(EDGES, EDGES, Fuzz.PAIR_SPECIALS);
    Fuzz.PairEdges empty = new Fuzz.PairEdges(EDGES, EDGES, Fuzz.PAIR_SPECIALS, new int[0]);
    SplittableRandom a = new SplittableRandom(7);
    SplittableRandom b = new SplittableRandom(7);
    double[] p = new double[2];
    double[] q = new double[2];
    int hits = 0;
    for (int i = 0; i < 200_000; i++) {
      Fuzz.drawPair(a, without, p);
      Fuzz.drawPair(b, empty, q);
      assertEquals(Double.doubleToRawLongBits(p[0]), Double.doubleToRawLongBits(q[0]));
      assertEquals(Double.doubleToRawLongBits(p[1]), Double.doubleToRawLongBits(q[1]));
      int d = word(p[0]) - word(p[1]);
      hits += d == ABOVE || d == BELOW ? 1 : 0;
    }
    assertTrue(hits < 10, "an exact gap by chance: " + hits);
  }

  private int fuzz(String function, String species, Path out) {
    return Fuzz.run(new String[] {
      "--function", function, "--species", species, "--seed", "0x5eed", "--draws", "1000000",
      "--stop-after", "1", "--seconds", "600", "--threads", "2", "--out", out.toString()});
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void aFaultAtEachGapIsFoundAtEveryWidthOnlyWhenTheGapsAreListed(VectorWidth width) {
    String species = width == VectorWidth.PREFERRED
        ? "preferred" : "" + width.species().vectorBitSize();
    for (int i = 0; i < GAPS.length; i++) {
      assertEquals(Nightly.MISMATCH, fuzz("toy2-gap" + i, species, dir.resolve(width + "g" + i)),
          "gap " + GAPS[i] + " at " + width);
      assertEquals(Nightly.OK, fuzz("toy2-nogap" + i, species, dir.resolve(width + "n" + i)),
          "gap " + GAPS[i] + " unlisted at " + width);
    }
  }
}
