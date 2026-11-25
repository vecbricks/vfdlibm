/**
 * Per-second compounding, (1 + r/n)^(n*t) with n the seconds in a year: r in basis points
 * (0.01% to 20%), t whole years 70 to 300, so n*t is past 2^31 and 1 + r/n within 2^-20 of 1:
 * fdlibm's branch for |y| > 2^31 (e_pow.c line 191), where the scans found JDK 27's changes. Prints each result's bits, to compare two JDKs line by line.
 */
public class Compound {
  public static void main(String[] a) {
    double n = 365.0 * 24 * 3600;
    for (int bp = 1; bp <= 2000; bp++)
      for (int t = 70; t <= 300; t++) {
        double r = bp / 10000.0, x = 1 + r / n, y = n * t;
        System.out.printf("%.4f %d %016x %s%n", r, t, Double.doubleToRawLongBits(StrictMath.pow(x, y)), Double.toString(StrictMath.pow(x, y)));
      }
  }
}
