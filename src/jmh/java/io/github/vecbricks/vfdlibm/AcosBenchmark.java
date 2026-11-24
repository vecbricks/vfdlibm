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
 * acos over an array of 1024 doubles: the scalar {@code StrictMath.acos} loop and {@link
 * Acos#acos}, the one arm ({@code VFdlibm.acos} runs it, with every lane of {@code |x| >= 1}
 * finished per lane), at 256 and 512 bits, at the host's preferred species and at {@code
 * SPECIES_MAX}. 128 bits is left out: on x86 C2 lowers no mask at two 64-bit lanes, so every
 * 128-bit arm boxes (SKILLS.md). On a host without 512-bit vectors the 512-bit benchmark runs in
 * software and measures nothing useful.
 *
 * <p>{@code inputs} picks the data: {@code common} draws from {@code (-0.99, 0.99)}, which holds
 * all three of e_acos.c's ranges below 1 (the first form below {@code 0.5} in magnitude, about
 * 51% of the draws, the form of {@code x <= -0.5}, about 24%, and the form of {@code x >= 0.5},
 * about 24%) and, with one draw in 64, a lane below {@code 2^-28} in magnitude (down to about
 * {@code 2^-59}), some of them in the constant return ({@code ix <= 0x3c600000}); no lane takes the
 * per-lane finish. {@code mixed} replaces one input in 16 with a rare one (1, -1, 1.5, -2,
 * 1e300, {@code +inf}, {@code -inf} or a NaN: exactly 1, above 1, infinities and NaN), so many
 * vectors hold one. {@code setUp} throws on any other value.
 *
 * <p>The vector form is larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed. bench/results/acos-xeon8573c-2026-11-22.txt and
 * acos-epyc7763-2026-11-22.txt read these rows, a run and its re-run on each host. acos has one arm, so there is no comparison to make between arms.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class AcosBenchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> S512 = DoubleVector.SPECIES_512;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> SMAX = DoubleVector.SPECIES_MAX;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    if (!inputs.equals("common") && !inputs.equals("mixed")) {
      throw new IllegalArgumentException("inputs: " + inputs);
    }
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {
      1.0, -1.0, 1.5, -2.0, 1e300, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN
    };
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-0.99, 0.99);
      if (random.nextInt(64) == 0) {
        in[i] = Math.scalb(random.nextDouble(-1.0, 1.0), -random.nextInt(28, 60));
      }
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.acos(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Acos.acos(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      Acos.acos(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Acos.acos(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Acos.acos(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
