/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's s_atan.c (reference/fdlibm-5.3/s_atan.c), which carries this
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
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * atan over lanes, ported from fdlibm 5.3's {@code atan} in {@code
 * reference/fdlibm-5.3/s_atan.c}; every lane is bit-identical to {@code StrictMath.atan}.
 *
 * <p>The method, from the C: below {@code |x| = 7/16} there is no reduction (and below {@code
 * 2^-29} the result is {@code x}); from there up {@code |x|} goes to {@code (2.0*x-1)/(2.0+x)}
 * below 11/16, {@code (x-1)/(x+1)} below 19/16, {@code (x-1.5)/(1+1.5*x)} below 39/16 and {@code
 * -1/x} above, each with the pair {@code atanhi[id]}, {@code atanlo[id]}. One polynomial in the
 * reduced value serves every range. The result is {@code x - x*(s1+s2)} without a reduction, and
 * {@code atanhi[id] - ((x*(s1+s2) - atanlo[id]) - x)} with one, negated for a negative argument.
 * Every operation is the C's, in the C's order, with no fused multiply-add and no reassociation,
 * so no lemma of its own is needed beyond L13's blend argument ({@code PROOFS.md}'s section
 * "Blends"); the atan section says what it rests on.
 *
 * <p>The four reductions share one division. A lane takes exactly one of the C's branches, picked
 * by the C's own compare of {@code ix}; its numerator and its denominator are that branch's
 * operands, blended by range, so the division is the C's division of the C's operands. The values
 * the other branches' operations take in a lane are computed and never kept, and a lane below
 * {@code 7/16}, which the C does not divide, keeps {@code x} itself and drops the quotient.
 *
 * <p>The one rare path is {@code |x| >= 2^66}, the infinities and the NaNs, whose C results are
 * a constant with {@code x}'s sign or {@code x+x}. The arm finishes those lanes per lane with
 * {@code StrictMath.atan}, which is the C, so no computation of the port is in them. A vector
 * holds one only when its input is that large or not a number. This is the finish arm, which is
 * the default by issue #30's tie-break rule, and the only one: no timing of a second arm exists.
 *
 * <p>The C's {@code int} words stay in int lanes: {@code hx} is the high word, signed, with the
 * double vector's lane count at half its bits, and every test is the C's compare of {@code ix =
 * hx&0x7fffffff}. C2 stops inlining once the compiled graph passes {@code
 * NodeCountInliningCutoff}, so every call this body makes comes first, {@code Lanes.intSpecies}
 * and {@code Lanes.hiInt}; after them there are only Vector API operations and the per-lane
 * finish written out in the body.
 */
final class Atan {

  // The constants of s_atan.c, as its source writes them; AtanTest checks the words.
  static final double ONE = 1.0;
  // Scalar constants, which C2 folds into the body; the arrays below hold the same doubles for
  // the tests and are not read by the body.
  private static final double HI0 = 4.63647609000806093515e-01;
  private static final double HI1 = 7.85398163397448278999e-01;
  private static final double HI2 = 9.82793723247329054082e-01;
  private static final double HI3 = 1.57079632679489655800e+00;
  private static final double LO0 = 2.26987774529616870924e-17;
  private static final double LO1 = 3.06161699786838301793e-17;
  private static final double LO2 = 1.39033110312309984516e-17;
  private static final double LO3 = 6.12323399573676603587e-17;
  private static final double AT0 = 3.33333333333329318027e-01;
  private static final double AT1 = -1.99999999998764832476e-01;
  private static final double AT2 = 1.42857142725034663711e-01;
  private static final double AT3 = -1.11111104054623557880e-01;
  private static final double AT4 = 9.09088713343650656196e-02;
  private static final double AT5 = -7.69187620504482999495e-02;
  private static final double AT6 = 6.66107313738753120669e-02;
  private static final double AT7 = -5.83357013379057348645e-02;
  private static final double AT8 = 4.97687799461593236017e-02;
  private static final double AT9 = -3.65315727442169155270e-02;
  private static final double AT10 = 1.62858201153657823623e-02;
  static final double[] ATAN_HI = {HI0, HI1, HI2, HI3};
  static final double[] ATAN_LO = {LO0, LO1, LO2, LO3};
  static final double[] AT = {AT0, AT1, AT2, AT3, AT4, AT5, AT6, AT7, AT8, AT9, AT10};

  // The high words s_atan.c compares |x| against.
  /** {@code |x| >= 2^66}: the lanes the C answers with a constant or {@code x+x} (line 97). */
  static final int HX_BIG = 0x44100000;
  /** {@code |x| < 2^-29}: the lanes whose result is {@code x} (line 104). */
  static final int HX_TINY = 0x3e200000;
  /** {@code |x| < 0.4375}: the lanes with no reduction, {@code id = -1} (line 103). */
  static final int HX_NO_REDUCTION = 0x3fdc0000;
  /** {@code |x| < 11/16}: the lanes of {@code id = 0} (line 111). */
  static final int HX_ID0 = 0x3fe60000;
  /** {@code |x| < 19/16}: the lanes of {@code id = 0} and {@code id = 1} (line 110). */
  static final int HX_ID1 = 0x3ff30000;
  /** {@code |x| < 39/16}: the lanes up to {@code id = 2} (line 116). */
  static final int HX_ID2 = 0x40038000;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_LO = "atan.dropLo";
  static final String SPLIT_MOVED = "atan.splitMoved";
  static final String HI_ONE_ULP_OFF = "atan.hiOneUlpOff";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  private static final boolean DROPPING_LO = Faults.is(DROP_LO);
  private static final int HX_ID1_USED = Faults.is(SPLIT_MOVED) ? 0x40000000 : HX_ID1;
  private static final double ATAN_HI_1_USED =
      Faults.is(HI_ONE_ULP_OFF) ? Math.nextUp(HI1) : HI1;

