/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.util.Objects;

/**
 * The one comparison of a lane of a function of two arguments with its oracle's, for the
 * functions that follow the rule below. Every check of such a function goes through
 * {@link #matches}: {@code Oracle}'s pair asserts and masked forms, the nightly's pair check (and
 * so its mismatch files and their replay), and the tests of the comparator itself.
 *
 * <p>The rule: a lane equals {@code StrictMath}'s result by {@link Double#doubleToRawLongBits},
 * except on a lane where both arguments are NaN and their bits differ. There the result must be
 * one of two <em>candidates</em>, all 64 bits: the two arguments' bits with the quiet bit set
 * ({@link Candidates#OPERANDS}, which {@code atan2} and {@code pow} declare), or the same with the
 * sign cleared ({@link Candidates#MAGNITUDES}, which {@code hypot} declares, since fdlibm's
 * {@code hypot} adds the absolute values of its arguments). Each function declares one on its
 * {@link Nightly.PairFunction} and there is no default. The oracle's own bits must be among the
 * candidates too, so a function declared the wrong way fails on the first two-NaN lane where the
 * declarations differ, and not only a port.
 *
 * <p><b>Which candidate is not specified, and no port or test may assume one.</b> It varies with
 * the tier that runs {@code StrictMath} (interpreter, C1 or C2) and for one call site within a
 * run, for the first argument or the second: measured on JDK 25.0.5.1, issue 33. In particular
 * it is not the NaN with the larger high word that fdlibm's {@code e_hypot.c} orders its operands
 * by. Every other lane keeps the raw-bit rule: a NaN made from arguments that are not both NaN,
 * one NaN argument with a finite one, and two NaNs with identical bits.
 * (PROOFS.md, "The contract of a function of two arguments".)
 *
 * <p>The comparison is on bits, not on {@code double}s, so that a NaN is never converted.
 */
final class PairBits {

  /** The quiet bit of a NaN, the top bit of its significand. */
  static final long QUIET = 0x0008000000000000L;

  private static final long ABS = 0x7fffffffffffffffL;
  private static final long INFINITY = 0x7ff0000000000000L;

  /** What a function's result may be on a lane of two NaNs with different bits. */
  enum Candidates {
    /** The arguments' bits as given, quieted: {@code atan2}, {@code pow}. */
    OPERANDS,
    /** The arguments' bits with the sign cleared, quieted: {@code hypot}. */
    MAGNITUDES
  }

  private PairBits() {}

  /** Whether {@code bits} are a NaN's, of either sign and quiet or signalling. */
  static boolean isNaN(long bits) {
    return (bits & ABS) > INFINITY;
  }

  /** Whether the lane {@code (x, y)} is one where the oracle's bits are not the rule. */
  static boolean bothNaNWithDifferentBits(long x, long y) {
    return isNaN(x) && isNaN(y) && x != y;
  }

  /** The candidate made from {@code argument}'s bits. */
  static long candidate(Candidates candidates, long argument) {
    long bits = argument | QUIET;
    return candidates == Candidates.MAGNITUDES ? bits & ABS : bits;
  }

  /**
   * Whether {@code got} is acceptable for the lane {@code (x, y)} given the oracle's {@code want}:
   * all arguments are raw bits. On a lane of two NaNs with different bits both {@code got} and
   * {@code want} must be a candidate.
   */
  static boolean matches(Candidates candidates, long x, long y, long want, long got) {
    Objects.requireNonNull(candidates, "a pair function declares its candidates");
    if (bothNaNWithDifferentBits(x, y)) {
      return isCandidate(candidates, x, y, want) && isCandidate(candidates, x, y, got);
    }
    return got == want;
  }

  private static boolean isCandidate(Candidates candidates, long x, long y, long bits) {
    return bits == candidate(candidates, x) || bits == candidate(candidates, y);
  }

  /** {@link #matches(Candidates, long, long, long, long)} on doubles, by their raw bits. */
  static boolean matches(Candidates candidates, double x, double y, double want, double got) {
    return matches(candidates, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y),
        Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got));
  }

  /** What a report says was expected of the lane: the oracle's bits, or the two candidates. */
  static String expected(Candidates candidates, long x, long y, long want) {
    if (bothNaNWithDifferentBits(x, y)) {
      return String.format("one of 0x%016x, 0x%016x (the oracle's: 0x%016x)",
          candidate(candidates, x), candidate(candidates, y), want);
    }
    return String.format("0x%016x", want);
  }

  /** {@link #expected(Candidates, long, long, long)} on doubles, the oracle's with its value. */
  static String expected(Candidates candidates, double x, double y, double want) {
    long xb = Double.doubleToRawLongBits(x);
    long yb = Double.doubleToRawLongBits(y);
    if (bothNaNWithDifferentBits(xb, yb)) {
      return expected(candidates, xb, yb, Double.doubleToRawLongBits(want));
    }
    return Oracle.hex(want);
  }
}
