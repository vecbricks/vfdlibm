/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jdk.incubator.vector.VectorSpecies;

/**
 * The exhaustive sweep of the high word: every one of the 2^32 high words, both signs, each with
 * three low words, 0, 0xffffffff and one drawn per high word from the seed, checked for one
 * function at one species by {@link Nightly.BlockCheck}, every arm unmasked and masked. fdlibm
 * decides its branches on the high word (exp's and expm1's filters, reductions and tiny tests;
 * log's subnormal, non-finite and significand tests, which log10 shares; log1p's; sinh's tiny, 1
 * and 22; cosh's tiny, 0.5 ln2 and 22; atan's tiny, 7/16, 11/16, 19/16, 39/16 and 2^66), so
 * every such decision is taken by some input here. asin's (2^-27, 0.5, 0.975 and 1) and acos's
 * (the constant return at 2^-57's word, 0.5 and 1) can be taken by a sweep of each run by hand;
 * both are in {@code Nightly}'s table and not yet in the night's list. The low word of the
 * third input is {@code mix(seed + hi * 0x9e3779b97f4a7c15L)}, so any input of any part is
 * reproducible from the seed alone, whatever the parts and threads.
 * Taking every high-word branch is not detecting every moved split: a split one step off on the
 * high word changes few of these inputs (SKILLS.md, "The nightly sweep and fuzz"), and the fuzz,
 * which draws the neighbourhood of each split, is what catches it.
 *
 * <pre>
 *   java --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
 *     io.github.vecbricks.vfdlibm.Sweep --function exp --species 256 --part 3/4096 \
 *     --seed 0x1234 --threads 4 --out target/nightly/mismatches.txt
 * </pre>
 *
 * <p>A function of two arguments takes {@code --argument 1} (the default) or {@code 2}: the
 * high words swept are that argument's, each with its three low words, and each swept value gets
 * a partner for the other argument, drawn from the seed, the argument and the input's index
 * alone ({@link #partner}), so any pair of the pass replays from the seed. A pair
 * is checked once in a pass, so a night that sweeps both arguments checks 3 * 2^33 pairs of the
 * 2^128. A bug that needs two particular high words together is left to the {@link Fuzz}, which
 * draws both arguments at their edges at once.
 *
 * <p>{@code --part i/N} checks the i-th of N equal parts of the high words (N a power of two up
 * to 2^32; 0/1, the default, is all of them), and a comma-separated list of parts checks each.
 * {@code --stripe s/S} instead cuts the high words into {@link #CHUNKS} chunks of 2^20 and checks
 * those whose number is {@code s} modulo {@code S} (S a power of two up to {@code CHUNKS}): S
 * stripes cover every high word, and each takes its share of every exponent and both signs, so
 * stripes run as parallel jobs take about equal time, where equal parts would not (half the high
 * words are negative, for which log is quick). {@code --threads} defaults to the available
 * processors. The exit status is {@link Nightly#OK} when every lane matched, {@link
 * Nightly#MISMATCH} when any differed (the mismatches are in {@code --out}), and {@link
 * Nightly#ERROR} for a usage error or a harness failure.
 */
final class Sweep {

  /** High words per block: 3072 inputs, a multiple of every lane count. */
  static final int BLOCK = 1024;
  /** The chunks of 2^20 high words that {@code --stripe} deals out. */
  static final int CHUNKS = 4096;
  /** Seconds between progress lines. */
  static final long PROGRESS_SECONDS = 60;

  private Sweep() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    Nightly.Subject function;
    int argument = 1;
    VectorSpecies<Double> species;
    long[][] ranges;
    String part;
    long seed;
    int threads;
    Path out;
    try {
      Map<String, String> options =
          Nightly.options(
              args, "function", "species", "part", "stripe", "argument", "seed", "threads",
              "out");
      function = Nightly.subject(Nightly.require(options, "function"));
      if (function.arity() == 2) {
        String text = options.getOrDefault("argument", "1");
        if (!text.equals("1") && !text.equals("2")) {
          throw new IllegalArgumentException("--argument takes 1 or 2, not '" + text + "'");
        }
        argument = Integer.parseInt(text);
      } else if (options.containsKey("argument")) {
        throw new IllegalArgumentException("--argument is for a function of two arguments");
      }
      species = Nightly.species(options.getOrDefault("species", "preferred"));
      if (options.containsKey("stripe")) {
        if (options.containsKey("part")) {
          throw new IllegalArgumentException("--part and --stripe exclude each other");
        }
        part = "--stripe " + options.get("stripe");
        ranges = stripe(options.get("stripe"));
      } else {
        part = "--part " + options.getOrDefault("part", "0/1");
        ranges = ranges(options.getOrDefault("part", "0/1"));
      }
      if (function.arity() == 2) {
        part += " --argument " + argument;
      }
      seed = Nightly.parseNumber(Nightly.require(options, "seed"));
      threads = Integer.parseInt(
          options.getOrDefault("threads", "" + Runtime.getRuntime().availableProcessors()));
      if (threads < 1) {
        throw new IllegalArgumentException("--threads takes a positive number");
      }
      out = Path.of(options.getOrDefault("out", "target/nightly/sweep-mismatches.txt"));
    } catch (IllegalArgumentException e) {
      System.err.println("Sweep: " + e.getMessage());
      return Nightly.ERROR;
    }

