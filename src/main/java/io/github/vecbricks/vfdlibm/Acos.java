/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_acos.c (reference/fdlibm-5.3/e_acos.c), which carries this
 * notice:
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

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * acos over lanes, ported from fdlibm 5.3's {@code __ieee754_acos} in {@code
 * reference/fdlibm-5.3/e_acos.c}; every lane is bit-identical to {@code StrictMath.acos}.
 *
 * <p>The method, from the C, by {@code ix}, the high word of {@code |x|}, and {@code hx}'s sign:
 * for {@code ix <= 0x3c600000} return {@code pio2_hi+pio2_lo}; below {@code 0.5}, with {@code z =
 * x*x}, {@code pio2_hi - (x - (pio2_lo - x*r))} where {@code r = p/q}, {@code p} and {@code q}
 * being polynomials in {@code z}; for {@code x <= -0.5}, with {@code z = (1+x)*0.5} and {@code s =
 * sqrt(z)}, {@code pi - 2.0*(s+w)} with {@code w = r*s - pio2_lo}; and for {@code x >= 0.5}, with
 * {@code z = (1-x)*0.5}, {@code s = sqrt(z)}, {@code df} the root {@code s} with its low word
 * cleared and {@code c = (z - df*df)/(s+df)}, {@code 2.0*(df + w)} with {@code w = r*s + c}.
 * Every operation is the C's, in the C's order, with no fused multiply-add and no
 * reassociation, so no lemma of its own is needed beyond L13's blend argument and (R4)'s {@code
 * sqrt}; {@code PROOFS.md}'s acos section says which hypotheses of L13 each blend meets.
 *
 * <p>The three ranges below 1 share one pass, as asin's do: {@code z} is picked by the C's
 * compares ({@code x*x}, {@code (1+x)*0.5} or {@code (1-x)*0.5}), and the polynomials and the
 * division {@code p/q} run on it in every lane. The root is {@code VectorOperators.SQRT}. The
 * step that clears a low word, {@code __LO(df) = 0}, is the C's own, an AND of the lane's bits with
 * {@code 0xFFFFFFFF00000000}, and not arithmetic. The positive range divides a second time, for
 * {@code c}; every lane computes both divisions and the blends keep each lane's.
 *
 * <p>The rare path: {@code |x| >= 1} (exactly {@code 1} gives {@code 0.0}, exactly {@code -1}
 * gives {@code pi+2.0*pio2_lo}, anything above one or a NaN a NaN made by {@code (x-x)/(x-x)}).
 * The arm finishes those lanes per lane with {@code StrictMath.acos}, which is the C. A vector
 * holds one only when its input is {@code 1} or more in magnitude or a NaN.
 *
 * <p>The C's {@code int} words stay in int lanes, as in asin; every test is the C's compare of
 * {@code ix = hx&0x7fffffff} or of {@code hx}'s sign. C2 stops inlining once the compiled graph
 * passes {@code NodeCountInliningCutoff}, so every call this body makes comes first ({@code
 * Lanes.intSpecies}, {@code Lanes.hiInt}); after them there are only Vector API operations and
 * the per-lane finish written out in the body.
 */
final class Acos {

  // The constants of e_acos.c, as its source writes them; AcosTest checks the bits.
  static final double ONE = 1.0;
  static final double PI = 3.14159265358979311600e+00; // 0x400921FB, 0x54442D18
  static final double PIO2_HI = 1.57079632679489655800e+00; // 0x3FF921FB, 0x54442D18
  static final double PIO2_LO = 6.12323399573676603587e-17; // 0x3C91A626, 0x33145C07
  /** The coefficients of {@code R(z)}: pS0 to pS5, then qS1 to qS4. */
  static final double PS0 = 1.66666666666666657415e-01; // 0x3FC55555, 0x55555555
  static final double PS1 = -3.25565818622400915405e-01; // 0xBFD4D612, 0x03EB6F7D
  static final double PS2 = 2.01212532134862925881e-01; // 0x3FC9C155, 0x0E884455
  static final double PS3 = -4.00555345006794114027e-02; // 0xBFA48228, 0xB5688F3B
  static final double PS4 = 7.91534994289814532176e-04; // 0x3F49EFE0, 0x7501B288
  static final double PS5 = 3.47933107596021167570e-05; // 0x3F023DE1, 0x0DFDF709
  static final double QS1 = -2.40339491173441421878e+00; // 0xC0033A27, 0x1C8A2D4B
  static final double QS2 = 2.02094576023350569471e+00; // 0x40002AE5, 0x9C598AC8
  static final double QS3 = -6.88283971605453293030e-01; // 0xBFE6066C, 0x1B8D0159
  static final double QS4 = 7.70381505559019352791e-02; // 0x3FB3B8C5, 0xB12E9282

  // The high words e_acos.c compares |x| against.
  /** {@code |x| >= 1}: the lanes finished per lane. */
  static final int HX_ONE = 0x3ff00000;
  /** {@code |x| < 0.5}: the lanes of the first form; from it on the sign of {@code hx} decides. */
  static final int HX_HALF = 0x3fe00000;
  /**
   * {@code ix <= 0x3c600000}: the lanes whose result is a constant, the doubles below {@code 2^-57}
   * and those of the word of {@code 2^-57} itself, up to just under {@code 2^-57 * (1 + 2^-20)}.
   */
  static final int HX_TINY = 0x3c600000;

