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
import org.openjdk.jmh.annotations.CompilerControl;
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

  /**
   * Doubles of a spacer array allocated between xs, ys and out: 0 keeps the three arrays
   * back to back in the TLAB, so out sits 2 * 8208 bytes, 32 mod 4096, past xs.
   */
  @Param({"0"})
  public int gap;

  /** Doubles of a spacer array allocated before xs (0: none), which moves all three arrays. */
  @Param({"0"})
  public int pre;

  private double[] spacer0;

  private double[] xs;
  private double[] ys;
  private double[] out;
  private double[] spacer1;
  private double[] spacer2;

  private static final double[][] RARE = {
    {Double.NaN, 1.0}, {2.0, Double.NaN}, {Double.POSITIVE_INFINITY, 3.0},
    {-4.0, Double.NEGATIVE_INFINITY}, {Double.POSITIVE_INFINITY, Double.NaN},
    {5.0, 0x1p-70}, {0.0, -6.0}, {0x1p-1060, 0.0}, {0x1.8p600, -0x1p590},
    {0x1p-600, 0x1.4p-590}, {0x1p-1030, -0x1p-1040}, {0x1.8p-1060, 0x1p-1062},
  };

  @Setup
  public void setUp() {
    int only = inputs.matches("k([0-9]|1[01])") ? Integer.parseInt(inputs.substring(1)) : -1;
    if (!inputs.equals("common") && !inputs.equals("mixed") && only < 0) {
      throw new IllegalArgumentException("inputs is common or mixed, not '" + inputs + "'");
    }
    SplittableRandom random = new SplittableRandom(1L);
    spacer0 = pre > 0 ? new double[pre] : null;
    xs = new double[N];
    spacer1 = gap > 0 ? new double[gap] : null;
    ys = new double[N];
    spacer2 = gap > 0 ? new double[gap] : null;
    out = new double[N];
    if (Boolean.getBoolean("diag.addr")) {
      System.out.println("diag.addr xs=" + Addr.of(xs) + " ys=" + Addr.of(ys) + " out=" + Addr.of(out)
          + " ys-xs mod 4096=" + Math.floorMod(Addr.of(ys) - Addr.of(xs), 4096)
          + " out-xs mod 4096=" + Math.floorMod(Addr.of(out) - Addr.of(xs), 4096)
          + " xs mod 4096=" + Math.floorMod(Addr.of(xs), 4096)
          + " data mod 32: xs=" + Math.floorMod(Addr.of(xs) + 16, 32) + " ys="
          + Math.floorMod(Addr.of(ys) + 16, 32) + " out=" + Math.floorMod(Addr.of(out) + 16, 32));
    }
    for (int i = 0; i < N; i++) {
      xs[i] = random.nextDouble(-10.0, 10.0);
      ys[i] = random.nextDouble(-10.0, 10.0);
      if (!inputs.equals("common") && random.nextInt(16) == 0) {
        int pick = random.nextInt(RARE.length);
        double[] rare = RARE[only < 0 ? pick : only];
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



  /** main's loop as a method of its own, never inlined, so the rows below call one compiled loop. */
  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  double[] loopMain() {
    for (int i = 0; i < N; i += S256.length()) {
      Hypot.hypot(DoubleVector.fromArray(S256, xs, i), DoubleVector.fromArray(S256, ys, i), null)
          .intoArray(out, i);
    }
    return out;
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  double[] pad1() {
    return loopMain();
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  double[] pad2() {
    return pad1();
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  double[] pad3() {
    return pad2();
  }

  /** main's loop under JMH's sample mode: same body, another harness loop around it. */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  public double[] mainSample() {
    return main();
  }

  /** The old code under JMH's sample mode. */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  public double[] oldSample() {
    return old();
  }

  /** The same compiled loop from four stack depths: does the stack offset move it? */
  @Benchmark
  public double[] depth0() {
    return loopMain();
  }

  @Benchmark
  public double[] depth1() {
    return pad1();
  }

  @Benchmark
  public double[] depth2() {
    return pad2();
  }

  @Benchmark
  public double[] depth3() {
    return pad3();
  }
}
