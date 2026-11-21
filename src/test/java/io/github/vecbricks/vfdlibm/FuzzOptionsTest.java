/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps {@link Fuzz}'s two bounds on a run working: {@code --draws N} gives each thread N inputs,
 * rounded up to a block, and {@code --stop-after N} ends the run once N lane checks have differed.
 * The tests count the inputs the run reports, so that no check depends on the machine's speed.
 * That {@code --stop-after} ends a run at a mismatch is checked where an arm is really broken, by
 * {@code NightlyTest.fuzzCatchesThePlantedFault}, in the fault runs. {@code --threads} and {@code
 * --seconds} are validated by the same line of {@code Fuzz.run} and are not tested here.
 */
class FuzzOptionsTest {

  private static final Pattern FUZZED = Pattern.compile("fuzzed (\\d+) inputs");
  private static final Pattern DIFFER = Pattern.compile("FAILED: (\\d+) lane checks differ");

  @TempDir Path dir;

  /** What a call of {@link Fuzz#run} returned and printed. */
  record Run(int status, String out, String err) {

    /** The number of inputs the run reports having fuzzed, all its threads together. */
    long fuzzed() {
      Matcher matcher = FUZZED.matcher(out);
      assertTrue(matcher.find(), "the run reports no count: " + out);
      return Long.parseLong(matcher.group(1));
    }

    /** The number of lane checks that differed, as the run reports it on failure. */
    long differing() {
      Matcher matcher = DIFFER.matcher(out);
      assertTrue(matcher.find(), "the run reports no mismatches: " + out);
      return Long.parseLong(matcher.group(1));
    }
  }

  /** Calls {@link Fuzz#run} with {@code args}, keeping what it prints. */
  static Run run(String... args) {
    PrintStream out = System.out;
    PrintStream err = System.err;
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    ByteArrayOutputStream complained = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(printed, true, UTF_8));
      System.setErr(new PrintStream(complained, true, UTF_8));
      int status = Fuzz.run(args);
      return new Run(status, printed.toString(UTF_8), complained.toString(UTF_8));
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  /**
   * Two threads of exp from one seed, a time limit of 20 seconds that only ends a run whose
   * bound failed (the count then differs, and the test fails after at most that time), and
   * {@code more}.
   */
  private String[] options(String... more) {
    String[] base = {
      "--function", "exp", "--seed", "0x5eed", "--seconds", "20", "--threads", "2",
      "--out", dir.resolve("fuzz.txt").toString()};
    String[] all = new String[base.length + more.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(more, 0, all, base.length, more.length);
    return all;
  }

  /**
   * A bound that is zero, negative or not a number is a usage error: nothing is fuzzed, and what
   * is printed starts with the program's name. Zero and negative numbers say why; for a text that
   * is no number the message is Java's, which names the text and not the option.
   */
  @Test
  void aBadBoundIsRefused() {
    for (String option : new String[] {"--stop-after", "--draws"}) {
      for (String bad : new String[] {"0", "-5", "1.5", "abc"}) {
        Run run = run(options(option, bad));
        String what = option + " " + bad;
        assertEquals(Nightly.ERROR, run.status(), what);
        assertTrue(run.err().startsWith("Fuzz: "), what + ": " + run.err());
        assertTrue(run.err().contains(bad.matches("-?\\d+") ? "take positive numbers"
            : "\"" + bad + "\""), what + ": " + run.err());
        assertFalse(run.out().contains("fuzzed"), what + " fuzzed something: " + run.out());
      }
    }
  }

  /**
   * With no fault planted nothing differs, so {@code --stop-after 1} ends no run early, and each
   * thread draws its {@code --draws} inputs rounded up to whole blocks. With two threads the run
   * reports twice that many, and no mismatch.
   */
  @Test
  void drawsBoundTheRunInWholeBlocks() {
    assumeTrue(Faults.planted() == null);
    long[][] cases = {
      {1, 1}, {Fuzz.BLOCK, 1}, {Fuzz.BLOCK + 1, 2}, {2L * Fuzz.BLOCK, 2}};
    for (long[] c : cases) {
      Run run = run(options("--draws", "" + c[0], "--stop-after", "1"));
      String what = "--draws " + c[0];
      assertEquals(Nightly.OK, run.status(), what + ": " + run.out() + run.err());
      assertEquals(2 * c[1] * Fuzz.BLOCK, run.fuzzed(), what);
      assertTrue(run.out().contains("every lane matched StrictMath"), what + ": " + run.out());
    }
  }
}
