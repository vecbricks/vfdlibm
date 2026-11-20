/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file transcribes fdlibm 5.3's e_exp.c, s_expm1.c, e_log.c and e_log10.c (under
 * reference/fdlibm-5.3/), each of which carries this notice:
 *
 * ====================================================
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */
package io.github.vecbricks.vfdlibm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * fdlibm's C in scalar Java, line by line, with every constant, every threshold and every
 * correction term taken from a parameter set, and the one-step mutants of each function made
 * from that set: each double constant one ulp up and one down, each threshold one step up and
 * one down, each correction term dropped. {@link Survey} counts the inputs on which a mutant's
 * result differs; {@code dev/survey/README.md} says what the tables are for.
 *
 * <p>A transcription is the C as written, none of a port's folds ({@code PROOFS.md}), so a
 * mutant here is a change to the C. Where a port does the C's operations, an input on which
 * the mutant differs shows the same change in the vector code; where a port departs by a
 * lemma, the lemma's hinge tests are what guards it. With no parameter changed a transcription
 * is {@code StrictMath}'s result bit for bit, which {@code MutantsTest} checks and every run
 * of {@link Survey} counts.
 *
 * <p>A constant the C names once is one parameter, whatever the number of its uses: {@code
 * ln2HI[2]} is its magnitude, a literal such as {@code 0.5} is one parameter for all its
 * places. Masks, shifts and the exponent arithmetic ({@code 0xfffff}, {@code 1023}, {@code
 * k -= 54}) are not thresholds and are not mutated. log1p has no transcription here yet.
 */
final class Mutants {

  private Mutants() {}

  /** The C's body over a parameter set: doubles, threshold words and dropped terms. */
  @FunctionalInterface
  interface Body {
    double apply(double x, double[] d, int[] w, boolean[] drop);
  }

  /** A function: its oracle, the names and C values of its parameters, and its body. */
  record Port(
      String id,
      DoubleUnaryOperator oracle,
      String[] doubleNames,
      double[] doubles,
      String[] wordNames,
      int[] words,
      String[] dropNames,
      Body body) {

    Port {
      if (doubleNames.length != doubles.length || wordNames.length != words.length) {
        throw new IllegalArgumentException(id + ": names and values differ in number");
      }
    }

    /** The transcription with nothing changed. */
    Mutant unchanged() {
      return new Mutant("none", "none", doubles, words, new boolean[dropNames.length]);
    }

    /** Every one-step mutant, in the order of the parameters. */
    List<Mutant> mutants() {
      List<Mutant> mutants = new ArrayList<>();
      boolean[] none = new boolean[dropNames.length];
      for (int i = 0; i < doubles.length; i++) {
        double[] up = doubles.clone();
        up[i] = Math.nextUp(doubles[i]);
        mutants.add(new Mutant(doubleNames[i] + "+ulp", "constant", up, words, none));
        double[] down = doubles.clone();
        down[i] = Math.nextDown(doubles[i]);
        mutants.add(new Mutant(doubleNames[i] + "-ulp", "constant", down, words, none));
      }
      for (int i = 0; i < words.length; i++) {
        int[] up = words.clone();
        up[i]++;
        mutants.add(new Mutant(wordNames[i] + "+1", "threshold", doubles, up, none));
        int[] down = words.clone();
        down[i]--;
        mutants.add(new Mutant(wordNames[i] + "-1", "threshold", doubles, down, none));
      }
      for (int i = 0; i < dropNames.length; i++) {
        boolean[] dropped = new boolean[dropNames.length];
        dropped[i] = true;
        mutants.add(new Mutant("drop:" + dropNames[i], "drop", doubles, words, dropped));
      }
      return mutants;
    }

    double apply(double x, Mutant m) {
      return body.apply(x, m.d(), m.w(), m.drop());
    }
  }

  /** A parameter set one step from the C's, or the C's own. */
  record Mutant(String name, String kind, double[] d, int[] w, boolean[] drop) {}

  /** The transcribed functions, by {@code Nightly.Function}'s names. */
  static Port of(String id) {
    return switch (id) {
      case "exp" -> EXP;
      case "expm1" -> EXPM1;
      case "log" -> LOG;
      case "log10" -> LOG10;
      default -> throw new IllegalArgumentException(
          "no transcription of '" + id + "'; known: exp, expm1, log, log10");
    };
  }

