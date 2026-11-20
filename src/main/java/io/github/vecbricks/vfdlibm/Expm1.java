/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's s_expm1.c (reference/fdlibm-5.3/s_expm1.c), which carries this
 * notice:
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

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * expm1 over lanes, ported from fdlibm 5.3's {@code expm1} in {@code
 * reference/fdlibm-5.3/s_expm1.c}; every lane is bit-identical to {@code StrictMath.expm1}.
 *
 * <p>The method, from the C: reduce {@code x = k*ln2 + r} with {@code |r| <= 0.5*ln2}, keeping
 * the rounding error of {@code r = hi - lo} as the correction {@code c}; approximate {@code
 * expm1(r)} by the rational form with the degree-5 polynomial {@code Q1..Q5} in {@code hxs =
 * r*r/2}, giving {@code e}; then return one of seven forms chosen by {@code k}: {@code k == 0},
 * {@code k == -1}, {@code k == 1} (two forms, split at {@code r < -0.25}), {@code k <= -2 || k >
 * 56}, {@code 2 <= k < 20} and {@code 20 <= k <= 56}, the last three scaled back by adding {@code
 * k} to the exponent. Every operation is the C's, in the C's order, with no fused multiply-add
 * and no reassociation, except where {@code PROOFS.md} proves a different computation equal:
 *
 * <ul>
 *   <li>The reduction is exp's, and so are its folds (L10): every lane takes the far form,
 *       which gives the near band's {@code k}, {@code hi} and {@code lo} exactly (exp's L2, for
 *       the same constants and words); a lane that is not reduced gets {@code k = 0}, forced by
 *       the C's high-word test, so {@code r = x} and {@code c = +0} (exp's L1, step 1); and
 *       {@code (xsb==0)?0.5:-0.5} is 0.5 with {@code x}'s sign bit (exp's L5).
 *   <li>The {@code k == 0} return, {@code x - (x*e-hxs)}, is computed as the other forms' {@code
 *       x - e} after {@code e = (x*(e-c)-c)-hxs}, with {@code c = +0} on those lanes (L11).
 * </ul>
 *
 * <p>The C's {@code int} words stay {@code int}, as in {@code Log1p}: {@code hx} and {@code k}
 * are {@link IntVector} lanes, with the double vector's lane count at half its bits. The C's
 * {@code hx} is {@code unsigned}, but every compare is made after {@code hx &= 0x7fffffff}, so
 * both sides lie in {@code [0, 2^31)} and the signed {@code int} compare is the C's. {@code k} is
 * the far form's {@code D2I} and {@code t = k} its {@code I2D}, as in {@code Exp}; the words
 * {@code 0x3ff00000-(0x200000>>k)} and {@code (0x3ff-k)<<20} are int lanes too, shifted by
 * {@code k} lane by lane, and the C's {@code __HI(y) += (k<<20)} is the long add of {@code k <<
 * 52} to the bits ({@code Lanes.addExponent}, written out). Each form is computed in every lane
 * and blended by the masks of {@code k}; a form's value on a lane that does not take it is never
 * kept, whatever the shift counts there.
 *
 * <p>Three arms differ in the C's rare paths, the early returns: the filter ({@code +-inf},
 * NaN, {@code x > o_threshold} and {@code x < -56*ln2}) and {@code |x| < 2^-54}. A positive
 * {@code x} from {@code 56*ln2} up to {@code o_threshold} is no rare lane: the C reduces it, with
 * {@code k} up to 1024, and so does every arm here.
 *
 * <ul>
 *   <li>{@link #expm1Blended} computes them in every lane and blends them, with no branch.
 *   <li>{@link #expm1Guarded} computes and blends them the same way, but only for a vector that
 *       holds a rare lane, behind one {@code anyTrue}.
 *   <li>{@link #expm1Finish} finishes the rare lanes with {@code StrictMath.expm1}, behind one
 *       {@code anyTrue}, as {@code Log1p}'s finish does, all in this body, with no array and no
 *       call that takes a vector.
 * </ul>
 *
 * <p>All three are exact. {@link #expm1}, which {@code VFdlibm.expm1} calls, is the guarded arm,
 * provisionally, as for log1p, whose rare paths are as cheap in vector form. No arm has been
 * timed: the runs on the benchmark hosts decide, each for its own instruction set, the AVX-512
 * hosts first ({@code HARDWARE.md}), and the other two arms are kept so the choice can be
 * measured.
 */
final class Expm1 {

  // The constants of s_expm1.c, lines 115-127, as its source writes them; Expm1Test checks they
  // parse to its hex.
  static final double ONE = 1.0;
  static final double HUGE = 1.0e+300;
  static final double TINY = 1.0e-300;
  static final double O_THRESHOLD = 7.09782712893383973096e+02; // 0x40862E42, 0xFEFA39EF
  static final double LN2_HI = 6.93147180369123816490e-01; // 0x3fe62e42, 0xfee00000
  static final double LN2_LO = 1.90821492927058770002e-10; // 0x3dea39ef, 0x35793c76
  static final double INVLN2 = 1.44269504088896338700e+00; // 0x3ff71547, 0x652b82fe
  static final double Q1 = -3.33333333333331316428e-02; // BFA11111 111110F4
  static final double Q2 = 1.58730158725481460165e-03; // 3F5A01A0 19FE5585
  static final double Q3 = -7.93650757867487942473e-05; // BF14CE19 9EAADBB7
  static final double Q4 = 4.00821782732936239552e-06; // 3ED0CFCA 86E65239
  static final double Q5 = -2.01099218183624371326e-07; // BE8AFDB7 6E09C32D

  // The words s_expm1.c compares the high word of |x| against, as ints in [0, 2^31).
  /** {@code hx >= 0x4043687A}: {@code |x| >= 56*ln2}, the filter's outer test (line 146). */
  static final int HX_56_LN2 = 0x4043687A;
  /** {@code hx >= 0x40862E42}: {@code |x| >= 709.78...} (line 147). */
  static final int HX_FILTER = 0x40862E42;
  /** {@code hx >= 0x7ff00000}: an infinity or a NaN (line 148). */
  static final int HX_NON_FINITE = 0x7ff00000;
  /** {@code hx > 0x3fd62e42}: {@code |x| > 0.5*ln2}, the reduced lanes (line 162). */
  static final int HX_HALF_LN2 = 0x3fd62e42;
  /**
   * {@code hx < 0x3FF0A2B2}: {@code |x| < 1.5*ln2}, the C's near band (line 163), which the far
   * form covers here (PROOFS.md, L10); no lane tests it.
   */
  static final int HX_THREE_HALVES_LN2 = 0x3FF0A2B2;
  /** {@code hx < 0x3c900000}: {@code |x| < 2^-54}, the lanes returning x (line 177). */
  static final int HX_TINY = 0x3c900000;
  /** {@code k > 56}: with {@code k <= -2}, the form that returns {@code 2^k*y - 1} (line 197). */
  static final int K_FAR = 56;
  /** {@code k < 20}: the form with {@code t = 1-2^-k}; above it, {@code t = 2^-k} (line 203). */
  static final int K_SPLIT = 20;
  /** The raw bits of 0.5; {@code -0.5} is the same with the sign bit set (PROOFS.md, L5). */
  static final long HALF_BITS = 0x3fe00000_00000000L;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_CORRECTION = "expm1.dropCorrection";
  static final String CONSTANT_OFF_BY_ONE_ULP = "expm1.constantOffByOneUlp";
  static final String THRESHOLD_MOVED = "expm1.thresholdMoved";
  static final String GUARD_MISSES_FILTER = "expm1.guardMissesFilter";
  /** ln2_hi one ulp up, for {@link #CONSTANT_OFF_BY_ONE_ULP}. */
  private static final double LN2_HI_UP = Double.longBitsToDouble(0x3fe62e42_fee00001L);
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /** Drops the correction {@code c} from {@code e = (x*(e-c)-c)}. */
  private static final boolean DROPPING_CORRECTION = Faults.is(DROP_CORRECTION);
  private static final double LN2_HI_USED =
      Faults.is(CONSTANT_OFF_BY_ONE_ULP) ? LN2_HI_UP : LN2_HI;
  /**
   * Moves line 197's {@code k > 56} down to {@code k > 55}: the lanes with {@code k = 56} then
   * return {@code 2^k*y - 1}, which rounds differently from line 208's form on many of them.
   */
  private static final int K_FAR_USED = Faults.is(THRESHOLD_MOVED) ? K_FAR - 1 : K_FAR;
  /**
   * The guarded arm's rare mask without the filter's finite lanes ({@code x > o_threshold} and
   * {@code x < -56*ln2}), for {@link #GUARD_MISSES_FILTER}: a vector whose only rare lanes are
   * those then skips the rare paths, and a lane far past either limit returns the polynomial's
   * garbage ({@code k} saturates). The other arms are unchanged.
   */
  private static final boolean GUARD_MISSING_FILTER = Faults.is(GUARD_MISSES_FILTER);

  /** How the rare lanes are computed: blended always, blended behind a test, or per lane. */
  private static final int BLEND = 0;
  private static final int GUARDED = 1;
  private static final int FINISH = 2;

  private Expm1() {}

  /**
   * expm1 as {@code VFdlibm.expm1} computes it, the guarded arm. {@code live} is null for every
   * lane; otherwise only live lanes are computed, inactive lanes return {@code x}'s lane
   * untouched, and an inactive lane never sends the vector down a rare path.
   */
  static DoubleVector expm1(DoubleVector x, VectorMask<Double> live) {
    return expm1(x, live, GUARDED);
  }

  /**
   * expm1 with every branch of s_expm1.c computed in every lane and blended. {@code live} is as
   * for {@link #expm1(DoubleVector, VectorMask)}.
   */
  static DoubleVector expm1Blended(DoubleVector x, VectorMask<Double> live) {
    return expm1(x, live, BLEND);
  }

  /**
   * expm1 with the rare paths computed and blended only when a live lane takes one. {@code live}
   * is as for {@link #expm1(DoubleVector, VectorMask)}.
   */
  static DoubleVector expm1Guarded(DoubleVector x, VectorMask<Double> live) {
    return expm1(x, live, GUARDED);
  }

  /**
   * expm1 with the rare lanes finished per lane by {@code StrictMath.expm1}, read and written in
   * the vector with {@code lane} and {@code withLane}. {@code live} is as for {@link
   * #expm1(DoubleVector, VectorMask)}.
   */
  static DoubleVector expm1Finish(DoubleVector x, VectorMask<Double> live) {
    return expm1(x, live, FINISH);
  }

  /**
   * The three arms in one body, so they share every line but the rare paths; {@code mode} is a
   * constant at each call above, and once inlined the other arms' code folds away.
   */
  private static DoubleVector expm1(DoubleVector x, VectorMask<Double> live, int mode) {
    VectorSpecies<Double> species = x.species();
    // The calls into Lanes come first, while the graph is small: C2 stops inlining once a method
    // is large (NodeCountInliningCutoff), and a refused call boxes its vectors (SKILLS.md).
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    IntVector hx = Lanes.hiInt(x, intSpecies);
    LongVector bits = (LongVector) x.viewAsIntegralLanes();
    VectorSpecies<Long> longSpecies = bits.species();
    DoubleVector one = DoubleVector.broadcast(species, ONE);

    // Lines 140-143: hx = __HI(x); xsb = hx&0x80000000; hx &= 0x7fffffff.
    VectorMask<Integer> negative = hx.compare(VectorOperators.LT, 0);
    IntVector ax = hx.and(0x7fffffff);
    // Every branch on the high word. Line 146: hx >= 0x4043687A. Line 147: hx >= 0x40862E42.
    // Line 148: hx >= 0x7ff00000. Line 162: hx > 0x3fd62e42. Line 177: hx < 0x3c900000. Line
    // 163's near band needs no test of its own (L10).
    VectorMask<Integer> big = ax.compare(VectorOperators.GE, HX_56_LN2);
    VectorMask<Integer> filter = ax.compare(VectorOperators.GE, HX_FILTER);
    VectorMask<Integer> nonFinite = ax.compare(VectorOperators.GE, HX_NON_FINITE);
    VectorMask<Double> reduced = ax.compare(VectorOperators.GT, HX_HALF_LN2).cast(species);
    VectorMask<Integer> tiny = ax.compare(VectorOperators.LT, HX_TINY);

    // Argument reduction, lines 162-175, in the far form for every lane (PROOFS.md, L10):
    // k = invln2*x+((xsb==0)?0.5:-0.5); t = k; hi = x - t*ln2_hi; lo = t*ln2_lo.
    // - (xsb==0)?0.5:-0.5 is 0.5 with x's sign bit (exp's L5).
    // - The near band, 0.5 ln2 < |x| < 1.5 ln2, gets k = +-1 from it, and the near form's hi, lo
    //   and k exactly (exp's L2).
    // - A lane that is not reduced (line 162's test false) gets k = 0, forced by that test: the
    //   sum is replaced by 0 before the cast, so t = +0, hi = x, lo = +0, and then x = hi - lo is
    //   x itself and c = (hi-x)-lo is +0 (exp's L1, step 1). The C leaves k = 0 and x there.
    // D2I truncates toward zero like the C's int cast, and I2D is exact. A NaN gives 0, and the
    // cast saturates when the sum it converts, x*invln2 +- 0.5, truncates outside int's range
    // (about |x| >= 2^31 ln2), but those lanes are rare, returned early by the C, and their k is
    // never kept; a positive x up to o_threshold is kept, with k up to 1024.
    DoubleVector halF = (DoubleVector) bits.and(Long.MIN_VALUE).or(HALF_BITS)
        .viewAsFloatingLanes();
    DoubleVector kArg = DoubleVector.zero(species).blend(x.mul(INVLN2).add(halF), reduced);
    IntVector k = (IntVector) kArg.convertShape(VectorOperators.D2I, intSpecies, 0);
    DoubleVector t = (DoubleVector) k.convertShape(VectorOperators.I2D, species, 0);
    LongVector kLong = (LongVector) k.convertShape(VectorOperators.I2L, longSpecies, 0);
    DoubleVector hi = x.sub(t.mul(LN2_HI_USED));
    DoubleVector lo = t.mul(LN2_LO);
    // Lines 174-175: x = hi - lo; c = (hi-x)-lo. From here on the C's x is this r.
    DoubleVector r = hi.sub(lo);
    DoubleVector c = DROPPING_CORRECTION ? DoubleVector.zero(species) : hi.sub(r).sub(lo);

    // Lines 184-188: hfx = 0.5*x; hxs = x*hfx; r1 = one+hxs*(Q1+hxs*(Q2+hxs*(Q3+hxs*(Q4+hxs*
    // Q5)))); t = 3.0-r1*hfx; e = hxs*((r1-t)/(6.0 - x*t)). Each product of the C is written with
    // its operands either way round, the same single rounding of the same exact product.
    DoubleVector hfx = r.mul(0.5);
    DoubleVector hxs = r.mul(hfx);
    DoubleVector r1 = one.add(hxs.mul(Q5).add(Q4).mul(hxs).add(Q3).mul(hxs).add(Q2).mul(hxs)
        .add(Q1).mul(hxs));
    DoubleVector tt = DoubleVector.broadcast(species, 3.0).sub(r1.mul(hfx));
    DoubleVector e = hxs.mul(r1.sub(tt).div(DoubleVector.broadcast(species, 6.0).sub(r.mul(tt))));
    // Lines 191-192: e = (x*(e-c)-c); e -= hxs. On the k == 0 lanes c = +0, and this e is line
    // 189's x*e-hxs, so line 189's return is x-e below (L11).
    DoubleVector em = r.mul(e.sub(c)).sub(c).sub(hxs);
    DoubleVector rMinusE = r.sub(em);

    // The masks of k. Line 197: k <= -2 || k > 56. Line 203: k < 20, among the rest of k >= 2.
    VectorMask<Integer> far = k.compare(VectorOperators.LE, -2)
        .or(k.compare(VectorOperators.GT, K_FAR_USED));
    VectorMask<Integer> mid = k.compare(VectorOperators.GE, 2)
        .and(k.compare(VectorOperators.LT, K_SPLIT));
    VectorMask<Integer> high = k.compare(VectorOperators.GE, K_SPLIT).and(far.not());
    // Lines 202-208: t = one; __HI(t) = 0x3ff00000 - (0x200000>>k) for k < 20, or (0x3ff-k)<<20
    // above; one's high word, 0x3ff00000, is t's on the far lanes. Its low word is one's, 0.
    // The shifts take k lane by lane; on a lane whose form does not use the word, any count
    // gives a value that is not kept.
    IntVector midWord = IntVector.broadcast(intSpecies, 0x3ff00000)
        .sub(IntVector.broadcast(intSpecies, 0x200000).lanewise(VectorOperators.ASHR, k));
    IntVector highWord = IntVector.broadcast(intSpecies, 0x3ff).sub(k)
        .lanewise(VectorOperators.LSHL, 20);
    IntVector tWord = IntVector.broadcast(intSpecies, 0x3ff00000).blend(midWord, mid)
        .blend(highWord, high);
    // __HI(t) = tWord, Lanes.withHi on a zero low word written out, so no call is left here.
    LongVector tWordBits =
        (LongVector) tWord.convertShape(VectorOperators.I2L, longSpecies, 0);
    DoubleVector tv = (DoubleVector) tWordBits.lanewise(VectorOperators.LSHL, 32)
        .viewAsFloatingLanes();
    // Line 198, y = one-(e-x), and line 205, y = t-(e-x): one operation, its first operand
    // picked per lane by tWord. Lines 209-210: y = x-(e+t); y += one.
    VectorMask<Double> highLanes = high.cast(species);
    DoubleVector y = tv.sub(em.sub(r)).blend(r.sub(em.add(tv)).add(one), highLanes);
    // Lines 199, 206 and 211: __HI(y) += (k<<20), as the long add of k << 52 to y's bits
    // (Lanes.addExponent, written out). The addend's low word is zero, so y's low word is kept
    // and nothing carries out of it: the high word becomes __HI(y) + (k<<20) modulo 2^32, the
    // C's int add. Line 200: the far lanes return y-one.
    LongVector yBits = (LongVector) y.viewAsIntegralLanes();
    DoubleVector scaled = (DoubleVector) yBits.add(kLong.lanewise(VectorOperators.LSHL, 52))
        .viewAsFloatingLanes();
    DoubleVector result = scaled.blend(scaled.sub(one), far.cast(species));
    // Lines 194-196, k == 1: -2.0*(e-(x+0.5)) for x < -0.25, else one+2.0*(x-e).
    DoubleVector kOne = rMinusE.mul(2.0).add(one)
        .blend(em.sub(r.add(0.5)).mul(-2.0), r.compare(VectorOperators.LT, -0.25));
    result = result.blend(kOne, k.compare(VectorOperators.EQ, 1).cast(species));
    // Line 193, k == -1: 0.5*(x-e)-0.5.
    result = result.blend(rMinusE.mul(0.5).sub(0.5), k.compare(VectorOperators.EQ, -1)
        .cast(species));
    // Line 189, k == 0: x - (x*e-hxs), here x-e (L11).
    result = result.blend(rMinusE, k.compare(VectorOperators.EQ, 0).cast(species));

    // The rare lanes, the early returns of lines 146-158 and 177-179: the non-finite lanes, the
    // lanes of x > o_threshold and x < -56*ln2 (the outer test and the sign; line 156's x+tiny <
    // 0.0 is the blend's own test below), and |x| < 2^-54. The blended arm never reads this mask,
    // and its code folds away there.
    VectorMask<Double> overflow =
        filter.cast(species).and(x.compare(VectorOperators.GT, O_THRESHOLD));
    VectorMask<Integer> negativeBig = big.and(negative);
    VectorMask<Double> rare = (GUARD_MISSING_FILTER && mode == GUARDED
        ? nonFinite.or(tiny).cast(species)
        : nonFinite.or(tiny).or(negativeBig).cast(species).or(overflow));
    if (live != null) {
      rare = rare.and(live);
    }
    if (mode == BLEND || (mode == GUARDED && rare.anyTrue())) {
      // A later blend wins where its mask overlaps an earlier one's, as the C's earlier return
      // does: the non-finite lanes (+-inf among them) over the two blends before them; the tiny
      // lanes overlap no other. Lines 155-157: x < -56*ln2 returns tiny-one (-1.0) if
      // x+tiny<0.0, which holds for every such finite x; the non-finite ones are blended over.
      result = result.blend(TINY - ONE, negativeBig.cast(species)
          .and(x.add(TINY).compare(VectorOperators.LT, 0.0)));
      // Line 153: x > o_threshold returns huge*huge (+inf); +inf is blended over below.
      result = result.blend(HUGE * HUGE, overflow);
      // Lines 148-151: x+x for a NaN (a nonzero significand under hx >= 0x7ff00000, which is
      // IS_NAN), x for +inf and -1.0 for -inf.
      DoubleVector nonFiniteResult = x.blend(-1.0, negative.cast(species))
          .blend(x.add(x), x.test(VectorOperators.IS_NAN));
      result = result.blend(nonFiniteResult, nonFinite.cast(species));
      // Lines 177-179, |x| < 2^-54: t = huge+x; return x - (t-(huge+x)).
      DoubleVector tinyT = x.add(HUGE);
      result = result.blend(x.sub(tinyT.sub(x.add(HUGE))), tiny.cast(species));
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (mode == FINISH && rare.anyTrue()) {
      // The per-lane finish is written out here, with no call into vfdlibm, as Log1p's is: a
      // vfdlibm call this late in the body is refused (NodeCountInliningCutoff) and would take
      // its vectors boxed. lane and withLane are lane extract and insert, which C2 intrinsifies
      // at a variable lane index for the fixed shapes. rare is already and-ed with live, so an
      // inactive lane is never rewritten.
      long rareLanes = rare.toLong();
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.expm1(x.lane(lane)));
      }
    }
    return result;
  }
}
