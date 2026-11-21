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
 * {@code VectorOperators.SQRT} over an array of 1024 doubles, at 256 and 512 bits and at the
 * host's preferred species, against the scalar {@code StrictMath.sqrt} loop. There is no port
 * of {@code sqrt} (the operator is the library's, PROOFS.md (R4)); this class exists so that
 * {@code bench/alloc-check.py} shows on each host whether C2 lowers the operator to a vector
 * instruction or boxes it, before a port that calls it (asin, acos) relies on it. It makes no
 * claim about speed, and no result file backs one.
 *
 * <p>{@code inputs} picks the data: {@code common} draws positive doubles over 2^-1000 to 2^1000;
 * {@code mixed} replaces one input in 16 with a zero of either sign, a negative number, an
 * infinity, a NaN or a subnormal.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class SqrtBenchmark {

  private static final int N = 1024;
  private static final VectorSpecies<Double> S256 = DoubleVector.SPECIES_256;
  private static final VectorSpecies<Double> S512 = DoubleVector.SPECIES_512;
  private static final VectorSpecies<Double> SP = DoubleVector.SPECIES_PREFERRED;

  @Param({"common", "mixed"})
  public String inputs;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    SplittableRandom random = new SplittableRandom(1L);
    double[] rare = {
      0.0, -0.0, -1.0, -5e-324, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
      Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE
    };
    in = new double[N];
    out = new double[N];
    for (int i = 0; i < N; i++) {
      in[i] = Math.scalb(random.nextDouble(1.0, 2.0), random.nextInt(-1000, 1001));
      if (inputs.equals("mixed") && random.nextInt(16) == 0) {
        in[i] = rare[random.nextInt(rare.length)];
      }
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < N; i++) {
      out[i] = StrictMath.sqrt(in[i]);
    }
    return out;
  }

  @Benchmark
  public double[] sqrt256() {
    for (int i = 0; i < N; i += S256.length()) {
      DoubleVector.fromArray(S256, in, i).lanewise(VectorOperators.SQRT).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] sqrt512() {
    for (int i = 0; i < N; i += S512.length()) {
      DoubleVector.fromArray(S512, in, i).lanewise(VectorOperators.SQRT).intoArray(out, i);
    }
    return out;
  }

  @Benchmark
  public double[] sqrtPreferred() {
    for (int i = 0; i < N; i += SP.length()) {
      DoubleVector.fromArray(SP, in, i).lanewise(VectorOperators.SQRT).intoArray(out, i);
    }
    return out;
  }
}
