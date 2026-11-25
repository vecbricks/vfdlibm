import jdk.incubator.vector.*;
import io.github.vecbricks.vfdlibm.VFdlibm;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * For each of vfdlibm's 13 functions, reads the hard cases of vfdlibm's tests and adds 3,000,000
 * random inputs (uniform bits, moderate magnitudes, near 1), and for pow 1,000,000 more aimed at
 * fdlibm's branch for |y| > 2^31 with |x| near 1 (389,513 of them reach its arithmetic; see the
 * comment there and aimed.py). Writes StrictMath's raw bits, in input order, to
 * <out>/<fn>.bin (0 on a lane of two NaN arguments), Math.pow's to <out>/pow-Math.bin, pow's inputs
 * (x and y per input, in order) to <out>/pow-inputs.bin, and prints per function a digest and the
 * number of vfdlibm lanes that differ from StrictMath, at 256 bits and at the preferred species (a
 * lane of two NaN arguments is checked against the contract's two candidates instead). The inputs
 * are seeded, so two JDKs' outputs compare input by input (compare.py).
 *
 * <pre>
 * javac --add-modules jdk.incubator.vector -cp vfdlibm-0.3.0.jar -d . JdkScan.java
 * java --add-modules jdk.incubator.vector -cp .:vfdlibm-0.3.0.jar JdkScan \
 *     ../../../src/test/resources/io/github/vecbricks/vfdlibm/hard-cases out-&lt;jdk&gt;
 * </pre>
 */
public class JdkScan {
  static final int N = 1_000_000;
  public static void main(String[] a) throws Exception {
    Path hard = Path.of(a[0]); Path out = Path.of(a[1]); Files.createDirectories(out);
    Map<String, DoubleUnaryOperator> one = new LinkedHashMap<>();
    one.put("acos", StrictMath::acos); one.put("asin", StrictMath::asin); one.put("atan", StrictMath::atan);
    one.put("cosh", StrictMath::cosh); one.put("sinh", StrictMath::sinh); one.put("exp", StrictMath::exp);
    one.put("expm1", StrictMath::expm1); one.put("log", StrictMath::log); one.put("log10", StrictMath::log10);
    one.put("log1p", StrictMath::log1p);
    Map<String, DoubleBinaryOperator> two = new LinkedHashMap<>();
    two.put("atan2", StrictMath::atan2); two.put("hypot", StrictMath::hypot); two.put("pow", StrictMath::pow);
    System.out.println("# " + System.getProperty("java.runtime.version"));
    for (var e : one.entrySet()) {
      String fn = e.getKey(); List<double[]> in = inputs(hard, fn, 1);
      run(fn, in, out, p -> e.getValue().applyAsDouble(p[0]), null);
    }
    for (var e : two.entrySet()) {
      String fn = e.getKey(); List<double[]> in = inputs(hard, fn, 2);
      run(fn, in, out, p -> e.getValue().applyAsDouble(p[0], p[1]), fn);
      if (fn.equals("pow")) {
        try (var w = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("pow-Math.bin"))))) {
          for (double[] p : in)
            w.writeLong(Double.isNaN(p[0]) && Double.isNaN(p[1]) ? 0 : Double.doubleToRawLongBits(Math.pow(p[0], p[1])));
        }
        try (var w = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("pow-inputs.bin"))))) {
          for (double[] p : in) { w.writeLong(Double.doubleToRawLongBits(p[0])); w.writeLong(Double.doubleToRawLongBits(p[1])); }
        }
      }
    }
  }
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
          l = l.strip(); if (l.isEmpty() || l.startsWith("#")) continue;
          String[] t = l.split("\\s+"); double[] p = new double[arity];
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
      // Aimed at fdlibm's branch for |y| > 2^31 (e_pow.c line 191). Of the four high words of x,
      // 0x3fefffff and 0x3ff00000 put |x| within 2^-20 of 1, where that branch's arithmetic runs
      // unless x < 0 and y is not an integer (NaN first, line 185); 0x3feffffe and 0x3ff00001 sit
      // just outside and return early, by overflow or underflow, as neighbours. The integer
      // variant of y clamps at 2^63, since (long) saturates; the published runs used it as is.
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
  interface Oracle { double at(double[] p); }
  static void run(String fn, List<double[]> in, Path out, Oracle o, String two) throws IOException {
    int n = in.size(); long digest = 0;
    long[] sm = new long[n];
    try (var w = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(fn + ".bin"))))) {
      for (int i = 0; i < n; i++) {
        double[] p = in.get(i);
        sm[i] = Double.doubleToRawLongBits(o.at(p));
        // Which NaN StrictMath returns for two NaN arguments depends on the JIT tier, so the file
        // and the digest hold 0 there, as StrictScan's do; vfdlibm is checked against sm[i].
        long kept = two != null && Double.isNaN(p[0]) && Double.isNaN(p[1]) ? 0 : sm[i];
        w.writeLong(kept);
        digest = digest * 31 + kept;
      }
    }
    StringBuilder sb = new StringBuilder(String.format("%-6s n=%d StrictMath=%016x", fn, n, digest));
    for (VectorSpecies<Double> S : List.of(DoubleVector.SPECIES_256, DoubleVector.SPECIES_PREFERRED)) {
      int L = S.length(); int bad = 0; long firstBad = -1;
      double[] x = new double[L], y = new double[L], r = new double[L];
      for (int i = 0; i < n; i += L) {
        int m = Math.min(L, n - i);
        Arrays.fill(x, 1.0); Arrays.fill(y, 1.0);
        for (int k = 0; k < m; k++) { x[k] = in.get(i + k)[0]; if (two != null) y[k] = in.get(i + k)[1]; }
        var X = DoubleVector.fromArray(S, x, 0); var Y = DoubleVector.fromArray(S, y, 0);
        DoubleVector R = switch (fn) {
          case "acos" -> VFdlibm.acos(X); case "asin" -> VFdlibm.asin(X); case "atan" -> VFdlibm.atan(X);
          case "cosh" -> VFdlibm.cosh(X); case "sinh" -> VFdlibm.sinh(X); case "exp" -> VFdlibm.exp(X);
          case "expm1" -> VFdlibm.expm1(X); case "log" -> VFdlibm.log(X); case "log10" -> VFdlibm.log10(X);
          case "log1p" -> VFdlibm.log1p(X); case "atan2" -> VFdlibm.atan2(X, Y); case "hypot" -> VFdlibm.hypot(X, Y);
          case "pow" -> VFdlibm.pow(X, Y); default -> throw new IllegalArgumentException(fn);
        };
        R.intoArray(r, 0);
        for (int k = 0; k < m; k++) {
          long got = Double.doubleToRawLongBits(r[k]), want = sm[i + k];
          if (got == want) continue;
          if (two != null && Double.isNaN(x[k]) && Double.isNaN(y[k])) {
            long q = 1L << 51, xb = Double.doubleToRawLongBits(x[k]) | q, yb = Double.doubleToRawLongBits(y[k]) | q;
            if (fn.equals("hypot")) { xb &= Long.MAX_VALUE; yb &= Long.MAX_VALUE; }
            if (got == xb || got == yb) continue;
          }
          if (bad++ == 0) firstBad = i + k;
        }
      }
      sb.append(String.format("  vf%d diff=%d", S.vectorBitSize(), bad));
      if (firstBad >= 0) { double[] p = in.get((int) firstBad); sb.append(String.format(" first=%016x%s", Double.doubleToRawLongBits(p[0]), two != null ? "," + Long.toHexString(Double.doubleToRawLongBits(p[1])) : "")); }
    }
    System.out.println(sb);
  }
}
