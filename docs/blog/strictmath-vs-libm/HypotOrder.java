/**
 * StrictMath.hypot and Math.hypot on the input the nightly fuzzer found (issue #173), in both
 * argument orders, with the JDK's version. Prints the raw bits of each result.
 *
 *   java HypotOrder.java
 */
public class HypotOrder {
  public static void main(String[] a) {
    double x = Double.longBitsToDouble(0x5f201b45bbc5eac3L);
    double y = Double.longBitsToDouble(0x5f201b45fffffffeL);
    System.out.printf("JDK %s: StrictMath.hypot(x, y) = %016x, StrictMath.hypot(y, x) = %016x, "
            + "Math.hypot(x, y) = %016x, Math.hypot(y, x) = %016x%n",
        System.getProperty("java.version"),
        Double.doubleToRawLongBits(StrictMath.hypot(x, y)), Double.doubleToRawLongBits(StrictMath.hypot(y, x)),
        Double.doubleToRawLongBits(Math.hypot(x, y)), Double.doubleToRawLongBits(Math.hypot(y, x)));
  }
}
