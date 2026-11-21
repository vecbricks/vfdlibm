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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps the mutant survey working: each transcription of {@link Mutants}, unchanged, is {@code
 * StrictMath} bit for bit; the mutants are the one-step changes of every parameter; and {@link
 * Survey} counts a mutant on the inputs where it differs and writes its table.
 */
class MutantsTest {

  @TempDir Path dir;

  /** The specials, sampled bit patterns, the hard cases and the fuzz's draws for {@code id}. */
  private static double[] inputs(String id) {
    Fuzz.Edges edges = Fuzz.edges(Nightly.Function.of(id));
    SplittableRandom random = new SplittableRandom(4301L);
    double[] drawn = new double[1 << 17];
    for (int i = 0; i < drawn.length; i++) {
      drawn[i] = Fuzz.draw(random, edges);
    }
    return Inputs.concat(
        Inputs.specials(), Inputs.sampled(4302L, 1 << 16), Inputs.hardCases(id), drawn,
        Inputs.uniform(4303L, 1 << 16, -2.0, 2.0), Inputs.magnitudes(4304L, 1 << 16, -60, 10));
  }

  @ParameterizedTest
  @ValueSource(strings = {"exp", "expm1", "log", "log10"})
  void theUnchangedTranscriptionIsStrictMath(String id) {
    Mutants.Port port = Mutants.of(id);
    Mutants.Mutant unchanged = port.unchanged();
    for (double x : inputs(id)) {
      assertEquals(
          Double.doubleToRawLongBits(port.oracle().applyAsDouble(x)),
          Double.doubleToRawLongBits(port.apply(x, unchanged)),
          id + " at " + Oracle.hex(x));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"exp", "expm1", "log", "log10"})
  void theMutantsAreEveryParameterOneStepOff(String id) {
    Mutants.Port port = Mutants.of(id);
    List<Mutants.Mutant> mutants = port.mutants();
    assertEquals(
        2 * port.doubles().length + 2 * port.words().length + port.dropNames().length,
        mutants.size());
    Set<String> names = new HashSet<>();
    for (Mutants.Mutant m : mutants) {
      assertTrue(names.add(m.name()), "two mutants named " + m.name());
      int changed = 0;
      for (int i = 0; i < m.d().length; i++) {
        if (Double.doubleToRawLongBits(m.d()[i])
            != Double.doubleToRawLongBits(port.doubles()[i])) {
          changed++;
          assertTrue(
              m.d()[i] == Math.nextUp(port.doubles()[i])
                  || m.d()[i] == Math.nextDown(port.doubles()[i]),
              m.name());
        }
      }
      for (int i = 0; i < m.w().length; i++) {
        if (m.w()[i] != port.words()[i]) {
          changed++;
          assertEquals(1, Math.abs(m.w()[i] - port.words()[i]), m.name());
        }
      }
      for (boolean dropped : m.drop()) {
        changed += dropped ? 1 : 0;
      }
      assertEquals(1, changed, m.name() + " changes one parameter");
    }
  }

  /**
   * The inputs the survey found for mutants that the unit tests' inputs missed: function,
   * mutant, input bits. Each is in the function's hard cases (the last section of the file), so the
   * suite shows the mutant from then on; {@code dev/survey/README.md} says which rows moved.
   */
  private static final String[][] WITNESSES = {
    {"exp", "invln2+ulp", "0x4080b08f291b07d7"},
    {"exp", "invln2-ulp", "0xc0853d0be66558b9"},
    {"exp", "ln2LO-ulp", "0x40862e41b4b81a53"},
    {"exp", "P3+ulp", "0xc05af233642a11e6"},
    {"exp", "P3-ulp", "0x3fd62e455f911a51"},
    {"expm1", "ln2_lo+ulp", "0x40813d11ecc9bf58"},
    {"expm1", "ln2_lo-ulp", "0x40613becdec29a5c"},
    {"expm1", "Q2+ulp", "0x405033caf119c34c"},
    {"expm1", "Q2-ulp", "0x4012059671dffac3"},
    {"expm1", "k<20-1", "0x402a129199ab1c18"},
    {"expm1", "k<20+1", "0x402b2688d692d264"},
    {"log", "Lg3-ulp", "0x3fe6a0dc5d06b879"},
    {"log", "Lg4+ulp", "0x3ff6a09e66436ea9"},
    {"log", "Lg4-ulp", "0x3ff6a0995fd5e385"},
    {"log", "hx<0x00100000-1", "0x000ffffffffece5e"},
    {"log10", "log.Lg3+ulp", "0x3fe6a09e667f6b7d"},
    {"log10", "log.Lg3-ulp", "0x3fe6a09e66b789a6"},
    {"log10", "log.Lg4-ulp", "0x3fe6a09e65d9ba3e"},
  };

