/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_exp.c (reference/fdlibm-5.3/e_exp.c), which carries this notice:
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
 * exp over lanes, ported from fdlibm 5.3's {@code __ieee754_exp} in
 * {@code reference/fdlibm-5.3/e_exp.c}; every lane is bit-identical to {@code StrictMath.exp}.
 *
 * <p>The method, from the C: reduce {@code x = k*ln2 + r} with {@code |r| <= 0.5*ln2} (r held as
 * {@code hi - lo}), approximate {@code exp(r)} by the rational form with the degree-5 polynomial
 * {@code P1..P5}, and scale back by {@code 2^k}. Every operation is the C's, in the C's order, with
 * no fused multiply-add and no reassociation.
 *
 * <p>The C's branches on {@code |x|} become lane masks. Every lane computes the reduction (the near
 * form for {@code 0.5*ln2 < |x| < 1.5*ln2} and the far form above it), the primary-range formula,
 * the reduced formula and the {@code |x| < 2^-28} result, and the masks blend them. Two arms differ
 * in the C's rare paths, the filter on {@code |x| >= 709.78} (NaN, the infinities, overflow,
 * underflow and the band the filter lets through) and the subnormal scale-back ({@code k < -1021}):
 *
 * <ul>
 *   <li>{@link #exp} finishes those lanes per lane with {@code StrictMath.exp} after the vector
 *       body, behind one {@code anyTrue} a vector; on ordinary data that branch never runs. The
 *       finish walks the set bits of the rare mask ({@code toLong}), reads each lane with {@code
 *       lane} and writes it back with {@code withLane}, all in this body. At the fixed shapes
 *       C2 intrinsifies that lane access: at 256 and 512 bits the finish allocates nothing (at
 *       128 bits on x86 the masks box anyway). At {@code SPECIES_MAX} it is not intrinsified,
 *       and the finish still boxes on data with rare lanes (the {@code finishMax} rows of {@code
 *       bench/results/exp-finish-laptop-2026-11-02.txt}).
 *   <li>{@link #expBlended} computes them in every lane too and blends them, with no branch.
 * </ul>
 *
 * <p>Both are exact. {@code VFdlibm.exp} uses the first. On the AMD EPYC 7763, the headline
 * host, the first arm as first merged ({@code bench/results/exp-epyc7763-2026-11-02.txt}, minimums
 * in ns per 1024 doubles at 256 bits) was 1.57x faster than the blend on common data, where no
 * lane is rare (4203 against 6590), and lost once rare lanes were common: on mixed data, one rare
 * input in 16, it was 1.64x slower than the blend (12331 against 7512). Its finish was then a
 * separate method, which C2 refused to inline at the end of this large body, so every vector
 * boxed its arguments and the mixed rows allocated 52928 B/op. The finish is now written out
 * inline and allocates nothing at 256 and 512 bits (laptop numbers in {@code
 * bench/results/exp-finish-laptop-2026-11-02.txt}); the EPYC run of this code decides between
 * the arms again. The second arm is kept so the choice can be re-measured, on other hosts and as
 * the JDK changes.
 */
final class Exp {

  // The constants of e_exp.c, as its source writes them; ExpTest checks they parse to its hex.
  static final double ONE = 1.0;
  static final double HUGE = 1.0e+300;
  static final double TWOM1000 = 9.33263618503218878990e-302; // 2**-1000=0x01700000,0
  static final double O_THRESHOLD = 7.09782712893383973096e+02; // 0x40862E42, 0xFEFA39EF
  static final double U_THRESHOLD = -7.45133219101941108420e+02; // 0xc0874910, 0xD52D3051
  static final double LN2HI_0 = 6.93147180369123816490e-01; // 0x3fe62e42, 0xfee00000
  static final double LN2HI_1 = -6.93147180369123816490e-01; // 0xbfe62e42, 0xfee00000
  static final double LN2LO_0 = 1.90821492927058770002e-10; // 0x3dea39ef, 0x35793c76
  static final double LN2LO_1 = -1.90821492927058770002e-10; // 0xbdea39ef, 0x35793c76
  static final double INVLN2 = 1.44269504088896338700e+00; // 0x3ff71547, 0x652b82fe
  static final double P1 = 1.66666666666666019037e-01; // 0x3FC55555, 0x5555553E
  static final double P2 = -2.77777777770155933842e-03; // 0xBF66C16C, 0x16BEBD93
  static final double P3 = 6.61375632143793436117e-05; // 0x3F11566A, 0xAF25DE2C
  static final double P4 = -1.65339022054652515390e-06; // 0xBEBBBD41, 0xC5D26BF1
  static final double P5 = 4.13813679705723846039e-08; // 0x3E663769, 0x72BEA4D0

  // The high words e_exp.c compares |x| against.
  /** {@code |x| >= 709.78...}: the filter on non-finite, overflowing and underflowing lanes. */
  static final int HX_FILTER = 0x40862E42;
  /** {@code |x| > 0.5 ln2}: the lanes that are reduced. */
  static final int HX_HALF_LN2 = 0x3fd62e42;
  /** {@code |x| < 1.5 ln2}: of the reduced lanes, those that take the near form. */
  static final int HX_THREE_HALVES_LN2 = 0x3FF0A2B2;
  /** {@code |x| < 2**-28}: the lanes whose result is {@code one + x}. */
  static final int HX_TINY = 0x3e300000;
  /** {@code hx >= 0x7ff00000}: NaN or an infinity. */
  static final int HX_NON_FINITE = 0x7ff00000;
  /** The smallest k whose scale-back is a plain exponent add; below it the result is tiny. */
  static final int K_MIN_NORMAL = -1021;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_CORRECTION = "exp.dropCorrection";
  static final String CONSTANT_OFF_BY_ONE_ULP = "exp.constantOffByOneUlp";
  static final String THRESHOLD_MOVED = "exp.thresholdMoved";
  /** ln2HI[0] one ulp up, for {@link #CONSTANT_OFF_BY_ONE_ULP}. */
  private static final double LN2HI_0_UP = Double.longBitsToDouble(0x3fe62e42_fee00001L);
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  private static final boolean DROPPING_CORRECTION = Faults.is(DROP_CORRECTION);
  private static final double LN2HI_0_USED =
      Faults.is(CONSTANT_OFF_BY_ONE_ULP) ? LN2HI_0_UP : LN2HI_0;
  private static final int K_MIN_USED =
      Faults.is(THRESHOLD_MOVED) ? K_MIN_NORMAL - 1 : K_MIN_NORMAL;

  private Exp() {}

  /**
   * exp with the rare lanes finished per lane. {@code live} is null for every lane; otherwise only
   * live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an inactive lane
   * never takes the per-lane finish.
   */
  static DoubleVector exp(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, true);
  }

  /**
   * exp with every branch of e_exp.c computed in every lane and blended. {@code live} is as for
   * {@link #exp(DoubleVector, VectorMask)}.
   */
  static DoubleVector expBlended(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, false);
  }

  /**
   * Both arms in one body, so they share every line but the rare paths; {@code finish} is a
   * constant at each call above, and once inlined the other arm's code folds away.
   */
  private static DoubleVector exp(DoubleVector x, VectorMask<Double> live, boolean finish) {
    VectorSpecies<Double> species = x.species();
    LongVector bits = Lanes.bits(x);
    // Lines 112-114: xsb, the sign bit, and |x|, whose high word hx the branches compare.
    VectorMask<Double> negative = bits.compare(VectorOperators.LT, 0L).cast(species);
    VectorMask<Long> negativeK = negative.cast(bits.species());
    DoubleVector ax = x.lanewise(VectorOperators.ABS);

    // Every hx compare, first: C2 stops inlining calls once a method's graph is large
    // (NodeCountInliningCutoff), and a refused call boxes its vectors, so the calls into Lanes
    // come before the arithmetic. Lines 117, 118, 128, 129 and 139, and the int species for k.
    VectorMask<Double> filter = Lanes.hiAtLeast(ax, HX_FILTER);
    VectorMask<Double> reduced = Lanes.hiAbove(ax, HX_HALF_LN2);
    VectorMask<Double> near = Lanes.hiBelow(ax, HX_THREE_HALVES_LN2);
    VectorMask<Double> isTiny = Lanes.hiBelow(ax, HX_TINY);
    VectorMask<Double> nonFiniteLane = finish ? null : Lanes.hiAtLeast(ax, HX_NON_FINITE);
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);

    // Argument reduction, lines 128-137, for |x| > 0.5 ln2.
    // Far form: k = (int)(invln2*x+halF[xsb]); t = k; hi = x - t*ln2HI[0]; lo = t*ln2LO[0].
    // k goes through int lanes, as the C's int k does: x86 C2 lowers D2L and L2D only with
    // AVX512DQ, and without it (on AVX2 hosts) they run in Java and box, while D2I, I2D and I2L
    // are vector instructions on every AVX2 host. D2I truncates toward zero like the C's (int);
    // a NaN gives 0 and a huge |x| saturates, but those are filter lanes, whose k is never used.
    // I2D is exact, so t = k as in the C. The int vector has the same lane count at half the
    // bits, so each conversion is part 0.
    DoubleVector halF = DoubleVector.broadcast(species, 0.5).blend(-0.5, negative);
    IntVector kInt =
        (IntVector) x.mul(INVLN2).add(halF).convertShape(VectorOperators.D2I, intSpecies, 0);
    DoubleVector t = (DoubleVector) kInt.convertShape(VectorOperators.I2D, species, 0);
    LongVector kFar = (LongVector) kInt.convertShape(VectorOperators.I2L, bits.species(), 0);
    DoubleVector hiFar = x.sub(t.mul(LN2HI_0_USED));
    DoubleVector loFar = t.mul(LN2LO_0);
    // Near form: hi = x-ln2HI[xsb]; lo = ln2LO[xsb]; k = 1-xsb-xsb.
    DoubleVector hiNear = x.sub(DoubleVector.broadcast(species, LN2HI_0).blend(LN2HI_1, negative));
    DoubleVector loNear = DoubleVector.broadcast(species, LN2LO_0).blend(LN2LO_1, negative);
    LongVector kNear = LongVector.broadcast(bits.species(), 1L).blend(-1L, negativeK);
    DoubleVector hi = hiFar.blend(hiNear, near);
    DoubleVector lo = loFar.blend(loNear, near);
    // k = 0 for the lanes that are not reduced (line 142's else).
    LongVector k = kFar.blend(kNear, near.cast(bits.species()))
        .blend(0L, reduced.not().cast(bits.species()));
    // x = hi - lo for the reduced lanes; the others keep x.
    DoubleVector r = x.blend(hi.sub(lo), reduced);

    // Lines 145-146: t = x*x; c = x - t*(P1+t*(P2+t*(P3+t*(P4+t*P5)))).
    DoubleVector tt = r.mul(r);
    DoubleVector c = r.sub(tt.mul(tt.mul(tt.mul(tt.mul(tt.mul(P5).add(P4)).add(P3)).add(P2))
        .add(P1)));
    DoubleVector rc = r.mul(c);
    // k == 0: one-((x*c)/(c-2.0)-x).
    DoubleVector primary = DoubleVector.broadcast(species, ONE).sub(rc.div(c.sub(2.0)).sub(r));
    // Otherwise: y = one-((lo-(x*c)/(2.0-c))-hi).
    DoubleVector loTerm = DROPPING_CORRECTION ? DoubleVector.zero(species) : lo;
    DoubleVector y = DoubleVector.broadcast(species, ONE)
        .sub(loTerm.sub(rc.div(DoubleVector.broadcast(species, 2.0).sub(c))).sub(hi));

    VectorMask<Double> kIsZero = k.compare(VectorOperators.EQ, 0L).cast(species);
    VectorMask<Double> kBelowNormal = k.compare(VectorOperators.LT, K_MIN_USED).cast(species);
    // Lines 139-141: |x| < 2**-28 returns one+x.
    DoubleVector tiny = DoubleVector.broadcast(species, ONE).add(x);

    // Scale back, lines 149-155: k >= -1021 adds k to y's exponent; below, k + 1000, then the
    // product with twom1000 (blended), or a per-lane finish.
    // The exponent add is Lanes.addExponent written out, for the reason above.
    LongVector yBits = (LongVector) y.viewAsIntegralLanes();
    DoubleVector result = (DoubleVector) yBits.add(k.lanewise(VectorOperators.LSHL, 52))
        .viewAsFloatingLanes();
    if (!finish) {
      DoubleVector subnormal = ((DoubleVector) yBits
          .add(k.add(1000).lanewise(VectorOperators.LSHL, 52)).viewAsFloatingLanes())
          .mul(TWOM1000);
      result = result.blend(subnormal, kBelowNormal);
    }
    result = result.blend(primary, kIsZero).blend(tiny, isTiny);
    if (!finish) {
      // The filter, lines 117-125, in the C's order of precedence (the last blend wins).
      result = result.blend(HUGE * HUGE, filter.and(x.compare(VectorOperators.GT, O_THRESHOLD)));
      result = result.blend(
          TWOM1000 * TWOM1000, filter.and(x.compare(VectorOperators.LT, U_THRESHOLD)));
      // Non-finite: x + x for a NaN, x for +inf, 0.0 for -inf.
      DoubleVector nonFinite =
          x.blend(0.0, negative).blend(x.add(x), x.test(VectorOperators.IS_NAN));
      result = result.blend(nonFinite, nonFiniteLane);
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (finish) {
      VectorMask<Double> rare = filter.or(kBelowNormal);
      if (live != null) {
        rare = rare.and(live);
      }
      if (rare.anyTrue()) {
        // The per-lane finish is written out here, with no call into vfdlibm, for the reason the
        // Lanes calls come first: a vfdlibm call this late is refused (NodeCountInliningCutoff),
        // and a refused call takes x, result and rare as heap objects, boxed on every vector,
        // rare or not. The Vector API's own methods used here are inlined late instead. It
        // visits only the set lanes of rare, through its bits, and moves no lane through an
        // array: lane and withLane are lane extract and insert. C2 intrinsifies them at a
        // variable lane index for the fixed shapes only; at SPECIES_MAX this loop still boxes.
        long rareLanes = rare.toLong();
        while (rareLanes != 0) {
          int lane = Long.numberOfTrailingZeros(rareLanes);
          rareLanes &= rareLanes - 1;
          result = result.withLane(lane, StrictMath.exp(x.lane(lane)));
        }
      }
    }
    return result;
  }
}
