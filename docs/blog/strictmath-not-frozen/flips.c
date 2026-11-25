/*
 * How often two math libraries, each within an ulp, put a money amount on different cents.
 *
 * Per row, with seeded random inputs: amount (whole cents, 0.01 to 1,000,000.00), rate (a whole
 * number of basis points, 0.01% to 20.00%) and years (1 to 40), it computes
 *   discrete:   amount * pow(1 + rate, years)
 *   continuous: amount * exp(rate * years)
 * with Sun's fdlibm 5.3 (__ieee754_pow, __ieee754_exp, which is what java.lang.StrictMath
 * returns, as FlipsCheck.java checks) and with the C library's pow and exp (glibc's on Linux).
 * It counts the rows where the two libraries' doubles differ, the rows where the amounts (amount
 * times that double) differ, and the rows where the amounts' cents differ. Every step but pow and exp is
 * the same IEEE operation on both sides, so a difference comes from the library alone. Cents are
 * counted two ways: from the double's exact value, and from its shortest decimal, which is what
 * Spark's round() rounds (round(24471612.324999999D, 2) is 24471612.33 on Spark 4.3.0).
 *
 *   F=../../../reference/fdlibm-5.3   # x86-64 is not in fdlibm.h's little-endian list
 *   cc -O2 -D__LITTLE_ENDIAN -fno-strict-aliasing -ffp-contract=off -fopenmp -I$F flips.c \
 *       $F/e_pow.c $F/e_exp.c -lm -o flips
 *   ./flips <rows> [dump-rows dump-file] 2> rows.txt   (each row whose cents differ, on stderr)
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <gnu/libc-version.h>

double __ieee754_pow(double, double);
double __ieee754_exp(double);

static inline uint64_t mix(uint64_t z) {  /* splitmix64 of the row index: rows are independent */
  z += 0x9e3779b97f4a7c15ULL;
  z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
  z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
  return z ^ (z >> 31);
}

/* The cent an amount rounds to, half up, from the double's exact value (113-bit arithmetic is
   exact here: 100 * v needs at most 60 bits, and adding 0.5 to it fits). */
static inline __int128 cents(double v) {
  __float128 c = (__float128) v * 100 + (__float128) 0.5;
  __int128 k = (__int128) c;
  if ((__float128) k > c) k--;
  return k;
}


/* The cent an amount rounds to, half up, from its shortest decimal: the fewest significant digits
   that read back as the same double, as Java's Double.toString prints it and as Spark's round()
   rounds it. Only called near a cent boundary, since snprintf is slow. */
static __int128 cents_shortest(double v) {
  char buf[64];
  int p;
  for (p = 1; p <= 17; p++) {
    snprintf(buf, sizeof buf, "%.*e", p - 1, v);
    if (strtod(buf, NULL) == v) break;
  }
  __int128 m = 0;
  char *c = buf;
  for (; *c && *c != 'e'; c++) if (*c >= '0' && *c <= '9') m = m * 10 + (*c - '0');
  int e10 = atoi(c + 1) - (p - 1);           /* v's decimal is m * 10^e10 */
  int s = -(e10 + 2);                        /* cents = m * 10^(e10 + 2), half up */
  __int128 ten = 1;
  if (s <= 0) { for (int k = 0; k < -s; k++) ten *= 10; return m * ten; }
  for (int k = 0; k < s; k++) ten *= 10;
  return (m + ten / 2) / ten;
}

/* Whether a cent boundary lies within 1e-4 of a cent of v: an ulp of these amounts (at most
   2^-21, for amounts below 2^32; the largest here is about 3.0e9) is 4.8e-5 of a cent or less,
   so a pair whose shortest decimals round to different cents always passes. */
static inline int near_boundary(double v) {
  __float128 c = (__float128) v * 100 + (__float128) 0.5;
  __float128 f = c - (__float128) (__int128) c;
  return f < (__float128) 1e-4 || f > 1 - (__float128) 1e-4;
}

typedef struct { double amount, rate, years; } row;

