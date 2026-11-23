/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The assembly check of every port's vector arms (SKILLS.md, "The assembly check"): for each
 * function and each {@link VectorWidth} the host has, {@link AsmProbe} runs in a JVM of its own
 * and prints the C2 code of a loop over each of the function's arms with the arm inlined, and each
 * arm's code must meet {@link AsmCheck}'s rules: every byte decoded, packed-double arithmetic on
 * registers of the width, no fused multiply-add, no call but uncommon traps and the entry barrier,
 * and no StrictMath code.
 *
 * <p>The arms are {@link #ARMS}: each port's benchmark arms, unmasked and masked, and the cores
 * its benchmark calls. The ports' own methods are not printed alone: without the forced inline a
 * port's shared body compiles as a method of its own whose {@code arm} is no constant, taking and
 * returning its vectors as heap objects, which is not code any caller runs inline (SKILLS.md).
 *
 * <p>At 128 bits on x86 the call rule is not asserted: C2 lowers no mask of two 64-bit lanes
 * there (SKILLS.md, "At two 64-bit lanes x86 C2 lowers no mask"), so every arm calls into the
 * Vector API's Java fallback. Its other rules are asserted. The widths are the fixed ones the
 * host has; the preferred species is one of them ({@link #functionsAndWidths}).
 *
 * <p>Skipped, with the reason, on a host that is not Linux on x86-64, when no hsdis is found
 * ({@link AsmCheck#findHsdis}), when the JDK has one of its own that HotSpot would load first, and
 * when one is found but HotSpot printed no disassembly with it; with {@code
 * VFDLIBM_ASM_REQUIRED=true}, as CI's x86 rows set, the last three fail instead. A width that
 * {@code VFDLIBM_ASM_WIDTHS} leaves out is skipped with the CI row that checks it; {@link
 * #ciRowsCoverEveryWidth} fails if ci.yml's rows leave a width out.
 */
class AsmTest {

  private static final String PROBE = AsmProbe.class.getName();

  /**
   * Each function's arms, as the probe names them, in any order; the probe must hold these and no
   * others.
   */
  static final Map<String, List<String>> ARMS = arms();

  private static Map<String, List<String>> arms() {
    Map<String, List<String>> arms = new LinkedHashMap<>();
    arms.put("exp", List.of("blended", "blendedMasked", "core", "finish", "finishMasked"));
    arms.put("expm1", List.of("blended", "blendedMasked", "core", "finish", "finishMasked",
        "guarded", "guardedMasked"));
    arms.put("log", List.of("blended", "blendedMasked", "finish", "finishMasked"));
    arms.put("log10", List.of("blended", "blendedMasked", "finish", "finishMasked"));
    arms.put("log1p", List.of("blended", "blendedMasked", "finish", "finishMasked", "guarded",
        "guardedMasked"));
    arms.put("sinh", List.of("finish", "finishMasked"));
    arms.put("cosh", List.of("finish", "finishMasked", "slice", "sliceMasked"));
    arms.put("asin", List.of("finish", "finishMasked"));
    arms.put("atan", List.of("finish", "finishMasked"));
    arms.put("acos", List.of("finish", "finishMasked"));
    arms.put("atan2", List.of("blended", "blendedMasked", "guarded", "guardedMasked"));
    arms.put("hypot", List.of("finish", "finishMasked"));
    return arms;
  }

  /**
   * The probe's arms are this test's: none missing, and none that no function here lists; and
   * every public function of {@link VFdlibm} is here, so a new port fails until it is checked.
   */
  @Test
  void theProbeHoldsEveryListedArm() {
    assertEquals(java.util.Arrays.stream(VFdlibm.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
        .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()),
        ARMS.keySet(), "VFdlibm's functions");
    List<String> listed = new ArrayList<>();
    for (Map.Entry<String, List<String>> function : ARMS.entrySet()) {
      List<String> methods = AsmProbe.arms(function.getKey()).stream()
          .map(java.lang.reflect.Method::getName).toList();
      assertEquals(function.getValue().stream().sorted().toList(), methods.stream()
          .map(m -> AsmProbe.armName(function.getKey(), m)).sorted().toList(),
          function.getKey());
      listed.addAll(methods);
    }
    assertEquals(AsmProbe.allArms().stream().map(java.lang.reflect.Method::getName).sorted()
        .toList(), listed.stream().sorted().toList(), "the probe's arms");
  }

  /** ci.yml's build workflow, read from the project's directory, where surefire runs. */
  static final java.nio.file.Path CI = java.nio.file.Path.of(".github", "workflows", "ci.yml");

  /**
   * The assembly check's rows of ci.yml's build matrix ({@code asm: true}), each name with the
   * widths its {@code asm-widths} names. Read line by line: an entry starts at {@code - name:}
   * and its keys follow, one per line, as ci.yml writes them.
   */
  static Map<String, List<Integer>> ciAsmRows(List<String> lines) {
    Map<String, List<Integer>> rows = new LinkedHashMap<>();
    String name = null;
    boolean asm = false;
    String widths = null;
    for (String line : lines) {
      String t = line.trim();
      if (t.startsWith("- name:") || t.equals("steps:")) {
        if (name != null && asm) {
          rows.put(name, widths == null ? List.of() : AsmCheck.widths(widths));
        }
        name = t.startsWith("- name:") ? unquote(t.substring("- name:".length())) : null;
        asm = false;
        widths = null;
        if (t.equals("steps:")) {
          break;
        }
      } else if (name != null && t.equals("asm: true")) {
        asm = true;
      } else if (name != null && t.startsWith("asm-widths:")) {
        widths = unquote(t.substring("asm-widths:".length()));
      }
    }
    if (name != null && asm) {
      rows.put(name, widths == null ? List.of() : AsmCheck.widths(widths));
    }
    return rows;
  }

  private static String unquote(String value) {
    String v = value.trim();
    return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
        ? v.substring(1, v.length() - 1) : v;
  }

  /** The names of ci.yml's rows that check {@code bits}, for a skip's message. */
  static String rowsCovering(int bits) {
    try {
      List<String> names = ciAsmRows(java.nio.file.Files.readAllLines(CI)).entrySet().stream()
          .filter(e -> e.getValue().contains(bits)).map(e -> "'" + e.getKey() + "'").toList();
      return names.isEmpty() ? "(none: ciRowsCoverEveryWidth fails)" : String.join(", ", names);
    } catch (IOException | RuntimeException e) {
      return "(" + CI + " not read: " + e + ")";
    }
  }

  /**
   * The split of the widths between ci.yml's rows leaves none out: every row that runs the check
   * names its widths, and together they are 128, 256 and 512 bits.
   */
  @Test
  void ciRowsCoverEveryWidth() throws IOException {
    Map<String, List<Integer>> rows = ciAsmRows(java.nio.file.Files.readAllLines(CI));
    assertTrue(rows.size() >= 2, "ci.yml's assembly check rows: " + rows);
    java.util.Set<Integer> covered = new java.util.TreeSet<>();
    for (Map.Entry<String, List<Integer>> row : rows.entrySet()) {
      assertTrue(!row.getValue().isEmpty(), "ci.yml's row '" + row.getKey()
          + "' runs the assembly check but names no asm-widths");
      covered.addAll(row.getValue());
    }
    assertEquals(new java.util.TreeSet<>(AsmCheck.ALL_WIDTHS), covered, "widths checked in CI: "
        + rows);
  }

  /** The parser on a matrix like ci.yml's, and a split that leaves a width out. */
  @Test
  void ciRowsAreReadAsCiWritesThem() {
    List<String> matrix = List.of("      matrix:", "        include:",
        "          - name: x86 wide", "            os: ubuntu-latest", "            asm: true",
        "            asm-widths: \"256,512\"", "          - name: x86 128 bits",
        "            asm: true", "            asm-widths: \"128\"",
        "          - name: Arm 128 bits", "            os: ubuntu-24.04-arm", "    steps:", "      - name: Build",
        "        asm: true");
    assertEquals(Map.of("x86 wide", List.of(256, 512), "x86 128 bits", List.of(128)),
        ciAsmRows(matrix));
    // without the 128-bit row, 128 bits is covered nowhere
    assertEquals(Map.of("x86 wide", List.of(256, 512)),
        ciAsmRows(matrix.subList(0, 6)));
    // a row that runs the check and names no widths is read as covering none
    assertEquals(Map.of("x86 wide", List.of()), ciAsmRows(matrix.subList(0, 5)));
    assertEquals(List.of(128, 256, 512), AsmCheck.widths(null));
    assertEquals(List.of(128, 256, 512), AsmCheck.widths(" "));
    assertEquals(List.of(256, 512), AsmCheck.widths("256, 512"));
    for (String bad : List.of("64", "128,128", "wide", "128;256")) {
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
          () -> AsmCheck.widths(bad), bad);
    }
  }

  /** What leaving the preferred species out rests on: it is one of the fixed widths' species. */
  @Test
  void thePreferredSpeciesIsAFixedWidths() {
    var preferred = jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED;
    int bits = preferred.vectorBitSize();
    assertTrue(bits == 128 || bits == 256 || bits == 512, bits + " bits");
    assertTrue(preferred == jdk.incubator.vector.VectorSpecies.of(double.class,
        jdk.incubator.vector.VectorShape.forBitSize(bits)), "the preferred species is not the "
        + bits + "-bit one");
  }

  /**
   * Each function at each fixed width. The preferred species is left out: it is one of the fixed
   * widths' species, the same object, so its code is what that width's case reads (exp's census
   * read the same at both), and a second run of it would add a third to a row's time.
   */
  static Stream<Arguments> functionsAndWidths() {
    return ARMS.keySet().stream().flatMap(f -> Stream.of(VectorWidth.values())
        .filter(w -> w != VectorWidth.PREFERRED).map(w -> Arguments.of(f, w)));
  }

  @ParameterizedTest(name = "{0} at {1}")
  @MethodSource("functionsAndWidths")
  void eachArmIsVectorAtItsWidthWithNoFmaAndNoCall(String function, VectorWidth width)
      throws Exception {
    int bits = width.species().vectorBitSize();
    List<Integer> widths = AsmCheck.widths();
    if (!widths.contains(bits)) {
      // Not a failure even when required: ci.yml's rows split the widths, and
      // ciRowsCoverEveryWidth checks that the rows together leave none out.
      abort(bits + " bits is not checked here (" + AsmCheck.WIDTHS_ENV + "=" + widths
          + "); in CI it is checked on " + rowsCovering(bits));
    }
    String output = probe(function, width);
    assertTrue(output.contains("asm-probe start: " + function + ", width " + width + ", " + bits
        + " bits"), "the probe ran another function or species than " + function + " at "
        + width + ", " + bits + " bits");
    boolean callsChecked = bits > 128;
    List<String> violations = new ArrayList<>();
    // The census, as Markdown rows: `grep '^| <function> '` on the output gives SKILLS.md's table.
    System.out.println("| function | width | bits | arm | " + String.join(" | ",
        AsmCheck.CENSUS.keySet()) + " |");
    for (String arm : ARMS.get(function)) {
      String method = function + Character.toUpperCase(arm.charAt(0)) + arm.substring(1);
      Optional<AsmCheck.Body> body = AsmCheck.lastC2(output, PROBE + "::" + method);
      if (body.isEmpty()) {
        fail("no standard C2 compile of " + method + " at " + width + " (this JVM's options, "
            + "which the probe got too: " + ManagementFactory.getRuntimeMXBean()
                .getInputArguments() + "); the probe's output ends:\n"
            + AsmCheck.tail(output, 40));
      }
      System.out.println("| " + function + " | " + width + " | " + bits + " | " + arm + " | "
          + String.join(" | ", AsmCheck.census(body.get()).stream().map(String::valueOf).toList())
          + " |");
      violations.addAll(AsmCheck.undecodedViolations(body.get()));
      violations.addAll(AsmCheck.widthViolations(body.get(), bits));
      violations.addAll(AsmCheck.fmaViolations(body.get()));
      violations.addAll(AsmCheck.licenceViolations(body.get()));
      if (callsChecked) {
        violations.addAll(AsmCheck.callViolations(body.get()));
      }
    }
    if (!callsChecked) {
      System.out.println(function + " asm " + width + ": calls not checked at 128 bits on x86 "
          + "(no mask of two lanes lowers; SKILLS.md)");
    }
    assertEquals(List.of(), violations, function + "'s arms at " + width + " (" + bits + " bits)");
  }

  /**
   * The probe's output for {@code function} at {@code width}, or a skip (a failure when required)
   * when this host has no hsdis that HotSpot loads.
   */
  static String probe(String function, VectorWidth width)
      throws IOException, InterruptedException {
    // Not a failure even when required: the rules are x86's, and CI requires the x86 rows only.
    if (AsmCheck.libraryName() == null) {
      abort("the assembly check reads Linux x86-64 code only; this host is "
          + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    }
    AsmCheck.Hsdis hsdis = AsmCheck.findHsdis();
    if (!hsdis.found()) {
      skipOrFail(hsdis.why());
    }
    String inJdk = AsmCheck.hsdisInJdk();
    if (inJdk != null) {
      skipOrFail("the JDK has an hsdis of its own, " + inJdk + ", which HotSpot loads before "
          + hsdis.dir() + "'s; the rules read Capstone's output only");
    }
    String output = AsmCheck.runProbe(AsmProbe.class, hsdis.dir(),
        List.of("-Dvfdlibm.asm.function=" + function, "-Dvfdlibm.asm.width=" + width.name()));
    if (!AsmCheck.disassembled(output)) {
      skipOrFail("hsdis found but not loaded: " + hsdis.dir().resolve(AsmCheck.libraryName())
          + " exists, and HotSpot printed no [Disassembly] with it; the probe's output ends:\n"
          + AsmCheck.tail(output, 20));
    }
    return output;
  }

  private static void skipOrFail(String why) {
    if (AsmCheck.required()) {
      fail(why + " (" + AsmCheck.REQUIRED_ENV + "=true)");
    }
    abort(why);
  }
}
