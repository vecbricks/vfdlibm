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
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jdk.incubator.vector.VectorSpecies;

/**
 * Fuzzing for a fixed time: random inputs for one function at one species, checked by {@link
 * Nightly.Checker} like the {@link Sweep}'s, every arm unmasked and masked. The nightly workflow
 * draws a fresh seed each night and logs it. Each input is one of:
 *
 * <ul>
 *   <li>a uniformly random bit pattern (3 in 10), every exponent, NaN payload and sign;
 *   <li>a branch word's neighbourhood (1 in 4): a high word within 4 of one the C compares
 *       against, either sign, with a low word of 0, 1, all ones, all ones less one, or random;
 *   <li>a significand split's neighbourhood (1 in 5), for log, log10 and log1p: the low 20 bits
 *       of the high word within 4 of a split the C tests ({@code e_log.c}'s {@code 0x95f64} carry
 *       and its band, which log10's rescaled {@code x} keeps, and {@code s_log1p.c}'s {@code
 *       0x6a09e}), at a random exponent; for log1p the split is in {@code 1+x}'s word, so the
 *       input is that double less one;
 *   <li>a threshold's neighbourhood (1 in 4): a double the C's result changes form at, such as
 *       exp's overflow and underflow limits and the {@code (k+1/2) ln2} where its {@code k} steps,
 *       moved by up to 2^44 ulps, and of either sign one time in ten.
 * </ul>
 *
 * <p>The sweep takes three low words per high word; these take every other one, where the C's
 * results depend on the low word: exp's {@code k}, log's and log1p's reductions.
 *
 * <pre>
 *   java --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
 *     io.github.vecbricks.vfdlibm.Fuzz --function log --species 256 --seconds 60 \
 *     --seed 0x1234 --out target/nightly/fuzz-mismatches.txt
 * </pre>
 *
 * <p>Each thread draws from its own stream, {@code seed} mixed with the thread's number, so a run
 * is the same inputs in the same order per thread; how far each thread gets depends on the time.
 * A mismatch is reported with its input's bits, which replay it with no seed at all. The exit
 * status is as {@link Sweep}'s.
 */
final class Fuzz {

  /** Inputs per block, a multiple of every lane count. */
  static final int BLOCK = 4096;

  private Fuzz() {}

  /** Where a function's branches are: the high words, significand splits and doubles. */
  record Edges(int[] words, int[] splits, boolean splitOnOnePlusX, double[] points) {}

  /** exp: e_exp.c's high-word tests, its limits and the doubles where k steps. */
  static final Edges EXP = new Edges(
      new int[] {
        Exp.HX_FILTER, Exp.HX_HALF_LN2, Exp.HX_THREE_HALVES_LN2, Exp.HX_TINY,
        Exp.HX_NON_FINITE, 0x00100000, 0
      },
      new int[0],
      false,
      expPoints());

  /** log: e_log.c's high-word tests, and the significand splits of lines 110 to 131. */
  static final Edges LOG = new Edges(
      new int[] {Log.HX_MIN_NORMAL, Log.HX_NON_FINITE, 0x3ff00000, 0x7fefffff, 0},
      new int[] {
        0x100000 - Log.SQRT2_SPLIT, Log.HX_BAND_LO, Log.HX_BAND_HI, 0, 0xfffff, 0xffffd
      },
      false,
      new double[] {1.0, Math.sqrt(2.0), Math.sqrt(0.5), Double.MIN_NORMAL, Double.MAX_VALUE});

  /**
   * log10: e_log10.c's high-word tests and line 85's split at 1.0 (the high words 0x3fefffff and
   * 0x3ff00000), and e_log.c's significand splits, which the rescaled x keeps.
   */
  static final Edges LOG10 = new Edges(
      new int[] {
        Log10.HX_MIN_NORMAL, Log10.HX_NON_FINITE, 0x3ff00000, 0x3fefffff, 0x7fefffff, 0
      },
      LOG.splits(),
      false,
      new double[] {
        1.0, 10.0, 0.1, Math.sqrt(2.0), Math.sqrt(0.5), Double.MIN_NORMAL, Double.MAX_VALUE
      });

  /** log1p: s_log1p.c's tests on hx and ax, and its split of 1+x at sqrt(2). */
  static final Edges LOG1P = new Edges(
      new int[] {
        Log1p.HX_BELOW_SQRT2_M1, Log1p.AX_MINUS_ONE, Log1p.AX_TINY, Log1p.AX_TINIEST,
        Log1p.HX_NEGATIVE_K_ZERO, Log1p.HX_NON_FINITE, Log1p.HX_HUGE
      },
      new int[] {Log1p.HU_SQRT2, 0, 0xfffff},
      true,
      new double[] {
        -1.0, Math.sqrt(2.0) - 1.0, -0.29289321881345247, 0x1p53, 0x1p-29, 0x1p-54, 0x1p-20
      });

  /** exp's limits, 0.5 ln2 and 1.5 ln2, and {@code (k + 1/2) ln2} for every k a result has. */
  private static double[] expPoints() {
    List<Double> points = new ArrayList<>(List.of(
        Exp.O_THRESHOLD, Exp.U_THRESHOLD, 0.5 * StrictMath.log(2.0), 1.5 * StrictMath.log(2.0)));
    for (int k = -1080; k <= 1025; k++) {
      points.add((k + 0.5) * StrictMath.log(2.0));
    }
    return points.stream().mapToDouble(Double::doubleValue).toArray();
  }

