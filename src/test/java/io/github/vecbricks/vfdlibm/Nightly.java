/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.UnaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * What the nightly correctness runs, {@link Sweep} and {@link Fuzz}, share: the functions and
 * every arm of each, the species, the check of a block of inputs against {@code StrictMath} by
 * raw bits, and the record of mismatches. These are {@code main} programs, not tests, so {@code
 * mvn verify} does not run them; {@code NightlyTest} runs each on a few inputs to keep them
 * working, and SKILLS.md ("The nightly sweep and fuzz") says how to run a slice by hand.
 *
 * <p>A block is checked as follows: {@code StrictMath} once per input, then for each arm the whole
 * block unmasked, every lane against the oracle, and the whole block masked, each vector under one
 * mask drawn from the run's seed and the vector's position, active lanes against the oracle and
 * inactive lanes against the input's raw bits (PLAN.md section 5). The unit tests check all-true
 * and all-false masks. Each input meets one mask per arm and run, so it is an active or an
 * inactive lane with probability 1/2 each: a bug of a masked form that a single input shows is
 * caught with probability about 1/2 per species and night, though a bug a band of inputs shows
 * is caught all but surely. A JVM checks one function at one species: SKILLS.md's "one species
 * of a lane type per JVM" (a second species makes the shared vector templates bimorphic and
 * boxes them, which keeps the bits right but costs the time), so the workflow starts one JVM per
 * species.
 */
final class Nightly {

  /** Exit status when every lane matched. */
  static final int OK = 0;
  /** Exit status when some lane differed from {@code StrictMath}. */
  static final int MISMATCH = 1;
  /** Exit status for a usage error or a failure of the harness itself. */
  static final int ERROR = 2;

  private Nightly() {}

  /**
   * {@code Nightly species} prints the species this host runs, as {@code --species} takes them:
   * 128 bits, 256 and 512 where the preferred species is that wide, and {@code max}.
   * {@code dev/nightly.sh} starts one JVM for each. Each fixed width it leaves out gets a line on
   * standard error, such as {@code 512: not on this host (preferred species 256 bits)}, so a log
   * says why a width is missing.
   */
  public static void main(String[] args) {
    if (args.length != 1 || !args[0].equals("species")) {
      System.err.println("usage: Nightly species");
      System.exit(ERROR);
    }
    int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
    StringBuilder names = new StringBuilder("128");
    for (int bits = 256; bits <= 512; bits *= 2) {
      if (bits <= preferred) {
        names.append(' ').append(bits);
      } else {
        System.err.println(bits + ": not on this host (preferred species " + preferred + " bits)");
      }
    }
    System.out.println(names.append(" max"));
  }

  /** A vector form of a function: its name, the all-lanes form and the masked form. */
  record Arm(
      String name,
      UnaryOperator<DoubleVector> all,
      BiFunction<DoubleVector, VectorMask<Double>, DoubleVector> masked) {}

  /** A function with its oracle and every arm, {@code VFdlibm}'s first. */
  enum Function {
    EXP("exp", StrictMath::exp, List.of(
        new Arm("VFdlibm.exp", VFdlibm::exp, VFdlibm::exp),
        new Arm("Exp.expBlended", x -> Exp.expBlended(x, null), Exp::expBlended),
        // The pair experiment's split of the finish arm (ExpBenchmark.finishPairBoxed256): the
        // core in every lane, the rare mask's lanes finished per lane here. It has no masked form.
        // It catches expRare missing a lane the core gets wrong, never expRare marking too many:
        // an extra lane is finished by StrictMath here and so matches.
        new Arm("Exp.expCore+expRare", Nightly::expCoreAndRare, null))),
    LOG("log", StrictMath::log, List.of(
        new Arm("VFdlibm.log", VFdlibm::log, VFdlibm::log),
        new Arm("Log.logBlended", x -> Log.logBlended(x, null), Log::logBlended))),
    LOG1P("log1p", StrictMath::log1p, List.of(
        // VFdlibm.log1p is Log1p.log1pGuarded, so the guarded arm is checked under that name.
        new Arm("VFdlibm.log1p", VFdlibm::log1p, VFdlibm::log1p),
        new Arm("Log1p.log1pBlended", x -> Log1p.log1pBlended(x, null), Log1p::log1pBlended),
        new Arm("Log1p.log1pFinish", x -> Log1p.log1pFinish(x, null), Log1p::log1pFinish)));

