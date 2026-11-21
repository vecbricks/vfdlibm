/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

/**
 * The one comparison of a lane of a function of two arguments with its oracle's. Every check of
 * such a function goes through {@link #matches}: {@code Oracle}'s binary asserts and masked forms,
 * the nightly's pair check (and so its mismatch files and their replay), and the tests of the
 * comparator itself.
 *
 * <p>The rule: a lane equals {@code StrictMath}'s result by {@link Double#doubleToRawLongBits},
 * except on a lane where both arguments are NaN and their bits differ. There the result must equal
 * one of the two arguments' bits with the quiet bit set, all 64 bits, the sign included; which of
 * the two is not specified. The reason is that {@code StrictMath.atan2} returns {@code x + y}
 * there (so does fdlibm's {@code pow}), and which operand's payload that sum keeps changes with the
 * JIT tier that runs it, so no port can match it by bits. fdlibm's {@code hypot} adds the absolute
 * values of its operands, so its sign is cleared: this rule has no sign clause for it, and its port
 * settles that. Everything else keeps the raw-bit rule: a NaN made from arguments
 * that are not both NaN, one NaN argument with a finite one, and two NaNs with identical bits.
 * (PROOFS.md, "The contract of a function of two arguments".)
 *
 * <p>The comparison is on bits, not on {@code double}s, so that a NaN is never converted.
 */
final class PairBits {

  /** The quiet bit of a NaN, the top bit of its significand. */
  static final long QUIET = 0x0008000000000000L;

  private static final long ABS = 0x7fffffffffffffffL;
  private static final long INFINITY = 0x7ff0000000000000L;

  private PairBits() {}

  /** Whether {@code bits} are a NaN's, of either sign and quiet or signalling. */
  static boolean isNaN(long bits) {
    return (bits & ABS) > INFINITY;
  }

  /** Whether the lane {@code (x, y)} is one where the oracle's bits are not the rule. */
  static boolean bothNaNWithDifferentBits(long x, long y) {
    return isNaN(x) && isNaN(y) && x != y;
  }

  /**
   * Whether {@code got} is acceptable for the lane {@code (x, y)} given the oracle's {@code want}:
   * all arguments are raw bits.
   */
  static boolean matches(long x, long y, long want, long got) {
    if (bothNaNWithDifferentBits(x, y)) {
      return got == (x | QUIET) || got == (y | QUIET);
    }
    return got == want;
  }

  /** {@link #matches(long, long, long, long)} on doubles, by their raw bits. */
  static boolean matches(double x, double y, double want, double got) {
    return matches(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y),
        Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got));
  }

  /** What a report says was expected of the lane: the oracle's bits, or the rule on a NaN pair. */
  static String expected(double x, double y, double want) {
    if (bothNaNWithDifferentBits(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y))) {
      return "either argument's bits, quieted";
    }
    return Oracle.hex(want);
  }
}
