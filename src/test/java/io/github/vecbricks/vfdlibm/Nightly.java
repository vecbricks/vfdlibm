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
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.UnaryOperator;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * What the nightly correctness runs, {@link Sweep} and {@link Fuzz}, share: the functions (of one
 * argument, or of two: {@link PairFunction}) and every arm of each, the species, the check of a
 * block of inputs against {@code StrictMath} by raw bits, and the record of mismatches. These are
 * {@code main} programs, not tests, so {@code mvn verify} does not run them; {@code NightlyTest}
 * runs each on a few inputs to keep them working, and SKILLS.md ("The nightly sweep and fuzz")
 * says how to run a slice by hand.
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

  /**
   * A function the nightly checks: what {@link Sweep} and {@link Fuzz} need of it, whether it
   * takes one argument ({@link Function}) or two ({@link PairFunction}).
   */
  interface Subject {
    String id();

    /** The number of arguments, 1 or 2. */
    int arity();

    /** The arms by name, for the log. */
    List<String> armNames();

    /** A checker of blocks for one thread at one species. */
    BlockCheck newChecker(VectorSpecies<Double> species, long maskSeed, Mismatches sink);
  }

  /**
   * Checks a block of {@code n} inputs, {@code n} a multiple of the lane count: {@code xs}, and
   * for a function of two arguments the second argument in {@code ys}, which is null for one.
   */
  interface BlockCheck {
    void check(double[] xs, double[] ys, int n, long position);
  }

  /** A vector form of a function: its name, the all-lanes form and the masked form. */
  record Arm(
      String name,
      UnaryOperator<DoubleVector> all,
      BiFunction<DoubleVector, VectorMask<Double>, DoubleVector> masked) {}

  /** A function with its oracle and every arm, {@code VFdlibm}'s first. */
  enum Function implements Subject {
    EXP("exp", StrictMath::exp, List.of(
        new Arm("VFdlibm.exp", VFdlibm::exp, VFdlibm::exp),
        new Arm("Exp.expBlended", x -> Exp.expBlended(x, null), Exp::expBlended),
        // The pair experiment's split of the finish arm (ExpBenchmark.finishPairBoxed256): the
        // core in every lane, the rare mask's lanes finished per lane here. It has no masked form.
        // It catches expRare missing a lane the core gets wrong, never expRare marking too many:
        // an extra lane is finished by StrictMath here and so matches.
        new Arm("Exp.expCore+expRare", Nightly::expCoreAndRare, null))),
    EXPM1("expm1", StrictMath::expm1, List.of(
        // VFdlibm.expm1 is Expm1.expm1Finish, so the finish arm is checked under that name.
        new Arm("VFdlibm.expm1", VFdlibm::expm1, VFdlibm::expm1),
        new Arm("Expm1.expm1Blended", x -> Expm1.expm1Blended(x, null), Expm1::expm1Blended),
        new Arm("Expm1.expm1Guarded", x -> Expm1.expm1Guarded(x, null), Expm1::expm1Guarded),
        // The core entry sinh and tanh nest, as Exp's above: the core in every lane, the rare
        // mask's lanes finished per lane here, no masked form. It catches expm1Rare missing a lane
        // the core gets wrong, never expm1Rare marking too many: an extra lane matches by
        // StrictMath.
        new Arm("Expm1.expm1Core+expm1Rare", Nightly::expm1CoreAndRare, null))),
    LOG("log", StrictMath::log, List.of(
        new Arm("VFdlibm.log", VFdlibm::log, VFdlibm::log),
        new Arm("Log.logBlended", x -> Log.logBlended(x, null), Log::logBlended))),
    LOG10("log10", StrictMath::log10, List.of(
        new Arm("VFdlibm.log10", VFdlibm::log10, VFdlibm::log10),
        new Arm("Log10.log10Blended", x -> Log10.log10Blended(x, null), Log10::log10Blended))),
    LOG1P("log1p", StrictMath::log1p, List.of(
        // VFdlibm.log1p is Log1p.log1pFinish, so the finish arm is checked under that name.
        new Arm("VFdlibm.log1p", VFdlibm::log1p, VFdlibm::log1p),
        new Arm("Log1p.log1pBlended", x -> Log1p.log1pBlended(x, null), Log1p::log1pBlended),
        new Arm("Log1p.log1pGuarded", x -> Log1p.log1pGuarded(x, null), Log1p::log1pGuarded))),
    SINH("sinh", StrictMath::sinh, List.of(
        new Arm("VFdlibm.sinh", VFdlibm::sinh, VFdlibm::sinh))),
    COSH("cosh", StrictMath::cosh, List.of(
        // The two arms by name, so that both are swept whichever one VFdlibm.cosh calls and none
        // runs twice. VFdlibm.cosh is a thin call to the default arm; CoshTest runs it at every
        // width, masked and unmasked, and the planted-fault tests and jar-check's consumer cover
        // it too.
        new Arm("Cosh.coshSlice", x -> Cosh.coshSlice(x, null), Cosh::coshSlice),
        new Arm("Cosh.coshFinish", x -> Cosh.coshFinish(x, null), Cosh::coshFinish))),
    TANH("tanh", StrictMath::tanh, List.of(
        new Arm("VFdlibm.tanh", VFdlibm::tanh, VFdlibm::tanh))),
    ASIN("asin", StrictMath::asin, List.of(
        new Arm("VFdlibm.asin", VFdlibm::asin, VFdlibm::asin))),
    ATAN("atan", StrictMath::atan, List.of(
        new Arm("VFdlibm.atan", VFdlibm::atan, VFdlibm::atan))),
    ACOS("acos", StrictMath::acos, List.of(
        new Arm("VFdlibm.acos", VFdlibm::acos, VFdlibm::acos)));

    final String id;
    final DoubleUnaryOperator oracle;
    final List<Arm> arms;

    Function(String id, DoubleUnaryOperator oracle, List<Arm> arms) {
      this.id = id;
      this.oracle = oracle;
      this.arms = arms;
    }

    /**
     * The function of one argument named {@code id}; the message of an unknown name lists the
     * functions of one argument only, and {@link Nightly#subject} lists those of two as well.
     */
    static Function of(String id) {
      for (Function f : values()) {
        if (f.id.equals(id)) {
          return f;
        }
      }
      List<String> known = new ArrayList<>();
      for (Function f : values()) {
        known.add(f.id);
      }
      throw new IllegalArgumentException(
          "unknown function '" + id + "'; known: " + String.join(", ", known));
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public int arity() {
      return 1;
    }

    @Override
    public List<String> armNames() {
      List<String> names = new ArrayList<>();
      for (Arm arm : arms) {
        names.add(arm.name() + (arm.masked() == null ? " (unmasked only)" : ""));
      }
      return names;
    }

    @Override
    public BlockCheck newChecker(
        VectorSpecies<Double> species, long maskSeed, Mismatches sink) {
      return new Checker(this, species, maskSeed, sink);
    }
  }

  /**
   * A vector form of a function of two arguments. {@code masked} is the contract of PLAN.md
   * section 5: a lane that is not live keeps the first argument's bits.
   */
  record Arm2(String name, BinaryOperator<DoubleVector> all, Oracle.MaskedBinary masked) {}

  /** The high words e_hypot.c tests (lines 63-90): hypot's edges, and HypotTest's specials. */
  static final int[] HYPOT_WORDS = {
    0x5f300000, 0x5f300001, 0x5f2fffff, 0x20b00000, 0x20afffff, 0x20b00001, 0x000fffff, 0x00100000,
    0x00000000, 0x00000001, 0x3c00000, 0x3c00001, 0x3bfffff, 0x7fd00000, 0x7fefffff, 0x7ff00000,
    0x3ff00000
  };

  /**
   * The high words e_pow.c tests of {@code x} (lines 149-220) and of {@code y} (lines 133-198,
   * 266-272), with their neighbours: pow's edges, and PowTest's specials.
   */
  static final int[] POW_WORDS = {
    // x: zero, the subnormals' top, one and its neighbours (lines 193-198), infinity
    0x00000000, 0x000fffff, 0x00100000, 0x3feffffe, 0x3fefffff, 0x3ff00000, 0x3ff00001,
    0x7fefffff, 0x7ff00000,
    // x: the interval splits of lines 218-220, at the exponent of 1 and of 2^-1000
    0x3ff3988e, 0x3ff3988f, 0x3ff3988d, 0x3ffbb67a, 0x3ffbb679, 0x3ffbb67b, 0x0173988e,
    0x0173988f, 0x017bb67a, 0x017bb679,
    // y: 0.5, 1, 2, 2^20, 2^21 (line 136), 2^31 (line 191), 2^52, 2^53 (line 133), 2^64 (192)
    0x3fe00000, 0x40000000, 0x41300000, 0x41400000, 0x41dfffff, 0x41e00000, 0x41e00001,
    0x432fffff, 0x43300000, 0x433fffff, 0x43400000, 0x43efffff, 0x43f00000, 0x43f00001,
    // z: 1024 and 1075 (lines 266, 272)
    0x40900000, 0x408fffff, 0x4090cc00, 0x4090cbff, 0x4090cc01
  };

  /** The differences of the high words, {@code ha-hb}, at which line 63 decides, and zero. */
  static final int[] HYPOT_GAPS = {
    0x3c00000, 0x3c00001, 0x3bfffff, -0x3c00000, -0x3c00001, -0x3bfffff, 0, 1, -1
  };

  /**
   * A function of two arguments: its oracle, what its result may be on a lane of two NaNs with
   * different bits ({@link PairBits}; no default), every arm and where its branches are (for the
   * {@link Fuzz}). {@link #KNOWN} lists the ported ones, and the stand-ins a test adds; a pair
   * function that is not listed in nightly.yml is never run there.
   */
  record PairFunction(
      String id, DoubleBinaryOperator oracle, PairBits.Candidates candidates, List<Arm2> arms,
      Fuzz.PairEdges edges)
      implements Subject {

    PairFunction {
      Objects.requireNonNull(candidates, id + " declares no candidates for a lane of two NaNs");
      arms = List.copyOf(arms);
      if (arms.isEmpty() || edges.specials().length == 0
          || edges.x().words().length == 0 || edges.y().words().length == 0
          || edges.x().points().length == 0 || edges.y().points().length == 0) {
        throw new IllegalArgumentException(
            id + " has no arm, no special value, no branch word or no branch point");
      }
    }

    /**
     * atan2: its two arms (the guarded arm {@code VFdlibm} calls, and the blended one), the
     * candidates of a lane of two NaNs, and where its branches are: the high words of zero, one,
     * the infinities and (for {@code y}) {@code 2^66}, and the differences of the two words
     * that {@code k = (iy-ix)>>20} compares against 60 and -60 (e_atan2.c, lines 111-113).
     */
    static final PairFunction ATAN2 = new PairFunction("atan2", StrictMath::atan2,
        PairBits.Candidates.OPERANDS,
        List.of(
            new Arm2("VFdlibm.atan2", VFdlibm::atan2, VFdlibm::atan2),
            new Arm2("Atan2.atan2Blended", (y, x) -> Atan2.atan2Blended(y, x, null),
                Atan2::atan2Blended)),
        new Fuzz.PairEdges(
            new Fuzz.Edges(new int[] {0x00000000, 0x3ff00000, 0x44100000, 0x7ff00000},
                new int[0], false, new double[] {0.0, 1.0, 0x1p66, Double.POSITIVE_INFINITY}),
            new Fuzz.Edges(new int[] {0x00000000, 0x3ff00000, 0x7ff00000},
                new int[0], false, new double[] {0.0, 1.0, Double.POSITIVE_INFINITY}),
            Fuzz.PAIR_SPECIALS,
            new int[] {61 << 20, (61 << 20) - 1, -60 << 20, (-60 << 20) - 1}));

    /**
     * hypot: its one arm (the blended arm {@code VFdlibm} calls), the candidates of a lane of two
     * NaNs (the magnitudes: e_hypot.c adds the absolute values), and where its branches are: the
     * high words e_hypot.c tests (the scalings at {@code 2^500}, {@code 2^-500} and the subnormal
     * {@code b}, the ratio word, the infinities) and the differences of the two words at which
     * line 63's {@code ha-hb > 0x3c00000} decides, of either sign, since either argument may be
     * the larger.
     */
    static final PairFunction HYPOT = hypot();

    private static PairFunction hypot() {
      Fuzz.Edges edges = new Fuzz.Edges(HYPOT_WORDS, new int[0], false, new double[] {
        0x1p500, 0x1p-500, 0x1p1022, 0x1p-1022, 0x1p60, 1.0, 0.0, Double.POSITIVE_INFINITY
      });
      return new PairFunction("hypot", StrictMath::hypot, PairBits.Candidates.MAGNITUDES,
          List.of(new Arm2("VFdlibm.hypot", VFdlibm::hypot, VFdlibm::hypot)),
          new Fuzz.PairEdges(edges, edges, Fuzz.PAIR_SPECIALS, HYPOT_GAPS));
    }

    /**
     * pow: its one arm (the finish arm {@code VFdlibm} calls), the candidates of a lane of two NaNs
     * (the operands: e_pow.c returns {@code x+y}), and where its branches are: {@link #POW_WORDS}
     * for both arguments; for {@code x} the significand splits of lines 218-220 and the points 0,
     * 1, 2, the smallest normal and the infinity; for {@code y} the special exponents, 2^31 and
     * 2^64. pow has no branch on the two words relative to each other, so it lists no gaps; the
     * result's limits, which need {@code x} and {@code y} together, are PowTest's target pairs.
     */
    static final PairFunction POW = new PairFunction("pow", StrictMath::pow,
        PairBits.Candidates.OPERANDS,
        List.of(new Arm2("VFdlibm.pow", VFdlibm::pow, VFdlibm::pow)),
        new Fuzz.PairEdges(
            new Fuzz.Edges(POW_WORDS, new int[] {0x3988e, 0xbb67a}, false,
                new double[] {0.0, 1.0, 2.0, Double.MIN_NORMAL, Double.POSITIVE_INFINITY}),
            new Fuzz.Edges(POW_WORDS, new int[0], false,
                new double[] {0.5, 1.0, 2.0, 0x1p31, 0x1p64, Double.POSITIVE_INFINITY}),
            Fuzz.PAIR_SPECIALS));

    /**
     * The functions of two arguments by name, in the order they are listed. A port registers its
     * function here, in {@code Nightly}; a test adds a stand-in and removes it again. A function
     * in this list is not in the nightly's {@code all} until nightly.yml says so.
     */
    static final List<PairFunction> KNOWN = new CopyOnWriteArrayList<>(List.of(ATAN2, HYPOT, POW));

    @Override
    public int arity() {
      return 2;
    }

    @Override
    public List<String> armNames() {
      List<String> names = new ArrayList<>();
      for (Arm2 arm : arms) {
        names.add(arm.name() + (arm.masked() == null ? " (unmasked only)" : ""));
      }
      return names;
    }

    @Override
    public BlockCheck newChecker(
        VectorSpecies<Double> species, long maskSeed, Mismatches sink) {
      return new PairChecker(this, species, maskSeed, sink);
    }
  }

  /**
   * The function named {@code id}, of one argument or of two. The message of an unknown name
   * lists the functions of one argument and then those of two, each from its registry.
   */
  static Subject subject(String id) {
    for (Function f : Function.values()) {
      if (f.id.equals(id)) {
        return f;
      }
    }
    for (PairFunction f : PairFunction.KNOWN) {
      if (f.id().equals(id)) {
        return f;
      }
    }
    List<String> known = new ArrayList<>();
    for (Function f : Function.values()) {
      known.add(f.id);
    }
    for (PairFunction f : PairFunction.KNOWN) {
      known.add(f.id());
    }
    throw new IllegalArgumentException(
        "unknown function '" + id + "'; known: " + String.join(", ", known));
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

  /** {@link Expm1#expm1Core}, with the lanes {@link Expm1#expm1Rare} sets finished per lane. */
  private static DoubleVector expm1CoreAndRare(DoubleVector x) {
    DoubleVector core = Expm1.expm1Core(x);
    long rare = Expm1.expm1Rare(x).toLong();
    for (long bits = rare; bits != 0; bits &= bits - 1) {
      int lane = Long.numberOfTrailingZeros(bits);
      core = core.withLane(lane, StrictMath.expm1(x.lane(lane)));
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
   * <p>For a function of two arguments the file is a pair hard-cases file: its first line is
   * {@code # arity: 2} and each input is two words, {@code 0x<x> 0x<y>}, on a line of its own;
   * {@code Inputs.hardCasePairs} reads it, and a reader of one word a line refuses it by that
   * header.
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
    private final int arity;
    private final AtomicLong count = new AtomicLong();
    private final Map<String, Long> perKind = new LinkedHashMap<>();
    private long printed;

    /**
     * Opens {@code file} (created with its directories) and writes a header naming the run;
     * {@code context} goes on every mismatch, e.g. {@code "function=exp species=... seed=..."}.
     */
    Mismatches(Path file, String header, String context) {
      this(file, header, context, 1);
    }

    /** As the constructor above, for a function of {@code arity} arguments, 1 or 2. */
    Mismatches(Path file, String header, String context, int arity) {
      if (arity != 1 && arity != 2) {
        throw new IllegalArgumentException("arity is 1 or 2, not " + arity);
      }
      this.arity = arity;
      try {
        if (file.getParent() != null) {
          Files.createDirectories(file.getParent());
        }
        out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.US_ASCII));
      } catch (IOException e) {
        throw new UncheckedIOException(file.toString(), e);
      }
      this.context = context;
      if (arity == 2) {
        out.println(Inputs.PAIR_HEADER);
      }
      for (String line : header.split("\n")) {
        out.println("# " + line);
      }
      out.flush();
    }

    /** Records one lane that differed. {@code mask} is the vector's mask bits, or -1 unmasked. */
    synchronized void add(String arm, long mask, int lane, long x, long want, long got) {
      requireArity(1);
      add(arm, mask, lane, x, 0L, String.format("0x%016x", want), got, false);
    }

    /** As {@link #add(String, long, int, long, long, long)}, for the pair {@code (x, y)}. */
    synchronized void add(
        String arm, long mask, int lane, long x, long y, long want, long got) {
      requireArity(2);
      add(arm, mask, lane, x, y, String.format("0x%016x", want), got, true);
    }

    /** As the pair {@code add}, with the expected result said in words (two candidates). */
    synchronized void add(
        String arm, long mask, int lane, long x, long y, String expected, long got) {
      requireArity(2);
      add(arm, mask, lane, x, y, expected, got, true);
    }

    private void requireArity(int given) {
      if (arity != given) {
        throw new IllegalStateException(
            "a mismatch of " + given + " arguments in a file of " + arity);
      }
    }

    private void add(String arm, long mask, int lane, long x, long y, String expected, long got,
        boolean pair) {
      count.incrementAndGet();
      String kind = arm + (mask < 0 ? " unmasked" : " masked");
      long seen = perKind.merge(kind, 1L, Long::sum);
      if (printed >= PRINTED && seen > KEPT_PER_KIND) {
        return;
      }
      // Formatted only for a mismatch that is printed or written.
      String inputs = pair ? String.format("x=0x%016x y=0x%016x", x, y)
          : String.format("x=0x%016x", x);
      String data = pair ? String.format("0x%016x 0x%016x", x, y) : String.format("0x%016x", x);
      String line = String.format(
          "MISMATCH %s arm=%s masked=%s%s lane=%d %s expected=%s actual=0x%016x",
          context, arm, mask >= 0, mask >= 0 ? String.format(" mask=0x%x", mask) : "", lane,
          inputs, expected, got);
      if (printed < PRINTED) {
        printed++;
        System.out.println(line);
      }
      if (seen <= KEPT_PER_KIND) {
        out.println("# " + line);
        out.println(data);
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
  static final class Checker implements BlockCheck {

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

    @Override
    public void check(double[] xs, double[] ys, int n, long position) {
      if (ys != null) {
        throw new IllegalArgumentException(function.id + " takes one argument");
      }
      check(xs, n, position);
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

  /**
   * Checks blocks of pairs for one function of two arguments at one species, as {@link Checker}
   * does: every arm unmasked and masked, a lane that is not live held to the first argument's
   * bits. One per thread.
   */
  static final class PairChecker implements BlockCheck {

    private final PairFunction function;
    private final PairBits.Candidates candidates;
    private final VectorSpecies<Double> species;
    private final long maskSeed;
    private final Mismatches mismatches;
    private final int lanes;
    private final long laneMask;
    private double[] got = new double[0];
    private long[] want = new long[0];

    PairChecker(
        PairFunction function, VectorSpecies<Double> species, long maskSeed, Mismatches sink) {
      this.function = function;
      this.candidates = function.candidates();
      this.species = species;
      this.maskSeed = maskSeed;
      this.mismatches = sink;
      this.lanes = species.length();
      this.laneMask = lanes == 64 ? -1L : (1L << lanes) - 1;
    }

    @Override
    public void check(double[] xs, double[] ys, int n, long position) {
      if (xs.length < n || ys == null || ys.length < n) {
        throw new IllegalArgumentException(function.id() + " takes two arguments of " + n);
      }
      if (n % lanes != 0) {
        throw new IllegalArgumentException(n + " inputs do not fill " + lanes + "-lane vectors");
      }
      if (got.length < n) {
        got = new double[n];
        want = new long[n];
      }
      DoubleBinaryOperator oracle = function.oracle();
      for (int i = 0; i < n; i++) {
        want[i] = Double.doubleToRawLongBits(oracle.applyAsDouble(xs[i], ys[i]));
      }
      for (Arm2 arm : function.arms()) {
        for (int i = 0; i < n; i += lanes) {
          arm.all().apply(DoubleVector.fromArray(species, xs, i),
              DoubleVector.fromArray(species, ys, i)).intoArray(got, i);
        }
        for (int i = 0; i < n; i++) {
          long bits = Double.doubleToRawLongBits(got[i]);
          long xb = Double.doubleToRawLongBits(xs[i]);
          long yb = Double.doubleToRawLongBits(ys[i]);
          if (!PairBits.matches(candidates, xb, yb, want[i], bits)) {
            mismatches.add(arm.name(), -1, i % lanes, xb, yb,
                PairBits.expected(candidates, xb, yb, want[i]), bits);
          }
        }
        if (arm.masked() == null) {
          continue;
        }
        for (int i = 0; i < n; i += lanes) {
          long live = mix(maskSeed + position + i) & laneMask;
          VectorMask<Double> mask = VectorMask.fromLong(species, live);
          arm.masked().apply(DoubleVector.fromArray(species, xs, i),
              DoubleVector.fromArray(species, ys, i), mask).intoArray(got, i);
          for (int lane = 0; lane < lanes; lane++) {
            long x = Double.doubleToRawLongBits(xs[i + lane]);
            long y = Double.doubleToRawLongBits(ys[i + lane]);
            boolean active = (live >>> lane & 1) != 0;
            long expected = active ? want[i + lane] : x;
            long bits = Double.doubleToRawLongBits(got[i + lane]);
            if (active ? !PairBits.matches(candidates, x, y, expected, bits) : bits != expected) {
              mismatches.add(arm.name(), live, lane, x, y,
                  active ? PairBits.expected(candidates, x, y, expected)
                      : String.format("0x%016x", expected),
                  bits);
            }
          }
        }
      }
    }
  }
}
