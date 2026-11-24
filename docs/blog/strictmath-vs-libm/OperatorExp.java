import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * How often the Vector API's VectorOperators.EXP returns other bits than StrictMath.exp, on
 * Gen.java's 200,000 inputs: x in [-700, 700) from java.util.Random seeded 7, drawn by Gen's
 * formula. Each pass computes DoubleVector.lanewise(VectorOperators.EXP) over the inputs at one
 * species and compares every lane by Double.doubleToRawLongBits with StrictMath.exp(x) and with
 * Math.exp(x) (computed in the same pass, so that a change of tier shows in either column), and
 * prints one line: the lanes that differ from StrictMath, the largest distance from StrictMath in
 * units in the last place (the difference of the raw bits; every result here is a positive normal
 * double), the lanes that differ from Math.exp, and the lanes where Math.exp differs from
 * StrictMath.
 *
 *   java --add-modules jdk.incubator.vector -Dbits=256 OperatorExp.java [passes] [dump file]
 *
 * -Dbits is 128, 256, 512 or 0 (the default) for DoubleVector.SPECIES_PREFERRED; one species per
 * JVM, since a JVM that runs two species of one lane type can compile both worse. passes is 20
 * by default. With a dump file, the last pass writes each lane that differs from StrictMath as
 * "x strictmath operator" (Double.toString, which reads back as the same double), for
 * operator_rounding.py.
 */
public class OperatorExp {
  static final int N = 200_000;
  static final VectorSpecies<Double> S = species(Integer.getInteger("bits", 0));

  static VectorSpecies<Double> species(int bits) {
    return switch (bits) {
      case 0 -> DoubleVector.SPECIES_PREFERRED;
      case 128 -> DoubleVector.SPECIES_128;
      case 256 -> DoubleVector.SPECIES_256;
      case 512 -> DoubleVector.SPECIES_512;
      default -> throw new IllegalArgumentException("bits=" + bits + ": 0, 128, 256 or 512");
    };
  }

  public static void main(String[] a) throws IOException {
    int passes = a.length > 0 ? Integer.parseInt(a[0]) : 20;
    String dump = a.length > 1 ? a[1] : null;
    Random r = new Random(7);
    double[] xs = new double[N];
    double[] strict = new double[N];
    for (int i = 0; i < N; i++) {
      xs[i] = (r.nextDouble() - 0.5) * 1400;
      strict[i] = StrictMath.exp(xs[i]);
    }
    if (N % S.length() != 0) {
      throw new IllegalStateException(N + " inputs are not a multiple of " + S.length());
    }
    double[] op = new double[N];
    double[] math = new double[N];
    System.out.printf("species %d bits (%d lanes), %d inputs%n", S.vectorBitSize(), S.length(), N);
    for (int pass = 1; pass <= passes; pass++) {
      for (int i = 0; i < N; i += S.length()) {
        DoubleVector.fromArray(S, xs, i).lanewise(VectorOperators.EXP).intoArray(op, i);
      }
      for (int i = 0; i < N; i++) {
        math[i] = Math.exp(xs[i]);
      }
      int fromStrict = 0, fromMath = 0, mathFromStrict = 0;
      long maxUlp = 0;
      for (int i = 0; i < N; i++) {
        long s = Double.doubleToRawLongBits(strict[i]);
        long o = Double.doubleToRawLongBits(op[i]);
        long m = Double.doubleToRawLongBits(math[i]);
        if (o != s) {
          fromStrict++;
          maxUlp = Math.max(maxUlp, Math.abs(o - s));
        }
        fromMath += o != m ? 1 : 0;
        mathFromStrict += m != s ? 1 : 0;
      }
      System.out.printf(
          "pass %2d: EXP differs from StrictMath.exp on %d (max %d ulp), from Math.exp on %d;"
              + " Math.exp differs from StrictMath.exp on %d%n",
          pass, fromStrict, maxUlp, fromMath, mathFromStrict);
    }
    if (dump != null) {
      try (PrintWriter w = new PrintWriter(new FileWriter(dump))) {
        for (int i = 0; i < N; i++) {
          if (Double.doubleToRawLongBits(op[i]) != Double.doubleToRawLongBits(strict[i])) {
            w.println(xs[i] + " " + strict[i] + " " + op[i]);
          }
        }
      }
    }
  }
}
