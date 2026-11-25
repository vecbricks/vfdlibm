/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The method cHypot below transcribes fdlibm 5.3's e_hypot.c, which carries this notice:
 *
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The entry gate of the hypot port (issue 34): {@code e_hypot.c} in scalar Java, line by line but
 * for line 60, which orders the arguments by magnitude as the port does (PROOFS.md, L21; issue
 * 173), against {@code StrictMath.hypot} by {@link PairBits#matches} with hypot's declared
 * candidates ({@link PairBits.Candidates#MAGNITUDES}), so raw bits on every lane but one of two
 * NaNs with different bits. The inputs reach every way out of the C ({@link Path}, each asserted
 * taken): the ratio word {@code 0x3c00000} (2^60), the scalings at {@code 2^500}, {@code 2^-500}
 * and the subnormal {@code b} (times 2^1022, with the words the C leaves stale), equal high words
 * with either low word larger, {@code a} near {@code 2b}, the zeros, infinities beside NaNs, NaNs
 * of every kind and sampled bits. The same check runs in a JVM of its own per JIT mode ({@link
 * JitMode}), since which candidate a sum of two NaNs keeps varies with the tier; that run asserts
 * the results, and prints which candidate won in each mode without asserting it, since which one
 * is not specified.
 *
 * <p>The vector port, {@link VFdlibm#hypot}, is checked on the same inputs against {@code
 * StrictMath.hypot} by the same comparison, at every width and at {@code SPECIES_MAX}, unmasked
 * and masked, with vectors that mix every way out of the C and the rare lanes in every subset of
 * lanes, the pairs of {@code hard-cases/hypot.txt}, {@code hard-cases/hypot-coremath.txt} and
 * {@code hard-cases/hypot-order.txt}, the C's stale words (PROOFS.md, L16) and the pairs whose
 * result by the C as written depends on the order of the arguments (L21).
 */
class HypotTest {

  private static final PairBits.Candidates MAGNITUDES = PairBits.Candidates.MAGNITUDES;

  // ---------------------------------------------------------------------------------------------
  // The C in scalar Java
  // ---------------------------------------------------------------------------------------------

  /**
   * The C ordered by magnitude, which is what {@code StrictMath} gave and what the port computes,
   * or with one change, to show that the inputs below see a change.
   */
  enum Variant {
    /**
     * e_hypot.c line by line but for line 60, which swaps where {@code |y| > |x|}, by the
     * sign-cleared bits compared as longs, and not where {@code hb > ha} (PROOFS.md, L21).
     */
    BY_MAGNITUDE,
    /** e_hypot.c line by line, line 60 by the high words alone. */
    AS_WRITTEN,
    /**
     * {@link #BY_MAGNITUDE} with lines 80-84's {@code ha} and {@code hb} read again from the
     * scaled {@code a} and {@code b}.
     */
    REFRESHED_WORDS,
    /** {@link #BY_MAGNITUDE} with line 63's early return of {@code a+b} dropped. */
    NO_EARLY_RETURN
  }

  /** The way out of the C a lane took: {@link #cHypot(double, double, Variant, long[])} counts. */
  enum Path {
    /** Line 63: the ratio exceeds 2^60. */
    RATIO,
    /** Lines 66-70: an infinity or a NaN. */
    NON_FINITE,
    /** Line 79: {@code b} is zero. */
    ZERO_B,
    /** Lines 73-75 then the medium path: scaled by 2^-600. */
    SCALED_DOWN,
    /** Lines 80-84 then the medium path: a subnormal {@code b}, times 2^1022. */
    SUBNORMAL_B,
    /** Lines 86-90 then the medium path: scaled by 2^600. */
    SCALED_UP,
    /** Lines 96-99: {@code w>b}. */
    W_ABOVE_B,
    /** Lines 101-108: {@code w<=b}. */
    W_NOT_ABOVE_B,
    /**
     * Line 60 with equal high words and {@code |y| > |x|}: swapped by magnitude, where line 60 as
     * written keeps {@code a = x} (L21), on a lane that takes lines 101-108, the split where the
     * order decides bits.
     */
    EQUAL_WORDS_SWAPPED,
    /** Lines 80-84 then lines 96-99: the stale {@code ha} split at line 97. */
    SUBNORMAL_B_W_ABOVE_B,
    /** Lines 80-84 then lines 101-108: the stale {@code hb} and {@code ha} split at 103 and 106. */
    SUBNORMAL_B_W_NOT_ABOVE_B
  }

  private static int hi(double d) {
    return (int) (Double.doubleToRawLongBits(d) >>> 32);
  }

  private static int lo(double d) {
    return (int) Double.doubleToRawLongBits(d);
  }

  /** {@code __HI(d) = word}: the high word replaced, the low word kept. */
  private static double withHi(double d, int word) {
    return Double.longBitsToDouble(
        ((long) word << 32) | (Double.doubleToRawLongBits(d) & 0xffffffffL));
  }

  private static void took(long[] paths, Path path) {
    if (paths != null) {
      paths[path.ordinal()]++;
    }
  }

  /** e_hypot.c in scalar Java, ordered by magnitude ({@link Variant#BY_MAGNITUDE}). */
  static double cHypot(double x, double y) {
    return cHypot(x, y, Variant.BY_MAGNITUDE, null);
  }

  /**
   * {@link #cHypot(double, double)} as {@code variant} says, counting into {@code paths} (by
   * {@link Path#ordinal()}, when not null) the ways out the lane took. The line numbers are
   * e_hypot.c's. The C's {@code sqrt} is {@code Math.sqrt}, the correctly rounded square root
   * (PROOFS.md, R4); as written every radicand is finite, while {@link Variant#NO_EARLY_RETURN}
   * may take {@code sqrt(+inf)} (as for {@code x = 0x1p1000, y = 1.0}).
   */
  static double cHypot(double x, double y, Variant variant, long[] paths) {
    double a;
    double b;
    double t1;
    double t2;
    double y1;
    double y2;
    double w;
    int j;
    int k;
    int ha;
    int hb;
    ha = hi(x) & 0x7fffffff; // 58
    hb = hi(y) & 0x7fffffff; // 59
    // 60: if(hb > ha) as written; by the sign-cleared bits otherwise (L21).
    boolean swap = variant == Variant.AS_WRITTEN ? hb > ha
        : (Double.doubleToRawLongBits(y) & Long.MAX_VALUE)
            > (Double.doubleToRawLongBits(x) & Long.MAX_VALUE);
    boolean equalWordsSwapped = swap && hb == ha;
    if (swap) { // 60
      a = y;
      b = x;
      j = ha;
      ha = hb;
      hb = j;
    } else {
      a = x;
      b = y;
    }
    a = withHi(a, ha); // 61
    b = withHi(b, hb); // 62
    if ((ha - hb) > 0x3c00000 && variant != Variant.NO_EARLY_RETURN) { // 63
      took(paths, Path.RATIO);
      return a + b;
    }
    k = 0; // 64
    if (ha > 0x5f300000) { // 65
      if (ha >= 0x7ff00000) { // 66
        w = a + b; // 67
        if (((ha & 0xfffff) | lo(a)) == 0) { // 68
          w = a;
        }
        if (((hb ^ 0x7ff00000) | lo(b)) == 0) { // 69
          w = b;
        }
        took(paths, Path.NON_FINITE);
        return w; // 70
      }
      ha -= 0x25800000; // 73
      hb -= 0x25800000;
      k += 600;
      a = withHi(a, ha); // 74
      b = withHi(b, hb); // 75
      took(paths, Path.SCALED_DOWN);
    }
    if (hb < 0x20b00000) { // 77
      if (hb <= 0x000fffff) { // 78
        if ((hb | lo(b)) == 0) { // 79
          took(paths, Path.ZERO_B);
          return a;
        }
        t1 = 0; // 80
        t1 = withHi(t1, 0x7fd00000); // 81
        b *= t1; // 82
        a *= t1; // 83
        k -= 1022; // 84
        if (variant == Variant.REFRESHED_WORDS) {
          ha = hi(a);
          hb = hi(b);
        }
        took(paths, Path.SUBNORMAL_B);
      } else {
        ha += 0x25800000; // 86
        hb += 0x25800000; // 87
        k -= 600; // 88
        a = withHi(a, ha); // 89
        b = withHi(b, hb); // 90
        took(paths, Path.SCALED_UP);
      }
    }
    w = a - b; // 94
    if (w > b) { // 95
      t1 = 0; // 96
      t1 = withHi(t1, ha); // 97
      t2 = a - t1; // 98
      w = Math.sqrt(t1 * t1 - (b * (-b) - t2 * (a + t1))); // 99
      took(paths, Path.W_ABOVE_B);
      if (k == -1022) {
        took(paths, Path.SUBNORMAL_B_W_ABOVE_B);
      }
    } else {
      a = a + a; // 101
      y1 = 0; // 102
      y1 = withHi(y1, hb); // 103
      y2 = b - y1; // 104
      t1 = 0; // 105
      t1 = withHi(t1, ha + 0x00100000); // 106
      t2 = a - t1; // 107
      w = Math.sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b))); // 108
      took(paths, Path.W_NOT_ABOVE_B);
      if (equalWordsSwapped) {
        // the split where the order of a and b decides bits (L21)
        took(paths, Path.EQUAL_WORDS_SWAPPED);
      }
      if (k == -1022) {
        took(paths, Path.SUBNORMAL_B_W_NOT_ABOVE_B);
      }
    }
    if (k != 0) { // 110
      t1 = 1.0; // 111
      t1 = withHi(t1, hi(t1) + (k << 20)); // 112
      return t1 * w; // 113
    } else {
      return w; // 114
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Inputs
  // ---------------------------------------------------------------------------------------------

  /** The high words e_hypot.c tests, as doubles of a zero low word and of two others each. */
  private static final int[] WORDS = Nightly.HYPOT_WORDS;

  /** NaNs of both signs, quiet and signalling, several payloads; pairs differ in one bit too. */
  private static final long[] NANS = {
    0x7ff8_0000_0000_0000L, 0xfff8_0000_0000_0000L, 0x7ff8_0000_0000_0001L,
    0xfff8_0000_0000_0001L, 0x7ff0_0000_0000_0001L, 0xfff0_0000_0000_0001L,
    0x7ff8_0000_0001_0000L, 0x7ff0_0000_0001_0000L, 0x7fff_ffff_ffff_ffffL,
    0xffff_ffff_ffff_ffffL, 0x7ff7_ffff_ffff_ffffL, 0xfff8_dead_beef_0001L,
    0x7ff0_0001_0000_0000L, 0x7ff8_0001_0000_0000L
  };

  private static double fromWords(int high, long low, boolean negative) {
    long hi = (high | (negative ? 0x80000000 : 0)) & 0xffffffffL;
    return Double.longBitsToDouble(hi << 32 | (low & 0xffffffffL));
  }

  /** The special values and {@link #WORDS} at the low words 0, 1 and all ones, both signs. */
  static double[] specials() {
    double[] words = new double[WORDS.length * 6];
    int at = 0;
    for (int word : WORDS) {
      for (long low : new long[] {0L, 1L, 0xffffffffL}) {
        words[at++] = fromWords(word, low, false);
        words[at++] = fromWords(word, low, true);
      }
    }
    double[] nans = new double[NANS.length];
    for (int i = 0; i < NANS.length; i++) {
      nans[i] = Double.longBitsToDouble(NANS[i]);
    }
    return Inputs.concat(Inputs.specials(0x1p60, 0x1p500, 0x1p-500, 0x1p1022, 2.0, 3.0, 4.0),
        words, nans);
  }

  /** Every pair of the special values. */
  static Inputs.Pairs specialPairs() {
    double[] specials = specials();
    return Inputs.cross(specials, specials);
  }

  /**
   * The fuzz's pair draws over e_hypot.c's words and points, a tenth at its gaps: the nightly's
   * own edges ({@link Nightly.PairFunction#HYPOT}), which list {@link #WORDS} and {@link
   * Nightly#HYPOT_GAPS}.
   */
  static final Fuzz.PairEdges EDGES = Nightly.PairFunction.HYPOT.edges();

  static Inputs.Pairs fuzzPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] pair = new double[2];
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      Fuzz.drawPair(random, EDGES, pair);
      xs[i] = pair[0];
      ys[i] = pair[1];
    }
    return new Inputs.Pairs(xs, ys);
  }

  /** A low word as the fuzz draws one: 0, 1, all ones, all ones less one, or random. */
  private static long lowWord(SplittableRandom random) {
    return switch (random.nextInt(5)) {
      case 0 -> 0L;
      case 1 -> 1L;
      case 2 -> 0xffffffffL;
      case 3 -> 0xfffffffeL;
      default -> random.nextLong() & 0xffffffffL;
    };
  }

  /** The pair of high words {@code ha >= hb}, random signs and low words, in a random order. */
  private static void put(SplittableRandom random, int ha, int hb, double[] xs, double[] ys,
      int i) {
    double a = fromWords(ha, lowWord(random), random.nextBoolean());
    double b = fromWords(hb, lowWord(random), random.nextBoolean());
    boolean swap = random.nextBoolean();
    xs[i] = swap ? b : a;
    ys[i] = swap ? a : b;
  }

  /**
   * Pairs at each scaling's word with the other word within line 63's gap, so that the scaling is
   * reached: {@code ha} within 2^16 words of {@code 0x5f300000}, {@code hb} within 2^16 of {@code
   * 0x20b00000} and of {@code 0x00100000}, and {@code ha} up to {@code 2^20} words of the largest
   * finite one; the other word is drawn at a gap from 0 to two words past {@code 0x3c00000}.
   */
  static Inputs.Pairs scalingPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      int gap = random.nextInt(4) == 0 ? 0x3c00000 + random.nextInt(-2, 3)
          : random.nextInt(0, 0x3c00003);
      int near = random.nextInt(-(1 << 16), 1 << 16);
      switch (random.nextInt(4)) {
        case 0 -> put(random, 0x5f300000 + near, 0x5f300000 + near - gap, xs, ys, i);
        case 1 -> {
          int hb = 0x20b00000 + near;
          put(random, hb + gap, hb, xs, ys, i);
        }
        case 2 -> {
          int hb = 0x00100000 + near;
          put(random, hb + gap, hb, xs, ys, i);
        }
        default -> {
          int ha = 0x7fefffff - random.nextInt(1 << 20);
          put(random, ha, ha - gap, xs, ys, i);
        }
      }
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * A subnormal {@code b} (or a zero, one draw in sixteen) beside an {@code a} from subnormal up to
   * 2^73 times {@code b}'s scale: the 2^1022 path of lines 80-84, where the C reads the words it
   * did not update, and line 63 on either side.
   */
  static Inputs.Pairs subnormalPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      long mantissa = random.nextInt(16) == 0 ? 0L : random.nextLong() & 0x000fffffffffffffL;
      double b = Double.longBitsToDouble(mantissa | (random.nextBoolean() ? Long.MIN_VALUE : 0L));
      double a = random.nextInt(4) == 0
          ? Double.longBitsToDouble(random.nextLong() & 0x000fffffffffffffL)
          : Math.scalb(1.0 + random.nextDouble(), random.nextInt(-1074, -950));
      a = random.nextBoolean() ? a : -a;
      boolean swap = random.nextBoolean();
      xs[i] = swap ? b : a;
      ys[i] = swap ? a : b;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * Equal high words with random low words, so either may be the larger ({@code w} negative when
   * {@code b} is): at random exponents, at each scaling's and among the subnormals.
   */
  static Inputs.Pairs equalWordPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      int word = switch (random.nextInt(4)) {
        case 0 -> Math.min(WORDS[random.nextInt(WORDS.length)], 0x7fefffff);
        case 1 -> random.nextInt(0, 0x00100000);
        default -> random.nextInt(0, 0x7ff00000);
      };
      put(random, word, word, xs, ys, i);
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * {@code a} near {@code 2b}, either side of line 95's {@code w>b}: {@code 2b} moved by up to 8
   * ulps, at random exponents and at the scalings' words.
   */
  static Inputs.Pairs twiceBPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      int exponent = switch (random.nextInt(4)) {
        case 0 -> random.nextInt(495, 505);
        case 1 -> random.nextInt(-505, -495);
        default -> random.nextInt(-1060, 1022);
      };
      double b = Math.scalb(1.0 + random.nextDouble(), exponent);
      double a = Double.longBitsToDouble(
          Double.doubleToRawLongBits(2.0 * b) + random.nextInt(-8, 9));
      a = random.nextBoolean() ? a : -a;
      b = random.nextBoolean() ? b : -b;
      boolean swap = random.nextBoolean();
      xs[i] = swap ? b : a;
      ys[i] = swap ? a : b;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * NaN beside NaN (random payloads, of random signs, and pairs one bit apart), beside both
   * infinities (line 69: {@code hypot(inf, NaN)} is {@code +inf}) and beside finite values.
   */
  static Inputs.Pairs nanPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      long nan = (random.nextLong() | 0x7ff0000000000000L) & (random.nextBoolean() ? -1L
          : Long.MAX_VALUE);
      if ((nan & 0x000fffffffffffffL) == 0) {
        nan |= 1;
      }
      long other = switch (random.nextInt(6)) {
        case 0, 1 -> {
          long second = random.nextLong() | 0x7ff0000000000000L;
          yield (second & 0x000fffffffffffffL) == 0 ? second | 1 : second;
        }
        case 2 -> {
          long flipped = nan ^ (1L << random.nextInt(52));
          yield (flipped & 0x000fffffffffffffL) == 0 ? flipped | 1 : flipped;
        }
        case 3 -> nan ^ Long.MIN_VALUE;
        case 4 -> random.nextBoolean() ? 0x7ff0000000000000L : 0xfff0000000000000L;
        default -> random.nextLong();
      };
      boolean swap = random.nextBoolean();
      xs[i] = Double.longBitsToDouble(swap ? other : nan);
      ys[i] = Double.longBitsToDouble(swap ? nan : other);
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * Pythagorean triples {@code (m^2-n^2, 2mn)}, whose hypot {@code m^2+n^2} is exact, scaled by
   * powers of two across each scaling and among the subnormals.
   */
  static Inputs.Pairs triplePairs() {
    int[] scales = {-1074, -1060, -1022, -700, -600, -550, -500, -450, 0, 450, 500, 550, 600, 900,
      960};
    List<double[]> pairs = new ArrayList<>();
    for (int m = 2; m <= 40; m++) {
      for (int n = 1; n < m; n++) {
        for (int scale : scales) {
          double p = Math.scalb((double) (m * m - n * n), scale);
          double q = Math.scalb((double) (2 * m * n), scale);
          pairs.add(new double[] {p, q});
          pairs.add(new double[] {-q, p});
        }
      }
    }
    double[] xs = new double[pairs.size()];
    double[] ys = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = pairs.get(i)[0];
      ys[i] = pairs.get(i)[1];
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * Random bits, uniform draws of Varka's baseline range, every normal exponent, huge magnitudes
   * near overflow, and subnormals: on both sides, and beside a small normal in either argument.
   */
  static Inputs.Pairs densePairs(long seed, int n) {
    return concat(
        new Inputs.Pairs(Inputs.sampled(seed, n), Inputs.sampled(seed + 1, n)),
        new Inputs.Pairs(Inputs.uniform(seed + 2, n, -10.0, 10.0),
            Inputs.uniform(seed + 3, n, -10.0, 10.0)),
        new Inputs.Pairs(Inputs.magnitudes(seed + 4, n, -1022, 1023),
            Inputs.magnitudes(seed + 5, n, -1022, 1023)),
        new Inputs.Pairs(Inputs.subnormals(seed + 6, n / 4), Inputs.subnormals(seed + 7, n / 4)),
        new Inputs.Pairs(Inputs.magnitudes(seed + 8, n / 8, -1022, -900),
            Inputs.subnormals(seed + 9, n / 8)),
        new Inputs.Pairs(Inputs.subnormals(seed + 10, n / 8),
            Inputs.magnitudes(seed + 11, n / 8, -1022, -900)),
        new Inputs.Pairs(Inputs.magnitudes(seed + 12, n / 8, 900, 1023),
            Inputs.magnitudes(seed + 13, n / 8, -1022, 1023)));
  }

  /**
   * The pairs of {@code hard-cases/hypot-order.txt}: equal high words at which the C as written
   * depends on the order of the arguments (L21), issue 173's first, each in both orders and four
   * sign combinations.
   */
  static Inputs.Pairs orderPairs() {
    return Inputs.hardCasePairs("hypot-order");
  }

  /**
   * The gate's inputs: {@code n} draws of each generator, the special pairs, the triples and the
   * order pairs.
   */
  static Inputs.Pairs everything(long seed, int n) {
    return concat(specialPairs(), triplePairs(), orderPairs(), fuzzPairs(seed, n),
        scalingPairs(seed + 1, n), subnormalPairs(seed + 2, n), equalWordPairs(seed + 3, n),
        twiceBPairs(seed + 4, n), nanPairs(seed + 5, n), densePairs(seed + 6, n));
  }

  /** The pairs one after another. */
  private static Inputs.Pairs concat(Inputs.Pairs... parts) {
    double[][] xs = new double[parts.length][];
    double[][] ys = new double[parts.length][];
    for (int i = 0; i < parts.length; i++) {
      xs[i] = parts[i].x();
      ys[i] = parts[i].y();
    }
    return new Inputs.Pairs(Inputs.concat(xs), Inputs.concat(ys));
  }

  /** The size of the generated parts in the suite: about 1.25 million pairs in all. */
  static final int DRAWS = 1 << 17;
  static final long SEED = 0x4879_706fL;

  // ---------------------------------------------------------------------------------------------
  // The check, in this JVM or in one per JIT mode
  // ---------------------------------------------------------------------------------------------

  /** What one pass over the inputs found: every mismatch counted, the first 100 kept. */
  record Tally(long checked, long mismatched, List<String> mismatches, long[] paths,
      NanTally strict,
      NanTally transcribed) {}

  /**
   * On the lanes of two NaNs with different bits: how many results were the first argument's
   * candidate, the second's, both (the two candidates coincide, as for NaNs that differ in sign or
   * the quiet bit only), and of those that were one argument's, how many were the argument with
   * the larger high word, which e_hypot.c makes its {@code a} (the first on equal words).
   */
  record NanTally(long first, long second, long both, long larger) {
    String show() {
      return "first " + first + ", second " + second + ", both " + both + ", larger word " + larger;
    }
  }

  /** The candidates' counts of {@code result} over the two-NaN lanes, into {@code into[0..3]}. */
  private static void count(long xb, long yb, long result, long[] into) {
    long first = PairBits.candidate(MAGNITUDES, xb);
    long second = PairBits.candidate(MAGNITUDES, yb);
    if (first == second) {
      into[2] += result == first ? 1 : 0;
      return;
    }
    boolean xLarger = (int) ((yb & Long.MAX_VALUE) >>> 32) <= (int) ((xb & Long.MAX_VALUE) >>> 32);
    if (result == first) {
      into[0]++;
      into[3] += xLarger ? 1 : 0;
    } else if (result == second) {
      into[1]++;
      into[3] += xLarger ? 0 : 1;
    }
  }

  /** Every pair of {@code p}, the transcription against {@code StrictMath.hypot}. */
  static Tally check(Inputs.Pairs p) {
    long[] paths = new long[Path.values().length];
    long[] strict = new long[4];
    long[] transcribed = new long[4];
    List<String> mismatches = new ArrayList<>();
    long mismatched = 0;
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      double want = StrictMath.hypot(x, y);
      double got = cHypot(x, y, Variant.BY_MAGNITUDE, paths);
      if (!PairBits.matches(MAGNITUDES, x, y, want, got) && mismatched++ < 100) {
        mismatches.add("hypot(" + Oracle.hex(x) + ", " + Oracle.hex(y) + "): StrictMath "
            + PairBits.expected(MAGNITUDES, x, y, want) + ", transcription " + Oracle.hex(got));
      }
      long xb = Double.doubleToRawLongBits(x);
      long yb = Double.doubleToRawLongBits(y);
      if (PairBits.bothNaNWithDifferentBits(xb, yb)) {
        count(xb, yb, Double.doubleToRawLongBits(want), strict);
        count(xb, yb, Double.doubleToRawLongBits(got), transcribed);
      }
    }
    return new Tally(p.x().length, mismatched, mismatches, paths,
        new NanTally(strict[0], strict[1], strict[2], strict[3]),
        new NanTally(transcribed[0], transcribed[1], transcribed[2], transcribed[3]));
  }

  /** How many pairs of {@code p} the variant's result does not match {@code StrictMath} on. */
  static long differing(Inputs.Pairs p, Variant variant) {
    long n = 0;
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      n += PairBits.matches(MAGNITUDES, x, y, StrictMath.hypot(x, y),
          cHypot(x, y, variant, null)) ? 0 : 1;
    }
    return n;
  }

  /**
   * The check in a JVM of its own: {@code [draws [passes [variants]]]}, by default {@link #DRAWS}
   * and 3. It prints the JVM's arguments, each pass's count of pairs, mismatches (the first 100
   * with their bits) and the candidates of the two-NaN lanes, then, given {@code variants}, how
   * many pairs each {@link Variant} changes, and exits 1 if any pass has a mismatch, no two-NaN
   * lane of distinct candidates or a {@link Path} never taken.
   *
   * <pre>
   *   java -Xint --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
   *     io.github.vecbricks.vfdlibm.HypotTest 0x100000 1 variants
   * </pre>
   */
  public static void main(String[] args) {
    int draws = args.length > 0 ? Integer.decode(args[0]) : DRAWS;
    int passes = args.length > 1 ? Integer.parseInt(args[1]) : 3;
    System.out.println("jvm: " + ManagementFactory.getRuntimeMXBean().getInputArguments()
        + " | " + System.getProperty("java.vm.info"));
    Inputs.Pairs p = everything(SEED, draws);
    boolean failed = false;
    for (int pass = 1; pass <= passes; pass++) {
      Tally t = check(p);
      System.out.println("pass " + pass + ": checked " + t.checked() + ", mismatches "
          + t.mismatched());
      System.out.println("pass " + pass + ": StrictMath " + t.strict().show());
      System.out.println("pass " + pass + ": transcription " + t.transcribed().show());
      t.mismatches().forEach(m -> System.out.println("MISMATCH " + m));
      failed |= t.mismatched() > 0 || t.checked() == 0;
      if (t.strict().first() + t.strict().second() == 0) {
        System.out.println("pass " + pass + ": no two-NaN lane of distinct candidates");
        failed = true;
      }
      for (Path path : Path.values()) {
        if (t.paths()[path.ordinal()] == 0) {
          System.out.println("pass " + pass + ": " + path + " is never taken");
          failed = true;
        }
      }
    }
    for (Variant variant : Variant.values()) {
      if (args.length > 2 && args[2].equals("variants") && variant != Variant.BY_MAGNITUDE) {
        System.out.println("variant " + variant + ": differs on " + differing(p, variant));
      }
    }
    System.out.println(failed ? "result: FAILED" : "result: ok");
    System.exit(failed ? 1 : 0);
  }

  /** The JIT modes the gate runs the check in, each a JVM of its own. */
  enum JitMode {
    INTERPRETER("-Xint"),
    C1_ONLY("-XX:TieredStopAtLevel=1"),
    C2_ONLY("-XX:-TieredCompilation"),
    TIERED(null);

    final String flag;

    JitMode(String flag) {
      this.flag = flag;
    }
  }

  /** A line of {@code -XX:+PrintCompilation}'s log: a time stamp, a compile id and a method. */
  static final java.util.regex.Pattern COMPILE_LINE = java.util.regex.Pattern.compile(
      "^\\s*\\d+\\s+\\d+ [%sbn! ]{5}");

  /**
   * How long a child JVM may take: a generous bound that keeps a hung child from stalling the
   * suite.
   */
  static final int CHILD_SECONDS = 60;

  /**
   * The output of {@link #main} run in a JVM with {@code mode}'s flag; fails on a bad exit. The
   * child inherits no option from {@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS} or {@code
   * _JAVA_OPTIONS}, which could change its mode.
   */
  static String runInJvm(JitMode mode, int draws, int passes)
      throws IOException, InterruptedException {
    return runInJvm(HypotTest.class, "hypot gate", mode, draws, passes);
  }

  /**
   * {@link #runInJvm(JitMode, int, int)} for the gate whose {@code main} is {@code gate}'s, its
   * output printed under {@code label}: {@code PowTest} runs its children by it too.
   */
  static String runInJvm(Class<?> gate, String label, JitMode mode, int draws, int passes)
      throws IOException, InterruptedException {
    return runInJvm(gate, label, mode, List.of(), List.of(Integer.toString(draws),
        Integer.toString(passes)));
  }

  /** {@link #runInJvm(Class, String, JitMode, int, int)} with its own JVM options and arguments. */
  static String runInJvm(Class<?> gate, String label, JitMode mode, List<String> options,
      List<String> args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
    if (mode.flag != null) {
      command.add(mode.flag);
    }
    command.addAll(options);
    command.addAll(List.of("-Xmx256m", "--add-modules", "jdk.incubator.vector", "-cp",
        System.getProperty("java.class.path"), gate.getName()));
    command.addAll(args);
    // ByteArrayOutputStream is synchronized, so a timeout can report what was read so far.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().keySet().removeAll(
        List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
    Process process = builder.start();
    try {
      CompletableFuture<Void> drained = CompletableFuture.runAsync(() -> {
        try (InputStream in = process.getInputStream()) {
          in.transferTo(bytes);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      if (!process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError(label + ", " + mode + ": the JVM did not finish in "
            + CHILD_SECONDS + " s; its output:\n" + bytes.toString(StandardCharsets.UTF_8));
      }
      drained.join();
    } finally {
      // a JVM still running here (a timeout, an interrupt) is not left behind
      process.destroyForcibly();
    }
    String text = bytes.toString(StandardCharsets.UTF_8);
    // A child's compile log (-XX:+PrintCompilation) is printed only for hypot's own methods.
    String shown = text.lines().filter(l -> !COMPILE_LINE.matcher(l).find()
        || l.contains("vfdlibm.Hypot::")).collect(java.util.stream.Collectors.joining("\n"));
    System.out.println(label + ", " + mode + ":\n" + shown);
    assertEquals(0, process.exitValue(), label + ", " + mode + ":\n" + shown);
    return text;
  }

  // ---------------------------------------------------------------------------------------------
  // The tests
  // ---------------------------------------------------------------------------------------------

  /**
   * The gate: the transcription is {@code StrictMath.hypot} on every pair (a lane of two NaNs
   * with different bits by {@link PairBits}, where both results must be a candidate), and the
   * pairs take every way out of the C.
   */
  @Test
  void scalarTranscriptionIsStrictMath() {
    Tally t = check(everything(SEED, DRAWS));
    assertEquals(0, t.mismatched(), String.join("\n", t.mismatches()));
    for (Path path : Path.values()) {
      assertTrue(t.paths()[path.ordinal()] > 0, path + " is never taken");
    }
    NanTally s = t.strict();
    assertTrue(s.first() > 0 || s.second() > 0, "no two-NaN lane of distinct candidates");
  }

  /**
   * The same check in a JVM per JIT mode, where {@code StrictMath} and the transcription each run
   * in that mode's tiers: every pass of every mode passes, each JVM had the mode's flag, and its
   * {@code java.vm.info} says interpreted mode for {@code -Xint} and mixed mode for the others.
   * That C1 or C2 compiled either method is not checked; the output shows which candidate each
   * mode's {@code StrictMath} returned, and that is not asserted, since it is not specified.
   *
   * <p>The children do not depend on the vector width, so they run only where the test JVM has no
   * width option ({@code -XX:MaxVectorSize}, {@code -XX:UseAVX}), which in ci.yml and by default
   * is where {@code vfdlibm.test.jvmArgs} is empty: in ci.yml the rows
   * "x86 128+256 bits, 512 on AVX-512 runners" and "Arm 128 bits" (once per architecture), and a
   * plain {@code mvn -B verify}, {@code dev/release.sh}'s included. The other rows skip them.
   */
  @ParameterizedTest
  @EnumSource(JitMode.class)
  void scalarTranscriptionIsStrictMathInEveryJitMode(JitMode mode) throws Exception {
    assumeNoWidthOption();
    assertRanInModeAndPassed(mode, runInJvm(mode, DRAWS >> 2, 3));
  }

  /** Skips a test in a JVM with a width option ({@code -XX:MaxVectorSize}, {@code -XX:UseAVX}). */
  static void assumeNoWidthOption() {
    List<String> own = ManagementFactory.getRuntimeMXBean().getInputArguments();
    assumeTrue(own.stream().noneMatch(
        a -> a.startsWith("-XX:MaxVectorSize=") || a.startsWith("-XX:UseAVX=")),
        "a row with width options: " + own);
  }

  /**
   * A child's output, {@code text}: its JVM had {@code mode}'s flag, its {@code java.vm.info}
   * says interpreted mode for {@code -Xint} and mixed mode for the others, and it passed.
   */
  static void assertRanInModeAndPassed(JitMode mode, String text) {
    String jvm = text.lines().filter(l -> l.startsWith("jvm: ")).findFirst().orElseThrow();
    if (mode.flag != null) {
      assertTrue(jvm.contains(mode.flag), jvm);
    } else {
      assertTrue(!jvm.contains("-Xint") && !jvm.contains("Tiered"), jvm);
    }
    assertTrue(jvm.contains(mode == JitMode.INTERPRETER ? "interpreted mode" : "mixed mode"), jvm);
    assertTrue(text.contains("result: ok"), text);
  }

  /**
   * The inputs see the transcription changed: with line 60 as written (by the high words alone),
   * the words of lines 80-84 refreshed, or without line 63's return, the variant differs from
   * {@code StrictMath} on some pair, so the gate's no-mismatch result is not that of inputs too
   * weak to tell.
   */
  @ParameterizedTest
  @EnumSource(value = Variant.class, names = "BY_MAGNITUDE", mode = EnumSource.Mode.EXCLUDE)
  void theInputsSeeAChangedTranscription(Variant variant) {
    assertTrue(differing(everything(SEED + 1, DRAWS >> 3), variant) > 0, variant.name());
  }

  /** Line 69: an infinity beside a NaN is {@code +inf}, either order and either sign. */
  @Test
  void anInfinityBesideANaNIsPositiveInfinity() {
    long inf = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
    for (long nan : NANS) {
      for (double i : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
        double n = Double.longBitsToDouble(nan);
        assertEquals(inf, Double.doubleToRawLongBits(cHypot(i, n)));
        assertEquals(inf, Double.doubleToRawLongBits(cHypot(n, i)));
        assertEquals(inf, Double.doubleToRawLongBits(StrictMath.hypot(i, n)));
        assertEquals(inf, Double.doubleToRawLongBits(StrictMath.hypot(n, i)));
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The vector port
  // ---------------------------------------------------------------------------------------------

  /**
   * L22's observed hinge in every JIT mode: lines 66-70 are blends whose NaN results are the vector
   * sum {@code a+b}, and which NaN a sum keeps is fixed by neither IEEE 754 nor the JLS. So the
   * vector port runs on the pairs of {@link #specialPairs} with an infinity or a NaN, and on {@link
   * #nanPairs} (NaNs of both signs, quiet and
   * signalling, several payloads, beside NaNs, infinities and finite values) in a JVM per mode, at
   * every width the host has, against {@code StrictMath.hypot} by raw bits but on the lane of two
   * NaNs ({@code MAGNITUDES}). A sum that returned a canonical NaN, or kept a NaN's sign, fails on
   * the one-NaN lanes. The children run on every row, those with width options too, where {@link
   * #scalarTranscriptionIsStrictMathInEveryJitMode}'s skip: the parent's {@code -XX:UseAVX} and
   * {@code -XX:MaxVectorSize} are passed to them.
   */
  @ParameterizedTest
  @EnumSource(JitMode.class)
  void nanLanesAreStrictMathInEveryJitMode(JitMode mode) throws Exception {
    // The parent's width options go to the children, so that a row run as AVX2 checks AVX2's
    // lowering of the sum in every mode, where the scalar children skip such rows.
    List<String> width = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(a -> a.startsWith("-XX:MaxVectorSize=") || a.startsWith("-XX:UseAVX=")).toList();
    // A JVM per width the host has, so that the compiled body is the one for that width and is
    // not deoptimized by a change of shape. In the compiled modes, 15,000 unchecked calls first
    // and -Xbatch, and the compile log must show Hypot::hypot compiled at the mode's tier, and not
    // thrown away, before the checked pass; interpreted, one checked pass and no warm-up.
    boolean interpreted = mode == JitMode.INTERPRETER;
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      String text = runInJvm(NanLanes.class, "hypot NaN lanes, " + bits + " bits", mode,
          concat(width, interpreted ? List.of() : List.of("-Xbatch", "-XX:+PrintCompilation")),
          List.of(Integer.toString(1 << 9), "1", interpreted ? "0" : "15000",
              Integer.toString(bits)));
      assertRanInModeAndPassed(mode, text);
      if (!interpreted) {
        String level = switch (mode) {
          case C1_ONLY -> "1";
          case C2_ONLY -> "";
          default -> "4";
        };
        assertTrue(compiledAtCheck(text, level),
            mode + ", " + bits + " bits: Hypot::hypot was not compiled at its tier when checked");
      }
    }
  }

  /**
   * Whether the child's compile log, before its line {@code "checking"}, holds a compile of
   * {@code Hypot::hypot} at {@code level} ({@code ""} for C2 without tiers) that was not made not
   * entrant before that line, and none of those compiles is made not entrant between that line and
   * the child's result: so the whole checked pass ran a compiled body of that tier.
   */
  static boolean compiledAtCheck(String text, String level) {
    java.util.regex.Pattern compile = java.util.regex.Pattern.compile(
        "^\\s*\\d+\\s+(\\d+)\\s+b\\s+" + (level.isEmpty() ? "" : level + "\\s+")
        + "io\\.github\\.vecbricks\\.vfdlibm\\.Hypot::hypot \\(");
    java.util.Set<String> live = new java.util.HashSet<>();
    boolean checking = false;
    for (String line : text.lines().toList()) {
      if (line.equals("checking")) {
        if (live.isEmpty()) {
          return false;
        }
        checking = true;
        continue;
      }
      if (checking && line.startsWith("result: ")) {
        return true;
      }
      java.util.regex.Matcher m = compile.matcher(line);
      if (m.find()) {
        live.add(m.group(1));
      } else if (line.contains("vfdlibm.Hypot::hypot") && line.contains("made not entrant")) {
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("^\\s*\\d+\\s+(\\d+)\\s")
            .matcher(line);
        if (id.find() && live.remove(id.group(1)) && checking) {
          return false;
        }
      }
    }
    return false;
  }

  /** Two lists of JVM options, one after the other. */
  private static List<String> concat(List<String> a, List<String> b) {
    List<String> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  /** The pairs of {@code p} with an infinity or a NaN in either argument: lines 66-70's lanes. */
  static Inputs.Pairs nonFinite(Inputs.Pairs p) {
    int n = 0;
    double[] x = new double[p.x().length];
    double[] y = new double[p.x().length];
    for (int i = 0; i < p.x().length; i++) {
      if (!Double.isFinite(p.x()[i]) || !Double.isFinite(p.y()[i])) {
        x[n] = p.x()[i];
        y[n] = p.y()[i];
        n++;
      }
    }
    return new Inputs.Pairs(java.util.Arrays.copyOf(x, n), java.util.Arrays.copyOf(y, n));
  }

  /**
   * The child of {@link #nanLanesAreStrictMathInEveryJitMode}: args are draws, checked passes,
   * the unchecked warm-up calls made before them, and the width in bits.
   */
  static final class NanLanes {
    private NanLanes() {}

    public static void main(String[] args) {
      int draws = Integer.decode(args[0]);
      int passes = Integer.parseInt(args[1]);
      int warmCalls = Integer.parseInt(args[2]);
      VectorSpecies<Double> species =
          VectorSpecies.of(double.class, VectorShape.forBitSize(Integer.parseInt(args[3])));
      System.out.println("jvm: " + ManagementFactory.getRuntimeMXBean().getInputArguments()
          + " | " + System.getProperty("java.vm.info"));
      Inputs.Pairs p = concat(nonFinite(specialPairs()), nanPairs(9100L, draws));
      int n = species.length();
      // Calls, not passes: C2 without tiers compiles after 10,000 (CompileThreshold), and a pass is
      // fewer calls the wider the vector.
      int calls = 0;
      while (calls < warmCalls) {
        for (int j = 0; j + n <= p.x().length && calls < warmCalls; j += n, calls++) {
          VFdlibm.hypot(DoubleVector.fromArray(species, p.x(), j),
              DoubleVector.fromArray(species, p.y(), j));
        }
      }
      System.out.println("warm-up: " + calls + " calls, unchecked");
      System.out.println("checking");
      boolean failed = false;
      for (int pass = 1; pass <= passes; pass++) {
        try {
          assertHypot(species, p);
          System.out.println("pass " + pass + ": " + p.x().length + " pairs at "
              + species.vectorBitSize() + " bits");
        } catch (AssertionError e) {
          System.out.println("pass " + pass + ": " + e.getMessage());
          failed = true;
        }
      }
      System.out.println(failed ? "result: FAILED" : "result: ok");
      System.exit(failed ? 1 : 0);
    }
  }

  /** {@link VFdlibm#hypot} unmasked at {@code species} over the pairs {@code p}. */
  private static void assertHypot(VectorSpecies<Double> species, Inputs.Pairs p) {
    Oracle.assertPair("VFdlibm.hypot", species, MAGNITUDES, VFdlibm::hypot, StrictMath::hypot,
        p.x(), p.y());
  }

  /** {@link VFdlibm#hypot} masked, under seeded random masks, all-true and all-false. */
  private static void assertHypotMasked(VectorSpecies<Double> species, Inputs.Pairs p, long seed) {
    Oracle.assertPairMasked("VFdlibm.hypot", species, MAGNITUDES, VFdlibm::hypot,
        StrictMath::hypot, p.x(), p.y(), seed);
  }

  private static Inputs.Pairs hardCasePairs() {
    return Inputs.hardCasePairs("hypot");
  }

  /** The pairs taken from CORE-MATH's list by {@code dev/coremath-hard-cases.py}. */
  private static Inputs.Pairs coreMathPairs() {
    return Inputs.hardCasePairs("hypot-coremath");
  }

  /** A smaller {@link #everything}, with the hard cases, for the masked forms. */
  private static Inputs.Pairs everythingSmall(long seed) {
    return concat(everything(seed, 1 << 12), hardCasePairs(), coreMathPairs());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void specialValues(VectorWidth width) {
    assertHypot(width.species(),
        concat(specialPairs(), nanPairs(9000L + width.ordinal(), 1 << 14)));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void triples(VectorWidth width) {
    assertHypot(width.species(), triplePairs());
  }

  /** The words and gaps the C tests, as the nightly's fuzz draws them. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void edges(VectorWidth width) {
    assertHypot(width.species(), fuzzPairs(9100L + width.ordinal(), DRAWS));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void scalings(VectorWidth width) {
    assertHypot(width.species(), scalingPairs(9200L + width.ordinal(), DRAWS));
  }

  /** The 2^1022 scaling, which reads the words it left stale (PROOFS.md, L16). */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void subnormalB(VectorWidth width) {
    assertHypot(width.species(), subnormalPairs(9300L + width.ordinal(), DRAWS));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void equalWordsAndTwiceB(VectorWidth width) {
    assertHypot(width.species(), concat(equalWordPairs(9400L + width.ordinal(), DRAWS),
        twiceBPairs(9450L + width.ordinal(), DRAWS)));
  }

  /**
   * Pairs of one high word with both low words random ({@link Inputs#sharedWordPairs}), a quarter
   * at e_hypot.c's words: the lanes of one high word in general. The pairs where line 60 and the
   * port's order differ are about 2 in 10^8 of these, so what catches L21's fault is {@link
   * #orderPairsInEveryLane}, not this.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sharedHighWords(VectorWidth width) {
    assertHypot(width.species(), Inputs.sharedWordPairs(9480L + width.ordinal(), DRAWS, WORDS));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledAndDense(VectorWidth width) {
    assertHypot(width.species(), densePairs(9500L + 16L * width.ordinal(), DRAWS));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedPairs(VectorWidth width) {
    assertHypot(width.species(), hardCasePairs());
  }

  /**
   * {@code hard-cases/hypot-coremath.txt}: exact ties and results within {@code 2^-60} ulp of a
   * midpoint or {@code 2^-105} ulp of a double, from CORE-MATH's list ({@link HardCasePairsTest}
   * checks the claims).
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void coreMathListPairs(VectorWidth width) {
    assertHypot(width.species(), coreMathPairs());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertHypotMasked(width.species(), everythingSmall(9600L), 9601L);
  }

  /** SPECIES_MAX, which {@link VectorWidth} does not run: its int species is another shape. */
  @Test
  void maxSpecies() {
    Inputs.Pairs p = everythingSmall(9700L);
    assertHypot(DoubleVector.SPECIES_MAX, p);
    assertHypotMasked(DoubleVector.SPECIES_MAX, p, 9701L);
  }

  private static double bits(long b) {
    return Double.longBitsToDouble(b);
  }

  /** An infinity or a NaN in either argument: line 63's return, or lines 66-70's. */
  private static final double[][] RARE_LANES = {
    {Double.NaN, 1.0}, {1.0, Double.NaN}, {Double.POSITIVE_INFINITY, Double.NaN},
    {Double.NaN, Double.NEGATIVE_INFINITY},
    {bits(0xfff4_0000_0000_0456L), bits(0x7ff8_0000_0000_0123L)},
    {Double.NEGATIVE_INFINITY, 1.0e-300}, {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
    {Double.NaN, 0x1p-1060}, {1.0e300, bits(0xfff0_0000_0000_0001L)}, {-0.0, Double.NaN},
  };

  /** Pairs the vector computes, one for each way out of the C ({@link Path}) and its forms. */
  private static final double[][] GENERAL_LANES = {
    {3.0, 4.0}, {5.0, -1.0}, {1.0, 0x1p-70}, {0x1p-70, -1.0}, {3.0, 0.0}, {0x1p-1060, -0.0},
    {0x1p600, 0x1p580}, {-0x1.8p700, 0x1p690}, {0x1p-600, 0x1.4p-590}, {0x1p-1030, 0x1p-1040},
    {0x1.8p-1060, 0x1p-1060}, {bits(0x3ff0_0000_0000_0001L), bits(0x3ff0_0000_ffff_ffffL)},
    {bits(0x0008_0000_0000_0001L), 0x1p-1000}, {0x1.fffffffffffffp1023, 0x1p1000},
  };

  /**
   * One vector for every subset of a vector's lanes ({@link ExpTest#rareLaneSubsets}), the
   * subset's lanes rare and the others taking every way out of the C in turn, so that a vector
   * holds rare lanes beside lanes of each scaling and form, in every position and mix.
   */
  static Inputs.Pairs everySubsetOfLanesRare(int lanes) {
    long[] subsets = ExpTest.rareLaneSubsets(lanes);
    double[] xs = new double[subsets.length * lanes];
    double[] ys = new double[xs.length];
    int rare = 0;
    int general = 0;
    for (int s = 0; s < subsets.length; s++) {
      for (int lane = 0; lane < lanes; lane++) {
        boolean isRare = (subsets[s] >>> lane & 1L) != 0;
        double[] pair = isRare ? RARE_LANES[rare++ % RARE_LANES.length]
            : GENERAL_LANES[general++ % GENERAL_LANES.length];
        xs[s * lanes + lane] = pair[0];
        ys[s * lanes + lane] = pair[1];
      }
    }
    return new Inputs.Pairs(xs, ys);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void everySubsetOfLanesRare(VectorWidth width) {
    assertEverySubset(width.species());
  }

  @Test
  void everySubsetOfLanesRareAtMaxSpecies() {
    assertEverySubset(DoubleVector.SPECIES_MAX);
  }

  private static void assertEverySubset(VectorSpecies<Double> species) {
    Inputs.Pairs p = everySubsetOfLanesRare(species.length());
    assertHypot(species, p);
    assertHypotMasked(species, p, 9800L);
  }

  /**
   * L16's hinge (PROOFS.md): the C reads {@code ha} and {@code hb} stale after the 2^1022
   * scaling, and that reading decides bits. On the subnormal-{@code b} pairs the C (ordered by
   * magnitude, L21) is {@code StrictMath} (the gate), the C with the words read again from the
   * scaled values differs from it on some of them, and the port's vector lanes equal the C, bit
   * for bit, at every width: so the port keeps the words stale, and a port that refreshed them
   * would fail.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void theStaleWordsAreRead(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    Inputs.Pairs p = subnormalPairs(9900L + width.ordinal(), 1 << 16);
    long refreshedDiffers = 0;
    int n = species.length();
    for (int i = 0; i + n <= p.x().length; i += n) {
      double[] got = VFdlibm.hypot(DoubleVector.fromArray(species, p.x(), i),
          DoubleVector.fromArray(species, p.y(), i)).toArray();
      for (int lane = 0; lane < n; lane++) {
        double x = p.x()[i + lane];
        double y = p.y()[i + lane];
        assertEquals(Double.doubleToRawLongBits(cHypot(x, y)),
            Double.doubleToRawLongBits(got[lane]),
            "hypot(" + Oracle.hex(x) + ", " + Oracle.hex(y) + ")");
        if (!PairBits.matches(MAGNITUDES, x, y, StrictMath.hypot(x, y),
            cHypot(x, y, Variant.REFRESHED_WORDS, null))) {
          refreshedDiffers++;
        }
      }
    }
    assertTrue(refreshedDiffers > 0, "no pair shows the refreshed words");
  }

  /**
   * L16's witnesses (PROOFS.md): {@code x}, {@code y}, the C ordered by magnitude (which is {@code
   * StrictMath.hypot}; their high words differ, so it is the C as written too) and the C with the
   * words of lines 80-84 read again, two of each form of the medium path.
   */
  static final long[][] STALE_WORD_WITNESSES = {
    // w <= b (lines 101-108)
    {0x000db0d196a90b9aL, 0x800d27338d43c3cfL, 0x0012fc39ebdadea2L, 0x0012fc39ebdadea3L},
    {0x000e6d4294dc0a1eL, 0x000b85f92b97b424L, 0x001276c9661f63f5L, 0x001276c9661f63f6L},
    // w > b (lines 96-99)
    {0x80715ef1f174dc02L, 0x000c253f97a162e2L, 0x00715f35e084bb17L, 0x00715f35e084bb16L},
    {0x80000000091e440eL, 0x0009982a438c0f4bL, 0x0009982a438c0f50L, 0x0009982a438c0f4fL},
  };

  /** L16's counter-example: at each witness the stale and the refreshed words differ. */
  @Test
  void staleWordWitnesses() {
    for (long[] w : STALE_WORD_WITNESSES) {
      double x = bits(w[0]);
      double y = bits(w[1]);
      String where = "hypot(" + Oracle.hex(x) + ", " + Oracle.hex(y) + ")";
      long[] paths = new long[Path.values().length];
      assertEquals(w[2], Double.doubleToRawLongBits(StrictMath.hypot(x, y)), where);
      assertEquals(w[2], Double.doubleToRawLongBits(cHypot(x, y, Variant.BY_MAGNITUDE, paths)),
          where);
      assertEquals(w[3],
          Double.doubleToRawLongBits(cHypot(x, y, Variant.REFRESHED_WORDS, null)), where);
      assertTrue(paths[Path.SUBNORMAL_B.ordinal()] > 0, where + " takes the 2^1022 scaling");
    }
  }

  /** L16's vector tie: each witness at every lane of a vector, the others general, every width. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void staleWordWitnessesInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    int n = species.length();
    List<double[]> pairs = new ArrayList<>();
    for (long[] w : STALE_WORD_WITNESSES) {
      for (int at = 0; at < n; at++) {
        for (int lane = 0; lane < n; lane++) {
          pairs.add(lane == at ? new double[] {bits(w[0]), bits(w[1])}
              : GENERAL_LANES[(at + lane) % GENERAL_LANES.length]);
        }
      }
    }
    double[] xs = new double[pairs.size()];
    double[] ys = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      xs[i] = pairs.get(i)[0];
      ys[i] = pairs.get(i)[1];
    }
    assertHypot(species, new Inputs.Pairs(xs, ys));
    assertHypotMasked(species, new Inputs.Pairs(xs, ys), 9850L + width.ordinal());
  }

  // ---------------------------------------------------------------------------------------------
  // L21: the order of the arguments
  // ---------------------------------------------------------------------------------------------

  /**
   * Issue 173's witness (PROOFS.md, L21): {@code x} and {@code y} of one high word, {@code |y|}
   * the larger, the C as written on {@code (x, y)}, and {@code StrictMath.hypot} in either order,
   * which is the C as written on {@code (y, x)}.
   */
  static final long[] ORDER_WITNESS = {
    0x5f201b45bbc5eac3L, 0x5f201b45fffffffeL, 0x5f26c7304042d062L, 0x5f26c7304042d063L
  };

  private static long raw(double d) {
    return Double.doubleToRawLongBits(d);
  }

  /**
   * L21's witness, in scalar Java: the high words are equal and {@code |y| > |x|}; the C as
   * written gives {@code ...d062} on {@code (x, y)} and {@code ...d063} on {@code (y, x)};
   * {@code StrictMath.hypot} and the C ordered by magnitude give {@code ...d063} in both orders.
   */
  @Test
  void orderWitness() {
    double x = bits(ORDER_WITNESS[0]);
    double y = bits(ORDER_WITNESS[1]);
    assertEquals(hi(x) & 0x7fffffff, hi(y) & 0x7fffffff);
    assertTrue(Math.abs(y) > Math.abs(x));
    assertEquals(ORDER_WITNESS[2], raw(cHypot(x, y, Variant.AS_WRITTEN, null)));
    assertEquals(ORDER_WITNESS[3], raw(cHypot(y, x, Variant.AS_WRITTEN, null)));
    for (double[] p : new double[][] {{x, y}, {y, x}}) {
      assertEquals(ORDER_WITNESS[3], raw(StrictMath.hypot(p[0], p[1])));
      assertEquals(ORDER_WITNESS[3], raw(cHypot(p[0], p[1])));
    }
  }

  /**
   * L21's hinge on {@code hard-cases/hypot-order.txt}, every pair: one high word, the C as
   * written different by the order of the arguments, and {@code StrictMath.hypot} the same in
   * both orders and equal to the C ordered by magnitude; so the file's pairs are what its header
   * says, and a port ordered as line 60 is written fails on one order of each.
   */
  @Test
  void orderPairsDependOnTheOrderInTheCAsWritten() {
    Inputs.Pairs p = orderPairs();
    assertEquals(160, p.x().length);
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      String where = "hypot(" + Oracle.hex(x) + ", " + Oracle.hex(y) + ")";
      assertEquals(hi(x) & 0x7fffffff, hi(y) & 0x7fffffff, where);
      assertTrue(raw(cHypot(x, y, Variant.AS_WRITTEN, null))
          != raw(cHypot(y, x, Variant.AS_WRITTEN, null)), where);
      long want = raw(StrictMath.hypot(x, y));
      assertEquals(want, raw(StrictMath.hypot(y, x)), where);
      assertEquals(want, raw(cHypot(x, y)), where);
      assertEquals(want, raw(cHypot(y, x)), where);
    }
  }

  /**
   * L21's step (a): where the high words of {@code |x|} and {@code |y|} differ, the swap by the
   * sign-cleared bits is line 60's swap by the words, on sampled bits and on pairs whose words
   * differ by one, with low words 0, 1 and all ones; and where they are equal line 60 never
   * swaps, while the swap by the bits does where {@code |y|}'s low word is the larger.
   */
  @Test
  void theMagnitudeSwapIsLine60sWhereTheHighWordsDiffer() {
    SplittableRandom random = new SplittableRandom(0x4c3231L);
    long[] lows = {0L, 1L, 0xffffffffL};
    long equalSwapped = 0;
    for (int i = 0; i < 1 << 20; i++) {
      long xb = random.nextLong();
      long yb = switch (i & 3) {
        case 0 -> random.nextLong();
        case 1 -> xb + (1L << 32);
        case 2 -> xb - (1L << 32);
        default -> (xb & 0xffffffff00000000L) | (random.nextLong() & 0xffffffffL);
      };
      if ((i & 3) == 1 || (i & 3) == 2) {
        xb = (xb & 0xffffffff00000000L) | lows[random.nextInt(3)];
        yb = (yb & 0xffffffff00000000L) | lows[random.nextInt(3)];
      }
      int hx = (int) ((xb & Long.MAX_VALUE) >>> 32);
      int hy = (int) ((yb & Long.MAX_VALUE) >>> 32);
      boolean byWords = hy > hx;
      boolean byBits = (yb & Long.MAX_VALUE) > (xb & Long.MAX_VALUE);
      if (hx != hy) {
        assertEquals(byWords, byBits, Long.toHexString(xb) + " " + Long.toHexString(yb));
      } else {
        assertTrue(!byWords);
        equalSwapped += byBits ? 1 : 0;
      }
    }
    assertTrue(equalSwapped > 0, "no pair of equal words that the bits swap");
  }

  /**
   * L21's vector tie: issue 173's witness and every pair of {@code hard-cases/hypot-order.txt}
   * at every lane of a vector beside lanes of every way out of the C, at every width the host
   * has, unmasked and masked. A port that ordered by the high words alone, as line 60 is
   * written, fails here on one order of each pair ({@code hypot.highWordOrder}).
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void orderPairsInEveryLane(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    Inputs.Pairs p = orderPairLanes(species.length());
    assertHypot(species, p);
    assertHypotMasked(species, p, 9860L + width.ordinal());
  }

  /** {@link #orderPairsInEveryLane(VectorWidth)} at {@code SPECIES_MAX}. */
  @Test
  void orderPairsInEveryLaneAtMaxSpecies() {
    Inputs.Pairs p = orderPairLanes(DoubleVector.SPECIES_MAX.length());
    assertHypot(DoubleVector.SPECIES_MAX, p);
    assertHypotMasked(DoubleVector.SPECIES_MAX, p, 9870L);
  }

  /**
   * Each order pair, and the witness in both orders, at each of {@code n} lanes, the other lanes
   * {@link #GENERAL_LANES}.
   */
  static Inputs.Pairs orderPairLanes(int n) {
    Inputs.Pairs file = orderPairs();
    List<double[]> witnesses = new ArrayList<>();
    witnesses.add(new double[] {bits(ORDER_WITNESS[0]), bits(ORDER_WITNESS[1])});
    witnesses.add(new double[] {bits(ORDER_WITNESS[1]), bits(ORDER_WITNESS[0])});
    for (int i = 0; i < file.x().length; i++) {
      witnesses.add(new double[] {file.x()[i], file.y()[i]});
    }
    double[] xs = new double[witnesses.size() * n * n];
    double[] ys = new double[xs.length];
    int k = 0;
    for (double[] w : witnesses) {
      for (int at = 0; at < n; at++) {
        for (int lane = 0; lane < n; lane++) {
          double[] pair = lane == at ? w : GENERAL_LANES[(at + lane) % GENERAL_LANES.length];
          xs[k] = pair[0];
          ys[k++] = pair[1];
        }
      }
    }
    return new Inputs.Pairs(xs, ys);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector v = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.hypot(v, v, null));
  }

  /** The words and constants are e_hypot.c's, by the hexadecimal its source writes. */
  @Test
  void constantsAreTheSourcesBits() {
    assertEquals(0x3c00000, Hypot.RATIO_WORD);
    assertEquals(0x5f300000, Hypot.HA_BIG);
    assertEquals(0x7ff00000, Hypot.HA_INF);
    assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), Hypot.INF_BITS);
    assertEquals(0x20b00000, Hypot.HB_SMALL);
    assertEquals(0x000fffff, Hypot.HB_SUBNORMAL);
    assertEquals(0x25800000, Hypot.SCALE_WORD);
    assertEquals(0x00100000, Hypot.TWICE_WORD);
    assertEquals(0x3ff00000, Hypot.ONE_WORD);
    assertEquals(0x7fd0000000000000L, Double.doubleToRawLongBits(Hypot.TWO1022));
    assertEquals(0x1p1022, Hypot.TWO1022);
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of hypot's faults, with a check that must fail while it is planted: the vector form over
   * {@link #faultRunInputs()} at every width the host has, and for {@code hypot.highWordOrder}
   * the order pairs in every lane ({@link #orderPairLanes}), since random equal-word draws show
   * it about twice in 10^8 (L21). The first four were sized on draws before they were wired; the
   * sizes are in {@code SKILLS.md}, "What the hypot port taught". {@code hypot.dropInfinityOverNan}
   * changes exactly the lanes of a NaN beside an infinite {@code b} (L22), which {@link
   * #specialPairs} holds.
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Hypot.DROP_EARLY_RETURN, Hypot.REFRESHED_WORDS, Hypot.DROP_T2, Hypot.DROP_Y2,
        Hypot.DROP_INFINITY_OVER_NAN}) {
      catchers.put(fault, () -> forEachWidth(species ->
          PlantedFaultTest.assertCaught(fault, () -> assertHypot(species, faultRunInputs()))));
    }
    catchers.put(Hypot.HIGH_WORD_ORDER, () -> forEachWidth(species ->
        PlantedFaultTest.assertCaught(Hypot.HIGH_WORD_ORDER,
            () -> assertHypot(species, orderPairLanes(species.length())))));
    return catchers;
  }

  private static Inputs.Pairs faultRunInputs() {
    return concat(specialPairs(), scalingPairs(9950L, 1 << 14), subnormalPairs(9951L, 1 << 14),
        equalWordPairs(9952L, 1 << 14), twiceBPairs(9953L, 1 << 14), densePairs(9954L, 1 << 12));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertHypot(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
