/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * The child JVM of {@link AsmTest}: each vector arm of each port in a loop over an array, the shape
 * the port's benchmark rows have, warmed until C2 compiles the loop with the arm inlined, so that
 * {@code -XX:CompileCommand=print} on this class prints the code a caller runs.
 *
 * <p>One function and one species per JVM, named by {@code -Dvfdlibm.asm.function} and by the
 * {@link VectorWidth} in {@code -Dvfdlibm.asm.width}; the species is read into a {@code static
 * final}, a constant to C2. An arm of function {@code f} is a static method named {@code f}
 * followed by a capital ({@code expFinish}, {@code log10BlendedMasked}), taking the same five
 * arrays; {@link #arms} finds them by that rule, for this class's {@code main} and for {@link
 * AsmTest} alike. Each arm makes direct static calls into vfdlibm, which the child's forced inline
 * puts in the loop, while the arms themselves are kept out of {@code main} ({@code dontinline}), so
 * that each is a compile of its own and an on-stack-replacement compile of {@code main} cannot
 * swallow one.
 *
 * <p>The data are each benchmark's common inputs, on which no lane takes a rare path: the per-lane
 * finish is compiled as an uncommon trap, and no {@code StrictMath} code is compiled into the C2
 * loops (AGENTS.md, licence). One range differs from its benchmark's: cosh's is {@code 0.35 <=
 * |x| < 60}, which leaves out the small range {@code |x| < 0.5*ln2} (0.3466), 0.6% of {@code
 * CoshBenchmark}'s common lanes, which {@code coshFinish} finishes per lane, and the lanes up to
 * 0.35 with it. The C1 compiles printed beside the C2 ones keep the finish, with a call into
 * {@code StrictMath}, and are not read.
 */
final class AsmProbe {

  /**
   * The width this JVM runs, from {@code -Dvfdlibm.asm.width}. The preferred species without it,
   * since {@link AsmTest} loads this class for its arm list; the child always has it, and its start
   * line, which the test compares, names the width it ran.
   */
  static final VectorWidth WIDTH =
      VectorWidth.valueOf(System.getProperty("vfdlibm.asm.width", "PREFERRED"));
  static final VectorSpecies<Double> S = WIDTH.species();
  static final int N = 256;
  /** Calls of each loop: past C2's thresholds, and under -Xbatch each compile completes. */
  static final int CALLS = 12_000;

  private AsmProbe() {}

  /**
   * The arms of {@code function}, sorted: the static methods whose name is {@code function}
   * followed by a capital letter. A call from {@link AsmTest} initializes this class, which is why
   * {@link #WIDTH} has a default.
   */
  static List<Method> arms(String function) {
    return allArms().stream()
        .filter(m -> m.getName().length() > function.length() && m.getName().startsWith(function)
            && Character.isUpperCase(m.getName().charAt(function.length())))
        .toList();
  }

  /** Every arm of every function, sorted: the static methods that take an arm's five arrays. */
  static List<Method> allArms() {
    return Arrays.stream(AsmProbe.class.getDeclaredMethods())
        .filter(m -> Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()
            && Arrays.equals(m.getParameterTypes(), ARM_PARAMETERS))
        .sorted((p, q) -> p.getName().compareTo(q.getName()))
        .toList();
  }

  private static final Class<?>[] ARM_PARAMETERS =
      {double[].class, double[].class, double[].class, boolean[].class, boolean[].class};

  /** An arm's name as the test and the census print it: {@code expFinishMasked} is finishMasked. */
  static String armName(String function, String method) {
    String arm = method.substring(function.length());
    return Character.toLowerCase(arm.charAt(0)) + arm.substring(1);
  }

  // ---------------------------------------------------------------------------------------------
  // exp: ExpBenchmark's arms, [-20, 20].