  static final List<String> IDS = List.of("exp", "expm1", "log", "log10");

  private static int hi(double x) {
    return (int) (Double.doubleToRawLongBits(x) >>> 32);
  }

  private static int lo(double x) {
    return (int) Double.doubleToRawLongBits(x);
  }

  /** {@code __HI(y) = hi}. */
  private static double withHi(double y, int hi) {
    return Double.longBitsToDouble(
        ((long) hi << 32) | (Double.doubleToRawLongBits(y) & 0xffff_ffffL));
  }

  /** {@code __HI(y) += add}, the C's int sum modulo 2^32 in the high word. */
  private static double addHi(double y, int add) {
    return withHi(y, hi(y) + add);
  }

  // ---------------------------------------------------------------------------------------
  // e_exp.c

  private static final int E_ONE = 0;
  private static final int E_HALF = 1;
  private static final int E_HUGE = 2;
  private static final int E_TWOM1000 = 3;
  private static final int E_O_THRESHOLD = 4;
  private static final int E_U_THRESHOLD = 5;
  private static final int E_LN2HI = 6;
  private static final int E_LN2LO = 7;
  private static final int E_INVLN2 = 8;
  private static final int E_P1 = 9;
  private static final int E_P2 = 10;
  private static final int E_P3 = 11;
  private static final int E_P4 = 12;
  private static final int E_P5 = 13;
  private static final int E_TWO = 14;

  private static final int EW_FILTER = 0;
  private static final int EW_NON_FINITE = 1;
  private static final int EW_HALF_LN2 = 2;
  private static final int EW_THREE_HALVES_LN2 = 3;
  private static final int EW_TINY = 4;
  private static final int EW_K_SMALL = 5;

  static final Port EXP = new Port(
      "exp",
      StrictMath::exp,
      new String[] {
        "one", "halF", "huge", "twom1000", "o_threshold", "u_threshold", "ln2HI", "ln2LO",
        "invln2", "P1", "P2", "P3", "P4", "P5", "2.0"
      },
      new double[] {
        1.0, 0.5, 1.0e+300, 9.33263618503218878990e-302, 7.09782712893383973096e+02,
        -7.45133219101941108420e+02, 6.93147180369123816490e-01, 1.90821492927058770002e-10,
        1.44269504088896338700e+00, 1.66666666666666019037e-01, -2.77777777770155933842e-03,
        6.61375632143793436117e-05, -1.65339022054652515390e-06, 4.13813679705723846039e-08,
        2.0
      },
      new String[] {
        "hx>=0x40862E42", "hx>=0x7ff00000", "hx>0x3fd62e42", "hx<0x3FF0A2B2", "hx<0x3e300000",
        "k>=-1021"
      },
      new int[] {0x40862E42, 0x7ff00000, 0x3fd62e42, 0x3FF0A2B2, 0x3e300000, -1021},
      new String[] {"x=hi-lo", "lo-in-result", "t*P5"},
      Mutants::exp);

