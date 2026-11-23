/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assembly check's machinery: finds hsdis, runs a probe class in a child JVM that prints its
 * methods' C2 code, parses that output and holds the rules a port's arm must meet (SKILLS.md, "The
 * assembly check"). x86 only: the rules name x86 mnemonics and registers.
 *
 * <p>What is read: the last standard (not on-stack-replacement) {@code C2-compiled nmethod} of a
 * method, from its entry point to its stub code, in Intel syntax (pinned by {@code
 * PrintAssemblyOptions=intel}). The constant pool, the C1 nmethod and the stubs are not read.
 * Nothing is read unless HotSpot printed a {@code [Disassembly]} section: without a working
 * hsdis it prints the code as hex words under {@code [MachCode]}, whose lines have the same
 * {@code 0x<address>:} shape, and a rule that found no instruction would pass vacuously.
 *
 * <p>The rules, each a list of violations (empty is a pass):
 *
 * <ul>
 *   <li>{@link #undecodedViolations}: every byte decoded, or the other rules read garbage (hsdis
 *       needs Capstone 5 or later for AVX-512; {@code dev/hsdis-build.sh}).
 *   <li>{@link #widthViolations}: at least one packed-double arithmetic instruction ({@code
 *       v(add|sub|mul|div|sqrt)pd}), every one of them on registers of the width's class ({@code
 *       xmm} at 128 bits, {@code ymm} at 256, {@code zmm} at 512), and no scalar double
 *       arithmetic ({@code (v)(add|sub|mul|div|sqrt)sd}). Narrower registers elsewhere are
 *       allowed: the int lanes of a 512-bit body are a {@code ymm}, and zeroing idioms name {@code
 *       xmm}.
 *   <li>{@link #fmaViolations}: no fused multiply-add of any form ({@code vfmadd*}, {@code
 *       vfmsub*}, {@code vfnmadd*}, {@code vfnmsub*}, {@code vfmaddsub*}, {@code vfmsubadd*}).
 *   <li>{@link #callViolations}: every {@code call} goes to an uncommon trap ({@code {runtime_call
 *       UncommonTrapBlob}}) or the method entry barrier ({@code {runtime_call
 *       Stub::method_entry_barrier}}), and no other instruction carries a call's relocation but
 *       the safepoint poll stub's {@code jmp} ({@code {runtime_call SafepointBlob}}). On data
 *       where no lane is rare C2 compiles each rare branch as an uncommon trap, a deoptimizing
 *       exit that is never the common path; so any other call, anywhere in the method, fails,
 *       which is stricter than the common path alone. Prefixes such as {@code notrack} are read
 *       off before the mnemonic, so {@code notrack call} is a call.
 *   <li>{@link #licenceViolations}: no inlined frame of {@code java.lang.StrictMath} or {@code
 *       FdLibm}, whose compiled code this project does not read (AGENTS.md, licence). HotSpot
 *       names frames only at calls, traps and safepoints, so this is a backstop; what keeps that
 *       code out is the probe's data, on which no lane reaches the per-lane finish.
 * </ul>
 */
final class AsmCheck {

  private AsmCheck() {}

  /** The environment variable that names a directory holding {@code hsdis-<arch>.so}. */
  static final String HSDIS_DIR_ENV = "VFDLIBM_HSDIS_DIR";
  /** Set to {@code true}, a skip for want of hsdis fails instead (CI's x86 rows set it). */
  static final String REQUIRED_ENV = "VFDLIBM_ASM_REQUIRED";
  /** How long a probe JVM may take. */
  static final int CHILD_SECONDS = 180;

  // ---------------------------------------------------------------------------------------------
  // Finding hsdis

  /** Where hsdis was looked for, and what was found. */
  record Hsdis(Path dir, String why) {
    boolean found() {
      return dir != null;
    }
  }

  /** The library's file name on this architecture, or null where the check does not run. */
  static String libraryName() {
    if (!System.getProperty("os.name").equals("Linux")) {
      return null;
    }
    String arch = System.getProperty("os.arch");
    return switch (arch) {
      case "amd64", "x86_64" -> "hsdis-amd64.so";
      default -> null;
    };
  }

