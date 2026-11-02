/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.UnaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * The oracle every function is tested against: {@code StrictMath} on the same JVM, compared lane
 * by lane by {@link Double#doubleToRawLongBits}, so a different NaN payload or a zero of the other
 * sign is a failure just as a one-ulp difference is.
 *
 * <p>A check feeds an array of inputs through the vector function one species-length vector at a
 * time and compares every lane that holds an input. It runs all inputs before failing and reports
 * the first difference (function, species, lane, input bits, both results in hex) and how many
 * inputs differed. A short last vector is padded with the last input; padded lanes are not
 * compared.
 *
 * <p>The masked checks hold a function's masked form, {@code f(x, live)}, to the contract of
 * PLAN.md section 5: a lane where {@code live} is set equals {@code StrictMath}, and a lane where
 * it is clear keeps its input's raw bits, untouched. For a two-argument function an inactive lane
 * keeps the first argument's bits, {@code x}; that is the same rule ("leaves inactive lanes
 * untouched", the result standing in place of {@code x}) and the one Varka's plan states for the
 * library's masked forms. Every vector is checked under an all-true mask, an all-false mask and
 * {@link #RANDOM_MASKS} masks drawn from a fixed seed, so a failure reproduces.
 */
final class Oracle {

  /** How many seeded random masks each vector is checked under, besides all-true and all-false. */
  static final int RANDOM_MASKS = 4;

  private Oracle() {}

  /** Checks a one-argument vector function against its {@code StrictMath} oracle. */
  static void assertUnary(
      String name,
      VectorSpecies<Double> species,
      UnaryOperator<DoubleVector> function,
      DoubleUnaryOperator oracle,
      double[] xs) {
    assertNonEmpty(xs);
    int lanes = species.length();
    double[] x = new double[lanes];
    String first = null;
    int differing = 0;
    for (int base = 0; base < xs.length; base += lanes) {
      fill(x, xs, base);
      double[] got = function.apply(DoubleVector.fromArray(species, x, 0)).toArray();
      assertEquals(lanes, got.length, name + " returned a vector of another length");
      for (int lane = 0; lane < lanes && base + lane < xs.length; lane++) {
        double want = oracle.applyAsDouble(x[lane]);
        if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got[lane])) {
          differing++;
          if (first == null) {
            first = String.format(
                "%s at %s lane %d (input %d): x = %s: expected %s, got %s",
                name, species, lane, base + lane, hex(x[lane]), hex(want), hex(got[lane]));
          }
        }
      }
    }
    report(first, differing, xs.length);
  }

  /** Checks a two-argument vector function, such as atan2, pow or hypot, against its oracle. */
  static void assertBinary(
      String name,
      VectorSpecies<Double> species,
      BinaryOperator<DoubleVector> function,
      DoubleBinaryOperator oracle,
      double[] xs,
      double[] ys) {
    assertNonEmpty(xs);
    assertEquals(xs.length, ys.length, "x and y inputs differ in length");
    int lanes = species.length();
    double[] x = new double[lanes];
    double[] y = new double[lanes];
    String first = null;
    int differing = 0;
    for (int base = 0; base < xs.length; base += lanes) {
      fill(x, xs, base);
      fill(y, ys, base);
      double[] got = function.apply(
          DoubleVector.fromArray(species, x, 0), DoubleVector.fromArray(species, y, 0)).toArray();
      assertEquals(lanes, got.length, name + " returned a vector of another length");
      for (int lane = 0; lane < lanes && base + lane < xs.length; lane++) {
        double want = oracle.applyAsDouble(x[lane], y[lane]);
        if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got[lane])) {
          differing++;
          if (first == null) {
            first = String.format(
                "%s at %s lane %d (input %d): x = %s, y = %s: expected %s, got %s",
                name, species, lane, base + lane, hex(x[lane]), hex(y[lane]), hex(want),
                hex(got[lane]));
          }
        }
      }
    }
    report(first, differing, xs.length);
  }

  /**
   * Checks a one-argument masked form: active lanes against the oracle, inactive lanes against
   * their input, by raw bits, under the masks the class comment lists, drawn from {@code seed}.
   */
  static void assertUnaryMasked(
      String name,
      VectorSpecies<Double> species,
      BiFunction<DoubleVector, VectorMask<Double>, DoubleVector> function,
      DoubleUnaryOperator oracle,
      double[] xs,
      long seed) {
    assertBinaryMasked(
        name,
        species,
        (x, y, live) -> function.apply(x, live),
        (x, y) -> oracle.applyAsDouble(x),
        xs,
        xs,
        seed,
        false);
  }

  /**
   * Checks a two-argument masked form: active lanes against the oracle, inactive lanes against
   * the first argument {@code x}, by raw bits, under the masks the class comment lists.
   */
  static void assertBinaryMasked(
      String name,
      VectorSpecies<Double> species,
      MaskedBinary function,
      DoubleBinaryOperator oracle,
      double[] xs,
      double[] ys,
      long seed) {
    assertBinaryMasked(name, species, function, oracle, xs, ys, seed, true);
  }

  /** A two-argument masked form, {@code f(x, y, live)}. */
  @FunctionalInterface
  interface MaskedBinary {
    DoubleVector apply(DoubleVector x, DoubleVector y, VectorMask<Double> live);
  }

  private static void assertBinaryMasked(
      String name,
      VectorSpecies<Double> species,
      MaskedBinary function,
      DoubleBinaryOperator oracle,
      double[] xs,
      double[] ys,
      long seed,
      boolean binary) {
    assertNonEmpty(xs);
    assertEquals(xs.length, ys.length, "x and y inputs differ in length");
    SplittableRandom random = new SplittableRandom(seed);
    int lanes = species.length();
    double[] x = new double[lanes];
    double[] y = new double[lanes];
    boolean[] live = new boolean[lanes];
    String first = null;
    int differing = 0;
    for (int base = 0; base < xs.length; base += lanes) {
      fill(x, xs, base);
      fill(y, ys, base);
      DoubleVector vx = DoubleVector.fromArray(species, x, 0);
      DoubleVector vy = DoubleVector.fromArray(species, y, 0);
      for (int m = 0; m < RANDOM_MASKS + 2; m++) {
        for (int lane = 0; lane < lanes; lane++) {
          live[lane] = m == 0 || (m > 1 && random.nextBoolean());
        }
        VectorMask<Double> mask = VectorMask.fromArray(species, live, 0);
        double[] got = function.apply(vx, vy, mask).toArray();
        assertEquals(lanes, got.length, name + " returned a vector of another length");
        for (int lane = 0; lane < lanes && base + lane < xs.length; lane++) {
          double want = live[lane] ? oracle.applyAsDouble(x[lane], y[lane]) : x[lane];
          if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got[lane])) {
            differing++;
            if (first == null) {
              first = String.format(
                  "%s at %s under mask %s, %s lane %d (input %d): x = %s%s: expected %s, got %s",
                  name, species, mask, live[lane] ? "active" : "inactive", lane, base + lane,
                  hex(x[lane]), binary ? ", y = " + hex(y[lane]) : "", hex(want), hex(got[lane]));
            }
          }
        }
      }
    }
    if (first != null) {
      fail(first + "; " + differing + " lane checks differ");
    }
  }

  /** A double as its raw bits in hex, with its decimal value: {@code 0x3ff0000000000000 (1.0)}. */
  static String hex(double d) {
    return String.format("0x%016x (%s)", Double.doubleToRawLongBits(d), d);
  }

  /** Copies one vector's worth of inputs from {@code base}, padding with the last input. */
  private static void fill(double[] lanes, double[] inputs, int base) {
    for (int lane = 0; lane < lanes.length; lane++) {
      lanes[lane] = inputs[Math.min(base + lane, inputs.length - 1)];
    }
  }

  /** A check over no inputs would pass without testing anything. */
  private static void assertNonEmpty(double[] xs) {
    if (xs.length == 0) {
      fail("no inputs to check");
    }
  }

  private static void report(String first, int differing, int inputs) {
    if (first != null) {
      fail(first + "; " + differing + " of " + inputs + " inputs differ");
    }
  }
}
