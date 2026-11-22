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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The assembly check of exp's vector arms (SKILLS.md, "The assembly check"): at each {@link
 * VectorWidth} the host has, {@link ExpAsmProbe} runs in a JVM of its own and prints the C2 code
 * of a loop over each arm with the arm inlined, and each arm's code must meet {@link AsmCheck}'s
 * rules: packed-double arithmetic on registers of the width, no fused multiply-add, no call but
 * uncommon traps and the entry barrier, and no StrictMath code.
 *
 * <p>The arms are {@code VFdlibm.exp} unmasked and masked (the finish arm), {@code
 * Exp.expBlended} unmasked and masked, and {@code Exp.expCore} with {@code Exp.expRare}. Exp's
 * own methods are not printed alone: without the forced inline the arms' shared body compiles as
 * a method of its own whose {@code arm} is no constant, taking and returning its vectors as heap
 * objects, which is not code any caller runs inline (SKILLS.md).
 *
 * <p>At 128 bits on x86 the call rule is not asserted: C2 lowers no mask of two 64-bit lanes
 * there (SKILLS.md, "At two 64-bit lanes x86 C2 lowers no mask"), so every arm calls into the
 * Vector API's Java fallback. Its width and FMA rules are asserted. The width is the child's
 * species, so the preferred species at 128 bits ({@code -XX:MaxVectorSize=16}) is treated alike.
 *
 * <p>Skipped, with the reason, on a host that is not x86-64, when no hsdis is found ({@link
 * AsmCheck#findHsdis}), and when one is found but HotSpot printed no disassembly with it; with
 * {@code VFDLIBM_ASM_REQUIRED=true}, as CI's x86 rows set, the last two fail instead.
 */
class ExpAsmTest {

  private static final String PROBE = ExpAsmProbe.class.getName();
  /**
   * The probe's methods, one per arm. Named here, not read from the probe, whose initialization
   * reads the child's width property.
   */
  static final List<String> ARMS =
      List.of("finish", "finishMasked", "blended", "blendedMasked", "core");

  @ParameterizedTest
  @EnumSource(VectorWidth.class)
  void eachArmIsVectorAtItsWidthWithNoFmaAndNoCall(VectorWidth width) throws Exception {
    int bits = width.species().vectorBitSize();
    String output = probe(width);
    assertTrue(output.contains("asm-probe start: width " + width + ", " + bits + " bits"),
        "the probe ran another species than " + width + " at " + bits + " bits");
    boolean callsChecked = bits > 128;
    List<String> violations = new ArrayList<>();
    // The census, as Markdown rows: `grep '^| exp '` on the test's output gives SKILLS.md's table.
    System.out.println("| function | width | bits | arm | " + String.join(" | ",
        AsmCheck.CENSUS.keySet()) + " |");
    for (String arm : ARMS) {
      Optional<AsmCheck.Body> body = AsmCheck.lastC2(output, PROBE + "::" + arm);
      if (body.isEmpty()) {
        fail("no standard C2 compile of " + arm + " at " + width + "; the probe's output ends:\n"
            + AsmCheck.tail(output, 40));
      }
      System.out.println("| exp | " + width + " | " + bits + " | " + arm + " | "
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
      System.out.println("exp asm " + width + ": calls not checked at 128 bits on x86 (no mask "
          + "of two lanes lowers; SKILLS.md)");
    }
    assertEquals(List.of(), violations, "exp's arms at " + width + " (" + bits + " bits)");
  }

  /**
   * The probe's output at {@code width}, or a skip (a failure when required) when this host has
   * no hsdis that HotSpot loads.
   */
  static String probe(VectorWidth width) throws IOException, InterruptedException {
    if (AsmCheck.libraryName() == null) {
      abort("the assembly check reads x86-64 code only; this host is "
          + System.getProperty("os.arch"));
    }
    AsmCheck.Hsdis hsdis = AsmCheck.findHsdis();
    if (!hsdis.found()) {
      skipOrFail(hsdis.why());
    }
    String output = AsmCheck.runProbe(ExpAsmProbe.class, hsdis.dir(),
        List.of("-Dvfdlibm.asm.width=" + width.name()));
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
