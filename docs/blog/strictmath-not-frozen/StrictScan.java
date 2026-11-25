import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * StrictMath's and Math's results for the 20 double functions of StrictMath whose results may
 * differ by JDK or by CPU (not floor, ceil, rint, scalb, ulp, nextUp, fma, toRadians and the like), on seeded inputs, written in input order to <out>/<StrictMath|Math>-<fn>.bin so two
 * JDKs compare input by input (compare.py --all). It needs no vfdlibm and no Vector API, so it
 * runs on JDK 17 and later. The inputs per function: the hard cases of vfdlibm's tests where it
 * has them, then 1,000,000 each of uniform bits, moderate magnitudes and values near 1 (as in
 * JdkScan, by the same seeds), and for pow 1,000,000 more aimed at fdlibm's branch for
 * |y| > 2^31 with |x| near 1. A lane whose two arguments are both NaN is written as 0, since
 * which NaN StrictMath returns there depends on the JIT tier.
 *
 * <pre>
 * java StrictScan.java ../../../src/test/resources/io/github/vecbricks/vfdlibm/hard-cases out-<jdk>
 * </pre>
 */
public class StrictScan {
  static final int N = 1_000_000;

  public static void main(String[] a) throws Exception {
    Path hard = Path.of(a[0]), out = Path.of(a[1]);
    Files.createDirectories(out);
    Map<String, DoubleUnaryOperator[]> one = new LinkedHashMap<>();
    one.put("sin", new DoubleUnaryOperator[] {StrictMath::sin, Math::sin});
    one.put("cos", new DoubleUnaryOperator[] {StrictMath::cos, Math::cos});
    one.put("tan", new DoubleUnaryOperator[] {StrictMath::tan, Math::tan});
    one.put("asin", new DoubleUnaryOperator[] {StrictMath::asin, Math::asin});
    one.put("acos", new DoubleUnaryOperator[] {StrictMath::acos, Math::acos});
    one.put("atan", new DoubleUnaryOperator[] {StrictMath::atan, Math::atan});
    one.put("exp", new DoubleUnaryOperator[] {StrictMath::exp, Math::exp});
    one.put("log", new DoubleUnaryOperator[] {StrictMath::log, Math::log});
    one.put("log10", new DoubleUnaryOperator[] {StrictMath::log10, Math::log10});
    one.put("sqrt", new DoubleUnaryOperator[] {StrictMath::sqrt, Math::sqrt});
    one.put("cbrt", new DoubleUnaryOperator[] {StrictMath::cbrt, Math::cbrt});
    one.put("sinh", new DoubleUnaryOperator[] {StrictMath::sinh, Math::sinh});
    one.put("cosh", new DoubleUnaryOperator[] {StrictMath::cosh, Math::cosh});
    one.put("tanh", new DoubleUnaryOperator[] {StrictMath::tanh, Math::tanh});
    one.put("expm1", new DoubleUnaryOperator[] {StrictMath::expm1, Math::expm1});
    one.put("log1p", new DoubleUnaryOperator[] {StrictMath::log1p, Math::log1p});
    Map<String, DoubleBinaryOperator[]> two = new LinkedHashMap<>();
    two.put("atan2", new DoubleBinaryOperator[] {StrictMath::atan2, Math::atan2});
    two.put("hypot", new DoubleBinaryOperator[] {StrictMath::hypot, Math::hypot});
    two.put("pow", new DoubleBinaryOperator[] {StrictMath::pow, Math::pow});
    two.put("IEEEremainder", new DoubleBinaryOperator[] {StrictMath::IEEEremainder, Math::IEEEremainder});
    System.out.println("# " + System.getProperty("java.runtime.version"));
    for (var e : one.entrySet()) {
      List<double[]> in = inputs(hard, e.getKey(), 1);
      for (int k = 0; k < 2; k++) {
        DoubleUnaryOperator f = e.getValue()[k];
        write(out, k, e.getKey(), in, p -> f.applyAsDouble(p[0]));
      }
    }
    for (var e : two.entrySet()) {
      List<double[]> in = inputs(hard, e.getKey(), 2);
      for (int k = 0; k < 2; k++) {
        DoubleBinaryOperator f = e.getValue()[k];
        write(out, k, e.getKey(), in, p -> Double.isNaN(p[0]) && Double.isNaN(p[1]) ? 0.0 : f.applyAsDouble(p[0], p[1]));
      }
    }
  }

