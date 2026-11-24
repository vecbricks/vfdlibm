/*
 * glibc's AVX2 vector functions (libmvec) against its scalar ones, on the 32 inputs of
 * ../photon-check.sql. Reads rows.txt ("f x libm" a line, written by rounding.py), computes f(x)
 * with _ZGVdN4v_exp, _ZGVdN4v_log, _ZGVdN4v_expm1 or _ZGVdN4vv_pow (pow(2.0, x)), all four lanes
 * set to x, and compares lane 0 with this machine's scalar exp, log, expm1 or pow. It also
 * counts the rows where the scalar result is not the libm column (glibc 2.43 when it was made).
 *
 *   cc -O1 -mavx2 -o libmvec libmvec.c -lmvec -lm && ./libmvec
 *
 * Needs an x86-64 CPU with AVX2 and glibc's libmvec.
 */
#include <immintrin.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

__m256d _ZGVdN4v_exp(__m256d);
__m256d _ZGVdN4v_log(__m256d);
__m256d _ZGVdN4v_expm1(__m256d);
__m256d _ZGVdN4vv_pow(__m256d, __m256d);

int main(void) {
  char f[16];
  double x, column;
  int same = 0, differ = 0, off_column = 0, n;
  FILE *in = fopen("rows.txt", "r");
  if (in == NULL) {
    perror("rows.txt (run rounding.py first)");
    return 1;
  }
  while ((n = fscanf(in, "%15s %lf %lf", f, &x, &column)) == 3) {
    __m256d v = _mm256_set1_pd(x), r;
    volatile double vx = x; /* keep the scalar calls at run time */
    double scalar, out[4];
    if (!strcmp(f, "exp")) {
      r = _ZGVdN4v_exp(v);
      scalar = exp(vx);
    } else if (!strcmp(f, "ln")) {
      r = _ZGVdN4v_log(v);
      scalar = log(vx);
    } else if (!strcmp(f, "expm1")) {
      r = _ZGVdN4v_expm1(v);
      scalar = expm1(vx);
    } else if (!strcmp(f, "pow")) {
      r = _ZGVdN4vv_pow(_mm256_set1_pd(2.0), v);
      scalar = pow(2.0, vx);
    } else {
      fprintf(stderr, "unknown function '%s'\n", f);
      return 1;
    }
    _mm256_storeu_pd(out, r);
    if (scalar != column) off_column++;
    if (out[0] == scalar) {
      same++;
    } else {
      differ++;
      printf("%s(%.17g): libmvec %.17g, scalar %.17g\n", f, x, out[0], scalar);
    }
  }
  fclose(in);
  if (n != EOF || same + differ != 32) {
    fprintf(stderr, "rows.txt: expected 32 rows, read %d\n", same + differ);
    return 1;
  }
  printf("libmvec (AVX2) equals scalar glibc on %d, differs on %d; "
         "scalar glibc differs from the libm column on %d\n", same, differ, off_column);
  return 0;
}