  /** Each witness is a hard case, shows its mutant, and is no input the C itself gets wrong. */
  @Test
  void theHardCasesHoldAWitnessOfEachMutantTheSurveyFound() {
    for (String[] w : WITNESSES) {
      Mutants.Port port = Mutants.of(w[0]);
      Mutants.Mutant mutant = port.mutants().stream()
          .filter(m -> m.name().equals(w[1])).findFirst().orElseThrow();
      long bits = Long.parseUnsignedLong(w[2].substring(2), 16);
      double x = Double.longBitsToDouble(bits);
      String at = w[0] + " " + w[1] + " at " + Oracle.hex(x);
      assertTrue(Arrays.stream(Inputs.hardCases(w[0]))
          .anyMatch(h -> Double.doubleToRawLongBits(h) == bits), at + " is not a hard case");
      long want = Double.doubleToRawLongBits(port.oracle().applyAsDouble(x));
      assertEquals(want, Double.doubleToRawLongBits(port.apply(x, port.unchanged())), at);
      assertTrue(want != Double.doubleToRawLongBits(port.apply(x, mutant)),
          at + " does not show its mutant");
    }
  }

  /** A dropped reduction term changes many of exp's results, and none where no k is reduced. */
  @Test
  void aCountIsTheInputsThatDiffer() {
    Mutants.Port port = Mutants.EXP;
    Mutants.Mutant[] mutants = port.mutants().toArray(new Mutants.Mutant[0]);
    int drop = -1;
    for (int m = 0; m < mutants.length; m++) {
      if (mutants[m].name().equals("drop:x=hi-lo")) {
        drop = m;
      }
    }
    assertTrue(drop >= 0);
    long[] reduced = Arrays.stream(Inputs.uniform(4305L, 4096, 1.5, 700.0))
        .mapToLong(Double::doubleToRawLongBits).toArray();
    long[] unreduced = Arrays.stream(Inputs.uniform(4306L, 4096, -0.3, 0.3))
        .mapToLong(Double::doubleToRawLongBits).toArray();
    Survey.Tally far = Survey.run(port, mutants, Survey.listTasks(reduced), 2);
    Survey.Tally near = Survey.run(port, mutants, Survey.listTasks(unreduced), 2);
    assertEquals(4096, far.inputs);
    assertEquals(0, far.unchangedDiffers);
    long expected = 0;
    for (long bits : reduced) {
      double x = Double.longBitsToDouble(bits);
      expected += Double.doubleToRawLongBits(port.apply(x, mutants[drop]))
          != Double.doubleToRawLongBits(StrictMath.exp(x)) ? 1 : 0;
    }
    assertEquals(expected, far.count[drop]);
    assertTrue(far.count[drop] > 2048, "dropping lo changed " + far.count[drop] + " of 4096");
    assertEquals(0, near.count[drop]);
    double example = Double.longBitsToDouble(far.example[drop]);
    assertTrue(Double.doubleToRawLongBits(port.apply(example, mutants[drop]))
        != Double.doubleToRawLongBits(StrictMath.exp(example)));
  }

  @Test
  void aTestsNameSaysWhichFunctionItsInputsAreFor() {
    assertEquals("exp", Survey.functionOf("VFdlibm.exp"));
    assertEquals("exp", Survey.functionOf("Exp.expBlended"));
    assertEquals("expm1", Survey.functionOf("Expm1.expm1Guarded"));
    assertEquals("log", Survey.functionOf("Log.logBlended"));
    assertEquals("log10", Survey.functionOf("Log10.log10Blended"));
    assertEquals("log1p", Survey.functionOf("VFdlibm.log1p"));
    assertNull(Survey.functionOf("exp"));
    assertNull(Survey.functionOf("Harness.sqrt"));
  }

