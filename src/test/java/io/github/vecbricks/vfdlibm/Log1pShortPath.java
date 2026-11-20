/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.util.SplittableRandom;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * The search behind the question whether s_log1p.c's {@code hu == 0} path, {@code |f| < 2^-20},
 * can be folded into its general polynomial path: every input of a range through the C as
 * written and through the C sent down the general path ({@link Log1pTest#cLog1p}), counting the
 * inputs where the two doubles differ. One difference settles it: the fold is not exact.
 *
 * <p>Run it from the test classes, as {@code Sweep} is run:
 *
 * <pre>
 * java -cp target/classes:target/test-classes --add-modules jdk.incubator.vector \
 *   io.github.vecbricks.vfdlibm.Log1pShortPath sampled
 * java ... Log1pShortPath range 0x3feffffa00000000 0x3ff0000200000000 4
 * </pre>
 *
 * <p>{@code sampled} runs the published hard cases, seeded draws near every power of two of
 * {@code 1+x}, and seeded draws of x in (-1, -0.5) with {@code 1+x} near a power of two.
 * {@code range from to threads} runs every double whose raw bits are in {@code [from, to)}.
 *
 * <p>The {@code hu == 0} path runs only for x with {@code 1+x} within a few units of 2^-20
 * (relative) of a power of two, {@code 1+x} in [2^m - 3*2^(m-21), 2^m * (1 + 2^-20)): a window
 * of 2^33 to 2^35 doubles for each m (the C leaves {@code x} in (-0.2929, 0.41422) with {@code
 * hu = 1}, so m = 0 has none). {@link #K1_FROM} to {@link #K1_TO} is the window of m = 1, where
 * {@code k = 1}, and {@link #KM1_FROM} to {@link #KM1_TO} that of m = -1, where {@code k = -1}.
 * The counts in {@value #RESULTS} are over those two windows, and the other windows are not
 * searched. The long passes there ran a scratch program with the same transcription (its
 * constants as literals); this program is its committed form, and was run over two slices of
 * 2^24 doubles, around a known witness each, which the results file also holds.
 */
final class Log1pShortPath {

  private Log1pShortPath() {}

  /** x from {@code 1 - 3*2^-20}, where {@code 1+x} is within 3*2^-20 of 2 from below. */
  static final long K1_FROM = 0x3feffffa_00000000L;
  /** x up to {@code 1 + 2^-19}, where {@code 1+x} leaves {@code [2, 2 + 2^-19)}. */
  static final long K1_TO = 0x3ff00002_00000000L;
  /** x from {@code -(0.5 - 2^-21)}, by rising magnitude: {@code 1+x} below {@code 0.5 + 2^-21}. */
  static final long KM1_FROM = 0xbfdffffe_00000000L;
  /** x up to, not including, {@code -(0.5 + 3*2^-22)}, whose {@code 1+x} is the lowest. */
  static final long KM1_TO = 0xbfe00001_80000000L;

  /** Where the counts of the two windows, and how they were made, are. */
  static final String RESULTS = "bench/results/log1p-hu0-search-laptop-2026-11-20.txt";

  /** How many differing inputs a run prints. */
  private static final int PRINTED = 16;

  /** Inputs per task of a range. */
  private static final long CHUNK = 1L << 20;

  /**
   * {@code n} draws within 2^-21, relatively, of each power of two of {@code u = 1+x} from 2^-30
   * to 2^60, either side, as {@code x = u - 1}.
   */
  static double[] nearPowersOfTwo(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[91 * n];
    int next = 0;
    for (int e = -30; e <= 60; e++) {
      for (int i = 0; i < n; i++) {
        double u = Math.scalb(1.0 + (random.nextDouble() - 0.5) * 0x1p-20, e);
        xs[next++] = u - 1.0;
      }
    }
    return xs;
  }

  /**
   * {@code n} draws of x in (-1, -0.5) for each power of two 2^e of {@code 1+x}, e from -1 down
   * to -52, with {@code 1+x} within 2^-21, relatively, of it: the interval an earlier sample of
   * the question left out.
   */
  static double[] negativeNearPowersOfTwo(long seed, int n) {
    SplittableRandom random = new SplittableRandom(seed);
    double[] xs = new double[52 * n];
    int next = 0;
    for (int e = -1; e >= -52; e--) {
      for (int i = 0; i < n; i++) {
        double u = Math.scalb(1.0 + (random.nextDouble() - 0.5) * 0x1p-20, e);
        xs[next++] = u - 1.0;
      }
    }
    return xs;
  }

  /** What a run counted. */
  private static final class Counts {
    final AtomicLong inputs = new AtomicLong();
    final AtomicLong shortPath = new AtomicLong();
    final AtomicLong differ = new AtomicLong();
    final AtomicLong notOracle = new AtomicLong();
    final AtomicInteger printed = new AtomicInteger();

    void add(long n, long sp, long d, long no) {
      inputs.addAndGet(n);
      shortPath.addAndGet(sp);
      differ.addAndGet(d);
      notOracle.addAndGet(no);
    }

    void print(String what, long nanos) {
      System.out.printf("%s: %d inputs, %d on the hu == 0 path, %d differ, %d where the C as"
          + " written is not StrictMath.log1p, %.1f s%n", what, inputs.get(), shortPath.get(),
          differ.get(), notOracle.get(), nanos / 1e9);
    }
  }

  /**
   * One input through both paths. Returns bit 0 set if it is on the short path, bit 1 if the two
   * paths differ, bit 2 if the C as written is not the oracle (checked when {@code oracle}).
   */
  private static int one(double x, boolean oracle, Counts counts) {
    long written = Double.doubleToRawLongBits(Log1pTest.cLog1p(x, false));
    long general = Double.doubleToRawLongBits(Log1pTest.cLog1p(x, true));
    int flags = Log1pTest.takesShortPath(x) ? 1 : 0;
    if (written != general) {
      flags |= 2;
      if (counts.printed.incrementAndGet() <= PRINTED) {
        System.out.printf("differs at %s: the C %s, its general path %s, StrictMath %s%n",
            Oracle.hex(x), Oracle.hex(Double.longBitsToDouble(written)),
            Oracle.hex(Double.longBitsToDouble(general)), Oracle.hex(StrictMath.log1p(x)));
      }
    }
    if (oracle && written != Double.doubleToRawLongBits(StrictMath.log1p(x))) {
      flags |= 4;
    }
    return flags;
  }

  private static void sampled(String what, double[] xs) {
    Counts counts = new Counts();
    long start = System.nanoTime();
    for (double x : xs) {
      int flags = one(x, true, counts);
      counts.add(1, flags & 1, (flags >> 1) & 1, (flags >> 2) & 1);
    }
    counts.print(what, System.nanoTime() - start);
  }

  /** Every double with raw bits in {@code [from, to)}; the oracle on one input in 1024. */
  private static void range(long from, long to, int threads) throws Exception {
    Counts counts = new Counts();
    long start = System.nanoTime();
    long chunks = (to - from + CHUNK - 1) / CHUNK;
    ForkJoinPool pool = new ForkJoinPool(threads);
    try {
      pool.submit(() -> LongStream.range(0, chunks).parallel().forEach(chunk -> {
        long lo = from + chunk * CHUNK;
        long hi = Math.min(to, lo + CHUNK);
        long sp = 0;
        long d = 0;
        long no = 0;
        for (long b = lo; b < hi; b++) {
          int flags = one(Double.longBitsToDouble(b), (b & 1023) == 0, counts);
          sp += flags & 1;
          d += (flags >> 1) & 1;
          no += (flags >> 2) & 1;
        }
        counts.add(hi - lo, sp, d, no);
      })).get();
    } finally {
      pool.shutdown();
    }
    counts.print(String.format("bits [0x%016x, 0x%016x) on %d threads", from, to, threads),
        System.nanoTime() - start);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("sampled")) {
      sampled("the published hard cases", Inputs.hardCases("log1p"));
      sampled("near the powers of two of 1+x, seed 5100", nearPowersOfTwo(5100L, 1 << 18));
      sampled("x in (-1, -0.5) near them, seed 5101", negativeNearPowersOfTwo(5101L, 1 << 18));
    } else if (args.length == 4 && args[0].equals("range")) {
      long from = Long.parseUnsignedLong(args[1].replaceFirst("^0x", ""), 16);
      long to = Long.parseUnsignedLong(args[2].replaceFirst("^0x", ""), 16);
      if (from >= to || (from < 0) != (to - 1 < 0)) {
        throw new IllegalArgumentException("a range of raw bits, from below to, of one sign");
      }
      range(from, to, Integer.parseInt(args[3]));
    } else {
      System.err.println("Log1pShortPath: sampled | range <from bits> <to bits> <threads>");
      System.exit(2);
    }
  }
}
