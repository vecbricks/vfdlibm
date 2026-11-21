/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_asin.c (reference/fdlibm-5.3/e_asin.c), which carries this
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
 * asin over lanes, ported from fdlibm 5.3's {@code __ieee754_asin} in {@code
 * reference/fdlibm-5.3/e_asin.c}; every lane is bit-identical to {@code StrictMath.asin}.
 *
 * <p>The method, from the C, by {@code ix}, the high word of {@code |x|}: below {@code 2^-27}
 * return {@code x}; below {@code 0.5}, with {@code t = x*x}, return {@code x + x*(p/q)}, {@code
 * p} and {@code q} being polynomials in {@code t}; from {@code 0.5} up to 1, with {@code t =
 * (1-|x|)*0.5} and {@code s = sqrt(t)}, return {@code pio2_hi - (2.0*(s + s*w) - pio2_lo)} with
 * {@code w = p/q} above {@code 0.975}, and below it {@code pio4_hi - (p - q)} with {@code w}
 * the root {@code s} with its low word cleared, {@code c = (t - w*w)/(s+w)}, {@code p = 2.0*s*r
 * - (pio2_lo - 2.0*c)} and {@code q = pio4_hi - 2.0*w}, where {@code r = p/q}; the result takes
 * {@code x}'s sign. Every operation is the C's, in the C's order, with no fused multiply-add and
 * no reassociation, so no lemma of its own is needed beyond L13's blend argument and (R4)'s
 * {@code sqrt}; {@code PROOFS.md}'s asin section says which hypotheses of L13 each blend meets.
 *
 * <p>The three ranges below 1 share one pass: {@code t} is {@code x*x} below {@code 0.5} and
 * {@code (1-|x|)*0.5} from it on, picked by the C's compare, and the polynomials and the division
 * {@code p/q} are the same operations on it in all three. The root is {@code
 * VectorOperators.SQRT} on {@code t}, correctly rounded as the C's {@code sqrt} is. The step that
 * clears a low word, {@code __LO(w) = 0}, is the C's own, an AND of the lane's bits with {@code
 * 0xFFFFFFFF00000000}, and not arithmetic. The split range divides a second time, for {@code
 * c}; every lane computes both divisions and the blends keep each lane's.
 *
 * <p>The rare path: {@code |x| >= 1} (exactly {@code +-1} gives {@code x*pio2_hi + x*pio2_lo},
 * anything above one or a NaN a NaN made by {@code (x-x)/(x-x)}). The arm finishes those lanes
 * per lane with {@code StrictMath.asin}, which is the C. A vector holds one only when its input
 * is {@code 1} or more in magnitude or a NaN.
 *
 * <p>The C's {@code int} words stay in int lanes, as in sinh: {@code hx} is the high word,
 * signed, with the double vector's lane count at half its bits, and every test is the C's compare
 * of {@code ix = hx&0x7fffffff}. C2 stops inlining once the compiled graph passes {@code
 * NodeCountInliningCutoff}, so every call this body makes comes first ({@code Lanes.intSpecies},
 * {@code Lanes.hiInt}); after them there are only Vector API operations and the per-lane finish
 * written out in the body.
 */
final class Asin {

  // The constants of e_asin.c, as its source writes them; AsinTest checks the bits.
  static final double ONE = 1.0;
  static final double PIO2_HI = 1.57079632679489655800e+00; // 0x3FF921FB, 0x54442D18
  static final double PIO2_LO = 6.12323399573676603587e-17; // 0x3C91A626, 0x33145C07
  static final double PIO4_HI = 7.85398163397448278999e-01; // 0x3FE921FB, 0x54442D18
  /** The coefficients of {@code R(x^2)}: pS0 to pS5, then qS1 to qS4. */
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

  // The high words e_asin.c compares |x| against.
  /** {@code |x| >= 1}: the lanes finished per lane. */
  static final int HX_ONE = 0x3ff00000;
  /** {@code |x| < 0.5}: the lanes of the first form, {@code x + x*w}. */
  static final int HX_HALF = 0x3fe00000;
  /** {@code |x| < 2^-27}: the lanes whose result is {@code x}. */
  static final int HX_TINY = 0x3e400000;
  /** {@code |x| > 0.975}, tested as {@code ix >= 0x3FEF3333}: the lanes of the root form. */
  static final int HX_SPLIT = 0x3FEF3333;

  /** The mask of {@code w = s; __LO(w) = 0}: the high word kept, the low word cleared. */
  static final long CLEAR_LOW_WORD = 0xFFFFFFFF00000000L;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_PIO2_LO = "asin.dropPio2Lo";
  static final String SPLIT_MOVED = "asin.splitMoved";
  static final String SKIP_LOW_CLEAR = "asin.skipLowClear";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /**
   * {@code pio2_lo} as {@code 0.0} in both forms that use it (sized in SKILLS.md, "What the asin
   * port taught").
   */
  private static final double PIO2_LO_USED = Faults.is(DROP_PIO2_LO) ? 0.0 : PIO2_LO;
  /** Moves line 101's {@code 0.975} word (sized in SKILLS.md, "What the asin port taught"). */
  private static final int HX_SPLIT_USED =
      Faults.is(SPLIT_MOVED) ? HX_SPLIT + 0x00100000 : HX_SPLIT;
  /**
   * The low-word clear skipped: the mask keeps every bit (sized in SKILLS.md, "What the asin port
   * taught").
   */
  private static final long CLEAR_LOW_WORD_USED = Faults.is(SKIP_LOW_CLEAR) ? -1L : CLEAR_LOW_WORD;

