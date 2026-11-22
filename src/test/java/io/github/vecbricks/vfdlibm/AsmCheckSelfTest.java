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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests of {@link AsmCheck} on crafted disassembly, written in the shapes HotSpot prints with
 * hsdis (Capstone, Intel syntax: an address, the mnemonic and its operands separated by tabs, the
 * inlined frames and the relocation on comment lines below): a clean body passes every rule, and
 * a body with one defect, a fused multiply-add, a narrow or scalar instruction, a call, or
 * StrictMath's code, fails the rule for it. The parser must also find nothing to read in HotSpot's
 * hex dump (no hsdis loaded), in a C1 compile and in an on-stack-replacement one, since a rule
 * that reads no instruction passes vacuously.
 */
class AsmCheckSelfTest {

  private static final String METHOD = "io.github.vecbricks.vfdlibm.Fixture::arm";

  /** A C2 nmethod of {@link #METHOD} holding {@code code}, with a constant pool and stubs. */
  private static String nmethod(String code) {
    return nmethod("c2", "", code);
  }

  private static String nmethod(String compiler, String osr, String code) {
    return """
        ============================= %s-compiled nmethod ==============================
        ----------------------------------- Assembly -----------------------------------

        Compiled method (%s) 2537 1030 %s      4       %s%s (41 bytes)
         total in heap  [0x00007ac3981f8708,0x00007ac3981f8c50] = 1352
         main code      [0x00007ac3981f88c0,0x00007ac3981f8c30] = 880

        [Disassembly]
        --------------------------------------------------------------------------------

        [Constant Pool]
                     Address          hex4                    hex8
          0x00007ac3981f8800:   0xf4f4f4f4      0xf4f4f4f4f4f4f4f4

        --------------------------------------------------------------------------------

        [Verified Entry Point]
          # {method} {0x00007ac34c401928} 'arm' '([D[D)V' in 'io/github/vecbricks/vfdlibm/Fixture'
          # parm0:    rsi:rsi   = '[D'
        %s\
        [Exception Handler]
          0x00007ac3981f8c30:   jmp\t\t0x7ac397b923e0      ;   {runtime_call ExceptionBlob}
        [Deopt Handler Code]
          0x00007ac3981f8c35:   call\t\t0x7ac3981f8c3a
          0x00007ac3981f8c3a:   jmp\t\t0x7ac397aaf2e0      ;   {runtime_call DeoptimizationBlob}
        --------------------------------------------------------------------------------
        [/Disassembly]
        """.formatted(compiler.toUpperCase(), compiler, osr, METHOD, osr.isEmpty() ? "" : " @ 2",
        code);
  }

  /** One instruction line at {@code address}. */
  private static String insn(int address, String mnemonic, String operands) {
    return String.format("  0x00007ac3981f%04x:   %s\t\t%s%n", address, mnemonic, operands);
  }

  /** A call with the comment lines HotSpot prints under it, ending in {@code relocation}. */
  private static String call(int address, String frame, String relocation) {
    return insn(address, "call", "0x7ac397aae4e0      ; ImmutableOopMap {rbp=Oop }")
        + "                                                            ;*ifeq {reexecute=1}\n"
        + "                                                            ; - " + frame + "\n"
        + "                                                            ;   " + relocation + "\n";
  }

  /** A clean 512-bit body: zmm arithmetic, a ymm int lane, an xmm zeroing idiom, two calls. */
  private static String clean512() {
    return insn(0x88c0, "call", "0x7ac397a975c0      ;   {runtime_call Stub::method_entry_barrier}")
        + insn(0x88c5, "vxorps", "xmm3, xmm3, xmm3")
        + insn(0x88c9, "vmovupd", "zmm21, zmmword ptr [r8 + r11*8 + 0x10]")
        + insn(0x88d4, "vmulpd", "zmm0, zmm19, zmm21")
        + insn(0x88da, "vaddpd", "zmm0, zmm0, zmmword ptr [rip + 0x20]")
        + insn(0x88e4, "vcvttpd2dq", "ymm15, zmm0")
        + insn(0x88ea, "vpmovsxdq", "zmm2, ymm15")
        + insn(0x88f0, "vdivpd", "zmm0, zmm24, zmm0")
        + insn(0x88f6, "vpblendmq", "zmm1 {k3}, zmm0, zmm23")
        + insn(0x88fc, "kortestb", "k7, k7")
        + insn(0x8900, "jne", "0x7ac3981f8a00")
        + call(0x8a00, "io.github.vecbricks.vfdlibm.Exp::exp@615 (line 298)",
            "{runtime_call UncommonTrapBlob}");
  }

