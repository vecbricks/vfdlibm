/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.util.Objects;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;

/**
 * fdlibm's double functions over {@link DoubleVector} lanes, each lane bit-identical to the
 * {@link StrictMath} method of the same name, NaN payloads and signed zeros included.
 *
 * <p>Every method works on a vector of any species of 128 bits or more (exp, expm1, log, log10,
 * log1p, sinh and cosh hold fdlibm's {@code int} words in int lanes of half the vector's size).
 * Each comes in two forms: one computes every lane, and a masked form computes only the lanes set
 * in {@code live} and returns every other lane of its argument untouched, raw bits and all. An
 * inactive lane never sends the vector down a slow path.
 *
 * <p>Which species to pass. Every species of 128 bits or more gives the same bits. The files
 * under {@code bench/results/}, in the source repository and not in the jar, read what the body
 * allocates at each, in bytes per op, an op being one pass over 1024 doubles. The arm that {@code
 * exp}, {@code log} and {@code log10} call is the finish arm, and the one {@code expm1} and {@code
 * log1p} call is the guarded arm.
 *
 * <ul>
 *   <li>Covered by {@code bench/alloc-check.py}, which CI runs and which fails a body that
 *       allocates 1 byte per op or more: on x86, 256 bits and {@code
 *       DoubleVector.SPECIES_PREFERRED}, with the runner's own flags and as an AVX2 host; on
 *       GitHub's Arm runner, whose vectors are 128 bits, the preferred species, where all 24 rows
 *       passed ({@code bench/results/alloc-arm-2026-11-20.txt}, an allocation reading and no
 *       timing).
 *   <li>At 128 bits {@code exp} and {@code log} allocate 0.5 to 0.6 million bytes per op on
 *       x86, the finish arm that {@code VFdlibm} calls: on the EPYC 7763, an AVX2 host, in the
 *       files of 20 November 2026 named below, and on the Xeon Platinum 8573C in {@code
 *       bench/results/exp-xeon8573c-2026-11-21.txt} and {@code log-xeon8573c-2026-11-21.txt}.
 *       {@code log10}, {@code log1p} and {@code expm1} have no 128-bit row.
 *   <li>On the EPYC 7763 at 512 bits, which is the Vector API's Java fallback there, each of the
 *       five functions allocates 0.9 to 1.8 million bytes per op. The files of 20 November 2026
 *       hold this reading and the 128-bit one above ({@code exp-epyc7763-2026-11-20.txt}, and
 *       the same for {@code log}, {@code log10}, {@code log1p} and {@code expm1}, in {@code
 *       bench/results/}).
 *   <li>{@code DoubleVector.SPECIES_MAX} is none of the fixed shapes. On mixed data (one input in
 *       16 replaced by one that takes its own path in the C, as each benchmark class lists),
 *       {@code exp}, {@code log} and {@code log10} allocate 30432 to 36992 bytes per op there,
 *       and {@code expm1} and {@code log1p} less than 0.5 ({@code
 *       bench/results/max-finish-laptop-2026-11-20.txt}, a laptop file and not a headline, read
 *       at 512 bits and as an AVX2 host at 256; {@code SPECIES_MAX} at 128 bits was not read).
 *       The host files read the same for the arm {@code VFdlibm} calls (readings of 21 and 20
 *       November 2026): on the Xeon Platinum 8573C (the files named {@code
 *       <function>-xeon8573c-2026-11-21.txt}) 30,912 bytes per op for {@code exp}, 30,432 for
 *       {@code log} and {@code log10}, and 0.012 and 0.009 for the guarded arms of {@code
 *       expm1} and {@code log1p}; on the EPYC 7763 at 256 bits 36,992 for {@code exp} and
 *       36,672 for {@code log10} ({@code exp-epyc7763-2026-11-20.txt}, {@code
 *       log10-epyc7763-2026-11-20.txt}).
 *   <li>Not measured: any port's speed at 512 bits against 256 on the EPYC 9V74 draws that have
 *       AVX-512 ({@code HARDWARE.md} has only a datapath reading for them, and lists {@code
 *       MaxVectorSize=64}), any port's speed on Arm, and allocation on an Arm host with vectors
 *       wider than 128 bits.
 * </ul>
 *
 * <p>Each function is ported from fdlibm 5.3's C source, kept in {@code reference/fdlibm-5.3/};
 * the class that holds a function's port names the file it follows and carries its notice.
 *
 * <p>The system property {@code vfdlibm.fault} plants a known fault in a function, to prove that
 * this project's tests catch it, and it works in the shipped jar too: never set it outside those
 * tests, including through {@code JAVA_TOOL_OPTIONS}. A JVM with a fault planted prints a warning
 * to {@code System.err} once, when the first function that reads its faults is initialised. The
 * property and the fault names are for this project's tests only: they are not part of the public
 * API or of the compatibility promise, and any release, a patch included, may rename or remove
 * them. With the property unset, the results are unchanged, and each lane still equals {@code
 * StrictMath}'s.
 */
