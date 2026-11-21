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
 * <p>Every method works on a vector of any species of 128 bits or more (exp, expm1, log, log10
 * and log1p hold fdlibm's {@code int} words in int lanes of half the vector's size). Each comes in
 * two forms: one computes every lane, and a masked form computes only the lanes set in {@code live}
 * and returns every other lane of its argument untouched, raw bits and all. An inactive lane
 * never sends the vector down a slow path.
 *
 * <p>Which species to pass. Every species of 128 bits or more gives the same bits, but not at the
 * same cost, and the types do not say. What the files under {@code bench/results/} show:
 *
 * <ul>
 *   <li>256 bits, and 512 bits on a host that has AVX-512, run natively. {@code
 *       bench/alloc-check.py}, which CI runs, fails a body that allocates 1 byte per op or more,
 *       at 256 bits and at {@code DoubleVector.SPECIES_PREFERRED}.
 *   <li>128 bits is native on Arm, where it is the preferred species: all 24 of the check's
 *       {@code *Preferred} rows passed there ({@code bench/results/alloc-arm-2026-11-20.txt}, an
 *       allocation reading and no timing). On x86, 128 bits runs lane by lane and allocates over
 *       1.3 MB per op ({@code bench/results/exp-epyc7763-2026-11-02.txt}).
 *   <li>512 bits on a host without AVX-512 runs in the Vector API's Java fallback, and allocates
 *       as much (the same file).
 *   <li>{@code DoubleVector.SPECIES_MAX} is none of the fixed shapes. On data with rare lanes (the
 *       NaNs, infinities and out-of-range inputs that take their own path in the C), {@code exp},
 *       {@code log} and {@code log10} allocate 30432 to 36992 bytes per op there, and {@code
 *       expm1} and {@code log1p} less than 0.5 ({@code
 *       bench/results/max-finish-laptop-2026-11-20.txt}, a laptop file and not a headline).
 *   <li>Not measured: whether 512 bits beats 256 on a CPU whose AVX-512 datapath is 256 bits wide
 *       (the EPYC 9V74 draws that have AVX-512 prefer 512 bits), and the speed of anything on
 *       Arm.
 * </ul>
 *
 * <p>So a fixed species of 256 bits, of 512 bits where the host has AVX-512, or the preferred
 * species on Arm, is what the check covers; 128 bits on x86, 512 bits without AVX-512 and
 * {@code SPECIES_MAX} are slow paths.
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
}