  private static AsmCheck.Body body(String output) {
    Optional<AsmCheck.Body> body = AsmCheck.lastC2(output, METHOD);
    assertTrue(body.isPresent(), "no C2 body parsed");
    return body.get();
  }

  @Test
  void aCleanBodyPassesEveryRule() {
    String output = nmethod(clean512());
    assertTrue(AsmCheck.disassembled(output));
    AsmCheck.Body body = body(output);
    // the constant pool and the stubs are not code; the entry barrier is the first instruction
    assertEquals(12, body.code().size(), body.code().toString());
    assertEquals(List.of(), AsmCheck.widthViolations(body, 512));
    assertEquals(List.of(), AsmCheck.fmaViolations(body));
    assertEquals(List.of(), AsmCheck.callViolations(body));
    assertEquals(List.of(), AsmCheck.licenceViolations(body));
    List<Integer> census = AsmCheck.census(body);
    List<String> columns = List.copyOf(AsmCheck.CENSUS.keySet());
    assertEquals(3, census.get(columns.indexOf("pd arith")));
    assertEquals(1, census.get(columns.indexOf("vdivpd")));
    assertEquals(2, census.get(columns.indexOf("calls")));
    assertEquals(2, census.get(columns.indexOf("converts")));
    assertEquals(0, census.get(columns.indexOf("fma")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"vfmadd231pd", "vfmsub132pd", "vfnmadd213pd", "vfnmsub231sd",
      "vfmaddsub213pd", "vfmsubadd231pd", "vfmadd213ps"})
  void aFusedMultiplyAddFails(String fma) {
    AsmCheck.Body body = body(nmethod(clean512().replace("vmulpd", fma)));
    assertEquals(1, AsmCheck.fmaViolations(body).size(), fma);
  }

  @Test
  void aNarrowBodyFailsTheWidthRule() {
    // the whole body a width down: every packed-double instruction on ymm
    String narrow = clean512().replace("zmm", "ymm");
    List<String> violations = AsmCheck.widthViolations(body(nmethod(narrow)), 512);
    assertEquals(3, violations.size(), violations.toString());
    // the same body read at its own width passes
    assertEquals(List.of(), AsmCheck.widthViolations(body(nmethod(narrow)), 256));
    // one instruction narrow, and one wider than expected
    assertEquals(1, AsmCheck.widthViolations(
        body(nmethod(clean512().replace("vdivpd\t\tzmm0", "vdivpd\t\tymm0"))), 512).size());
    assertEquals(1, AsmCheck.widthViolations(body(nmethod(narrow.replace(
        "vdivpd\t\tymm0, ymm24, ymm0", "vdivpd\t\tzmm0, zmm24, zmm0"))), 256).size());
  }

  @Test
  void scalarOrNoPackedArithmeticFailsTheWidthRule() {
    String scalar =
        clean512().replace("vmulpd\t\tzmm0, zmm19, zmm21", "vmulsd\t\txmm0, xmm19, xmm21");
    List<String> violations = AsmCheck.widthViolations(body(nmethod(scalar)), 512);
    assertEquals(1, violations.size(), violations.toString());
    assertTrue(violations.get(0).contains("scalar"), violations.toString());
    String none = clean512().replaceAll("v(mul|add|div)pd", "vpaddq");
    violations = AsmCheck.widthViolations(body(nmethod(none)), 512);
    assertEquals(1, violations.size(), violations.toString());
    assertTrue(violations.get(0).contains("no packed-double"), violations.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{static_call}", "{optimized virtual_call}", "{virtual_call}",
      "{runtime_call C2 Runtime new_instance}", "{runtime_call StubRoutines::dexp}"})
  void aCallThatIsNotATrapFails(String relocation) {
    String code = clean512()
        + call(0x8a40, "jdk.incubator.vector.DoubleVector::fromArray@24 (line 2832)", relocation);
    List<String> violations = AsmCheck.callViolations(body(nmethod(code)));
    assertEquals(1, violations.size(), violations.toString());
    assertTrue(violations.get(0).contains(relocation), violations.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"java.lang.StrictMath::exp@1 (line 254)",
      "java.lang.FdLibm$Exp::compute@12 (line 2400)"})
  void strictMathCodeFails(String frame) {
    String code = clean512() + call(0x8a40, frame, "{runtime_call UncommonTrapBlob}");
    assertEquals(1, AsmCheck.licenceViolations(body(nmethod(code))).size());
  }

  /** Capstone 4.0.2's reading of an AVX-512 body: an EVEX prefix as .byte, garbage after it. */
  @Test
  void undecodedBytesFail() {
    String code = clean512()
        + insn(0x8a40, ".byte", "0x62")
        + insn(0x8a41, "cmc", "")
        + insn(0x8a42, "call", "0x76759d920cce")
        + insn(0x8a47, "(bad)", "");
    AsmCheck.Body body = body(nmethod(code));
    assertEquals(2, AsmCheck.undecodedViolations(body).size(), body.code().toString());
    assertEquals(List.of(), AsmCheck.undecodedViolations(body(nmethod(clean512()))));
  }

  /** HotSpot's output when no hsdis is loaded: the same nmethod as hex words under MachCode. */
  @Test
  void aHexDumpIsNotDisassembly() {
    String hex = """
        ============================= C2-compiled nmethod ==============================
        Compiled method (c2) 544 1419       4       %s (41 bytes)
         main code      [0x00007b7f141fe580,0x00007b7f141fe5d0] = 80

        [Constant Pool (empty)]

        [MachCode]
        [Verified Entry Point]
          # {method} {0x00007b7ec0a9c298} 'arm' '([D[D)V' in 'io/github/vecbricks/vfdlibm/Fixture'
          0x00007b7f141fe580: 4881 ec18 | 0000 0048 | 896c 2410 | 4181 7f20 | 0000 0000 | 0f85
          0x00007b7f141fe5a0: ;   {poll_return}
          0x00007b7f141fe5a0: 105d 493b | 6728 0f87 | 0100 0000
          0x00007b7f141fe5ac: c349 baa2 | e51f 147f | 7b00 004d | 8997 3805
        [Exception Handler]
          0x00007b7f141fe5d0: e90b 3e99 | ffe8 0000 | 0000 4883
        [/MachCode]
        """.formatted(METHOD);
    assertFalse(AsmCheck.disassembled(hex));
    assertEquals(Optional.empty(), AsmCheck.lastC2(hex, METHOD));
  }

  @Test
  void aC1OrOnStackReplacementCompileIsNotRead() {
    assertEquals(Optional.empty(), AsmCheck.lastC2(nmethod("c1", "", clean512()), METHOD));
    assertEquals(Optional.empty(), AsmCheck.lastC2(nmethod("c2", "%", clean512()), METHOD));
    // with an OSR compile after the standard one, the standard one is read
    String narrowOsr = nmethod("c2", "%", clean512().replace("zmm", "ymm"));
    AsmCheck.Body body = body(nmethod(clean512()) + narrowOsr);
    assertEquals(List.of(), AsmCheck.widthViolations(body, 512));
    // of two standard compiles, the last is read
    body = body(nmethod(clean512()) + nmethod(clean512().replace("zmm", "ymm")));
    assertEquals(3, AsmCheck.widthViolations(body, 512).size());
  }

  @Test
  void hsdisNotFoundSaysWhereItLooked(@TempDir Path dir) throws Exception {
    AsmCheck.Hsdis none = AsmCheck.findHsdis(null, "hsdis-amd64.so", List.of(dir));
    assertFalse(none.found());
    assertTrue(none.why().startsWith("hsdis not found") && none.why().contains(dir.toString()),
        none.why());
    Files.createFile(dir.resolve("hsdis-amd64.so"));
    assertEquals(dir, AsmCheck.findHsdis(null, "hsdis-amd64.so", List.of(dir)).dir());
    // the environment's directory replaces the defaults
    AsmCheck.Hsdis env = AsmCheck.findHsdis(dir.resolve("elsewhere").toString(), "hsdis-amd64.so",
        List.of(dir));
    assertFalse(env.found());
  }
}
