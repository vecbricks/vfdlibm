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
 * pow over two arrays of 1024 doubles ({@code x} and {@code y}): the scalar {@code
 * StrictMath.pow} loop and {@link Pow#pow}, the one arm ({@code VFdlibm.pow} runs it, with every
 * lane off the common path finished per lane), at 256 bits, at the host's preferred species and
 * at {@code SPECIES_MAX}. 128 bits is left out: on x86 C2 lowers no mask at two 64-bit lanes, so
 * every 128-bit arm boxes (SKILLS.md). No results file times it yet.
 *
 * <p>{@code inputs} picks the data: {@code common} draws {@code x} from {@code [1e-6, 1e6)} and
 * {@code y} from {@code [-10, 10)}, Varka's baseline range, where every lane takes the common
 * path (setUp checks that none is a special {@code y}) and none takes the per-lane finish; {@code
 * mixed} replaces one pair in 16 with one that takes another way out of the C, from the arguments
 * (a NaN, an infinity, a zero, 1, a negative or subnormal {@code x}, a {@code y} of 0, 1, -1, 2
 * or 0.5, a {@code |y|} past {@code 2^31}) or from {@code z} (overflow, underflow and a subnormal
 * result), so many vectors hold one. Any other value is refused.
 *
 * <p>The vector form is larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. To measure the body inlined, add {@code -jvmArgsAppend
 * -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed. The {@code *Max} row is at {@code
 * DoubleVector.SPECIES_MAX}; {@link Pow#pow}'s finish writes out its first eight lanes with
 * constant indices, as the other ports' do. pow has one arm, so there is no comparison to make
 * between arms. The oracle row below is the check's baseline: the finish calls {@code
 * StrictMath.pow}, which allocates on some rare lanes itself.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class PowBenchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> SMAX = DoubleVector.SPECIES_MAX;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] xs;
  private double[] ys;
  private double[] out;
  /** The lanes {@code mixed} replaced from {@link #RARE}, which the finish sends to the oracle. */
  private boolean[] rare;

  /** The pairs {@code mixed} puts in one place in 16: each takes another way out of the C. */
  private static final double[][] RARE = {
    {Double.NaN, 1.5}, {2.0, Double.NaN}, {Double.POSITIVE_INFINITY, 3.0},
    {0.5, Double.NEGATIVE_INFINITY}, {0.0, 2.5}, {1.0, 7.25}, {-3.0, 3.0}, {-3.0, 0.75},
    {0x1p-1060, 0.5625}, {5.0, 0.0}, {5.0, 1.0}, {5.0, -1.0}, {5.0, 2.0}, {5.0, 0.5},
    {1.0000001, 0x1p40}, {10.0, 400.0}, {1e-6, 60.0}, {0.5, 1060.0},
  };

  @Setup
  public void setUp() {
    if (!inputs.equals("common") && !inputs.equals("mixed")) {
      throw new IllegalArgumentException("inputs is common or mixed, not '" + inputs + "'");
    }
    SplittableRandom random = new SplittableRandom(1L);
    xs = new double[N];
    ys = new double[N];
    out = new double[N];
    rare = new boolean[N];
    for (int i = 0; i < N; i++) {
      xs[i] = random.nextDouble(1e-6, 1e6);
      ys[i] = random.nextDouble(-10.0, 10.0);
      if (ys[i] == 1.0 || ys[i] == -1.0 || ys[i] == 2.0 || ys[i] == 0.5 || ys[i] == 0.0) {
        throw new IllegalStateException("a special y in the common data: " + ys[i]);
      }
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        double[] pick = RARE[random.nextInt(RARE.length)];
        xs[i] = pick[0];
        ys[i] = pick[1];
        this.rare[i] = true;
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.pow(xs[i], ys[i]);
    }
    return out;
  }

  /**
   * The oracle baseline for the allocation check, and no timing claim: {@code StrictMath.pow} on
   * the lanes {@link Pow#pow}'s per-lane finish computes ({@code mixed}'s pairs from {@link
   * #RARE}, each a lane its guard sends to the finish; {@code common} has none), over the same
   * arrays, into the same {@code out}. {@code StrictMath.pow} allocates on some of those lanes
   * itself (SKILLS.md, "What the pow port taught"), so the finish rows allocate as much as this
   * row does without boxing anything, and the check compares them with it.
   */
  @Benchmark
  public double[] rareOracle() {
    for (int i = 0; i < N; i++) {
      if (rare[i]) {
        out[i] = java.lang.StrictMath.pow(xs[i], ys[i]);
      }
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Pow.pow(DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Pow.pow(DoubleVector.fromArray(SP, xs, i), DoubleVector.fromArray(SP, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Pow.pow(DoubleVector.fromArray(SMAX, xs, i), DoubleVector.fromArray(SMAX, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }
}
