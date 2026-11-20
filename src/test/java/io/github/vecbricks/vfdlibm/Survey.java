/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The survey of one-step mutants: for each mutant of one function's C ({@link Mutants}), the
 * number of inputs whose result is no longer {@code StrictMath}'s, among the inputs the unit
 * tests give the function, its published hard cases, a fuzz of fixed length and one stripe of
 * the nightly sweep. A mutant that changes a result on some input of a layer is one that layer
 * would catch in a port that does the C's operations. {@code dev/survey.sh} runs it and {@code
 * dev/survey/README.md} holds the tables.
 *
 * <pre>
 *   java --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
 *     io.github.vecbricks.vfdlibm.Survey \
 *     --function exp --suite target/survey/inputs --fuzz 67108864 --stripe 0/64 \
 *     --seed 0x5eed --threads 2 --out dev/survey/exp.tsv
 * </pre>
 *
 * <p>The layers:
 *
 * <ul>
 *   <li>{@code --suite <dir>}: the inputs {@code Oracle.assertUnary} and {@code
 *       assertUnaryMasked} were given for the function in a run of the tests with {@code
 *       -Dvfdlibm.survey.record=<dir>}, each distinct input once. Inputs a test checks by
 *       other means (the lemmas' hinge tests of {@code ProofsTest}, {@code NightlyTest}'s few
 *       thousand) are not among them.
 *   <li>the hard cases of {@code hard-cases/<function>.txt}, a part of the suite counted on
 *       its own.
 *   <li>{@code --fuzz <n>}: {@code n} inputs of {@link Fuzz#draw}, in {@link #STREAMS} streams
 *       seeded as the fuzz's threads are, {@code Nightly.mix(seed + t)}; the table also gives
 *       the least position in a stream at which a mutant first differs.
 *   <li>{@code --stripe s/S}: the inputs of {@link Sweep}'s stripe, three per high word.
 *   <li>{@code --search <n>}, 0 by default: {@code n} inputs drawn evenly over the function's
 *       common path ({@link #searchTasks}). Nothing the project runs draws these; they are
 *       the search for an input that shows a mutant the layers above let through.
 * </ul>
 *
 * <p>{@code --only <regex>} keeps the mutants whose whole name matches, for a longer run on the
 * few that a first run left uncaught. With {@code --append true} a run adds its rows to an
 * {@code --out} that exists, so such a run can go one mutant at a time and be taken up again.
 *
 * <p>The output is tab-separated, one line per mutant after {@code #} lines of provenance: the
 * counts per layer, the first layer that catches it ({@code suite}, {@code fuzz}, {@code
 * sweep}, {@code search} or {@code none}) and one input from that layer, as raw bits. Exit
 * status {@link Nightly#OK}, or {@link Nightly#ERROR} for a usage error or when the unchanged
 * transcription differs from {@code StrictMath} on any input, which would make every count
 * meaningless.
 */
final class Survey {

  /** The fuzz's streams, fixed so that the inputs do not depend on {@code --threads}. */
  static final int STREAMS = 16;
  /** The pieces the suite's inputs are dealt to the threads in. */
  private static final int PIECES = 64;
  /** The layers in the order a mutant is credited to them, and the name for no layer. */
  static final String[] LAYERS = {"suite", "fuzz", "sweep", "search", "none"};

  private Survey() {}

  // ---------------------------------------------------------------------------------------
  // Recording the suite's inputs.

  /** The directory the tests' inputs are recorded in, or null: the usual case. */
  static final String RECORD = System.getProperty("vfdlibm.survey.record");

  private static final Map<String, DataOutputStream> RECORDS = new HashMap<>();
  private static final Set<Long> RECORDED = new HashSet<>();

  /**
   * Appends {@code xs} to {@code <dir>/<function>-<pid>.bin} as raw bits, unless the same
   * array was recorded for the function before. {@code name} is the name a test gives {@code
   * Oracle}: {@code VFdlibm.exp} or {@code Exp.expBlended} is exp's, and a name with no dot,
   * as {@code HarnessSelfTest}'s, is nobody's.
   */
  static synchronized void record(String name, double[] xs) {
    String id = functionOf(name);
    if (RECORD == null || id == null) {
      return;
    }
    long hash = id.hashCode();
    for (double x : xs) {
      hash = Nightly.mix(hash + Double.doubleToRawLongBits(x));
    }
    if (!RECORDED.add(hash)) {
      return;
    }
    try {
      DataOutputStream out = RECORDS.get(id);
      if (out == null) {
        Path dir = Path.of(RECORD);
        Files.createDirectories(dir);
        Path file = dir.resolve(id + "-" + ProcessHandle.current().pid() + ".bin");
        out = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(file), 1 << 16));
        RECORDS.put(id, out);
        DataOutputStream opened = out;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            opened.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }));
      }
      ByteBuffer bytes = ByteBuffer.allocate(8 * xs.length);
      for (double x : xs) {
        bytes.putLong(Double.doubleToRawLongBits(x));
      }
      out.write(bytes.array());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The function a test's name for an arm belongs to, or null. */
  static String functionOf(String name) {
    int dot = name.indexOf('.');
    if (dot < 0) {
      return null;
    }
    String method = name.substring(dot + 1);
    String best = null;
    for (Nightly.Function f : Nightly.Function.values()) {
      if (method.startsWith(f.id) && (best == null || f.id.length() > best.length())) {
        best = f.id;
      }
    }
    return best;
  }

  /** The distinct inputs recorded for {@code id} in {@code dir}, as sorted raw bits. */
  static long[] recorded(Path dir, String id) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, id + "-*.bin")) {
      stream.forEach(files::add);
    }
    long total = 0;
    for (Path file : files) {
      total += Files.size(file) / 8;
    }
    if (total == 0 || total > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException(
          total + " inputs recorded for " + id + " in " + dir + "; run the tests with"
              + " -Dvfdlibm.survey.record=" + dir + " first (dev/survey.sh does)");
    }
    long[] bits = new long[(int) total];
    int at = 0;
    for (Path file : files) {
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(
          Files.newInputStream(file), 1 << 20))) {
        for (long n = Files.size(file) / 8; n > 0; n--) {
          bits[at++] = in.readLong();
        }
      }
    }
    Arrays.parallelSort(bits, 0, at);
    int unique = 0;
    for (int i = 0; i < at; i++) {
      if (i == 0 || bits[i] != bits[i - 1]) {
        bits[unique++] = bits[i];
      }
    }
    return Arrays.copyOf(bits, unique);
  }

  // ---------------------------------------------------------------------------------------
  // Counting.

  /** One layer's counts: per mutant the differing inputs, one of them, and the first place. */
  static final class Tally {
    final long[] count;
    final long[] example;
    final long[] firstAt;
    long inputs;
    long unchangedDiffers;
    long unchangedExample;

    Tally(int mutants) {
      count = new long[mutants];
      example = new long[mutants];
      firstAt = new long[mutants];
      Arrays.fill(firstAt, Long.MAX_VALUE);
    }

    void add(Tally other) {
      inputs += other.inputs;
      if (unchangedDiffers == 0) {
        unchangedExample = other.unchangedExample;
      }
      unchangedDiffers += other.unchangedDiffers;
      for (int m = 0; m < count.length; m++) {
        count[m] += other.count[m];
        if (other.firstAt[m] < firstAt[m]) {
          firstAt[m] = other.firstAt[m];
          example[m] = other.example[m];
        }
      }
    }
  }

  /** A share of a layer's inputs, counted into the tally of the thread that takes it. */
  @FunctionalInterface
  interface Task {
    void run(Counter counter);
  }

  /** One thread's view: the function, its mutants and the thread's tally. */
  static final class Counter {
    private final Mutants.Port port;
    private final Mutants.Mutant unchanged;
    private final Mutants.Mutant[] mutants;
    final Tally tally;

    Counter(Mutants.Port port, Mutants.Mutant[] mutants) {
      this.port = port;
      this.unchanged = port.unchanged();
      this.mutants = mutants;
      this.tally = new Tally(mutants.length);
    }

    /** Counts {@code x}, the input at {@code position} of its stream. */
    void count(double x, long position) {
      long want = Double.doubleToRawLongBits(port.oracle().applyAsDouble(x));
      tally.inputs++;
      if (Double.doubleToRawLongBits(port.apply(x, unchanged)) != want) {
        if (tally.unchangedDiffers++ == 0) {
          tally.unchangedExample = Double.doubleToRawLongBits(x);
        }
      }
      for (int m = 0; m < mutants.length; m++) {
        if (Double.doubleToRawLongBits(port.apply(x, mutants[m])) != want) {
          tally.count[m]++;
          if (position < tally.firstAt[m]) {
            tally.firstAt[m] = position;
            tally.example[m] = Double.doubleToRawLongBits(x);
          }
        }
      }
    }
  }

  /** Runs the tasks on {@code threads} threads and returns the sum of their tallies. */
  static Tally run(Mutants.Port port, Mutants.Mutant[] mutants, List<Task> tasks, int threads) {
    AtomicInteger next = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Counter> counters = new ArrayList<>();
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Counter counter = new Counter(port, mutants);
      counters.add(counter);
      Thread worker = new Thread(() -> {
        try {
          for (int i; (i = next.getAndIncrement()) < tasks.size(); ) {
            tasks.get(i).run(counter);
          }
        } catch (Throwable e) {
          failure.compareAndSet(null, e);
        }
      }, "survey-" + t);
      worker.setDaemon(true);
      worker.start();
      workers.add(worker);
    }
    try {
      for (Thread worker : workers) {
        worker.join();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    if (failure.get() != null) {
      throw new IllegalStateException("a survey thread failed", failure.get());
    }
    Tally sum = new Tally(mutants.length);
    for (Counter counter : counters) {
      sum.add(counter.tally);
    }
    return sum;
  }

  /** The inputs {@code bits}, each at its index. */
  static List<Task> listTasks(long[] bits) {
    List<Task> tasks = new ArrayList<>();
    int pieces = Math.min(PIECES, bits.length);
    for (int p = 0; p < pieces; p++) {
      int from = (int) ((long) bits.length * p / pieces);
      int to = (int) ((long) bits.length * (p + 1) / pieces);
      tasks.add(counter -> {
        for (int i = from; i < to; i++) {
          counter.count(Double.longBitsToDouble(bits[i]), i);
        }
      });
    }
    return tasks;
  }

  /** {@code draws} inputs of the fuzz, dealt evenly to {@link #STREAMS} streams. */
  static List<Task> fuzzTasks(Nightly.Function function, long seed, long draws) {
    Fuzz.Edges edges = Fuzz.edges(function);
    List<Task> tasks = new ArrayList<>();
    for (int t = 0; t < STREAMS; t++) {
      long stream = Nightly.mix(seed + t);
      long n = draws / STREAMS + (t < draws % STREAMS ? 1 : 0);
      tasks.add(counter -> {
        SplittableRandom random = new SplittableRandom(stream);
        for (long i = 0; i < n; i++) {
          counter.count(Fuzz.draw(random, edges), i);
        }
      });
    }
    return tasks;
  }

  /**
   * {@code draws} inputs over the function's common path, which no layer above samples densely:
   * for exp, uniform doubles between its underflow and overflow limits; for expm1, between
   * {@code -56 ln2}, below which it is -1, and its overflow limit; for log and log10, positive
   * normal doubles of uniform bits, so every exponent as often.
   */
  static List<Task> searchTasks(String id, long seed, long draws) {
    List<Task> tasks = new ArrayList<>();
    for (int t = 0; t < STREAMS; t++) {
      long stream = Nightly.mix(seed + 0x5ea5c4L * (t + 1));
      long n = draws / STREAMS + (t < draws % STREAMS ? 1 : 0);
      tasks.add(counter -> {
        SplittableRandom random = new SplittableRandom(stream);
        for (long i = 0; i < n; i++) {
          double x = switch (id) {
            case "exp" -> random.nextDouble(Exp.U_THRESHOLD, Exp.O_THRESHOLD);
            case "expm1" -> random.nextDouble(-56.0 * StrictMath.log(2.0), Expm1.O_THRESHOLD);
            default -> Double.longBitsToDouble(
                random.nextLong(0x0010_0000_0000_0000L, 0x7ff0_0000_0000_0000L));
          };
          counter.count(x, i);
        }
      });
    }
    return tasks;
  }

  /** The inputs of the sweep's stripe, as {@link Sweep} makes them, in blocks of 2^16 words. */
  static List<Task> sweepTasks(String stripe, long seed) {
    List<Task> tasks = new ArrayList<>();
    for (long[] range : Sweep.stripe(stripe)) {
      for (long first = range[0]; first < range[1]; first += 1 << 16) {
        long from = first;
        long to = Math.min(range[1], first + (1 << 16));
        tasks.add(counter -> {
          for (long word = from; word < to; word++) {
            long hi = word << 32;
            counter.count(Double.longBitsToDouble(hi), 3 * word);
            counter.count(Double.longBitsToDouble(hi | 0xffffffffL), 3 * word + 1);
            counter.count(Double.longBitsToDouble(hi | Sweep.lowWord(seed, word)), 3 * word + 2);
          }
        });
      }
    }
    return tasks;
  }

  /** The {@code #} lines of provenance and the column names. */
  private static void header(PrintWriter w, Mutants.Port port, String note, boolean append,
      int count, long seed, String stripe, Tally[] in, long unchanged) {
    w.printf("# Survey of %s's one-step mutants (dev/survey/README.md): the inputs on"
        + " which each%n# mutant of the C's transcription differs from StrictMath.%s.%n",
        port.id(), port.id());
    if (!note.isEmpty()) {
      w.println("# " + note);
    }
    if (append) {
      w.println("# each mutant from a run of its own with these settings (--only, --append)");
    } else if (count != port.mutants().size()) {
      w.printf("# only %d of the %d mutants (--only)%n", count, port.mutants().size());
    }
    w.printf("# seed: %s%n", Nightly.seedText(seed));
    w.printf("# suite: %d distinct inputs recorded from the unit tests%n", in[0].inputs);
    w.printf("# hard: %d inputs of hard-cases/%s.txt%n", in[1].inputs, port.id());
    w.printf("# fuzz: %d draws in %d streams%n", in[2].inputs, STREAMS);
    w.printf("# sweep: stripe %s, %d inputs%n", stripe, in[3].inputs);
    w.printf("# search: %d draws in %d streams%n", in[4].inputs, STREAMS);
    w.printf("# unchanged transcription: differs on %d inputs%n", unchanged);
    w.println(
        "mutant\tkind\tsuite\thard\tfuzz\tfuzz_first\tsweep\tsearch\tcaught_by\texample");
  }

  // ---------------------------------------------------------------------------------------

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    Mutants.Port port;
    Nightly.Function function;
    Path suite;
    long draws;
    long searched;
    String stripe;
    long seed;
    int threads;
    Path out;
    String note;
    String only;
    boolean append;
    try {
      Map<String, String> options = Nightly.options(
          args, "function", "suite", "fuzz", "stripe", "search", "seed", "threads", "out",
          "note", "only", "append");
      port = Mutants.of(Nightly.require(options, "function"));
      function = Nightly.Function.of(port.id());
      suite = Path.of(Nightly.require(options, "suite"));
      draws = Nightly.parseNumber(options.getOrDefault("fuzz", "" + (1L << 24)));
      stripe = options.getOrDefault("stripe", "0/4096");
      Sweep.stripe(stripe);
      searched = Nightly.parseNumber(options.getOrDefault("search", "0"));
      seed = Nightly.parseNumber(Nightly.require(options, "seed"));
      threads = Integer.parseInt(options.getOrDefault("threads", "2"));
      if (threads < 1 || draws < 1) {
        throw new IllegalArgumentException("--threads and --fuzz take positive numbers");
      }
      out = Path.of(options.getOrDefault("out", "target/survey/" + port.id() + ".tsv"));
      note = options.getOrDefault("note", "");
      only = options.getOrDefault("only", ".*");
      java.util.regex.Pattern.compile(only);
      append = Boolean.parseBoolean(options.getOrDefault("append", "false"));
    } catch (IllegalArgumentException e) {
      System.err.println("Survey: " + e.getMessage());
      return Nightly.ERROR;
    }

    try {
      Mutants.Mutant[] mutants = port.mutants().stream()
          .filter(m -> m.name().matches(only)).toArray(Mutants.Mutant[]::new);
      if (mutants.length == 0) {
        System.err.println("Survey: --only " + only + " names no mutant of " + port.id());
        return Nightly.ERROR;
      }
      long start = System.nanoTime();
      long[] suiteBits = recorded(suite, port.id());
      Tally inSuite = run(port, mutants, listTasks(suiteBits), threads);
      long[] hardBits = Arrays.stream(Inputs.hardCases(port.id()))
          .mapToLong(Double::doubleToRawLongBits).toArray();
      Tally inHard = run(port, mutants, listTasks(hardBits), threads);
      Tally inFuzz = run(port, mutants, fuzzTasks(function, seed, draws), threads);
      Tally inSweep = run(port, mutants, sweepTasks(stripe, seed), threads);
      Tally inSearch = run(port, mutants, searchTasks(port.id(), seed, searched), threads);
      double seconds = (System.nanoTime() - start) / 1e9;

      long unchanged = inSuite.unchangedDiffers + inHard.unchangedDiffers
          + inFuzz.unchangedDiffers + inSweep.unchangedDiffers + inSearch.unchangedDiffers;
      int[] caughtBy = new int[5];
      if (out.getParent() != null) {
        Files.createDirectories(out.getParent());
      }
      boolean rowsOnly = append && Files.exists(out) && Files.size(out) > 0;
      try (PrintWriter w = new PrintWriter(rowsOnly
          ? Files.newBufferedWriter(out, StandardCharsets.US_ASCII, StandardOpenOption.APPEND)
          : Files.newBufferedWriter(out, StandardCharsets.US_ASCII))) {
        if (!rowsOnly) {
          header(w, port, note, append, mutants.length, seed, stripe,
              new Tally[] {inSuite, inHard, inFuzz, inSweep, inSearch}, unchanged);
        }
        for (int m = 0; m < mutants.length; m++) {
          int layer = inSuite.count[m] > 0 ? 0 : inFuzz.count[m] > 0 ? 1
              : inSweep.count[m] > 0 ? 2 : inSearch.count[m] > 0 ? 3 : 4;
          caughtBy[layer]++;
          long example = layer == 0 ? inSuite.example[m] : layer == 1 ? inFuzz.example[m]
              : layer == 2 ? inSweep.example[m] : inSearch.example[m];
          w.printf("%s\t%s\t%d\t%d\t%d\t%s\t%d\t%d\t%s\t%s%n",
              mutants[m].name(), mutants[m].kind(), inSuite.count[m], inHard.count[m],
              inFuzz.count[m], inFuzz.count[m] > 0 ? "" + inFuzz.firstAt[m] : "-",
              inSweep.count[m], inSearch.count[m], LAYERS[layer],
              layer == 4 ? "-" : String.format("0x%016x", example));
        }
      }
      System.out.printf("%s: %d mutants in %.0f s: %d caught by the suite, %d only by the fuzz,"
          + " %d only by the sweep, %d only by the search, %d by nothing; %s%n", port.id(),
          mutants.length, seconds, caughtBy[0], caughtBy[1], caughtBy[2], caughtBy[3],
          caughtBy[4], out);
      if (unchanged != 0) {
        System.err.printf("Survey: the unchanged transcription of %s differs from StrictMath"
            + " on %d inputs, such as 0x%016x%n", port.id(), unchanged,
            inSuite.unchangedDiffers > 0 ? inSuite.unchangedExample
                : inHard.unchangedDiffers > 0 ? inHard.unchangedExample
                : inFuzz.unchangedDiffers > 0 ? inFuzz.unchangedExample
                : inSweep.unchangedDiffers > 0 ? inSweep.unchangedExample
                : inSearch.unchangedExample);
        return Nightly.ERROR;
      }
      return Nightly.OK;
    } catch (IOException | RuntimeException e) {
      System.err.println("Survey: " + e);
      return Nightly.ERROR;
    }
  }
}
