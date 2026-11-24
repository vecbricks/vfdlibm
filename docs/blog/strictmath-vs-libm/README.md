# StrictMath against the C library's libm

How often `java.lang.StrictMath` and the C library's math functions return different doubles, on
200,000 random inputs. `Gen.java` draws `x` uniformly in `[-700, 700)` with `java.util.Random`
seeded 7, and prints `x` with StrictMath's `exp(x)`, `log(|x|)`, `expm1(x / 100)` and
`pow(2.0, x / 700)`. `compare.py` computes the same four with Python's `math` module, which calls
the platform's C library, and counts the inputs where the two differ.

```sh
java Gen.java > gen.txt          # JDK 11 or later runs a single source file
python3 compare.py < gen.txt
```

On JDK 25.0.5.1 and glibc 2.43 (Python 3.14.4, x86-64) it printed:

```
200000 inputs, libc glibc 2.43, Python 3.14.4
          exp(x): StrictMath and libm differ on 19,720
         ln(|x|): StrictMath and libm differ on 4,047
    expm1(x/100): StrictMath and libm differ on 139
 pow(2.0, x/700): StrictMath and libm differ on 19,627
```

StrictMath's results are the same on every JVM; the libm counts are glibc 2.43's, and another C
library or version may give others. `../photon-check.sql` holds 32 such inputs, eight a function,
with both results, for checking which one a SQL engine returns.

## Correct rounding and glibc's vector library

Two more tests on the 32 inputs of `../photon-check.sql`. `rounding.py` computes each function in
Python's `decimal` at 80 digits, rounds to a double, and says which column is the correctly
rounded one; it also writes `rows.txt` in the current directory. `libmvec.c` reads `rows.txt` and
compares glibc's AVX2 vector functions (libmvec) with the scalar results.

```sh
python3 rounding.py
cc -O1 -mavx2 -o libmvec libmvec.c -lmvec -lm
./libmvec
```

On glibc 2.43, x86-64 with AVX2, Python 3.14.4, they printed (the lines of each mismatch left out
but for the counts):

```
32 rows; the correctly rounded result is libm's on 29, StrictMath's on 3, neither on 0
libmvec (AVX2) equals scalar glibc on 15, differs on 17
```

The three where `StrictMath`'s result is the correctly rounded one are all `expm1`, at
3.178803427426695, 1.0542424180475716 and 1.0400925680121542. libmvec differs on 5 `exp`, 4 `ln`,
3 `expm1` and 5 `pow(2.0, x)` inputs.
