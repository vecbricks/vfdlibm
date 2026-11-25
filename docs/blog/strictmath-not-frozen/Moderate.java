import java.util.Random;
/**
 * pow's |y| > 2^31 branch with ordinary results: |x - 1| from 2^-34 to 2^-24 and y from 2^31 to
 * 2^33, so |y ln x| is about 0.1 to 500. Prints y ln x, x, y and the result, in bits, per input.
 */
public class Moderate {
  public static void main(String[] a) {
    Random r = new Random(11);
    for (int i = 0; i < 4_000_000; i++) {
      double d = Math.scalb(1 + r.nextDouble(), -34 + r.nextInt(10));
      double x = r.nextBoolean() ? 1 + d : 1 - d;
      double y = Math.scalb(1 + r.nextDouble(), 31 + r.nextInt(2)) * (r.nextBoolean() ? 1 : -1);
      System.out.printf("%.3f %016x %016x %016x%n", y * Math.log(x), Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(StrictMath.pow(x, y)));
    }
  }
}
