/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from vecbricks/varka's VarkaProofFilesSuite.scala, the check of its rendered proofs.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The rendered files under {@code proofs/} are what {@link ProofFiles} renders: the prelude's
 * checks from the JVM's own results, and exp's and log's proofs from their constants. A
 * definition, an operand set or a constant that moves without its file fails here, rather than
 * leaving a committed check that no longer says what the code says. The
 * proofs themselves run under {@code dev/prove.sh}, which needs no JVM.
 *
 * <p>{@code -Dvfdlibm.proofs.regen=true} rewrites the files instead, e.g. {@code mvn -q test
 * -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true}; then run {@code dev/prove.sh} on them.
 */
class ProofFilesTest {

  @Test
  void theCommittedProofFilesAreTheOnesTheCodeRenders() throws Exception {
    Path dir = Path.of("proofs");
    boolean regen = Boolean.getBoolean("vfdlibm.proofs.regen");
    for (Map.Entry<String, String> file : ProofFiles.render().entrySet()) {
      Path path = dir.resolve(file.getKey());
      String text = file.getValue();
      if (regen) {
        Files.writeString(path, text, StandardCharsets.UTF_8);
        continue;
      }
      String committed = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
      if (!committed.equals(text)) {
        Set<String> was = committed.lines().collect(Collectors.toSet());
        String moved = text.lines().filter(l -> !was.contains(l)).limit(20)
            .collect(Collectors.joining("\n  "));
        fail(file.getKey() + " differs from what the code renders; regenerate it with mvn -q "
            + "test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true, then run dev/prove.sh. "
            + "First lines that differ:\n  " + moved);
      }
    }
  }

  @Test
  void everyRenderedLineFitsTheColumnLimitAndIsAscii() {
    for (Map.Entry<String, String> file : ProofFiles.render().entrySet()) {
      file.getValue().lines().forEach(line -> {
        assertTrue(line.length() <= 100, file.getKey() + " has a line past 100 columns: " + line);
        assertTrue(line.chars().allMatch(c -> c < 128), file.getKey() + " has non-ASCII: " + line);
      });
    }
  }
}
