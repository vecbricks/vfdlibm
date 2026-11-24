/*
 * glibc's AVX2 vector functions (libmvec) against its scalar ones, on the 32 inputs of
 * ../photon-check.sql. Reads rows.txt ("f x libm" a line, written by rounding.py), computes f(x)
 * with _ZGVdN4v_exp, _ZGVdN4v_log, _ZGVdN4v_expm1 or _ZGVdN4vv_pow (pow(2.0, x)), all four lanes
 * set to x, and compares lane 0 with the scalar result in the libm column.
 *
 *   cc -O1 -mavx2 -o libmvec libmvec.c -lmvec -lm && ./libmvec
 *
 * Needs an x86-64 CPU with AVX2 and glibc's libmvec.
 */
#include <immintrin.h>
#include <stdio.h>
#include <string.h>

__m256d _ZGVdN4v_exp(__m256d);
__m256d _ZGVdN4v_log(__m256d);
__m256d _ZGVdN4v_expm1(__m256d);
__m256d _ZGVdN4vv_pow(__m256d, __m256d);

int main(void) {
  char f[16];
  double x, scalar;
  int same = 0, differ = 0;
  FILE *in = fopen("rows.txt", "r");
  if (in == NULL) {
    perror("rows.txt (run rounding.py first)");
    return 1;
  }
  while (fscanf(in, "%15s %lf %lf", f, &x, &scalar) == 3) {
    __m256d v = _mm256_set1_pd(x), r;
    double out[4];
    if (!strcmp(f, "exp")) r = _ZGVdN4v_exp(v);
    else if (!strcmp(f, "ln")) r = _ZGVdN4v_log(v);
    else if (!strcmp(f, "expm1")) r = _ZGVdN4v_expm1(v);
    else r = _ZGVdN4vv_pow(_mm256_set1_pd(2.0), v);
    _mm256_storeu_pd(out, r);
    if (out[0] == scalar) {
      same++;
    } else {
      differ++;
      printf("%s(%.17g): libmvec %.17g, scalar %.17g\n", f, x, out[0], scalar);
    }
  }
  fclose(in);
  printf("libmvec (AVX2) equals scalar glibc on %d, differs on %d\n", same, differ);
  return 0;
}
