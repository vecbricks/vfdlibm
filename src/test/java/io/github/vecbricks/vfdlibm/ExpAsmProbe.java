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
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * The child JVM of {@link ExpAsmTest}: each of exp's vector arms in a loop over an array, the
 * shape {@code ExpBenchmark}'s rows have, warmed until C2 compiles the loop with the arm inlined,
 * so that {@code -XX:CompileCommand=print} on this class prints the code a caller runs.
 *
 * <p>One species per JVM, named by the {@link VectorWidth} in {@code -Dvfdlibm.asm.width} and read
 * into a {@code static final}, a constant to C2. The data are {@code ExpBenchmark}'s common inputs,
 * {@code [-20, 20]}, so no lane takes a rare path: the per-lane finish is compiled as an uncommon
 * trap, and no {@code StrictMath} code is compiled into the C2 loops (AGENTS.md, licence). The
 * C1 compiles printed beside them keep the finish, with {@code StrictMath.exp}'s one-line wrapper
 * inlined and a call into {@code FdLibm}, and are not read. Every static method but {@code main}
 * is an arm, and {@link ExpAsmTest} checks each.
 */
final class ExpAsmProbe {

  /** The width this JVM runs, from {@code -Dvfdlibm.asm.width}. */
  static final VectorWidth WIDTH = VectorWidth.valueOf(System.getProperty("vfdlibm.asm.width"));
  static final VectorSpecies<Double> S = WIDTH.species();
  static final int N = 256;
  /** Calls of each loop: past C2's thresholds, and under -Xbatch each compile completes. */
  static final int CALLS = 12_000;

  private ExpAsmProbe() {}

  /** {@link VFdlibm#exp(DoubleVector)}: the finish arm, every lane. */
  static void finish(double[] in, double[] out) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S, in, i)).intoArray(out, i);
    }
  }

  /** {@link VFdlibm#exp(DoubleVector, VectorMask)}: the finish arm, live lanes only. */
  static void finishMasked(double[] in, double[] out, boolean[] live) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S, in, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  /** {@link Exp#expBlended}: every branch computed and blended, every lane. */
  static void blended(double[] in, double[] out) {
    for (int i = 0; i < N; i += S.length()) {
      Exp.expBlended(DoubleVector.fromArray(S, in, i), null).intoArray(out, i);
    }
  }

  /** {@link Exp#expBlended}, live lanes only. */
  static void blendedMasked(double[] in, double[] out, boolean[] live) {
    for (int i = 0; i < N; i += S.length()) {
      Exp.expBlended(DoubleVector.fromArray(S, in, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  /**
   * {@link Exp#expRare} and {@link Exp#expCore}: the rare lanes and the branch-free part, in
   * {@code ExpBenchmark}'s order. The other order boxes: with {@code expCore} inlined first, C2
   * refuses {@code expRare} for {@code NodeCountInliningCutoff} (SKILLS.md, "The assembly check").
   */
  static void core(double[] in, double[] out, boolean[] rare) {
    for (int i = 0; i < N; i += S.length()) {
      DoubleVector x = DoubleVector.fromArray(S, in, i);
      Exp.expRare(x).intoArray(rare, i);
      Exp.expCore(x).intoArray(out, i);
    }
  }

  /**
   * Prints a start line with the width and the species' bits, which {@link ExpAsmTest} compares
   * with its own so that it never checks another width, warms each arm in turn and prints a done
   * line.
   */
  public static void main(String[] args) {
    System.out.println("asm-probe start: width " + WIDTH + ", " + S.vectorBitSize() + " bits");
    SplittableRandom random = new SplittableRandom(1L);
    double[] in = new double[N];
    double[] out = new double[N];
    boolean[] live = new boolean[N];
    boolean[] flags = new boolean[N];
    for (int i = 0; i < N; i++) {
      in[i] = random.nextDouble(-20.0, 20.0);
      live[i] = random.nextBoolean();
    }
    double sink = 0;
    for (int c = 0; c < CALLS; c++) {
      finish(in, out);
      sink += out[c & (N - 1)];
    }
    for (int c = 0; c < CALLS; c++) {
      finishMasked(in, out, live);
      sink += out[c & (N - 1)];
    }
    for (int c = 0; c < CALLS; c++) {
      blended(in, out);
      sink += out[c & (N - 1)];
    }
    for (int c = 0; c < CALLS; c++) {
      blendedMasked(in, out, live);
      sink += out[c & (N - 1)];
    }
    for (int c = 0; c < CALLS; c++) {
      core(in, out, flags);
      sink += out[c & (N - 1)];
    }
    System.out.println("asm-probe done: " + sink);
  }
}
