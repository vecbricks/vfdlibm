/* Prints, for each function and id of ../aggregate-check.sql, the arguments as that script derives
 * them and glibc libm's result, as CSV (f,id,x,x2,y), every double as %.17g (reads back exact).
 * Build: gcc -O1 -ffp-contract=off -fno-builtin -o aggregate-glibc-values aggregate-glibc-values.c -lm
 * Run:   ./aggregate-glibc-values > glibc-values.csv   (9,000,000 rows, about 465 MB; not committed)
 * The function to call is chosen at run time through a pointer, and the inputs come from a loop
 * counter, so the compiler cannot fold a call or substitute its own evaluation. */
#include <math.h>
#include <stdio.h>
typedef double (*f1)(double);
static double cpow(double a, double b) { return pow(a, b); }
int main(void) {
  const char *name[] = {"exp", "ln", "log10", "expm1", "pow", "sin", "tanh", "cbrt", "sqrt"};
  volatile f1 fn[] = {exp, log, log10, expm1, 0, sin, tanh, cbrt, sqrt};
  for (int k = 0; k < 9; k++)
    for (long id = 0; id < 1000000; id++) {
      double u = (double) id / 1000000.0, x, x2 = 0.0, y;
      switch (k) {
        case 0: x = 4.6 * u; break;
        case 1: x = 3.0 + 1000.0 * u; break;
        case 2: x = 2.0 + 999998.0 * u; break;
        case 3: x = 0.1 + 4.5 * u; break;
        case 4: x = 1.5 + u; x2 = 1.0 + (double) (id % 1000) / 1000.0 * 3.0; break;
        case 5: x = 0.2 + 2.7 * u; break;
        case 6: x = 0.2 + 4.8 * u; break;
        case 7: x = 1.0 + 999999.0 * u; break;
        default: x = 1.0 + 9999.0 * u; break;
      }
      y = k == 4 ? cpow(x, x2) : fn[k](x);
      printf("%s,%ld,%.17g,%.17g,%.17g\n", name[k], id, x, x2, y);
    }
  return 0;
}
