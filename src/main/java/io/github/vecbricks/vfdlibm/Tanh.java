/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's s_tanh.c (reference/fdlibm-5.3/s_tanh.c), which carries this
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
 * tanh over lanes, ported from fdlibm 5.3's {@code tanh} in {@code reference/fdlibm-5.3/s_tanh.c};
 * every lane is bit-identical to {@code StrictMath.tanh}.
 *
 * <p>The method, from the C: below {@code |x| = 2^-55} return {@code x*(one+x)}; below {@code |x|
 * = 1}, with {@code t = expm1(-two*|x|)}, {@code z = -t/(t+two)}; from 1 to 22, with {@code t =
 * expm1(two*|x|)}, {@code z = one - two/(t+two)}; from 22 up {@code z = one - tiny}, which is 1;
 * and return {@code z} with {@code x}'s sign, {@code -z} for a negative {@code x}. An infinity or a
 * NaN returns {@code one/x+one} or {@code one/x-one}. Every operation is the C's, in the C's order,
 * with no fused multiply-add and no reassociation, so no lemma of its own is needed beyond L13's
 * blend argument; what it rests on through {@link Expm1#expm1Core}, L10 and L11 and through them
 * exp's L1, L2 and L5, is in {@code PROOFS.md}'s tanh section, with the hinge that every lane whose
 * {@code expm1} is kept lies outside {@link Expm1#expm1Rare}.
 *
 * <p>The {@code expm1} is {@link Expm1#expm1Core}, the only entry into {@code Expm1} this class
 * uses, of one argument per lane: {@code two*|x|} from {@code |x| = 1} on and {@code -two*|x|}
 * below it, picked by the C's test before the call. Its result is {@code StrictMath.expm1}'s on
 * every lane the result keeps: those arguments lie in {@code (-2, -2^-54]} and {@code [2, 44)},
 * none of expm1's early returns. A lane below {@code 2^-55} or from 22 up gets a value the core
 * computes and nobody keeps.
 *
 * <p>The two forms share one division: its numerator is {@code -t} below {@code |x| = 1} and
 * {@code two} from it on, over {@code t+two}, and the lanes from 1 on then take {@code one - q};
 * both are blended by the high word (L13). The lanes from 22 up, the infinities' and NaNs' aside,
 * take {@code one - tiny}, and the sign is applied as the C's last line does, to every lane but the
 * tiny ones, which return before it.
 *
 * <p>The C's first branch, an infinity or a NaN ({@code ix >= 0x7ff00000}), is the one rare path,
 * finished per lane with {@code StrictMath.tanh}, as sinh finishes its own non-finite lanes. So no
 * kept lane is a {@code NaN} made in the vector, and L13's exception for a {@code NaN}'s payload
 * is not needed. Every finite lane, the saturated ones included, is computed in the vector.
 *
 * <p>The C's {@code int} words stay in int lanes, as in {@code Sinh}: {@code jx} is the high
 * word, signed, with the double vector's lane count at half its bits, and every test is the C's
 * compare of {@code ix = jx&0x7fffffff}, or of {@code jx} against 0 for the sign. C2 stops
 * inlining once the compiled graph passes {@code NodeCountInliningCutoff}, so every call this body
 * makes comes first, {@code Lanes.intSpecies} and {@code Lanes.hiInt}, then {@link
 * Expm1#expm1Core} as soon as its argument exists; after it there are only Vector API operations
 * and the per-lane finish written out in the body.
 */
final class Tanh {

  // The constants of s_tanh.c, line 41, as its source writes them; TanhTest checks the words.
  static final double ONE = 1.0;
  static final double TWO = 2.0;
  static final double TINY = 1.0e-300;
  /** Line 79's {@code one - tiny}, the C's subtraction, done once: it rounds to 1. */
  static final double SATURATED = ONE - TINY;

  // The high words s_tanh.c compares ix against.
  /** {@code ix >= 0x7ff00000}: an infinity or a NaN, the lanes finished per lane (line 61). */
  static final int HX_NON_FINITE = 0x7ff00000;
  /** {@code |x| < 22}: the lanes of the expm1 forms and the tiny return (line 67). */
  static final int HX_LIMIT = 0x40360000;
  /** {@code |x| < 2^-55}: the lanes whose result is {@code x*(one+x)} (line 68). */
  static final int HX_TINY = 0x3c800000;
  /** {@code |x| >= 1}: the lanes of the second form, {@code one - two/(t+two)} (line 70). */
  static final int HX_ONE = 0x3ff00000;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String SPLIT_MOVED = "tanh.splitMoved";
  static final String TINY_MOVED = "tanh.tinyMoved";
  static final String THRESHOLD_MOVED = "tanh.thresholdMoved";
  static final String TWO_ONE_ULP_OFF = "tanh.twoOneUlpOff";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /**
   * Moves line 70's split from {@code |x| = 1} to {@code |x| = 2}: the lanes of {@code [1, 2)}
   * take the first form, {@code -t/(t+two)} of {@code t = expm1(-two*|x|)}, where the C takes the
   * second; sized on 2^22 draws of {@code [1, 2)} of both signs, 2476159 changed. The word moved
   * by one changed 1 of them.
   */
  private static final int HX_ONE_USED = Faults.is(SPLIT_MOVED) ? 0x40000000 : HX_ONE;
  /**
   * Moves line 68's {@code 2^-55} up to {@code 2^-28}: the lanes of {@code [2^-55, 2^-28)} return
   * {@code x*(one+x)}, which is not {@code x} from {@code 2^-53} up, where tanh rounds to {@code
   * x} (sized on 2^22 draws spread over the binades {@code 2^-60} to {@code 2^-21}, both signs:
   * 2694129 changed). The word moved one down changed none of them.
   */
  private static final int HX_TINY_USED = Faults.is(TINY_MOVED) ? 0x3e300000 : HX_TINY;
  /**
   * Moves line 67's {@code 22} down to {@code 16}: the lanes of {@code [16, 22)} return {@code
   * one - tiny} where the C computes {@code one - two/(t+two)}, which is below 1 up to about
   * 19.06 (sized on 2^22 draws of {@code [16, 22)}, both signs: 2141980 changed). Moved to 20 it
   * changed none: from about 19.06 on the second form rounds to 1 too.
   */
  private static final int HX_LIMIT_USED = Faults.is(THRESHOLD_MOVED) ? 0x40300000 : HX_LIMIT;
  /**
   * Line 72's numerator {@code two} one ulp up: the lanes from 1 up to about 19 change where
   * {@code two/(t+two)} then rounds differently (sized on 2^22 draws of {@code (-30, 30)}: 35443
   * changed).
   */
  private static final double TWO_USED = Faults.is(TWO_ONE_ULP_OFF) ? Math.nextUp(TWO) : TWO;

  private Tanh() {}

  /**
   * tanh with the non-finite lanes finished per lane. {@code live} is null for every lane;
   * otherwise only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and
   * an inactive lane never takes the per-lane finish.
   */
  static DoubleVector tanh(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 57-58: jx = __HI(x); ix = jx&0x7fffffff.
    IntVector jx = Lanes.hiInt(x, intSpecies);
    IntVector ix = jx.and(0x7fffffff);
    // Line 70: |x| >= 1.
    VectorMask<Double> atLeastOne = ix.compare(VectorOperators.GE, HX_ONE_USED).cast(species);

    // Lines 71 and 74: expm1(two*fabs(x)) from |x| = 1 on, expm1(-two*fabs(x)) below it, one
    // call on the argument the same test picks, made as soon as that argument exists (its own
    // calls into Lanes come first in its body).
    DoubleVector ax = x.lanewise(VectorOperators.ABS);
    DoubleVector t = Expm1.expm1Core(ax.mul(-TWO).blend(ax.mul(TWO), atLeastOne));

    // Lines 67, 68 and 81: |x| < 22, |x| < 2^-55 and jx < 0.
    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_LIMIT_USED).cast(species);
    VectorMask<Double> tiny = ix.compare(VectorOperators.LT, HX_TINY_USED).cast(species);
    VectorMask<Double> negative = jx.compare(VectorOperators.LT, 0).cast(species);

    // Lines 72 and 75: one - two/(t+two) from |x| = 1 on, -t/(t+two) below it, through one
    // division: the numerator is two or -t, the denominator t+two in both.
    DoubleVector q = t.neg().blend(TWO_USED, atLeastOne).div(t.add(TWO));
    DoubleVector z = q.blend(DoubleVector.broadcast(species, ONE).sub(q), atLeastOne);
    // Line 79: from 22 up, z = one - tiny.
    z = z.blend(SATURATED, common.not());
    // Line 81: return (jx>=0)? z: -z.
    DoubleVector result = z.blend(z.neg(), negative);
    // Lines 68-69: |x| < 2^-55 returns x*(one+x), before line 81's sign.
    result = result.blend(x.mul(DoubleVector.broadcast(species, ONE).add(x)), tiny);

    if (live != null) {
      result = x.blend(result, live);
    }
    // Lines 61-63, the infinities and NaNs, are finished per lane, written out here with no call
    // that takes a vector, as Sinh's finish is: lane and withLane are lane extract and insert,
    // which C2 intrinsifies for the fixed shapes, and the only call is StrictMath.tanh on a
    // double. The first eight lanes have constant indices, which C2 intrinsifies at SPECIES_MAX
    // too, and the loop takes the lanes past eight. rare is and-ed with live, so an inactive
    // lane is never rewritten.
    VectorMask<Double> rare = ix.compare(VectorOperators.GE, HX_NON_FINITE).cast(species);
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.tanh(x.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.tanh(x.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.tanh(x.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.tanh(x.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.tanh(x.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.tanh(x.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.tanh(x.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.tanh(x.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.tanh(x.lane(lane)));
      }
    }
    return result;
  }
}
