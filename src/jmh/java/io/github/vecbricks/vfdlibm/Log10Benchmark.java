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
 * log10 over an array of 1024 doubles: the scalar {@code StrictMath.log10} loop and log10's two
 * arms, {@link Log10#log10} (rare lanes finished per lane, the arm {@link VFdlibm#log10} uses) and
 * {@link Log10#log10Blended} (every branch blended), each at 256 and 512 bits, at the host's
 * preferred species and at {@code SPECIES_MAX}. On a host without 512-bit vectors the 512-bit
 * benchmarks run in software and measure nothing useful. 128 bits is left out: on x86 C2 lowers
 * no mask at two 64-bit lanes, so those rows measure a per-lane fallback (SKILLS.md).
 *
 * <p>{@code inputs} picks the data, as {@code LogBenchmark}'s: {@code common} draws positive
 * normal doubles whose binary exponents are uniform over {@code [-30, 30]}, with random
 * significands, so both sides of e_log10.c's split at 1 ({@code k < 0} and {@code k >= 0}) and
 * every common branch of e_log.c on the rescaled {@code x} run, and no lane is rare; {@code mixed}
 * replaces one input in 16 with a rare one (zero, a negative, a NaN, a subnormal, +inf), so many
 * vectors hold one.
 *
 * <p>The vector forms are larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256} and
 * {@code *Preferred} on {@code common} and {@code mixed} inputs and fails if any allocates, which
 * means the body boxed. The {@code *Max} rows are not checked: at {@code SPECIES_MAX} C2 does not
 * intrinsify lane access at a variable index, so a finish arm may box there on mixed data
 * (SKILLS.md); they are here to measure that.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class Log10Benchmark {

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
      out[i] = StrictMath.log10(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      Log10.log10(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      Log10.log10(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Log10.log10(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Log10.log10(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended256() {
    for (int i = 0; i < N; i += S256.length()) {
      Log10.log10Blended(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended512() {
    for (int i = 0; i < N; i += S512.length()) {
      Log10.log10Blended(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Log10.log10Blended(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Log10.log10Blended(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