  /**
   * The directory holding hsdis: {@code $VFDLIBM_HSDIS_DIR} if set, else {@code target/hsdis}
   * (where {@code dev/hsdis-build.sh} builds it, on Capstone, the output the rules and the
   * self-test read; a binutils hsdis prints another one). Found means the file
   * exists; whether HotSpot loads it is known only from a probe's output ({@link
   * #disassembled}).
   */
  static Hsdis findHsdis() {
    return findHsdis(System.getenv(HSDIS_DIR_ENV), libraryName(),
        List.of(Path.of("target", "hsdis").toAbsolutePath()));
  }

  /** {@link #findHsdis()} with the environment's value and the default places given. */
  static Hsdis findHsdis(String env, String name, List<Path> defaults) {
    List<Path> candidates = env != null && !env.isEmpty() ? List.of(Path.of(env)) : defaults;
    for (Path dir : candidates) {
      if (Files.isRegularFile(dir.resolve(name))) {
        return new Hsdis(dir, null);
      }
    }
    return new Hsdis(null, "hsdis not found: no " + name + " in " + candidates
        + " (build it with dev/hsdis-build.sh, or set " + HSDIS_DIR_ENV + ")");
  }

  /**
   * An hsdis in the running JDK's own library directories, or null: HotSpot loads one there before
   * it looks on {@code LD_LIBRARY_PATH}, so the probe would read that one, not the one found.
   */
  static String hsdisInJdk() {
    Path lib = Path.of(System.getProperty("java.home"), "lib");
    for (Path dir : List.of(lib, lib.resolve("server"))) {
      for (String name : List.of(libraryName(), "lib" + libraryName())) {
        Path file = dir.resolve(name);
        if (Files.exists(file)) {
          return file.toString();
        }
      }
    }
    return null;
  }

  /** Whether {@link #REQUIRED_ENV} turns a skip into a failure. */
  static boolean required() {
    return "true".equals(System.getenv(REQUIRED_ENV));
  }

  // ---------------------------------------------------------------------------------------------
  // Running a probe

