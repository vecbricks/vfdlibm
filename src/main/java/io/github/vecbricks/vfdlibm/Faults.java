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
 * <p>A port reads each of its faults once, through {@link #is}, into a {@code static final}
 * constant of its own class, for example {@code static final boolean DROPPING_CORRECTION =
 * Faults.is("exp.dropCorrection")}, and tests that constant at the point it would go wrong; fault
 * names are {@code <function>.<what>}. The vector body then holds no call, which C2 could refuse
 * to inline and so box the vectors. The planted fault is read once, when this class loads, from
 * the system property {@code vfdlibm.fault}, and is null when the property is unset. The port's
 * constant is a {@code static final} too, so the JIT treats it as a constant: with no fault planted
 * it is {@code false} and the faulty branch is compiled away.
 *
 * <p>Because the value is fixed for the life of the JVM, a planted fault is tested in a JVM of its
 * own: {@code pom.xml} runs the normal suite once with no fault, then one forked surefire execution
 * per fault with {@code -Dvfdlibm.fault=<name>}, which runs only the tests tagged {@code fault}.
 *
 * <p>This class is in the main sources, so the shipped jar reads the property too: a JVM started
 * with {@code -Dvfdlibm.fault=exp.dropCorrection}, for example through a stray {@code
 * JAVA_TOOL_OPTIONS}, gets wrong results from {@link VFdlibm#exp}. The property is meant for this
 * project's tests only. So that such a JVM does not go unnoticed, loading this class with a fault
 * planted prints one warning to {@code System.err}; it runs once, in this class's initialiser, and
 * adds nothing to any vector body.
 */
final class Faults {

  /** The fault planted in this JVM, or null. */
  private static final String PLANTED = System.getProperty("vfdlibm.fault");

  static {
    if (PLANTED != null) {
      System.err.println("WARNING: vfdlibm: fault " + PLANTED + " is planted by -Dvfdlibm.fault;"
          + " its functions may return wrong results. The property is for vfdlibm's own tests.");
    }
  }

  private Faults() {}

  /** Whether the fault {@code name} is planted in this JVM. Ports call this once per fault. */
  static boolean is(String name) {
    return PLANTED != null && PLANTED.equals(name);
  }

  /** The fault planted in this JVM, or null when none is. */
  static String planted() {
    return PLANTED;
  }
}
