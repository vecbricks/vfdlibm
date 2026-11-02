/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * The vector widths every check runs at: 128, 256 and 512 bits, and the host's preferred species.
 *
 * <p>A test runs at each of them with {@code @ParameterizedTest @EnumSource(VectorWidth.class)} and
 * takes its species from {@link #species()}. A fixed width wider than the preferred species is
 * skipped rather than run: the Vector API would still compute it, but in software, which neither
 * proves anything about the hardware path nor finishes quickly. An AVX2 host, such as GitHub's
 * EPYC 7763 runners, skips the 512-bit cases; an AVX-512 host, which some GitHub x86 runners are,
 * runs them.
 */
enum VectorWidth {
  BITS_128(128),
  BITS_256(256),
  BITS_512(512),
  PREFERRED(0);

  /** The width in bits, or 0 for whatever the host prefers. */
  private final int bits;

  VectorWidth(int bits) {
    this.bits = bits;
  }

  /**
   * The species for this width, or a skipped test (a JUnit assumption failure) when the host's
   * hardware has no vectors this wide.
   */
  VectorSpecies<Double> species() {
    if (bits == 0) {
      return DoubleVector.SPECIES_PREFERRED;
    }
    int preferredBits = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    assumeTrue(
        bits <= preferredBits,
        () -> bits + "-bit vectors are not supported by this host's hardware (its preferred "
            + "species is " + preferredBits + " bits); skipping the " + bits + "-bit case");
    return VectorSpecies.of(double.class, VectorShape.forBitSize(bits));
  }
}
