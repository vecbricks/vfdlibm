/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.function.Executable;
import org.opentest4j.AssertionFailedError;

/**
 * The planted-fault switch: proof that a check can fail. PLAN.md section 4 asks every check to
 * catch a known fault planted in the code it tests (a dropped correction term, a constant off by
 * one ulp, a branch threshold moved).
 *
 * <p>A test plants a fault by name around a check with {@link #assertCaught}: the fault is active
 * while the check runs, and the check must then fail. The code under test asks {@link #is} at the
 * point it would go wrong. Fault names are {@code <function>.<what>}, for example {@code
 * exp.dropCorrection}.
 *
 * <p>The switch is a test-scoped static field rather than a system property, so one JVM can plant
 * and clear faults test by test. Here only the harness's own self-test reads it. When the first
 * port arrives, its pull request moves the field to a package-private holder in the main sources,
 * where the port can read it; this class keeps planting it. Reading a plain static field costs one
 * load per call, and the library is Varka's reference rather than a hot path (PLAN.md section 2).
 */
final class PlantedFault {

  /** The fault planted now, or null. Tests run one at a time; volatile keeps it safe if not. */
  private static volatile String planted;

  private PlantedFault() {}

  /** Whether the fault {@code name} is planted. Code under test calls this. */
  static boolean is(String name) {
    return name.equals(planted);
  }

  /**
   * Runs {@code check} with the fault {@code name} planted, and fails unless the check fails with
   * an assertion error. Returns that error, so a test can check what it reported.
   */
  static AssertionFailedError assertCaught(String name, Executable check) {
    if (planted != null) {
      throw new IllegalStateException("fault " + planted + " is already planted");
    }
    planted = name;
    try {
      return assertThrows(
          AssertionFailedError.class, check, "the check did not catch planted fault " + name);
    } finally {
      planted = null;
    }
  }
}
