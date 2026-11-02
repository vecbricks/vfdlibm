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
 * <p>Every method works on a vector of any species of 128 bits or more (exp holds its exponent
 * count in int lanes of half the vector's size). Each comes in two forms: one computes every lane,
 * and a masked form computes only the lanes set in {@code live} and returns every other lane of
 * its argument untouched, raw bits and all. An inactive lane never sends the vector down a slow
 * path.
 *
 * <p>Each function is ported from fdlibm 5.3's C source, kept in {@code reference/fdlibm-5.3/};
 * the class that holds a function's port names the file it follows and carries its notice.
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
