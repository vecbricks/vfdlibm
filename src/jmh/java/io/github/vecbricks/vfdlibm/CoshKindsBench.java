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
 * cosh's two arms at a share of small-range lanes, to decide which of them {@link VFdlibm#cosh}
 * runs: the scalar {@code StrictMath.cosh} loop, {@link Cosh#coshSlice} (the small range, {@code
 * |x| < 0.5*ln2}, computed in the vector) and {@link Cosh#coshFinish} (finished per lane, the
 * default arm), each vector form at 256 bits and at the host's preferred species, over an array of
 * 1024 doubles. The arms differ only in that range, and {@code CoshBenchmark}'s {@code common} data
 * has 0.6% of its lanes there, which says little: a vector holds a small lane with probability
 * {@code 1 - (1-p)^n}, so at 3.5% 13% of the 4-lane and 25% of the 8-lane vectors do (the share of
 * Varka's baseline benchmark, {@code x} in [-10, 10)), and at 17% (the share of Spark's unit tests,
 * -2.0 to 2.0 in steps of 0.1) 52% and 77% (the survey on issue #32). Those are the shares in
 * theory; the exact placement below has 35 of the 256 four-lane vectors and 34 of the 128
 * eight-lane ones hold a small lane at 3.5% (14% and 27%), and 134 and 105 at 17% (52% and 82%).
 *
 * <p>{@code inputs} is {@code none} or {@code small}, and {@code percent} the share of the
 * places where {@code small} puts a small-range input; any other value is refused. Every lane
 * draws a magnitude from {@code [0.4, 20]} with a random sign, which is {@code half*t + half/t}
 * for both arms, and a small-range draw from {@code (-0.34, 0.34)}; exactly {@code
 * round(1024*percent/100)} places, picked by a shuffle, are the small ones. {@code none} keeps
 * the ordinary draw at a small place and {@code small} takes the small one, so the two differ at
 * those places only, and a row's time on {@code small} less its time on {@code none} is the
 * cost of the small lanes. {@code CoshTest.kindsBenchInputsAreSmallAtTheirPlaces} pins the
 * places and the rows' results.
 *
 * <p>The class is not named {@code *Benchmark}, on purpose, as {@code ExpKindsBench} is not, so
 * that {@code bench/alloc-check.py}'s default regex leaves it out: its rows call the bodies
 * {@code CoshBenchmark}'s rows call, which the check covers. A run with no regex, or with a
 * method's name alone, does run these rows. To check them: {@code bench/alloc-check.py --include
 * 'CoshKindsBench\.\w+(256|Preferred)$' --inputs none,small}.
 *
 * <p>The annotations' warmup and forks are {@code CoshBenchmark}'s, for a quick run. Times that
 * are to be quoted come from {@code bench/dispatch-bench.sh}, which applies the fixed method
 * (AGENTS.md); by hand that is {@code -f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc -jvmArgsAppend
 * -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>bench/results/cosh-kinds-xeon8573c-2026-11-21.txt (one run) and
 * cosh-kinds-epyc7763-2026-11-21.txt (a run and a re-run) read these rows, and give no direction
 * at either share; the finish arm is the default by the owner's decision of 23 November.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class CoshKindsBench {

  static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;

  @Param({"none", "small"})
  public String inputs;

  @Param({"3.5", "17"})
  public double percent;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    if (!inputs.equals("none") && !inputs.equals("small")) {
      throw new IllegalArgumentException("inputs=" + inputs);
    }
    if (!(percent > 0.0 && percent < 100.0)) {
      throw new IllegalArgumentException("percent=" + percent + ": above 0 and below 100");
    }
    SplittableRandom random = new SplittableRandom(1L);
    in = new double[N];
    out = new double[N];
    double[] ordinary = new double[N];
    double[] small = new double[N];
    for (int i = 0; i < N; i++) {
      ordinary[i] = random.nextDouble(0.4, 20.0) * (random.nextBoolean() ? 1.0 : -1.0);
      small[i] = random.nextDouble(-0.34, 0.34);
    }
    // The places: exactly round(N * percent / 100) of them, picked by a shuffle of the indices.
    int[] order = new int[N];
    for (int i = 0; i < N; i++) {
      order[i] = i;
    }
    for (int i = N - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      int swap = order[i];
      order[i] = order[j];
      order[j] = swap;
    }
    boolean[] place = new boolean[N];
    for (int i = 0; i < Math.round(N * percent / 100.0); i++) {
      place[order[i]] = true;
    }
    for (int i = 0; i < N; i++) {
      in[i] = place[i] && inputs.equals("small") ? small[i] : ordinary[i];
    }
  }

  /** A copy of the inputs, for {@code CoshTest}. */
  double[] inputs() {
    return in.clone();
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
  public double[] slicePreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
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
  public double[] finishPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
