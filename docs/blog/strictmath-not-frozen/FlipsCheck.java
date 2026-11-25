import java.nio.file.*;
/** Checks flips.c's fdlibm results against StrictMath: reads its dump, prints the mismatches. */
public class FlipsCheck {
  public static void main(String[] a) throws Exception {
    long n = 0, bad = 0;
    for (String l : Files.readAllLines(Path.of(a[0]))) {
      String[] f = l.split(" ");
      double[] v = new double[5];
      for (int k = 0; k < 5; k++) v[k] = Double.longBitsToDouble(Long.parseUnsignedLong(f[k], 16));
      if (Double.doubleToRawLongBits(StrictMath.pow(v[0], v[1])) != Double.doubleToRawLongBits(v[3])) bad++;
      if (Double.doubleToRawLongBits(StrictMath.exp(v[2])) != Double.doubleToRawLongBits(v[4])) bad++;
      n++;
    }
    System.out.println(System.getProperty("java.runtime.version") + ": " + n + " rows, " + bad + " fdlibm results differ from StrictMath");
  }
}
