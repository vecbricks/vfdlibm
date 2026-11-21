/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.SplittableRandom;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * The hinge of (R4) for {@code sqrt} (PROOFS.md): the vector operator {@code
 * VectorOperators.SQRT} returns {@code StrictMath.sqrt}'s bits, lane by lane, by {@link
 * Double#doubleToRawLongBits}, so that a port may use it where the C uses {@code sqrt}. The API
 * documents both as the correctly rounded square root, with the zeros returning their argument,
 * and says nothing of a NaN's payload; this test pins the bits, NaN payloads included, at every
 * width the host has and, in {@link #everyWidthIncludingTheSoftwareFallback}, at the widths it
 * does not have, where the Vector API computes in Java.
 *
 * <p>The inputs are the boundary doubles (the zeros, the subnormals and the smallest normal,
 * {@code 1}, every power of two with its neighbours, the squares of the integers to 4096 with
 * theirs, the largest double, the infinities, NaNs of several payloads, negative numbers: the
 * specials of both signs), then a few million random bit patterns, magnitudes spread over every
 * exponent and subnormals. What it does not show: that C2 lowers the operator without boxing
 * (that is {@code bench/alloc-check.py} on {@code SqrtBenchmark}, unmasked only); the masked
 * form's lowering; other JDKs or hosts than those the tests run on; or anything about a species
 * of fewer than 128 bits.
 */
class SqrtTest {

  /** The inputs of the boundary and of structure, which a random draw reaches only by luck. */
  private static final double[] BOUNDARY = boundary();

  /** The random doubles, built once, on first use: 70 MB. */
  private static final class RandomDoubles {
    static final double[] XS = random();
  }

  private static double[] boundary() {
    double[] xs = Inputs.specials();
    for (int e = -1074; e <= 1023; e++) {
      double p = Math.scalb(1.0, e);
      xs = Inputs.concat(xs, Inputs.around(p, 2), Inputs.around(-p, 2));
    }
    for (int n = 0; n <= 4096; n++) {
      double square = (double) n * n;
      xs = Inputs.concat(xs, Inputs.around(square, 2));
    }
    xs = Inputs.concat(
        xs, Inputs.around(Double.MAX_VALUE, 4), Inputs.around(Double.MIN_NORMAL, 4),
        Inputs.around(Inputs.MAX_SUBNORMAL, 4), Inputs.around(Double.MIN_VALUE, 4));
    return xs;
  }

  /**
   * About 8.7 million doubles: bit patterns and magnitudes over every normal exponent, each drawn
   * and again as its absolute value, and 2^18 positive subnormals.
   */
  private static double[] random() {
    SplittableRandom random = new SplittableRandom(0x5917L);
    double[] subnormals = new double[1 << 18];
    for (int i = 0; i < subnormals.length; i++) {
      subnormals[i] = Double.longBitsToDouble(random.nextLong() & 0x000f_ffff_ffff_ffffL);
    }
    double[] drawn = Inputs.concat(
        Inputs.sampled(0x5918L, 1 << 21), Inputs.magnitudes(0x5919L, 1 << 21, -1022, 1023));
    double[] positive = new double[drawn.length];
    for (int i = 0; i < drawn.length; i++) {
      positive[i] = Math.abs(drawn[i]);
    }
    return Inputs.concat(drawn, positive, subnormals);
  }

  private static void assertSqrt(VectorSpecies<Double> species, double[] xs) {
    Oracle.assertUnary(
        "sqrt", species, v -> v.lanewise(VectorOperators.SQRT), StrictMath::sqrt, xs);
  }

  private static void assertSqrtMasked(VectorSpecies<Double> species, double[] xs) {
    Oracle.assertUnaryMasked(
        "sqrt", species, (v, live) -> v.lanewise(VectorOperators.SQRT, live), StrictMath::sqrt, xs,
        0x591aL);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sqrtIsStrictMathsAtTheBoundary(VectorWidth width) {
    assertSqrt(width.species(), BOUNDARY);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sqrtIsStrictMathsOnRandomDoubles(VectorWidth width) {
    assertSqrt(width.species(), RandomDoubles.XS);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void maskedSqrtIsStrictMathsAndKeepsTheInactiveLanes(VectorWidth width) {
    assertSqrtMasked(width.species(), Inputs.concat(BOUNDARY, Inputs.sampled(0x591bL, 1 << 19)));
  }

  /**
   * {@code VectorWidth} skips a width wider than the host's preferred one, which the Vector API
   * would compute in Java. The hinge is claimed there too, since a port runs on whatever the host
   * is, so it is checked at 128, 256, 512 bits and {@code SPECIES_MAX} whether the host has them
   * or not, on the boundary and a quarter million random doubles; the failure message names the
   * species.
   */
  @Test
  void everyWidthIncludingTheSoftwareFallback() {
    double[] xs = Inputs.concat(BOUNDARY, Inputs.sampled(0x591cL, 1 << 18));
    for (VectorSpecies<Double> species : List.<VectorSpecies<Double>>of(
      VectorSpecies.of(double.class, VectorShape.S_128_BIT),
      VectorSpecies.of(double.class, VectorShape.S_256_BIT),
      VectorSpecies.of(double.class, VectorShape.S_512_BIT), DoubleVector.SPECIES_MAX)) {
      assertSqrt(species, xs);
      assertSqrtMasked(species, xs);
    }
  }

  /** The check can fail: another function in place of {@code SQRT} differs on the boundary. */
  @Test
  void aDifferentOperatorIsRefused() {
    assertThrows(AssertionFailedError.class, () -> Oracle.assertUnary(
        "cbrt", DoubleVector.SPECIES_PREFERRED, v -> v.lanewise(VectorOperators.CBRT),
        StrictMath::sqrt, BOUNDARY));
    assertThrows(AssertionFailedError.class, () -> Oracle.assertUnary(
        "-sqrt", DoubleVector.SPECIES_PREFERRED, v -> v.lanewise(VectorOperators.SQRT).neg(),
        StrictMath::sqrt, BOUNDARY));
  }
}
