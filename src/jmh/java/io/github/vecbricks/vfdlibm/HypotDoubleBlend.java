/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_hypot.c (reference/fdlibm-5.3/e_hypot.c), which carries this
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
 * hypot over lanes, ported from fdlibm 5.3's {@code __ieee754_hypot} in {@code
 * reference/fdlibm-5.3/e_hypot.c}; every lane is bit-identical to {@code StrictMath.hypot}, except
 * that a lane of two NaNs with different bits is one of the two with the sign cleared and the quiet
 * bit set, as {@code PROOFS.md}'s "The contract of a function of two arguments" says. A lane that
 * is not live keeps {@code x}'s bits.
 *
 * <p>The method, from the C, by {@code ha} and {@code hb}, the high words of {@code |x|} and
 * {@code |y|}: the arguments are swapped where {@code |y| > |x|} and their signs cleared (lines
 * 58-62, ordered as below); where {@code ha-hb > 0x3c00000} the result is {@code a+b} (line
 * 63); above {@code 2^500} both are scaled by {@code 2^-600} through their words (lines 65-76),
 * and where {@code b < 2^-500} by {@code 2^600} through their words (lines 86-90), or, where
 * {@code b} is subnormal or zero, a zero {@code b} returns {@code a} (line 79) and any other is
 * scaled by {@code 2^1022} through a multiplication (lines 80-84); then {@code
 * sqrt(t1*t1-(b*(-b)-t2*(a+t1)))} where {@code w = a-b > b} and {@code
 * sqrt(t1*y1-(w*(-w)-(t1*y2+t2*b)))} otherwise, over operands split at the high word (lines
 * 94-109), times {@code 2^k} where {@code k != 0} (lines 110-114).
 *
 * <p>The one departure from the C is on purpose: line 60 swaps where {@code hb > ha}, by the high
 * words alone, so with equal high words {@code a} is {@code x} even where {@code |y|} is the
 * larger, and lines 101-108 then split the two at their low words the other way round, which can
 * change the last bit ({@code hypot(0x5f201b45bbc5eac3, 0x5f201b45fffffffe)} is {@code
 * 0x5f26c7304042d062} by the C as written, {@code ...d063} with the arguments the other way).
 * {@code StrictMath.hypot} gave the C's result with the larger magnitude first, in either order,
 * on every pair tried: it has been commutative since JDK 9, whose JDK-7130085 ported fdlibm's
 * hypot to Java (JDK 8 and earlier ran the C, with line 60's order). So the port swaps where
 * {@code |y| > |x|}, comparing the sign-cleared bits as longs: where the high words differ that
 * is line 60's swap, and where they are equal it puts the larger first; every line after it is
 * the C's ({@code PROOFS.md}, hypot: L21, which separates what is proven from what is observed).
 * {@code ha} is the larger high word either way, so the rare lanes are line 60's.
 *
 * <p>The words stay apart from the values, as in the C. The 2^1022 scaling multiplies {@code a}
 * and {@code b} and leaves {@code ha} and {@code hb} as they were, and lines 97, 103 and 106 read
 * those stale words: the port builds {@code t1}, {@code y1} and line 106's {@code t1} from the
 * words, so it reads them stale too ({@code PROOFS.md}, hypot: L16, a counter-example and not a
 * fold). Every operation is the C's, in the C's order, with no fused multiply-add and no
 * reassociation; the branches are blends ({@code PROOFS.md}, L13), and the two square roots are
 * one {@code VectorOperators.SQRT} of the radicand each lane's branch computes.
 *
 * <p>The rare path: {@code ha >= 0x7ff00000}, an infinity or a NaN in either argument (lines
 * 66-70, and the lanes of line 63 whose {@code a} is not finite), finished per lane with {@code
 * StrictMath.hypot}, which is the C on those lanes. Every finite lane is computed in the vector.
 * One arm: the finish. The C's {@code int} words are int lanes of half the double vector's size,
 * as in atan2; every call this body makes ({@code Lanes.intSpecies}, and {@code Lanes.hiInt} on
 * the swapped values) comes before the scalings and the medium path, with only the views, one
 * compare and two blends ahead of it, and the high-word writes are written out, since C2 stops
 * inlining once the graph is large (NodeCountInliningCutoff) and a refused call boxes its vectors.
 */
final class HypotDoubleBlend {

  /** The ratio word of line 63: {@code ha-hb > 0x3c00000}, a ratio above {@code 2^60}. */
  static final int RATIO_WORD = 0x3c00000;
  /** Line 65: {@code ha > 0x5f300000}, {@code a > 2^500}. */
  static final int HA_BIG = 0x5f300000;
  /** Line 66: {@code ha >= 0x7ff00000}, an infinity or a NaN: the lanes finished per lane. */
  static final int HA_INF = 0x7ff00000;
  /** Line 77: {@code hb < 0x20b00000}, {@code b < 2^-500}. */
  static final int HB_SMALL = 0x20b00000;
  /** Line 78: {@code hb <= 0x000fffff}, a subnormal {@code b} or zero. */
  static final int HB_SUBNORMAL = 0x000fffff;
  /** Lines 73 and 86-87: the words' scaling by {@code 2^-600} and {@code 2^600}. */
  static final int SCALE_WORD = 0x25800000;
  /** Line 106: {@code ha+0x00100000}, the word of {@code 2a}'s split. */
  static final int TWICE_WORD = 0x00100000;
  /** Line 81: {@code __HI(t1) = 0x7fd00000}, {@code t1 = 2^1022}. */
  static final double TWO1022 = Lanes.fromHi(0x7fd00000);
  /** The high word of {@code 1.0}, which line 112 adds {@code k<<20} to. */
  static final int ONE_WORD = 0x3ff00000;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_EARLY_RETURN = "hypot.dropEarlyReturn";
  static final String REFRESHED_WORDS = "hypot.refreshedWords";
  static final String DROP_T2 = "hypot.dropT2";
  static final String DROP_Y2 = "hypot.dropY2";
  static final String HIGH_WORD_ORDER = "hypot.highWordOrder";
  // Read once into constants, so the body holds no call and an unplanted fault folds away. Each
  // was sized on draws before it was wired (SKILLS.md, "What the hypot port taught").
  /** Line 63's return of {@code a+b} dropped. */
  private static final boolean DROPPING_EARLY_RETURN = Faults.is(DROP_EARLY_RETURN);
  /** Lines 80-84 with {@code ha} and {@code hb} read again from the scaled values (L16). */
  private static final boolean REFRESHING_WORDS = Faults.is(REFRESHED_WORDS);
  /** Line 99's {@code t2*(a+t1)} dropped. */
  private static final boolean DROPPING_T2 = Faults.is(DROP_T2);
  /** Line 108's {@code t1*y2} dropped. */
  private static final boolean DROPPING_Y2 = Faults.is(DROP_Y2);
  /** Line 60 as written: the arguments ordered by their high words alone (L21). */
  private static final boolean ORDERING_BY_HIGH_WORDS = Faults.is(HIGH_WORD_ORDER);

  private HypotDoubleBlend() {}

  /**
   * hypot with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an inactive
   * lane never takes the per-lane finish.
   */
  static DoubleVector hypot(DoubleVector x, DoubleVector y, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 58-62, ordered by magnitude and not by line 60's high words alone (PROOFS.md, L21):
    // a is the larger of |x| and |y| by their sign-cleared bits, compared as longs, b the other,
    // and a lane of equal magnitudes keeps a=x, b=y. Where the high words differ this is line
    // 60's swap; where they are equal it puts the larger first, as StrictMath.hypot does.
    // Clearing the sign bit is lines 61-62's __HI(a) = ha, __HI(b) = hb.
    LongVector xBits = ((LongVector) x.viewAsIntegralLanes()).and(Long.MAX_VALUE);
    LongVector yBits = ((LongVector) y.viewAsIntegralLanes()).and(Long.MAX_VALUE);
    VectorSpecies<Long> longSpecies = xBits.species();
    VectorMask<Long> swapL = yBits.compare(VectorOperators.GT, xBits);
    // Diagnostic: main's words from the swapped values, but the swap blends the doubles.
    VectorMask<Double> swap = swapL.cast(species);
    LongVector aBits = ((LongVector) x.blend(y, swap).viewAsIntegralLanes()).and(Long.MAX_VALUE);
    LongVector bBits = ((LongVector) y.blend(x, swap).viewAsIntegralLanes()).and(Long.MAX_VALUE);
    DoubleVector a1 = (DoubleVector) aBits.viewAsFloatingLanes();
    DoubleVector b1 = (DoubleVector) bBits.viewAsFloatingLanes();
    IntVector ha = Lanes.hiInt(a1, intSpecies);
    IntVector hb = Lanes.hiInt(b1, intSpecies);

    // Line 63: (ha-hb) > 0x3c00000, the ratio above 2^60: return a+b.
    VectorMask<Double> ratio = ha.sub(hb).compare(VectorOperators.GT, RATIO_WORD).cast(species);

    // Lines 65 and 73-75: ha > 0x5f300000 takes both words down by 0x25800000 (2^-600), k = 600.
    // (Line 66's lanes, ha >= 0x7ff00000, are finished per lane below; what they compute here is
    // never read.) Each __HI(v) = word is written out: the word widened by I2L, whose sign
    // extension the shift drops, over the low word kept.
    VectorMask<Integer> bigI = ha.compare(VectorOperators.GT, HA_BIG);
    IntVector ha2 = ha.blend(ha.sub(SCALE_WORD), bigI);
    IntVector hb2 = hb.blend(hb.sub(SCALE_WORD), bigI);
    IntVector k = IntVector.zero(intSpecies).blend(IntVector.broadcast(intSpecies, 600), bigI);
    VectorMask<Long> bigL = bigI.cast(longSpecies);
    LongVector ha2Wide = (LongVector) ha2.convertShape(VectorOperators.I2L, longSpecies, 0);
    LongVector hb2Wide = (LongVector) hb2.convertShape(VectorOperators.I2L, longSpecies, 0);
    LongVector a2Bits = aBits.blend(
        ha2Wide.lanewise(VectorOperators.LSHL, 32).or(aBits.and(Lanes.LOW_WORD)), bigL);
    LongVector b2Bits = bBits.blend(
        hb2Wide.lanewise(VectorOperators.LSHL, 32).or(bBits.and(Lanes.LOW_WORD)), bigL);
    DoubleVector a2 = (DoubleVector) a2Bits.viewAsFloatingLanes();
    DoubleVector b2 = (DoubleVector) b2Bits.viewAsFloatingLanes();

    // Lines 77-90, on hb after line 73: hb < 0x20b00000, then hb <= 0x000fffff (a subnormal b,
    // times 2^1022, k -= 1022, the words left stale) or not (both words up by 0x25800000, 2^600,
    // k -= 600).
    VectorMask<Integer> lowI = hb2.compare(VectorOperators.LT, HB_SMALL);
    VectorMask<Integer> subI = lowI.and(hb2.compare(VectorOperators.LE, HB_SUBNORMAL));
    VectorMask<Integer> smallI = lowI.andNot(subI);
    VectorMask<Double> sub = subI.cast(species);
    // Line 79, inside the subnormal branch: (hb|__LO(b))==0, b's bits being zero, returns a.
    VectorMask<Double> zeroB = b2Bits.compare(VectorOperators.EQ, 0L).cast(species).and(sub);
    IntVector ha3 = ha2.blend(ha2.add(SCALE_WORD), smallI);
    IntVector hb3 = hb2.blend(hb2.add(SCALE_WORD), smallI);
    k = k.blend(k.sub(1022), subI);
    k = k.blend(k.sub(600), smallI);
    LongVector ha3High =
        ((LongVector) ha3.convertShape(VectorOperators.I2L, longSpecies, 0))
            .lanewise(VectorOperators.LSHL, 32);
    LongVector hb3High =
        ((LongVector) hb3.convertShape(VectorOperators.I2L, longSpecies, 0))
            .lanewise(VectorOperators.LSHL, 32);
    // Lines 89-90: __HI(a) = ha; __HI(b) = hb. Lines 82-83: b *= t1; a *= t1, t1 = 2^1022.
    VectorMask<Long> smallL = smallI.cast(longSpecies);
    DoubleVector a = ((DoubleVector) a2Bits.blend(ha3High.or(a2Bits.and(Lanes.LOW_WORD)), smallL)
        .viewAsFloatingLanes()).blend(a2.mul(TWO1022), sub);
    DoubleVector b = ((DoubleVector) b2Bits.blend(hb3High.or(b2Bits.and(Lanes.LOW_WORD)), smallL)
        .viewAsFloatingLanes()).blend(b2.mul(TWO1022), sub);
    if (REFRESHING_WORDS) {
      // The fault: ha and hb read again from the values as they now are, their low words zero.
      ha3 = (IntVector) ((LongVector) a.viewAsIntegralLanes()).lanewise(VectorOperators.LSHR, 32)
          .convertShape(VectorOperators.L2I, intSpecies, 0);
      ha3High = ((LongVector) a.viewAsIntegralLanes()).and(~Lanes.LOW_WORD);
      hb3High = ((LongVector) b.viewAsIntegralLanes()).and(~Lanes.LOW_WORD);
    }

    // Line 94: w = a-b; line 95: w > b picks the form.
    DoubleVector w = a.sub(b);
    VectorMask<Double> above = w.compare(VectorOperators.GT, b);
    // Lines 96-99: t1 = 0, __HI(t1) = ha; t2 = a-t1; sqrt(t1*t1-(b*(-b)-t2*(a+t1))).
    DoubleVector t1 = (DoubleVector) ha3High.viewAsFloatingLanes();
    DoubleVector t2 = a.sub(t1);
    DoubleVector bb = b.mul(b.neg());
    DoubleVector r1 = t1.mul(t1).sub(DROPPING_T2 ? bb : bb.sub(t2.mul(a.add(t1))));
    // Lines 101-108: a = a+a; y1 = 0, __HI(y1) = hb; y2 = b-y1; t1 = 0, __HI(t1) =
    // ha+0x00100000; t2 = a-t1; sqrt(t1*y1-(w*(-w)-(t1*y2+t2*b))).
    DoubleVector twoA = a.add(a);
    DoubleVector y1 = (DoubleVector) hb3High.viewAsFloatingLanes();
    DoubleVector y2 = b.sub(y1);
    DoubleVector t1b = (DoubleVector) ((LongVector) ha3.add(TWICE_WORD)
        .convertShape(VectorOperators.I2L, longSpecies, 0))
        .lanewise(VectorOperators.LSHL, 32).viewAsFloatingLanes();
    DoubleVector t2b = twoA.sub(t1b);
    DoubleVector inner2 = DROPPING_Y2 ? t2b.mul(b) : t1b.mul(y2).add(t2b.mul(b));
    DoubleVector r2 = t1b.mul(y1).sub(w.mul(w.neg()).sub(inner2));
    // Lines 99 and 108: one square root, of the radicand of each lane's form.
    DoubleVector root = r2.blend(r1, above).lanewise(VectorOperators.SQRT);

    // Lines 110-114: if (k!=0) t1 = 1.0 with k<<20 added to its high word; return t1*w.
    VectorMask<Double> scaled = k.compare(VectorOperators.NE, 0).cast(species);
    DoubleVector twoK = (DoubleVector) ((LongVector) k.lanewise(VectorOperators.LSHL, 20)
        .add(ONE_WORD).convertShape(VectorOperators.I2L, longSpecies, 0))
        .lanewise(VectorOperators.LSHL, 32).viewAsFloatingLanes();
    DoubleVector result = root.blend(twoK.mul(root), scaled);

    // The returns before the medium path, from the last to the first, so that the first that
    // matches wins (L13): line 79's a, then line 63's a+b of the unscaled a and b.
    result = result.blend(a2, zeroB);
    if (!DROPPING_EARLY_RETURN) {
      result = result.blend(a1.add(b1), ratio);
    }

    if (live != null) {
      result = x.blend(result, live);
    }
    // ha >= 0x7ff00000, an infinity or a NaN in either argument, is finished per lane, written
    // out here with no call that takes a vector: lane and withLane are lane extract and insert,
    // which C2 intrinsifies for the fixed shapes, and the only call is StrictMath.hypot on two
    // doubles. The first eight lanes have constant indices, as in the other ports' finishes, and
    // the loop takes the lanes past eight. rare is and-ed with live, so an inactive lane is never
    // rewritten.
    VectorMask<Double> rare = ha.compare(VectorOperators.GE, HA_INF).cast(species);
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.hypot(x.lane(0), y.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.hypot(x.lane(1), y.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.hypot(x.lane(2), y.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.hypot(x.lane(3), y.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.hypot(x.lane(4), y.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.hypot(x.lane(5), y.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.hypot(x.lane(6), y.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.hypot(x.lane(7), y.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.hypot(x.lane(lane), y.lane(lane)));
      }
    }
    return result;
  }
}