    final String id;
    final DoubleUnaryOperator oracle;
    final List<Arm> arms;

    Function(String id, DoubleUnaryOperator oracle, List<Arm> arms) {
      this.id = id;
      this.oracle = oracle;
      this.arms = arms;
    }

    static Function of(String id) {
      for (Function f : values()) {
        if (f.id.equals(id)) {
          return f;
        }
      }
      throw new IllegalArgumentException("unknown function '" + id + "'; known: exp, log, log1p");
    }
  }

  /** {@link Exp#expCore}, with the lanes {@link Exp#expRare} sets finished by StrictMath. */
  private static DoubleVector expCoreAndRare(DoubleVector x) {
    DoubleVector core = Exp.expCore(x);
    long rare = Exp.expRare(x).toLong();
    for (long bits = rare; bits != 0; bits &= bits - 1) {
      int lane = Long.numberOfTrailingZeros(bits);
      core = core.withLane(lane, StrictMath.exp(x.lane(lane)));
    }
    return core;
  }

  /**
   * The species named {@code 128}, {@code 256}, {@code 512}, {@code preferred} or {@code max}. A
   * fixed width wider than the preferred species is refused, as {@link VectorWidth} skips it: the
   * Vector API would compute it in software, which says nothing about the hardware path.
   */
  static VectorSpecies<Double> species(String name) {
    return switch (name) {
      case "preferred" -> DoubleVector.SPECIES_PREFERRED;
      case "max" -> DoubleVector.SPECIES_MAX;
      case "128", "256", "512" -> {
        int bits = Integer.parseInt(name);
        int preferred = DoubleVector.SPECIES_PREFERRED.vectorBitSize();
        if (bits > preferred) {
          throw new IllegalArgumentException(
              bits + "-bit vectors are wider than this host's preferred " + preferred + " bits");
        }
        yield VectorSpecies.of(double.class, VectorShape.forBitSize(bits));
      }
      default -> throw new IllegalArgumentException(
          "unknown species '" + name + "'; known: 128, 256, 512, preferred, max");
    };
  }

  /** SplitMix64's finaliser: a stateless 64-bit mix, so any input is reproducible on its own. */
  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  /** A seed as written on the command line and in the logs: {@code 0x} and 16 hex digits. */
  static String seedText(long seed) {
    return String.format("0x%016x", seed);
  }

  /** Parses a decimal or {@code 0x} hexadecimal number, unsigned. */
  static long parseNumber(String text) {
    return text.startsWith("0x") || text.startsWith("0X")
        ? Long.parseUnsignedLong(text.substring(2), 16)
        : Long.parseUnsignedLong(text);
  }

  /**
   * Parses {@code --name value} pairs. Every option takes a value; {@code allowed} lists the
   * names, so a typo fails instead of being ignored.
   */
  static Map<String, String> options(String[] args, String... allowed) {
    Map<String, String> options = new HashMap<>();
    List<String> known = List.of(allowed);
    for (int i = 0; i < args.length; i += 2) {
      String name = args[i];
      if (!name.startsWith("--") || !known.contains(name.substring(2))) {
        throw new IllegalArgumentException("unknown option '" + name + "'; known: " + known);
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(name + " takes a value");
      }
      options.put(name.substring(2), args[i + 1]);
    }
    return options;
  }

