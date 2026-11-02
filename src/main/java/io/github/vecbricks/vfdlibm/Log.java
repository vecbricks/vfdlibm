/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_log.c (reference/fdlibm-5.3/e_log.c), which carries this notice:
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
 * log over lanes, ported from fdlibm 5.3's {@code __ieee754_log} in
 * {@code reference/fdlibm-5.3/e_log.c}; every lane is bit-identical to {@code StrictMath.log}.
 *
 * <p>The method, from the C: write {@code x = 2^k * (1+f)} with {@code sqrt(2)/2 < 1+f <
 * sqrt(2)}, by reading {@code k} from the high word and rewriting the high word's exponent;
 * approximate {@code log(1+f)} with {@code s = f/(2+f)} and the degree-14 polynomial {@code
 * Lg1..Lg7} in {@code s}, or by {@code f - f*f*(0.5 - f/3)} when {@code |f| < 2^-20}; and add
 * {@code k*ln2}, split as {@code ln2_hi + ln2_lo}. Every operation is the C's, in the C's order,
 * with no fused multiply-add and no reassociation.
 *
 * <p>The C's {@code int} words stay in int lanes: {@code hx}, {@code k}, {@code i} and {@code j}
 * are computed in an {@link IntVector} with the double vector's lane count at half its bits, as
 * {@link Lanes#intSpecies} gives it. So {@code dk = (double)k} is an {@code I2D}, which every AVX2
 * host lowers, where a {@code k} in long lanes would need {@code L2D}, which boxes there.
 *
 * <p>The C's branches become lane masks. Every lane computes the common path (a normal, positive,
 * finite {@code x}): both forms of {@code log(1+f)} for {@code |f| >= 2^-20} (the {@code i > 0}
 * one with {@code hfsq} and the other), the {@code |f| < 2^-20} form and the {@code f == 0}
 * result, each in its {@code k == 0} and its {@code k != 0} form as the C writes them, and the
 * masks blend them. Two arms differ in the C's rare paths, the lanes with {@code hx < 0x00100000}
 * ({@code x <= 0}, a subnormal, a negative NaN) or {@code hx >= 0x7ff00000} (+inf, a positive NaN):
 *
 * <ul>
 *   <li>{@link #log} finishes those lanes per lane with {@code StrictMath.log} after the vector
 *       body, behind one {@code anyTrue} a vector, reading and writing the lanes in place; on
 *       ordinary data that code never runs.
 *   <li>{@link #logBlended} computes them in every lane too and blends them, with no branch: the
 *       subnormal lanes are scaled by {@code 2^54} before the high word is read.
 * </ul>
 *
 * <p>Both are exact. {@code VFdlibm.log} uses the first, a provisional choice from the laptop's
 * JMH runs in {@code bench/results/log-laptop-avx2-2026-11-02.txt} (under {@code -XX:UseAVX=2},
 * as C2 compiles for the AVX2 hosts that are the target) and {@code log-laptop-2026-11-02.txt}
 * (with AVX-512), laptop numbers, not headlines. Under {@code UseAVX=2} at 256 bits the finish is
 * about 1.2x ahead of the blend on ordinary data and level with it, about 1.05x, when one input
 * in 16 is rare; both allocate nothing. With AVX-512 at 512 bits on that mixed data the blend
 * wins, by 1.36x in both runs. The EPYC 7763 run of this port decides the choice. The second arm
 * is kept so it can be re-measured, on other hosts and as the JDK changes.
 */
final class Log {

  // The constants of e_log.c, as its source writes them; LogTest checks they parse to its hex.
  static final double LN2_HI = 6.93147180369123816490e-01; // 3fe62e42 fee00000
  static final double LN2_LO = 1.90821492927058770002e-10; // 3dea39ef 35793c76
  static final double TWO54 = 1.80143985094819840000e+16; // 43500000 00000000
  static final double LG1 = 6.666666666666735130e-01; // 3FE55555 55555593
  static final double LG2 = 3.999999999940941908e-01; // 3FD99999 9997FA04
  static final double LG3 = 2.857142874366239149e-01; // 3FD24924 94229359
  static final double LG4 = 2.222219843214978396e-01; // 3FCC71C5 1D8E78AF
  static final double LG5 = 1.818357216161805012e-01; // 3FC74664 96CB03DE
  static final double LG6 = 1.531383769920937332e-01; // 3FC39A09 D078C69F
  static final double LG7 = 1.479819860511658591e-01; // 3FC2F112 DF3E5244
  static final double ZERO = 0.0;
  /** Line 117's {@code 0.33333333333333333}, which parses to 0x3fd5555555555555. */
  static final double THIRD = 0.33333333333333333;

  // The words e_log.c compares and adds.
  /** {@code hx < 0x00100000}: {@code x < 2^-1022}, so zero, negative or subnormal. */
  static final int HX_MIN_NORMAL = 0x00100000;
  /** {@code hx >= 0x7ff00000}: +inf or a NaN. */
  static final int HX_NON_FINITE = 0x7ff00000;
  /**
   * Line 110's {@code 0x95f64}: added to the significand's high bits, it carries into bit 20 when
   * {@code 1+f >= sqrt(2)} (from {@code hx >= 0x6a09c}), and those lanes are halved.
   */
  static final int SQRT2_SPLIT = 0x95f64;
  /** Line 124's {@code 0x6147a}: the lowest {@code hx} with {@code i > 0}. */
  static final int HX_BAND_LO = 0x6147a;
  /** Line 126's {@code 0x6b851}: the highest {@code hx} with {@code i > 0}. */
  static final int HX_BAND_HI = 0x6b851;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_CORRECTION = "log.dropCorrection";
  static final String CONSTANT_OFF_BY_ONE_ULP = "log.constantOffByOneUlp";
  static final String THRESHOLD_MOVED = "log.thresholdMoved";
  /** ln2_hi one ulp up, for {@link #CONSTANT_OFF_BY_ONE_ULP}. */
  private static final double LN2_HI_UP = Double.longBitsToDouble(0x3fe62e42_fee00001L);
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  private static final boolean DROPPING_CORRECTION = Faults.is(DROP_CORRECTION);
  private static final double LN2_HI_USED =
      Faults.is(CONSTANT_OFF_BY_ONE_ULP) ? LN2_HI_UP : LN2_HI;
  private static final int SQRT2_SPLIT_USED =
      Faults.is(THRESHOLD_MOVED) ? SQRT2_SPLIT + 1 : SQRT2_SPLIT;

  private Log() {}

  /**
   * log with the rare lanes finished per lane. {@code live} is null for every lane; otherwise only
   * live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an inactive lane
   * never takes the per-lane finish.
   */
  static DoubleVector log(DoubleVector x, VectorMask<Double> live) {
    return log(x, live, true);
  }

  /**
   * log with every branch of e_log.c computed in every lane and blended. {@code live} is as for
   * {@link #log(DoubleVector, VectorMask)}.
   */
  static DoubleVector logBlended(DoubleVector x, VectorMask<Double> live) {
    return log(x, live, false);
  }

  /**
   * Both arms in one body, so they share every line but the rare paths; {@code finish} is a
   * constant at each call above, and once inlined the other arm's code folds away.
   */
  private static DoubleVector log(DoubleVector x, VectorMask<Double> live, boolean finish) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 100 and 107: the common lanes have 0x00100000 <= hx < 0x7ff00000, for a signed hx,
    // which for doubles is MIN_NORMAL <= x < +inf. Both compares are false for a NaN, so every
    // NaN lane, of either sign, is rare, as in the C.
    VectorMask<Double> common = x.compare(VectorOperators.GE, Lanes.fromHi(HX_MIN_NORMAL))
        .and(x.compare(VectorOperators.LT, Lanes.fromHi(HX_NON_FINITE)));

    // Line 104, blended arm only: a subnormal is scaled by two54, and k starts at -54.
    DoubleVector xs = x;
    VectorMask<Double> subnormal = null;
    if (!finish) {
      subnormal = x.compare(VectorOperators.GT, ZERO)
          .and(x.compare(VectorOperators.LT, Lanes.fromHi(HX_MIN_NORMAL)));
      xs = x.blend(x.mul(TWO54), subnormal);
    }

    // The int words, lines 105-112, first: C2 stops inlining calls once a method's graph is
    // large (NodeCountInliningCutoff), and a refused call boxes its vectors, so the calls into
    // Lanes come before the arithmetic. hx is the signed high word, as the C's int hx.
    IntVector hx = Lanes.hiInt(xs, intSpecies);
    // Line 108: k += (hx>>20)-1023.
    IntVector k = hx.lanewise(VectorOperators.ASHR, 20).sub(1023);
    if (!finish) {
      k = k.add(-54, subnormal.cast(intSpecies));
    }
    // Lines 109-112: hx &= 0x000fffff; i = (hx+0x95f64)&0x100000; __HI(x) = hx|(i^0x3ff00000);
    // k += (i>>20). The lanes with 1+f >= sqrt(2) carry into bit 20 and are halved.
    IntVector hxm = hx.and(0x000fffff);
    IntVector i = hxm.add(SQRT2_SPLIT_USED).and(0x100000);
    DoubleVector xn = Lanes.withHi(xs, hxm.or(i.lanewise(VectorOperators.XOR, 0x3ff00000)));
    k = k.add(i.lanewise(VectorOperators.ASHR, 20));
    // Line 114: (0x000fffff&(2+hx))<3, which holds when |f| < 2**-20.
    VectorMask<Double> small =
        hxm.add(2).and(0x000fffff).compare(VectorOperators.LT, 3).cast(species);
    // Lines 124, 126, 129 and 131: i = hx-0x6147a; j = 0x6b851-hx; i |= j; i > 0.
    IntVector j = IntVector.broadcast(intSpecies, HX_BAND_HI).sub(hxm);
    VectorMask<Double> band =
        hxm.sub(HX_BAND_LO).or(j).compare(VectorOperators.GT, 0).cast(species);
    VectorMask<Double> kIsZero = k.compare(VectorOperators.EQ, 0).cast(species);
    // Lines 115, 118 and 122: dk = (double)k, an exact I2D.
    DoubleVector dk = (DoubleVector) k.convertShape(VectorOperators.I2D, species, 0);

    // Line 113: f = x-1.0.
    DoubleVector f = xn.sub(1.0);
    DoubleVector dkHi = dk.mul(LN2_HI_USED);
    DoubleVector dkLo = DROPPING_CORRECTION ? DoubleVector.zero(species) : dk.mul(LN2_LO);

    // Lines 121-130: s = f/(2.0+f); z = s*s; w = z*z;
    // t1 = w*(Lg2+w*(Lg4+w*Lg6)); t2 = z*(Lg1+w*(Lg3+w*(Lg5+w*Lg7))); R = t2+t1.
    DoubleVector s = f.div(DoubleVector.broadcast(species, 2.0).add(f));
    DoubleVector z = s.mul(s);
    DoubleVector w = z.mul(z);
    DoubleVector t1 = w.mul(w.mul(w.mul(LG6).add(LG4)).add(LG2));
    DoubleVector t2 = z.mul(w.mul(w.mul(w.mul(LG7).add(LG5)).add(LG3)).add(LG1));
    DoubleVector r = t2.add(t1);

    // Lines 136-137, i <= 0: f-s*(f-R) when k == 0, else dk*ln2_hi-((s*(f-R)-dk*ln2_lo)-f).
    DoubleVector sfr = s.mul(f.sub(r));
    DoubleVector result = dkHi.sub(sfr.sub(dkLo).sub(f)).blend(f.sub(sfr), kIsZero);
    // Lines 132-134, i > 0: hfsq = 0.5*f*f; f-(hfsq-s*(hfsq+R)) when k == 0,
    // else dk*ln2_hi-((hfsq-(s*(hfsq+R)+dk*ln2_lo))-f).
    DoubleVector hfsq = f.mul(0.5).mul(f);
    DoubleVector shr = s.mul(hfsq.add(r));
    DoubleVector withHfsq =
        dkHi.sub(hfsq.sub(shr.add(dkLo)).sub(f)).blend(f.sub(hfsq.sub(shr)), kIsZero);
    result = result.blend(withHfsq, band);
    // Lines 117-119, |f| < 2**-20: R = f*f*(0.5-0.33333333333333333*f); f-R when k == 0,
    // else dk*ln2_hi-((R-dk*ln2_lo)-f).
    DoubleVector rSmall = f.mul(f).mul(DoubleVector.broadcast(species, 0.5).sub(f.mul(THIRD)));
    DoubleVector smallResult =
        dkHi.sub(rSmall.sub(dkLo).sub(f)).blend(f.sub(rSmall), kIsZero);
    // Lines 115-116, f == 0: zero when k == 0, else dk*ln2_hi+dk*ln2_lo.
    VectorMask<Double> fIsZero = f.compare(VectorOperators.EQ, ZERO).and(small);
    DoubleVector exact = dkHi.add(dkLo).blend(ZERO, kIsZero);
    result = result.blend(smallResult, small).blend(exact, fIsZero);

    if (!finish) {
      // The rare paths, in the C's order of precedence (the last blend wins). Line 107: x+x for
      // +inf and a positive NaN. Line 103: (x-x)/zero for a set sign bit, which covers -inf and
      // a negative NaN. Line 102: -two54/zero, -inf, for +-0.
      VectorMask<Double> negative =
          Lanes.bits(x).compare(VectorOperators.LT, 0L).cast(species);
      VectorMask<Double> positiveNonFinite = common.or(subnormal).or(negative).not();
      result = result.blend(x.add(x), positiveNonFinite);
      result = result.blend(x.sub(x).div(ZERO), negative);
      result = result.blend(-TWO54 / ZERO, x.compare(VectorOperators.EQ, ZERO));
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (finish) {
      VectorMask<Double> rare = common.not();
      if (live != null) {
        rare = rare.and(live);
      }
      // The finish stays in the vector: lane(i) and withLane(i, v) switch on i to a constant
      // lane, which C2 intrinsifies, so no array is made and no vector is passed to a call; the
      // only call is StrictMath.log itself. A finish that took the vectors as arguments would be
      // refused inlining this late in the body (NodeCountInliningCutoff) and receive them boxed.
      if (rare.anyTrue()) {
        long bits = rare.toLong();
        for (int lane = 0; lane < species.length(); lane++) {
          if ((bits >>> lane & 1L) != 0) {
            result = result.withLane(lane, StrictMath.log(x.lane(lane)));
          }
        }
      }
    }
    return result;
  }
}
