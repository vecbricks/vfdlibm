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
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Keeps the nightly runs, {@link Sweep} and {@link Fuzz}, working between nights: each runs on a
 * few thousand inputs and must pass, their mismatch file must be in {@code Inputs.hardCases}
 * form, and in each fault run but {@link #NOT_FOR_THE_FUZZ}'s the fuzz must catch the planted
 * fault within {@link #DRAWS} inputs a thread, stopping at its first mismatch, so the nightly
 * can fail.
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

  @Test
  void fuzzDrawsAreBoundedAndValidated() {
    assumeTrue(Faults.planted() == null);
    String out = dir.resolve("fuzz.txt").toString();
    // A run that ignored --draws would end itself at the 20 seconds, with nothing left running.
    long start = System.nanoTime();
    assertEquals(Nightly.OK, Fuzz.run(new String[] {
      "--function", "exp", "--seed", "0x5eed", "--seconds", "20", "--draws", "4096",
      "--threads", "2", "--out", out}));
    assertTrue(System.nanoTime() - start < 10_000_000_000L, "the run ended on its seconds");
    assertEquals(Nightly.ERROR, Fuzz.run(new String[] {
      "--function", "exp", "--seed", "0x5eed", "--seconds", "600", "--draws", "0",
      "--threads", "2", "--out", out}));
    for (String bad : new String[] {"-5", "abc"}) {
      assertEquals(Nightly.ERROR, Fuzz.run(new String[] {
        "--function", "exp", "--seed", "0x5eed", "--seconds", "600", "--draws", bad,
        "--threads", "2", "--out", out}));
    }
  }

  /**
   * Faults the fuzz cannot be asked to find: the inputs where they change a result are about one
   * in 2^29 of a window of 2^35 doubles, and only {@code Log1pTest}'s witnesses reach them.
   */
  private static final Set<String> NOT_FOR_THE_FUZZ = Set.of(Log1p.FOLD_SHORT_PATH);

  /**
   * Inputs each fuzz thread draws in {@link #fuzzCatchesThePlantedFault}. The streams are fixed
   * by the seed, so the fuzz finds each fault at one input index, the same at the 128, 256, 512,
   * preferred and max species of the host measured; only the time to reach it varies with the
   * machine. Measured on an x86 laptop, one stream per run, for the 23 faults the test runs: with
   * the test's seed 0x5eed (first thread) the index is 4,096 for 19 faults and, for the others,
   * 69,632 (exp.thresholdMoved), 57,344 (exp.dropKForcing), 20,480 (expm1.dropKForcing) and
   * 16,384 (log.thresholdMoved); the second thread's seed 0x5eee finds them at 24,576, 159,744,
   * 110,592 and 4,096. Over 20 seeds sinh's faults need at most 8,192 and the longest is 614,400
   * (exp.dropKForcing), which a million would clear only 1.6 times, but the seed is fixed. One
   * thread finding the fault is enough, so a fault's index is the smaller of its two threads':
   * the largest is 57,344 (exp.dropKForcing), and a million inputs is 17 times that (14 times the
   * first thread's alone, 69,632). The time was the trouble: on GitHub's x86 runner (4 cores, 128
   * bits) the one second the test used to allow drew 32,768 inputs, 16,384 a thread, one block
   * short of expm1.dropKForcing's 20,480 (run 44893905573, job 136815350908). Now the inputs are
   * bounded and the seconds only end a hung run. The test also passes {@code --stop-after 1}, so
   * a fault found costs the inputs up to it and the bound is paid in full only by a fault the
   * fuzz misses, which fails.
   */
  private static final String DRAWS = "1000000";

  /**
   * The fuzz finds each planted fault of the functions within {@link #DRAWS} inputs per
   * thread, but those of {@link #NOT_FOR_THE_FUZZ}.
   */
  @Test
  @Tag("fault")
  void fuzzCatchesThePlantedFault() {
    String fault = Faults.planted();
    assumeTrue(fault != null && !fault.startsWith("selftest."), "not a function's fault");
    assumeTrue(!NOT_FOR_THE_FUZZ.contains(fault), "a fault the fuzz is not asked to find");
    String function = fault.substring(0, fault.indexOf('.'));
    FuzzOptionsTest.Run run = FuzzOptionsTest.run(
        "--function", function, "--seed", "0x5eed", "--draws", DRAWS, "--stop-after", "1",
        "--seconds", "600", "--threads", "2", "--out", dir.resolve("fuzz.txt").toString());
    assertEquals(Nightly.MISMATCH, run.status(),
        "the fuzz missed planted fault " + fault + ": " + run.out() + run.err());
    // Without --stop-after a fault that is rare runs on to the bound (DRAWS inputs, rounded up to
    // a block, from each of two threads), and one that is dense runs on to Mismatches.ENOUGH
    // differing lane checks; these two assertions fail such a run. They do not pin the stop to
    // the first block. The test passes under every function's fault, so the first block of two
    // threads stays under ENOUGH for them; a function with many more arms may need it looked at.
    assertTrue(run.fuzzed() < 2 * Long.parseLong(DRAWS),
        fault + ": " + run.fuzzed() + " inputs fuzzed, not stopped at the first mismatch");
    assertTrue(run.differing() < Nightly.Mismatches.ENOUGH,
        fault + ": " + run.differing() + " lane checks differ, not stopped at the first mismatch");
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