  /** The value of a required option. */
  static String require(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  /** The species' name as the options take it: {@code max}, or its width in bits. */
  static String speciesName(VectorSpecies<Double> species) {
    return species == DoubleVector.SPECIES_MAX ? "max" : "" + species.vectorBitSize();
  }

  /** Prints the machine, the JVM and its vector settings, for the log of a nightly run. */
  static void printHost(VectorSpecies<Double> species) {
    System.out.println("cpu: " + cpuModel());
    System.out.println("cpu flags: " + cpuVectorFlags());
    System.out.println("cores: " + Runtime.getRuntime().availableProcessors());
    System.out.println("jdk: " + System.getProperty("java.vm.name") + " "
        + System.getProperty("java.runtime.version") + " (" + System.getProperty("os.arch") + ")");
    System.out.println("jvm args: " + jvmArgs());
    System.out.println("species: " + species + "; preferred: " + DoubleVector.SPECIES_PREFERRED
        + "; max: " + DoubleVector.SPECIES_MAX);
    String fault = Faults.planted();
    System.out.println("planted fault: " + (fault == null ? "none" : fault));
  }

  /** This JVM's options, which a replay needs too (a planted fault, -XX:UseAVX=2). */
  static String jvmArgs() {
    return String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
  }

  /** {@code /proc/cpuinfo}'s first model name, or its first line naming a CPU on Arm. */
  private static String cpuModel() {
    for (String line : cpuinfo()) {
      if (line.startsWith("model name") || line.startsWith("CPU part")) {
        return line.substring(line.indexOf(':') + 1).strip();
      }
    }
    return "unknown (" + System.getProperty("os.arch") + ")";
  }

  /** The vector flags of the first CPU: avx*, sve* and asimd*, as {@code bench/datapath.sh}. */
  private static String cpuVectorFlags() {
    for (String line : cpuinfo()) {
      if (line.startsWith("flags") || line.startsWith("Features")) {
        StringBuilder flags = new StringBuilder();
        for (String flag : line.substring(line.indexOf(':') + 1).strip().split("\\s+")) {
          if (flag.startsWith("avx") || flag.startsWith("sve") || flag.startsWith("asimd")) {
            flags.append(flags.isEmpty() ? "" : " ").append(flag);
          }
        }
        return flags.toString();
      }
    }
    return "unknown";
  }

  private static List<String> cpuinfo() {
    try {
      return Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.US_ASCII);
    } catch (IOException | UncheckedIOException e) {
      return List.of();
    }
  }

  /**
   * The mismatches of a run: printed to standard output and written to a file of
   * {@code Inputs.hardCases} form, each input as {@code 0x} and 16 hex digits on its own line
   * after {@code #} lines saying what differed, so the inputs can be appended as they are to a
   * {@code hard-cases/<function>.txt} resource. Every mismatch is counted; the first
   * {@link #KEPT_PER_KIND} of each arm and mask form are written, so one broken branch cannot
   * fill the disk. Each written mismatch is flushed at once, so a JVM killed by a timeout or a
   * cancel leaves every mismatch it wrote, though no {@code # total mismatches} line.
   *
   * <p>A replay finds the same mismatches but need not write the same file: the threads reach
   * them in no fixed order, so which ones are printed and which pass {@link #KEPT_PER_KIND} vary
   * from run to run, and so does the total of a run stopped at {@link #ENOUGH}. A run that is
   * not stopped early counts the same total; the inputs it writes are a subset of the same set.
   */
  static final class Mismatches implements AutoCloseable {

    /** How many mismatches of one arm and mask form are written out; all are counted. */
    static final int KEPT_PER_KIND = 200;
    /** How many are printed to standard output in all. */
    static final int PRINTED = 20;
    /**
     * After this many a run stops early: a broken branch is found by then, and formatting
     * millions of lanes would only slow the report down.
     */
    static final long ENOUGH = 100_000;

    private final PrintWriter out;
    private final String context;
    private final AtomicLong count = new AtomicLong();
    private final Map<String, Long> perKind = new LinkedHashMap<>();
    private long printed;