  /** {@link VFdlibm#exp(DoubleVector)}: the finish arm, every lane. */
  static void expFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  /** {@link VFdlibm#exp(DoubleVector, VectorMask)}: the finish arm, live lanes only. */
  static void expFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  /** {@link Exp#expBlended}: every branch computed and blended, every lane. */
  static void expBlended(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Exp.expBlended(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  /** {@link Exp#expBlended}, live lanes only. */
  static void expBlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Exp.expBlended(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  /**
   * {@link Exp#expRare} and {@link Exp#expCore}: the rare lanes and the branch-free part, in
   * {@code ExpBenchmark}'s order. The other order boxes: with {@code expCore} inlined first, C2
   * refuses {@code expRare} for {@code NodeCountInliningCutoff} (SKILLS.md, "The assembly check").
   */
  static void expCore(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      DoubleVector x = DoubleVector.fromArray(S, a, i);
      Exp.expRare(x).intoArray(flags, i);
      Exp.expCore(x).intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // expm1: Expm1Benchmark's arms, [-30, 30]. VFdlibm.expm1 is the guarded arm.

  static void expm1Guarded(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.expm1(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void expm1GuardedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.expm1(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void expm1Blended(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Expm1.expm1Blended(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void expm1BlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Expm1.expm1Blended(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void expm1Finish(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Expm1.expm1Finish(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void expm1FinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Expm1.expm1Finish(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  /** {@link Expm1#expm1Core} alone, as {@code Expm1Benchmark}'s core rows call it. */
  static void expm1Core(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Expm1.expm1Core(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // log: LogBenchmark's arms, positive normals with binary exponents in [-30, 30].

  static void logFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void logFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void logBlended(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log.logBlended(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void logBlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log.logBlended(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // log10: Log10Benchmark's arms, log's data.

  static void log10Finish(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log10(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void log10FinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log10(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void log10Blended(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log10.log10Blended(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void log10BlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log10.log10Blended(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // log1p: Log1pBenchmark's arms, [-0.9, 20]. VFdlibm.log1p is the guarded arm.

  static void log1pGuarded(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log1p(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void log1pGuardedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log1p(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void log1pBlended(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log1p.log1pBlended(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void log1pBlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log1p.log1pBlended(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void log1pFinish(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log1p.log1pFinish(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void log1pFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Log1p.log1pFinish(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // sinh: SinhBenchmark's arm, (-21.5, 21.5).

  static void sinhFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.sinh(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void sinhFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.sinh(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // cosh: CoshBenchmark's arms, each by name (not through VFdlibm.cosh's default), on
  // 0.35 <= |x| < 60.

  static void coshSlice(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void coshSliceMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Cosh.coshSlice(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void coshFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(S, a, i), null).intoArray(out, i);
    }
  }

  static void coshFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Cosh.coshFinish(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // asin, atan, acos: their benchmarks' arm, on (-0.99, 0.99) with one lane in 64
  // below 2^-27 (asin and acos) and (-4, 4) (atan).

  static void asinFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.asin(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void asinFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.asin(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void atanFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.atan(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void atanFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.atan(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  static void acosFinish(double[] a, double[] b, double[] out, boolean[] live, boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.acos(DoubleVector.fromArray(S, a, i)).intoArray(out, i);
    }
  }

  static void acosFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.acos(DoubleVector.fromArray(S, a, i), VectorMask.fromArray(S, live, i))
          .intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // atan2: Atan2Benchmark's arms, y in a and x in b, both on (-4, 4). VFdlibm.atan2 is the
  // guarded arm.

  static void atan2Guarded(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.atan2(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i))
          .intoArray(out, i);
    }
  }

  static void atan2GuardedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.atan2(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i),
          VectorMask.fromArray(S, live, i)).intoArray(out, i);
    }
  }

  static void atan2Blended(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Atan2.atan2Blended(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i), null)
          .intoArray(out, i);
    }
  }

  static void atan2BlendedMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      Atan2.atan2Blended(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i),
          VectorMask.fromArray(S, live, i)).intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // hypot: HypotBenchmark's arm, x in a and y in b, both on [-10, 10).

  static void hypotFinish(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.hypot(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i))
          .intoArray(out, i);
    }
  }

  static void hypotFinishMasked(double[] a, double[] b, double[] out, boolean[] live,
      boolean[] flags) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.hypot(DoubleVector.fromArray(S, a, i), DoubleVector.fromArray(S, b, i),
          VectorMask.fromArray(S, live, i)).intoArray(out, i);
    }
  }

  // ---------------------------------------------------------------------------------------------

  /** Fills {@code a} and {@code b} with {@code function}'s common data (see each section above). */
  static void fill(String function, SplittableRandom random, double[] a, double[] b) {
    for (int i = 0; i < a.length; i++) {
      switch (function) {
        case "exp" -> a[i] = random.nextDouble(-20.0, 20.0);
        case "expm1" -> a[i] = random.nextDouble(-30.0, 30.0);
        case "log", "log10" -> {
          long exponent = random.nextInt(-30, 31) + 1023L;
          a[i] = Double.longBitsToDouble(
              (exponent << 52) | (random.nextLong() & 0x000f_ffff_ffff_ffffL));
        }
        case "log1p" -> a[i] = random.nextDouble(-0.9, 20.0);
        case "sinh" -> a[i] = random.nextDouble(-21.5, 21.5);
        case "cosh" -> a[i] = (random.nextBoolean() ? 1 : -1) * random.nextDouble(0.35, 60.0);
        case "asin", "acos" -> {
          // as their benchmarks: one lane in 64 below 2^-27, a common lane of their own
          a[i] = random.nextInt(64) == 0
              ? Math.scalb(random.nextDouble(-1.0, 1.0), -random.nextInt(28, 60))
              : random.nextDouble(-0.99, 0.99);
        }
        case "atan" -> a[i] = random.nextDouble(-4.0, 4.0);
        case "atan2" -> {
          a[i] = random.nextDouble(-4.0, 4.0);
          b[i] = random.nextDouble(-4.0, 4.0);
        }
        case "hypot" -> {
          a[i] = random.nextDouble(-10.0, 10.0);
          b[i] = random.nextDouble(-10.0, 10.0);
        }
        default -> throw new IllegalArgumentException("no data for '" + function + "'");
      }
    }
  }

  /**
   * Prints a start line with the function, the width and the species' bits, which {@link AsmTest}
   * compares with its own so that it never checks another, warms each arm of the function in turn
   * and prints a done line.
   */
  public static void main(String[] args) throws ReflectiveOperationException {
    String function = java.util.Objects.requireNonNull(
        System.getProperty("vfdlibm.asm.function"), "-Dvfdlibm.asm.function");
    List<Method> arms = arms(function);
    if (arms.isEmpty()) {
      throw new IllegalArgumentException("no arms for '" + function + "'");
    }
    System.out.println("asm-probe start: " + function + ", width " + WIDTH + ", "
        + S.vectorBitSize() + " bits");
    SplittableRandom random = new SplittableRandom(1L);
    double[] a = new double[N];
    double[] b = new double[N];
    double[] out = new double[N];
    boolean[] live = new boolean[N];
    boolean[] flags = new boolean[N];
    fill(function, random, a, b);
    for (int i = 0; i < N; i++) {
      live[i] = random.nextBoolean();
    }
    double sink = 0;
    for (Method arm : arms) {
      for (int c = 0; c < CALLS; c++) {
        arm.invoke(null, a, b, out, live, flags);
        sink += out[c & (N - 1)];
      }
    }
    System.out.println("asm-probe done: " + sink);
  }
}