  static Edges edges(Nightly.Function function) {
    return switch (function) {
      case EXP -> EXP;
      case LOG -> LOG;
      case LOG10 -> LOG10;
      case LOG1P -> LOG1P;
    };
  }

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    Nightly.Function function;
    VectorSpecies<Double> species;
    long seed;
    double budget;
    int threads;
    Path out;
    try {
      Map<String, String> options =
          Nightly.options(args, "function", "species", "seed", "seconds", "threads", "out");
      function = Nightly.Function.of(Nightly.require(options, "function"));
      species = Nightly.species(options.getOrDefault("species", "preferred"));
      seed = Nightly.parseNumber(Nightly.require(options, "seed"));
      budget = Double.parseDouble(Nightly.require(options, "seconds"));
      threads = Integer.parseInt(
          options.getOrDefault("threads", "" + Runtime.getRuntime().availableProcessors()));
      if (threads < 1 || !(budget > 0)) {
        throw new IllegalArgumentException("--threads and --seconds take positive numbers");
      }
      out = Path.of(options.getOrDefault("out", "target/nightly/fuzz-mismatches.txt"));
    } catch (IllegalArgumentException e) {
      System.err.println("Fuzz: " + e.getMessage());
      return Nightly.ERROR;
    }

    String what = String.format("fuzz %s at %d bits for %.0f s", function.id,
        species.vectorBitSize(), budget);
    System.out.println(what);
    System.out.println("seed: " + Nightly.seedText(seed));
    System.out.println("arms: " + Nightly.armNames(function));
    System.out.println("threads: " + threads);
    Nightly.printHost(species);
    String context = String.format("function=%s species=%s seed=%s", function.id, species,
        Nightly.seedText(seed));
    String replay = String.format("Fuzz --function %s --species %s --seed %s --threads %d",
        function.id, Nightly.speciesName(species), Nightly.seedText(seed), threads);

    Edges edges = edges(function);
    long start = System.nanoTime();
    long deadline = start + (long) (budget * 1e9);
    AtomicLong done = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (Nightly.Mismatches mismatches = new Nightly.Mismatches(
        out, what + "\nreplay: " + replay + "\njvm args: " + Nightly.jvmArgs()
            + "\nplanted fault: " + Faults.planted(), context)) {
      List<Thread> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        long stream = Nightly.mix(seed + t);
        long maskSeed = Nightly.mix(stream);
        Thread worker = new Thread(() -> {
          try {
            SplittableRandom random = new SplittableRandom(stream);
            Nightly.Checker checker =
                new Nightly.Checker(function, species, maskSeed, mismatches);
            double[] xs = new double[BLOCK];
            for (long position = 0; System.nanoTime() < deadline && !mismatches.enough();
                position += BLOCK) {
              for (int i = 0; i < BLOCK; i++) {
                xs[i] = draw(random, edges);
              }
              checker.check(xs, BLOCK, position);
              done.addAndGet(BLOCK);
            }
          } catch (Throwable e) {
            failure.compareAndSet(null, e);
          }
        }, "fuzz-" + t);
        worker.setDaemon(true);
        worker.start();
        workers.add(worker);
      }
      for (Thread worker : workers) {
        while (worker.isAlive()) {
          worker.join(Sweep.PROGRESS_SECONDS * 1000);
          if (worker.isAlive()) {
            System.out.printf("progress: %d inputs in %.0f s, %d mismatches%n", done.get(),
                (System.nanoTime() - start) / 1e9, mismatches.count());
          }
        }
      }
      if (failure.get() != null) {
        System.err.println("Fuzz: a worker failed");
        failure.get().printStackTrace();
        return Nightly.ERROR;
      }
      double seconds = (System.nanoTime() - start) / 1e9;
      System.out.printf("fuzzed %d inputs in %.1f s: %.0f inputs/s%n", done.get(), seconds,
          done.get() / seconds);
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

  /** One input, drawn as the class comment says. */
  static double draw(SplittableRandom random, Edges edges) {
    int kind = random.nextInt(20);
    if (kind < 6) {
      return Double.longBitsToDouble(random.nextLong());
    }
    if (kind < 11 || (kind < 15 && edges.splits().length == 0)) {
      int word = edges.words()[random.nextInt(edges.words().length)] + random.nextInt(-4, 5);
      long hi = (word ^ (random.nextBoolean() ? 0x80000000 : 0)) & 0xffffffffL;
      return Double.longBitsToDouble(hi << 32 | lowWord(random));
    }
    if (kind < 15) {
      int split = edges.splits()[random.nextInt(edges.splits().length)] + random.nextInt(-4, 5);
      // The biased exponent: near 1 for a split of 1+x, so that 1+x is the double built here;
      // anywhere from the subnormals to the largest finite exponent otherwise.
      long exponent = edges.splitOnOnePlusX() ? 1023 + random.nextInt(-60, 61)
          : random.nextInt(0, 2047);
      long hi = exponent << 20 | (split & 0xfffff);
      double u = Double.longBitsToDouble(hi << 32 | lowWord(random));
      double x = edges.splitOnOnePlusX() ? u - 1.0 : u;
      return random.nextInt(10) == 0 ? -x : x;
    }
    double point = edges.points()[random.nextInt(edges.points().length)];
    long ulps = random.nextLong(1L << random.nextInt(45)) * (random.nextBoolean() ? 1 : -1);
    double x = Double.longBitsToDouble(Double.doubleToRawLongBits(point) + ulps);
    return random.nextInt(10) == 0 ? -x : x;
  }

  /** A low word: 0, 1, all ones, all ones less one, or random, each as likely. */
  private static long lowWord(SplittableRandom random) {
    return switch (random.nextInt(5)) {
      case 0 -> 0L;
      case 1 -> 1L;
      case 2 -> 0xffffffffL;
      case 3 -> 0xfffffffeL;
      default -> random.nextLong() & 0xffffffffL;
    };
  }
}