  private Atan() {}

  /**
   * atan with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an
   * inactive lane never takes the per-lane finish.
   */
  static DoubleVector atan(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 94-95: hx = __HI(x); ix = hx&0x7fffffff.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector ix = hx.and(0x7fffffff);

    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_BIG).cast(species);
    VectorMask<Double> tiny = ix.compare(VectorOperators.LT, HX_TINY).cast(species);
    VectorMask<Double> noReduction =
        ix.compare(VectorOperators.LT, HX_NO_REDUCTION).cast(species);
    VectorMask<Double> id0 = ix.compare(VectorOperators.LT, HX_ID0).cast(species);
    VectorMask<Double> idUpTo1 = ix.compare(VectorOperators.LT, HX_ID1_USED).cast(species);
    VectorMask<Double> idUpTo2 = ix.compare(VectorOperators.LT, HX_ID2).cast(species);
    VectorMask<Double> negative = hx.compare(VectorOperators.LT, 0).cast(species);

    // Line 109: x = fabs(x), for the reduced ranges.
    DoubleVector ax = x.lanewise(VectorOperators.ABS);

    // Lines 112-121: the four reductions through one division. Each lane's numerator and
    // denominator are its own range's, blended from the last range down to the first: id = 3
    // (-1.0/x), then 2, 1 and 0 override it in their lanes.
    // L13 (PROOFS.md, "Blends"): H1 the compares above, nested as the C's if chain is; H2 the
    // operands of each range, then one division; H3 and H4 as in Sinh.
    DoubleVector one = DoubleVector.broadcast(species, ONE);
    DoubleVector numerator = DoubleVector.broadcast(species, -1.0);
    DoubleVector denominator = ax;
    numerator = numerator.blend(ax.sub(1.5), idUpTo2);
    denominator = denominator.blend(one.add(ax.mul(1.5)), idUpTo2);
    numerator = numerator.blend(ax.sub(ONE), idUpTo1);
    denominator = denominator.blend(ax.add(ONE), idUpTo1);
    numerator = numerator.blend(ax.mul(2.0).sub(ONE), id0);
    denominator = denominator.blend(ax.add(2.0), id0);
    DoubleVector quotient = numerator.div(denominator);
    // Below 7/16 the C does not reduce: id = -1 and x is x itself, sign included.
    DoubleVector reduced = quotient.blend(x, noReduction);

    // Lines 124-128: z = x*x; w = z*z; the odd and the even polynomial, in the C's order.
    DoubleVector z = reduced.mul(reduced);
    DoubleVector w = z.mul(z);
    // s1 = z*(aT0+w*(aT2+w*(aT4+w*(aT6+w*(aT8+w*aT10))))), innermost first.
    DoubleVector a8 = w.mul(AT10).add(AT8);
    DoubleVector a6 = w.mul(a8).add(AT6);
    DoubleVector a4 = w.mul(a6).add(AT4);
    DoubleVector a2 = w.mul(a4).add(AT2);
    DoubleVector s1 = z.mul(w.mul(a2).add(AT0));
    // s2 = w*(aT1+w*(aT3+w*(aT5+w*(aT7+w*aT9)))).
    DoubleVector b7 = w.mul(AT9).add(AT7);
    DoubleVector b5 = w.mul(b7).add(AT5);
    DoubleVector b3 = w.mul(b5).add(AT3);
    DoubleVector s2 = w.mul(w.mul(b3).add(AT1));
    DoubleVector sum = s1.add(s2);
    DoubleVector product = reduced.mul(sum);

    // Line 129: id < 0 returns x - x*(s1+s2), with no sign step (it is odd).
    DoubleVector plain = reduced.sub(product);

    // Lines 131-132: atanhi[id] - ((x*(s1+s2) - atanlo[id]) - x), negated for hx < 0.
    DoubleVector hi = DoubleVector.broadcast(species, HI3);
    DoubleVector lo = DoubleVector.broadcast(species, LO3);
    hi = hi.blend(DoubleVector.broadcast(species, HI2), idUpTo2);
    lo = lo.blend(DoubleVector.broadcast(species, LO2), idUpTo2);
    hi = hi.blend(DoubleVector.broadcast(species, ATAN_HI_1_USED), idUpTo1);
    lo = lo.blend(DoubleVector.broadcast(species, LO1), idUpTo1);
    hi = hi.blend(DoubleVector.broadcast(species, HI0), id0);
    lo = lo.blend(DoubleVector.broadcast(species, LO0), id0);
    if (DROPPING_LO) {
      lo = DoubleVector.zero(species);
    }
    DoubleVector result = hi.sub(product.sub(lo).sub(reduced));
    result = result.blend(result.neg(), negative);
    result = result.blend(plain, noReduction);
    // Lines 104-105: |x| < 2^-29 returns x (huge+x>one holds for every such x).
    result = result.blend(x, tiny);

    if (live != null) {
      result = x.blend(result, live);
    }
    // |x| >= 2^66, infinities and NaN are finished per lane, written out here with no call that
    // takes a vector, as Sinh's finish is: lane and withLane are lane extract and insert, which
    // C2 intrinsifies for the fixed shapes, and the only call is StrictMath.atan on a double.
    // rare is and-ed with live, so an inactive lane is never rewritten.
    VectorMask<Double> rare = common.not();
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.atan(x.lane(lane)));
      }
    }
    return result;
  }
}
