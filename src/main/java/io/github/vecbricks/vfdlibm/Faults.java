/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

/**
 * The planted-fault switch: proof that a check can fail. PLAN.md section 4 asks every check to
 * catch a known fault planted in the code it tests (a dropped correction term, a constant off by
 * one ulp, a branch threshold moved).
 *
 * <p>A port asks {@link #is} at the point it would go wrong, for example {@code
 * Faults.is("exp.dropCorrection")}; fault names are {@code <function>.<what>}. The planted fault
 * is read once, when this class loads, from the system property {@code vfdlibm.fault}, and is null
 * when the property is unset. It is a {@code static final}, so the JIT treats it as a constant:
 * with no fault planted every {@code is} call folds to {@code false} and the faulty branch is
 * compiled away.
 *
 * <p>Because the value is fixed for the life of the JVM, a planted fault is tested in a JVM of its
 * own: {@code pom.xml} runs the normal suite once with no fault, then one forked surefire execution
 * per fault with {@code -Dvfdlibm.fault=<name>}, which runs only the tests tagged {@code fault}.
 */
final class Faults {

  /** The fault planted in this JVM, or null. */
  private static final String PLANTED = System.getProperty("vfdlibm.fault");

  private Faults() {}

  /** Whether the fault {@code name} is planted in this JVM. Ports call this. */
  static boolean is(String name) {
    return PLANTED != null && PLANTED.equals(name);
  }

  /** The fault planted in this JVM, or null when none is. */
  static String planted() {
    return PLANTED;
  }
}
