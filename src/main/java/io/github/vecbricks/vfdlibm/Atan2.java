/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_atan2.c (reference/fdlibm-5.3/e_atan2.c), which carries this
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
 * atan2 over lanes, ported from fdlibm 5.3's {@code __ieee754_atan2} in {@code
 * reference/fdlibm-5.3/e_atan2.c}; every lane is bit-identical to {@code StrictMath.atan2}, except
 * that a lane of two NaNs with different bits is one of the two, quieted, as {@code PROOFS.md}'s
 * "The contract of a function of two arguments" says. The first argument is {@code y}, as in the
 * C and in {@code Math.atan2(y, x)}; a lane that is not live keeps {@code y}'s bits.
 *
 * <p>The lanes, in the C's order: a NaN in either argument returns {@code x+y} (lines 71-73); for
 * {@code y = +-0} the result is {@code y} or {@code +-pi} by the signs (lines 78-85); for {@code
 * x = +-0}, {@code +-pi/2} (line 87); for {@code x = +-inf}, the diagonals {@code +-pi/4} and
 * {@code +-3pi/4}, or {@code +-0} and {@code +-pi} (lines 90-106); for {@code y = +-inf},
 * {@code +-pi/2} (line 108). The rest take {@code k = (iy-ix)>>20}: above 60 the result is
 * {@code pi_o_2+0.5*pi_lo}, for a negative {@code x} below -60 it is {@code 0.0}, and otherwise it
 * is {@code atan(fabs(y/x))}, then moved to the quadrant by the signs: {@code z}, {@code -z},
 * {@code pi-(z-pi_lo)} or {@code (z-pi_lo)-pi}.
 *
 * <p>{@code x = 1.0} is the C's {@code atan(y)} (line 74), taken before every test but the NaN.
 * It is not a lane of its own here: it takes the general path, whose result is {@code atan(y)}
 * (L14, {@code PROOFS.md}), with the {@code k > 60} shortcut off as the C's early return has it.
 * The core is {@link Atan#atan}, called on {@code t = |y/x|} where {@code t < 2^66}: an inactive
 * lane of its mask comes back untouched and never takes its per-lane finish. The lanes of {@code
 * t >= 2^66} that the shortcuts of {@code k} leave to atan ({@code x = 1.0} beside a huge {@code
 * y}, or a subnormal {@code x} whose words understate the gap) take the constant {@code
 * atanhi[3]+atanlo[3]}, which is atan's value there, in the vector. Nothing in this port calls
 * {@code StrictMath}.
 *
 * <p>Every special lane is computed in the vector and blended over the general result, from the
 * last of the C's tests to the first, so the first that matches wins as in the C: no value of a
 * lane the blend overwrites is read (L13, {@code PROOFS.md}'s section "Blends"). The core's mask
 * leaves those lanes out, so that no infinity or NaN of theirs reaches its finish. Two arms
 * share the body below: {@code blended} computes the special lanes' blends in every vector,
 * {@code guarded} only in a vector with a live special lane, which is the one {@code VFdlibm}
 * calls, at every species. atan2 has no finish arm, so rule 1 of issue #30 (a tie goes to the
 * finish arm) does not pick between the two (the issue's corrected comment,
 * https://github.com/vecbricks/vfdlibm/issues/30#issuecomment-7085437272). On {@code
 * bench/results/atan2-xeon8573c-2026-11-22.txt} and {@code atan2-epyc7763-2026-11-22.txt}, one
 * run each, the guarded arm reads lower on the slower input at every species on the EPYC 7763;
 * on the Xeon 8573C the two arms are a tie at every species (guarded lower by 1.3% at 256 bits,
 * blended lower by 3.3% at the preferred species and 1.3% at {@code SPECIES_MAX}, all inside
 * 1.3x), which waits for the 8573C's second run. Guarded is the default at every species by the
 * owner's decision of 22 November.
 */
final class Atan2 {

  // The constants of e_atan2.c, as its source writes them; Atan2Test checks the words.
  static final double TINY = 1.0e-300;
  static final double ZERO = 0.0;
  static final double PI_O_4 = 7.8539816339744827900E-01;
  static final double PI_O_2 = 1.5707963267948965580E+00;
  static final double PI = 3.1415926535897931160E+00;
  static final double PI_LO = 1.2246467991473531772E-16;

  // What the C returns by expression, evaluated by the same operations: `pi+tiny` is not assumed
  // to be `pi`, nor `3.0*pi_o_4+tiny` to be a constant of its own.
  static final double PI_P = PI + TINY;
  static final double N_PI = -PI - TINY;
  static final double PI_O_2_P = PI_O_2 + TINY;
  static final double N_PI_O_2 = -PI_O_2 - TINY;
  static final double PI_O_4_P = PI_O_4 + TINY;
  static final double N_PI_O_4 = -PI_O_4 - TINY;
  static final double THREE_PI_O_4_P = 3.0 * PI_O_4 + TINY;
  static final double N_THREE_PI_O_4 = -3.0 * PI_O_4 - TINY;
  /**
   * What the C's atan returns from {@code 2^66} up, for a positive argument: {@code
   * atanhi[3]+atanlo[3]} (s_atan.c line 100). The general path keeps {@code |y/x| >= 2^66} in the
   * lanes of {@code x = 1.0} beside a huge {@code y}, and where a subnormal {@code x} makes {@code
   * k} understate the gap of the exponents.
   */
  static final double ATAN_BIG = Atan.ATAN_HI[3] + Atan.ATAN_LO[3];
  /** {@code 2^66}, as s_atan.c's {@code ix>=0x44100000}: the double of that high word. */
  static final double ATAN_BIG_FROM = Lanes.fromHi(Atan.HX_BIG);
  /** The shortcut for {@code |y/x| > 2^60}: {@code z=pi_o_2+0.5*pi_lo} (line 112). */
  static final double Z_HIGH = PI_O_2 + 0.5 * PI_LO;

  /** The {@code k} the C compares against: {@code k > 60} (line 112), {@code k < -60} (113). */
  static final int K_MAX = 60;
  static final int K_MIN = -60;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String K_HIGH_MOVED = "atan2.kHighMoved";
  static final String K_LOW_MOVED = "atan2.kLowMoved";
  static final String LOW_SHORTCUT_FOR_POSITIVE_X = "atan2.lowShortcutForPositiveX";
  static final String INF_ORDER_SWAPPED = "atan2.infOrderSwapped";
  static final String DROP_PI_LO = "atan2.dropPiLo";
  static final String ZERO_SIGN_OF_X = "atan2.zeroSignOfX";
  static final String DIAGONAL_INF = "atan2.diagonalInf";
  static final String NO_SIGN_FLIP = "atan2.noSignFlip";
  // Read once into constants, so the body holds no call and an unplanted fault folds away. The two
  // thresholds are moved by ten: the shortcuts give the bits of atan(|y/x|) wherever they are
  // taken, so moved by one they change no result (PROOFS.md, "atan2"), and a fault must be seen.
  private static final int K_MAX_USED = Faults.is(K_HIGH_MOVED) ? K_MAX - 10 : K_MAX;
  private static final int K_MIN_USED = Faults.is(K_LOW_MOVED) ? K_MIN + 10 : K_MIN;
  private static final boolean LOW_FOR_ANY_X = Faults.is(LOW_SHORTCUT_FOR_POSITIVE_X);
  private static final boolean INF_SWAPPED = Faults.is(INF_ORDER_SWAPPED);
  private static final boolean DROPPING_PI_LO = Faults.is(DROP_PI_LO);
  private static final boolean ZERO_BY_VALUE = Faults.is(ZERO_SIGN_OF_X);
  private static final boolean ONE_DIAGONAL = Faults.is(DIAGONAL_INF);
  private static final boolean KEEPING_SIGN = Faults.is(NO_SIGN_FLIP);

  /** How the special lanes are reached: blended in every vector, or behind a test. */
  private static final int BLEND = 0;
  private static final int GUARDED = 1;

  private Atan2() {}

  /**
   * atan2 as {@code VFdlibm.atan2} computes it, the guarded arm. {@code live} is null for every
   * lane; otherwise only live lanes are computed, inactive lanes return {@code y}'s lane
   * untouched, and an inactive lane never sends the vector down a rare path.
   */
  static DoubleVector atan2(DoubleVector y, DoubleVector x, VectorMask<Double> live) {
    return atan2(y, x, live, GUARDED);
  }

  /** atan2 with the special lanes' blends computed in every vector; {@code live} as above. */
  static DoubleVector atan2Blended(DoubleVector y, DoubleVector x, VectorMask<Double> live) {
    return atan2(y, x, live, BLEND);
  }

  /** atan2 with the special lanes' blends behind one test; {@code live} as above. */
  static DoubleVector atan2Guarded(DoubleVector y, DoubleVector x, VectorMask<Double> live) {
    return atan2(y, x, live, GUARDED);
  }

  /**
   * The two arms in one body; {@code mode} is a constant at each call above. The call into atan
   * comes second, right after the quotient, while the compiled graph is small: C2 stops inlining
   * once a method's graph is large (NodeCountInliningCutoff), and a refused call boxes its
   * vectors. After it there are only Vector API operations.
   */
  private static DoubleVector atan2(
      DoubleVector y, DoubleVector x, VectorMask<Double> live, int mode) {
    VectorSpecies<Double> species = y.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 67-69: hx = __HI(x); ix = hx&0x7fffffff; likewise y.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector hy = Lanes.hiInt(y, intSpecies);

    // Line 114: z = atan(fabs(y/x)). The core takes the lanes whose |y/x| is below 2^66. From
    // there up the C's atan returns atanhi[3]+atanlo[3] (s_atan.c, lines 97-102), a constant,
    // blended below; those lanes are the shortcuts' (overwritten), the special lanes' (a NaN, an
    // infinity: overwritten) and the general ones that the shortcuts of k leave to atan. A lane
    // outside the mask comes back as |y/x| and is overwritten or takes the constant, so it is
    // never read, no NaN, infinity or huge value reaches the core's per-lane finish, and an
    // inactive lane never does.
    DoubleVector t = y.div(x).abs();
    VectorMask<Double> small = t.compare(VectorOperators.LT, ATAN_BIG_FROM);
    DoubleVector z = Atan.atan(t, live == null ? small : small.and(live));
    z = z.blend(DoubleVector.broadcast(species, ATAN_BIG), small.not());

    IntVector ix = hx.and(0x7fffffff);
    IntVector iy = hy.and(0x7fffffff);
    // Line 111: k = (iy-ix)>>20, an int difference of two words in [0, 0x7ff00000].
    IntVector k = iy.sub(ix).lanewise(VectorOperators.ASHR, 20);
    VectorMask<Double> kHigh = k.compare(VectorOperators.GT, K_MAX_USED).cast(species);
    VectorMask<Double> kLow = k.compare(VectorOperators.LT, K_MIN_USED).cast(species);
    VectorMask<Double> negX = hx.compare(VectorOperators.LT, 0).cast(species);
    VectorMask<Double> negY = hy.compare(VectorOperators.LT, 0).cast(species);

    // Lines 112-113: the shortcuts. x = 1.0 returned atan(y) at line 74, before k was made.
    VectorMask<Double> xOne = x.compare(VectorOperators.EQ, 1.0);
    VectorMask<Double> high = kHigh.and(xOne.not());
    VectorMask<Double> low = LOW_FOR_ANY_X ? kLow : negX.and(kLow);
    z = z.blend(DoubleVector.broadcast(species, Z_HIGH), high);
    z = z.blend(DoubleVector.zero(species), low);

    // Lines 115-122: the quadrant, picked by m = 2*sign(x)+sign(y). z - pi_lo is one operation.
    DoubleVector w = DROPPING_PI_LO ? z : z.sub(PI_LO);
    DoubleVector pi = DoubleVector.broadcast(species, PI);
    VectorMask<Double> posX = negX.not();
    VectorMask<Double> posY = negY.not();
    VectorMask<Double> case1 = negY.and(posX);
    VectorMask<Double> case2 = posY.and(negX);
    VectorMask<Double> case3 = negY.and(negX);
    DoubleVector result = z;
    result = result.blend(KEEPING_SIGN ? z : z.neg(), case1);
    result = result.blend(pi.sub(w), case2);
    result = result.blend(w.sub(pi), case3);

    // The special lanes, from the last of the C's tests to the first, so that the first that
    // matches wins: y infinite (line 108), x infinite (lines 90-106), x zero (87), y zero
    // (78-85), a NaN (71-73). In the guarded arm only a vector with a live special lane runs
    // them; the tests are exact classifications of the doubles.
    VectorMask<Double> nan = x.test(VectorOperators.IS_NAN).or(y.test(VectorOperators.IS_NAN));
    VectorMask<Double> yZero = y.compare(VectorOperators.EQ, 0.0);
    VectorMask<Double> xZero = x.compare(VectorOperators.EQ, 0.0);
    VectorMask<Double> xInf = x.test(VectorOperators.IS_INFINITE);
    VectorMask<Double> yInf = y.test(VectorOperators.IS_INFINITE);
    VectorMask<Double> special = nan.or(yZero).or(xZero).or(xInf).or(yInf);
    VectorMask<Double> anySpecial = live == null ? special : special.and(live);
    if (mode == BLEND || anySpecial.anyTrue()) {
      // Line 108: (hy<0)? -pi_o_2-tiny: pi_o_2+tiny.
      DoubleVector pio2 = DoubleVector.broadcast(species, PI_O_2_P)
          .blend(DoubleVector.broadcast(species, N_PI_O_2), negY);
      result = result.blend(pio2, yInf);
      // Lines 90-106: x is infinite, y is too (the diagonals) or not (0 and pi), by m.
      DoubleVector finite = DoubleVector.zero(species);
      finite = finite.blend(DoubleVector.broadcast(species, -ZERO), case1);
      finite = finite.blend(DoubleVector.broadcast(species, PI_P), case2);
      finite = finite.blend(DoubleVector.broadcast(species, N_PI), case3);
      DoubleVector diagonal = DoubleVector.broadcast(species, PI_O_4_P);
      if (!ONE_DIAGONAL) {
        diagonal = diagonal.blend(DoubleVector.broadcast(species, N_PI_O_4), case1);
        diagonal = diagonal.blend(DoubleVector.broadcast(species, THREE_PI_O_4_P), case2);
        diagonal = diagonal.blend(DoubleVector.broadcast(species, N_THREE_PI_O_4), case3);
      }
      result = result.blend(finite.blend(diagonal, yInf), xInf);
      if (INF_SWAPPED) {
        result = result.blend(pio2, yInf);
      }
      // Line 87: x = +-0 and y not: +-(pi_o_2+tiny) by y's sign.
      result = result.blend(pio2, xZero);
      // Lines 78-85: y = +-0: y itself for x positive, +-(pi+tiny) for x negative.
      VectorMask<Double> xNegative = ZERO_BY_VALUE ? x.compare(VectorOperators.LT, 0.0) : negX;
      DoubleVector zero = y;
      zero = zero.blend(DoubleVector.broadcast(species, PI_P), xNegative.and(posY));
      zero = zero.blend(DoubleVector.broadcast(species, N_PI), xNegative.and(negY));
      result = result.blend(zero, yZero);
      // Lines 71-73: x+y for a NaN in either argument.
      result = result.blend(x.add(y), nan);
    }
    return live == null ? result : y.blend(result, live);
  }
}
