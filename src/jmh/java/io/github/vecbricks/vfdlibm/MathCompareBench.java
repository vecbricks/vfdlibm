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
 * exp, log and pow over arrays of 1024 doubles three ways each, in one run: the scalar {@code
 * StrictMath} loop ({@code <fn>StrictMath}), the same loop over {@code java.lang.Math} ({@code
 * <fn>Math}) and vfdlibm's default arm at the host's preferred species ({@code <fn>Preferred}),
 * the body that the {@code finishPreferred} row of {@code ExpBenchmark}, {@code LogBenchmark} and
 * {@code PowBenchmark} calls, in the same loop.
 *
 * <p>{@code Math} promises 1 ulp of the exact result and lets the JIT use intrinsics, so its
 * bits may differ from {@code StrictMath}'s on some inputs and from one JVM or CPU to another.
 * {@code StrictMath} and vfdlibm give one result everywhere. These rows give the time of {@code
 * Math}'s freedom beside the time of {@code StrictMath}'s bits, scalar and vector: {@code
 * MathCompareBenchTest} holds the {@code StrictMath} and vfdlibm rows to {@code StrictMath}'s raw
 * bits, and the {@code Math} rows to {@code StrictMath}'s sign and 3 steps of its bits (a NaN for
 * a NaN), not to its bits.
 *
 * <p>{@code inputs} picks the data, and each function's arrays are its {@code *Benchmark}'s, drawn
 * the same way from a {@code SplittableRandom} of its own, which {@code MathCompareBenchTest} pins
 * by raw bits: {@code common}, where no lane takes vfdlibm's rare path, and {@code mixed}, where
 * one input in 16 (one pair, for pow) is replaced by one that does. {@code setUp} throws on any
 * other value, which {@code MathCompareBenchTest.setUpThrowsOnAnUnknownInputsValue} pins, so that
 * a row named for a value cannot run on another's data.
 *
 * <p>The class is not named {@code *Benchmark}, on purpose: {@code bench/alloc-check.py}'s default
 * regex and {@code bench/readme-table.py} leave it out, and the code under every {@code
 * *Benchmark}, by {@code bench/record.py}'s rule, stays what it was. Its {@code Preferred} rows
 * call the bodies that CI's check covers. They are checked here by naming the class: {@code
 * bench/alloc-check.py --include 'MathCompareBench\.(exp|log)Preferred$' --inputs common,mixed}
 * and {@code bench/alloc-check.py --include 'MathCompareBench\.powPreferred$' --inputs common}.
 * pow is left to {@code common} because {@code StrictMath.pow} allocates on some of {@code
 * mixed}'s lanes itself, and the check knows an oracle baseline ({@code rareOracle}) in a {@code
 * *Benchmark} class only: by its rule {@code powPreferred} on {@code mixed} would read that
 * allocation as a box. Its body on {@code mixed} is {@code PowBenchmark.finishPreferred}'s, which
 * CI holds to {@code PowBenchmark.rareOracle}. A run under {@code -prof gc} prints {@code
 * gc.alloc.rate.norm} for every row, to be read before its time is.
 *
 * <p>The annotations' warmup and forks are the {@code *Benchmark} classes', for a quick run. Times
 * that are to be quoted come from {@code bench/dispatch-bench.sh} with the regex {@code
 * MathCompareBench}, which applies the fixed method (AGENTS.md); by hand that is {@code -f 2 -wi 3
 * -w 2s -i 5 -r 2s -prof gc -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 * Without the inline directive vfdlibm's bodies may box, and their times are those of a boxed body.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class MathCompareBench {

  static final int N = 1024;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;

  /** {@code PowBenchmark}'s rare pairs, in its order: each takes another way out of the C. */
  private static final double[][] POW_RARE = {
    {Double.NaN, 1.5}, {2.0, Double.NaN}, {Double.POSITIVE_INFINITY, 3.0},
    {0.5, Double.NEGATIVE_INFINITY}, {0.0, 2.5}, {1.0, 7.25}, {-3.0, 3.0}, {-3.0, 0.75},
    {0x1p-1060, 0.5625}, {5.0, 0.0}, {5.0, 1.0}, {5.0, -1.0}, {5.0, 2.0}, {5.0, 0.5},
    {1.0000001, 0x1p40}, {10.0, 400.0}, {1e-6, 60.0}, {0.5, 1060.0},
  };

  @Param({"common", "mixed"})
  public String inputs;

  private double[] expIn;
  private double[] logIn;
  private double[] powX;
  private double[] powY;
  private double[] out;

  @Setup
  public void setUp() {
    if (!inputs.equals("common") && !inputs.equals("mixed")) {
      throw new IllegalArgumentException("inputs=" + inputs + ": common or mixed");
    }
    boolean mixed = inputs.equals("mixed");
    out = new double[N];

    // ExpBenchmark's draws, in its order.
    SplittableRandom random = new SplittableRandom(1L);
    double[] expRare = {Double.NaN, 800.0, -750.0, -708.5};
    expIn = new double[N];
    for (int i = 0; i < N; i++) {
      expIn[i] = random.nextDouble(-20.0, 20.0);
      if (mixed && random.nextInt(16) == 0) {
        expIn[i] = expRare[random.nextInt(expRare.length)];
      }
    }

    // LogBenchmark's draws, in its order.
    random = new SplittableRandom(1L);
    double[] logRare = {0.0, -1.5, Double.NaN, 1.0e-310, Double.POSITIVE_INFINITY};
    logIn = new double[N];
    for (int i = 0; i < N; i++) {
      long exponent = random.nextInt(-30, 31) + 1023L;
      logIn[i] = Double.longBitsToDouble(
          (exponent << 52) | (random.nextLong() & 0x000f_ffff_ffff_ffffL));
      if (mixed && random.nextInt(16) == 0) {
        logIn[i] = logRare[random.nextInt(logRare.length)];
      }
    }

    // PowBenchmark's draws, in its order.
    random = new SplittableRandom(1L);
    powX = new double[N];
    powY = new double[N];
    for (int i = 0; i < N; i++) {
      powX[i] = random.nextDouble(1e-6, 1e6);
      powY[i] = random.nextDouble(-10.0, 10.0);
      if (powY[i] == 1.0 || powY[i] == -1.0 || powY[i] == 2.0 || powY[i] == 0.5
          || powY[i] == 0.0) {
        throw new IllegalStateException("a special y in the common data: " + powY[i]);
      }
      if (mixed && random.nextInt(16) == 0) {
        double[] pick = POW_RARE[random.nextInt(POW_RARE.length)];
        powX[i] = pick[0];
        powY[i] = pick[1];
      }
    }
  }

  /** Copies of the inputs, for {@code MathCompareBenchTest}: exp's, log's, pow's x and y. */
  double[][] inputs() {
    return new double[][] {expIn.clone(), logIn.clone(), powX.clone(), powY.clone()};
  }

  @Benchmark
  public double[] expStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.exp(expIn[i]);
    }
    return out;
  }

  @Benchmark
  public double[] expMath() {
    for (int i = 0; i < N; i++) {
      out[i] = Math.exp(expIn[i]);
    }
    return out;
  }

  @Benchmark
  public double[] expPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      VFdlibm.exp(DoubleVector.fromArray(SP, expIn, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] logStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.log(logIn[i]);
    }
    return out;
  }

  @Benchmark
  public double[] logMath() {
    for (int i = 0; i < N; i++) {
      out[i] = Math.log(logIn[i]);
    }
    return out;
  }

  @Benchmark
  public double[] logPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Log.log(DoubleVector.fromArray(SP, logIn, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] powStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.pow(powX[i], powY[i]);
    }
    return out;
  }

  @Benchmark
  public double[] powMath() {
    for (int i = 0; i < N; i++) {
      out[i] = Math.pow(powX[i], powY[i]);
    }
    return out;
  }

  @Benchmark
  public double[] powPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Pow.pow(DoubleVector.fromArray(SP, powX, i), DoubleVector.fromArray(SP, powY, i), null)
          .intoArray(out, i);
    }
    return out;
  }
}
