/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file ports fdlibm 5.3's e_cosh.c (reference/fdlibm-5.3/e_cosh.c) and, for cosh's small
 * range, the unreduced lines of s_expm1.c (reference/fdlibm-5.3/s_expm1.c, lines 177-189), which
 * carry these notices:
 *
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
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
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * cosh over lanes, ported from fdlibm 5.3's {@code __ieee754_cosh} in {@code
 * reference/fdlibm-5.3/e_cosh.c}; every lane is bit-identical to {@code StrictMath.cosh}.
 *
 * <p>The method, from the C, for {@code ix}, the high word of {@code |x|}: below {@code
 * 0.5*ln2} ({@code ix < 0x3fd62e43}) with {@code t = expm1(|x|)} and {@code w = 1+t}, return
 * {@code w} below {@code 2^-55} and {@code 1 + t*t/(w+w)} above it; up to {@code 22} return
 * {@code half*t + half/t} with {@code t = exp(|x|)}; up to {@code o_threshold}'s word {@code
 * 0x40862E42} return {@code half*exp(|x|)}; then {@code exp(half*|x|)} squared with {@code half}
 * until the overflow. Every operation is the C's, in the C's order, with no fused multiply-add
 * and no reassociation, so no lemma of its own is needed beyond L13's blend argument; what it
 * rests on through {@link Exp#expCore}, exp's L2 and L5 (L1 is not used), is in {@code
 * PROOFS.md}'s cosh section (L13 is in its section "Blends").
 *
 * <p>Two arms differ in the small range, {@code |x| < 0.5*ln2}:
 *
 * <ul>
 *   <li>{@link #coshSlice} computes it in the vector. The C's {@code expm1(|x|)} there is
 *       s_expm1.c's unreduced path ({@code k == 0}, lines 177-189), which is written out here,
 *       line by line, with {@code Expm1}'s constants: the tiny return of line 178-179 for
 *       {@code |x| < 2^-54}, and otherwise {@code x - (x*e-hxs)} from the polynomial. It is not
 *       {@code Expm1}'s body: there is no reduction, so no second port body is in the graph.
 *   <li>{@link #coshFinish} finishes those lanes per lane with {@code StrictMath.cosh}, so the
 *       vector never computes the polynomial.
 * </ul>
 *
 * <p>Both finish per lane the lanes from the overflow window up ({@code ix >= 0x40862E42}), the
 * infinities and the NaNs. They share one division for cosh's own quotient. In the slice arm its
 * numerator is {@code t*t} (small range) or {@code half}, its denominator {@code w+w} or {@code
 * exp(|x|)}, and each lane's sum is its own range's: {@code 1+q}, {@code w}, {@code half*t+q}
 * or {@code half*t}; it divides once more, in the polynomial of its expm1 lines ({@code
 * (r1-t3)/(6-x*t3)}). In the finish arm it divides {@code half} by {@code exp(|x|)} in every
 * lane, and a small-range lane takes the per-lane finish's value instead. Each lane takes one
 * of the C's branches and does that branch's operations in the C's order; the other branches'
 * values are computed and never kept.
 *
 * <p>The {@code exp} of every lane is {@link Exp#expCore} of {@code |x|}, the only entry into
 * {@code Exp} this class uses, which is {@code StrictMath.exp} in every lane below {@code
 * o_threshold}'s word. One {@code expCore} is the only port body in the graph. The C's {@code
 * int} words stay in int lanes, as in {@code Sinh}. C2 stops inlining once the compiled graph
 * passes {@code NodeCountInliningCutoff}, so every call this body makes comes first, {@code
 * Lanes.intSpecies} and {@code Lanes.hiInt}, then {@link Exp#expCore} as soon as its argument
 * exists; after it there are only Vector API operations and the per-lane finish written out in
 * the body.
 *
 * <p>{@link #cosh(DoubleVector, VectorMask)}, which {@code VFdlibm.cosh} calls, is the slice
 * arm, provisionally: the choice is the design's, not a measurement's. Each host has run {@code
 * CoshBenchmark} and {@code CoshKindsBench} once ({@code
 * bench/results/cosh-xeon8573c-2026-11-21.txt}, {@code cosh-epyc7763-2026-11-21.txt} and the
 * two {@code cosh-kinds-} files of that day), and no file decides the arm. On {@code
 * CoshBenchmark} the ratios between the arms are near 1.3x, waiting for a re-run before a
 * direction is written, or inside it, with no direction. On {@code CoshKindsBench} the finish
 * arm reads lower by more than 1.3x only on data with no small lane, and at 3.5% and 17% of
 * small lanes the arms read within 1.3x of each other, with no direction ({@code SKILLS.md},
 * issue #30). The default is chosen in {@code cosh(x, live)}; other
 * places say "the default arm".
 */
final class Cosh {

  // The constants of e_cosh.c, as its source writes them; CoshTest checks the words.
  static final double ONE = 1.0;
  static final double HALF = 0.5;

  // The high words e_cosh.c compares |x| against.
  /** {@code |x| < 0.5*ln2}: the lanes of the small range. */
  static final int HX_SMALL = 0x3fd62e43;
  /** {@code |x| < 2^-55}: the lanes of the small range whose result is {@code w}. */
  static final int HX_TINY = 0x3c800000;
  /** {@code |x| < 22}: the lanes of {@code half*t + half/t}. */
  static final int HX_LIMIT = 0x40360000;
  /** {@code |x| >= 709.78...}: the lanes finished per lane, as exp's filter. */
  static final int HX_FILTER = 0x40862E42;

  // Planted faults (PLAN.md section 4), read through Faults; each folds away when unplanted.
  static final String SPLIT_MOVED = "cosh.splitMoved";
  static final String HALF_ONE_ULP_OFF = "cosh.halfOneUlpOff";
  static final String DROP_HALF_OVER_T = "cosh.dropHalfOverT";
  static final String FINISH_MISSES_SMALL = "cosh.finishMissesSmall";
  static final String DROP_SMALL_TERM = "cosh.dropSmallTerm";
  // Read once into constants, so the body holds no call and an unplanted fault folds away.
  /**
   * Moves line 62's {@code 0x3fd62e43} to {@code 0x3ff00000} in the slice arm: the lanes of
   * {@code [0.5*ln2, 1)} take the polynomial of the small range where the C takes {@code exp},
   * which rounds differently in about three quarters of them (sized on 2^22 draws of {@code
   * (-1, 1)}: 2070784 changed, of about 2.74 million in {@code [0.3466, 1)}; moved by one word,
   * none). The finish arm computes the small range per lane
   * and never reads it.
   */
  private static final boolean MOVING_SPLIT = Faults.is(SPLIT_MOVED);
  /**
   * {@code half} one ulp up, 0.5 as {@code 0x3fe0000000000001}: nearly every lane of the exp
   * ranges is then wrong (4144395 of 2^22 draws of {@code (-30, 30)} changed; the small range,
   * 1% of them, does not read {@code half}).
   */
  private static final double HALF_USED =
      Faults.is(HALF_ONE_ULP_OFF) ? Double.longBitsToDouble(0x3fe00000_00000001L) : HALF;
  /**
   * Drops line 72's {@code half/t}: the lanes of {@code [0.5*ln2, 22)} return {@code half*t},
   * which is wrong in about 61% of the draws of {@code (-30, 30)} (2568400 of 2^22), in every
   * lane up to {@code |x|} of about 18.7, above which the term is under half an ulp of {@code
   * half*t}.
   */
  private static final boolean DROPPING_HALF_OVER_T = Faults.is(DROP_HALF_OVER_T);
  /**
   * The finish arm's rare mask leaves the small range out: those lanes keep the exp form's value,
   * which differs from the C's in about 12% of them (503123 of 2^22 draws of {@code (-0.34,
   * 0.34)}). The slice arm has no such mask.
   */
  private static final boolean FINISH_MISSING_SMALL = Faults.is(FINISH_MISSES_SMALL);
  /**
   * Drops the {@code - hxs} of line 189's {@code x - (x*e-hxs)} in the small range's polynomial:
   * its result is then wrong by {@code hxs}, about {@code x*x/2}, which moves {@code cosh} for
   * every {@code |x|} above about 1e-5 (sized on 2^22 draws: 4194245 changed of {@code (-0.34,
   * 0.34)}, 4170398 of {@code (-1e-3, 1e-3)}, 1793307 of {@code (-1e-5, 1e-5)}). Only the slice
   * arm reads it: the finish arm never runs that range.
   */
  private static final boolean DROPPING_SMALL_TERM = Faults.is(DROP_SMALL_TERM);

  /** How the small range is computed: in the vector, or per lane. */
  private static final int SLICE = 0;
  private static final int FINISH = 1;

  private Cosh() {}

  /**
   * cosh as {@code VFdlibm.cosh} computes it, the slice arm. {@code live} is null for every
   * lane; otherwise only live lanes are computed, inactive lanes return {@code x}'s lane
   * untouched, and an inactive lane never takes the per-lane finish.
   */
  static DoubleVector cosh(DoubleVector x, VectorMask<Double> live) {
    return cosh(x, live, SLICE);
  }

  /**
   * cosh with the small range computed in the vector, as {@link #cosh(DoubleVector,
   * VectorMask)} does.
   */
  static DoubleVector coshSlice(DoubleVector x, VectorMask<Double> live) {
    return cosh(x, live, SLICE);
  }

  /**
   * cosh with the small range finished per lane. {@code live} is as for {@link
   * #cosh(DoubleVector, VectorMask)}.
   */
  static DoubleVector coshFinish(DoubleVector x, VectorMask<Double> live) {
    return cosh(x, live, FINISH);
  }

  /**
   * Both arms in one body, so they share every line but the small range; {@code arm} is a
   * constant at each call above, and once inlined the other arm's code folds away.
   */
  private static DoubleVector cosh(DoubleVector x, VectorMask<Double> live, int arm) {
    VectorSpecies<Double> species = x.species();
    VectorSpecies<Integer> intSpecies = Lanes.intSpecies(species);
    // Lines 55-56: ix = __HI(x)&0x7fffffff, below.
    IntVector jx = Lanes.hiInt(x, intSpecies);

    // The exp of lines 71 and 76, of |x| (line 63's expm1 is the small range's own): Exp's
    // common path, called as soon as its argument exists (its own calls into Lanes come first).
    DoubleVector ax = x.lanewise(VectorOperators.ABS);
    DoubleVector e = Exp.expCore(ax);

    IntVector ix = jx.and(0x7fffffff);
    // Lines 62, 70 and 76: |x| < 0.5*ln2, |x| < 22 and |x| < o_threshold's word.
    int smallWord = arm == SLICE && MOVING_SPLIT ? 0x3ff00000 : HX_SMALL;
    VectorMask<Double> small = ix.compare(VectorOperators.LT, smallWord).cast(species);
    VectorMask<Double> mid = ix.compare(VectorOperators.LT, HX_LIMIT).cast(species);
    VectorMask<Double> common = ix.compare(VectorOperators.LT, HX_FILTER).cast(species);

    DoubleVector one = DoubleVector.broadcast(species, ONE);
    DoubleVector half = DoubleVector.broadcast(species, HALF_USED);
    DoubleVector numerator = half;
    DoubleVector denominator = e;
    DoubleVector w = null;
    VectorMask<Double> tiny = null;
    if (arm == SLICE) {
      // s_expm1.c lines 184-189, the k == 0 path, for x = |x|: hfx = 0.5*x; hxs = x*hfx; r1 =
      // one+hxs*(Q1+hxs*(Q2+hxs*(Q3+hxs*(Q4+hxs*Q5)))); t = 3.0-r1*hfx; e = hxs*((r1-t)/(6.0 -
      // x*t)); return x - (x*e-hxs). Written as Expm1's body writes its polynomial.
      DoubleVector hfx = ax.mul(0.5);
      DoubleVector hxs = ax.mul(hfx);
      DoubleVector r1 = one.add(hxs.mul(Expm1.Q5).add(Expm1.Q4).mul(hxs).add(Expm1.Q3).mul(hxs)
          .add(Expm1.Q2).mul(hxs).add(Expm1.Q1).mul(hxs));
      DoubleVector t3 = DoubleVector.broadcast(species, 3.0).sub(r1.mul(hfx));
      DoubleVector ee = hxs.mul(r1.sub(t3).div(DoubleVector.broadcast(species, 6.0)
          .sub(ax.mul(t3))));
      DoubleVector xe = ax.mul(ee);
      DoubleVector primary = ax.sub(DROPPING_SMALL_TERM ? xe : xe.sub(hxs));
      // Lines 177-179: |x| < 2^-54 returns x - (t-(huge+x)) with t = huge+x.
      DoubleVector hugeX = ax.add(Expm1.HUGE);
      DoubleVector tinyReturn = ax.sub(hugeX.sub(ax.add(Expm1.HUGE)));
      DoubleVector t = primary.blend(tinyReturn,
          ix.compare(VectorOperators.LT, Expm1.HX_TINY).cast(species));
      // Lines 64-66: w = one+t; below 2^-55 return w, else one+(t*t)/(w+w).
      w = one.add(t);
      numerator = half.blend(t.mul(t), small);
      denominator = e.blend(w.add(w), small);
      tiny = ix.compare(VectorOperators.LT, HX_TINY).cast(species);
    }

    // The one division: line 66's (t*t)/(w+w) in the small range, line 72's half/t above it.
    DoubleVector q = numerator.div(denominator);
    // Line 76: half*exp(|x|); line 72: half*t + half/t (the quotient dropped by the fault).
    DoubleVector large = half.mul(e);
    DoubleVector result = DROPPING_HALF_OVER_T
        ? large : large.blend(large.add(q), mid);
    if (arm == SLICE) {
      // Lines 65-66: w below 2^-55, else one + q.
      result = result.blend(one.add(q).blend(w, tiny), small);
    }

    if (live != null) {
      result = x.blend(result, live);
    }
    // The finish, written out here with no call that takes a vector, as Sinh's is: lane and
    // withLane are lane extract and insert, which C2 intrinsifies for the fixed shapes, and the
    // only call is StrictMath.cosh on a double. rare is and-ed with live, so an inactive lane is
    // never rewritten. Everything from the overflow window up, the infinities and the NaNs is
    // rare in both arms; the finish arm adds the small range.
    VectorMask<Double> rare = common.not();
    if (arm == FINISH && !FINISH_MISSING_SMALL) {
      rare = rare.or(small);
    }
    if (live != null) {
      rare = rare.and(live);
    }
    if (rare.anyTrue()) {
      long rareLanes = rare.toLong();
      int lanes = x.length();
      if (lanes > 0 && (rareLanes & 1L) != 0) {
        result = result.withLane(0, StrictMath.cosh(x.lane(0)));
      }
      if (lanes > 1 && (rareLanes & 2L) != 0) {
        result = result.withLane(1, StrictMath.cosh(x.lane(1)));
      }
      if (lanes > 2 && (rareLanes & 4L) != 0) {
        result = result.withLane(2, StrictMath.cosh(x.lane(2)));
      }
      if (lanes > 3 && (rareLanes & 8L) != 0) {
        result = result.withLane(3, StrictMath.cosh(x.lane(3)));
      }
      if (lanes > 4 && (rareLanes & 16L) != 0) {
        result = result.withLane(4, StrictMath.cosh(x.lane(4)));
      }
      if (lanes > 5 && (rareLanes & 32L) != 0) {
        result = result.withLane(5, StrictMath.cosh(x.lane(5)));
      }
      if (lanes > 6 && (rareLanes & 64L) != 0) {
        result = result.withLane(6, StrictMath.cosh(x.lane(6)));
      }
      if (lanes > 7 && (rareLanes & 128L) != 0) {
        result = result.withLane(7, StrictMath.cosh(x.lane(7)));
      }
      rareLanes &= ~0xffL;
      while (rareLanes != 0) {
        int lane = Long.numberOfTrailingZeros(rareLanes);
        rareLanes &= rareLanes - 1;
        result = result.withLane(lane, StrictMath.cosh(x.lane(lane)));
      }
    }
    return result;
  }
}
