/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_sinh.c (reference/fdlibm-5.3/e_sinh.c), which carries this
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
 * sinh over lanes, ported from fdlibm 5.3's {@code __ieee754_sinh} in {@code
 * reference/fdlibm-5.3/e_sinh.c}; every lane is bit-identical to {@code StrictMath.sinh}.
 *
 * <p>The method, from the C: for {@code |x| < 22}, with {@code t = expm1(|x|)}, return {@code
 * h*(2.0*t - t*t/(t+1))} below {@code |x| = 1} and {@code h*(t + t/(t+1))} from it on, {@code h}
 * being {@code 0.5} with {@code x}'s sign, so that the sign is applied by the last product; below
 * {@code 2^-28} return {@code x}. From {@code 22} up the C needs {@code exp}: {@code
 * h*exp(|x|)} up to {@code o_threshold}, and {@code exp(|x|/2)} squared with {@code h} until the
 * overflow. Every operation is the C's, in the C's order, with no fused multiply-add and no
 * reassociation, so no lemma of its own is needed beyond L13's blend argument; what it rests on
 * through {@link Expm1#expm1Core}, L10 and L11 and through them exp's L1, L2 and L5, is in
 * {@code PROOFS.md}'s sinh section (L13 is in its section "Blends").
 *
 * <p>The {@code expm1} is {@link Expm1#expm1Core} of {@code |x|}, the only entry into {@code
 * Expm1} this class uses: e_expm1's common path in every lane, which is the C's result for every
 * lane below 22 but the tiny ones ({@code |x| < 2^-54}), whose result here is {@code x} from the
 * C's own test. A lane of {@code |x| >= 22} gets a value the core computes and nobody keeps.
 *
 * <p>Those lanes, the C's second, third and fourth ranges, infinities and NaN, are the one rare
 * path, and the arm finishes them per lane with {@code StrictMath.sinh}. Their exp is not
 * computed in the vector: {@code Exp.expCore} beside {@code Expm1}'s body would be two port
 * bodies in one graph, which C2 refuses to inline ({@code SKILLS.md}). A vector holds one such
 * lane only when its input is 22 or more in magnitude, an infinity or a NaN. The inputs of
 * Varka's baseline benchmark and of Spark's unit tests never reach 22 (the survey on issue #32);
 * Varka's bit-identity draws do, and are finished per lane as any other input.
 *
 * <p>The sign. {@code h} is {@code 0.5} with {@code x}'s sign bit, as in exp's L5, so {@code
 * -0.0} and every negative lane get {@code -0.5}; the lanes below {@code 2^-28} return {@code x}
 * itself, signed zero and subnormals included.
 *
 * <p>The two forms of {@code t} share one division: its numerator is {@code t*t} below {@code
 * |x| = 1} and {@code t} from it on, over {@code t+1}, and each lane then does its own form's
 * operations, {@code 2.0*t - q} or {@code t + q}, before the product with {@code h}; both forms
 * are computed and blended by the high word (L13).
 *
 * <p>The C's {@code int} words stay in int lanes: {@code jx} is the high word, signed, with the
 * double vector's lane count at half its bits, and every test is the C's compare of {@code ix =
 * jx&0x7fffffff}. C2 stops inlining once the compiled graph passes {@code
 * NodeCountInliningCutoff}, so every call this body makes comes first, {@code Lanes.intSpecies}
 * and {@code Lanes.hiInt}, then {@link Expm1#expm1Core} as soon as its argument exists; after it
 * there are only Vector API operations and the per-lane finish written out in the body.
 */
final class Sinh {

  // The constants of e_sinh.c, as its source writes them; SinhTest checks the words.
  static final double ONE = 1.0;

  // The high words e_sinh.c compares |x| against.
  /** {@code |x| < 22}: the lanes the expm1 form covers. */
  static final int HX_LIMIT = 0x40360000;
  /** {@code |x| < 2^-28}: the lanes whose result is {@code x}. */
  static final int HX_TINY = 0x3e300000;
  /** {@code |x| < 1}: the lanes of the first form, {@code h*(2.0*t-t*t/(t+one))}. */
  static final int HX_ONE = 0x3ff00000;

  /** The raw bits of 0.5, which {@code h} takes the sign of {@code x} on. */
  static final long HALF_BITS = 0x3fe00000_00000000L;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String SPLIT_MOVED = "sinh.splitMoved";
  static final String HALF_ONE_ULP_OFF = "sinh.halfOneUlpOff";
  static final String THRESHOLD_MOVED = "sinh.thresholdMoved";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /**
   * Moves line 65's split from {@code |x| = 1} to {@code |x| = 2}: the lanes of {@code [1, 2)}
   * take the first form where the C takes the second, which gives the same value to within an
   * ulp, so about a quarter of them round differently. The word moved by one only shows in the
   * one word's lanes: sized on 2^22 draws of {@code [-30, 30]}, the move to 2 changed 32877 and
   * the move by one word none.
   */
  private static final int HX_ONE_USED =
      Faults.is(SPLIT_MOVED) ? 0x40000000 : HX_ONE;
  /**
   * {@code h} one ulp up, 0.5 as {@code 0x3fe0000000000001}: every lane of the vector range is
   * then wrong (sized the same way: 3075309 of 4194304 draws changed).
   */
  private static final long HALF_BITS_USED =
      Faults.is(HALF_ONE_ULP_OFF) ? HALF_BITS + 1 : HALF_BITS;
  /**
   * Moves line 61's {@code 22} to {@code 32}: the lanes of {@code [22, 32)} take the expm1 form
   * where the C takes {@code h*exp(|x|)}, which rounds differently in about 1.5% of them (63885
   * of 2^22 draws of {@code [22, 32)}). Moving it by one word changes nothing: the two forms
   * agree there to within an ulp, and a word holds few doubles.
   */
  private static final int HX_LIMIT_USED =
      Faults.is(THRESHOLD_MOVED) ? 0x40400000 : HX_LIMIT;

  private Sinh() {}

  /**
   * sinh with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an
   * inactive lane never takes the per-lane finish.
   */
  static DoubleVector sinh(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 52-53: jx = __HI(x); ix = jx&0x7fffffff, below.
    IntVector jx = Lanes.hiInt(x, intSpecies);

    // Line 64's expm1(fabs(x)): Expm1's common path, called as soon as its argument exists
    // (its own calls into Lanes come first in its body).
    DoubleVector t = Expm1.expm1Core(x.lanewise(VectorOperators.ABS));

    IntVector ix = jx.and(0x7fffffff);
    // Lines 61, 62 and 65: |x| < 22, |x| < 2^-28 and |x| < 1.
    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_LIMIT_USED).cast(species);
    VectorMask<Double> tiny = ix.compare(VectorOperators.LT, HX_TINY).cast(species);
    VectorMask<Double> belowOne = ix.compare(VectorOperators.LT, HX_ONE_USED).cast(species);

    // Lines 58-59: h = 0.5; if (jx<0) h = -h: 0.5 with x's sign bit (exp's L5).
    LongVector bits = (LongVector) x.viewAsIntegralLanes();
    DoubleVector h = (DoubleVector) bits.and(Long.MIN_VALUE).or(HALF_BITS_USED)
        .viewAsFloatingLanes();

    // Lines 65-66: h*(2.0*t-t*t/(t+one)) below |x| = 1, h*(t+t/(t+one)) from it on, through one
    // division: the numerator is t*t or t, the denominator t+one in both.
    DoubleVector q = t.blend(t.mul(t), belowOne).div(t.add(ONE));
    DoubleVector first = t.mul(2.0).sub(q);
    DoubleVector second = t.add(q);
    DoubleVector result = h.mul(second.blend(first, belowOne));
    // Lines 62-63: |x| < 2^-28 returns x (shuge+x>one holds for every such x).
    result = result.blend(x, tiny);

    if (live != null) {
      result = x.blend(result, live);
    }
    // Everything from 22 up, infinities and NaN included, is finished per lane, written out here
    // with no call that takes a vector, as Log's and Expm1's finishes are: lane and withLane are
    // lane extract and insert, which C2 intrinsifies for the fixed shapes, and the only call is
    // StrictMath.sinh on a double. rare is and-ed with live, so an inactive lane is never
    // rewritten.
    VectorMask<Double> rare = common.not();
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.sinh(x.lane(lane)));
      }
    }
    return result;
  }
}
