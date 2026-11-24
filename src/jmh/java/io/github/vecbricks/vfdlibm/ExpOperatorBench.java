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
import jdk.incubator.vector.VectorOperators;
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
 * exp over an array of 1024 doubles three ways, in one run: the scalar {@code StrictMath.exp}
 * loop, {@link VFdlibm#exp} (its default arm, rare lanes finished per lane, as {@code
 * ExpBenchmark}'s {@code finish} rows call it) and the Vector API's own operator, {@code
 * lanewise(VectorOperators.EXP)}, each vector form at 256 bits, at the host's preferred species
 * and at {@code SPECIES_MAX}.
 *
 * <p>The operator promises {@code Math}'s accuracy, not {@code StrictMath}'s bits, so its rows
 * return other bits than the other two on some lanes, by design: no test compares them. How
 * often, on a laptop, is in {@code
 * docs/blog/strictmath-vs-libm/data/operator-exp-jdk-25.0.5.1-laptop.txt}. These rows give the
 * time that difference buys or costs, beside the time of {@code StrictMath}'s bits.
 *
 * <p>{@code inputs} picks the data, and the arrays are {@code ExpBenchmark}'s, drawn the same
 * way: {@code common} draws from {@code [-20, 20]}, where no lane takes vfdlibm's rare path;
 * {@code mixed} replaces one input in 16 with a NaN, 800, -750 or -708.5, so many vectors hold a
 * rare lane. {@code setUp} throws on any other value, which {@code
 * ExpOperatorBenchTest.setUpThrowsOnAnUnknownInputsValue} pins, so that a row named for a value
 * cannot run on another's data.
 *
 * <p>The class is not named {@code *Benchmark}, on purpose: {@code bench/alloc-check.py}'s
 * default regex and {@code bench/readme-table.py} leave it out, and the code under {@code
 * ExpBenchmark}, by {@code bench/record.py}'s rule, stays what it was. Its {@code finish} rows
 * call the body that {@code ExpBenchmark}'s rows call, which CI's check covers. The operator's
 * rows are checked by naming the class: {@code bench/alloc-check.py --include
 * 'ExpOperatorBench\.\w+(256|Preferred|Max)$' --inputs common,mixed}, and a run under {@code -prof
 * gc} prints {@code gc.alloc.rate.norm} for every row, to be read before its time is.
 *
 * <p>The annotations' warmup and forks are {@code ExpBenchmark}'s, for a quick run. Times that are
 * to be quoted come from {@code bench/dispatch-bench.sh} with the regex {@code ExpOperatorBench},
 * which applies the fixed method (AGENTS.md); by hand that is {@code -f 2 -wi 3 -w 2s -i 5 -r 2s
 * -prof gc -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}. Without
 * the inline directive vfdlibm's body may box, and its times are those of a boxed body.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class ExpOperatorBench {

  static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> SMAX = DoubleVector.SPECIES_MAX;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    // ExpBenchmark's draws, in its order, so that common and mixed are its arrays.
    if (!inputs.equals("common") && !inputs.equals("mixed")) {
      throw new IllegalArgumentException("inputs=" + inputs + ": common or mixed");
    }
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {Double.NaN, 800.0, -750.0, -708.5};
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-20.0, 20.0);
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  /** A copy of the inputs, for {@code ExpOperatorBenchTest}. */
  double[] inputs() {
    return in.clone();
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.exp(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish256() {
    for (int i = 0; i < N; i += S256.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S256, in, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      VFdlibm.exp(DoubleVector.fromArray(SP, in, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] finishMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      VFdlibm.exp(DoubleVector.fromArray(SMAX, in, i)).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] operator256() {
    for (int i = 0; i < N; i += S256.length()) {
      DoubleVector.fromArray(S256, in, i).lanewise(VectorOperators.EXP).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] operatorPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      DoubleVector.fromArray(SP, in, i).lanewise(VectorOperators.EXP).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] operatorMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      DoubleVector.fromArray(SMAX, in, i).lanewise(VectorOperators.EXP).intoArray(out, i);
    }
    return out;
  }
}
