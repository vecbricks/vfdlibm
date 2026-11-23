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
 * VFDLIBM_ASM_REQUIRED=true}, as CI's x86 rows set, the last three fail instead.
 */
class AsmTest {

  private static final String PROBE = AsmProbe.class.getName();

  /** Each function's arms, as the probe names them; the probe must hold these and no others. */
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

  /** The probe's arms are this test's: none missing, and none that no function here lists. */
  @Test
  void theProbeHoldsEveryListedArm() {
    List<String> listed = new ArrayList<>();
    for (Map.Entry<String, List<String>> function : ARMS.entrySet()) {
      List<String> methods = AsmProbe.arms(function.getKey()).stream()
          .map(java.lang.reflect.Method::getName).toList();
      assertEquals(function.getValue(), methods.stream()
          .map(m -> AsmProbe.armName(function.getKey(), m)).toList(), function.getKey());
      listed.addAll(methods);
    }
    assertEquals(AsmProbe.allArms().stream().map(java.lang.reflect.Method::getName).sorted()
        .toList(), listed.stream().sorted().toList(), "the probe's arms");
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
