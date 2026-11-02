/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's s_log1p.c (reference/fdlibm-5.3/s_log1p.c), which carries this
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
 * log1p over lanes, ported from fdlibm 5.3's {@code log1p} in {@code
 * reference/fdlibm-5.3/s_log1p.c}; every lane is bit-identical to {@code StrictMath.log1p}.
 *
 * <p>The method, from the C: write {@code 1+x = 2^k * (1+f)} with {@code sqrt(2)/2 < 1+f <
 * sqrt(2)}, keeping the rounding error of {@code u = 1+x} as the correction term {@code c/u}; then
 * {@code log1p(x) = k*ln2_hi + (f - (hfsq - (s*(hfsq+R) + (k*ln2_lo + c))))} with {@code s =
 * f/(2+f)} and the degree-14 polynomial {@code R} in {@code s}. For {@code -0.2929 < x < 0.41422}
 * the C takes {@code k = 0} and {@code f = x} with no reduction. Every operation is the C's, in the
 * C's order, with no fused multiply-add and no reassociation.
 *
 * <p>The C's {@code int} words stay {@code int}: {@code hx}, {@code ax}, {@code hu} and {@code k}
 * are {@link IntVector} lanes, with the double vector's lane count at half its bits, and every
 * branch on them is the C's signed {@code int} compare. A high word comes in through {@code L2I}
 * from the long view ({@code Lanes.hiInt}), goes back through {@code I2L} ({@code Lanes.withHi}),
 * and {@code k} becomes a double through {@code I2D}: all three are vector instructions on every
 * AVX2 host, where {@code L2D} and {@code D2L} are not (SKILLS.md).
 *
 * <p>The C's branches become lane masks. Every lane computes the reduction (both forms of {@code
 * u}, both normalisations, the {@code k == 0} lanes' {@code f = x}) and both results of the
 * polynomial path ({@code k == 0} and otherwise). Three arms differ in the C's rare paths, the
 * early returns ({@code x <= -1}, {@code |x| < 2^-29}, {@code +inf} and NaN) and the {@code hu ==
 * 0} path ({@code |f| < 2^-20}, where {@code 1+x} is within 2^-20 of a power of two):
 *
 * <ul>
 *   <li>{@link #log1pBlended} computes them in every lane and blends them, with no branch.
 *   <li>{@link #log1pGuarded} computes and blends them the same way, but only for a vector that
 *       holds a rare lane, behind one {@code anyTrue}; it never allocates.
 *   <li>{@link #log1pFinish} finishes the rare lanes with {@code StrictMath.log1p}, one lane at a
 *       time through {@code toArray} and {@code fromArray}, behind one {@code anyTrue}, as {@code
 *       Exp} does; a vector that holds a rare lane allocates two arrays.
 * </ul>
 *
 * <p>All three are exact. {@link #log1p}, which {@code VFdlibm.log1p} calls, is the guarded arm,
 * chosen by the JMH measurement committed in {@code bench/results/log1p-laptop-avx2-2026-11-02.txt}
 * (under {@code -XX:UseAVX=2}, as C2 compiles for the AVX2 hosts that are the target) and {@code
 * log1p-laptop-2026-11-02.txt} (AVX-512); the pull request that added this port gives the numbers.
 * The other two are kept so the choice can be re-measured, on other hosts and as the JDK changes.
 */
final class Log1p {

  // The constants of s_log1p.c, lines 86-95 and 156, as its source writes them; Log1pTest checks
  // they parse to its hex.
  static final double LN2_HI = 6.93147180369123816490e-01; // 3fe62e42 fee00000
  static final double LN2_LO = 1.90821492927058770002e-10; // 3dea39ef 35793c76
  static final double TWO54 = 1.80143985094819840000e+16; // 43500000 00000000
  static final double LP1 = 6.666666666666735130e-01; // 3FE55555 55555593
  static final double LP2 = 3.999999999940941908e-01; // 3FD99999 9997FA04
  static final double LP3 = 2.857142874366239149e-01; // 3FD24924 94229359
  static final double LP4 = 2.222219843214978396e-01; // 3FCC71C5 1D8E78AF
  static final double LP5 = 1.818357216161805012e-01; // 3FC74664 96CB03DE
  static final double LP6 = 1.531383769920937332e-01; // 3FC39A09 D078C69F
  static final double LP7 = 1.479819860511658591e-01; // 3FC2F112 DF3E5244
  /** The literal of line 156, {@code 1.0-0.66666666666666666*f}. */
  static final double TWO_THIRDS = 0.66666666666666666;
  static final double ZERO = 0.0;

  // The words s_log1p.c compares hx, ax and hu against, all as signed ints, as the C does.
  /** {@code hx < 0x3FDA827A}: {@code x < 0.41422}, negative x included (line 113). */
  static final int HX_BELOW_SQRT2_M1 = 0x3FDA827A;
  /** {@code ax >= 0x3ff00000}: of those, {@code x <= -1.0} (line 114). */
  static final int AX_MINUS_ONE = 0x3ff00000;
  /** {@code ax < 0x3e200000}: {@code |x| < 2^-29}, the lanes returned early (line 118). */
  static final int AX_TINY = 0x3e200000;
  /** {@code ax < 0x3c900000}: {@code |x| < 2^-54}, of those the lanes returning x (line 120). */
  static final int AX_TINIEST = 0x3c900000;
  /** {@code hx <= (int)0xbfd2bec3}: {@code -0.2929 < x < 0}, which take k = 0 (line 125). */
  static final int HX_NEGATIVE_K_ZERO = 0xbfd2bec3;
  /** {@code hx >= 0x7ff00000}: {@code +inf} or a NaN with the sign bit clear (line 128). */
  static final int HX_NON_FINITE = 0x7ff00000;
  /** {@code hx < 0x43400000}: {@code x < 2^53}, where {@code u = 1.0+x} (line 130). */
  static final int HX_HUGE = 0x43400000;
  /** {@code hu < 0x6a09e}: the significand below sqrt(2), normalised to [1, 2) (line 143). */
  static final int HU_SQRT2 = 0x6a09e;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_CORRECTION = "log1p.dropCorrection";
  static final String CONSTANT_OFF_BY_ONE_ULP = "log1p.constantOffByOneUlp";
  static final String THRESHOLD_MOVED = "log1p.thresholdMoved";
  /** Lp1 one ulp up, for {@link #CONSTANT_OFF_BY_ONE_ULP}. */
  private static final double LP1_UP = Double.longBitsToDouble(0x3FE55555_55555594L);
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  private static final boolean DROPPING_CORRECTION = Faults.is(DROP_CORRECTION);
  private static final double LP1_USED = Faults.is(CONSTANT_OFF_BY_ONE_ULP) ? LP1_UP : LP1;
  /** The u = x threshold moved from 2^53 down to 2^52, for {@link #THRESHOLD_MOVED}. */
  private static final int HX_HUGE_USED = Faults.is(THRESHOLD_MOVED) ? 0x43300000 : HX_HUGE;

  /** How the rare lanes are computed: blended always, blended behind a test, or per lane. */
  private static final int BLEND = 0;
  private static final int GUARDED = 1;
  private static final int FINISH = 2;

  private Log1p() {}

  /**
   * log1p as {@code VFdlibm.log1p} computes it, the guarded arm. {@code live} is null for every
   * lane; otherwise only live lanes are computed, inactive lanes return {@code x}'s lane untouched,
   * and an inactive lane never sends the vector down a rare path.
   */
  static DoubleVector log1p(DoubleVector x, VectorMask<Double> live) {
    return log1p(x, live, GUARDED);
  }

  /**
   * log1p with every branch of s_log1p.c computed in every lane and blended. {@code live} is as
   * for {@link #log1p(DoubleVector, VectorMask)}.
   */
  static DoubleVector log1pBlended(DoubleVector x, VectorMask<Double> live) {
    return log1p(x, live, BLEND);
  }

  /**
   * log1p with the rare paths computed and blended only when a live lane takes one. {@code live}
   * is as for {@link #log1p(DoubleVector, VectorMask)}.
   */
  static DoubleVector log1pGuarded(DoubleVector x, VectorMask<Double> live) {
    return log1p(x, live, GUARDED);
  }

  /**
   * log1p with the rare lanes finished per lane by {@code StrictMath.log1p}, through {@code
   * toArray} and {@code fromArray}, Exp's way. {@code live} is as for {@link #log1p(DoubleVector,
   * VectorMask)}.
   */
  static DoubleVector log1pFinish(DoubleVector x, VectorMask<Double> live) {
    return log1p(x, live, FINISH);
  }

  /**
   * The three arms in one body, so they share every line but the rare paths; {@code mode} is a
   * constant at each call above, and once inlined the other arms' code folds away.
   */
  private static DoubleVector log1p(DoubleVector x, VectorMask<Double> live, int mode) {
    VectorSpecies<Double> species = x.species();
    // The calls into Lanes come early, before the polynomial, while the graph is small: C2 stops
    // inlining once a method is large (NodeCountInliningCutoff), and a refused call boxes.
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    DoubleVector one = DoubleVector.broadcast(species, 1.0);

    // Lines 109-110: hx = __HI(x), ax = hx&0x7fffffff, as ints.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector ax = hx.and(0x7fffffff);

    // Every branch on hx and ax. Line 113: hx < 0x3FDA827A. Line 114: of those, ax >= 0x3ff00000
    // (x <= -1). Line 118: of those, ax < 0x3e200000 (|x| < 2^-29). Line 125: hx > 0 or hx <=
    // (int)0xbfd2bec3, k = 0. Line 128: hx >= 0x7ff00000. Line 130: hx >= 0x43400000, u = x.
    VectorMask<Integer> below = hx.compare(VectorOperators.LT, HX_BELOW_SQRT2_M1);
    VectorMask<Integer> atMostMinusOne = below.and(ax.compare(VectorOperators.GE, AX_MINUS_ONE));
    VectorMask<Integer> tiny = below.and(ax.compare(VectorOperators.LT, AX_TINY));
    VectorMask<Integer> kZeroPath = below.and(hx.compare(VectorOperators.GT, 0)
        .or(hx.compare(VectorOperators.LE, HX_NEGATIVE_K_ZERO)));
    VectorMask<Integer> nonFinite = hx.compare(VectorOperators.GE, HX_NON_FINITE);
    VectorMask<Double> huge = hx.compare(VectorOperators.GE, HX_HUGE_USED).cast(species);

    // Lines 129-151, the reduction, for the lanes with k != 0 (the k = 0 lanes are blended in
    // below). Lines 131 and 137: u = 1.0+x, or u = x for x >= 2^53.
    DoubleVector u = one.add(x).blend(x, huge);
    // Lines 132-133 and 138-139: hu = __HI(u); k = (hu>>20)-1023.
    IntVector hu = Lanes.hiInt(u, intSpecies);
    IntVector k = hu.lanewise(VectorOperators.ASHR, 20).sub(1023);
    // Lines 134-135: c = (k>0)? 1.0-(u-x):x-(u-1.0); c /= u. Line 140: c = 0 for x >= 2^53.
    VectorMask<Double> kPositive = k.compare(VectorOperators.GT, 0).cast(species);
    DoubleVector c = x.sub(u.sub(1.0)).blend(one.sub(u.sub(x)), kPositive).div(u).blend(0.0, huge);
    if (DROPPING_CORRECTION) {
      c = DoubleVector.zero(species);
    }
    // Lines 142-149: hu &= 0x000fffff; below sqrt(2), __HI(u) = hu|0x3ff00000; at or above it,
    // k += 1, __HI(u) = hu|0x3fe00000 and hu = (0x00100000-hu)>>2.
    hu = hu.and(0x000fffff);
    VectorMask<Integer> halve = hu.compare(VectorOperators.GE, HU_SQRT2);
    k = k.blend(k.add(1), halve);
    IntVector uHi = hu.or(0x3ff00000).blend(hu.or(0x3fe00000), halve);
    hu = hu.blend(IntVector.broadcast(intSpecies, 0x00100000).sub(hu)
        .lanewise(VectorOperators.ASHR, 2), halve);
    DoubleVector uNormal = Lanes.withHi(u, uHi);
    // Line 150: f = u-1.0. Line 126: the k = 0 lanes take k = 0, f = x and hu = 1.
    DoubleVector f = uNormal.sub(1.0).blend(x, kZeroPath.cast(species));
    k = k.blend(0, kZeroPath);
    hu = hu.blend(1, kZeroPath);
    // k as a double, for k*ln2_hi and k*ln2_lo; I2D is exact, as the C's int-to-double is.
    DoubleVector dk = (DoubleVector) k.convertShape(VectorOperators.I2D, species, 0);
    VectorMask<Double> kIsZero = k.compare(VectorOperators.EQ, 0).cast(species);
    VectorMask<Integer> huIsZero = hu.compare(VectorOperators.EQ, 0);

    // Line 152: hfsq = 0.5*f*f.
    DoubleVector hfsq = f.mul(0.5).mul(f);
    DoubleVector kLn2Hi = dk.mul(LN2_HI);
    DoubleVector kLn2LoPlusC = dk.mul(LN2_LO).add(c);
    // Lines 160-162: s = f/(2.0+f); z = s*s; R = z*(Lp1+z*(Lp2+z*(Lp3+z*(Lp4+z*(Lp5+z*(Lp6+
    // z*Lp7)))))).
    DoubleVector s = f.div(DoubleVector.broadcast(species, 2.0).add(f));
    DoubleVector z = s.mul(s);
    DoubleVector r = z.mul(z.mul(z.mul(z.mul(z.mul(z.mul(z.mul(LP7).add(LP6)).add(LP5)).add(LP4))
        .add(LP3)).add(LP2)).add(LP1_USED));
    // Lines 163-164: k == 0 gives f-(hfsq-s*(hfsq+R)); otherwise
    // k*ln2_hi-((hfsq-(s*(hfsq+R)+(k*ln2_lo+c)))-f).
    DoubleVector sTimes = s.mul(hfsq.add(r));
    DoubleVector result = kLn2Hi.sub(hfsq.sub(sTimes.add(kLn2LoPlusC)).sub(f))
        .blend(f.sub(hfsq.sub(sTimes)), kIsZero);

    // The rare lanes: the early returns and |f| < 2^-20. The blended arm never reads this mask,
    // and its code folds away there.
    VectorMask<Double> rare = atMostMinusOne.or(tiny).or(nonFinite).or(huIsZero).cast(species);
    if (live != null) {
      rare = rare.and(live);
    }
    if (mode == BLEND || (mode == GUARDED && rare.anyTrue())) {
      // Lines 153-159, |f| < 2^-20: R = hfsq*(1.0-0.66666666666666666*f); k == 0 gives f-R,
      // otherwise k*ln2_hi-((R-(k*ln2_lo+c))-f); and for f == 0, zero when k == 0, otherwise
      // c += k*ln2_lo, k*ln2_hi+c.
      DoubleVector rNear = hfsq.mul(one.sub(f.mul(TWO_THIRDS)));
      DoubleVector near = kLn2Hi.sub(rNear.sub(kLn2LoPlusC).sub(f)).blend(f.sub(rNear), kIsZero);
      // c += k*ln2_lo is c + k*ln2_lo, which IEEE addition rounds as k*ln2_lo + c.
      DoubleVector exact = kLn2Hi.add(kLn2LoPlusC).blend(ZERO, kIsZero);
      near = near.blend(exact, f.compare(VectorOperators.EQ, ZERO));
      result = result.blend(near, huIsZero.cast(species));
      // The early returns, lines 114-128; their masks are disjoint. Line 128: x+x.
      result = result.blend(x.add(x), nonFinite.cast(species));
      // Lines 119-123: x for |x| < 2^-54 (two54+x>zero holds for every such x), else
      // x - x*x*0.5.
      DoubleVector tinyResult = x.sub(x.mul(x).mul(0.5))
          .blend(x, ax.compare(VectorOperators.LT, AX_TINIEST).cast(species));
      result = result.blend(tinyResult, tiny.cast(species));
      // Lines 115-116: -two54/zero for x == -1.0, else (x-x)/(x-x), an invalid operation
      // whose NaN's bits the C leaves to the hardware (x86's divpd gives 0xfff8000000000000,
      // or a negative NaN x quieted). StrictMath.log1p returns Double.NaN, 0x7ff8000000000000,
      // for every such lane, negative NaNs of any payload included; Log1pTest checks it at every
      // width. So these lanes take Double.NaN. A positive NaN takes line 128's x+x, as in the C.
      DoubleVector minusOne = DoubleVector.broadcast(species, Double.NaN)
          .blend(-TWO54 / ZERO, x.compare(VectorOperators.EQ, -1.0));
      result = result.blend(minusOne, atMostMinusOne.cast(species));
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (mode == FINISH && rare.anyTrue()) {
      result = finishPerLane(x, result, rare);
    }
    return result;
  }

  /** The lanes of {@code rare} replaced by {@code StrictMath.log1p} of their input. */
  private static DoubleVector finishPerLane(
      DoubleVector x, DoubleVector result, VectorMask<Double> rare) {
    double[] xs = x.toArray();
    double[] rs = result.toArray();
    for (int lane = 0; lane < xs.length; lane++) {
      if (rare.laneIsSet(lane)) {
        rs[lane] = StrictMath.log1p(xs[lane]);
      }
    }
    return DoubleVector.fromArray(x.species(), rs, 0);
  }
}
