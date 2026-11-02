/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Input generators for the checks: sampled bit patterns over the whole {@code double} domain,
 * dense sweeps around a threshold, the special values and the published hard-to-round cases. All
 * are deterministic, so a failure reproduces from the seed and the parameters in the test.
 */
final class Inputs {

  /** Quiet NaNs with a payload other than the canonical one, of both signs. */
  static final long[] QUIET_NAN_BITS = {
    0x7ff8_0000_0000_0000L, 0x7ff8_0000_0000_0001L, 0x7fff_ffff_ffff_ffffL,
    0xfff8_0000_0000_0000L, 0xfff8_dead_beef_0001L
  };

  /** Signalling NaNs (quiet bit clear, payload non-zero), of both signs. */
  static final long[] SIGNALLING_NAN_BITS = {
    0x7ff0_0000_0000_0001L, 0x7ff7_ffff_ffff_ffffL, 0xfff0_0000_0000_0001L
  };

  /** The largest subnormal double, one ulp below {@link Double#MIN_NORMAL}. */
  static final double MAX_SUBNORMAL = Math.nextDown(Double.MIN_NORMAL);

  private Inputs() {}

  /**
   * {@code n} doubles whose bit patterns are drawn uniformly from all 2^64, so every exponent,
   * NaNs, infinities, zeros and subnormals all appear in proportion to their share of the bits.
   */
  static double[] sampled(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      xs[i] = Double.longBitsToDouble(random.nextLong());
    }
    return xs;
  }

  /** {@code n} doubles drawn uniformly from {@code [lo, hi)}, reproducibly from {@code seed}. */
  static double[] uniform(long seed, int n, double lo, double hi) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      xs[i] = random.nextDouble(lo, hi);
    }
    return xs;
  }

  /**
   * {@code n} doubles of random sign whose magnitudes are spread evenly over the binary exponents
   * from {@code 2^minExponent} to {@code 2^maxExponent}, with random significands.
   */
  static double[] magnitudes(long seed, int n, int minExponent, int maxExponent) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      long exponent = random.nextInt(minExponent, maxExponent + 1) + 1023L;
      long bits = (exponent << 52) | (random.nextLong() & 0x000f_ffff_ffff_ffffL);
      xs[i] = random.nextBoolean() ? Double.longBitsToDouble(bits) : -Double.longBitsToDouble(bits);
    }
    return xs;
  }

  /**
   * {@code threshold} and its {@code ulps} neighbours on each side, in increasing order: the
   * doubles a branch on {@code threshold} must get right. The sweep stops early at an infinity.
   */
  static double[] around(double threshold, int ulps) {
    if (Double.isNaN(threshold) || ulps < 0) {
      throw new IllegalArgumentException("threshold " + threshold + ", ulps " + ulps);
    }
    double[] below = new double[ulps];
    int nBelow = 0;
    for (double d = threshold; nBelow < ulps && d != Double.NEGATIVE_INFINITY; ) {
      d = Math.nextDown(d);
      below[nBelow++] = d;
    }
    double[] above = new double[ulps];
    int nAbove = 0;
    for (double d = threshold; nAbove < ulps && d != Double.POSITIVE_INFINITY; ) {
      d = Math.nextUp(d);
      above[nAbove++] = d;
    }
    double[] xs = new double[nBelow + 1 + nAbove];
    for (int i = 0; i < nBelow; i++) {
      xs[i] = below[nBelow - 1 - i];
    }
    xs[nBelow] = threshold;
    System.arraycopy(above, 0, xs, nBelow + 1, nAbove);
    return xs;
  }

  /**
   * The special values every function is checked at: NaNs with several payloads (quiet and
   * signalling), both infinities and zeros, the smallest and largest subnormals, the smallest
   * normal, {@code MAX_VALUE} and {@code +-1}, all of both signs; then, for each of {@code edges}
   * (a function's overflow or underflow limit, given with its sign), that edge and its four
   * neighbours on each side.
   */
  static double[] specials(double... edges) {
    double[] magnitudes = {
      0.0, Double.MIN_VALUE, MAX_SUBNORMAL, Double.MIN_NORMAL, 1.0, Double.MAX_VALUE,
      Double.POSITIVE_INFINITY
    };
    double[] xs = new double[2 * magnitudes.length];
    for (int i = 0; i < magnitudes.length; i++) {
      xs[2 * i] = magnitudes[i];
      xs[2 * i + 1] = -magnitudes[i];
    }
    double[] nans = new double[QUIET_NAN_BITS.length + SIGNALLING_NAN_BITS.length];
    for (int i = 0; i < QUIET_NAN_BITS.length; i++) {
      nans[i] = Double.longBitsToDouble(QUIET_NAN_BITS[i]);
    }
    for (int i = 0; i < SIGNALLING_NAN_BITS.length; i++) {
      nans[QUIET_NAN_BITS.length + i] = Double.longBitsToDouble(SIGNALLING_NAN_BITS[i]);
    }
    xs = concat(xs, nans);
    for (double edge : edges) {
      xs = concat(xs, around(edge, 4));
    }
    return xs;
  }

  /**
   * The published hard-to-round inputs for {@code function} ({@code "exp"}, {@code "log"},
   * {@code "log10"}, {@code "log1p"}), from the test resource {@code hard-cases/<function>.txt},
   * whose header names the sources. At each the exact result lies very close to a rounding
   * boundary, where a dropped correction term or a reassociated sum, an error far below one ulp,
   * is most likely to change the returned double. A port matching fdlibm step for step matches
   * {@code StrictMath} on them as on any input. For exp's planted faults they caught nothing that
   * uniform draws miss, and fewer of them flip (the commit adding them has the counts); they are
   * kept so every port is checked exactly at the boundaries.
   */
  static double[] hardCases(String function) {
    String resource = "hard-cases/" + function + ".txt";
    InputStream in = Inputs.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalArgumentException("no hard cases for " + function + ": " + resource);
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
      double[] xs = reader.lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .mapToDouble(line -> Double.longBitsToDouble(parseBits(line)))
          .toArray();
      if (xs.length == 0) {
        throw new IllegalStateException(resource + " holds no inputs");
      }
      return xs;
    } catch (IOException e) {
      throw new UncheckedIOException(resource, e);
    }
  }

  /** {@code 0x} and sixteen hex digits, the raw bits of a double. */
  private static long parseBits(String line) {
    if (!line.matches("0x[0-9a-f]{16}")) {
      throw new IllegalArgumentException("not 0x and 16 hex digits: " + line);
    }
    return Long.parseUnsignedLong(line.substring(2), 16);
  }

  /** The arrays one after another. */
  static double[] concat(double[]... parts) {
    double[] xs = new double[Arrays.stream(parts).mapToInt(p -> p.length).sum()];
    int at = 0;
    for (double[] part : parts) {
      System.arraycopy(part, 0, xs, at, part.length);
      at += part.length;
    }
    return xs;
  }

  /** Two-argument inputs: {@code x[i]} goes with {@code y[i]}. */
  record Pairs(double[] x, double[] y) {}

  /** Every {@code (x, y)} with {@code x} from {@code xs} and {@code y} from {@code ys}. */
  static Pairs cross(double[] xs, double[] ys) {
    double[] x = new double[xs.length * ys.length];
    double[] y = new double[x.length];
    for (int i = 0; i < xs.length; i++) {
      for (int j = 0; j < ys.length; j++) {
        x[i * ys.length + j] = xs[i];
        y[i * ys.length + j] = ys[j];
      }
    }
    return new Pairs(x, y);
  }
}
