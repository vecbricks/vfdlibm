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
