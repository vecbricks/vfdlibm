import java.util.SplittableRandom;

/**
 * Prints N pairs (seed 11) whose two magnitudes share their high 32 bits (the signs are drawn
 * apart), with StrictMath.hypot's result: "x y strictmath" as raw bits in hex, for
 * hypot-order-check.c. A third of the high words are random (NaNs included), a third lie near 1.0,
 * a third are random finite.
 *
 *   java HypotPairs.java 1000000
 */
public class HypotPairs {
  public static void main(String[] a) {
    var r = new SplittableRandom(11);
    int n = Integer.parseInt(a[0]);
    for (int i = 0; i < n; i++) {
      int mode = i % 3;
      long hi = mode == 0 ? (r.nextLong() & 0x7fffffff00000000L)
          : mode == 1 ? ((0x3ff00000L + r.nextInt(-0x100000, 0x100000)) << 32)
          : ((long) r.nextInt(0x00100000, 0x7fefffff)) << 32;
      long lx = r.nextLong() & 0xffffffffL, ly = r.nextLong() & 0xffffffffL;
      long sx = r.nextBoolean() ? Long.MIN_VALUE : 0, sy = r.nextBoolean() ? Long.MIN_VALUE : 0;
      double x = Double.longBitsToDouble(sx | hi | lx), y = Double.longBitsToDouble(sy | hi | ly);
      System.out.printf("%016x %016x %016x%n", Double.doubleToRawLongBits(x),
          Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(StrictMath.hypot(x, y)));
    }
  }
}
