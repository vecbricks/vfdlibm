/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_log10.c (reference/fdlibm-5.3/e_log10.c), which carries this
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
 * log10 over lanes, ported from fdlibm 5.3's {@code __ieee754_log10} in {@code
 * reference/fdlibm-5.3/e_log10.c}; every lane is bit-identical to {@code StrictMath.log10}.
 *
 * <p>The method, from the C: read {@code k} from the high word, set {@code i = 1} when {@code k <
 * 0}, rescale {@code x} by rewriting its high word's exponent so that it lies in {@code [1, 2)}
 * ({@code k >= 0}) or {@code [0.5, 1)} ({@code k < 0}), and with {@code y = k + i} return {@code
 * (y*log10_2lo + ivln10*log(x)) + y*log10_2hi}, {@code log} being e_log.c's {@code
 * __ieee754_log} of the rescaled {@code x}. Every operation is the C's, in the C's order, with no
 * fused multiply-add and no reassociation, so no lemma of {@code PROOFS.md} is needed.
 *
 * <p>The {@code log} of the rescaled {@code x} is {@link Log#logCore}, the only entry into {@code
 * Log} this class uses: e_log.c's common path in every lane, which is the C's result for a
 * positive normal finite argument. Every rescaled lane is one, rare lanes included, since its
 * high word is {@code (hx&0x000fffff)|(0x3fe00000 or 0x3ff00000)}: a positive double in {@code
 * [0.5, 2)}. So none of Log's rare paths is needed, and none is in the body.
 *
 * <p>The C's {@code int} words stay in int lanes, as in {@code Log}: {@code hx}, {@code k} and
 * {@code i} are an {@link IntVector} with the double vector's lane count at half its bits, every
 * test on them is the C's signed {@code int} compare, and {@code y = (double)(k+i)} is an exact
 * {@code I2D}, which every AVX2 host lowers.
 *
 * <p>C2 stops inlining once the compiled graph passes {@code NodeCountInliningCutoff}, and a
 * refused call passes its vectors boxed ({@code SKILLS.md}, "What the log10 port taught"). So
 * every call this body makes comes first: {@code Lanes.intSpecies} and {@code Lanes.hiInt}, then
 * {@link Log#logCore} as soon as its argument exists, whose own calls into {@code Lanes} come
 * first in its body too. The high-word write-back, {@code Lanes.withHi}, is written out here and
 * in Log's body: nested this way, a call to it in Log was refused and every vector boxed. After
 * {@link Log#logCore} there are only Vector API operations and the per-lane finish written out
 * in the body.
 *
 * <p>Two arms differ in the C's rare paths, the lanes with {@code hx < 0x00100000} ({@code x <=
 * 0}, a subnormal, a negative NaN) or {@code hx >= 0x7ff00000} (+inf, a positive NaN):
 *
 * <ul>
 *   <li>{@link #log10} finishes those lanes per lane with {@code StrictMath.log10} after the vector
 *       body, behind one {@code anyTrue} a vector, reading and writing the lanes in place with
 *       {@code lane} and {@code withLane}; on ordinary data that code never runs.
 *   <li>{@link #log10Blended} computes them in every lane and blends them, with no branch: the
 *       subnormal lanes are scaled by {@code 2^54} before the high word is read, as in the C.
 * </ul>
 *
 * <p>Both are exact. {@code VFdlibm.log10} uses the first, as {@code VFdlibm.log} does, until the
 * benchmark hosts measure the two ({@code HARDWARE.md}); the second is kept so the choice can be
 * re-measured.
 */
final class Log10 {

  // The constants of e_log10.c, lines 54-57, as its source writes them; Log10Test checks they
  // parse to its hex.
  static final double TWO54 = 1.80143985094819840000e+16; // 0x43500000, 0x00000000
  static final double IVLN10 = 4.34294481903251816668e-01; // 0x3FDBCB7B, 0x1526E50E
  static final double LOG10_2HI = 3.01029995663611771306e-01; // 0x3FD34413, 0x509F6000
  static final double LOG10_2LO = 3.69423907715893078616e-13; // 0x3D59FEF3, 0x11F12B36
  static final double ZERO = 0.0;

  // The words e_log10.c compares hx against, as signed ints, as the C does.
  /** {@code hx < 0x00100000}: {@code x < 2^-1022}, so zero, negative or subnormal (line 76). */
  static final int HX_MIN_NORMAL = 0x00100000;
  /** {@code hx >= 0x7ff00000}: +inf or a NaN with the sign bit clear (line 83). */
  static final int HX_NON_FINITE = 0x7ff00000;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String DROP_CORRECTION = "log10.dropCorrection";
  static final String CONSTANT_OFF_BY_ONE_ULP = "log10.constantOffByOneUlp";
  static final String THRESHOLD_MOVED = "log10.thresholdMoved";
  /** ivln10 one ulp up, for {@link #CONSTANT_OFF_BY_ONE_ULP}. */
  private static final double IVLN10_UP = Double.longBitsToDouble(0x3FDBCB7B_1526E50FL);
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /** Drops line 89's {@code y*log10_2lo}. */
  private static final boolean DROPPING_CORRECTION = Faults.is(DROP_CORRECTION);
  private static final double IVLN10_USED =
      Faults.is(CONSTANT_OFF_BY_ONE_ULP) ? IVLN10_UP : IVLN10;
  /**
   * Moves line 85's split from {@code k < 0} to {@code k < 1}: the lanes of {@code [1, 2)} are
   * then rescaled to {@code [0.5, 1)} with {@code y = 1}, whose sum cancels and rounds otherwise.
   */
  private static final boolean MOVING_THRESHOLD = Faults.is(THRESHOLD_MOVED);

  /** How the rare lanes are computed: finished per lane or blended. */
  private static final int FINISH = 0;
  private static final int BLENDED = 1;

  private Log10() {}

  /**
   * log10 with the rare lanes finished per lane. {@code live} is null for every lane; otherwise
   * only live lanes are computed, inactive lanes return {@code x}'s lane untouched, and an
   * inactive lane never takes the per-lane finish.
   */
  static DoubleVector log10(DoubleVector x, VectorMask<Double> live) {
    return log10(x, live, FINISH);
  }

  /**
   * log10 with every branch of e_log10.c computed in every lane and blended. {@code live} is as
   * for {@link #log10(DoubleVector, VectorMask)}.
   */
  static DoubleVector log10Blended(DoubleVector x, VectorMask<Double> live) {
    return log10(x, live, BLENDED);
  }

  /**
   * Both arms in one body, so they share every line but the rare paths; {@code arm} is a constant
   * at each call above, and once inlined the other arm's code folds away.
   */
  private static DoubleVector log10(DoubleVector x, VectorMask<Double> live, int arm) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);

    // Line 80, blended arm only: a subnormal is scaled by two54, and k starts at -54.
    DoubleVector xs = x;
    VectorMask<Double> subnormal = null;
    if (arm == BLENDED) {
      subnormal = x.compare(VectorOperators.GT, ZERO)
          .and(x.compare(VectorOperators.LT, Double.MIN_NORMAL));
      xs = x.blend(x.mul(TWO54), subnormal);
    }
    // Lines 72 and 81: hx = __HI(x), the signed high word, of the scaled x where it was scaled.
    IntVector hx = Lanes.hiInt(xs, intSpecies);
    // Line 84: k += (hx>>20)-1023.
    IntVector k = hx.lanewise(VectorOperators.ASHR, 20).sub(1023);
    if (arm == BLENDED) {
      k = k.add(-54, subnormal.cast(intSpecies));
    }
    // Line 85: i = ((unsigned)k&0x80000000)>>31, which is 1 when k < 0.
    IntVector i = MOVING_THRESHOLD
        ? k.sub(1).lanewise(VectorOperators.LSHR, 31)
        : k.lanewise(VectorOperators.LSHR, 31);
    // Lines 86 and 88: hx = (hx&0x000fffff)|((0x3ff-i)<<20); __HI(x) = hx.
    IntVector hxn = hx.and(0x000fffff)
        .or(IntVector.broadcast(intSpecies, 0x3ff).sub(i).lanewise(VectorOperators.LSHL, 20));
    // __HI(x) = hx, Lanes.withHi written out, as in Log and Log1p, so no call is left here.
    LongVector xsBits = (LongVector) xs.viewAsIntegralLanes();
    LongVector hxnBits =
        (LongVector) hxn.convertShape(VectorOperators.I2L, xsBits.species(), 0);
    DoubleVector xn = (DoubleVector) xsBits.and(Lanes.LOW_WORD)
        .or(hxnBits.lanewise(VectorOperators.LSHL, 32)).viewAsFloatingLanes();

    // Line 89's __ieee754_log(x), of the rescaled x: Log's common path, which is the C's result
    // for every lane, each being a positive normal double in [0.5, 2). It is called as soon as
    // its argument exists: Log's body makes calls into Lanes of its own, and each node parsed
    // before them brings the graph nearer the cutoff, past which they are refused (SKILLS.md,
    // "What the log10 port taught").
    DoubleVector logx = Log.logCore(xn);

    // Line 87: y = (double)(k+i), an exact I2D.
    DoubleVector y = (DoubleVector) k.add(i).convertShape(VectorOperators.I2D, species, 0);
    // The lanes the C returns early, from its own tests on hx. Lines 76 and 83: the common lanes
    // have 0x00100000 <= hx < 0x7ff00000, for a signed hx, so every NaN lane, of either sign, is
    // rare, as in the C. In the blended arm hx is the scaled x's, so a subnormal is common there.
    VectorMask<Double> common = hx.compare(VectorOperators.GE, HX_MIN_NORMAL)
        .and(hx.compare(VectorOperators.LT, HX_NON_FINITE)).cast(species);

    // Lines 89-90: z = y*log10_2lo + ivln10*log(x); return z+y*log10_2hi. The product
    // ivln10*log(x) is written log(x)*ivln10, the same single rounding of the same exact product.
    DoubleVector yLo = DROPPING_CORRECTION ? DoubleVector.zero(species) : y.mul(LOG10_2LO);
    DoubleVector z = yLo.add(logx.mul(IVLN10_USED));
    DoubleVector result = z.add(y.mul(LOG10_2HI));

    if (arm == BLENDED) {
      // The rare paths, in the C's order of precedence (the last blend wins), each from the C's
      // own test. Line 83: x+x for hx >= 0x7ff00000, +inf and a positive NaN. Line 79: (x-x)/zero
      // for hx < 0, which covers -inf and a negative NaN. Line 78: -two54/zero, -inf, for +-0.
      VectorMask<Double> positiveNonFinite =
          hx.compare(VectorOperators.GE, HX_NON_FINITE).cast(species);
      VectorMask<Double> negative = hx.compare(VectorOperators.LT, 0).cast(species);
      result = result.blend(x.add(x), positiveNonFinite);
      result = result.blend(x.sub(x).div(ZERO), negative);
      result = result.blend(-TWO54 / ZERO, x.compare(VectorOperators.EQ, ZERO));
    }
    if (live != null) {
      result = x.blend(result, live);
    }
    if (arm == FINISH) {
      VectorMask<Double> rare = common.not();
      if (live != null) {
        rare = rare.and(live);
      }
      // The finish is written out here, with no call that takes a vector, as Log's is: lane and
      // withLane are lane extract and insert, which C2 intrinsifies for the fixed shapes, and the
      // only call is StrictMath.log10 on a double. rare is already and-ed with live, so an
      // inactive lane is never rewritten.
      if (rare.anyTrue()) {
        long rareLanes = rare.toLong();
        while (rareLanes != 0) {
          int lane = Long.numberOfTrailingZeros(rareLanes);
          rareLanes &= rareLanes - 1;
          result = result.withLane(lane, StrictMath.log10(x.lane(lane)));
        }
      }
    }
    return result;
  }
}
