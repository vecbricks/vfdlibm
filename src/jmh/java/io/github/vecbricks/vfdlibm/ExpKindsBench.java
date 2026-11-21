/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * exp's two arms on one kind of rare input at a time, to find which kind of {@link ExpBenchmark}'s
 * mixed data costs an arm its time there: the scalar {@code StrictMath.exp} loop, {@link
 * VFdlibm#exp} (rare lanes finished per lane) and {@link Exp#expBlended}, each vector form at 256
 * bits and at the host's preferred species, over an array of 1024 doubles. The time in question:
 * on the Xeon Platinum 8573C the blended arm's minimum at the preferred species is 1,909 ns on
 * common data and 4,998 ns on mixed, 2.62 times ({@code
 * bench/results/exp-xeon8573c-2026-11-21.txt}).
 *
 * <p>{@code inputs} picks the data. {@code common} and {@code mixed} are {@code ExpBenchmark}'s
 * arrays: draws from {@code [-20, 20]}, and for {@code mixed} one input in 16 replaced by a NaN,
 * 800, -750 or -708.5, which with this seed is 66 inputs (20, 17, 19 and 10 of them). Four kinds
 * have a rare input at those same 66 places, all of one value: {@code nan} (a NaN), {@code
 * overflow} (800, whose result is infinite), {@code underflow} (-750, whose result is zero) and
 * {@code subnormal} (-708.5, whose result is subnormal). {@code none} is their baseline: the same
 * draws with nothing replaced, so it differs from each kind at those 66 places only, and a row's
 * time on a kind less its time on {@code none} is that kind's cost at 66 lanes. In {@code mixed}
 * the kind has fewer, so its share there is that cost scaled by its count over 66 where the cost
 * is one per lane; a cost per vector, such as the finish arm's branch, would not add up that way.
 * Nor need the shares add up to {@code mixed}'s excess over {@code none}. A hypothesis, which
 * these rows are there to test: each value runs in a fork of its own, where C2 profiles one
 * kind's path and a branch repeats one outcome, while {@code mixed} interleaves four, and what
 * is left over would be that interleaving, not any one kind. The first run ({@code
 * bench/results/exp-kinds-xeon8573c-2026-11-21.txt}, one run, the finish before its first eight
 * lanes were written out) gives scaled sums that differ from mixed's excess over {@code none} by
 * 1.4% (blended at 256 bits), 8.9% (blended, preferred species), 5.9% (finish, 256 bits, where
 * the sum is above the excess) and 2.5% (finish, preferred species), and by 63% for the scalar
 * loop (+174 against +474 ns); the percentages are of mixed's excess over {@code none}, and one
 * run cannot attribute the leftover (289 ns for the blended arm at the preferred species).
 * ({@code common} is not the baseline: {@code ExpBenchmark} draws its common array without the
 * draws that place the rare inputs, so its ordinary values are other draws from the same range.)
 * Every kind has its rare lanes in the same vectors, 62 of the 256 four-lane vectors and 56 of
 * the 128 eight-lane ones. {@code ExpTest.kindsBenchInputsAreOneKindAtMixedsPlaces} pins the
 * counts and the rows' results.
 *
 * <p>{@code oneIn} is how rare the rare input is: one in 16, as in {@code ExpBenchmark}, unless a
 * run passes another ({@code -p oneIn=4,64}), to read whether a cost follows the number of vectors
 * that hold such a lane. At another rate the places are not {@code ExpBenchmark}'s, and {@code
 * common}, which has no rare input at any rate, is best left out ({@code -p
 * inputs=none,nan,overflow,underflow,subnormal}): its rows would repeat for each rate. {@code
 * bench/alloc-check.py} runs the rate of 16 only; at another, read {@code gc.alloc.rate.norm} in
 * the run itself.
 *
 * <p>The class is not named {@code *Benchmark}, on purpose, so that neither a run of {@code
 * ExpBenchmark} nor {@code bench/alloc-check.py}'s default regex picks it up: CI's check would
 * run exp's bodies a second time on the same common and mixed data, and never on the four kinds.
 * The rows call the bodies {@code ExpBenchmark}'s rows call, which the check covers. A run with
 * no regex, or with a method's name alone, does run these rows, and {@code -p inputs=...} applies
 * to every class the regex selects, where {@code ExpBenchmark} reads any value but {@code mixed}
 * as common: name this class alone when passing kinds. To check them on every kind:
 * {@code bench/alloc-check.py --include 'ExpKindsBench\.\w+(256|Preferred)$' --inputs
 * common,none,nan,overflow,underflow,subnormal,mixed}; and a run under {@code -prof gc} prints
 * {@code gc.alloc.rate.norm} for each kind, to be read before its time is.
 *
 * <p>The annotations' warmup and forks are {@code ExpBenchmark}'s, for a quick run. Times that are
 * to be quoted come from {@code bench/dispatch-bench.sh}, which applies the fixed method
 * (AGENTS.md); by hand that is {@code -f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc -jvmArgsAppend
 * -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}. Without the inline directive the
 * bodies may box, and the times are those of boxed bodies.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class ExpKindsBench {

  static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;

  @Param({"common", "none", "nan", "overflow", "underflow", "subnormal", "mixed"})
  public String inputs;

  @Param({"16"})
  public int oneIn;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    // The draws are ExpBenchmark's, in its order, so that common and mixed are its arrays and
    // every other kind has its rare inputs where mixed has them; none makes mixed's draws and
    // replaces nothing. The draw of the rare value is consumed for every kind but common, even
    // when it is not used: skipping it would shift every later draw.
    if (oneIn < 1) {
      throw new IllegalArgumentException("oneIn=" + oneIn + ": at least 1");
    }
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {Double.NaN, 800.0, -750.0, -708.5};
    double only =
        switch (inputs) {
          case "common", "none", "mixed" -> 0.0;
          case "nan" -> rare[0];
          case "overflow" -> rare[1];
          case "underflow" -> rare[2];
          case "subnormal" -> rare[3];
          default -> throw new IllegalArgumentException("inputs=" + inputs);
        };
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-20.0, 20.0);
      if (!inputs.equals("common") && random.nextInt(oneIn) == 0) {
        double mixed = rare[random.nextInt(rare.length)];
        if (!inputs.equals("none")) {
          in[i] = inputs.equals("mixed") ? mixed : only;
        }
      }
    }
  }

  /** A copy of the inputs, for {@code ExpTest}. */
  double[] inputs() {
    return in.clone();
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.exp(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S256, in, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      VFdlibm.exp(DoubleVector.fromArray(SP, in, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended256() {
    for (int i = 0; i < N; i += S256.length()) {
      Exp.expBlended(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Exp.expBlended(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
