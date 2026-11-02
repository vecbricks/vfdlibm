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
 * The scalar baseline the ported functions are measured against: {@code StrictMath.exp} over an
 * array as long as one vector of 128, 256 or 512 bits (2, 4 or 8 doubles). It is a placeholder
 * until exp is ported, when a benchmark of the vector form joins it at the same lengths.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class StrictMathExpBenchmark {

  /** Array length in doubles: one vector of 128, 256 or 512 bits. */
  @Param({"2", "4", "8"})
  public int lanes;

  private double[] in;
  private double[] out;

  @Setup
  public void setUp() {
    SplittableRandom random = new SplittableRandom(1L);
    in = new double[lanes];
    out = new double[lanes];
    for (int i = 0; i < lanes; i++) {
      in[i] = random.nextDouble(-20.0, 20.0);
    }
  }

  @Benchmark
  public double[] scalarStrictMath() {
    for (int i = 0; i < lanes; i++) {
      out[i] = StrictMath.exp(in[i]);
    }
    return out;
  }
}