  /** The mask of {@code __LO(df) = 0}: the high word kept, the low word cleared. */
  static final long CLEAR_LOW_WORD = 0xFFFFFFFF00000000L;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_PIO2_LO = "acos.dropPio2Lo";
  static final String HALF_MOVED = "acos.halfMoved";
  static final String SKIP_LOW_CLEAR = "acos.skipLowClear";
  static final String TINY_MOVED = "acos.tinyMoved";
  // Read once into constants, so the body holds no call and an unplanted fault folds away. Each
  // is sized in SKILLS.md, "What the acos port taught".
  /** {@code pio2_lo} as {@code 0.0} in the two forms that use it besides the constant return. */
  private static final double PIO2_LO_USED = Faults.is(DROP_PIO2_LO) ? 0.0 : PIO2_LO;
  /** Moves line 78's {@code 0.5} word to {@code 0.75}'s, {@code 0x3fe80000}. */
  private static final int HX_HALF_USED = Faults.is(HALF_MOVED) ? 0x3fe80000 : HX_HALF;
  /** The low-word clear skipped: the mask keeps every bit. */
  private static final long CLEAR_LOW_WORD_USED = Faults.is(SKIP_LOW_CLEAR) ? -1L : CLEAR_LOW_WORD;
  /** Moves line 79's word to {@code 0x3cd00000}, {@code 2^-50}'s. */
  private static final int HX_TINY_USED = Faults.is(TINY_MOVED) ? 0x3cd00000 : HX_TINY;

  private Acos() {}

  /**
   * acos with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an
   * inactive lane never takes the per-lane finish.
   */
  static DoubleVector acos(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 69-70: hx = __HI(x); ix = hx&0x7fffffff, below.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector ix = hx.and(0x7fffffff);
    // Lines 71, 78, 79 and 85: |x| < 1, |x| < 0.5, ix <= 0x3c600000 and hx < 0.
    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_ONE).cast(species);
    VectorMask<Double> small = ix.compare(VectorOperators.LT, HX_HALF_USED).cast(species);
    VectorMask<Double> tiny = ix.compare(VectorOperators.LE, HX_TINY_USED).cast(species);
    VectorMask<Double> negative = hx.compare(VectorOperators.LT, 0).cast(species);

    // Lines 80, 86 and 94: z = x*x below 0.5, (one+x)*0.5 for x <= -0.5, (one-x)*0.5 above. The
    // polynomials and the division then run on z in every lane (L13, H2: an operand blend).
    DoubleVector one = DoubleVector.broadcast(species, ONE);
    DoubleVector z = one.sub(x).mul(0.5).blend(one.add(x).mul(0.5), negative)
        .blend(x.mul(x), small);
    // Lines 81-82, 87-88, 99-100: p = z*(pS0+z*(pS1+...)), q = one+z*(qS1+z*(qS2+...)).
    DoubleVector p4 = z.mul(PS5).add(PS4);
    DoubleVector p3 = z.mul(p4).add(PS3);
    DoubleVector p2 = z.mul(p3).add(PS2);
    DoubleVector p1 = z.mul(p2).add(PS1);
    DoubleVector p = z.mul(z.mul(p1).add(PS0));
    DoubleVector q3 = z.mul(QS4).add(QS3);
    DoubleVector q2 = z.mul(q3).add(QS2);
    DoubleVector q1 = z.mul(q2).add(QS1);
    DoubleVector q = z.mul(q1).add(ONE);
    // Lines 83, 90 and 101: r = p/q, one division, the same operands in all three.
    DoubleVector r = p.div(q);
    // Lines 89 and 95: s = sqrt(z).
    DoubleVector s = z.lanewise(VectorOperators.SQRT);

    // Line 84: pio2_hi - (x - (pio2_lo-x*r)), the first form.
    DoubleVector first = DoubleVector.broadcast(species, PIO2_HI)
        .sub(x.sub(DoubleVector.broadcast(species, PIO2_LO_USED).sub(x.mul(r))));
    // Lines 91-92: w = r*s-pio2_lo; pi - 2.0*(s+w), for x <= -0.5.
    DoubleVector w = r.mul(s).sub(PIO2_LO_USED);
    DoubleVector second = DoubleVector.broadcast(species, PI).sub(s.add(w).mul(2.0));
    // Lines 96-97: df = s; __LO(df) = 0, the C's own clear of the low word (an AND of the bits).
    DoubleVector df = (DoubleVector) ((LongVector) s.viewAsIntegralLanes())
        .and(CLEAR_LOW_WORD_USED).viewAsFloatingLanes();
    // Line 98: c = (z-df*df)/(s+df), the second division.
    DoubleVector c = z.sub(df.mul(df)).div(s.add(df));
    // Lines 102-103: w = r*s+c; 2.0*(df+w), for x >= 0.5.
    DoubleVector third = df.add(r.mul(s).add(c)).mul(2.0);
    // Line 79: ix <= 0x3c600000 returns pio2_hi+pio2_lo, a constant of the C's own sum.
    DoubleVector result = third.blend(second, negative).blend(first, small)
        .blend(DoubleVector.broadcast(species, PIO2_HI + PIO2_LO), tiny);

    if (live != null) {
      result = x.blend(result, live);
    }
    // |x| >= 1, infinities and NaN included, is finished per lane, written out here with no call
    // that takes a vector, as Asin's finish is: lane and withLane are lane extract and insert,
    // which C2 intrinsifies for the fixed shapes, and the only call is StrictMath.acos on a
    // double. The first eight lanes have constant indices, as in the other ports' finishes
    // since #100, and the loop takes the lanes past eight. rare is and-ed with live, so an
    // inactive lane is never rewritten.
    VectorMask<Double> rare = common.not();
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.acos(x.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.acos(x.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.acos(x.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.acos(x.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.acos(x.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.acos(x.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.acos(x.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.acos(x.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.acos(x.lane(lane)));
      }
    }
    return result;
  }
}
