/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The method cPow below transcribes fdlibm 5.3's e_pow.c, which carries this notice:
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The entry gate of the pow port (issue 34): {@code e_pow.c} in scalar Java, line by line,
 * against {@code StrictMath.pow} by {@link PairBits#matches} with pow's declared candidates
 * ({@link PairBits.Candidates#OPERANDS}), so raw bits on every lane but one of two NaNs with
 * different bits. The inputs reach every way out of the C ({@link Path}, each asserted taken):
 * the special exponents and bases, {@code yisint} on both sides of its {@code k > 20} split, the
 * huge exponents above 2^31 and 2^64 with the short log beside 1, a subnormal {@code x}, the three
 * intervals of the significand, {@code y*log2(x)} at and around 1024 and -1075 (each exact
 * boundary both ways), the reduction of {@code |z| > 0.5}, and line 306's subnormal results,
 * where the C calls {@code scalbn}. The same check runs in a JVM of its own per JIT mode ({@link
 * HypotTest.JitMode}), since which candidate a sum of two NaNs keeps varies with the tier; that
 * run asserts the results, and prints which candidate won in each mode without asserting it.
 *
 * <p>{@code scalbn} is not in {@code reference/fdlibm-5.3/} ({@code fdlibm.h} only declares it).
 * The transcription takes {@code Math.scalb} in its place, which is specified as one correctly
 * rounded multiply by a power of two, and the gate counts how line 306's lanes round
 * ({@link Path#SCALB_INEXACT} and the paths after it) so that its agreement with {@code
 * StrictMath} there is not that of lanes the scale leaves exact.
 */
class PowTest {

  private static final PairBits.Candidates OPERANDS = PairBits.Candidates.OPERANDS;

  // ---------------------------------------------------------------------------------------------
  // The C in scalar Java
  // ---------------------------------------------------------------------------------------------

  /** The C as written, or with one change, to show that the inputs below see a change. */
  enum Variant {
    /** e_pow.c line by line. */
    AS_WRITTEN,
    /** Line 261 without {@code y*t2}, the low part of {@code log2(x)}. */
    DROP_T2,
    /** Line 306 never calls {@code scalbn}: line 307's exponent add on every lane. */
    NO_SCALBN,
    /** Line 306's {@code scalbn} truncating the bits it drops instead of rounding them. */
    TRUNCATING_SCALBN
  }

  /**
   * The way out of the C a lane took, or a form of it: {@link #cPow(double, double, Variant,
   * long[])} counts.
   */
  enum Path {
    /** Line 119: {@code y} is zero. */
    Y_ZERO,
    /** Line 124: a NaN, {@code x+y}. */
    NAN,
    /** Line 150: {@code (+-1) ** (+-inf)}, {@code y-y}. */
    Y_INF_X_ONE,
    /** Lines 151-154: {@code y} infinite, {@code |x|} not 1. */
    Y_INF,
    /** Line 157: {@code y} is 1. */
    Y_ONE,
    /** Line 157: {@code y} is -1. */
    Y_MINUS_ONE,
    /** Line 159: {@code y} is 2. */
    Y_TWO,
    /** Line 162: {@code y} is 0.5 and {@code x >= +0}. */
    Y_HALF,
    /** Lines 170-178: {@code x} is a zero, an infinity or 1, any sign. */
    X_SPECIAL,
    /** Line 174: {@code (-1) ** (non-integer)}, {@code (z-z)/(z-z)}. */
    X_MINUS_ONE_NON_INTEGER,
    /** Line 176: {@code x} special and negative, {@code y} an odd integer. */
    X_SPECIAL_ODD,
    /** Line 185: {@code x < 0} and {@code y} not an integer. */
    NEGATIVE_X_NON_INTEGER,
    /** Lines 133-141: {@code yisint} decided by the low word ({@code k > 20}). */
    YISINT_LOW_WORD,
    /** Lines 139-141: {@code yisint} decided by the high word ({@code k <= 20}). */
    YISINT_HIGH_WORD,
    /** Lines 193-194: {@code |y| > 2^64}. */
    Y_ABOVE_2_64,
    /** Lines 197-198: {@code |y| > 2^31} and {@code x} not near 1. */
    Y_ABOVE_2_31,
    /** Lines 201-207: {@code |y| > 2^31} and {@code x} near 1, the short log. */
    NEAR_ONE_LOG,
    /** Line 213: a subnormal {@code x}, scaled by 2^53. */
    SUBNORMAL_X,
    /** Line 218: {@code k = 0}. */
    INTERVAL_0,
    /** Line 219: {@code k = 1}. */
    INTERVAL_1,
    /** Line 220: {@code k = 0} with {@code n+1}. */
    INTERVAL_2,
    /** Line 268: {@code z > 1024}. */
    OVERFLOW,
    /** Line 270: {@code z == 1024} and the overflow returned. */
    OVERFLOW_AT_1024,
    /** Line 270: {@code z == 1024} and no overflow. */
    AT_1024,
    /** Line 274: {@code z < -1075}. */
    UNDERFLOW,
    /** Line 276: {@code z == -1075} and the underflow returned. */
    UNDERFLOW_AT_1075,
    /** Line 276: {@code z == -1075} and no underflow. */
    AT_1075,
    /** Lines 286-292: {@code |z| > 0.5}. */
    Z_ABOVE_HALF,
    /** Line 285 not taken: {@code |z| <= 0.5}. */
    Z_NOT_ABOVE_HALF,
    /** Line 306: a subnormal result, {@code scalbn}. */
    SUBNORMAL_RESULT,
    /** Line 307: the exponent added to the high word. */
    NORMAL_RESULT,
    /** Line 308 with {@code s = -1}. */
    NEGATIVE_RESULT,
    /** Line 306: the scale drops bits that are not all zero. */
    SCALB_INEXACT,
    /** Line 306: the bits the scale drops are exactly half an ulp of the result. */
    SCALB_TIE,
    /** Line 306: the result is {@code Double.MIN_VALUE}. */
    SCALB_TO_MIN_VALUE,
    /** Line 306: the result rounds up to {@code Double.MIN_NORMAL}. */
    SCALB_TO_MIN_NORMAL
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

  /** {@code __LO(d) = 0}: the low word cleared, the high word kept. */
  private static double withLoZero(double d) {
    return Double.longBitsToDouble(Double.doubleToRawLongBits(d) & 0xffffffff00000000L);
  }

  private static void took(long[] paths, Path path) {
    if (paths != null) {
      paths[path.ordinal()]++;
    }
  }

  static final double[] BP = {1.0, 1.5};
  static final double[] DP_H = {0.0, 5.84962487220764160156e-01}; /* 0x3FE2B803, 0x40000000 */
  static final double[] DP_L = {0.0, 1.35003920212974897128e-08}; /* 0x3E4CFDEB, 0x43CFD006 */
  static final double ZERO = 0.0;
  static final double ONE = 1.0;
  static final double TWO = 2.0;
  static final double TWO53 = 9007199254740992.0; /* 0x43400000, 0x00000000 */
  static final double HUGE = 1.0e300;
  static final double TINY = 1.0e-300;
  /* poly coefs for (3/2)*(log(x)-2s-2/3*s**3 */
  static final double L1 = 5.99999999999994648725e-01; /* 0x3FE33333, 0x33333303 */
  static final double L2 = 4.28571428578550184252e-01; /* 0x3FDB6DB6, 0xDB6FABFF */
  static final double L3 = 3.33333329818377432918e-01; /* 0x3FD55555, 0x518F264D */
  static final double L4 = 2.72728123808534006489e-01; /* 0x3FD17460, 0xA91D4101 */
  static final double L5 = 2.30660745775561754067e-01; /* 0x3FCD864A, 0x93C9DB65 */
  static final double L6 = 2.06975017800338417784e-01; /* 0x3FCA7E28, 0x4A454EEF */
  static final double P1 = 1.66666666666666019037e-01; /* 0x3FC55555, 0x5555553E */
  static final double P2 = -2.77777777770155933842e-03; /* 0xBF66C16C, 0x16BEBD93 */
  static final double P3 = 6.61375632143793436117e-05; /* 0x3F11566A, 0xAF25DE2C */
  static final double P4 = -1.65339022054652515390e-06; /* 0xBEBBBD41, 0xC5D26BF1 */
  static final double P5 = 4.13813679705723846039e-08; /* 0x3E663769, 0x72BEA4D0 */
  static final double LG2 = 6.93147180559945286227e-01; /* 0x3FE62E42, 0xFEFA39EF */
  static final double LG2_H = 6.93147182464599609375e-01; /* 0x3FE62E43, 0x00000000 */
  static final double LG2_L = -1.90465429995776804525e-09; /* 0xBE205C61, 0x0CA86C39 */
  static final double OVT = 8.0085662595372944372e-0017; /* -(1024-log2(ovfl+.5ulp)) */
  static final double CP = 9.61796693925975554329e-01; /* 0x3FEEC709, 0xDC3A03FD =2/(3ln2) */
  static final double CP_H = 9.61796700954437255859e-01; /* 0x3FEEC709, 0xE0000000 =(float)cp */
  static final double CP_L = -7.02846165095275826516e-09; /* 0xBE3E2FE0, 0x145B01F5 =tail of cp_h*/
  static final double IVLN2 = 1.44269504088896338700e+00; /* 0x3FF71547, 0x652B82FE =1/ln2 */
  static final double IVLN2_H = 1.44269502162933349609e+00; /* 0x3FF71547, 0x60000000 =24b 1/ln2*/
  static final double IVLN2_L = 1.92596299112661746887e-08; /* 0x3E54AE0B, 0xF85DDF44 =1/ln2 tail*/

  /** e_pow.c in scalar Java, line by line. */
  static double cPow(double x, double y) {
    return cPow(x, y, Variant.AS_WRITTEN, null);
  }

  /**
   * {@link #cPow(double, double)} as {@code variant} says, counting into {@code paths} (by {@link
   * Path#ordinal()}, when not null) the ways out the lane took. The line numbers are e_pow.c's.
   * The C's {@code sqrt} is {@code Math.sqrt}, the correctly rounded square root (PROOFS.md, R4),
   * and its {@code scalbn}, which {@code reference/} does not hold, is {@code Math.scalb}
   * ({@link #scalbn}). The C's {@code lx} and {@code ly} are unsigned: they are tested against
   * zero (alone or ORed, lines 119-168), compared for equality with {@code j<<(52-k)} (line 138)
   * and shifted right without sign (line 137), so an {@code int} holds their bits. Line 113's
   * {@code i0} and {@code i1} are unused in the C and left out.
   */
  static double cPow(double x, double y, Variant variant, long[] paths) {
    double z;
    double ax;
    double zH;
    double zL;
    double pH;
    double pL;
    double y1;
    double t1;
    double t2;
    double r;
    double s;
    double t;
    double u;
    double v;
    double w;
    int i;
    int j;
    int k;
    int yisint;
    int n;
    int hx;
    int hy;
    int ix;
    int iy;
    int lx;
    int ly;

    hx = hi(x); // 114
    lx = lo(x);
    hy = hi(y); // 115
    ly = lo(y);
    ix = hx & 0x7fffffff; // 116
    iy = hy & 0x7fffffff;

    /* y==zero: x**0 = 1 */
    if ((iy | ly) == 0) { // 119
      took(paths, Path.Y_ZERO);
      return ONE;
    }

    /* +-NaN return x+y */
    if (ix > 0x7ff00000 || ((ix == 0x7ff00000) && (lx != 0)) // 122
        || iy > 0x7ff00000 || ((iy == 0x7ff00000) && (ly != 0))) { // 123
      took(paths, Path.NAN);
      return x + y; // 124
    }

    /* determine if y is an odd int when x < 0
     * yisint = 0 ... y is not an integer
     * yisint = 1 ... y is an odd int
     * yisint = 2 ... y is an even int
     */
    yisint = 0; // 131
    if (hx < 0) { // 132
      if (iy >= 0x43400000) { // 133
        yisint = 2; /* even integer y */
      } else if (iy >= 0x3ff00000) { // 134
        k = (iy >> 20) - 0x3ff; /* exponent */ // 135
        if (k > 20) { // 136
          took(paths, Path.YISINT_LOW_WORD);
          j = ly >>> (52 - k); // 137
          if ((j << (52 - k)) == ly) { // 138
            yisint = 2 - (j & 1);
          }
        } else if (ly == 0) { // 139
          took(paths, Path.YISINT_HIGH_WORD);
          j = iy >> (20 - k); // 140
          if ((j << (20 - k)) == iy) { // 141
            yisint = 2 - (j & 1);
          }
        }
      }
    }

    /* special value of y */
    if (ly == 0) { // 147
      if (iy == 0x7ff00000) { /* y is +-inf */ // 148
        if (((ix - 0x3ff00000) | lx) == 0) { // 149
          took(paths, Path.Y_INF_X_ONE);
          return y - y; /* inf**+-1 is NaN */ // 150
        } else if (ix >= 0x3ff00000) { /* (|x|>1)**+-inf = inf,0 */ // 151
          took(paths, Path.Y_INF);
          return (hy >= 0) ? y : ZERO; // 152
        } else { /* (|x|<1)**-,+inf = inf,0 */ // 153
          took(paths, Path.Y_INF);
          return (hy < 0) ? -y : ZERO; // 154
        }
      }
      if (iy == 0x3ff00000) { /* y is  +-1 */ // 156
        if (hy < 0) { // 157
          took(paths, Path.Y_MINUS_ONE);
          return ONE / x;
        } else {
          took(paths, Path.Y_ONE);
          return x;
        }
      }
      if (hy == 0x40000000) { // 159
        took(paths, Path.Y_TWO);
        return x * x; /* y is  2 */
      }
      if (hy == 0x3fe00000) { /* y is  0.5 */ // 160
        if (hx >= 0) { /* x >= +0 */ // 161
          took(paths, Path.Y_HALF);
          return Math.sqrt(x); // 162
        }
      }
    }

    ax = Math.abs(x); // 166
    /* special value of x */
    if (lx == 0) { // 168
      if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) { // 169
        took(paths, Path.X_SPECIAL);
        z = ax; /*x is +-0,+-inf,+-1*/ // 170
        if (hy < 0) { // 171
          z = ONE / z; /* z = (1/|x|) */
        }
        if (hx < 0) { // 172
          if (((ix - 0x3ff00000) | yisint) == 0) { // 173
            took(paths, Path.X_MINUS_ONE_NON_INTEGER);
            z = (z - z) / (z - z); /* (-1)**non-int is NaN */ // 174
          } else if (yisint == 1) { // 175
            took(paths, Path.X_SPECIAL_ODD);
            z = -z; /* (x<0)**odd = -(|x|**odd) */ // 176
          }
        }
        return z; // 178
      }
    }

    n = (hx >> 31) + 1; // 182

    /* (x<0)**(non-int) is NaN */
    if ((n | yisint) == 0) { // 185
      took(paths, Path.NEGATIVE_X_NON_INTEGER);
      return (x - x) / (x - x);
    }

    s = ONE; /* s (sign of result -ve**odd) = -1 else = 1 */ // 187
    if ((n | (yisint - 1)) == 0) { // 188
      s = -ONE; /* (-ve)**(odd int) */
    }

    /* |y| is huge */
    if (iy > 0x41e00000) { /* if |y| > 2**31 */ // 191
      if (iy > 0x43f00000) { /* if |y| > 2**64, must o/uflow */ // 192
        if (ix <= 0x3fefffff) { // 193
          took(paths, Path.Y_ABOVE_2_64);
          return (hy < 0) ? HUGE * HUGE : TINY * TINY;
        }
        if (ix >= 0x3ff00000) { // 194
          took(paths, Path.Y_ABOVE_2_64);
          return (hy > 0) ? HUGE * HUGE : TINY * TINY;
        }
      }
      /* over/underflow if x is not close to one */
      if (ix < 0x3fefffff) { // 197
        took(paths, Path.Y_ABOVE_2_31);
        return (hy < 0) ? s * HUGE * HUGE : s * TINY * TINY;
      }
      if (ix > 0x3ff00000) { // 198
        took(paths, Path.Y_ABOVE_2_31);
        return (hy > 0) ? s * HUGE * HUGE : s * TINY * TINY;
      }
      /* now |1-x| is tiny <= 2**-20, suffice to compute
         log(x) by x-x^2/2+x^3/3-x^4/4 */
      took(paths, Path.NEAR_ONE_LOG);
      t = ax - ONE; /* t has 20 trailing zeros */ // 201
      w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25)); // 202
      u = IVLN2_H * t; /* ivln2_h has 21 sig. bits */ // 203
      v = t * IVLN2_L - w * IVLN2; // 204
      t1 = u + v; // 205
      t1 = withLoZero(t1); // 206
      t2 = v - (t1 - u); // 207
    } else { // 208
      double ss;
      double s2;
      double sH;
      double sL;
      double tH;
      double tL;
      n = 0; // 210
      /* take care subnormal number */
      if (ix < 0x00100000) { // 212
        took(paths, Path.SUBNORMAL_X);
        ax *= TWO53; // 213
        n -= 53;
        ix = hi(ax);
      }
      n += ((ix) >> 20) - 0x3ff; // 214
      j = ix & 0x000fffff; // 215
      /* determine interval */
      ix = j | 0x3ff00000; /* normalize ix */ // 217
      if (j <= 0x3988E) { // 218
        took(paths, Path.INTERVAL_0);
        k = 0; /* |x|<sqrt(3/2) */
      } else if (j < 0xBB67A) { // 219
        took(paths, Path.INTERVAL_1);
        k = 1; /* |x|<sqrt(3)   */
      } else { // 220
        took(paths, Path.INTERVAL_2);
        k = 0;
        n += 1;
        ix -= 0x00100000;
      }
      ax = withHi(ax, ix); // 221

      /* compute ss = s_h+s_l = (x-1)/(x+1) or (x-1.5)/(x+1.5) */
      u = ax - BP[k]; /* bp[0]=1.0, bp[1]=1.5 */ // 224
      v = ONE / (ax + BP[k]); // 225
      ss = u * v; // 226
      sH = ss; // 227
      sH = withLoZero(sH); // 228
      /* t_h=ax+bp[k] High */
      tH = ZERO; // 230
      tH = withHi(tH, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18)); // 231
      tL = ax - (tH - BP[k]); // 232
      sL = v * ((u - sH * tH) - sH * tL); // 233
      /* compute log(ax) */
      s2 = ss * ss; // 235
      r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6))))); // 236
      r += sL * (sH + ss); // 237
      s2 = sH * sH; // 238
      tH = 3.0 + s2 + r; // 239
      tH = withLoZero(tH); // 240
      tL = r - ((tH - 3.0) - s2); // 241
      /* u+v = ss*(1+...) */
      u = sH * tH; // 243
      v = sL * tH + tL * ss; // 244
      /* 2/(3log2)*(ss+...) */
      pH = u + v; // 246
      pH = withLoZero(pH); // 247
      pL = v - (pH - u); // 248
      zH = CP_H * pH; /* cp_h+cp_l = 2/(3*log2) */ // 249
      zL = CP_L * pH + pL * CP + DP_L[k]; // 250
      /* log2(ax) = (ss+..)*2/(3*log2) = n + dp_h + z_h + z_l */
      t = (double) n; // 252
      t1 = (((zH + zL) + DP_H[k]) + t); // 253
      t1 = withLoZero(t1); // 254
      t2 = zL - (((t1 - t) - DP_H[k]) - zH); // 255
    }

    /* split up y into y1+y2 and compute (y1+y2)*(t1+t2) */
    y1 = y; // 259
    y1 = withLoZero(y1); // 260
    pL = variant == Variant.DROP_T2 ? (y - y1) * t1 : (y - y1) * t1 + y * t2; // 261
    pH = y1 * t1; // 262
    z = pL + pH; // 263
    j = hi(z); // 264
    i = lo(z); // 265
    if (j >= 0x40900000) { /* z >= 1024 */ // 266
      if (((j - 0x40900000) | i) != 0) { /* if z > 1024 */ // 267
        took(paths, Path.OVERFLOW);
        return s * HUGE * HUGE; /* overflow */ // 268
      } else {
        if (pL + OVT > z - pH) { // 270
          took(paths, Path.OVERFLOW_AT_1024);
          return s * HUGE * HUGE; /* overflow */
        }
        took(paths, Path.AT_1024);
      }
    } else if ((j & 0x7fffffff) >= 0x4090cc00) { /* z <= -1075 */ // 272
      if (((j - 0xc090cc00) | i) != 0) { /* z < -1075 */ // 273
        took(paths, Path.UNDERFLOW);
        return s * TINY * TINY; /* underflow */ // 274
      } else {
        if (pL <= z - pH) { // 276
          took(paths, Path.UNDERFLOW_AT_1075);
          return s * TINY * TINY; /* underflow */
        }
        took(paths, Path.AT_1075);
      }
    }
    /*
     * compute 2**(p_h+p_l)
     */
    i = j & 0x7fffffff; // 282
    k = (i >> 20) - 0x3ff; // 283
    n = 0; // 284
    if (i > 0x3fe00000) { /* if |z| > 0.5, set n = [z+0.5] */ // 285
      took(paths, Path.Z_ABOVE_HALF);
      n = j + (0x00100000 >> (k + 1)); // 286
      k = ((n & 0x7fffffff) >> 20) - 0x3ff; /* new k for n */ // 287
      t = ZERO; // 288
      t = withHi(t, (n & ~(0x000fffff >> k))); // 289
      n = ((n & 0x000fffff) | 0x00100000) >> (20 - k); // 290
      if (j < 0) { // 291
        n = -n;
      }
      pH -= t; // 292
    } else {
      took(paths, Path.Z_NOT_ABOVE_HALF);
    }
    t = pL + pH; // 294
    t = withLoZero(t); // 295
    u = t * LG2_H; // 296
    v = (pL - (t - pH)) * LG2 + t * LG2_L; // 297
    z = u + v; // 298
    w = v - (z - u); // 299
    t = z * z; // 300
    t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5)))); // 301
    r = (z * t1) / (t1 - TWO) - (w + z * w); // 302
    z = ONE - (r - z); // 303
    j = hi(z); // 304
    j += (n << 20); // 305
    if ((j >> 20) <= 0 && variant != Variant.NO_SCALBN) { // 306
      took(paths, Path.SUBNORMAL_RESULT);
      z = scalbn(z, n, variant, paths); /* subnormal output */
    } else {
      took(paths, Path.NORMAL_RESULT);
      z = withHi(z, hi(z) + (n << 20)); // 307
    }
    if (s < 0) {
      took(paths, Path.NEGATIVE_RESULT);
    }
    return s * z; // 308
  }

  /**
   * Line 306's {@code scalbn(z, n)}: {@code Math.scalb}, the stand-in for the C's, which {@code
   * reference/} does not hold, or (by {@link Variant#TRUNCATING_SCALBN}) the exact product with
   * the bits it drops cut off. It counts how the scale rounds. {@code z} is positive and normal,
   * and the product is below {@code 2^-1022}. That {@code Math.scalb} is fdlibm's {@code scalbn}
   * is not shown here, since {@code s_scalbn.c} is not in {@code reference/} (issue 34, decision
   * 1): the gate shows only that, so transcribed, line 306 is {@code StrictMath}'s.
   */
  static double scalbn(double z, int n, Variant variant, long[] paths) {
    long bits = Double.doubleToRawLongBits(z);
    long m = (bits & 0x000fffffffffffffL) | 0x0010000000000000L;
    int exponent = (int) (bits >>> 52) - 1023;
    // z * 2^n = m * 2^(exponent + n - 52), and the subnormals are multiples of 2^-1074
    int dropped = -1074 - (exponent + n - 52);
    double scaled = Math.scalb(z, n);
    if (variant == Variant.TRUNCATING_SCALBN && dropped > 0) {
      scaled = Double.longBitsToDouble(dropped >= 64 ? 0L : m >>> dropped);
    }
    if (dropped > 0) {
      long rest = dropped >= 64 ? m : m & ((1L << dropped) - 1);
      if (rest != 0) {
        took(paths, Path.SCALB_INEXACT);
      }
      if (dropped < 64 && rest == 1L << (dropped - 1)) {
        took(paths, Path.SCALB_TIE);
      }
    }
    if (scaled == Double.MIN_VALUE) {
      took(paths, Path.SCALB_TO_MIN_VALUE);
    } else if (scaled == Double.MIN_NORMAL) {
      took(paths, Path.SCALB_TO_MIN_NORMAL);
    }
    return scaled;
  }

  // ---------------------------------------------------------------------------------------------
  // Inputs
  // ---------------------------------------------------------------------------------------------

  /**
   * The high words e_pow.c tests of {@code x} (lines 149-220) and of {@code y} (lines 133-198,
   * 266-272), with their neighbours: each as a double of a zero low word and of two others.
   */
  static final int[] WORDS = Nightly.POW_WORDS;

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

  /**
   * The special values, {@link #WORDS} at the low words 0, 1 and all ones, both signs, the NaNs,
   * and the special exponents with odd, even and half integers beside them.
   */
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
    double[] values = {
      2.0, 0.5, 3.0, 4.0, 1.5, 2.5, 10.0, 0.25, 0x1p20 + 1, 0x1p21 + 1, 0x1p52 + 1, 0x1p53 - 1,
      0x1p53, 1024.0, 1075.0, 1022.0, 1074.0
    };
    double[] signed = new double[2 * values.length];
    for (int i = 0; i < values.length; i++) {
      signed[2 * i] = values[i];
      signed[2 * i + 1] = -values[i];
    }
    return Inputs.concat(Inputs.specials(), words, nans, signed);
  }

  /** Every pair of the special values. */
  static Inputs.Pairs specialPairs() {
    double[] specials = specials();
    return Inputs.cross(specials, specials);
  }

  /**
   * Where e_pow.c's branches are, for the fuzz's pair draws: {@link #WORDS} for both arguments;
   * for {@code x} the significand splits of lines 218-220 and the points 0, 1, 2 and the
   * smallest normal; for {@code y} the special exponents, 2^31 and 2^64 (the result's limits,
   * which need {@code x} and {@code y} together, are {@link #targetPairs}'). pow has no branch on
   * the two words relative to each other, so it lists no {@code wordGaps}.
   */
  static final Fuzz.PairEdges EDGES = Nightly.PairFunction.POW.edges();

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

  /**
   * A negative {@code x} beside an integer or half-integer {@code y} (lines 131-144, 185, 188):
   * {@code |y|} from 1 to 2^61, its exponent drawn evenly so that both sides of line 136's {@code
   * k > 20} and of line 133's 2^53 are drawn, and {@code |x|} near 1, a power of two, or anywhere.
   */
  static Inputs.Pairs integerPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      int e = random.nextInt(0, 61);
      double y = (double) ((1L << e) + (e == 0 ? 0 : random.nextLong(1L << e)));
      if (random.nextInt(4) == 0) {
        y += 0.5;
      }
      ys[i] = random.nextBoolean() ? y : -y;
      double ax = switch (random.nextInt(4)) {
        case 0 -> 1.0 + Math.scalb(random.nextDouble(-1.0, 1.0), -random.nextInt(1, 53));
        case 1 -> Math.scalb(1.0, random.nextInt(-1074, 1024));
        case 2 -> random.nextDouble(0.0, 10.0);
        default -> Math.abs(Double.longBitsToDouble(random.nextLong()));
      };
      xs[i] = -ax;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * {@code x} near 1 beside a large {@code |y|} (lines 191-207): {@code x}'s high word at or
   * beside {@code 0x3fefffff} and {@code 0x3ff00000}, either sign; half the time {@code |y|} from
   * 2^31 to 2^66, half the time sized so that {@code y*log2(x)} lands from overflow to underflow
   * (above 2^31 it takes the short log of lines 201-207 through to the subnormal results, below it
   * the main log), and odd integers below 2^53 for {@code s = -1}.
   */
  static Inputs.Pairs hugeYPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    int[] words = {0x3feffffe, 0x3fefffff, 0x3ff00000, 0x3ff00001};
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      double x = fromWords(words[random.nextInt(words.length)], lowWord(random),
          random.nextInt(4) == 0);
      double y;
      double log2 = StrictMath.log(Math.abs(x)) / StrictMath.log(2.0);
      if (random.nextBoolean() && log2 != 0) {
        double target = random.nextInt(3) == 0 ? random.nextDouble(-1100.0, 1100.0)
            : random.nextDouble(-1076.0, -1020.0);
        y = target / log2;
      } else {
        y = Math.scalb(1.0 + random.nextDouble(), random.nextInt(31, 66));
        y = random.nextBoolean() ? y : -y;
      }
      if (x < 0 && random.nextBoolean() && Math.abs(y) < 0x1p53) {
        // an odd integer, so that the sign s is -1
        double odd = Math.floor(Math.abs(y));
        odd = (odd % 2 == 0) ? odd + 1 : odd;
        y = Math.copySign(odd, y);
      }
      xs[i] = x;
      ys[i] = y;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * {@code y*log2(x)} near a target where the C's result changes form: 1024 and -1075 (lines
   * 266-278), the subnormal results between -1022 and -1075 (line 306) and +-0.5 (line 285).
   * {@code x} is positive at any exponent, subnormals included, or a power of two moved by a few
   * ulps, {@code y} is {@code target / log2(x)} moved by a few ulps; one draw in eight takes a
   * negative {@code x} with {@code y} rounded to an integer, so the sign is set too.
   */
  static Inputs.Pairs targetPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    double[] targets = {1024.0, -1075.0, -1022.0, -1074.0, 0.5, -0.5};
    for (int i = 0; i < n; i++) {
      double target = switch (random.nextInt(4)) {
        case 0 -> random.nextDouble(-1075.0, -1022.0);
        case 1 -> random.nextDouble(-1100.0, 1100.0);
        default -> targets[random.nextInt(targets.length)];
      };
      double x;
      double y;
      if (random.nextInt(3) == 0) {
        // a power of two moved by a few ulps, where y = target / k is often exact
        int[] ks = {1, 2, 4, 5, 25, 43, 215, -1, -5, -25, -43, -215, 3, 7, -3, -7};
        int k = ks[random.nextInt(ks.length)];
        x = Double.longBitsToDouble(Double.doubleToRawLongBits(Math.scalb(1.0, k))
            + random.nextInt(-3, 4));
        y = target / k;
      } else {
        x = switch (random.nextInt(3)) {
          case 0 -> Math.scalb(1.0 + random.nextDouble(), random.nextInt(-1022, 1024));
          case 1 -> Double.longBitsToDouble(random.nextLong(1L, 0x0010000000000000L));
          default -> random.nextDouble(1e-6, 1e6);
        };
        double log2 = StrictMath.log(x) / StrictMath.log(2.0);
        y = log2 == 0 ? target : target / log2;
      }
      y = Double.longBitsToDouble(Double.doubleToRawLongBits(y) + random.nextInt(-4, 5));
      if (random.nextInt(8) == 0) {
        x = -x;
        y = Math.rint(y);
      }
      xs[i] = x;
      ys[i] = y;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * Pairs either side of where the result reaches {@code Double.MIN_NORMAL}, where line 306 may
   * round a subnormal up to the smallest normal. That needs {@code y*log2(x)} within about 2^-53
   * of -1022, finer than a step of {@code y} moves it, so {@code y} is {@code 1022/k} for an
   * integer {@code k} from 256 to 1021 (a {@code y} from 1 to 4, not 2), whose small size makes a
   * step of {@code x} move {@code y*log2(x)} by a few 2^-53 only, and {@code x} is {@code 2^-k}
   * moved by up to 4096 ulps (a {@code k} whose range does not cross {@code MIN_NORMAL} is drawn
   * again): a binary search over those {@code x}, by {@code StrictMath.pow}'s result (the oracle
   * chooses inputs here; the check compares on them as on any), keeps the two neighbours whose
   * results are below and at or above {@code MIN_NORMAL}. Two pairs per draw.
   */
  static Inputs.Pairs minNormalPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[2 * n];
    double[] ys = new double[2 * n];
    for (int i = 0; i < n; i++) {
      double y;
      long lo;
      long hi;
      do {
        int k = random.nextInt(256, 1022);
        k = k == 511 ? 510 : k;
        y = 1022.0 / k;
        long x0 = Double.doubleToRawLongBits(Math.scalb(1.0, -k));
        lo = x0 - 4096;
        hi = x0 + 4096;
      } while (!(StrictMath.pow(Double.longBitsToDouble(lo), y) < Double.MIN_NORMAL
          && StrictMath.pow(Double.longBitsToDouble(hi), y) >= Double.MIN_NORMAL));
      while (hi - lo > 1) {
        long mid = (lo + hi) >>> 1;
        if (StrictMath.pow(Double.longBitsToDouble(mid), y) < Double.MIN_NORMAL) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      xs[2 * i] = Double.longBitsToDouble(lo);
      xs[2 * i + 1] = Double.longBitsToDouble(hi);
      ys[2 * i] = y;
      ys[2 * i + 1] = y;
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * NaN beside NaN (random payloads, of random signs, and pairs one bit apart), beside the
   * special exponents and bases (line 119 before line 122: {@code NaN ** 0} is 1, {@code 1 **
   * NaN} is NaN), beside infinities and beside random bits.
   */
  static Inputs.Pairs nanPairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] specials = {0.0, -0.0, 1.0, -1.0, 2.0, 0.5, Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY};
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
        case 4 -> Double.doubleToRawLongBits(specials[random.nextInt(specials.length)]);
        default -> random.nextLong();
      };
      boolean swap = random.nextBoolean();
      xs[i] = Double.longBitsToDouble(swap ? other : nan);
      ys[i] = Double.longBitsToDouble(swap ? nan : other);
    }
    return new Inputs.Pairs(xs, ys);
  }

  /**
   * {@code integer ** integer} whose result is an integer up to 2^53, which e_pow.c promises
   * exact (lines 49-52), of either sign of {@code x}, with their reciprocals ({@code y < 0}); and
   * Spark's test grid, -5 to 5 by halves on both sides.
   */
  static Inputs.Pairs exactPairs() {
    List<double[]> pairs = new ArrayList<>();
    for (long b = 2; b <= 1000; b++) {
      long power = b;
      for (int e = 1; power <= (1L << 53); e++) {
        pairs.add(new double[] {b, e});
        pairs.add(new double[] {-b, e});
        pairs.add(new double[] {b, -e});
        if (power > (1L << 53) / b) {
          break;
        }
        power *= b;
      }
    }
    for (int a = -10; a <= 10; a++) {
      for (int c = -10; c <= 10; c++) {
        pairs.add(new double[] {a / 2.0, c / 2.0});
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
   * Random bits for both arguments, uniform draws of Varka's baseline range ({@code x} in [1e-6,
   * 1e6), {@code y} in [-10, 10)), and every normal exponent of {@code x} and subnormal {@code x}
   * (line 213), these last two each beside a {@code |y|} from 2^-12 to 2^12.
   */
  static Inputs.Pairs densePairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed + 6);
    double[] subnormals = new double[n];
    for (int i = 0; i < n; i++) {
      double x = Double.longBitsToDouble(random.nextLong(1L, 0x0010000000000000L));
      subnormals[i] = random.nextBoolean() ? x : -x;
    }
    return concat(
        new Inputs.Pairs(Inputs.sampled(seed, n), Inputs.sampled(seed + 1, n)),
        new Inputs.Pairs(Inputs.uniform(seed + 2, n, 1e-6, 1e6),
            Inputs.uniform(seed + 3, n, -10.0, 10.0)),
        new Inputs.Pairs(Inputs.magnitudes(seed + 4, n, -1022, 1023),
            Inputs.magnitudes(seed + 5, n, -12, 12)),
        new Inputs.Pairs(subnormals, Inputs.magnitudes(seed + 7, n, -12, 12)));
  }

  /**
   * The gate's inputs: the special and the exact pairs, {@code n} pairs of each generator but two,
   * pairs either side of {@code MIN_NORMAL} from {@code n/64} searches (at least one) and {@code
   * 4n} dense pairs.
   */
  static Inputs.Pairs everything(long seed, int n) {
    return concat(specialPairs(), exactPairs(), fuzzPairs(seed, n), integerPairs(seed + 1, n),
        hugeYPairs(seed + 2, n), targetPairs(seed + 3, n),
        minNormalPairs(seed + 4, Math.max(1, n >> 6)), nanPairs(seed + 5, n),
        densePairs(seed + 6, n));
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

  /** The size of the generated parts in the suite. */
  static final int DRAWS = 1 << 17;
  static final long SEED = 0x506f_7700L;

  // ---------------------------------------------------------------------------------------------
  // The check, in this JVM or in one per JIT mode
  // ---------------------------------------------------------------------------------------------

  /** What one pass over the inputs found: every mismatch counted, the first 100 kept. */
  record Tally(long checked, long mismatched, List<String> mismatches, long[] paths,
      NanTally strict, NanTally transcribed) {}

  /**
   * On the lanes of two NaNs with different bits: how many results were the first argument's
   * candidate ({@code x}'s), the second's ({@code y}'s), or both (the two candidates coincide, as
   * for NaNs that differ in the quiet bit only).
   */
  record NanTally(long first, long second, long both) {
    String show() {
      return "first (x) " + first + ", second (y) " + second + ", both " + both;
    }
  }

  /** The candidates' counts of {@code result} over the two-NaN lanes, into {@code into[0..2]}. */
  private static void count(long xb, long yb, long result, long[] into) {
    long first = PairBits.candidate(OPERANDS, xb);
    long second = PairBits.candidate(OPERANDS, yb);
    if (first == second) {
      into[2] += result == first ? 1 : 0;
    } else if (result == first) {
      into[0]++;
    } else if (result == second) {
      into[1]++;
    }
  }

  /** Every pair of {@code p}, the transcription against {@code StrictMath.pow}. */
  static Tally check(Inputs.Pairs p) {
    long[] paths = new long[Path.values().length];
    long[] strict = new long[3];
    long[] transcribed = new long[3];
    List<String> mismatches = new ArrayList<>();
    long mismatched = 0;
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      double want = StrictMath.pow(x, y);
      double got = cPow(x, y, Variant.AS_WRITTEN, paths);
      if (!PairBits.matches(OPERANDS, x, y, want, got) && mismatched++ < 100) {
        mismatches.add("pow(" + Oracle.hex(x) + ", " + Oracle.hex(y) + "): StrictMath "
            + PairBits.expected(OPERANDS, x, y, want) + ", transcription " + Oracle.hex(got));
      }
      long xb = Double.doubleToRawLongBits(x);
      long yb = Double.doubleToRawLongBits(y);
      if (PairBits.bothNaNWithDifferentBits(xb, yb)) {
        count(xb, yb, Double.doubleToRawLongBits(want), strict);
        count(xb, yb, Double.doubleToRawLongBits(got), transcribed);
      }
    }
    return new Tally(p.x().length, mismatched, mismatches, paths,
        new NanTally(strict[0], strict[1], strict[2]),
        new NanTally(transcribed[0], transcribed[1], transcribed[2]));
  }

  /** How many pairs of {@code p} the variant's result does not match {@code StrictMath} on. */
  static long differing(Inputs.Pairs p, Variant variant) {
    long n = 0;
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      n += PairBits.matches(OPERANDS, x, y, StrictMath.pow(x, y), cPow(x, y, variant, null))
          ? 0 : 1;
    }
    return n;
  }

  /**
   * The check in a JVM of its own: {@code [draws [passes [variants]]]}, by default {@link #DRAWS}
   * and 3. It prints the JVM's arguments, each pass's count of pairs, mismatches (the first 100
   * with their bits), the candidates of the two-NaN lanes and how often each {@link Path} was
   * taken, then, given {@code variants}, how many pairs each {@link Variant} changes, and exits 1
   * if any pass has a mismatch, no two-NaN lane of distinct candidates or a {@link Path} never
   * taken.
   *
   * <pre>
   *   java -Xint --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
   *     io.github.vecbricks.vfdlibm.PowTest 0x100000 1 variants
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
      StringBuilder taken = new StringBuilder("pass " + pass + ": paths");
      for (Path path : Path.values()) {
        taken.append(' ').append(path).append('=').append(t.paths()[path.ordinal()]);
        if (t.paths()[path.ordinal()] == 0) {
          System.out.println("pass " + pass + ": " + path + " is never taken");
          failed = true;
        }
      }
      System.out.println(taken);
    }
    for (Variant variant : Variant.values()) {
      if (args.length > 2 && args[2].equals("variants") && variant != Variant.AS_WRITTEN) {
        System.out.println("variant " + variant + ": differs on " + differing(p, variant));
      }
    }
    System.out.println(failed ? "result: FAILED" : "result: ok");
    System.exit(failed ? 1 : 0);
  }

  // ---------------------------------------------------------------------------------------------
  // The tests
  // ---------------------------------------------------------------------------------------------

  /**
   * The gate: the transcription is {@code StrictMath.pow} on every pair (a lane of two NaNs with
   * different bits by {@link PairBits}, where both results must be a candidate), and the pairs
   * take every way out of the C, line 306's rounding cases included.
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
   * The same check in a JVM per JIT mode, as {@link
   * HypotTest#scalarTranscriptionIsStrictMathInEveryJitMode} runs hypot's: every pass of every
   * mode passes, each JVM had the mode's flag and says so in its {@code java.vm.info}. The output
   * shows which candidate each mode's {@code StrictMath} returned on the two-NaN lanes, which is
   * not asserted, since it is not specified. As hypot's, the children run only where the test JVM
   * has no width option.
   */
  @ParameterizedTest
  @EnumSource(HypotTest.JitMode.class)
  void scalarTranscriptionIsStrictMathInEveryJitMode(HypotTest.JitMode mode) throws Exception {
    HypotTest.assumeNoWidthOption();
    HypotTest.assertRanInModeAndPassed(mode,
        HypotTest.runInJvm(PowTest.class, "pow gate", mode, DRAWS >> 3, 2));
  }

  /**
   * The inputs see the transcription changed: without {@code y*t2} at line 261, with line 306's
   * {@code scalbn} replaced by line 307's exponent add, or with a {@code scalbn} that truncates,
   * the variant differs from {@code StrictMath} on some pair, so the gate's no-mismatch result is
   * not that of inputs too weak to tell.
   */
  @ParameterizedTest
  @EnumSource(value = Variant.class, names = "AS_WRITTEN", mode = EnumSource.Mode.EXCLUDE)
  void theInputsSeeAChangedTranscription(Variant variant) {
    assertTrue(differing(everything(SEED + 1, DRAWS >> 3), variant) > 0, variant.name());
  }

  /**
   * Line 119 is the only return before line 122's NaN: {@code NaN ** +-0} is 1 for every NaN, and
   * {@code 1 ** NaN} is NaN, {@code x+y}, by the transcription and by {@code StrictMath}.
   */
  @Test
  void onlyAZeroExponentComesBeforeANaN() {
    long one = Double.doubleToRawLongBits(1.0);
    for (long bits : NANS) {
      double nan = Double.longBitsToDouble(bits);
      for (double zero : new double[] {0.0, -0.0}) {
        assertEquals(one, Double.doubleToRawLongBits(cPow(nan, zero)));
        assertEquals(one, Double.doubleToRawLongBits(StrictMath.pow(nan, zero)));
      }
      for (double base : new double[] {1.0, -1.0}) {
        assertTrue(Double.isNaN(cPow(base, nan)));
        assertTrue(Double.isNaN(StrictMath.pow(base, nan)));
        assertEquals(Double.doubleToRawLongBits(StrictMath.pow(base, nan)),
            Double.doubleToRawLongBits(cPow(base, nan)));
      }
    }
  }

  /** The constants are e_pow.c's, by the hexadecimal its comments give. */
  @Test
  void constantsAreTheSourcesBits() {
    assertEquals(0x3FE2B80340000000L, Double.doubleToRawLongBits(DP_H[1]));
    assertEquals(0x3E4CFDEB43CFD006L, Double.doubleToRawLongBits(DP_L[1]));
    assertEquals(0x4340000000000000L, Double.doubleToRawLongBits(TWO53));
    assertEquals(0x3FE3333333333303L, Double.doubleToRawLongBits(L1));
    assertEquals(0x3FDB6DB6DB6FABFFL, Double.doubleToRawLongBits(L2));
    assertEquals(0x3FD55555518F264DL, Double.doubleToRawLongBits(L3));
    assertEquals(0x3FD17460A91D4101L, Double.doubleToRawLongBits(L4));
    assertEquals(0x3FCD864A93C9DB65L, Double.doubleToRawLongBits(L5));
    assertEquals(0x3FCA7E284A454EEFL, Double.doubleToRawLongBits(L6));
    assertEquals(0x3FC555555555553EL, Double.doubleToRawLongBits(P1));
    assertEquals(0xBF66C16C16BEBD93L, Double.doubleToRawLongBits(P2));
    assertEquals(0x3F11566AAF25DE2CL, Double.doubleToRawLongBits(P3));
    assertEquals(0xBEBBBD41C5D26BF1L, Double.doubleToRawLongBits(P4));
    assertEquals(0x3E66376972BEA4D0L, Double.doubleToRawLongBits(P5));
    assertEquals(0x3FE62E42FEFA39EFL, Double.doubleToRawLongBits(LG2));
    assertEquals(0x3FE62E4300000000L, Double.doubleToRawLongBits(LG2_H));
    assertEquals(0xBE205C610CA86C39L, Double.doubleToRawLongBits(LG2_L));
    assertEquals(0x3FEEC709DC3A03FDL, Double.doubleToRawLongBits(CP));
    assertEquals(0x3FEEC709E0000000L, Double.doubleToRawLongBits(CP_H));
    assertEquals(0xBE3E2FE0145B01F5L, Double.doubleToRawLongBits(CP_L));
    assertEquals(0x3FF71547652B82FEL, Double.doubleToRawLongBits(IVLN2));
    assertEquals(0x3FF7154760000000L, Double.doubleToRawLongBits(IVLN2_H));
    assertEquals(0x3E54AE0BF85DDF44L, Double.doubleToRawLongBits(IVLN2_L));
  }

  // ---------------------------------------------------------------------------------------------
  // The common path, and the guard of the vector port (PROOFS.md, L19 and L20)
  // ---------------------------------------------------------------------------------------------

  /**
   * What the common path gives on a lane, whatever the lane: lines 210-307 run with no return,
   * {@code n = 0} at line 210 and line 212 left out, line 307's exponent add always and line
   * 308's {@code s} left out, as {@link Pow#pow} computes every lane before its finish; and
   * whether the late part of the guard holds there (line 266 or 272 taken as a whole, or line
   * 306's test).
   */
  record CommonPath(double result, boolean bounds, boolean subnormal) {}

  /** {@link #commonPath(double, double, boolean)} as the vector computes it. */
  static CommonPath commonPath(double x, double y) {
    return commonPath(x, y, false);
  }

  /**
   * The common path, lines 210-307 of e_pow.c with every return before them, line 212 and line
   * 308's {@code s} left out; {@code dropT2} drops line 261's {@code y*t2}, the planted fault
   * {@code pow.dropT2}.
   */
  static CommonPath commonPath(double x, double y, boolean dropT2) {
    int ix = hi(x) & 0x7fffffff;
    int n = (ix >> 20) - 0x3ff; // 210, 214
    int j = ix & 0x000fffff; // 215
    ix = j | 0x3ff00000; // 217
    int k;
    if (j <= 0x3988E) { // 218
      k = 0;
    } else if (j < 0xBB67A) { // 219
      k = 1;
    } else { // 220
      k = 0;
      n += 1;
      ix -= 0x00100000;
    }
    double ax = withHi(Math.abs(x), ix); // 221
    double u = ax - BP[k]; // 224
    double v = ONE / (ax + BP[k]); // 225
    double ss = u * v; // 226
    double sH = withLoZero(ss); // 227-228
    double tH = withHi(ZERO, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18)); // 230-231
    double tL = ax - (tH - BP[k]); // 232
    double sL = v * ((u - sH * tH) - sH * tL); // 233
    double s2 = ss * ss; // 235
    double r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6))))); // 236
    r += sL * (sH + ss); // 237
    s2 = sH * sH; // 238
    tH = withLoZero(3.0 + s2 + r); // 239-240
    tL = r - ((tH - 3.0) - s2); // 241
    u = sH * tH; // 243
    v = sL * tH + tL * ss; // 244
    double pH = withLoZero(u + v); // 246-247
    double pL = v - (pH - u); // 248
    double zH = CP_H * pH; // 249
    double zL = CP_L * pH + pL * CP + DP_L[k]; // 250
    double t = (double) n; // 252
    double t1 = withLoZero((((zH + zL) + DP_H[k]) + t)); // 253-254
    double t2 = zL - (((t1 - t) - DP_H[k]) - zH); // 255
    double y1 = withLoZero(y); // 259-260
    pL = dropT2 ? (y - y1) * t1 : (y - y1) * t1 + y * t2; // 261
    pH = y1 * t1; // 262
    double z = pL + pH; // 263
    j = hi(z); // 264
    boolean bounds = j >= 0x40900000 || (j & 0x7fffffff) >= 0x4090cc00; // 266, 272
    int i = j & 0x7fffffff; // 282
    k = (i >> 20) - 0x3ff; // 283
    n = 0; // 284
    if (i > 0x3fe00000) { // 285
      n = j + (0x00100000 >> (k + 1)); // 286
      k = ((n & 0x7fffffff) >> 20) - 0x3ff; // 287
      t = withHi(ZERO, (n & ~(0x000fffff >> k))); // 288-289
      n = ((n & 0x000fffff) | 0x00100000) >> (20 - k); // 290
      if (j < 0) { // 291
        n = -n;
      }
      pH -= t; // 292
    }
    t = withLoZero(pL + pH); // 294-295
    u = t * LG2_H; // 296
    v = (pL - (t - pH)) * LG2 + t * LG2_L; // 297
    z = u + v; // 298
    double w = v - (z - u); // 299
    t = z * z; // 300
    t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5)))); // 301
    r = (z * t1) / (t1 - TWO) - (w + z * w); // 302
    z = ONE - (r - z); // 303
    j = hi(z) + (n << 20); // 304-305
    boolean subnormal = (j >> 20) <= 0; // 306
    return new CommonPath(withHi(z, j), bounds, subnormal); // 307
  }

  /** The parts of the vector's guard (PROOFS.md, L19): each a reason a lane is finished. */
  enum Guard {
    /** {@code hx < 0}: a negative {@code x}, {@code -0} included. */
    NEGATIVE_X,
    /** {@code 0 <= hx < 0x00100000}: {@code +0} or a positive subnormal {@code x}. */
    ZERO_OR_SUBNORMAL_X,
    /** {@code hx >= 0x7ff00000}: {@code +inf} or a positive NaN. */
    NON_FINITE_X,
    /** {@code x} is 1.0. */
    X_ONE,
    /** {@code y} is a zero. */
    Y_ZERO,
    /** {@code iy > 0x41e00000}: {@code |y|} past 2^31, the infinities and the NaNs included. */
    Y_HUGE,
    /** {@code y} is 1, -1, 2 or 0.5. */
    Y_SPECIAL,
    /** Line 266 or line 272 holds, each taken as a whole. */
    Z_BOUNDS,
    /** Line 306: {@code (j>>20) <= 0}, a subnormal result. */
    SUBNORMAL_RESULT
  }

  /** The parts of the guard that hold on {@code (x, y)}, as the vector tests them. */
  static java.util.EnumSet<Guard> guard(double x, double y) {
    java.util.EnumSet<Guard> holds = java.util.EnumSet.noneOf(Guard.class);
    int hx = hi(x);
    int iy = hi(y) & 0x7fffffff;
    long yb = Double.doubleToRawLongBits(y);
    if (hx < 0) {
      holds.add(Guard.NEGATIVE_X);
    } else if (hx < 0x00100000) {
      holds.add(Guard.ZERO_OR_SUBNORMAL_X);
    } else if (hx >= 0x7ff00000) {
      holds.add(Guard.NON_FINITE_X);
    }
    if (Double.doubleToRawLongBits(x) == Pow.X_ONE_BITS) {
      holds.add(Guard.X_ONE);
    }
    if ((yb & Long.MAX_VALUE) == 0) {
      holds.add(Guard.Y_ZERO);
    }
    if (iy > 0x41e00000) {
      holds.add(Guard.Y_HUGE);
    }
    if (yb == Pow.Y_ONE_BITS || yb == Pow.Y_MINUS_ONE_BITS || yb == Pow.Y_TWO_BITS
        || yb == Pow.Y_HALF_BITS) {
      holds.add(Guard.Y_SPECIAL);
    }
    CommonPath c = commonPath(x, y);
    if (c.bounds()) {
      holds.add(Guard.Z_BOUNDS);
    }
    if (c.subnormal()) {
      holds.add(Guard.SUBNORMAL_RESULT);
    }
    return holds;
  }

  /** The ways out of the C a lane the guard admits may take (L19): no other is allowed. */
  static final java.util.EnumSet<Path> COMMON_PATHS = java.util.EnumSet.of(Path.INTERVAL_0,
      Path.INTERVAL_1, Path.INTERVAL_2, Path.Z_ABOVE_HALF, Path.Z_NOT_ABOVE_HALF,
      Path.NORMAL_RESULT);

  /** Every way out of the C for the guard's tests: the gate's inputs and the boundaries. */
  static Inputs.Pairs guardInputs(long seed, int n) {
    return concat(everything(seed, n), guardBoundaryPairs());
  }

  /**
   * The pairs at each boundary of the guard and beside it: {@code x} at the words of the
   * smallest normal, the largest finite, 1 and their neighbours, either sign; {@code y} at {@code
   * 0x41e00000} with low words 0, 1 and all ones and the word above, at 1, -1, 2, 0.5, -2, -0.5
   * and the neighbours of each, the zeros and the smallest subnormals; each beside a few common
   * partners.
   */
  static Inputs.Pairs guardBoundaryPairs() {
    List<Double> xs = new ArrayList<>();
    for (long b : new long[] {0x000fffffffffffffL, 0x0010000000000000L, 0x0010000000000001L,
        0x7fefffffffffffffL, 0x7ff0000000000000L, 0x3ff0000000000000L, 0x3fefffffffffffffL,
        0x3ff0000000000001L, 0x0000000000000001L, 0x0000000000000000L}) {
      xs.add(Double.longBitsToDouble(b));
      xs.add(-Double.longBitsToDouble(b));
    }
    xs.add(3.0);
    xs.add(0.1);
    xs.add(1e5);
    List<Double> ys = new ArrayList<>();
    for (double special : new double[] {1.0, -1.0, 2.0, 0.5, -2.0, -0.5, 0.0, Double.MIN_VALUE,
        0x1p31, 1.5}) {
      for (double v : new double[] {special, Math.nextUp(special), Math.nextDown(special)}) {
        ys.add(v);
        ys.add(-v);
      }
    }
    for (int word : new int[] {0x41dfffff, 0x41e00000, 0x41e00001}) {
      for (long low : new long[] {0L, 1L, 0xffffffffL}) {
        ys.add(fromWords(word, low, false));
        ys.add(fromWords(word, low, true));
      }
    }
    double[] x = xs.stream().mapToDouble(Double::doubleValue).toArray();
    double[] y = ys.stream().mapToDouble(Double::doubleValue).toArray();
    return Inputs.cross(x, y);
  }

  /**
   * L19's statement on scalar Java: on every pair the guard admits, the C as written takes only
   * the common path's ways out ({@link #COMMON_PATHS}: none of the returns of lines 119-198 or
   * 266-278, line 208's branch and not line 212, line 307 and not line 306, {@code s = one}),
   * and the common path's result is the C's, bit for bit. The pairs admitted are many and reach
   * each of those ways out.
   */
  @Test
  void l19TheGuardAdmitsOnlyTheCommonPath() {
    Inputs.Pairs p = guardInputs(SEED + 2, DRAWS >> 2);
    long admitted = 0;
    long[] reached = new long[Path.values().length];
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      if (!guard(x, y).isEmpty()) {
        continue;
      }
      admitted++;
      long[] paths = new long[Path.values().length];
      double c = cPow(x, y, Variant.AS_WRITTEN, paths);
      for (Path path : Path.values()) {
        if (paths[path.ordinal()] > 0) {
          assertTrue(COMMON_PATHS.contains(path), "pow(" + Oracle.hex(x) + ", " + Oracle.hex(y)
              + ") is admitted and takes " + path);
          reached[path.ordinal()]++;
        }
      }
      assertEquals(Double.doubleToRawLongBits(c),
          Double.doubleToRawLongBits(commonPath(x, y).result()),
          "pow(" + Oracle.hex(x) + ", " + Oracle.hex(y) + ")");
    }
    assertTrue(admitted > 20_000, "admitted " + admitted);
    for (Path path : COMMON_PATHS) {
      assertTrue(reached[path.ordinal()] > 0, path + " is never reached by an admitted pair");
    }
  }

  /**
   * L19's hinges: each part of the guard, alone, keeps out pairs on which the common path is not
   * the C's (so a guard without it would be wrong), but for the two parts L19 names as redundant,
   * {@code x == 1} and {@code y == 0}, on whose pairs the common path gives the C's result, and
   * which the guard keeps because the C returns early there. Each part is drawn on pairs only it
   * holds on.
   */
  @Test
  void l19EachPartOfTheGuardIsAHingeOrNamedRedundant() {
    Inputs.Pairs p = concat(guardInputs(SEED + 3, DRAWS >> 2), hingePairs(SEED + 4, DRAWS >> 2));
    long[] alone = new long[Guard.values().length];
    long[] wrong = new long[Guard.values().length];
    for (int i = 0; i < p.x().length; i++) {
      double x = p.x()[i];
      double y = p.y()[i];
      java.util.EnumSet<Guard> holds = guard(x, y);
      if (holds.size() != 1) {
        continue;
      }
      Guard g = holds.iterator().next();
      alone[g.ordinal()]++;
      if (!PairBits.matches(OPERANDS, x, y, StrictMath.pow(x, y), commonPath(x, y).result())) {
        wrong[g.ordinal()]++;
      }
    }
    StringBuilder counts = new StringBuilder();
    for (Guard g : Guard.values()) {
      counts.append(' ').append(g).append('=').append(wrong[g.ordinal()]).append('/')
          .append(alone[g.ordinal()]);
    }
    System.out.println("pow L19, pairs differing / pairs of each part alone:" + counts);
    for (Guard g : Guard.values()) {
      assertTrue(alone[g.ordinal()] > 0, g + " never holds alone:" + counts);
      if (g == Guard.X_ONE || g == Guard.Y_ZERO) {
        assertEquals(0, wrong[g.ordinal()], g + " is named redundant:" + counts);
      } else {
        assertTrue(wrong[g.ordinal()] > 0, g + " is no hinge on these pairs:" + counts);
      }
    }
  }

  /**
   * Pairs on which one part of the guard holds alone: a negative {@code x} beside a moderate
   * {@code y}, a positive subnormal {@code x} beside a {@code y} small enough that the result is
   * normal, {@code +inf} and positive NaNs beside a moderate {@code y}, {@code x = 1} and {@code
   * y} zero beside common partners, {@code x} beside 1 with a {@code |y|} past 2^31 that keeps
   * {@code z} in range, {@code y} special beside common {@code x}, and {@code z} out of range or
   * a subnormal result from a common pair.
   */
  static Inputs.Pairs hingePairs(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[n];
    double[] ys = new double[n];
    double[] specials = {1.0, -1.0, 2.0, 0.5};
    for (int i = 0; i < n; i++) {
      double x = random.nextDouble(1e-3, 1e3);
      double y = random.nextDouble(-10.0, 10.0);
      switch (random.nextInt(9)) {
        case 0 -> x = -x;
        case 1 -> {
          x = Double.longBitsToDouble(random.nextLong(1L, 0x0010000000000000L));
          y = random.nextDouble(-0.9, 0.9);
        }
        case 2 -> x = random.nextBoolean() ? Double.POSITIVE_INFINITY
            : Double.longBitsToDouble(0x7ff0000000000001L + random.nextLong(0x000fffffffffffffL));
        case 3 -> x = 1.0;
        case 4 -> y = random.nextBoolean() ? 0.0 : -0.0;
        case 5 -> {
          x = 1.0 + Math.scalb(random.nextDouble(-1.0, 1.0), -random.nextInt(40, 53));
          y = Math.scalb(1.0 + random.nextDouble(), random.nextInt(32, 40));
          y = random.nextBoolean() ? y : -y;
        }
        case 6 -> y = specials[random.nextInt(specials.length)];
        case 7 -> y = random.nextBoolean() ? 1100.0 / StrictMath.log(x) * StrictMath.log(2.0)
            : -1100.0 / StrictMath.log(x) * StrictMath.log(2.0);
        default -> y = random.nextDouble(-1074.0, -1023.0)
            / (StrictMath.log(x) / StrictMath.log(2.0));
      }
      xs[i] = x;
      ys[i] = y;
    }
    return new Inputs.Pairs(xs, ys);
  }

  // ---------------------------------------------------------------------------------------------
  // The vector port
  // ---------------------------------------------------------------------------------------------

  private static void assertPow(VectorSpecies<Double> species, Inputs.Pairs p) {
    Oracle.assertPair("VFdlibm.pow", species, OPERANDS, VFdlibm::pow, StrictMath::pow, p.x(),
        p.y());
  }

  /** {@link VFdlibm#pow} masked, under seeded random masks, all-true and all-false. */
  private static void assertPowMasked(VectorSpecies<Double> species, Inputs.Pairs p, long seed) {
    Oracle.assertPairMasked("VFdlibm.pow", species, OPERANDS, VFdlibm::pow, StrictMath::pow,
        p.x(), p.y(), seed);
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void specialValues(VectorWidth width) {
    assertPow(width.species(), concat(specialPairs(), nanPairs(9000L + width.ordinal(), 1 << 14)));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void exactPowers(VectorWidth width) {
    assertPow(width.species(), exactPairs());
  }

  /** The words and points the C tests, as the nightly's fuzz draws them. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void edges(VectorWidth width) {
    assertPow(width.species(), fuzzPairs(9100L + width.ordinal(), DRAWS));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void negativeXAndHugeY(VectorWidth width) {
    assertPow(width.species(), concat(integerPairs(9200L + width.ordinal(), DRAWS),
        hugeYPairs(9250L + width.ordinal(), DRAWS)));
  }

  /** {@code z} near 1024, -1075, the subnormal results and +-0.5, and either side of MIN_NORMAL. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void targets(VectorWidth width) {
    assertPow(width.species(), concat(targetPairs(9300L + width.ordinal(), DRAWS),
        minNormalPairs(9350L + width.ordinal(), 1 << 10)));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void sampledAndDense(VectorWidth width) {
    assertPow(width.species(), densePairs(9500L + 16L * width.ordinal(), DRAWS));
  }

  /** L19's vector tie: the boundaries of the guard, and the pairs of each part alone. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void guardBoundaries(VectorWidth width) {
    Inputs.Pairs p = concat(guardBoundaryPairs(), hingePairs(9550L + width.ordinal(), DRAWS));
    assertPow(width.species(), p);
    assertPowMasked(width.species(), p, 9551L + width.ordinal());
  }

  /**
   * The vector's lanes equal the C as written ({@link #cPow}) on every pair, by {@link
   * PairBits}, and the common path's result, bit for bit, on every lane the guard admits: the
   * port computes the transcribed operations, not only {@code StrictMath}'s bits.
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void lanesAreTheTranscription(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    Inputs.Pairs p = concat(guardInputs(9600L + width.ordinal(), 1 << 13),
        new Inputs.Pairs(Inputs.uniform(9601L, 1 << 16, 1e-6, 1e6),
            Inputs.uniform(9602L, 1 << 16, -10.0, 10.0)));
    int n = species.length();
    long admitted = 0;
    for (int i = 0; i + n <= p.x().length; i += n) {
      double[] got = VFdlibm.pow(DoubleVector.fromArray(species, p.x(), i),
          DoubleVector.fromArray(species, p.y(), i)).toArray();
      for (int lane = 0; lane < n; lane++) {
        double x = p.x()[i + lane];
        double y = p.y()[i + lane];
        String where = "pow(" + Oracle.hex(x) + ", " + Oracle.hex(y) + ")";
        assertTrue(PairBits.matches(OPERANDS, x, y, cPow(x, y), got[lane]), where);
        if (guard(x, y).isEmpty()) {
          admitted++;
          assertEquals(Double.doubleToRawLongBits(commonPath(x, y).result()),
              Double.doubleToRawLongBits(got[lane]), where);
        }
      }
    }
    assertTrue(admitted > 1 << 15, "admitted " + admitted);
  }

  private static Inputs.Pairs hardCasePairs() {
    return Inputs.hardCasePairs("pow");
  }

  /**
   * {@code hard-cases/pow.txt}: exact results, ties, results near a midpoint and the doubles
   * either side of the result's thresholds, computed by {@code dev/pow-hard-cases.py}
   * ({@link HardCasePairsTest} checks the claims).
   */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void publishedPairs(VectorWidth width) {
    assertPow(width.species(), hardCasePairs());
  }

  /** A smaller {@link #everything}, with the boundaries and hard cases, for the masked forms. */
  private static Inputs.Pairs everythingSmall(long seed) {
    return concat(everything(seed, 1 << 12), guardBoundaryPairs(), hardCasePairs());
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void masked(VectorWidth width) {
    assertPowMasked(width.species(), everythingSmall(9700L), 9701L);
  }

  /** SPECIES_MAX, which {@link VectorWidth} does not run: its int species is another shape. */
  @Test
  void maxSpecies() {
    Inputs.Pairs p = everythingSmall(9750L);
    assertPow(DoubleVector.SPECIES_MAX, p);
    assertPowMasked(DoubleVector.SPECIES_MAX, p, 9751L);
  }

  private static double bits(long b) {
    return Double.longBitsToDouble(b);
  }

  /** Pairs the per-lane finish takes, one for each part of the guard and the two-NaN lane. */
  private static final double[][] RARE_LANES = {
    {-2.0, 3.0}, {-2.0, 0.5}, {0x1p-1060, 0.75}, {0.0, 2.5}, {Double.POSITIVE_INFINITY, 1.5},
    {Double.NaN, 1.5}, {1.0, 7.25}, {5.0, 0.0}, {5.0, -0.0}, {1.0000001, 0x1p40},
    {5.0, Double.NaN}, {5.0, 1.0}, {5.0, -1.0}, {5.0, 2.0}, {5.0, 0.5}, {10.0, 400.0},
    {1e-6, 60.0}, {0.5, 1060.0}, {bits(0xfff4_0000_0000_0456L), bits(0x7ff8_0000_0000_0123L)},
  };

  /** Pairs the vector computes: each interval of the significand, both sides of |z| = 0.5. */
  private static final double[][] GENERAL_LANES = {
    {3.7, 2.3}, {1.2, -7.5}, {1.5, 3.0}, {1.9, 0.25}, {123.0, -0.3}, {0.001, 9.5},
    {2.0, -2.0}, {4.0, -0.5}, {1.0000001, 1e6}, {bits(0x3ff3988e_00000000L), 3.0},
    {bits(0x3ffbb67a_00000000L), 3.0}, {0.75, 1e-300}, {7.0, 300.0}, {0.5, 1021.0},
  };

  /**
   * One vector for every subset of a vector's lanes ({@link ExpTest#rareLaneSubsets}), the
   * subset's lanes rare and the others common, so that a vector holds rare lanes of each kind
   * beside common lanes of each form, in every position and mix.
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

  /** The lanes the tests call rare and general are what the guard says they are. */
  @Test
  void rareAndGeneralLanesAreWhatTheGuardSays() {
    for (double[] pair : RARE_LANES) {
      assertTrue(!guard(pair[0], pair[1]).isEmpty(), Oracle.hex(pair[0]) + ", "
          + Oracle.hex(pair[1]));
    }
    for (double[] pair : GENERAL_LANES) {
      assertTrue(guard(pair[0], pair[1]).isEmpty(), Oracle.hex(pair[0]) + ", "
          + Oracle.hex(pair[1]));
    }
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
    assertPow(species, p);
    assertPowMasked(species, p, 9800L);
  }

  @Test
  void maskedFormRejectsANullMask() {
    DoubleVector v = DoubleVector.zero(DoubleVector.SPECIES_128);
    assertThrows(NullPointerException.class, () -> VFdlibm.pow(v, v, null));
  }

  /** The port's constants and words are e_pow.c's, as this transcription has them. */
  @Test
  void portConstantsAreTheSources() {
    double[][] same = {
      {Pow.BP1, BP[1]}, {Pow.DP_H1, DP_H[1]}, {Pow.DP_L1, DP_L[1]}, {Pow.ONE, ONE},
      {Pow.TWO, TWO}, {Pow.L1, L1}, {Pow.L2, L2}, {Pow.L3, L3}, {Pow.L4, L4}, {Pow.L5, L5},
      {Pow.L6, L6}, {Pow.P1, P1}, {Pow.P2, P2}, {Pow.P3, P3}, {Pow.P4, P4}, {Pow.P5, P5},
      {Pow.LG2, LG2}, {Pow.LG2_H, LG2_H}, {Pow.LG2_L, LG2_L}, {Pow.CP, CP}, {Pow.CP_H, CP_H},
      {Pow.CP_L, CP_L}
    };
    for (double[] pair : same) {
      assertEquals(Double.doubleToRawLongBits(pair[1]), Double.doubleToRawLongBits(pair[0]));
    }
    assertEquals(1.0, BP[0]);
    assertEquals(0L, Double.doubleToRawLongBits(DP_H[0]));
    assertEquals(0L, Double.doubleToRawLongBits(DP_L[0]));
    assertEquals(0x00100000, Pow.X_NORMAL_WORD);
    assertEquals(0x7ff00000, Pow.INF_WORD);
    assertEquals(0x41e00000, Pow.Y_HUGE_WORD);
    assertEquals(0x3988E, Pow.SPLIT_LOW);
    assertEquals(0xBB67A, Pow.SPLIT_HIGH);
    assertEquals(0x40900000, Pow.OVERFLOW_WORD);
    assertEquals(0x4090cc00, Pow.UNDERFLOW_WORD);
    assertEquals(0x3fe00000, Pow.HALF_WORD);
    assertEquals(Double.doubleToRawLongBits(1.0), Pow.Y_ONE_BITS);
    assertEquals(Double.doubleToRawLongBits(-1.0), Pow.Y_MINUS_ONE_BITS);
    assertEquals(Double.doubleToRawLongBits(2.0), Pow.Y_TWO_BITS);
    assertEquals(Double.doubleToRawLongBits(0.5), Pow.Y_HALF_BITS);
    assertEquals(Double.doubleToRawLongBits(1.0), Pow.X_ONE_BITS);
  }

  // ---------------------------------------------------------------------------------------------
  // Planted faults
  // ---------------------------------------------------------------------------------------------

  /**
   * Each of pow's faults, with a check that must fail while it is planted: the vector form over
   * {@link #faultRunInputs()} at every width the host has. Each was sized on draws before it was
   * wired; the sizes are in {@code SKILLS.md}, "What the pow port taught".
   */
  static Map<String, Executable> faultCatchers() {
    Map<String, Executable> catchers = new LinkedHashMap<>();
    for (String fault : new String[] {
        Pow.GUARD_ADMITS_NEGATIVE_X, Pow.GUARD_MISSES_SUBNORMAL, Pow.DROP_T2}) {
      catchers.put(fault, () -> forEachWidth(species ->
          PlantedFaultTest.assertCaught(fault, () -> assertPow(species, faultRunInputs()))));
    }
    return catchers;
  }

  private static Inputs.Pairs faultRunInputs() {
    return concat(specialPairs(), integerPairs(9950L, 1 << 12), targetPairs(9951L, 1 << 12),
        hingePairs(9952L, 1 << 12), densePairs(9953L, 1 << 10));
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void faultRunInputsPass(VectorWidth width) {
    assertPow(width.species(), faultRunInputs());
  }

  private static void forEachWidth(Consumer<VectorSpecies<Double>> check) {
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    for (int bits = 128; bits <= preferred; bits *= 2) {
      check.accept(VectorSpecies.of(double.class, VectorShape.forBitSize(bits)));
    }
  }
}
