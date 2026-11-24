import java.util.Random;

/**
 * Prints 200,000 random inputs x in [-700, 700) (seed 7), one per line, each with StrictMath's
 * exp(x), log(|x|), expm1(x / 100) and pow(2.0, x / 700), as Double.toString writes them (the
 * shortest decimal that reads back as the same double). compare.py reads this output.
 */
public class Gen {
  public static void main(String[] a) {
    Random r = new Random(7);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200000; i++) {
      double x = (r.nextDouble() - 0.5) * 1400;
      sb.append(Double.toString(x)).append(' ')
          .append(Double.toString(StrictMath.exp(x))).append(' ')
          .append(Double.toString(StrictMath.log(Math.abs(x)))).append(' ')
          .append(Double.toString(StrictMath.expm1(x / 100))).append(' ')
          .append(Double.toString(StrictMath.pow(2.0, x / 700))).append('\n');
    }
    System.out.print(sb);
  }
}
