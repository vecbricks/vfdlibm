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
 * atan2 over two arrays of 1024 doubles ({@code y} and {@code x}): the scalar {@code
 * StrictMath.atan2} loop and the two arms of {@link Atan2}, {@code blended} (the special lanes'
 * blends in every vector) and {@code guarded} (behind one test; {@code VFdlibm.atan2} runs it),
 * at 256 bits, at the host's preferred species and at {@code SPECIES_MAX}. 128 bits is left out:
 * on x86 C2 lowers no mask at two 64-bit lanes, so every 128-bit arm boxes (SKILLS.md). {@code
 * bench/alloc-check.py} reads it for what the bodies allocate, and
 * bench/results/atan2-xeon8573c-2026-11-22.txt and atan2-epyc7763-2026-11-22.txt time it, a run and
 * a re-run on each host, which give the two arms no direction.
 *
 * <p>{@code inputs} picks the data: {@code common} draws {@code y} and {@code x} from {@code
 * (-4, 4)}, where no lane is special (NaN, zero, infinite), none has {@code x = 1.0} and none
 * takes a shortcut of {@code k}; {@code mixed} replaces one pair in 16 with one that takes a
 * special lane or a shortcut (a NaN, a zero, an infinity, {@code x = 1.0} beside a huge {@code
 * y}, a ratio past {@code 2^60} in either direction), so many vectors hold one. Any other value
 * is refused.
 *
 * <p>The vector form is larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. To measure the body inlined, add {@code -jvmArgsAppend
 * -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256}, {@code
 * *Preferred} and {@code *Max} on {@code common} and {@code mixed} inputs and fails if any
 * allocates, which means the body boxed.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class Atan2Benchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> SMAX = DoubleVector.SPECIES_MAX;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] ys;
  private double[] xs;
  private double[] out;

  /** The pairs {@code mixed} puts in one place in 16: each takes a special lane or a shortcut. */
  private static final double[][] RARE = {
    {Double.NaN, 1.0}, {1.0, Double.NaN}, {0.0, 1.0}, {-0.0, -1.0}, {1.0, 0.0},
    {Double.POSITIVE_INFINITY, 2.0}, {3.0, Double.NEGATIVE_INFINITY},
    {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}, {0x1p70, 1.0}, {-0x1p62, 1.0},
    {1.0e300, 1.0e-10}, {1.0e-300, -1.0e10},
  };

  @Setup
  public void setUp() {
    if (!inputs.equals("common") && !inputs.equals("mixed")) {
      throw new IllegalArgumentException("inputs is common or mixed, not '" + inputs + "'");
    }
    SplittableRandom random = new SplittableRandom(1L);
    ys = new double[N];
    xs = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      ys[i] = random.nextDouble(-4.0, 4.0);
      xs[i] = random.nextDouble(-4.0, 4.0);
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        double[] rare = RARE[random.nextInt(RARE.length)];
        ys[i] = rare[0];
        xs[i] = rare[1];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.atan2(ys[i], xs[i]);
    }
    return out;
  }

  @Benchmark
  public double[] blended256() {
    for (int i = 0; i < N; i += S256.length()) {
      Atan2.atan2Blended(DoubleVector.fromArray(S256, ys, i), DoubleVector.fromArray(S256, xs, i),
          null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Atan2.atan2Blended(DoubleVector.fromArray(SP, ys, i), DoubleVector.fromArray(SP, xs, i),
          null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Atan2.atan2Blended(DoubleVector.fromArray(SMAX, ys, i),
          DoubleVector.fromArray(SMAX, xs, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] guarded256() {
    for (int i = 0; i < N; i += S256.length()) {
      Atan2.atan2Guarded(DoubleVector.fromArray(S256, ys, i), DoubleVector.fromArray(S256, xs, i),
          null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] guardedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Atan2.atan2Guarded(DoubleVector.fromArray(SP, ys, i), DoubleVector.fromArray(SP, xs, i),
          null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] guardedMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Atan2.atan2Guarded(DoubleVector.fromArray(SMAX, ys, i),
          DoubleVector.fromArray(SMAX, xs, i), null).intoArray(out, i);
    }
    return out;
  }
}