  /**
   * The output of {@code probe}'s {@code main} in a JVM that prints the C2 code of every method
   * of {@code probe}, with vfdlibm's methods forced inline as the benchmarks run them, compiled on
   * the calling thread ({@code -Xbatch}: a warm-up count alone only queues a compile), with hsdis
   * from {@code hsdisDir} and this JVM's {@code -XX} options (a CI row's {@code UseAVX} and
   * {@code MaxVectorSize}). The probe's own methods are kept from inlining ({@code dontinline},
   * given after the forced inline, which it overrides for them): otherwise an on-stack-replacement
   * compile of {@code main} could take in an arm's loop, and the arm's own compile, the one read,
   * might never come. Fails on a non-zero exit, a timeout, or output without the probe's done
   * line.
   */
  static String runProbe(Class<?> probe, Path hsdisDir, List<String> properties)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of("-Xbatch", "-Xmx512m", "--add-modules", "jdk.incubator.vector",
        "-XX:+UnlockDiagnosticVMOptions"));
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (arg.startsWith("-XX:")) {
        command.add(arg);
      }
    }
    command.addAll(properties);
    command.addAll(List.of("-XX:PrintAssemblyOptions=intel", "-XX:CompileCommand=quiet",
        "-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*",
        "-XX:CompileCommand=dontinline," + probe.getName() + "::*",
        "-XX:CompileCommand=print," + probe.getName() + "::*",
        "-cp", System.getProperty("java.class.path"), probe.getName()));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().keySet().removeAll(
        List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
    String path = builder.environment().get("LD_LIBRARY_PATH");
    builder.environment().put("LD_LIBRARY_PATH",
        hsdisDir + (path == null || path.isEmpty() ? "" : ":" + path));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Process process = builder.start();
    try {
      CompletableFuture<Void> drained = CompletableFuture.runAsync(() -> {
        try (InputStream in = process.getInputStream()) {
          in.transferTo(bytes);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      if (!process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        throw new AssertionError("the probe JVM did not finish in " + CHILD_SECONDS + " s: "
            + command + "\n" + tail(bytes.toString(StandardCharsets.UTF_8), 60));
      }
      drained.join();
    } finally {
      process.destroyForcibly();
    }
    String text = bytes.toString(StandardCharsets.UTF_8);
    if (process.exitValue() != 0 || !text.contains("asm-probe done")) {
      throw new AssertionError("the probe JVM failed (exit " + process.exitValue() + "): "
          + command + "\n" + tail(text, 60));
    }
    return text;
  }

  /** The last {@code lines} lines of {@code text}. */
  static String tail(String text, int lines) {
    List<String> all = text.lines().toList();
    return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
  }

  // ---------------------------------------------------------------------------------------------
  // Parsing

  /**
   * One instruction: its address, its prefixes ({@code notrack}, {@code lock}, ...; empty for
   * none), its mnemonic, operands and the comments HotSpot put after it.
   */
  record Instruction(long address, String prefixes, String mnemonic, String operands,
      String notes) {
    @Override
    public String toString() {
      return String.format(Locale.ROOT, "0x%x: %s%s %s", address,
          prefixes.isEmpty() ? "" : prefixes + " ", mnemonic, operands);
    }
  }

  /**
   * The prefixes a disassembler may print before a mnemonic as words of their own ({@code notrack
   * call r10}), which {@link #lastC2} moves out of the mnemonic so that every rule reads the
   * instruction itself.
   */
  private static final Pattern PREFIX = Pattern.compile(
      "lock|rep|repe|repz|repne|repnz|notrack|bnd|xacquire|xrelease|data16|data32|addr16|addr32"
          + "|rex64|rex(\\.[wrxb]+)?|cs|ds|es|fs|gs|ss");

  /**
   * A method's C2 code from its entry point to its stubs, and the lines in that range that were
   * neither an instruction, a comment HotSpot attaches to one, a {@code #} header line nor blank.
   */
  record Body(String method, List<Instruction> code, List<String> unread) {}

  // A mnemonic may be a directive (".byte") or "(bad)": what a disassembler prints for bytes it
  // does not decode, which undecodedViolations must see rather than the parser drop.
  private static final Pattern INSTRUCTION = Pattern.compile(
      "^\\s*0x([0-9a-f]+):\\s+([a-z.(][a-z0-9_.()]*)(?:\\s+([^;]*?))?\\s*(;.*)?$");
  private static final Pattern NOTE = Pattern.compile("^\\s+;(.*)$");

  /**
   * Whether HotSpot disassembled: a {@code [Disassembly]} section holding an instruction line
   * after an entry point. Without hsdis loaded neither appears.
   */
  static boolean disassembled(String output) {
    boolean inside = false;
    boolean code = false;
    for (String line : output.split("\n", -1)) {
      if (line.startsWith("[Disassembly]")) {
        inside = true;
      } else if (line.startsWith("[/Disassembly]")) {
        inside = false;
        code = false;
      } else if (inside && isEntry(line)) {
        code = true;
      } else if (inside && code && INSTRUCTION.matcher(line).matches()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isEntry(String line) {
    return line.startsWith("[Entry Point]") || line.startsWith("[Verified Entry Point]");
  }

  private static boolean isEndOfCode(String line) {
    return line.startsWith("[Stub Code]") || line.startsWith("[Exception Handler]")
        || line.startsWith("[Deopt Handler Code]") || line.startsWith("[/Disassembly]");
  }

  /**
   * The last standard C2 compile of {@code method} ({@code package.Class::name}), or empty when
   * there is none: no C2 compile, only an on-stack-replacement one, or no disassembly.
   */
  static Optional<Body> lastC2(String output, String method) {
    String[] lines = output.split("\n", -1);
    int start = -1;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("Compiled method (c2)") && line.contains(" " + method + " (")
          && !line.contains(" % ")) {
        start = i;
      }
    }
    if (start < 0) {
      return Optional.empty();
    }
    List<Instruction> code = new ArrayList<>();
    List<String> unread = new ArrayList<>();
    boolean disassembly = false;
    boolean inCode = false;
    for (int i = start + 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("Compiled method (") || line.startsWith("[/Disassembly]")) {
        break;
      }
      if (line.startsWith("[Disassembly]")) {
        disassembly = true;
      } else if (disassembly && isEntry(line)) {
        inCode = true;
      } else if (inCode && isEndOfCode(line)) {
        inCode = false;
      } else if (inCode) {
        Matcher m = INSTRUCTION.matcher(line);
        if (m.matches()) {
          String mnemonic = m.group(2);
          String operands = m.group(3) == null ? "" : m.group(3).trim();
          String note = m.group(4) == null ? "" : m.group(4);
          StringBuilder prefixes = new StringBuilder();
          while (PREFIX.matcher(mnemonic).matches() && !operands.isEmpty()) {
            prefixes.append(prefixes.isEmpty() ? "" : " ").append(mnemonic);
            String[] split = operands.split("\\s+", 2);
            mnemonic = split[0];
            operands = split.length > 1 ? split[1] : "";
          }
          code.add(new Instruction(Long.parseUnsignedLong(m.group(1), 16), prefixes.toString(),
              mnemonic, operands, note));
        } else {
          Matcher n = NOTE.matcher(line);
          if (n.matches() && !code.isEmpty()) {
            Instruction last = code.remove(code.size() - 1);
            code.add(new Instruction(last.address(), last.prefixes(), last.mnemonic(),
                last.operands(), last.notes() + " ;" + n.group(1).trim()));
          } else if (!n.matches() && !line.isBlank() && !line.trim().startsWith("#")
              && !isEntry(line)) {
            unread.add(line);
          }
        }
      }
    }
    return disassembly && !code.isEmpty()
        ? Optional.of(new Body(method, code, unread))
        : Optional.empty();
  }

  // ---------------------------------------------------------------------------------------------
  // The rules

  private static final Pattern PACKED_DOUBLE = Pattern.compile("v(add|sub|mul|div|sqrt)pd");
  private static final Pattern SCALAR_DOUBLE = Pattern.compile("v?(add|sub|mul|div|sqrt)sd");
  private static final Pattern FMA = Pattern.compile("vfn?m(add|sub).*");
  private static final Pattern VECTOR_REGISTER = Pattern.compile("\\b([xyz]mm)\\d+\\b");
  /** A relocation that marks a call or a jump out of the method: HotSpot's comment for it. */
  private static final Pattern CALL_RELOCATION = Pattern.compile(
      "\\{(?:runtime_call|static_call|opt_virtual_call|optimized virtual_call|virtual_call)"
          + "\\b[^}]*\\}");
  /** The relocations a {@code call} may carry: an uncommon trap and the method entry barrier. */
  private static final List<String> ALLOWED_CALLS =
      List.of("{runtime_call UncommonTrapBlob}", "{runtime_call Stub::method_entry_barrier}");
  /**
   * The relocation a {@code jmp} may carry: the safepoint poll stub C2 places after the body,
   * which the return's poll enters only when a safepoint is pending.
   */
  private static final List<String> ALLOWED_JUMPS = List.of("{runtime_call SafepointBlob}");

  /** The register class of a vector of {@code bits}. */
  static String registerClass(int bits) {
    return switch (bits) {
      case 128 -> "xmm";
      case 256 -> "ymm";
      case 512 -> "zmm";
      default -> throw new IllegalArgumentException(bits + " bits");
    };
  }

  /** Packed-double arithmetic on {@code bits}' registers only, some of it, and no scalar. */
  static List<String> widthViolations(Body body, int bits) {
    String expected = registerClass(bits);
    List<String> violations = new ArrayList<>();
    int packed = 0;
    for (Instruction insn : body.code()) {
      if (PACKED_DOUBLE.matcher(insn.mnemonic()).matches()) {
        packed++;
        Matcher m = VECTOR_REGISTER.matcher(insn.operands());
        while (m.find()) {
          if (!m.group(1).equals(expected)) {
            violations.add(body.method() + ": " + insn + " is not on " + expected
                + " registers alone");
            break;
          }
        }
      } else if (SCALAR_DOUBLE.matcher(insn.mnemonic()).matches()) {
        violations.add(body.method() + ": " + insn + " is scalar double arithmetic");
      }
    }
    if (packed == 0) {
      violations.add(body.method() + ": no packed-double arithmetic (v(add|sub|mul|div|sqrt)pd)");
    }
    return violations;
  }

  /** Every fused multiply-add instruction. */
  static List<String> fmaViolations(Body body) {
    return matching(body, insn -> FMA.matcher(insn.mnemonic()).matches(),
        "is a fused multiply-add");
  }

  /**
   * Every call that is not an uncommon trap or the entry barrier, and every other instruction
   * that carries a call's relocation ({@code {runtime_call ...}}, {@code {static_call}}, {@code
   * {optimized virtual_call}}, {@code {virtual_call}}) but the poll stub's {@code jmp} to the
   * safepoint blob: a tail jump that HotSpot marks as a call is a call too. Each relocation is
   * matched whole, so an allowed one beside another does not pass, and a {@code call} with none
   * fails. A {@code jmp} with no call relocation (one to a label, an indirect one, one marked
   * only {@code {external_word}}) is not read as leaving the method.
   */
  static List<String> callViolations(Body body) {
    return matching(body, insn -> {
      List<String> relocations = CALL_RELOCATION.matcher(insn.notes()).results()
          .map(java.util.regex.MatchResult::group).toList();
      if (insn.mnemonic().startsWith("call")) {
        return relocations.isEmpty() || !ALLOWED_CALLS.containsAll(relocations);
      }
      if (insn.mnemonic().equals("jmp")) {
        return !ALLOWED_JUMPS.containsAll(relocations);
      }
      return !relocations.isEmpty();
    }, "is a call");
  }

  /**
   * Every run of bytes the disassembler did not decode ({@code .byte}, {@code (bad)}). After one,
   * the instructions that follow are read out of step, so no other rule can be trusted on the
   * body: Capstone 4.0.2 printed {@code .byte 0x62} for an EVEX prefix and a {@code call} among
   * the garbage after it.
   */
  static List<String> undecodedViolations(Body body) {
    List<String> violations = matching(body, insn -> insn.mnemonic().startsWith(".")
        || insn.mnemonic().equals("(bad)"), "was not decoded (hsdis's Capstone is older than 5?)");
    // A line in the code that the parser could not read could hide a call or an FMA.
    for (String line : body.unread()) {
      violations.add(body.method() + ": a line in its code was not read: " + line.trim());
    }
    return violations;
  }

  /** Every instruction whose inlined frames name StrictMath or FdLibm. */
  static List<String> licenceViolations(Body body) {
    return matching(body, insn -> insn.notes().contains("java.lang.StrictMath::")
        || insn.notes().contains("FdLibm"), "is StrictMath's code, which is not read here");
  }

  private static List<String> matching(Body body, Predicate<Instruction> bad, String what) {
    List<String> violations = new ArrayList<>();
    for (Instruction insn : body.code()) {
      if (bad.test(insn)) {
        violations.add(body.method() + ": " + insn + " " + what
            + (insn.notes().isEmpty() ? "" : " (" + insn.notes().trim() + ")"));
      }
    }
    return violations;
  }

  // ---------------------------------------------------------------------------------------------
  // The census

  /** The census's columns, in order, each a family of mnemonics or operands. */
  static final Map<String, Predicate<Instruction>> CENSUS = censusColumns();

  private static Map<String, Predicate<Instruction>> censusColumns() {
    Map<String, Predicate<Instruction>> c = new LinkedHashMap<>();
    c.put("insns", insn -> true);
    c.put("pd arith", insn -> PACKED_DOUBLE.matcher(insn.mnemonic()).matches());
    c.put("vdivpd", insn -> insn.mnemonic().equals("vdivpd"));
    c.put("vsqrtpd", insn -> insn.mnemonic().equals("vsqrtpd"));
    c.put("blends", insn -> insn.mnemonic().contains("blend"));
    c.put("broadcasts", insn -> insn.mnemonic().contains("broadcast"));
    c.put("compares", insn -> insn.mnemonic().startsWith("vpcmp")
        || insn.mnemonic().startsWith("vcmp"));
    c.put("k ops", insn -> insn.mnemonic().startsWith("k"));
    c.put("converts", insn -> insn.mnemonic().startsWith("vcvt")
        || insn.mnemonic().matches("vpmov(s|us)?(q|d|w)(d|w|b)|vpmov[sz]x.*"));
    c.put("stack", insn -> insn.operands().contains("[rsp"));
    c.put("calls", insn -> insn.mnemonic().startsWith("call"));
    c.put("fma", insn -> FMA.matcher(insn.mnemonic()).matches());
    return c;
  }

  /** The census's counts for {@code body}, in {@link #CENSUS}'s order. */
  static List<Integer> census(Body body) {
    List<Integer> counts = new ArrayList<>();
    for (Predicate<Instruction> column : CENSUS.values()) {
      counts.add((int) body.code().stream().filter(column).count());
    }
    return counts;
  }
}
