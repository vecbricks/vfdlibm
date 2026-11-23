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
 * cosh over an array of 1024 doubles: the scalar {@code StrictMath.cosh} loop and {@code
 * Cosh}'s two arms, {@link Cosh#coshSlice} (the small range, {@code |x| < 0.5*ln2}, computed in
 * the vector, the default arm) and {@link Cosh#coshFinish} (the small range
 * finished per lane), each vector form at 256 and 512 bits and at the host's preferred species,
 * and at {@code SPECIES_MAX}. 128 bits is left out: on x86 C2 lowers no mask at two 64-bit lanes,
 * so every 128-bit arm boxes (SKILLS.md). On a host without 512-bit vectors the 512-bit
 * benchmarks run in software and measure nothing useful.
 *
 * <p>{@code inputs} picks the data: {@code common} draws from {@code (-60, 60)}, where the small
 * range is 0.6% of the lanes, {@code half*t + half/t} 36% and {@code half*exp(|x|)} the rest, so
 * only that 0.6% (the lanes {@code coshFinish} finishes per lane) separates the two arms there;
 * {@code mixed} replaces one input in 16 with a rare one (the overflow window, overflow, an
 * infinity or a NaN, or {@code 0.2}, which only the finish arm finishes), so many vectors hold
 * one. The share of the small range, which is all the arms differ in, is in {@code
 * CoshKindsBench}.
 *
 * <p>The vector forms are larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed. The {@code *Max} rows are at {@code
 * DoubleVector.SPECIES_MAX}, where the finish writes out its first eight lanes with constant
 * indices and a loop takes any past eight; the rows read under 0.1 B/op in the laptop reading of
 * bench/results/max-finish-alloc-free-laptop-2026-11-21.txt (allocation only, no timing). No
 * file reads the finish they replaced. bench/results/cosh-xeon8573c-2026-11-21.txt (one run) and
 * cosh-epyc7763-2026-11-21.txt (a run and a re-run) read these rows; no file decides the arm.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class CoshBenchmark {

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
      710.2, -710.3, 800.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 0.2
    };
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-60.0, 60.0);
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.cosh(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] slice256() {
    for (int i = 0; i < N; i += S256.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] slice512() {
    for (int i = 0; i < N; i += S512.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] slicePreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] sliceMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
