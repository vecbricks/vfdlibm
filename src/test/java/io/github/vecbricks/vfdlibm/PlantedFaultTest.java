/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.AssertionFailedError;

/**
 * The fault runs: for the fault planted in this JVM ({@link Faults}), the checks that must catch
 * it, and the test that requires them to fail.
 *
 * <p>Each test class that owns a fault lists it in {@link #CATCHERS} with the check that must
 * catch it. {@code pom.xml} runs one forked surefire execution per fault with {@code
 * -Dvfdlibm.fault=<name>} and only the tests tagged {@code fault}, which here is {@link
 * #plantedFaultIsCaught}: it fails unless the check fails. In the normal run, which excludes the
 * tag, {@link #noFaultInTheNormalRun} proves no fault is planted, so the same checks passing there
 * shows that a fault run's failure comes from the fault, and {@link #everyFaultHasAnExecution}
 * proves each listed fault has its execution.
 */
class PlantedFaultTest {

  /** Every fault, with the check that must fail while it is planted. */
  static final Map<String, Executable> CATCHERS = new LinkedHashMap<>();

  static {
    CATCHERS.putAll(HarnessSelfTest.faultCatchers());
    CATCHERS.putAll(ExpTest.faultCatchers());
  }

  /**
   * Runs {@code check}, which must fail with an assertion error because a fault is planted, and
   * returns that error so a test can check what it reported.
   */
  static AssertionFailedError assertCaught(String name, Executable check) {
    return assertThrows(
        AssertionFailedError.class, check, "the check did not catch planted fault " + name);
  }

  @Test
  @Tag("fault")
  void plantedFaultIsCaught() throws Throwable {
    String name = Faults.planted();
    if (name == null) {
      fail("a fault run with no fault planted: set -Dvfdlibm.fault");
    }
    Executable catcher = CATCHERS.get(name);
    if (catcher == null) {
      fail("no check catches planted fault " + name + "; known faults: " + CATCHERS.keySet());
    }
    catcher.execute();
  }

  @Test
  void noFaultInTheNormalRun() {
    assertNull(Faults.planted(), "the normal run plants no fault");
  }

  @Test
  void everyFaultHasAnExecution() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    for (String name : CATCHERS.keySet()) {
      if (!pom.contains("-Dvfdlibm.fault=" + name + "</argLine>")) {
        fail("pom.xml has no surefire execution planting " + name);
      }
    }
  }

  @Test
  void assertCaughtFailsWhenTheCheckPasses() {
    AssertionFailedError e = assertThrows(
        AssertionFailedError.class, () -> assertCaught("selftest.notRead", () -> {}));
    if (!e.getMessage().startsWith("the check did not catch planted fault selftest.notRead")) {
      fail(e.getMessage());
    }
  }
}
