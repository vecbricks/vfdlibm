/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Keeps the nightly runs, {@link Sweep} and {@link Fuzz}, working between nights: each runs on a
 * few thousand inputs and must pass, their mismatch file must be in {@code Inputs.hardCases}
 * form, and in each fault run the fuzz must catch the planted fault, so the nightly can fail.
 */
class NightlyTest {

  @TempDir Path dir;

  @ParameterizedTest
  @EnumSource(Nightly.Function.class)
  void sweepOfASliceMatches(Nightly.Function function) throws IOException {
    assumeTrue(Faults.planted() == null);
    Path out = dir.resolve("sweep.txt");
    // 4096 high words from 0x3ff00000: x from 1 to about 1.004, both arms' common path.
    int status = Sweep.run(new String[] {
      "--function", function.id, "--part", (0x3ff00000L >> 12) + "/" + (1L << 20),
      "--seed", "0x5eed", "--threads", "2", "--out", out.toString()});
    assertEquals(Nightly.OK, status);
    List<String> lines = Files.readAllLines(out);
    assertTrue(lines.get(0).startsWith("# sweep " + function.id), lines.get(0));
    assertEquals("# total mismatches: 0 {}", lines.get(lines.size() - 1));
  }

  @ParameterizedTest
  @EnumSource(Nightly.Function.class)
  void shortFuzzMatches(Nightly.Function function) {
    assumeTrue(Faults.planted() == null);
    int status = Fuzz.run(new String[] {
      "--function", function.id, "--seed", "0x5eed", "--seconds", "0.2", "--threads", "2",
      "--out", dir.resolve("fuzz.txt").toString()});
    assertEquals(Nightly.OK, status);
  }

  /** The fuzz finds each planted fault of the three functions in a second at most. */
  @Test
  @Tag("fault")
  void fuzzCatchesThePlantedFault() {
    String fault = Faults.planted();
    assumeTrue(fault != null && !fault.startsWith("selftest."), "not a function's fault");
    String function = fault.substring(0, fault.indexOf('.'));
    int status = Fuzz.run(new String[] {
      "--function", function, "--seed", "0x5eed", "--seconds", "1", "--threads", "2",
      "--out", dir.resolve("fuzz.txt").toString()});
    assertEquals(Nightly.MISMATCH, status, "the fuzz missed planted fault " + fault);
  }

  @Test
  void mismatchFileIsAHardCasesFile() throws IOException {
    Path out = dir.resolve("m.txt");
    try (Nightly.Mismatches m = new Nightly.Mismatches(out, "a header\nreplay: x", "ctx")) {
      m.add("VFdlibm.exp", -1, 2, 0x3ff0000000000000L, 0x4005bf0a8b145769L, 0x4005bf0a8b14576aL);
      m.add("VFdlibm.exp", 0x5, 0, 0x4000000000000000L, 0x401d8e64b8d4ddaeL, 0L);
    }
    List<String> lines = Files.readAllLines(out);
    assertEquals(List.of(
        "# a header",
        "# replay: x",
        "# MISMATCH ctx arm=VFdlibm.exp masked=false lane=2 x=0x3ff0000000000000"
            + " expected=0x4005bf0a8b145769 actual=0x4005bf0a8b14576a",
        "0x3ff0000000000000",
        "# MISMATCH ctx arm=VFdlibm.exp masked=true mask=0x5 lane=0 x=0x4000000000000000"
            + " expected=0x401d8e64b8d4ddae actual=0x0000000000000000",
        "0x4000000000000000",
        "# total mismatches: 2 {VFdlibm.exp unmasked=1, VFdlibm.exp masked=1}"), lines);
    for (String line : lines) {
      assertTrue(line.startsWith("#") || line.matches("0x[0-9a-f]{16}"), line);
    }
  }

  @Test
  void partsAndStripes() {
    assertArrayEquals(new long[] {0x3ff00000L, 0x40000000L}, Sweep.range("1023/4096"));
    assertArrayEquals(new long[] {0L, 1L << 32}, Sweep.range("0/1"));
    long[][] stripe = Sweep.stripe("1/4");
    assertEquals(Sweep.CHUNKS / 4, stripe.length);
    assertArrayEquals(new long[] {0x00100000L, 0x00200000L}, stripe[0]);
    assertArrayEquals(new long[] {0x00500000L, 0x00600000L}, stripe[1]);
    assertArrayEquals(new long[] {0xffd00000L, 0xffe00000L}, stripe[stripe.length - 1]);
    for (String bad : new String[] {"4/4", "1/3", "-1/4", "1", "a/4", "0/8192"}) {
      assertThrows(IllegalArgumentException.class, () -> Sweep.stripe(bad), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> Sweep.range("0/" + (1L << 33)));
  }

  /** Every stripe of a set of S covers its chunks once, and the S stripes cover all of them. */
  @Test
  void stripesCoverEveryHighWordOnce() {
    boolean[] seen = new boolean[Sweep.CHUNKS];
    for (int s = 0; s < 16; s++) {
      for (long[] range : Sweep.stripe(s + "/16")) {
        int chunk = (int) (range[0] >> 20);
        assertTrue(!seen[chunk], "chunk " + chunk + " twice");
        seen[chunk] = true;
        assertEquals(1L << 20, range[1] - range[0]);
      }
    }
    for (boolean b : seen) {
      assertTrue(b);
    }
  }

  @Test
  void badOptionsAreUsageErrors() {
    assertEquals(Nightly.ERROR, Sweep.run(new String[] {"--function", "exp"}));
    assertEquals(Nightly.ERROR, Sweep.run(new String[] {"--function", "sin", "--seed", "1"}));
    assertEquals(Nightly.ERROR, Fuzz.run(new String[] {"--function", "exp", "--seed", "1"}));
    assertEquals(Nightly.ERROR, Fuzz.run(new String[] {"--fucntion", "exp"}));
  }
}
