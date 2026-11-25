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
 * hypot over two arrays of 1024 doubles ({@code x} and {@code y}): the scalar {@code
 * StrictMath.hypot} loop and {@link Hypot#hypot}, the one arm ({@code VFdlibm.hypot} runs it, with
 * every lane of an infinity or a NaN finished per lane), at 256 bits, at the host's preferred
 * species and at {@code SPECIES_MAX}. 128 bits is left out: on x86 C2 lowers no mask at two
 * 64-bit lanes, so every 128-bit arm boxes (SKILLS.md). bench/results/hypot-epyc7763-2026-11-23.txt
 * times it before #174 ordered hypot's arguments, a run and its re-run on the EPYC 7763: inside
 * 1.3x of the scalar loop at the preferred species, and finishMax about 1.6 times finishPreferred
 * on common data in both runs, the same code at the same width; hypot-xeon8573c-2026-11-24.txt
 * times that code too, one run on the Xeon 8573C. hypot-epyc7763-2026-11-24.txt times it after
 * #174, a run and its re-run on the EPYC 7763: the scalar loop lower on mixed data by 1.33 to 1.44
 * in both, no direction on common data, and finishMax at the level of the other rows.
 * hypot-order-pair-epyc7763-2026-11-24.txt pairs the code before and after #174 there, the vector
 * rows about 31% slower on mixed data, and hypot-harness-epyc7763-2026-11-24.txt times the same
 * body as a method of its own at the old level and inlined into this class's loop slower
 * (SKILLS.md, "What the hypot port taught"). hypot-xeon8573c-2026-11-24-2.txt times it after #174
 * on the Xeon 8573C, one run: the vector arm lower on common data by 2.13 to 2.16 at 512 bits, no
 * direction on mixed data.
 *
 * <p>{@code inputs} picks the data: {@code common} draws {@code x} and {@code y} from {@code
 * [-10, 10)}, Varka's baseline range, where every lane takes the medium path unscaled (both of
 * its forms) and none takes the per-lane finish; {@code mixed} replaces one pair in 16 with one
 * that takes another way out of the C: a NaN or an infinity in either argument (the per-lane
 * finish), a ratio past {@code 2^60}, a zero {@code b}, either scaling by {@code 2^600} and the
 * subnormal {@code b}, so many vectors hold one. Any other value is refused.
 *
 * <p>The vector form is larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. To measure the body inlined, add {@code -jvmArgsAppend
 * -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed. The {@code *Max} row is at {@code
 * DoubleVector.SPECIES_MAX}; {@link Hypot#hypot}'s finish writes out its first eight lanes with
 * constant indices, as the other ports' do. hypot has one arm, so there is no comparison to make
 * between arms.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class HypotBenchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> SMAX = DoubleVector.SPECIES_MAX;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] xs;
  private double[] ys;
  private double[] out;

  /** The pairs {@code mixed} puts in one place in 16: each takes another way out of the C. */
  private static final double[][] RARE = {
    {Double.NaN, 1.0}, {2.0, Double.NaN}, {Double.POSITIVE_INFINITY, 3.0},
    {-4.0, Double.NEGATIVE_INFINITY}, {Double.POSITIVE_INFINITY, Double.NaN},
    {5.0, 0x1p-70}, {0.0, -6.0}, {0x1p-1060, 0.0}, {0x1.8p600, -0x1p590},
    {0x1p-600, 0x1.4p-590}, {0x1p-1030, -0x1p-1040}, {0x1.8p-1060, 0x1p-1062},
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
    for (int i = 0; i < N; i++) {
      xs[i] = random.nextDouble(-10.0, 10.0);
      ys[i] = random.nextDouble(-10.0, 10.0);
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        double[] rare = RARE[random.nextInt(RARE.length)];
        xs[i] = rare[0];
        ys[i] = rare[1];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.hypot(xs[i], ys[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Hypot.hypot(DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Hypot.hypot(DoubleVector.fromArray(SP, xs, i), DoubleVector.fromArray(SP, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Hypot.hypot(DoubleVector.fromArray(SMAX, xs, i), DoubleVector.fromArray(SMAX, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }
}