  /** {@code __ieee754_exp}; {@code drop}: 0 the reduction's lo, 1 the result's lo, 2 P5's term. */
  static double exp(double x, double[] d, int[] w, boolean[] drop) {
    double one = d[E_ONE];
    double hi = 0.0;
    double lo = 0.0;
    int k = 0;
    int hx = hi(x);
    int xsb = (hx >>> 31) & 1;
    hx &= 0x7fffffff;

    if (hx >= w[EW_FILTER]) {
      if (hx >= w[EW_NON_FINITE]) {
        if (((hx & 0xfffff) | lo(x)) != 0) {
          return x + x;
        }
        return xsb == 0 ? x : 0.0;
      }
      if (x > d[E_O_THRESHOLD]) {
        return d[E_HUGE] * d[E_HUGE];
      }
      if (x < d[E_U_THRESHOLD]) {
        return d[E_TWOM1000] * d[E_TWOM1000];
      }
    }

    if (hx > w[EW_HALF_LN2]) {
      if (hx < w[EW_THREE_HALVES_LN2]) {
        hi = x - (xsb == 0 ? d[E_LN2HI] : -d[E_LN2HI]);
        lo = xsb == 0 ? d[E_LN2LO] : -d[E_LN2LO];
        k = 1 - xsb - xsb;
      } else {
        k = (int) (d[E_INVLN2] * x + (xsb == 0 ? d[E_HALF] : -d[E_HALF]));
        double t = k;
        hi = x - t * d[E_LN2HI];
        lo = t * d[E_LN2LO];
      }
      x = drop[0] ? hi : hi - lo;
    } else if (hx < w[EW_TINY]) {
      if (d[E_HUGE] + x > one) {
        return one + x;
      }
    }

    double t = x * x;
    double p4 = drop[2] ? d[E_P4] : d[E_P4] + t * d[E_P5];
    double c = x - t * (d[E_P1] + t * (d[E_P2] + t * (d[E_P3] + t * p4)));
    if (k == 0) {
      return one - ((x * c) / (c - d[E_TWO]) - x);
    }
    double q = (x * c) / (d[E_TWO] - c);
    double y = one - ((drop[1] ? -q : lo - q) - hi);
    if (k >= w[EW_K_SMALL]) {
      return addHi(y, k << 20);
    }
    return addHi(y, (k + 1000) << 20) * d[E_TWOM1000];
  }

  // ---------------------------------------------------------------------------------------
  // s_expm1.c

  private static final int M_ONE = 0;
  private static final int M_HUGE = 1;
  private static final int M_TINY = 2;
  private static final int M_O_THRESHOLD = 3;
  private static final int M_LN2_HI = 4;
  private static final int M_LN2_LO = 5;
  private static final int M_INVLN2 = 6;
  private static final int M_Q1 = 7;
  private static final int M_Q2 = 8;
  private static final int M_Q3 = 9;
  private static final int M_Q4 = 10;
  private static final int M_Q5 = 11;
  private static final int M_HALF = 12;
  private static final int M_THREE = 13;
  private static final int M_SIX = 14;
  private static final int M_QUARTER = 15;
  private static final int M_TWO = 16;

  private static final int MW_56_LN2 = 0;
  private static final int MW_FILTER = 1;
  private static final int MW_NON_FINITE = 2;
  private static final int MW_HALF_LN2 = 3;
  private static final int MW_THREE_HALVES_LN2 = 4;
  private static final int MW_TINY = 5;
  private static final int MW_K_NEG = 6;
  private static final int MW_K_FAR = 7;
  private static final int MW_K_SPLIT = 8;

  static final Port EXPM1 = new Port(
      "expm1",
      StrictMath::expm1,
      new String[] {
        "one", "huge", "tiny", "o_threshold", "ln2_hi", "ln2_lo", "invln2", "Q1", "Q2", "Q3",
        "Q4", "Q5", "0.5", "3.0", "6.0", "-0.25", "2.0"
      },
      new double[] {
        1.0, 1.0e+300, 1.0e-300, 7.09782712893383973096e+02, 6.93147180369123816490e-01,
        1.90821492927058770002e-10, 1.44269504088896338700e+00, -3.33333333333331316428e-02,
        1.58730158725481460165e-03, -7.93650757867487942473e-05, 4.00821782732936239552e-06,
        -2.01099218183624371326e-07, 0.5, 3.0, 6.0, -0.25, 2.0
      },
      new String[] {
        "hx>=0x4043687A", "hx>=0x40862E42", "hx>=0x7ff00000", "hx>0x3fd62e42", "hx<0x3FF0A2B2",
        "hx<0x3c900000", "k<=-2", "k>56", "k<20"
      },
      new int[] {
        0x4043687A, 0x40862E42, 0x7ff00000, 0x3fd62e42, 0x3FF0A2B2, 0x3c900000, -2, 56, 20
      },
      new String[] {"x=hi-lo", "c", "e-c", "x*(e-c)-c", "hxs*Q5"},
      Mutants::expm1);