    StringBuilder words = new StringBuilder();
    long total = 0;
    long[] firstBlock = new long[ranges.length + 1];
    for (int r = 0; r < ranges.length; r++) {
      if (r < 4) {
        words.append(String.format("%s[0x%08x, 0x%08x)", r == 0 ? "" : " ", ranges[r][0],
            ranges[r][1]));
      }
      total += ranges[r][1] - ranges[r][0];
      firstBlock[r + 1] = firstBlock[r] + (ranges[r][1] - ranges[r][0] + BLOCK - 1) / BLOCK;
    }
    if (ranges.length > 4) {
      words.append(" and ").append(ranges.length - 4).append(" more ranges");
    }
    String what = String.format("sweep %s at %d bits, %s: %d high words, %s",
        function.id(), species.vectorBitSize(), part, total, words);
    System.out.println(what);
    System.out.println("seed: " + Nightly.seedText(seed));
    System.out.println("arms: " + function.armNames());
    System.out.println("threads: " + threads);
    Nightly.printHost(species);
    String context = String.format("function=%s species=%s seed=%s", function.id(), species,
        Nightly.seedText(seed));
    String replay = String.format("Sweep --function %s --species %s %s --seed %s",
        function.id(), Nightly.speciesName(species), part, Nightly.seedText(seed));

