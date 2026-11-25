/*
 * Reads "x y strictmath" lines (raw bits in hex, as HypotPairs.java and HypotPairsEdges.java print
 * them) and compares StrictMath's result with fdlibm 5.3's C hypot (Sun's e_hypot.c) called as
 * hypot(x, y) and with the larger magnitude first. Prints each pair where the latter differs, then
 * the counts.
 *
 *   gcc -O0 -ffp-contract=off -fno-builtin -fno-strict-aliasing -D__LITTLE_ENDIAN \
 *       -I ../../../reference/fdlibm-5.3 -o hypot-order-check \
 *       hypot-order-check.c ../../../reference/fdlibm-5.3/e_hypot.c -lm
 *   java HypotPairs.java 1000000 | ./hypot-order-check
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

double __ieee754_hypot(double, double);

static double d(uint64_t u) { double x; memcpy(&x, &u, 8); return x; }
static uint64_t b(double x) { uint64_t u; memcpy(&u, &x, 8); return u; }

int main(void) {
  unsigned long long X, Y, Z;
  long n = 0, nan2 = 0, as_called = 0, larger_first = 0, order_dependent = 0, bad = 0;
  while (scanf("%llx %llx %llx", &X, &Y, &Z) == 3) {
    double x = d(X), y = d(Y);
    uint64_t r1 = b(__ieee754_hypot(x, y)), r2 = b(__ieee754_hypot(y, x));
    uint64_t rm = fabs(x) >= fabs(y) ? r1 : r2; /* the larger magnitude first */
    n++;
    if (isnan(x) && isnan(y)) nan2++;
    if (r1 == Z) as_called++;
    if (rm == Z) larger_first++;
    if (r1 != r2) order_dependent++;
    if (rm != Z) {
      bad++;
      printf("differs: x=%016llx y=%016llx StrictMath=%016llx C(x,y)=%016llx C(y,x)=%016llx%s\n",
             X, Y, Z, (unsigned long long)r1, (unsigned long long)r2,
             isnan(x) && isnan(y) ? " (two NaNs)" : "");
    }
  }
  printf("pairs %ld (of which two NaNs %ld); StrictMath equals C(x, y) on %ld, C(larger, smaller) on %ld,"
         " differs from C(larger, smaller) on %ld; C depends on the order on %ld\n",
         n, nan2, as_called, larger_first, bad, order_dependent);
  return 0;
}