  static void write(Path out, int k, String fn, List<double[]> in, ToDoubleFunction<double[]> f) throws IOException {
    long digest = 0;
    try (var w = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve((k == 0 ? "StrictMath-" : "Math-") + fn + ".bin"))))) {
      for (double[] p : in) {
        long b = Double.doubleToRawLongBits(f.applyAsDouble(p));
        w.writeLong(b);
        digest = digest * 31 + b;
      }
    }
    System.out.printf("%-10s %-14s n=%d digest=%016x%n", k == 0 ? "StrictMath" : "Math", fn, in.size(), digest);
  }

  /** JdkScan's inputs, by the same seeds; functions vfdlibm has no hard cases for get none. */
  static List<double[]> inputs(Path hard, String fn, int arity) throws IOException {
    List<double[]> in = new ArrayList<>();
    // The function's own file first, then the others by name, descending: the order the
    // published runs read them in, fixed here so that it does not depend on the filesystem.
    List<Path> files = new ArrayList<>();
    try (var ds = Files.newDirectoryStream(hard, fn + "*.txt")) {
      ds.forEach(files::add);
    }
    files.sort(Comparator.comparing((Path f) -> !f.getFileName().toString().equals(fn + ".txt"))
        .thenComparing(f -> f.getFileName().toString(), Comparator.reverseOrder()));
    {
      for (Path f : files) {
        String base = f.getFileName().toString();
        if (!(base.equals(fn + ".txt") || base.startsWith(fn + "-"))) continue;
        for (String l : Files.readAllLines(f)) {
          l = l.strip();
          if (l.isEmpty() || l.startsWith("#")) continue;
          String[] t = l.split("\\s+");
          double[] p = new double[arity];
          for (int k = 0; k < arity; k++) p[k] = Double.longBitsToDouble(Long.parseUnsignedLong(t[k].substring(2), 16));
          in.add(p);
        }
      }
    }
    Random r = new Random(fn.hashCode() * 31L + 20261125L);
    for (int i = 0; i < N; i++) { double[] p = new double[arity]; for (int k = 0; k < arity; k++) p[k] = Double.longBitsToDouble(r.nextLong()); in.add(p); }
    for (int i = 0; i < N; i++) { double[] p = new double[arity]; for (int k = 0; k < arity; k++) p[k] = Math.scalb(r.nextDouble() * 2 - 1, r.nextInt(80) - 60); in.add(p); }
    for (int i = 0; i < N; i++) { double[] p = new double[arity]; for (int k = 0; k < arity; k++) p[k] = (r.nextBoolean() ? 1 : -1) * (1 + Math.scalb(r.nextDouble() * 2 - 1, -r.nextInt(53))); in.add(p); }
    if (fn.equals("pow")) {
      long[] hi = {0x3fefffffL, 0x3ff00000L, 0x3feffffeL, 0x3ff00001L};
      for (int i = 0; i < N; i++) {
        long xb = (hi[r.nextInt(4)] << 32) | (r.nextLong() & 0xffffffffL);
        if (r.nextBoolean()) xb |= 1L << 63;
        double ay = Math.scalb(1.0 + r.nextDouble(), 31 + r.nextInt(33));
        double y = r.nextInt(4) == 0 ? (double) (long) ay : ay;
        in.add(new double[] {Double.longBitsToDouble(xb), r.nextBoolean() ? -y : y});
      }
    }
    return in;
  }
}
