// std::cbrt on ../aggregate-check.sql's cbrt inputs, computed at run time (the inputs come from the
// loop and a volatile scale, so the compiler cannot fold a call to a constant). Prints each result
// as %.17g; aggregate-std-cbrt-sum.py sums them as the query does.
//
//   g++ -O2 -ffp-contract=off aggregate-std-cbrt.cpp -o std-cbrt && ./std-cbrt | python3 aggregate-std-cbrt-sum.py
#include <cmath>
#include <cstdio>
#include <cstdlib>
int main(int argc, char** argv) {
  volatile double scale = argc > 1 ? std::atof(argv[1]) : 1000000.0;  // keeps the compiler from folding
  for (long i = 0; i < 1000000; i++) {
    double u = (double) i / scale;
    double x = 1.0 + 999999.0 * u;
    std::printf("%.17g\n", std::cbrt(x));
  }
}
