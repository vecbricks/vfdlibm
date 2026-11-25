import java.util.SplittableRandom;

/**
 * Like HypotPairs.java (seed 23), with the shared high words drawn at e_hypot.c's thresholds and
 * beside them, among subnormal high words and at random, and the low words equal, zero, all ones,
 * one apart or random: "x y strictmath" as raw bits in hex, for hypot-order-check.c.
 *
 *   java HypotPairsEdges.java 300000
 */
public class HypotPairsEdges {
  public static void main(String[] a) {
    var r = new SplittableRandom(23);
    int n = Integer.parseInt(a[0]);
    long[] th = {0x00000000L, 0x00000001L, 0x000fffffL, 0x00100000L, 0x20afffffL, 0x20b00000L,
        0x20b00001L, 0x5f2fffffL, 0x5f300000L, 0x5f300001L, 0x7fefffffL, 0x3ff00000L, 0x3fefffffL};
    for (int i = 0; i < n; i++) {
      int mode = i % 5;
      long hi;
      if (mode == 0) hi = r.nextLong(0, 0x00100000L);
      else if (mode == 1) hi = th[r.nextInt(th.length)];
      else if (mode == 2) hi = th[r.nextInt(th.length)] + r.nextInt(-3, 4);
      else hi = r.nextLong(0, 0x7ff00000L);
      hi = Math.max(0, Math.min(0x7fefffffL, hi));
      long lx = r.nextLong() & 0xffffffffL, ly;
      switch (r.nextInt(6)) {
        case 0: ly = lx; break;
        case 1: ly = 0; break;
        case 2: ly = 0xffffffffL; break;
        case 3: ly = (lx + 1) & 0xffffffffL; break;
        case 4: ly = (lx - 1) & 0xffffffffL; break;
        default: ly = r.nextLong() & 0xffffffffL;
      }
      if (r.nextInt(8) == 0) lx = r.nextBoolean() ? 0 : 0xffffffffL;
      long sx = r.nextBoolean() ? Long.MIN_VALUE : 0, sy = r.nextBoolean() ? Long.MIN_VALUE : 0;
      double x = Double.longBitsToDouble(sx | (hi << 32) | lx), y = Double.longBitsToDouble(sy | (hi << 32) | ly);
      System.out.printf("%016x %016x %016x%n", Double.doubleToRawLongBits(x),
          Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(StrictMath.hypot(x, y)));
    }
  }
}