static inline row make(uint64_t i) {
  uint64_t a = mix(3 * i), b = mix(3 * i + 1), c = mix(3 * i + 2);
  row r;
  r.amount = (double) (1 + a % 100000000ULL) / 100;   /* 0.01 .. 1,000,000.00 */
  r.rate = (double) (1 + b % 2000ULL) / 10000;         /* 0.0001 .. 0.2000 */
  r.years = (double) (1 + c % 40ULL);                  /* 1 .. 40 */
  return r;
}

int main(int argc, char **argv) {
  long long n = atoll(argv[1]);
  long long dpow = 0, fpow = 0, dexp = 0, fexp = 0, spow = 0, sexp = 0, apow = 0, aexp = 0;
  #pragma omp parallel for reduction(+:dpow,fpow,dexp,fexp,spow,sexp,apow,aexp) schedule(static)
  for (long long i = 0; i < n; i++) {
    row r = make((uint64_t) i);
    double base = 1 + r.rate, t = r.rate * r.years;
    double p1 = __ieee754_pow(base, r.years), p2 = pow(base, r.years);
    if (p1 != p2) {
      dpow++;
      if (r.amount * p1 != r.amount * p2) apow++;
      if (cents(r.amount * p1) != cents(r.amount * p2)) {
        fpow++;
        #pragma omp critical
        fprintf(stderr, "pow row %lld: amount %.2f rate %.4f years %.0f: fdlibm %.17g, libc %.17g\n",
                i, r.amount, r.rate, r.years, r.amount * p1, r.amount * p2);
      }
      double v1 = r.amount * p1, v2 = r.amount * p2;
      if ((near_boundary(v1) || near_boundary(v2)) && cents_shortest(v1) != cents_shortest(v2)) {
        spow++;
        #pragma omp critical
        fprintf(stderr, "pow shortest row %lld: amount %.2f rate %.4f years %.0f: fdlibm %.17g, libc %.17g\n",
                i, r.amount, r.rate, r.years, v1, v2);
      }
    }
    double e1 = __ieee754_exp(t), e2 = exp(t);
    if (e1 != e2) {
      dexp++;
      if (r.amount * e1 != r.amount * e2) aexp++;
      if (cents(r.amount * e1) != cents(r.amount * e2)) {
        fexp++;
        #pragma omp critical
        fprintf(stderr, "exp row %lld: amount %.2f rate %.4f years %.0f: fdlibm %.17g, libc %.17g\n",
                i, r.amount, r.rate, r.years, r.amount * e1, r.amount * e2);
      }
      double v1 = r.amount * e1, v2 = r.amount * e2;
      if ((near_boundary(v1) || near_boundary(v2)) && cents_shortest(v1) != cents_shortest(v2)) {
        sexp++;
        #pragma omp critical
        fprintf(stderr, "exp shortest row %lld: amount %.2f rate %.4f years %.0f: fdlibm %.17g, libc %.17g\n",
                i, r.amount, r.rate, r.years, v1, v2);
      }
    }
  }
  if (argc > 3) {  /* the first rows' inputs and fdlibm's results, for FlipsCheck.java */
    long long m = atoll(argv[2]);
    FILE *f = fopen(argv[3], "w");
    for (long long i = 0; i < m; i++) {
      row r = make((uint64_t) i);
      double base = 1 + r.rate, t = r.rate * r.years;
      double v[5] = {base, r.years, t, __ieee754_pow(base, r.years), __ieee754_exp(t)};
      uint64_t u[5];
      memcpy(u, v, sizeof u);
      fprintf(f, "%016llx %016llx %016llx %016llx %016llx\n", (unsigned long long) u[0],
              (unsigned long long) u[1], (unsigned long long) u[2], (unsigned long long) u[3],
              (unsigned long long) u[4]);
    }
    fclose(f);
  }
  printf("rows %lld, glibc %s\n", n, gnu_get_libc_version());
  printf("pow: libraries differ on %lld rows, amounts on %lld; cents differ on %lld from the exact value, on %lld from the shortest decimal\n", dpow, apow, fpow, spow);
  printf("exp: libraries differ on %lld rows, amounts on %lld; cents differ on %lld from the exact value, on %lld from the shortest decimal\n", dexp, aexp, fexp, sexp);
  return 0;
}