  private Asin() {}

  /**
   * asin with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an
   * inactive lane never takes the per-lane finish.
   */
  static DoubleVector asin(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 78-79: hx = __HI(x); ix = hx&0x7fffffff, below.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector ix = hx.and(0x7fffffff);
    // Lines 80, 85, 86 and 101: |x| < 1, |x| < 0.5, |x| < 2^-27 and |x| > 0.975.
    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_ONE).cast(species);
    VectorMask<Double> small = ix.compare(VectorOperators.LT, HX_HALF).cast(species);
    VectorMask<Double> tiny = ix.compare(VectorOperators.LT, HX_TINY).cast(species);
    VectorMask<Double> near = ix.compare(VectorOperators.GE, HX_SPLIT_USED).cast(species);
    VectorMask<Double> positive = hx.compare(VectorOperators.GT, 0).cast(species);

    // Lines 89 and 96-97: t = x*x below 0.5, and w = one-fabs(x); t = w*0.5 from it on. The
    // polynomials and the division then run on t in every lane (L13, H2: an operand blend).
    DoubleVector ax = x.lanewise(VectorOperators.ABS);
    DoubleVector one = DoubleVector.broadcast(species, ONE);
    DoubleVector t = one.sub(ax).mul(0.5).blend(x.mul(x), small);
    // Lines 90-91 and 98-99: p = t*(pS0+t*(pS1+...)), q = one+t*(qS1+t*(qS2+...)).
    DoubleVector p4 = t.mul(PS5).add(PS4);
    DoubleVector p3 = t.mul(p4).add(PS3);
    DoubleVector p2 = t.mul(p3).add(PS2);
    DoubleVector p1 = t.mul(p2).add(PS1);
    DoubleVector p = t.mul(t.mul(p1).add(PS0));
    DoubleVector q3 = t.mul(QS4).add(QS3);
    DoubleVector q2 = t.mul(q3).add(QS2);
    DoubleVector q1 = t.mul(q2).add(QS1);
    DoubleVector q = t.mul(q1).add(ONE);
    // Lines 92, 102 and 108: w = p/q, and r = p/q: one division, the same operands in all three.
    DoubleVector w = p.div(q);
    // Line 93: x+x*w, the first form.
    DoubleVector first = x.add(x.mul(w));

    // Line 100: s = sqrt(t).
    DoubleVector s = t.lanewise(VectorOperators.SQRT);
    // Lines 101-103: |x| > 0.975: pio2_hi-(2.0*(s+s*w)-pio2_lo).
    DoubleVector root = DoubleVector.broadcast(species, PIO2_HI)
        .sub(s.add(s.mul(w)).mul(2.0).sub(PIO2_LO_USED));
    // Lines 105-106: w = s; __LO(w) = 0, the C's own clear of the low word (an AND of the bits).
    DoubleVector wl = (DoubleVector) ((LongVector) s.viewAsIntegralLanes())
        .and(CLEAR_LOW_WORD_USED).viewAsFloatingLanes();
    // Line 107: c = (t-w*w)/(s+w), the second division.
    DoubleVector c = t.sub(wl.mul(wl)).div(s.add(wl));
    // Lines 109-111: p = 2.0*s*r-(pio2_lo-2.0*c); q = pio4_hi-2.0*w; t = pio4_hi-(p-q).
    DoubleVector pSplit = s.mul(2.0).mul(w)
        .sub(DoubleVector.broadcast(species, PIO2_LO_USED).sub(c.mul(2.0)));
    DoubleVector qSplit = DoubleVector.broadcast(species, PIO4_HI).sub(wl.mul(2.0));
    DoubleVector split = DoubleVector.broadcast(species, PIO4_HI).sub(pSplit.sub(qSplit));
    // Line 113: if (hx>0) return t; else return -t.
    DoubleVector second = split.blend(root, near);
    DoubleVector signed = second.neg().blend(second, positive);
    // Lines 86-87: |x| < 2^-27 returns x (huge+x>one holds for every such x).
    DoubleVector result = signed.blend(first, small).blend(x, tiny);

    if (live != null) {
      result = x.blend(result, live);
    }
    // |x| >= 1, infinities and NaN included, is finished per lane, written out here with no call
    // that takes a vector, as Sinh's finish is: lane and withLane are lane extract and insert,
    // which C2 intrinsifies for the fixed shapes, and the only call is StrictMath.asin on a
    // double. The first eight lanes have constant indices, which C2 intrinsifies at SPECIES_MAX
    // too, and the loop takes the lanes past eight. rare is and-ed with live, so an inactive lane
    // is never rewritten.
    VectorMask<Double> rare = common.not();
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.asin(x.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.asin(x.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.asin(x.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.asin(x.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.asin(x.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.asin(x.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.asin(x.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.asin(x.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.asin(x.lane(lane)));
      }
    }
    return result;
  }
}
