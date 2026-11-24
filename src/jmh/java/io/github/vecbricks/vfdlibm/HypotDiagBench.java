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
 * Diagnostic only, on a scratch branch that is never merged: which part of #174's change to
 * hypot's argument order costs the mixed rows about 31% on the EPYC 7763. HypotBenchmark's
 * data and loop, at 256 bits, over the code before #174 ({@link HypotOld}), main's, and
 * variants between them; two rows run main's code with a JVM option of their own.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class HypotDiagBench {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] xs;
  private double[] ys;
  private double[] out;

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

  /** The code before #174: high-word compare, int words blended, doubles blended. */
  @Benchmark
  public double[] old() {
    for (int i = 0; i < N; i += S256.length()) {
      HypotOld.hypot(DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  /** main's code: long compare, long blends, ha and hb read from the swapped values. */
  @Benchmark
  public double[] main() {
    for (int i = 0; i < N; i += S256.length()) {
      Hypot.hypot(DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  /** main's structure with the high-word compare (the planted fault; same order on this data). */
  @Benchmark
  @Fork(value = 1, jvmArgsPrepend = {"--add-modules", "jdk.incubator.vector",
      "-Dvfdlibm.fault=hypot.highWordOrder"})
  public double[] mainHighWordCompare() {
    return main();
  }

  /** The old structure with only the compare made the long one. */
  @Benchmark
  public double[] intWords() {
    for (int i = 0; i < N; i += S256.length()) {
      HypotIntWords.hypot(
              DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  /** main's long blends, ha and hb blended from the int words as before. */
  @Benchmark
  public double[] intWordsLongBlend() {
    for (int i = 0; i < N; i += S256.length()) {
      HypotIntWordsLongBlend.hypot(
              DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  /** main's words from the swapped values, with the swap blending the doubles. */
  @Benchmark
  public double[] doubleBlend() {
    for (int i = 0; i < N; i += S256.length()) {
      HypotDoubleBlend.hypot(
              DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  /** main's code with loops aligned to 64 bytes: does code layout move it? */
  @Benchmark
  @Fork(value = 1, jvmArgsPrepend = {"--add-modules", "jdk.incubator.vector",
      "-XX:OptoLoopAlignment=64"})
  public double[] mainLoopAlign64() {
    return main();
  }

  /** The old code with loops aligned to 64 bytes. */
  @Benchmark
  @Fork(value = 1, jvmArgsPrepend = {"--add-modules", "jdk.incubator.vector",
      "-XX:OptoLoopAlignment=64"})
  public double[] oldLoopAlign64() {
    return old();
  }
}
