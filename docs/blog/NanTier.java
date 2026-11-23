// Which NaN does StrictMath.atan2 return for two NaN arguments with different bits? Run it twice:
//   java -Xint NanTier.java     (the interpreter only)
//   java NanTier.java           (the default JIT tiers)
// The counts vary from run to run and from machine to machine.
public class NanTier {
  public static void main(String[] a) {
    double x = Double.longBitsToDouble(0x7ff8000000000001L), y = Double.longBitsToDouble(0x7ff8000000000002L);
    long first = 0, second = 0, other = 0;
    for (int i = 0; i < 2_000_000; i++) {
      long r = Double.doubleToRawLongBits(StrictMath.atan2(x, y));
      if (r == 0x7ff8000000000001L) first++; else if (r == 0x7ff8000000000002L) second++; else other++;
    }
    System.out.println("atan2(NaN#1, NaN#2): first=" + first + " second=" + second + " other=" + other);
  }
}
