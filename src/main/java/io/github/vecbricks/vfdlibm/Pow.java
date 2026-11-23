/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_pow.c (reference/fdlibm-5.3/e_pow.c), which carries this
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
 * pow over lanes, ported from fdlibm 5.3's {@code __ieee754_pow} in {@code
 * reference/fdlibm-5.3/e_pow.c}; every lane is bit-identical to {@code StrictMath.pow}, except
 * that a lane of two NaNs with different bits is one of the two with the quiet bit set, as {@code
 * PROOFS.md}'s "The contract of a function of two arguments" says. A lane that is not live keeps
 * {@code x}'s bits.
 *
 * <p>The method, from the C: {@code log2(x)} in two pieces {@code t1 + t2} by its own logarithm
 * (lines 208-256: {@code x = 2^n * ax} with {@code ax} in one of three intervals of the
 * significand, {@code ss = (ax-bp[k])/(ax+bp[k])} and a polynomial in {@code ss^2}), then {@code
 * z = y*log2(x)} as {@code p_h + p_l} over {@code y} split at its high word (lines 259-263), then
 * {@code 2^z} as {@code 2^n * 2^(z-n)} by its own exponential (lines 282-307).
 *
 * <p>The vector computes the common path only (the owner's decision of 23 November 2026 on issue
 * 34): {@code x} positive, finite and normal, not 1; {@code y} finite, not zero, {@code iy <=
 * 0x41e00000} ({@code |y|} about {@code 2^31} or less), and none of the special values 1, -1, 2
 * and 0.5 of lines 156-163; {@code z} inside line 266's and line 272's bounds; and a normal
 * result (line 306). Every other lane is finished per lane by {@code StrictMath.pow}, which is
 * the C: the special values of either argument, a negative {@code x} (whose sign {@code s} and
 * {@code yisint} the vector never computes), a subnormal {@code x}, {@code |y| > 2^31}, overflow,
 * underflow, and the subnormal results line 306 sends to {@code scalbn}. That the guard admits
 * only lanes the common path is right for is {@code PROOFS.md}'s L18; that the C's {@code s*z} is
 * {@code z} there is L19.
 *
 * <p>Every operation of the common path is the C's, in the C's order, with no fused multiply-add
 * and no reassociation; its choices ({@code bp[k]}, {@code dp_h[k]}, {@code dp_l[k]}, line 220's
 * {@code n += 1}, the block of lines 285-293 and line 291's sign) are blends (L13). The C's {@code
 * int} words are int lanes of half the double vector's size, as in hypot; {@code (double)n} is
 * {@code I2D}. Every call this body makes comes first ({@code Lanes.intSpecies}, {@code
 * Lanes.hiInt}), and every later word access is written out, since C2 stops inlining once the
 * graph is large (NodeCountInliningCutoff) and a refused call boxes its vectors. One arm: the
 * finish.
 */
final class Pow {

  // The C's constants, by their hexadecimal (PowTest.constantsAreTheSourcesBits).
  /** {@code bp[1]}; {@code bp[0]} is 1.0. */
  static final double BP1 = 1.5;
  /** {@code dp_h[1]}, 0x3FE2B803 0x40000000; {@code dp_h[0]} is 0.0. */
  static final double DP_H1 = 5.84962487220764160156e-01;
  /** {@code dp_l[1]}, 0x3E4CFDEB 0x43CFD006; {@code dp_l[0]} is 0.0. */
  static final double DP_L1 = 1.35003920212974897128e-08;
  static final double ONE = 1.0;
  static final double TWO = 2.0;
  static final double L1 = 5.99999999999994648725e-01; // 0x3FE33333, 0x33333303
  static final double L2 = 4.28571428578550184252e-01; // 0x3FDB6DB6, 0xDB6FABFF
  static final double L3 = 3.33333329818377432918e-01; // 0x3FD55555, 0x518F264D
  static final double L4 = 2.72728123808534006489e-01; // 0x3FD17460, 0xA91D4101
  static final double L5 = 2.30660745775561754067e-01; // 0x3FCD864A, 0x93C9DB65
  static final double L6 = 2.06975017800338417784e-01; // 0x3FCA7E28, 0x4A454EEF
  static final double P1 = 1.66666666666666019037e-01; // 0x3FC55555, 0x5555553E
  static final double P2 = -2.77777777770155933842e-03; // 0xBF66C16C, 0x16BEBD93
  static final double P3 = 6.61375632143793436117e-05; // 0x3F11566A, 0xAF25DE2C
  static final double P4 = -1.65339022054652515390e-06; // 0xBEBBBD41, 0xC5D26BF1
  static final double P5 = 4.13813679705723846039e-08; // 0x3E663769, 0x72BEA4D0
  static final double LG2 = 6.93147180559945286227e-01; // 0x3FE62E42, 0xFEFA39EF
  static final double LG2_H = 6.93147182464599609375e-01; // 0x3FE62E43, 0x00000000
  static final double LG2_L = -1.90465429995776804525e-09; // 0xBE205C61, 0x0CA86C39
  static final double CP = 9.61796693925975554329e-01; // 0x3FEEC709, 0xDC3A03FD
  static final double CP_H = 9.61796700954437255859e-01; // 0x3FEEC709, 0xE0000000
  static final double CP_L = -7.02846165095275826516e-09; // 0xBE3E2FE0, 0x145B01F5

  /** Line 212: {@code ix < 0x00100000}, a subnormal or zero {@code x}. */
  static final int X_NORMAL_WORD = 0x00100000;
  /** Lines 122 and 169: {@code ix >= 0x7ff00000}, an infinity or a NaN. */
  static final int INF_WORD = 0x7ff00000;
  /** Line 191: {@code iy > 0x41e00000}, {@code |y| > 2^31}. */
  static final int Y_HUGE_WORD = 0x41e00000;
  /** Line 218: {@code j <= 0x3988E}, {@code |x| < sqrt(3/2)}. */
  static final int SPLIT_LOW = 0x3988E;
  /** Line 219: {@code j < 0xBB67A}, {@code |x| < sqrt(3)}. */
  static final int SPLIT_HIGH = 0xBB67A;
  /** Line 266: {@code j >= 0x40900000}, {@code z >= 1024}. */
  static final int OVERFLOW_WORD = 0x40900000;
  /** Line 272: {@code (j&0x7fffffff) >= 0x4090cc00}, {@code z <= -1075}. */
  static final int UNDERFLOW_WORD = 0x4090cc00;
  /** Line 285: {@code i > 0x3fe00000}, {@code |z| > 0.5}. */
  static final int HALF_WORD = 0x3fe00000;
  /** The bits of the special {@code y} of lines 156-163: 1, -1, 2 and 0.5. */
  static final long Y_ONE_BITS = 0x3ff0000000000000L;
  static final long Y_MINUS_ONE_BITS = 0xbff0000000000000L;
  static final long Y_TWO_BITS = 0x4000000000000000L;
  static final long Y_HALF_BITS = 0x3fe0000000000000L;
  /** The bits of {@code x = 1.0}, a special {@code x} of line 169. */
  static final long X_ONE_BITS = 0x3ff0000000000000L;
  /** The high word's bits of a long lane, for {@code __LO(v) = 0}. */
  private static final long HIGH_WORD = 0xffff_ffff_0000_0000L;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String GUARD_ADMITS_NEGATIVE_X = "pow.guardAdmitsNegativeX";
  static final String GUARD_MISSES_SUBNORMAL = "pow.guardMissesSubnormal";
  static final String DROP_T2 = "pow.dropT2";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /** The guard tests {@code ix}, not {@code hx}, so a negative {@code x} takes the common path. */
  private static final boolean ADMITTING_NEGATIVE_X = Faults.is(GUARD_ADMITS_NEGATIVE_X);
  /** Line 306's test left out of the guard: a subnormal result keeps line 307's exponent add. */
  private static final boolean MISSING_SUBNORMAL = Faults.is(GUARD_MISSES_SUBNORMAL);
  /** Line 261's {@code y*t2} dropped. */
  private static final boolean DROPPING_T2 = Faults.is(DROP_T2);

  private Pow() {}

  /**
   * pow with the lanes off the common path finished per lane. {@code live} is null for every
   * lane; otherwise only live lanes are computed, inactive lanes return {@code x}'s lane
   * untouched, and an inactive lane never takes the per-lane finish.
   */
  static DoubleVector pow(DoubleVector x, DoubleVector y, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 114-116: hx = __HI(x); hy = __HI(y); ix = hx&0x7fffffff; iy = hy&0x7fffffff.
    IntVector hx = Lanes.hiInt(x, intSpecies);
    IntVector hy = Lanes.hiInt(y, intSpecies);
    LongVector xBits = (LongVector) x.viewAsIntegralLanes();
    LongVector yBits = (LongVector) y.viewAsIntegralLanes();
    VectorSpecies<Long> longSpecies = xBits.species();
    IntVector ix = hx.and(0x7fffffff);
    IntVector iy = hy.and(0x7fffffff);

    // The guard (PROOFS.md, L18): the lanes the common path is not right for, from the
    // arguments. x: not positive, normal and finite (hx outside [0x00100000, 0x7ff00000), which
    // takes in every negative x, the zeros, the subnormals, the infinities and the NaNs), or 1.
    IntVector hxTested = ADMITTING_NEGATIVE_X ? ix : hx;
    VectorMask<Integer> xOffI = hxTested.compare(VectorOperators.LT, X_NORMAL_WORD)
        .or(hxTested.compare(VectorOperators.GE, INF_WORD));
    // y: iy > 0x41e00000 (line 191, which takes in the infinities and the NaNs), y zero (line
    // 119), or one of 1, -1, 2 and 0.5 (lines 156-163, by y's 64 bits).
    VectorMask<Integer> yOffI = iy.compare(VectorOperators.GT, Y_HUGE_WORD);
    VectorMask<Long> specialL = xBits.compare(VectorOperators.EQ, X_ONE_BITS)
        .or(yBits.and(Long.MAX_VALUE).compare(VectorOperators.EQ, 0L))
        .or(yBits.compare(VectorOperators.EQ, Y_ONE_BITS))
        .or(yBits.compare(VectorOperators.EQ, Y_MINUS_ONE_BITS))
        .or(yBits.compare(VectorOperators.EQ, Y_TWO_BITS))
        .or(yBits.compare(VectorOperators.EQ, Y_HALF_BITS));
    VectorMask<Double> rare = xOffI.or(yOffI).cast(species).or(specialL.cast(species));

    // Lines 210-215 (line 212 is not taken: ix >= 0x00100000 on every common lane):
    // n = (ix>>20)-0x3ff; j = ix&0x000fffff.
    IntVector n = ix.lanewise(VectorOperators.ASHR, 20).sub(0x3ff);
    IntVector j = ix.and(0x000fffff);
    // Lines 217-220: ix = j|0x3ff00000; j <= 0x3988E: k = 0; else j < 0xBB67A: k = 1;
    // else k = 0, n += 1, ix -= 0x00100000.
    IntVector ixn = j.or(0x3ff00000);
    VectorMask<Integer> pastLowI = j.compare(VectorOperators.GT, SPLIT_LOW);
    VectorMask<Integer> k1I = pastLowI.and(j.compare(VectorOperators.LT, SPLIT_HIGH));
    VectorMask<Integer> k2I = pastLowI.andNot(k1I);
    n = n.blend(n.add(1), k2I);
    ixn = ixn.blend(ixn.sub(0x00100000), k2I);
    VectorMask<Double> k1 = k1I.cast(species);
    // Line 221: __HI(ax) = ix, over ax = fabs(x)'s low word, which is x's.
    DoubleVector ax = (DoubleVector) ((LongVector) ixn
        .convertShape(VectorOperators.I2L, longSpecies, 0)).lanewise(VectorOperators.LSHL, 32)
        .or(xBits.and(Lanes.LOW_WORD)).viewAsFloatingLanes();

    // Lines 224-228: u = ax-bp[k]; v = one/(ax+bp[k]); ss = u*v; s_h = ss; __LO(s_h) = 0.
    DoubleVector bp = DoubleVector.broadcast(species, ONE).blend(BP1, k1);
    DoubleVector u = ax.sub(bp);
    DoubleVector v = DoubleVector.broadcast(species, ONE).div(ax.add(bp));
    DoubleVector ss = u.mul(v);
    DoubleVector sH = (DoubleVector) ((LongVector) ss.viewAsIntegralLanes()).and(HIGH_WORD)
        .viewAsFloatingLanes();
    // Lines 230-231: t_h = zero; __HI(t_h) = ((ix>>1)|0x20000000)+0x00080000+(k<<18), k<<18
    // being 0 or 0x40000 by k.
    IntVector tHWord = ixn.lanewise(VectorOperators.ASHR, 1).or(0x20000000).add(0x00080000)
        .add(IntVector.zero(intSpecies).blend(1 << 18, k1I));
    DoubleVector tH = (DoubleVector) ((LongVector) tHWord
        .convertShape(VectorOperators.I2L, longSpecies, 0)).lanewise(VectorOperators.LSHL, 32)
        .viewAsFloatingLanes();
    // Lines 232-233: t_l = ax - (t_h-bp[k]); s_l = v*((u-s_h*t_h)-s_h*t_l).
    DoubleVector tL = ax.sub(tH.sub(bp));
    DoubleVector sL = v.mul(u.sub(sH.mul(tH)).sub(sH.mul(tL)));
    // Lines 235-237: s2 = ss*ss; r = s2*s2*(L1+s2*(L2+s2*(L3+s2*(L4+s2*(L5+s2*L6)))));
    // r += s_l*(s_h+ss).
    DoubleVector s2 = ss.mul(ss);
    DoubleVector r = s2.mul(s2).mul(
        s2.mul(s2.mul(s2.mul(s2.mul(s2.mul(L6).add(L5)).add(L4)).add(L3)).add(L2)).add(L1));
    r = r.add(sL.mul(sH.add(ss)));
    // Lines 238-241: s2 = s_h*s_h; t_h = 3.0+s2+r; __LO(t_h) = 0; t_l = r-((t_h-3.0)-s2).
    s2 = sH.mul(sH);
    DoubleVector tH2 = DoubleVector.broadcast(species, 3.0).add(s2).add(r);
    tH2 = (DoubleVector) ((LongVector) tH2.viewAsIntegralLanes()).and(HIGH_WORD)
        .viewAsFloatingLanes();
    DoubleVector tL2 = r.sub(tH2.sub(3.0).sub(s2));
    // Lines 243-248: u = s_h*t_h; v = s_l*t_h+t_l*ss; p_h = u+v; __LO(p_h) = 0;
    // p_l = v-(p_h-u).
    DoubleVector u2 = sH.mul(tH2);
    DoubleVector v2 = sL.mul(tH2).add(tL2.mul(ss));
    DoubleVector pH = (DoubleVector) ((LongVector) u2.add(v2).viewAsIntegralLanes())
        .and(HIGH_WORD).viewAsFloatingLanes();
    DoubleVector pL = v2.sub(pH.sub(u2));
    // Lines 249-250: z_h = cp_h*p_h; z_l = cp_l*p_h+p_l*cp+dp_l[k].
    DoubleVector zH = pH.mul(CP_H);
    DoubleVector zL = pH.mul(CP_L).add(pL.mul(CP))
        .add(DoubleVector.zero(species).blend(DP_L1, k1));
    // Lines 252-255: t = (double)n; t1 = (((z_h+z_l)+dp_h[k])+t); __LO(t1) = 0;
    // t2 = z_l-(((t1-t)-dp_h[k])-z_h).
    DoubleVector t = (DoubleVector) n.convertShape(VectorOperators.I2D, species, 0);
    DoubleVector dpH = DoubleVector.zero(species).blend(DP_H1, k1);
    DoubleVector t1 = (DoubleVector) ((LongVector) zH.add(zL).add(dpH).add(t)
        .viewAsIntegralLanes()).and(HIGH_WORD).viewAsFloatingLanes();
    DoubleVector t2 = zL.sub(t1.sub(t).sub(dpH).sub(zH));

    // Lines 259-263: y1 = y; __LO(y1) = 0; p_l = (y-y1)*t1+y*t2; p_h = y1*t1; z = p_l+p_h.
    DoubleVector y1 = (DoubleVector) yBits.and(HIGH_WORD).viewAsFloatingLanes();
    DoubleVector pL2 = DROPPING_T2 ? y.sub(y1).mul(t1) : y.sub(y1).mul(t1).add(y.mul(t2));
    DoubleVector pH2 = y1.mul(t1);
    DoubleVector z = pL2.add(pH2);
    // Line 264: j = __HI(z), written out.
    IntVector jz = (IntVector) ((LongVector) z.viewAsIntegralLanes())
        .lanewise(VectorOperators.LSHR, 32).convertShape(VectorOperators.L2I, intSpecies, 0);
    // Lines 266 and 272, each taken as a whole (L18): z >= 1024 or z <= -1075.
    VectorMask<Integer> boundsI = jz.compare(VectorOperators.GE, OVERFLOW_WORD)
        .or(jz.and(0x7fffffff).compare(VectorOperators.GE, UNDERFLOW_WORD));

    // Lines 282-284: i = j&0x7fffffff; k = (i>>20)-0x3ff; n = 0.
    IntVector iz = jz.and(0x7fffffff);
    IntVector kz = iz.lanewise(VectorOperators.ASHR, 20).sub(0x3ff);
    // Lines 285-293, where i > 0x3fe00000: n = j+(0x00100000>>(k+1)); k = ((n&0x7fffffff)>>20)
    // -0x3ff; t = zero; __HI(t) = (n&~(0x000fffff>>k)); n = ((n&0x000fffff)|0x00100000)>>(20-k);
    // if (j<0) n = -n; p_h -= t. The shift counts are in range on those lanes only; elsewhere
    // they are masked to five bits, and what they give is not kept.
    VectorMask<Integer> aboveHalfI = iz.compare(VectorOperators.GT, HALF_WORD);
    IntVector nz = jz.add(IntVector.broadcast(intSpecies, 0x00100000)
        .lanewise(VectorOperators.ASHR, kz.add(1)));
    IntVector kn = nz.and(0x7fffffff).lanewise(VectorOperators.ASHR, 20).sub(0x3ff);
    IntVector tWord = nz.and(IntVector.broadcast(intSpecies, 0x000fffff)
        .lanewise(VectorOperators.ASHR, kn).not());
    DoubleVector tz = (DoubleVector) ((LongVector) tWord
        .convertShape(VectorOperators.I2L, longSpecies, 0)).lanewise(VectorOperators.LSHL, 32)
        .viewAsFloatingLanes();
    nz = nz.and(0x000fffff).or(0x00100000)
        .lanewise(VectorOperators.ASHR, IntVector.broadcast(intSpecies, 20).sub(kn));
    nz = nz.blend(nz.neg(), jz.compare(VectorOperators.LT, 0));
    IntVector nExp = IntVector.zero(intSpecies).blend(nz, aboveHalfI);
    pH2 = pH2.blend(pH2.sub(tz), aboveHalfI.cast(species));

    // Lines 294-299: t = p_l+p_h; __LO(t) = 0; u = t*lg2_h; v = (p_l-(t-p_h))*lg2+t*lg2_l;
    // z = u+v; w = v-(z-u).
    DoubleVector te = (DoubleVector) ((LongVector) pL2.add(pH2).viewAsIntegralLanes())
        .and(HIGH_WORD).viewAsFloatingLanes();
    DoubleVector ue = te.mul(LG2_H);
    DoubleVector ve = pL2.sub(te.sub(pH2)).mul(LG2).add(te.mul(LG2_L));
    DoubleVector ze = ue.add(ve);
    DoubleVector we = ve.sub(ze.sub(ue));
    // Lines 300-303: t = z*z; t1 = z-t*(P1+t*(P2+t*(P3+t*(P4+t*P5))));
    // r = (z*t1)/(t1-two)-(w+z*w); z = one-(r-z).
    DoubleVector tt = ze.mul(ze);
    DoubleVector t1e = ze.sub(
        tt.mul(tt.mul(tt.mul(tt.mul(tt.mul(P5).add(P4)).add(P3)).add(P2)).add(P1)));
    DoubleVector re = ze.mul(t1e).div(t1e.sub(TWO)).sub(we.add(ze.mul(we)));
    DoubleVector zr = DoubleVector.broadcast(species, ONE).sub(re.sub(ze));
    // Lines 304-305: j = __HI(z); j += (n<<20).
    LongVector zrBits = (LongVector) zr.viewAsIntegralLanes();
    IntVector jr = ((IntVector) zrBits.lanewise(VectorOperators.LSHR, 32)
        .convertShape(VectorOperators.L2I, intSpecies, 0))
        .add(nExp.lanewise(VectorOperators.LSHL, 20));
    // Line 306: (j>>20) <= 0, a subnormal result, is finished per lane (the owner's decision A).
    VectorMask<Integer> lateI = boundsI;
    if (!MISSING_SUBNORMAL) {
      lateI = lateI.or(jr.lanewise(VectorOperators.ASHR, 20).compare(VectorOperators.LE, 0));
    }
    rare = rare.or(lateI.cast(species));
    // Line 307: __HI(z) += (n<<20), which is __HI(z) = j; line 308: s*z is z, s being one on
    // every common lane (L19).
    DoubleVector result = (DoubleVector) ((LongVector) jr
        .convertShape(VectorOperators.I2L, longSpecies, 0)).lanewise(VectorOperators.LSHL, 32)
        .or(zrBits.and(Lanes.LOW_WORD)).viewAsFloatingLanes();

    if (live != null) {
      result = x.blend(result, live);
      rare = rare.and(live);
    }
    // The rare lanes are finished per lane, written out here with no call that takes a vector:
    // lane and withLane are lane extract and insert, which C2 intrinsifies for the fixed shapes,
    // and the only call is StrictMath.pow on two doubles. The first eight lanes have constant
    // indices, as in the other ports' finishes, and the loop takes the lanes past eight. rare is
    // and-ed with live, so an inactive lane is never rewritten.
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.pow(x.lane(0), y.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.pow(x.lane(1), y.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.pow(x.lane(2), y.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.pow(x.lane(3), y.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.pow(x.lane(4), y.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.pow(x.lane(5), y.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.pow(x.lane(6), y.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.pow(x.lane(7), y.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.pow(x.lane(lane), y.lane(lane)));
      }
    }
    return result;
  }
}
