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
import jdk.incubator.vector.VectorMask;
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
 * exp over an array of 1024 doubles: the scalar {@code StrictMath.exp} loop, {@link VFdlibm#exp}
 * (rare lanes finished per lane) and the all-blended arm {@link Exp#expBlended}, each vector form
 * at 128, 256 and 512 bits, at the host's preferred species and at {@code SPECIES_MAX}. On a host
 * without 512-bit vectors the 512-bit benchmarks run in software and measure nothing useful.
 *
 * <p>{@code inputs} picks the data: {@code common} draws from {@code [-20, 20]}, where no lane
 * takes a rare path; {@code mixed} replaces one input in 16 with a rare one (a NaN, an overflow,
 * an underflow, a subnormal result), so many vectors hold one.
 *
 * <p>The vector forms are larger than C2 inlines by default ({@code FreqInlineSize}), so a call
 * from the loop may pass boxed vectors. Varka emits the sequence inline instead; to measure that,
 * add {@code -jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*}.
 *
 * <p>{@code bench/alloc-check.py} runs the vector benchmarks named {@code *256} and
 * {@code *Preferred} on {@code common} and {@code mixed} inputs and fails if any allocates, which
 * means the body boxed. A new arm named that way is checked with no further change. The
 * {@code *Max} rows are not checked: at {@code SPECIES_MAX} the finish arm boxes on mixed data
 * (see {@code bench/alloc-check.py}); they are here to record that.
 *
 * <p>The {@code finishPair} rows are an experiment for code generators: exp's body is bound by
 * latency, not by issue width, so each iteration loads two independent vectors, computes both
 * branch-free cores ({@link Exp#expCore}) and rare masks ({@link Exp#expRare}), and runs one
 * per-lane finish, written out in the loop as a code generator would emit it, only if either
 * vector has a rare lane. They return exactly {@link VFdlibm#exp}'s results ({@code
 * ExpTest.finishPairRowsMatchVFdlibmExp}). Two bodies in one loop pass C2's inlining budget, and
 * on the laptop, default flags and {@code -XX:UseAVX=2} alike, they box: 59392 B/op on common
 * data at 256 bits. {@code PrintInlining} shows the budget spent inside the first {@code
 * expCore}, after both {@code expRare} calls: its {@code Lanes} calls and then the second {@code
 * expCore} are refused with {@code NodeCountInliningCutoff}. HotSpot refuses there any callee
 * over {@code MaxTrivialSize} (6 bytes) that is not {@code @ForceInline}, which only the JDK's
 * own Vector API methods are and which are delayed rather than refused
 * ({@code opto/bytecodeInfo.cpp}); {@code CompileCommand=inline} does not lift it. So as written
 * these rows fail {@code bench/alloc-check.py}; a pair stays unboxed only with no vfdlibm call
 * in it, the body emitted inline as Varka emits it (SKILLS.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class ExpBenchmark {

  static final int N = 1024;
  private static final VectorSpecies<Double> S128 = DoubleVector.SPECIES_128;
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

  /** Replaces the inputs, for {@code ExpTest}; {@code xs} has {@code N} doubles. */
  void setInputs(double[] xs) {
    in = xs.clone();
    out = new double[N];
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.exp(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] finish128() {
    for (int i = 0; i < N; i += S128.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S128, in, i)).intoArray(out, i);
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
  public double[] finish512() {
    for (int i = 0; i < N; i += S512.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S512, in, i)).intoArray(out, i);
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
  public double[] finishPair256() {
    int n = S256.length();
    for (int i = 0; i < N; i += 2 * n) {
      DoubleVector a = DoubleVector.fromArray(S256, in, i);
      DoubleVector b = DoubleVector.fromArray(S256, in, i + n);
      VectorMask<Double> rareA = Exp.expRare(a);
      VectorMask<Double> rareB = Exp.expRare(b);
      DoubleVector ya = Exp.expCore(a);
      DoubleVector yb = Exp.expCore(b);
      if (rareA.or(rareB).anyTrue()) {
        long lanes = rareA.toLong();
        while (lanes != 0) {
          int lane = Long.numberOfTrailingZeros(lanes);
          lanes &= lanes - 1;
          ya = ya.withLane(lane, StrictMath.exp(a.lane(lane)));
        }
        lanes = rareB.toLong();
        while (lanes != 0) {
          int lane = Long.numberOfTrailingZeros(lanes);
          lanes &= lanes - 1;
          yb = yb.withLane(lane, StrictMath.exp(b.lane(lane)));
        }
      }
      ya.intoArray(out, i);
      yb.intoArray(out, i + n);
    }
    return out;
  }

  @Benchmark
  public double[] finishPairPreferred() {
    int n = SP.length();
    for (int i = 0; i < N; i += 2 * n) {
      DoubleVector a = DoubleVector.fromArray(SP, in, i);
      DoubleVector b = DoubleVector.fromArray(SP, in, i + n);
      VectorMask<Double> rareA = Exp.expRare(a);
      VectorMask<Double> rareB = Exp.expRare(b);
      DoubleVector ya = Exp.expCore(a);
      DoubleVector yb = Exp.expCore(b);
      if (rareA.or(rareB).anyTrue()) {
        long lanes = rareA.toLong();
        while (lanes != 0) {
          int lane = Long.numberOfTrailingZeros(lanes);
          lanes &= lanes - 1;
          ya = ya.withLane(lane, StrictMath.exp(a.lane(lane)));
        }
        lanes = rareB.toLong();
        while (lanes != 0) {
          int lane = Long.numberOfTrailingZeros(lanes);
          lanes &= lanes - 1;
          yb = yb.withLane(lane, StrictMath.exp(b.lane(lane)));
        }
      }
      ya.intoArray(out, i);
      yb.intoArray(out, i + n);
    }
    return out;
  }

  @Benchmark
  public double[] blended128() {
    for (int i = 0; i < N; i += S128.length()) {
      Exp.expBlended(DoubleVector.fromArray(S128, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended256() {
    for (int i = 0; i < N; i += S256.length()) {
      Exp.expBlended(DoubleVector.fromArray(S256, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blended512() {
    for (int i = 0; i < N; i += S512.length()) {
      Exp.expBlended(DoubleVector.fromArray(S512, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      Exp.expBlended(DoubleVector.fromArray(SP, in, i), null).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] blendedMax() {
    for (int i = 0; i < N; i += SMAX.length()) {
      Exp.expBlended(DoubleVector.fromArray(SMAX, in, i), null).intoArray(out, i);
    }
    return out;
  }
}
