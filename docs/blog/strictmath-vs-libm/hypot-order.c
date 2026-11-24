/*
 * fdlibm 5.3's hypot, from Sun's C (../../../reference/fdlibm-5.3/e_hypot.c), on the input the
 * nightly fuzzer found (issue #173), in both argument orders. Prints the raw bits of each result.
 *
 *   gcc -O0 -ffp-contract=off -fno-builtin -fno-strict-aliasing -D__LITTLE_ENDIAN \
 *       -I ../../../reference/fdlibm-5.3 -o hypot-order \
 *       hypot-order.c ../../../reference/fdlibm-5.3/e_hypot.c -lm && ./hypot-order
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

double __ieee754_hypot(double, double);

static double d(uint64_t u) { double x; memcpy(&x, &u, 8); return x; }
static uint64_t b(double x) { uint64_t u; memcpy(&u, &x, 8); return u; }

int main(void) {
  double x = d(0x5f201b45bbc5eac3ULL), y = d(0x5f201b45fffffffeULL);
  printf("fdlibm 5.3 C: hypot(x, y) = %016llx, hypot(y, x) = %016llx\n",
         (unsigned long long)b(__ieee754_hypot(x, y)), (unsigned long long)b(__ieee754_hypot(y, x)));
  return 0;
}
