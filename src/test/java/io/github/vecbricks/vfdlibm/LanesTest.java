/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The word helpers against their scalar meaning, by raw bits, on sampled patterns, the specials
 * and the high words exp compares against, at every width.
 */
class LanesTest {

  /** High words the compares are checked at: exp's, the non-finite edge, and a few others. */
  private static final int[] WORDS = {
    Exp.HX_FILTER, Exp.HX_HALF_LN2, Exp.HX_THREE_HALVES_LN2, Exp.HX_TINY, 0x3ff00000, 0x00100000,
    0x00000001, 0x7fefffff
  };

  private static double[] inputs() {
    double[] xs = Inputs.concat(Inputs.specials(), Inputs.sampled(7L, 1 << 14));
    for (int h : WORDS) {
      double d = Lanes.fromHi(h);
      xs = Inputs.concat(xs, Inputs.around(d, 3), Inputs.around(-d, 3),
          Inputs.around(Lanes.fromHi(h + 1), 3));
    }
    return xs;
  }

  /** Runs {@code check} on each vector's worth of {@link #inputs}, with the scalar bits. */
  private interface Check {
    void apply(DoubleVector x, long[] bits, long[] other);
  }

  private static void forEachVector(VectorSpecies<Double> species, Check check) {
    double[] xs = inputs();
    double[] ys = Inputs.sampled(8L, xs.length);
    int n = species.length();
    for (int base = 0; base + n <= xs.length; base += n) {
      DoubleVector x = DoubleVector.fromArray(species, xs, base);
      long[] bits = new long[n];
      long[] other = new long[n];
      for (int i = 0; i < n; i++) {
        bits[i] = Double.doubleToRawLongBits(xs[base + i]);
        other[i] = Double.doubleToRawLongBits(ys[base + i]);
      }
      check.apply(x, bits, other);
    }
  }

  private static LongVector longs(VectorSpecies<Double> species, long[] values) {
    return LongVector.fromArray(LongVector.SPECIES_PREFERRED.withShape(species.vectorShape())
        .check(long.class), values, 0);
  }

  private static long[] raw(DoubleVector v) {
    return ((LongVector) v.viewAsIntegralLanes()).toArray();
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void hiAndLo(VectorWidth width) {
    forEachVector(width.species(), (x, bits, other) -> {
      long[] hi = Lanes.hi(x).toArray();
      long[] lo = Lanes.lo(x).toArray();
      for (int i = 0; i < bits.length; i++) {
        assertEquals(bits[i] >>> 32, hi[i]);
        assertEquals(bits[i] & 0xffffffffL, lo[i]);
      }
    });
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void withHiWithLoAndFromHiLo(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    forEachVector(species, (x, bits, other) -> {
      LongVector w = longs(species, other);
      long[] withHi = raw(Lanes.withHi(x, w));
      long[] withLo = raw(Lanes.withLo(x, w));
      long[] fromHiLo = raw(Lanes.fromHiLo(Lanes.hi(x), w));
      for (int i = 0; i < bits.length; i++) {
        assertEquals((other[i] << 32) | (bits[i] & 0xffffffffL), withHi[i]);
        assertEquals((bits[i] & 0xffffffff00000000L) | (other[i] & 0xffffffffL), withLo[i]);
        assertEquals((bits[i] & 0xffffffff00000000L) | (other[i] & 0xffffffffL), fromHiLo[i]);
      }
    });
  }

  /** The compares match the C's unsigned compare of |x|'s high word, NaN lanes included. */
  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void highWordCompares(VectorWidth width) {
    forEachVector(width.species(), (x, bits, other) -> {
      DoubleVector ax = x.abs();
      for (int h : WORDS) {
        VectorMask<Double> below = Lanes.hiBelow(ax, h);
        VectorMask<Double> atLeast = Lanes.hiAtLeast(ax, h);
        VectorMask<Double> above = Lanes.hiAbove(ax, h);
        for (int i = 0; i < bits.length; i++) {
          long hx = (bits[i] >>> 32) & 0x7fffffffL;
          String at = Oracle.hex(Double.longBitsToDouble(bits[i])) + " against 0x"
              + Integer.toHexString(h);
          assertEquals(hx < h, below.laneIsSet(i), at);
          assertEquals(hx >= h, atLeast.laneIsSet(i), at);
          assertEquals(hx > h, above.laneIsSet(i), at);
        }
      }
    });
  }

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void addExponent(VectorWidth width) {
    VectorSpecies<Double> species = width.species();
    forEachVector(species, (x, bits, other) -> {
      long[] k = new long[bits.length];
      for (int i = 0; i < k.length; i++) {
        k[i] = (other[i] % 2100) - 1050;
      }
      long[] got = raw(Lanes.addExponent(x, longs(species, k)));
      for (int i = 0; i < bits.length; i++) {
        int hi = (int) (bits[i] >>> 32);
        hi += (int) k[i] << 20;
        long want = ((long) hi << 32) | (bits[i] & 0xffffffffL);
        assertEquals(want, got[i]);
      }
    });
  }

  @Test
  void fromHi() {
    assertEquals(0x40862E42_00000000L, Double.doubleToRawLongBits(Lanes.fromHi(0x40862E42)));
    assertEquals(0xbff00000_00000000L, Double.doubleToRawLongBits(Lanes.fromHi(0xbff00000)));
  }

  /**
   * Same lane count at half the bits; the named species come back as IntVector's constants, and
   * so does SPECIES_MAX at 128, 256 and 512 bits, though it is none of the named double species.
   */
  @Test
  void intSpecies() {
    assertSame(IntVector.SPECIES_64, Lanes.intSpecies(DoubleVector.SPECIES_128));
    assertSame(IntVector.SPECIES_128, Lanes.intSpecies(DoubleVector.SPECIES_256));
    assertSame(IntVector.SPECIES_256, Lanes.intSpecies(DoubleVector.SPECIES_512));
    VectorSpecies<Integer> max = Lanes.intSpecies(DoubleVector.SPECIES_MAX);
    assertEquals(DoubleVector.SPECIES_MAX.length(), max.length());
    assertEquals(DoubleVector.SPECIES_MAX.vectorBitSize() / 2, max.vectorBitSize());
    switch (DoubleVector.SPECIES_MAX.vectorBitSize()) {
      case 128 -> assertSame(IntVector.SPECIES_64, max);
      case 256 -> assertSame(IntVector.SPECIES_128, max);
      case 512 -> assertSame(IntVector.SPECIES_256, max);
      default -> { }
    }
  }
}
