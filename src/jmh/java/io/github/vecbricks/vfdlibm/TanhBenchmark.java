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
 * tanh over an array of 1024 doubles: the scalar {@code StrictMath.tanh} loop and {@link
 * Tanh#tanh}, the one arm ({@code VFdlibm.tanh} runs it, with every lane of an infinity or a NaN
 * finished per lane), at 256 and 512 bits and at the host's preferred species. 128 bits is left
 * out: on x86 C2 lowers no mask at two 64-bit lanes, so every 128-bit arm boxes (SKILLS.md). On a
 * host without 512-bit vectors the 512-bit benchmark runs in software and measures nothing
 * useful.
 *
 * <p>{@code inputs} picks the data: {@code common} draws from {@code (-25, 25)}, which holds both
 * forms of s_tanh.c ({@code |x| < 1} and from it on), with every {@code k} of the expm1 core up to
 * 63 on the lanes whose {@code t} is kept, and the saturated lanes from 22 up, which are computed
 * in the vector too (their core runs on {@code 2|x|} up to 50, {@code k} up to 72, and is
 * discarded); no lane takes the per-lane finish, and but by a chance of about 2^-60 none the tiny
 * return below {@code 2^-55}, whose operations every vector computes anyway. {@code mixed}
 * replaces one input in 16 with a rare one ({@code +inf}, {@code -inf} or a NaN, quiet or
 * signalling: the C's first branch, the one rare path), so many vectors hold one.
 *
 * <p>The vector form is larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed. The {@code *Max} row is at {@code
 * DoubleVector.SPECIES_MAX}, where the finish writes out its first eight lanes with constant
 * indices and a loop takes any past eight. No file under {@code bench/results/} reads these rows
 * yet.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class TanhBenchmark {

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
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {
      Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
      Double.longBitsToDouble(0x7ff0_0000_0000_0001L)
    };
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-25.0, 25.0);
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.tanh(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Tanh.tanh(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      Tanh.tanh(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Tanh.tanh(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Tanh.tanh(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
