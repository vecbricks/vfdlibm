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
 * log over an array of 1024 doubles: the scalar {@code StrictMath.log} loop and log's two arms,
 * {@link Log#log} (rare lanes finished per lane, the arm {@link VFdlibm#log} uses) and {@link
 * Log#logBlended} (every branch blended), each at 128, 256 and 512 bits and at the host's
 * preferred species. On a host without 512-bit vectors the 512-bit benchmarks run in software and
 * measure nothing useful.
 *
 * <p>{@code inputs} picks the data: {@code common} draws positive normal doubles whose binary
 * exponents are uniform over {@code [-30, 30]}, with random significands, so every common branch
 * of e_log.c runs ({@code 1+f} on both sides of the sqrt(2) split, the {@code i > 0} band and
 * outside it) and no lane is rare; {@code mixed} replaces one input in 16 with a rare one (zero,
 * a negative, a NaN, a subnormal, +inf), so many vectors hold one.
 *
 * <p>The vector forms are larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256} and
 * {@code *Preferred} on {@code common} inputs and fails if any allocates, which means the body
 * boxed. A new arm named that way is checked with no further change.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class LogBenchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S128 = DoubleVector.SPECIES_128;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> S512 = DoubleVector.SPECIES_512;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {0.0, -1.5, Double.NaN, 1.0e-310, Double.POSITIVE_INFINITY};
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      long exponent = random.nextInt(-30, 31) + 1023L;
      in[i] = Double.longBitsToDouble(
          (exponent << 52) | (random.nextLong() & 0x000f_ffff_ffff_ffffL));
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.log(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish128() {
    for (int i = 0; i < N; i += S128.length()) {
      Log.log(DoubleVector.fromArray(S128, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Log.log(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      Log.log(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Log.log(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended128() {
    for (int i = 0; i < N; i += S128.length()) {
      Log.logBlended(DoubleVector.fromArray(S128, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended256() {
    for (int i = 0; i < N; i += S256.length()) {
      Log.logBlended(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended512() {
    for (int i = 0; i < N; i += S512.length()) {
      Log.logBlended(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Log.logBlended(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