  /**
   * {@code expm1}; {@code drop}: 0 the reduction's lo, 1 the whole of c, 2 the c subtracted
   * from e, 3 the c subtracted from the product, 4 Q5's term.
   */
  static double expm1(double x, double[] d, int[] w, boolean[] drop) {
    double one = d[M_ONE];
    double half = d[M_HALF];
    double hi;
    double lo;
    double c = 0.0;
    int k;
    int hx = hi(x);
    int xsb = hx & 0x80000000;
    hx &= 0x7fffffff;

    if (hx >= w[MW_56_LN2]) {
      if (hx >= w[MW_FILTER]) {
        if (hx >= w[MW_NON_FINITE]) {
          if (((hx & 0xfffff) | lo(x)) != 0) {
            return x + x;
          }
          return xsb == 0 ? x : -1.0;
        }
        if (x > d[M_O_THRESHOLD]) {
          return d[M_HUGE] * d[M_HUGE];
        }
      }
      if (xsb != 0) {
        if (x + d[M_TINY] < 0.0) {
          return d[M_TINY] - one;
        }
      }
    }

    if (hx > w[MW_HALF_LN2]) {
      if (hx < w[MW_THREE_HALVES_LN2]) {
        if (xsb == 0) {
          hi = x - d[M_LN2_HI];
          lo = d[M_LN2_LO];
          k = 1;
        } else {
          hi = x + d[M_LN2_HI];
          lo = -d[M_LN2_LO];
          k = -1;
        }
      } else {
        k = (int) (d[M_INVLN2] * x + (xsb == 0 ? half : -half));
        double t = k;
        hi = x - t * d[M_LN2_HI];
        lo = t * d[M_LN2_LO];
      }
      x = drop[0] ? hi : hi - lo;
      c = drop[1] ? 0.0 : (hi - x) - lo;
    } else if (hx < w[MW_TINY]) {
      double t = d[M_HUGE] + x;
      return x - (t - (d[M_HUGE] + x));
    } else {
      k = 0;
    }

    double hfx = half * x;
    double hxs = x * hfx;
    double q4 = drop[4] ? d[M_Q4] : d[M_Q4] + hxs * d[M_Q5];
    double r1 = one + hxs * (d[M_Q1] + hxs * (d[M_Q2] + hxs * (d[M_Q3] + hxs * q4)));
    double t = d[M_THREE] - r1 * hfx;
    double e = hxs * ((r1 - t) / (d[M_SIX] - x * t));
    if (k == 0) {
      return x - (x * e - hxs);
    }
    double inner = x * (drop[2] ? e : e - c);
    e = drop[3] ? inner : inner - c;
    e -= hxs;
    if (k == -1) {
      return half * (x - e) - half;
    }
    if (k == 1) {
      if (x < d[M_QUARTER]) {
        return -d[M_TWO] * (e - (x + half));
      }
      return one + d[M_TWO] * (x - e);
    }
    double y;
    if (k <= w[MW_K_NEG] || k > w[MW_K_FAR]) {
      y = one - (e - x);
      y = addHi(y, k << 20);
      return y - one;
    }
    if (k < w[MW_K_SPLIT]) {
      y = withHi(one, 0x3ff00000 - (0x200000 >> k)) - (e - x);
      return addHi(y, k << 20);
    }
    y = x - (e + withHi(one, (0x3ff - k) << 20));
    y += one;
    return addHi(y, k << 20);
  }

  // ---------------------------------------------------------------------------------------
  // e_log.c

  private static final int L_LN2_HI = 0;
  private static final int L_LN2_LO = 1;
  private static final int L_TWO54 = 2;
  private static final int L_LG1 = 3;
  private static final int L_LG2 = 4;
  private static final int L_LG3 = 5;
  private static final int L_LG4 = 6;
  private static final int L_LG5 = 7;
  private static final int L_LG6 = 8;
  private static final int L_LG7 = 9;
  private static final int L_ONE = 10;
  private static final int L_HALF = 11;
  private static final int L_THIRD = 12;
  private static final int L_TWO = 13;
  private static final int L_DOUBLES = 14;

  private static final int LW_MIN_NORMAL = 0;
  private static final int LW_NON_FINITE = 1;
  private static final int LW_SQRT2 = 2;
  private static final int LW_NEAR_ADD = 3;
  private static final int LW_NEAR = 4;
  private static final int LW_BAND_LO = 5;
  private static final int LW_BAND_HI = 6;
  private static final int LW_WORDS = 7;

