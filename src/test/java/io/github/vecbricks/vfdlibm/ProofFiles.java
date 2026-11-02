/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from vecbricks/varka's VarkaProofFiles.java, the renderer of its proof files.
 */
package io.github.vecbricks.vfdlibm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;

/**
 * The rendered files under {@code proofs/}: the check of the prelude, {@code java.smt2}, against
 * the JVM. {@link ProofFilesTest} fails when a committed file differs from what this renders, so
 * a definition and the JVM results that pin it cannot drift apart. {@code java.smt2} itself, and
 * the proofs whose constants are fdlibm's rather than computed, are written by hand.
 *
 * <p>Every check is an {@code (echo "<what it shows>: expect sat|unsat")} followed by its
 * {@code (check-sat)} between {@code push} and {@code pop}; {@code dev/prove.sh} pairs each
 * verdict with the expectation before it.
 */
final class ProofFiles {

  private ProofFiles() {
  }

  /** Each rendered file's name under {@code proofs/}, and its text. */
  static Map<String, String> render() {
    var files = new LinkedHashMap<String, String>();
    files.put("java_check.smt2", javaCheck());
    files.put("exp_highword_compare.smt2", ExpProofFiles.highwordCompare());
    files.put("exp_half_sign.smt2", ExpProofFiles.halfSign());
    return files;
  }

  static final String LICENSE = """
      ; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
      ; "License"); you may not use this file except in compliance with the License. You may obtain
      ; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
      ; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
      ; ANY KIND, either express or implied. See the License for the specific language governing
      ; permissions and limitations under the License.
      ;
      """;

  /** The lines every rendered file shares after its own header, ending in its logic. */
  static String rendered(String logic) {
    return """
        ;
        ; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
        ; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
        ; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

        (set-logic %s)
        """.formatted(logic);
  }

  // ---------------------------------------------------------------------------------------------
  // java_check.smt2: the prelude against the JVM

  private static final int[] INTS =
      {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -7, -1, 0, 1, 7, Integer.MAX_VALUE};

