/**
 * Prints StrictMath.exp(1), Math.exp(1) and Math.E with their raw 64 bits
 * (Double.doubleToRawLongBits), one "name value bits" line each, for ../figures.py.
 *
 *   java ExpOne.java > exp1-bits.txt
 */
public class ExpOne {
  public static void main(String[] a) {
    print("StrictMath.exp(1)", StrictMath.exp(1));
    print("Math.exp(1)", Math.exp(1));
    print("Math.E", Math.E);
  }

  static void print(String name, double v) {
    System.out.printf("%s %s %016x%n", name, Double.toString(v), Double.doubleToRawLongBits(v));
  }
}