  private static final int L_DROPS = 3;

  private static final String[] LOG_DOUBLE_NAMES = {
    "ln2_hi", "ln2_lo", "two54", "Lg1", "Lg2", "Lg3", "Lg4", "Lg5", "Lg6", "Lg7", "1.0", "0.5",
    "0.33333333333333333", "2.0"
  };
  private static final double[] LOG_DOUBLES = {
    6.93147180369123816490e-01, 1.90821492927058770002e-10, 1.80143985094819840000e+16,
    6.666666666666735130e-01, 3.999999999940941908e-01, 2.857142874366239149e-01,
    2.222219843214978396e-01, 1.818357216161805012e-01, 1.531383769920937332e-01,
    1.479819860511658591e-01, 1.0, 0.5, 0.33333333333333333, 2.0
  };
  private static final String[] LOG_WORD_NAMES = {
    "hx<0x00100000", "hx>=0x7ff00000", "hx+0x95f64", "2+hx", "(2+hx)<3", "hx-0x6147a",
    "0x6b851-hx"
  };
  private static final int[] LOG_WORDS = {
    0x00100000, 0x7ff00000, 0x95f64, 2, 3, 0x6147a, 0x6b851
  };
  private static final String[] LOG_DROP_NAMES = {"dk*ln2_lo", "0.333*f", "w*Lg7"};

  static final Port LOG = new Port(
      "log",
      StrictMath::log,
      LOG_DOUBLE_NAMES,
      LOG_DOUBLES,
      LOG_WORD_NAMES,
      LOG_WORDS,
      LOG_DROP_NAMES,
      (x, d, w, drop) -> log(x, d, w, drop, 0, 0, 0));

  /**
   * {@code __ieee754_log}, its parameters from {@code od}, {@code ow} and {@code op} on in the
   * three arrays, so that log10's set can hold log's after its own. {@code drop}: 0 every
   * {@code dk*ln2_lo}, 1 the near path's {@code 0.333*f}, 2 Lg7's term.
   */
  static double log(double x, double[] d, int[] w, boolean[] drop, int od, int ow, int op) {
    double ln2Hi = d[od + L_LN2_HI];
    double ln2Lo = d[od + L_LN2_LO];
    double half = d[od + L_HALF];
    boolean noLo = drop[op];
    int hx = hi(x);
    int lx = lo(x);

    int k = 0;
    if (hx < w[ow + LW_MIN_NORMAL]) {
      if (((hx & 0x7fffffff) | lx) == 0) {
        return -d[od + L_TWO54] / 0.0;
      }
      if (hx < 0) {
        return (x - x) / 0.0;
      }
      k -= 54;
      x *= d[od + L_TWO54];
      hx = hi(x);
    }
    if (hx >= w[ow + LW_NON_FINITE]) {
      return x + x;
    }
    k += (hx >> 20) - 1023;
    hx &= 0x000fffff;
    int i = (hx + w[ow + LW_SQRT2]) & 0x100000;
    x = withHi(x, hx | (i ^ 0x3ff00000));
    k += i >> 20;
    double f = x - d[od + L_ONE];
    if ((0x000fffff & (w[ow + LW_NEAR_ADD] + hx)) < w[ow + LW_NEAR]) {
      if (f == 0.0) {
        if (k == 0) {
          return 0.0;
        }
        double dk = k;
        return noLo ? dk * ln2Hi : dk * ln2Hi + dk * ln2Lo;
      }
      double r = f * f * (drop[op + 1] ? half : half - d[od + L_THIRD] * f);
      if (k == 0) {
        return f - r;
      }
      double dk = k;
      return dk * ln2Hi - ((noLo ? r : r - dk * ln2Lo) - f);
    }
    double s = f / (d[od + L_TWO] + f);
    double dk = k;
    double z = s * s;
    i = hx - w[ow + LW_BAND_LO];
    double ww = z * z;
    int j = w[ow + LW_BAND_HI] - hx;
    double t1 = ww * (d[od + L_LG2] + ww * (d[od + L_LG4] + ww * d[od + L_LG6]));
    double lg5 = drop[op + 2] ? d[od + L_LG5] : d[od + L_LG5] + ww * d[od + L_LG7];
    double t2 = z * (d[od + L_LG1] + ww * (d[od + L_LG3] + ww * lg5));
    i |= j;
    double r = t2 + t1;
    if (i > 0) {
      double hfsq = half * f * f;
      if (k == 0) {
        return f - (hfsq - s * (hfsq + r));
      }
      double sum = s * (hfsq + r);
      return dk * ln2Hi - ((hfsq - (noLo ? sum : sum + dk * ln2Lo)) - f);
    }
    if (k == 0) {
      return f - s * (f - r);
    }
    double product = s * (f - r);
    return dk * ln2Hi - ((noLo ? product : product - dk * ln2Lo) - f);
  }

