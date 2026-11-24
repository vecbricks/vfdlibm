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
