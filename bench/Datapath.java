/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * How wide the vector execution units are, as opposed to how wide the instruction set and
 * {@code MaxVectorSize} say they are. Adapted from vecbricks/varka's
 * {@code dev/varka_canary/Datapath.java}, the probe of the same role, so its readings compare
 * with the rows of Varka's hardware census (HARDWARE.md here). {@code bench/datapath.sh} runs it
 * as a single-file source program, needing no build, at three widths:
 *
 * <pre>
 * java --add-modules jdk.incubator.vector -XX:MaxVectorSize=32 bench/Datapath.java
 * </pre>
 *
 * <p>The loop is bound by vector ALU throughput: eight independent {@code IntVector}
 * accumulators in registers, no loads or stores. It prints <b>lanes per nanosecond</b>, vector
 * adds times the species' lane count, so a datapath that is really twice as wide reads twice the
 * rate, and one that issues a wide operation as two halves reads the same rate. The 256:128
 * ratio is the probe's own control (about 2 on any machine with 256-bit vectors); the 512:256
 * ratio is the answer: about 2 for a full-width 512-bit datapath, about 1 for a double-pumped
 * one, about 1.3 on Intel's server cores (HARDWARE.md).
 */
public final class Datapath {

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
  /** Timed windows; the best is reported. Fewer than Varka's five, to keep the probe short. */
  private static final int WINDOWS = 3;
  private static final long WINDOW_NANOS = 1_000_000_000L;
  /** Independent accumulators, to keep the units fed rather than waiting on add latency. */
  private static final int CHAINS = 8;
  /** Vector adds per call, spread over the chains. */
  private static final int OPS = 1 << 14;

  public static void main(String[] args) {
    // Warm up until C2 has compiled the loop, then measure.
    long sink = 0;
    for (int i = 0; i < 200; i++) {
      sink += laneOps(i);
    }
    double best = 0;
    for (int w = 0; w < WINDOWS; w++) {
      long calls = 0;
      long start = System.nanoTime();
      long now;
      do {
        sink += laneOps((int) calls);
        calls++;
        now = System.nanoTime();
      } while (now - start < WINDOW_NANOS);
      best = Math.max(best, calls * (double) OPS * SPECIES.length() / (now - start));
    }
    System.out.printf("preferred_bits=%d%n", SPECIES.vectorBitSize());
    System.out.printf("lanes=%d%n", SPECIES.length());
    System.out.printf("lane_ops_per_ns=%.1f%n", best);
    // Printed so that C2 cannot drop the loop as dead code.
    System.out.printf("sink=%d%n", sink);
  }

  /**
   * {@code OPS} vector adds over {@link #CHAINS} independent accumulators. At 512 bits that is
   * eight {@code zmm} registers and a constant, well inside the 32 the ISA has, so nothing spills.
   */
  private static long laneOps(int seed) {
    IntVector step = IntVector.broadcast(SPECIES, seed | 1);
    IntVector a0 = IntVector.broadcast(SPECIES, seed);
    IntVector a1 = a0, a2 = a0, a3 = a0, a4 = a0, a5 = a0, a6 = a0, a7 = a0;
    for (int i = 0; i < OPS / CHAINS; i++) {
      a0 = a0.add(step);
      a1 = a1.add(step);
      a2 = a2.add(step);
      a3 = a3.add(step);
      a4 = a4.add(step);
      a5 = a5.add(step);
      a6 = a6.add(step);
      a7 = a7.add(step);
    }
    return a0.add(a1).add(a2).add(a3).add(a4).add(a5).add(a6).add(a7)
        .reduceLanes(VectorOperators.ADD);
  }

  private Datapath() {
  }
}