  private static final long[] LONGS = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -(1L << 32), -7, -1, 0,
      1, 7, 1L << 32, Long.MAX_VALUE};

  /** Shift counts: in range, at and past the mask, and negative, whose low bits are all set. */
  private static final int[] COUNTS = {-1, 0, 1, 31, 32, 33, 63, 64};

  private static final long P52 = 1L << 52;
  private static final long P53 = 1L << 53;
  private static final long MANTISSA_52 = P52 - 1;

  /**
   * {@code java_check.smt2}: each of the prelude's definitions gives what the JVM gives, over
   * boundary operands - each type's extremes and the values next to them, small values of both
   * signs, shift counts whose masked-off bits are set, and doubles of both signs at the edges of
   * their binades and of the subnormals. The expected values are computed here, by Java's own
   * operators, while rendering. One check per operator, which names it when it fails.
   */
  static String javaCheck() {
    var out = new StringBuilder(LICENSE);
    out.append("""
        ; Adapted from vecbricks/varka's sql/varka/proofs/java_check.smt2, of the same role.
        ;
        ; The prelude, java.smt2, against the JVM: for each operator it defines, a check that its
        ; definition gives what Java gives over boundary operands - each type's extremes and the
        ; values next to them, small values of both signs, shift counts whose masked-off bits are
        ; set, and doubles of both signs at the edges of binades and of the subnormals. The
        ; expected values were computed by Java's own operators while rendering, so a definition
        ; with the right name and the wrong meaning fails here before a proof can rest on it. Each
        ; check is unsat when every equation in it holds.
        """);
    out.append(rendered("QF_NIA"));
    range(out, "jint.in", "int", Integer.MIN_VALUE, Integer.MAX_VALUE);
    range(out, "jlong.in", "long", Long.MIN_VALUE, Long.MAX_VALUE);
    ints(out, "jint.add", "int +", INTS, (a, b) -> a + b);
    ints(out, "jint.sub", "int -", INTS, (a, b) -> a - b);
    ints(out, "jint.mul", "int *", INTS, (a, b) -> a * b);
    ints(out, "jint.shl", "int <<, its count masked", COUNTS, (a, s) -> a << s);
    ints(out, "jint.shr", "int >>, its count masked", COUNTS, (a, s) -> a >> s);
    ints(out, "jint.ushr", "int >>>, its count masked", COUNTS, (a, s) -> a >>> s);
    var intUnary = new ArrayList<String>();
    for (int a : INTS) {
      intUnary.add(eq("jint.neg", lit(a), lit(-a)));
      intUnary.add(eq("jint.not", lit(a), lit(~a)));
      intUnary.add(eq("i2l", lit(a), lit((long) a)));
      for (int k : new int[] {3, 20, 31, 32}) {
        intUnary.add(eq("jint.and.low", lit(a) + " " + (1L << k), lit(a & ((1L << k) - 1))));
      }
    }
    equations(out, "jint.neg, jint.not, jint.and.low, i2l are Java's", intUnary);
    long[] counts = {-1, 0, 1, 31, 32, 33, 63, 64};
    longs(out, "jlong.add", "long +", LONGS, (a, b) -> a + b);
    longs(out, "jlong.sub", "long -", LONGS, (a, b) -> a - b);
    longs(out, "jlong.mul", "long *", LONGS, (a, b) -> a * b);
    longs(out, "jlong.shl", "long <<, its count masked", counts, (a, s) -> a << s);
    longs(out, "jlong.shr", "long >>, its count masked", counts, (a, s) -> a >> s);
    longs(out, "jlong.ushr", "long >>>, its count masked", counts, (a, s) -> a >>> s);
    // The shifts above reach pow2 at a few counts only; each entry of its table is pinned here,
    // so a wrong entry at a count no shift above uses cannot pass.
    var pow2 = new ArrayList<String>();
    for (int k = 0; k < 64; k++) {
      pow2.add(eq("pow2", Integer.toString(k), Long.toUnsignedString(1L << k)));
    }
    equations(out, "pow2 k is Java's 1L << k read unsigned, every k in 0..63", pow2);
    var longUnary = new ArrayList<String>();
    for (long a : LONGS) {
      longUnary.add(eq("jlong.neg", lit(a), lit(-a)));
      longUnary.add(eq("jlong.not", lit(a), lit(~a)));
      longUnary.add(eq("jlong.and.low", lit(a) + " " + P52, lit(a & MANTISSA_52)));
      // Lanes.absBits's mask, Long.MAX_VALUE, and fromHiBits's, 0xffffffffL.
      longUnary.add(
          eq("jlong.and.low", lit(a) + " 9223372036854775808", lit(a & Long.MAX_VALUE)));
      longUnary.add(eq("jlong.and.low", lit(a) + " 4294967296", lit(a & 0xffff_ffffL)));
    }
    for (long a : new long[] {Long.MIN_VALUE, -(1L << 32) - 5, -(1L << 31) - 1, -(1L << 31), -1,
        0, (1L << 31) - 1, 1L << 31, (1L << 32) + 5, Long.MAX_VALUE}) {
      longUnary.add(eq("l2i", lit(a), lit((int) a)));
    }
    equations(out, "jlong.neg, jlong.not, jlong.and.low, l2i are Java's", longUnary);
    doubles(out);
    return out.toString();
  }

  /** The doubles c in {@code c - 2.0} and {@code 2.0 - c}, exp's denominators, and around them. */
  private static final double[] NEAR_TWO = {0.34657359027997264, -0.3665, 0.1, -0.1, 1e-20,
      -1e-20, 0x1p-60, -0x1p-60, 0x1p-1074, -0x1p-1022, 1.0, -2.0, 0.5, -0.5, 0x1.fffffffffffffp-1};

  /**
   * The doubles: {@code jdouble.near} and {@code jdouble.near.sub} against Java's {@code (double)}
   * of a long, {@code -}, {@code /} and {@code *}, every operation at both signs; then the bit
   * layout. Each rounding is checked both ways: the double Java computed satisfies the
   * definition, and its two neighbours, each written in its own binade, do not, so the definition
   * pins one double rather than admitting several, and a neighbour across a binade's edge or the
   * edge of the subnormals is refused as well as one inside.
   */
  private static void doubles(StringBuilder out) {
    encodings(out);
    var l2d = new ArrayList<String>();
    for (long v : new long[] {1, 7, P52 - 1, P52, P52 + 1, P53 - 1, P53, P53 + 1, P53 + 2,
        P53 + 3, P53 + 5, 2 * P53 - 2, 2 * P53 - 1, 3 * P53 + 1, Long.MAX_VALUE}) {
      rounded(l2d, BigInteger.valueOf(v), BigInteger.ONE, (double) v);
      rounded(l2d, BigInteger.valueOf(-v), BigInteger.ONE, (double) -v);
    }
    rounded(l2d, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.ONE, (double) Long.MIN_VALUE);
    equations(out, "jdouble.near is Java's (double) of a long, ties included", l2d);
    var sub = new ArrayList<String>();
    for (double c : NEAR_TWO) {
      rational(sub, c, '+', -2.0, c - 2.0);
      rational(sub, 2.0, '+', -c, 2.0 - c);
    }
    equations(out, "jdouble.near is Java's c - 2.0 and 2.0 - c", sub);
    var div = new ArrayList<String>();
    for (double a : new double[] {1.0, 7.0, 0.1, 0x1p-56, 0.1132, 1e200, 0x1.fffffffffffffp-1}) {
      for (double b : new double[] {3.0, 1.65, 2.3665, 0x1.0000000000001p0, 1e-30}) {
        for (int s = 0; s < 4; s++) {
          double sa = (s & 1) == 0 ? a : -a;
          double sb = (s & 2) == 0 ? b : -b;
          rational(div, sa, '/', sb, sa / sb);
        }
      }
    }
    equations(out, "jdouble.near is Java's / at all four signs", div);
    // The edge of the subnormals: the lowest binade's bottom, 2^-1022, has a whole step below
    // it, not a half one. (1 - 2^-53) * 2^-1022 is the tie between it and the largest
    // subnormal, which goes to 2^-1022, the even one; one ulp of the factor lower goes below.
    var edge = new ArrayList<String>();
    for (double a : new double[] {Double.MIN_NORMAL, 0x1.0000000000001p-1022, Double.MIN_VALUE,
        0x1p-1060, 0x0.fffffffffffffp-1022}) {
      for (double b : new double[] {0x1.fffffffffffffp-1, 0x1.ffffffffffffep-1, 0.75, 1.5, 2.5,
          1.0 / 3}) {
        rational(edge, a, '*', b, a * b);
        rational(edge, -a, '*', b, -a * b);
      }
    }
    rational(edge, Double.MIN_NORMAL, '/', 3.0, Double.MIN_NORMAL / 3.0);
    rational(edge, -Double.MIN_NORMAL, '/', 3.0, -Double.MIN_NORMAL / 3.0);
    equations(out, "jdouble.near(.sub) is Java's * and / at the subnormals", edge);
    var bits = new ArrayList<String>();
    for (double q : new double[] {Double.MIN_NORMAL, 0.1, 1.0, 1.65, 2.0, 2.3665,
        4503599627370497.0, 1e300, Double.MAX_VALUE}) {
      for (double sq : new double[] {q, -q}) {
        long raw = Double.doubleToRawLongBits(sq);
        String s = sq < 0 ? "(- 1)" : "1";
        long mant = (raw & MANTISSA_52) | P52;
        String e = lit(Math.getExponent(sq) - 52);
        bits.add("(= (jdouble.bits " + s + " " + mant + " " + e + ") " + lit(raw) + ")");
        bits.add("(= (jdouble.fromBits.S " + lit(raw) + ") " + s + ")");
        bits.add("(= (jdouble.fromBits.M " + lit(raw) + ") " + mant + ")");
        bits.add("(= (jdouble.fromBits.E " + lit(raw) + ") " + e + ")");
      }
    }
    equations(out, "jdouble.bits, fromBits are Java's raw bits, both ways", bits);
  }

  /**
   * Each double has one encoding: S is 1 or -1, a normal M is in [2^52, 2^53) and a subnormal M
   * in (0, 2^52), so Java's 2^53 is M = 2^52 in binade 2^1, never M = 2^53 in binade 2^0. The
   * ranges are checked at both ends and one past each, and S at 0; then through the rounding,
   * with the value and the double free and the binade fixed, so that a range made wider or
   * dropped from jdouble.near or jdouble.near.sub fails here: a value rounds only to an S and an
   * M in range.
   */
  private static void encodings(StringBuilder out) {
    // The ranges written out rather than through jdouble.S.in and jdouble.M.in, so that a wrong
    // range there cannot agree with itself here.
    var ranges = List.of("(jdouble.S.in 1)", "(jdouble.S.in (- 1))", "(not (jdouble.S.in 0))",
        "(jdouble.M.in " + P52 + ")", "(jdouble.M.in " + (P53 - 1) + ")",
        "(not (jdouble.M.in " + (P52 - 1) + "))", "(not (jdouble.M.in " + P53 + "))");
    equations(out, "jdouble.S.in, M.in hold at their ends, not past them", ranges);
    check(out, "jdouble.near rounds only to an S and an M of a normal double", "unsat", List.of(
        "(declare-const a Int) (declare-const S Int) (declare-const M Int)",
        "(assert (jdouble.near a 1 S M 1 1))",
        "(assert (not (and " + SIGN + " (<= " + P52 + " M) (< M " + P53 + "))))"));
    check(out, "jdouble.near.sub rounds only to an S and an M of a subnormal", "unsat", List.of(
        "(declare-const a Int) (declare-const S Int) (declare-const M Int)",
        "(assert (jdouble.near.sub a 1 S M))",
        "(assert (not (and " + SIGN + " (< 0 M) (< M " + P52 + "))))"));
  }

  /** S is 1 or -1, written out. */
  private static final String SIGN = "(or (= S 1) (= S (- 1)))";

  /** The exact value of a finite double, as a numerator over a power-of-two denominator. */
  private record Exact(BigInteger num, BigInteger den) {

    static Exact of(double d) {
      long raw = Double.doubleToRawLongBits(d);
      int biased = (int) ((raw >>> 52) & 0x7ff);
      long mant = biased == 0 ? raw & MANTISSA_52 : (raw & MANTISSA_52) | P52;
      int e = (biased == 0 ? 1 : biased) - 1075;
      BigInteger m = BigInteger.valueOf(d < 0 ? -mant : mant);
      return e >= 0 ? new Exact(m.shiftLeft(e), BigInteger.ONE)
          : new Exact(m, BigInteger.ONE.shiftLeft(-e));
    }
  }

  /**
   * That Java's {@code x op y}, {@code q}, is what the prelude rounds the exact result to, written
   * as a numerator over a positive denominator. A result of zero is left out, since zero is not
   * in the prelude: a proof whose value can round to it states its own case.
   */
  private static void rational(List<String> eqs, double x, char op, double y, double q) {
    if (q == 0) {
      return;
    }
    Exact a = Exact.of(x);
    Exact b = Exact.of(y);
    BigInteger num = switch (op) {
      case '+' -> a.num().multiply(b.den()).add(b.num().multiply(a.den()));
      case '*' -> a.num().multiply(b.num());
      case '/' -> a.num().multiply(b.den());
      default -> throw new IllegalArgumentException("no such operation: " + op);
    };
    BigInteger den = op == '/' ? a.den().multiply(b.num()) : a.den().multiply(b.den());
    if (den.signum() < 0) {
      num = num.negate();
      den = den.negate();
    }
    rounded(eqs, num, den, q);
  }

  /**
   * That {@code a / b} rounds to {@code q}, which Java computed, and to neither neighbour of
   * {@code q}: the next double up and the next down, unless that is zero.
   */
  private static void rounded(List<String> eqs, BigInteger a, BigInteger b, double q) {
    eqs.add(near(a, b, q));
    for (double neighbour : new double[] {Math.nextUp(q), Math.nextDown(q)}) {
      if (neighbour != 0) {
        eqs.add("(not " + near(a, b, neighbour) + ")");
      }
    }
  }

  /**
   * {@code (jdouble.near a b S M sn sd)} with the normal {@code q} as S, M and its binade, or
   * {@code (jdouble.near.sub a b S M)} with the subnormal {@code q}, whose M counts units of
   * 2^-1074; the rational {@code a / b} is passed as it is in both.
   */
  private static String near(BigInteger a, BigInteger b, double q) {
    if (q == 0 || !Double.isFinite(q)) {
      throw new IllegalArgumentException("not a nonzero finite double: " + q);
    }
    long raw = Double.doubleToRawLongBits(q);
    String s = q < 0 ? "(- 1)" : "1";
    if (Math.abs(q) < Double.MIN_NORMAL) {
      return "(jdouble.near.sub " + lit(a) + " " + lit(b) + " " + s + " " + (raw & MANTISSA_52)
          + ")";
    }
    long mant = (raw & MANTISSA_52) | P52;
    int e = Math.getExponent(q) - 52;
    BigInteger scale = BigInteger.ONE.shiftLeft(Math.abs(e));
    String sn = e >= 0 ? lit(scale) : "1";
    String sd = e >= 0 ? "1" : lit(scale);
    return "(jdouble.near " + lit(a) + " " + lit(b) + " " + s + " " + mant + " " + sn + " " + sd
        + ")";
  }

  private static void range(StringBuilder out, String in, String type, long min, long max) {
    BigInteger below = BigInteger.valueOf(min).subtract(BigInteger.ONE);
    BigInteger above = BigInteger.valueOf(max).add(BigInteger.ONE);
    var eqs = List.of("(" + in + " " + lit(min) + ")", "(" + in + " " + lit(max) + ")",
        "(not (" + in + " " + lit(below) + "))", "(not (" + in + " " + lit(above) + "))");
    equations(out, in + " holds at the " + type + " range's ends, not past them", eqs);
  }

  private static void ints(StringBuilder out, String name, String java, int[] rights,
      IntBinaryOperator op) {
    var eqs = new ArrayList<String>();
    for (int a : INTS) {
      for (int b : rights) {
        eqs.add(eq(name, lit(a) + " " + lit(b), lit(op.applyAsInt(a, b))));
      }
    }
    equations(out, name + " is Java's " + java, eqs);
  }

  private static void longs(StringBuilder out, String name, String java, long[] rights,
      LongBinaryOperator op) {
    var eqs = new ArrayList<String>();
    for (long a : LONGS) {
      for (long b : rights) {
        eqs.add(eq(name, lit(a) + " " + lit(b), lit(op.applyAsLong(a, b))));
      }
    }
    equations(out, name + " is Java's " + java, eqs);
  }

  private static String eq(String name, String args, String expected) {
    return "(= (" + name + " " + args + ") " + expected + ")";
  }

  /** One check that every equation holds: unsat for the negation of their conjunction. */
  private static void equations(StringBuilder out, String what, List<String> eqs) {
    var lines = new ArrayList<String>();
    lines.add("(assert (not (and");
    for (String e : eqs) {
      lines.addAll(wrap("  " + e));
    }
    lines.add(")))");
    check(out, what + ", on " + eqs.size() + " operand sets", "unsat", lines);
  }

  static void check(StringBuilder out, String what, String expect, List<String> body) {
    String echo = "(echo \"" + what + ": expect " + expect + "\")";
    if (echo.length() > 100) {
      throw new IllegalArgumentException("an expectation line past 100 columns: " + echo);
    }
    out.append('\n').append(echo).append('\n');
    out.append("(push 1)\n");
    for (String line : body) {
      out.append(line).append('\n');
    }
    out.append("(check-sat)\n(pop 1)\n");
  }

  /** The widest a rendered line is, as every line of the repository. */
  private static final int WIDTH = 100;

  /**
   * A line broken at spaces into lines of at most {@link #WIDTH} columns, each continuation
   * indented two more than the first. SMT-LIB lets a term span lines anywhere between tokens.
   */
  static List<String> wrap(String line) {
    var lines = new ArrayList<String>();
    String indent = line.substring(0, line.length() - line.stripLeading().length()) + "  ";
    String rest = line;
    while (rest.length() > WIDTH) {
      int at = rest.lastIndexOf(' ', WIDTH);
      if (at <= indent.length()) {
        throw new IllegalArgumentException("a token too wide to wrap: " + rest);
      }
      lines.add(rest.substring(0, at));
      rest = indent + rest.substring(at + 1);
    }
    lines.add(rest);
    return lines;
  }

  /** An SMT-LIB integer literal: a negative one is {@code (- n)}. */
  static String lit(long v) {
    return lit(BigInteger.valueOf(v));
  }

  /** The widest numeral written as digits; a wider one is built from narrow ones. */
  private static final int NUMERAL_BITS = 192;

  private static final BigInteger P62 = BigInteger.ONE.shiftLeft(62);

  /**
   * An SMT-LIB integer literal: a negative one is {@code (- n)}, and one wider than
   * {@link #NUMERAL_BITS} bits, which no line could hold, built from narrow numerals that
   * {@link #wrap} can break between: an odd part times factors of 2^62 when the odd part is
   * narrow, else its digits in base 2^62 in Horner's form, {@code (+ d0 (* 2^62 (+ d1 ...)))}.
   * The solvers fold the constant arithmetic before they reason.
   */
  static String lit(BigInteger v) {
    if (v.signum() < 0) {
      return "(- " + lit(v.negate()) + ")";
    }
    if (v.bitLength() <= NUMERAL_BITS) {
      return v.toString();
    }
    int twos = v.getLowestSetBit();
    BigInteger odd = v.shiftRight(twos);
    if (odd.bitLength() > NUMERAL_BITS) {
      BigInteger[] qr = v.divideAndRemainder(P62);
      return "(+ " + qr[1] + " (* " + P62 + " " + lit(qr[0]) + "))";
    }
    var product = new StringBuilder("(*");
    if (!odd.equals(BigInteger.ONE)) {
      product.append(' ').append(odd);
    }
    for (; twos >= 62; twos -= 62) {
      product.append(' ').append(P62);
    }
    if (twos > 0) {
      product.append(' ').append(BigInteger.ONE.shiftLeft(twos));
    }
    return product.append(')').toString();
  }
}