public final class VFdlibm {

  private VFdlibm() {}

  /**
   * {@code StrictMath.exp} in every lane. Ported from fdlibm 5.3's {@code e_exp.c}.
   *
   * @param x the arguments
   * @return {@code e} raised to each lane of {@code x}
   */
  public static DoubleVector exp(DoubleVector x) {
    return Exp.exp(x, null);
  }

  /**
   * {@code StrictMath.exp} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return {@code e} raised to each live lane of {@code x}, and {@code x} in the others
   */
  public static DoubleVector exp(DoubleVector x, VectorMask<Double> live) {
    return Exp.exp(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.expm1} in every lane. Ported from fdlibm 5.3's {@code s_expm1.c}.
   *
   * @param x the arguments
   * @return {@code e} raised to each lane of {@code x}, less one
   */
  public static DoubleVector expm1(DoubleVector x) {
    return Expm1.expm1(x, null);
  }

  /**
   * {@code StrictMath.expm1} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return {@code e} raised to each live lane of {@code x}, less one, and {@code x} in the others
   */
  public static DoubleVector expm1(DoubleVector x, VectorMask<Double> live) {
    return Expm1.expm1(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.log} in every lane. Ported from fdlibm 5.3's {@code e_log.c}.
   *
   * @param x the arguments
   * @return the natural logarithm of each lane of {@code x}
   */
  public static DoubleVector log(DoubleVector x) {
    return Log.log(x, null);
  }

  /**
   * {@code StrictMath.log} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return the natural logarithm of each live lane of {@code x}, and {@code x} in the others
   */
  public static DoubleVector log(DoubleVector x, VectorMask<Double> live) {
    return Log.log(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.log10} in every lane. Ported from fdlibm 5.3's {@code e_log10.c}.
   *
   * @param x the arguments
   * @return the base 10 logarithm of each lane of {@code x}
   */
  public static DoubleVector log10(DoubleVector x) {
    return Log10.log10(x, null);
  }

  /**
   * {@code StrictMath.log10} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return the base 10 logarithm of each live lane of {@code x}, and {@code x} in the others
   */
  public static DoubleVector log10(DoubleVector x, VectorMask<Double> live) {
    return Log10.log10(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.log1p} in every lane. Ported from fdlibm 5.3's {@code s_log1p.c}.
   *
   * @param x the arguments
   * @return the natural logarithm of {@code 1 + x} in each lane of {@code x}
   */
  public static DoubleVector log1p(DoubleVector x) {
    return Log1p.log1p(x, null);
  }

  /**
   * {@code StrictMath.log1p} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return the natural logarithm of {@code 1 + x} in each live lane of {@code x}, and {@code x}
   *     in the others
   */
  public static DoubleVector log1p(DoubleVector x, VectorMask<Double> live) {
    return Log1p.log1p(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.sinh} in every lane. Ported from fdlibm 5.3's {@code e_sinh.c}.
   *
   * @param x the arguments
   * @return the hyperbolic sine of each lane of {@code x}
   */
  public static DoubleVector sinh(DoubleVector x) {
    return Sinh.sinh(x, null);
  }

  /**
   * {@code StrictMath.sinh} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return the hyperbolic sine of each live lane of {@code x}, and {@code x} in the others
   */
  public static DoubleVector sinh(DoubleVector x, VectorMask<Double> live) {
    return Sinh.sinh(x, Objects.requireNonNull(live, "live"));
  }

  /**
   * {@code StrictMath.cosh} in every lane. Ported from fdlibm 5.3's {@code e_cosh.c}.
   *
   * @param x the arguments
   * @return the hyperbolic cosine of each lane of {@code x}
   */
  public static DoubleVector cosh(DoubleVector x) {
    return Cosh.cosh(x, null);
  }

  /**
   * {@code StrictMath.cosh} in the lanes set in {@code live}; every other lane is {@code x}'s,
   * untouched.
   *
   * @param x the arguments
   * @param live the lanes to compute, of {@code x}'s species
   * @return the hyperbolic cosine of each live lane of {@code x}, and {@code x} in the others
   */
  public static DoubleVector cosh(DoubleVector x, VectorMask<Double> live) {
    return Cosh.cosh(x, Objects.requireNonNull(live, "live"));
  }
}