  // ---------------------------------------------------------------------------------------
  // e_log10.c, which calls __ieee754_log: its parameters first, then log's, named "log.".

  private static final int T_TWO54 = 0;
  private static final int T_IVLN10 = 1;
  private static final int T_LOG10_2HI = 2;
  private static final int T_LOG10_2LO = 3;
  private static final int T_DOUBLES = 4;

  private static final int TW_MIN_NORMAL = 0;
  private static final int TW_NON_FINITE = 1;
  private static final int TW_WORDS = 2;

  private static final int T_DROPS = 1;

  static final Port LOG10 = new Port(
      "log10",
      StrictMath::log10,
      join(new String[] {"two54", "ivln10", "log10_2hi", "log10_2lo"}, LOG_DOUBLE_NAMES),
      join(
          new double[] {
            1.80143985094819840000e+16, 4.34294481903251816668e-01, 3.01029995663611771306e-01,
            3.69423907715893078616e-13
          },
          LOG_DOUBLES),
      join(new String[] {"hx<0x00100000", "hx>=0x7ff00000"}, LOG_WORD_NAMES),
      join(new int[] {0x00100000, 0x7ff00000}, LOG_WORDS),
      join(new String[] {"y*log10_2lo"}, LOG_DROP_NAMES),
      Mutants::log10);

  /** {@code __ieee754_log10}; {@code drop}: 0 its {@code y*log10_2lo}, then log's three. */
  static double log10(double x, double[] d, int[] w, boolean[] drop) {
    int hx = hi(x);
    int lx = lo(x);

    int k = 0;
    if (hx < w[TW_MIN_NORMAL]) {
      if (((hx & 0x7fffffff) | lx) == 0) {
        return -d[T_TWO54] / 0.0;
      }
      if (hx < 0) {
        return (x - x) / 0.0;
      }
      k -= 54;
      x *= d[T_TWO54];
      hx = hi(x);
    }
    if (hx >= w[TW_NON_FINITE]) {
      return x + x;
    }
    k += (hx >> 20) - 1023;
    int i = (k & 0x80000000) >>> 31;
    hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
    double y = k + i;
    x = withHi(x, hx);
    double log = log(x, d, w, drop, T_DOUBLES, TW_WORDS, T_DROPS);
    double z = drop[0] ? d[T_IVLN10] * log : y * d[T_LOG10_2LO] + d[T_IVLN10] * log;
    return z + y * d[T_LOG10_2HI];
  }

  private static String[] join(String[] own, String[] logs) {
    String[] all = new String[own.length + logs.length];
    System.arraycopy(own, 0, all, 0, own.length);
    for (int i = 0; i < logs.length; i++) {
      all[own.length + i] = "log." + logs[i];
    }
    return all;
  }

  private static double[] join(double[] own, double[] logs) {
    return Inputs.concat(own, logs);
  }

  private static int[] join(int[] own, int[] logs) {
    int[] all = new int[own.length + logs.length];
    System.arraycopy(own, 0, all, 0, own.length);
    System.arraycopy(logs, 0, all, own.length, logs.length);
    return all;
  }

  static {
    if (LOG_DOUBLES.length != L_DOUBLES || LOG_WORDS.length != LW_WORDS
        || LOG_DROP_NAMES.length != L_DROPS) {
      throw new AssertionError("log's parameter counts");
    }
  }
}
