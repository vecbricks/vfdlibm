/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.vfdlibm;

import java.lang.reflect.Field;

/** Diagnostic only: an object's address now, through sun.misc.Unsafe (the narrow oop's shift given). */
final class Addr {
  private static final sun.misc.Unsafe U;
  static {
    try {
      Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      U = (sun.misc.Unsafe) f.get(null);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private Addr() {}

  @SuppressWarnings("removal")
  static long of(Object o) {
    Object[] box = {o};
    long base = U.arrayBaseOffset(Object[].class);
    int scale = U.arrayIndexScale(Object[].class);
    long raw = scale == 4 ? (U.getInt(box, base) & 0xffffffffL) : U.getLong(box, base);
    // The narrow oop's shift: 0 for a heap under 4 GB (unscaled mode), else 3 (diag.oopShift).
    return scale == 4 ? raw << Integer.getInteger("diag.oopShift", 0) : raw;
  }
}
