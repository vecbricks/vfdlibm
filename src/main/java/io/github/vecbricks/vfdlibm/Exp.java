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
 * {@code P1..P5}, and scale back by {@code 2^k}, with no fused multiply-add and no reassociation.
 *
 * <p>The C's branches on {@code |x|} become lane masks, and where the C has three forms for the
 * common lanes this body has one, proven to return the same double ({@code PROOFS.md}, exp):
 * every lane takes the far reduction and the general tail, {@code y = one-((lo-(x*c)/(2.0-c))-hi)}.
 * In the near band, {@code 0.5*ln2 < |x| < 1.5*ln2}, the far form gives the near form's {@code k},
 * {@code hi} and {@code lo} exactly (L2); a lane that is not reduced gets {@code k = 0}, forced by
 * the C's high-word test, so {@code hi = x} and {@code lo = +0}, and the tail is the C's {@code k
 * == 0} formula (L1). The high-word tests are single 64-bit compares of {@code |x|}'s bits (L3)
 * and {@code halF[xsb]} is 0.5 with {@code x}'s sign bit (L5). The {@code |x| < 2^-28} lanes keep
 * the C's {@code one + x}, which no fold reproduces (L4). Two arms differ in the C's rare paths,
 * the filter on {@code |x| >= 709.78} (NaN, the infinities, overflow, underflow and the band the
 * filter lets through) and the subnormal scale-back ({@code k < -1021}):
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
 * the JDK changes. Those figures predate the folds above, which cut the common path C2 emits
 * from 77 instructions with two divisions to 52 with one at 512 bits on AVX-512, and from 107 to
 * 64 at 256 bits under {@code -XX:UseAVX=2} ({@code bench/asm/README.md}); no timing of the
 * folded body has been taken yet.
 *
 * <p>{@link #expCore} and {@link #expRare} split the finish arm into its branch-free part and its
 * rare-lane mask, for {@code ExpBenchmark}'s experiment with two vectors per loop iteration.
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
  /** {@code |x| < 1.5 ln2}: the C's near band, which the far form covers here (PROOFS.md, L2). */
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

  /** The raw bits of 0.5, halF[0]; halF[1] is the same with the sign bit set. */
  private static final long HALF_BITS = 0x3fe00000_00000000L;

  // The arms of the shared body; each call site passes a constant, and the others fold away.
  private static final int FINISH = 0;
  private static final int BLENDED = 1;
  private static final int CORE = 2;

  private Exp() {}

  /**
   * exp with the rare lanes finished per lane. {@code live} is null for every lane; otherwise only
   * live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an inactive lane
   * never takes the per-lane finish.
   */
  static DoubleVector exp(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, FINISH);
  }

  /**
   * exp with every branch of e_exp.c computed in every lane and blended. {@code live} is as for
   * {@link #exp(DoubleVector, VectorMask)}.
   */
  static DoubleVector expBlended(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, BLENDED);
  }

  /**
   * The finish arm's branch-free part, for an experiment in emitting two vectors per loop
   * iteration ({@code ExpBenchmark.finishPairBoxed256}): {@code StrictMath.exp} in every lane that
   * {@link #expRare} leaves clear, and an unspecified value in the lanes it sets, which the caller
   * finishes per lane with {@code StrictMath.exp}.
   */
  static DoubleVector expCore(DoubleVector x) {
    return exp(x, null, CORE);
  }

  /**
   * The lanes {@link #expCore} leaves to a per-lane finish: e_exp.c's filter ({@code |x| >=
   * 709.78}, by the high word) and the subnormal scale-back ({@code k < -1021}). Each expression
   * is the body's own, so once both are inlined beside {@link #expCore} C2 computes them once.
   */
  static VectorMask<Double> expRare(DoubleVector x) {
    VectorSpecies<Double> species = x.species();
    LongVector bits = Lanes.bits(x);
    LongVector absBits = Lanes.absBits(bits);
    VectorMask<Double> filter = Lanes.hiAtLeast(absBits, HX_FILTER).cast(species);
    VectorMask<Double> reduced = Lanes.hiAbove(absBits, HX_HALF_LN2).cast(species);
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    DoubleVector halF = Lanes.fromBits(bits.and(Long.MIN_VALUE).or(HALF_BITS));
    DoubleVector kArg = DoubleVector.zero(species).blend(x.mul(INVLN2).add(halF), reduced);
    IntVector kInt = (IntVector) kArg.convertShape(VectorOperators.D2I, intSpecies, 0);
    LongVector k = (LongVector) kInt.convertShape(VectorOperators.I2L, bits.species(), 0);
    return filter.or(k.compare(VectorOperators.LT, K_MIN_USED).cast(species));
  }

  /**
   * Every arm in one body, so they share every line but the rare paths; {@code arm} is a constant
   * at each call above, and once inlined the other arms' code folds away.
   */
  private static DoubleVector exp(DoubleVector x, VectorMask<Double> live, int arm) {
    VectorSpecies<Double> species = x.species();
    LongVector bits = Lanes.bits(x);
    // Lines 112-114: xsb, the sign bit, and hx, the high word of |x|, here the bits of |x| with
    // the sign cleared, which the long-form compares read (PROOFS.md, L3).
    LongVector absBits = Lanes.absBits(bits);

    // Every hx compare, first: C2 stops inlining calls once a method's graph is large
    // (NodeCountInliningCutoff), and a refused call boxes its vectors, so the calls into Lanes
    // come before the arithmetic. Lines 117, 118, 128, 139, and the int species for k. Line 129's
    // near band needs no test of its own (L2).
    VectorMask<Double> filter = Lanes.hiAtLeast(absBits, HX_FILTER).cast(species);
    VectorMask<Double> reduced = Lanes.hiAbove(absBits, HX_HALF_LN2).cast(species);
    VectorMask<Double> isTiny = Lanes.hiBelow(absBits, HX_TINY).cast(species);
    VectorMask<Double> nonFiniteLane =
        arm == BLENDED ? Lanes.hiAtLeast(absBits, HX_NON_FINITE).cast(species) : null;
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // halF[xsb]: 0.5 with x's sign bit, the same double the C's table gives.
    DoubleVector halF = Lanes.fromBits(bits.and(Long.MIN_VALUE).or(HALF_BITS));

    // Argument reduction, lines 128-142, in the far form for every lane:
    // k = (int)(invln2*x+halF[xsb]); t = k; hi = x - t*ln2HI[0]; lo = t*ln2LO[0].
    // - The near band, 0.5 ln2 < |x| < 1.5 ln2, gets k = +-1 from it, and the near form's hi, lo
    //   and k exactly (PROOFS.md, L2).
    // - A lane that is not reduced (line 128's test false) gets k = 0, forced by that test, not
    //   by the formula: the sum is replaced by 0 before the cast, so t = +0, hi = x, lo = +0 and
    //   x = hi - lo is x itself, and the general tail below is the C's k == 0 formula (L1).
    // k goes through int lanes, as the C's int k does: x86 C2 lowers D2L and L2D only with
    // AVX512DQ, and without it (on AVX2 hosts) they run in Java and box, while D2I, I2D and I2L
    // are vector instructions on every AVX2 host. D2I truncates toward zero like the C's (int);
    // a NaN gives 0 and a huge |x| saturates, but those are filter lanes, whose k is never used.
    // I2D is exact, so t = k as in the C. The int vector has the same lane count at half the
    // bits, so each conversion is part 0.
    DoubleVector kArg = DoubleVector.zero(species).blend(x.mul(INVLN2).add(halF), reduced);
    IntVector kInt = (IntVector) kArg.convertShape(VectorOperators.D2I, intSpecies, 0);
    DoubleVector t = (DoubleVector) kInt.convertShape(VectorOperators.I2D, species, 0);
    LongVector k = (LongVector) kInt.convertShape(VectorOperators.I2L, bits.species(), 0);
    DoubleVector hi = x.sub(t.mul(LN2HI_0_USED));
    DoubleVector lo = t.mul(LN2LO_0);
    DoubleVector r = hi.sub(lo);

    // Lines 145-146: t = x*x; c = x - t*(P1+t*(P2+t*(P3+t*(P4+t*P5)))).
    DoubleVector tt = r.mul(r);
    DoubleVector c = r.sub(tt.mul(tt.mul(tt.mul(tt.mul(tt.mul(P5).add(P4)).add(P3)).add(P2))
        .add(P1)));
    // Line 148, y = one-((lo-(x*c)/(2.0-c))-hi), for every lane; at k == 0 it is line 147's
    // one-((x*c)/(c-2.0)-x) (L1).
    DoubleVector loTerm = DROPPING_CORRECTION ? DoubleVector.zero(species) : lo;
    DoubleVector y = DoubleVector.broadcast(species, ONE)
        .sub(loTerm.sub(r.mul(c).div(DoubleVector.broadcast(species, 2.0).sub(c))).sub(hi));

    VectorMask<Double> kBelowNormal = k.compare(VectorOperators.LT, K_MIN_USED).cast(species);
    // Lines 139-141: |x| < 2**-28 returns one+x. Not folded: the tail differs there (L4).
    DoubleVector tiny = DoubleVector.broadcast(species, ONE).add(x);

    // Scale back, lines 149-155: k >= -1021 adds k to y's exponent (k == 0 adds nothing); below,
    // k + 1000, then the product with twom1000 (blended), or a per-lane finish.
    // The exponent add is Lanes.addExponent written out, for the reason above.
    LongVector yBits = (LongVector) y.viewAsIntegralLanes();
    DoubleVector result = (DoubleVector) yBits.add(k.lanewise(VectorOperators.LSHL, 52))
        .viewAsFloatingLanes();
    if (arm == BLENDED) {
      DoubleVector subnormal = ((DoubleVector) yBits
          .add(k.add(1000).lanewise(VectorOperators.LSHL, 52)).viewAsFloatingLanes())
          .mul(TWOM1000);
      result = result.blend(subnormal, kBelowNormal);
    }
    result = result.blend(tiny, isTiny);
    if (arm == BLENDED) {
      // The filter, lines 117-125, in the C's order of precedence (the last blend wins).
      result = result.blend(HUGE * HUGE, filter.and(x.compare(VectorOperators.GT, O_THRESHOLD)));
      result = result.blend(
          TWOM1000 * TWOM1000, filter.and(x.compare(VectorOperators.LT, U_THRESHOLD)));
      // Non-finite: x + x for a NaN, x for +inf, 0.0 for -inf.
      VectorMask<Double> negative = bits.compare(VectorOperators.LT, 0L).cast(species);
      DoubleVector nonFinite =
          x.blend(0.0, negative).blend(x.add(x), x.test(VectorOperators.IS_NAN));
      result = result.blend(nonFinite, nonFiniteLane);
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (arm == FINISH) {
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
