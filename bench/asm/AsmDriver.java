/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
import io.github.vecbricks.vfdlibm.VFdlibm;
import java.util.SplittableRandom;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Warms exp's and log's loops on common data until C2 compiles them, so that
 * -XX:CompileCommand=print shows the code a benchmark row runs: at 512 bits with
 * {@code -Dasm.bits=512}, otherwise at 256. The species is a static final, a constant to C2.
 * See README.md.
 */
public class AsmDriver {
  static final VectorSpecies<Double> S =
      Integer.getInteger("asm.bits", 256) == 512 ? DoubleVector.SPECIES_512
          : DoubleVector.SPECIES_256;
  static final int N = 1024;

  static void loopExp(double[] in, double[] out) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.exp(DoubleVector.fromArray(S, in, i)).intoArray(out, i);
    }
  }

  static void loopLog(double[] in, double[] out) {
    for (int i = 0; i < N; i += S.length()) {
      VFdlibm.log(DoubleVector.fromArray(S, in, i)).intoArray(out, i);
    }
  }

  public static void main(String[] args) {
    double[] e = new double[N];
    double[] l = new double[N];
    double[] out = new double[N];
    SplittableRandom r = new SplittableRandom(1);
    for (int i = 0; i < N; i++) {
      e[i] = r.nextDouble(-20, 20);
      l[i] = Math.scalb(1 + r.nextDouble(), r.nextInt(-30, 31));
    }
    double sink = 0;
    for (int it = 0; it < 30000; it++) {
      loopExp(e, out);
      sink += out[it & (N - 1)];
      loopLog(l, out);
      sink += out[it & (N - 1)];
    }
    System.out.println(sink);
  }
}