  /**
   * Appended runs, as the second pass makes them: the header once, a {@code # run:} line per
   * run, and the same rows whatever the threads, examples included.
   */
  @Test
  void appendedRunsAgreeAtAnyThreadCount() throws IOException {
    double[] xs = inputs("exp");
    ByteBuffer bytes = ByteBuffer.allocate(8 * xs.length);
    for (double x : xs) {
      bytes.putLong(Double.doubleToRawLongBits(x));
    }
    Files.write(dir.resolve("exp-1.bin"), bytes.array());
    Path out = dir.resolve("exp-uncaught.tsv");
    for (String threads : new String[] {"1", "3"}) {
      assertEquals(Nightly.OK, Survey.run(new String[] {
        "--function", "exp", "--suite", dir.toString(), "--fuzz", "100000", "--stripe",
        "0/4096", "--search", "100000", "--seed", "0x5eed", "--threads", threads,
        "--only", "\\QP2+ulp\\E|\\Qinvln2-ulp\\E", "--append", "true", "--out", out.toString()}));
    }
    List<String> lines = Files.readAllLines(out);
    assertEquals(1, lines.stream().filter(line -> line.startsWith("# Survey of")).count());
    assertEquals(2, lines.stream().filter(line -> line.startsWith("# run:")).count());
    List<String> rows = lines.stream()
        .filter(line -> !line.startsWith("#") && !line.startsWith("mutant\t")).toList();
    assertEquals(4, rows.size(), rows.toString());
    assertEquals(rows.subList(0, 2), rows.subList(2, 4));
    // invln2 one ulp down moves k near (k + 1/2) ln2, which the fuzz draws.
    assertTrue(rows.stream().anyMatch(row -> row.startsWith("invln2-ulp\t")
        && !row.split("\t")[8].equals("none")), rows.toString());
  }

  /** The program end to end on a small suite file: a line per mutant, exit status 0. */
  @Test
  void theSurveyWritesALinePerMutant() throws IOException {
    double[] xs = inputs("log");
    ByteBuffer bytes = ByteBuffer.allocate(16 * xs.length);
    for (double x : xs) {
      bytes.putLong(Double.doubleToRawLongBits(x));
    }
    for (double x : xs) {
      bytes.putLong(Double.doubleToRawLongBits(x));
    }
    Files.write(dir.resolve("log-1.bin"), bytes.array());
    long distinct = Arrays.stream(xs).mapToLong(Double::doubleToRawLongBits).distinct().count();
    assertEquals(distinct, Survey.recorded(dir, "log").length);

    Path out = dir.resolve("log.tsv");
    int status = Survey.run(new String[] {
      "--function", "log", "--suite", dir.toString(), "--fuzz", "4096", "--stripe", "0/4096",
      "--search", "4096",
      "--seed", "0x5eed", "--threads", "2", "--out", out.toString()});
    assertEquals(Nightly.OK, status);
    List<String> lines = Files.readAllLines(out);
    List<String> rows = lines.stream().filter(line -> !line.startsWith("#")).toList();
    assertEquals(1 + Mutants.LOG.mutants().size(), rows.size());
    assertTrue(rows.get(0).startsWith("mutant\tkind\tsuite\t"), rows.get(0));
    assertTrue(lines.contains("# unchanged transcription: differs on 0 inputs"), lines.get(0));
    for (String row : rows.subList(1, rows.size())) {
      String[] cells = row.split("\t");
      assertEquals(10, cells.length, row);
      assertTrue(List.of(Survey.LAYERS).contains(cells[8]), row);
    }
    // Lg1 one ulp off changes results all over the common path, which the suite's inputs hold.
    assertTrue(rows.stream().anyMatch(row -> row.startsWith("Lg1+ulp\tconstant\t")
        && row.split("\t")[8].equals("suite")));
  }
}
