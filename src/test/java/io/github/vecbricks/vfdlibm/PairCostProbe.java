/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.util.List;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;

/**
 * Times the nightly's pass over a function of two arguments before any is ported, on two
 * stand-ins registered for this program only, and then runs {@link Sweep} or {@link Fuzz} with
 * the rest of the arguments:
 *
 * <ul>
 *   <li>{@code toy2}, {@code x * 0.5 + y}: an oracle and a vector form that cost almost nothing,
 *       so a pass over it is the harness's own cost (partners, arrays, the check of a block, the
 *       masks) and a lower bound for any function;
 *   <li>{@code atan2-scratch}: the oracle is {@code StrictMath.atan2} and the one arm calls it lane
 *       by lane, so a pass over it is the harness plus three calls of it a pair (the oracle, the arm
 *       unmasked, the arm masked), the cost of an arm that finishes every lane per lane. It is no
 *       port and says nothing about one; it bounds the arm's cost from above.
 * </ul>
 *
 * <pre>
 *   java --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
 *     io.github.vecbricks.vfdlibm.PairCostProbe sweep --function atan2-scratch --species 256 \
 *     --stripe 0/4096 --argument 1 --seed 0x5eed --threads 4
 * </pre>
 */
final class PairCostProbe {

  private PairCostProbe() {}

  public static void main(String[] args) {
    Fuzz.Edges edges = new Fuzz.Edges(
        new int[] {0x3ff00000, 0x40000000, 0}, new int[0], false, new double[] {1.0, 2.0});
    Fuzz.PairEdges pairEdges = new Fuzz.PairEdges(edges, edges, Fuzz.PAIR_SPECIALS);
    Nightly.PairFunction.KNOWN.add(new Nightly.PairFunction("toy2", PairCostProbe::toy,
        PairBits.Candidates.OPERANDS,
        List.of(new Nightly.Arm2("Toy.masked", PairCostProbe::toy,
            (x, y, live) -> x.blend(toy(x, y), live))),
        pairEdges));
    Nightly.PairFunction.KNOWN.add(new Nightly.PairFunction("atan2-scratch", PairCostProbe::atan2,
        PairBits.Candidates.OPERANDS,
        List.of(new Nightly.Arm2("Scratch.atan2", PairCostProbe::atan2,
            (x, y, live) -> x.blend(atan2(x, y), live))),
        pairEdges));
    String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
    switch (args.length == 0 ? "" : args[0]) {
      case "sweep" -> Sweep.main(rest);
      case "fuzz" -> Fuzz.main(rest);
      default -> {
        System.err.println("usage: PairCostProbe sweep|fuzz <the program's options>");
        System.exit(Nightly.ERROR);
      }
    }
  }

  /** {@code x * 0.5 + y}, a NaN canonical: which operand's payload a sum keeps is no matter. */
  private static double toy(double x, double y) {
    double r = x * 0.5 + y;
    return Double.isNaN(r) ? Double.NaN : r;
  }

  private static DoubleVector toy(DoubleVector x, DoubleVector y) {
    DoubleVector r = x.mul(0.5).add(y);
    return r.blend(Double.NaN, r.test(VectorOperators.IS_NAN));
  }

  /**
   * {@code StrictMath.atan2}, a NaN canonical. For two NaNs it returns {@code x + y}, and which
   * payload that is depends on how the code that makes the sum was compiled: the oracle's call and
   * the arm's loop disagreed on some pairs of NaNs, which the run of 21 November 2026 in
   * bench/results/pair-sweep-laptop-2026-11-21.txt shows.
   */
  private static double atan2(double y, double x) {
    double r = StrictMath.atan2(y, x);
    return Double.isNaN(r) ? Double.NaN : r;
  }

  /** {@link #atan2(double, double)} of each lane's pair. */
  private static DoubleVector atan2(DoubleVector y, DoubleVector x) {
    double[] ys = y.toArray();
    double[] xs = x.toArray();
    for (int i = 0; i < ys.length; i++) {
      ys[i] = atan2(ys[i], xs[i]);
    }
    return DoubleVector.fromArray(y.species(), ys, 0);
  }
}