    final Nightly.Subject subject = function;
    final int swept = argument;
    final double[] specials = function instanceof Nightly.PairFunction pair
        ? pair.edges().specials() : null;
    long blocks = firstBlock[ranges.length];
    AtomicLong next = new AtomicLong();
    AtomicLong done = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    long start = System.nanoTime();
    try (Nightly.Mismatches mismatches = new Nightly.Mismatches(
        out, what + "\nreplay: " + replay + "\njvm args: " + Nightly.jvmArgs()
            + "\nplanted fault: " + Faults.planted(), context, subject.arity())) {
      List<Thread> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        Thread worker = new Thread(() -> {
          try {
            Nightly.BlockCheck checker = subject.newChecker(species, seed, mismatches);
            double[] xs = new double[3 * BLOCK];
            double[] ys = specials == null ? null : new double[3 * BLOCK];
            for (long b; (b = next.getAndIncrement()) < blocks && !mismatches.enough(); ) {
              // The range holding block b: the last whose first block is at most b.
              int r = Arrays.binarySearch(firstBlock, 0, ranges.length, b);
              r = r >= 0 ? r : -r - 2;
              long first = ranges[r][0] + (b - firstBlock[r]) * BLOCK;
              int n = (int) Math.min(BLOCK, ranges[r][1] - first);
              for (int k = 0; k < n; k++) {
                long hi = (first + k) << 32;
                xs[3 * k] = Double.longBitsToDouble(hi);
                xs[3 * k + 1] = Double.longBitsToDouble(hi | 0xffffffffL);
                xs[3 * k + 2] = Double.longBitsToDouble(hi | lowWord(seed, first + k));
              }
              // A short last block is padded with its last input, which is checked twice.
              int filled = 3 * n;
              int len = (filled + species.length() - 1) / species.length() * species.length();
              for (int k = filled; k < len; k++) {
                xs[k] = xs[filled - 1];
              }
              if (ys != null) {
                // The swept values are the first or the second argument; the partners fill the
                // other, and the padding repeats the last pair.
                for (int k = 0; k < filled; k++) {
                  ys[k] = partner(seed, swept, 3 * first + k, xs[k], specials);
                }
                for (int k = filled; k < len; k++) {
                  ys[k] = ys[filled - 1];
                }
              }
              // xs holds the swept argument's values, ys their partners.
              if (swept == 2) {
                checker.check(ys, xs, len, 3 * first);
              } else {
                checker.check(xs, ys, len, 3 * first);
              }
              done.addAndGet(n);
            }
          } catch (Throwable e) {
            failure.compareAndSet(null, e);
          }
        }, "sweep-" + t);
        worker.setDaemon(true);
        worker.start();
        workers.add(worker);
      }
      for (Thread worker : workers) {
        while (worker.isAlive()) {
          worker.join(PROGRESS_SECONDS * 1000);
          if (worker.isAlive()) {
            progress(done.get(), total, start, mismatches.count());
          }
        }
      }
      if (failure.get() != null) {
        System.err.println("Sweep: a worker failed");
        failure.get().printStackTrace();
        return Nightly.ERROR;
      }
      progress(done.get(), total, start, mismatches.count());
      double seconds = (System.nanoTime() - start) / 1e9;
      System.out.printf("swept %d high words (%d inputs) in %.1f s: %.0f high words/s%n",
          done.get(), 3 * done.get(), seconds, done.get() / seconds);
      if (mismatches.count() > 0) {
        System.out.println("FAILED: " + mismatches.count() + " lane checks differ"
            + (mismatches.enough() ? " (stopped early) " : " ")
            + mismatches.perKind() + "; inputs in " + out);
        return Nightly.MISMATCH;
      }
      System.out.println("every lane matched StrictMath");
      return Nightly.OK;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Nightly.ERROR;
    } catch (RuntimeException e) {
      e.printStackTrace();
      return Nightly.ERROR;
    }
  }

  /**
   * The partner of the swept value {@code swept}, the {@code index}-th input of the pass (three a
   * high word): a function of the seed, the argument swept and the index alone, of one kind in
   * four, by the top two bits of its first mix: a value of {@code specials}, with the sign its
   * next bit gives; a double of the swept value's exponent; one within 64 binary exponents of it
   * (the offsets are not quite uniform: 4096 is not a multiple of 129); or random bits. The
   * significand and sign of the second and third kinds are random.
   */
  static double partner(long seed, int argument, long index, double swept, double[] specials) {
    long r = Nightly.mix(seed + 0xa5a5a5a5a5a5a5a5L * argument + index * 0x9e3779b97f4a7c15L);
    long bits = Nightly.mix(r);
    long more = Nightly.mix(bits);
    int exponent = (int) (Double.doubleToRawLongBits(swept) >>> 52 & 0x7ff);
    int kind = (int) (r >>> 62);
    if (kind == 0) {
      double special = specials[(int) ((more >>> 1) % specials.length)];
      return (bits & 1) == 0 ? special : -special;
    }
    if (kind == 1 || kind == 2) {
      int e = kind == 1 ? exponent
          : Math.max(0, Math.min(0x7ff, exponent + (int) ((more >>> 20 & 0xfff) % 129) - 64));
      return Double.longBitsToDouble(bits & 0x800fffffffffffffL | (long) e << 52);
    }
    return Double.longBitsToDouble(bits);
  }

  /** The low word of the third input of high word {@code hi}. */
  static long lowWord(long seed, long hi) {
    return Nightly.mix(seed + hi * 0x9e3779b97f4a7c15L) & 0xffffffffL;
  }

  /** The high words of each comma-separated part {@code i/N}, each as {@code [from, to)}. */
  static long[][] ranges(String parts) {
    String[] list = parts.split(",");
    long[][] ranges = new long[list.length][];
    for (int i = 0; i < list.length; i++) {
      ranges[i] = range(list[i]);
    }
    return ranges;
  }

  /** The chunks of stripe {@code s/S}, as ranges {@code [from, to)} of high words. */
  static long[][] stripe(String stripe) {
    long[] sS = fraction(stripe, CHUNKS, "--stripe");
    int count = (int) (CHUNKS / sS[1]);
    long[][] ranges = new long[count][];
    long size = (1L << 32) / CHUNKS;
    for (int i = 0; i < count; i++) {
      long chunk = sS[0] + i * sS[1];
      ranges[i] = new long[] {chunk * size, (chunk + 1) * size};
    }
    return ranges;
  }

  /** {@code i/N} as {@code {i, N}}, N a power of two up to {@code max} and {@code i < N}. */
  private static long[] fraction(String text, long max, String option) {
    String[] iN = text.split("/");
    try {
      if (iN.length == 2) {
        long i = Long.parseLong(iN[0]);
        long n = Long.parseLong(iN[1]);
        if (n >= 1 && n <= max && Long.bitCount(n) == 1 && i >= 0 && i < n) {
          return new long[] {i, n};
        }
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    throw new IllegalArgumentException(option + " takes i/N with N a power of two up to " + max
        + " and 0 <= i < N, not '" + text + "'");
  }

  /** The high words of part {@code i/N}, as {@code [from, to)}. */
  static long[] range(String part) {
    long[] iN = fraction(part, 1L << 32, "--part");
    long size = (1L << 32) / iN[1];
    return new long[] {iN[0] * size, (iN[0] + 1) * size};
  }

  private static void progress(long done, long total, long start, long mismatches) {
    double seconds = (System.nanoTime() - start) / 1e9;
    double rate = done / Math.max(seconds, 1e-9);
    System.out.printf("progress: %d of %d high words (%.1f%%) in %.0f s, %.0f/s, ETA %.0f s,"
        + " %d mismatches%n", done, total, 100.0 * done / total, seconds, rate,
        rate > 0 ? (total - done) / rate : 0.0, mismatches);
  }
}
