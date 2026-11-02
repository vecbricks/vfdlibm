/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * fdlibm's word access, over lanes. fdlibm reads and writes a double's two 32-bit halves through
 * the {@code __HI} and {@code __LO} macros of {@code fdlibm.h}; here each lane's halves live in
 * the long view of the vector ({@link DoubleVector#viewAsIntegralLanes}), which has the same lane
 * count, so no reshaping is needed.
 *
 * <p>A word is held in a long lane as its unsigned 32-bit value, {@code 0} to {@code 0xffffffff},
 * as fdlibm's {@code unsigned hx} holds it. Where fdlibm does {@code int} arithmetic on a word or
 * on an exponent count {@code k}, the long lanes give the same result whenever the C has no
 * overflow, which fdlibm's own range checks guarantee for the lanes whose result is kept.
 */
final class Lanes {

  private static final long LOW_WORD = 0xffff_ffffL;

  private Lanes() {}

  /** The raw bits of each lane. */
  static LongVector bits(DoubleVector x) {
    return (LongVector) x.viewAsIntegralLanes();
  }

  /** The doubles whose raw bits are the lanes of {@code bits}. */
  static DoubleVector fromBits(LongVector bits) {
    return (DoubleVector) bits.viewAsFloatingLanes();
  }

  /** {@code __HI(x)}: each lane's high word, unsigned, sign bit included. */
  static LongVector hi(DoubleVector x) {
    return bits(x).lanewise(VectorOperators.LSHR, 32);
  }

  /** {@code __LO(x)}: each lane's low word, unsigned. */
  static LongVector lo(DoubleVector x) {
    return bits(x).and(LOW_WORD);
  }

  /** {@code __HI(x) = hi}: {@code x} with its high word replaced by {@code hi}'s low 32 bits. */
  static DoubleVector withHi(DoubleVector x, LongVector hi) {
    return fromBits(bits(x).and(LOW_WORD).or(hi.lanewise(VectorOperators.LSHL, 32)));
  }

  /** {@code __LO(x) = lo}: {@code x} with its low word replaced by {@code lo}'s low 32 bits. */
  static DoubleVector withLo(DoubleVector x, LongVector lo) {
    return fromBits(bits(x).and(~LOW_WORD).or(lo.and(LOW_WORD)));
  }

  /**
   * {@code __HI(x)} as an {@code int} lane, signed as fdlibm's {@code int hx} is, in {@code
   * intSpecies}, the species {@link #intSpecies} gives for {@code x}'s. The long lane's high word
   * is narrowed by {@code L2I}, which keeps its low 32 bits.
   */
  static IntVector hiInt(DoubleVector x, VectorSpecies<Integer> intSpecies) {
    return (IntVector) hi(x).convertShape(VectorOperators.L2I, intSpecies, 0);
  }

  /**
   * {@code __HI(x) = hi} for an {@code int} high word: {@code x} with its high word replaced by
   * {@code hi}'s lane, widened by {@code I2L}, whose sign extension the shift drops.
   */
  static DoubleVector withHi(DoubleVector x, IntVector hi) {
    LongVector wide = (LongVector) hi.convertShape(VectorOperators.I2L, bits(x).species(), 0);
    return withHi(x, wide);
  }

  /** The doubles whose high and low words are the low 32 bits of {@code hi} and {@code lo}. */
  static DoubleVector fromHiLo(LongVector hi, LongVector lo) {
    return fromBits(hi.lanewise(VectorOperators.LSHL, 32).or(lo.and(LOW_WORD)));
  }

  /** The double whose high word is {@code h} and whose low word is zero. */
  static double fromHi(int h) {
    return Double.longBitsToDouble(fromHiBits(h));
  }

  /** The double whose high word is {@code h} and whose low word is zero, as raw bits. */
  static long fromHiBits(int h) {
    return (h & LOW_WORD) << 32;
  }

  /**
   * The raw bits of {@code |x|} in each lane: {@code x}'s bits with the sign bit cleared, NaN
   * lanes included. This is the operand the long-form compares below take; unlike {@code
   * lanewise(ABS)} it clears a NaN's sign by definition, on every host.
   */
  static LongVector absBits(LongVector bits) {
    return bits.and(Long.MAX_VALUE);
  }

  /**
   * fdlibm's {@code hx < h} on {@code |x|}'s bits ({@link #absBits}), as one signed 64-bit compare,
   * {@code absBits < fromHiBits(h)}; see {@link #hiAtLeast(LongVector, int)}.
   */
  static VectorMask<Long> hiBelow(LongVector absBits, int h) {
    return absBits.compare(VectorOperators.LT, fromHiBits(h));
  }

  /**
   * fdlibm's {@code hx >= h} on {@code |x|}'s bits ({@link #absBits}), as one signed 64-bit
   * compare, {@code absBits > fromHiBits(h) - 1}, exact for every lane, NaN lanes included, and
   * every {@code h} from 0 to {@code 0x7fffffff} (PROOFS.md, lemma L3). The Vector API has no
   * unordered double compare, so the double form, {@code !(ax < fromHi(h))}, costs a compare, an
   * all-ones vector and an xor on AVX2; this is one {@code vpcmpgtq}.
   */
  static VectorMask<Long> hiAtLeast(LongVector absBits, int h) {
    return absBits.compare(VectorOperators.GT, fromHiBits(h) - 1);
  }

  /** fdlibm's {@code hx > h}, as {@code hx >= h + 1}, for {@code h} below {@code 0x7fffffff}. */
  static VectorMask<Long> hiAbove(LongVector absBits, int h) {
    return hiAtLeast(absBits, h + 1);
  }

  /**
   * fdlibm's {@code hx < h}, where {@code hx} is the high word of {@code |x|} and {@code ax} is
   * {@code |x|}: {@link #hiBelow(LongVector, int)} on {@code ax}'s bits with the sign cleared. A
   * NaN {@code ax} of either sign has {@code hx >= 0x7ff00000}, as in the C.
   */
  static VectorMask<Double> hiBelow(DoubleVector ax, int h) {
    return hiBelow(absBits(bits(ax)), h).cast(ax.species());
  }

  /** fdlibm's {@code hx >= h}, as {@link #hiBelow} negated: true for a NaN {@code ax}. */
  static VectorMask<Double> hiAtLeast(DoubleVector ax, int h) {
    return hiAtLeast(absBits(bits(ax)), h).cast(ax.species());
  }

  /** fdlibm's {@code hx > h}, as {@code hx >= h + 1}: true for a NaN {@code ax}. */
  static VectorMask<Double> hiAbove(DoubleVector ax, int h) {
    return hiAtLeast(ax, h + 1);
  }

  /**
   * The int species with as many lanes as {@code species}, at half its size, for an fdlibm {@code
   * int} such as exp's {@code k} converted from or to double lanes. The usual sizes are picked
   * by the species' bit size, a constant once inlined, and return {@code IntVector}'s named
   * species, so C2 sees a constant species, which it needs to intrinsify the conversion; {@code
   * VectorSpecies.of} reads a table and is not a constant to C2. Picking by size rather than by
   * identity covers {@code SPECIES_MAX} too, which is none of the named double species. A
   * 1024-bit species (an Arm SVE host's widest common size) maps to the named 512-bit int species.
   * Any other size has no int species to pick here, and throws: at 2048 bits {@code
   * VectorShape.forBitSize(1024)} names the host's 2048-bit maximum shape, whose int species has
   * twice the lanes, so a lookup would be wrong rather than slow. The species must have 128 bits
   * or more.
   */
  static VectorSpecies<Integer> intSpecies(VectorSpecies<Double> species) {
    return switch (species.vectorBitSize()) {
      case 128 -> IntVector.SPECIES_64;
      case 256 -> IntVector.SPECIES_128;
      case 512 -> IntVector.SPECIES_256;
      case 1024 -> IntVector.SPECIES_512;
      default -> throw new IllegalArgumentException(
          "no int species of half of " + species.vectorBitSize() + " bits");
    };
  }

  /**
   * {@code __HI(y) += (k << 20)}: adds {@code k} to each lane's binary exponent by adding {@code
   * k << 52} to its bits. The low word of {@code k << 52} is zero, so the sum's high word is the
   * C's {@code int} sum modulo 2^32 and its low word is unchanged.
   */
  static DoubleVector addExponent(DoubleVector y, LongVector k) {
    return fromBits(bits(y).add(k.lanewise(VectorOperators.LSHL, 52)));
  }
}