    /**
     * Opens {@code file} (created with its directories) and writes a header naming the run;
     * {@code context} goes on every mismatch, e.g. {@code "function=exp species=... seed=..."}.
     */
    Mismatches(Path file, String header, String context) {
      try {
        if (file.getParent() != null) {
          Files.createDirectories(file.getParent());
        }
        out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.US_ASCII));
      } catch (IOException e) {
        throw new UncheckedIOException(file.toString(), e);
      }
      this.context = context;
      for (String line : header.split("\n")) {
        out.println("# " + line);
      }
      out.flush();
    }

    /** Records one lane that differed. {@code mask} is the vector's mask bits, or -1 unmasked. */
    synchronized void add(String arm, long mask, int lane, long x, long want, long got) {
      count.incrementAndGet();
      String kind = arm + (mask < 0 ? " unmasked" : " masked");
      long seen = perKind.merge(kind, 1L, Long::sum);
      if (printed >= PRINTED && seen > KEPT_PER_KIND) {
        return;
      }
      String line = String.format(
          "MISMATCH %s arm=%s masked=%s%s lane=%d x=0x%016x expected=0x%016x actual=0x%016x",
          context, arm, mask >= 0, mask >= 0 ? String.format(" mask=0x%x", mask) : "", lane, x,
          want, got);
      if (printed < PRINTED) {
        printed++;
        System.out.println(line);
      }
      if (seen <= KEPT_PER_KIND) {
        out.println("# " + line);
        out.println(String.format("0x%016x", x));
        if (seen == KEPT_PER_KIND) {
          out.println("# " + kind + ": later mismatches are counted, not written");
        }
        out.flush();
      }
    }

    long count() {
      return count.get();
    }

    /** Whether the run has found {@link #ENOUGH} mismatches and should stop. */
    boolean enough() {
      return count.get() >= ENOUGH;
    }

    /** The count per arm and mask form, for the summary. */
    synchronized Map<String, Long> perKind() {
      return new LinkedHashMap<>(perKind);
    }

    @Override
    public synchronized void close() {
      out.println("# total mismatches: " + count.get() + (enough() ? " (stopped early)" : "") + " "
          + perKind);
      out.close();
      if (out.checkError()) {
        throw new UncheckedIOException(new IOException("writing the mismatch file failed"));
      }
    }
  }

  /**
   * Checks blocks of inputs for one function at one species. One per thread: it owns its
   * buffers, and only {@link Mismatches#add} is shared.
   */
  static final class Checker {

    private final Function function;
    private final VectorSpecies<Double> species;
    private final long maskSeed;
    private final Mismatches mismatches;
    private final int lanes;
    private final long laneMask;
    private double[] got = new double[0];
    private long[] want = new long[0];

    Checker(Function function, VectorSpecies<Double> species, long maskSeed, Mismatches sink) {
      this.function = function;
      this.species = species;
      this.maskSeed = maskSeed;
      this.mismatches = sink;
      this.lanes = species.length();
      this.laneMask = lanes == 64 ? -1L : (1L << lanes) - 1;
    }

    /**
     * Checks {@code xs[0..n)}, {@code n} a multiple of the lane count, with every arm unmasked
     * and masked. {@code position} is where the block starts in the run, so each vector's mask
     * is a function of the seed and the vector's position alone.
     */
    void check(double[] xs, int n, long position) {
      if (n % lanes != 0) {
        throw new IllegalArgumentException(n + " inputs do not fill " + lanes + "-lane vectors");
      }
      if (got.length < n) {
        got = new double[n];
        want = new long[n];
      }
      DoubleUnaryOperator oracle = function.oracle;
      for (int i = 0; i < n; i++) {
        want[i] = Double.doubleToRawLongBits(oracle.applyAsDouble(xs[i]));
      }
      for (Arm arm : function.arms) {
        UnaryOperator<DoubleVector> all = arm.all();
        for (int i = 0; i < n; i += lanes) {
          all.apply(DoubleVector.fromArray(species, xs, i)).intoArray(got, i);
        }
        for (int i = 0; i < n; i++) {
          long bits = Double.doubleToRawLongBits(got[i]);
          if (bits != want[i]) {
            mismatches.add(arm.name(), -1, i % lanes, Double.doubleToRawLongBits(xs[i]),
                want[i], bits);
          }
        }
        if (arm.masked() == null) {
          continue;
        }
        BiFunction<DoubleVector, VectorMask<Double>, DoubleVector> masked = arm.masked();
        for (int i = 0; i < n; i += lanes) {
          long live = mix(maskSeed + position + i) & laneMask;
          VectorMask<Double> mask = VectorMask.fromLong(species, live);
          masked.apply(DoubleVector.fromArray(species, xs, i), mask).intoArray(got, i);
          for (int lane = 0; lane < lanes; lane++) {
            long x = Double.doubleToRawLongBits(xs[i + lane]);
            long expected = (live >>> lane & 1) != 0 ? want[i + lane] : x;
            long bits = Double.doubleToRawLongBits(got[i + lane]);
            if (bits != expected) {
              mismatches.add(arm.name(), live, lane, x, expected, bits);
            }
          }
        }
      }
    }
  }

  /** The arms of {@code function}, by name, for the log. */
  static List<String> armNames(Function function) {
    List<String> names = new ArrayList<>();
    for (Arm arm : function.arms) {
      names.add(arm.name() + (arm.masked() == null ? " (unmasked only)" : ""));
    }
    return names;
  }
}
