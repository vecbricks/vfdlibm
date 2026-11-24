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

```text
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
compares glibc's AVX2 vector functions (libmvec) with the machine's scalar glibc, and the scalar
results with the `libm` column.

Run them from a scratch directory, so that `rows.txt` and the binary stay out of the
repository:

```sh
d=$(pwd); cd "$(mktemp -d)"
python3 "$d/rounding.py"
cc -O1 -mavx2 -o libmvec "$d/libmvec.c" -lmvec -lm
./libmvec
```

On glibc 2.43, x86-64 with AVX2, Python 3.14.4, their last lines read:

```text
32 rows; the correctly rounded result is libm's on 29, StrictMath's on 3, neither on 0, too close to call on 0
libmvec (AVX2) equals scalar glibc on 15, differs on 17; scalar glibc differs from the libm column on 0
```

The three where `StrictMath`'s result is the correctly rounded one are all `expm1`, at
3.178803427426695, 1.0542424180475716 and 1.0400925680121542. libmvec differs on 5 `exp`, 4 `ln`,
3 `expm1` and 5 `pow(2.0, x)` inputs. They also write `correctly-rounded.csv` and `libmvec.csv`.

## The data behind the post's figures

`../figures.py` draws the post's figures into `../img/` from the files in `data/`:

- `exp1-bits.txt`: `java ExpOne.java` on JDK 25.0.5.1, the bits of `StrictMath.exp(1)`,
  `Math.exp(1)` and `Math.E`.
- `compare-glibc-2.43.txt`: `compare.py`'s output above.
- `correctly-rounded.csv` and `libmvec-glibc-2.43-avx2.csv`: the files `rounding.py` and
  `libmvec` write, from the run above.
- `spark-4.3.0-local-2026-11-24.csv`: `photon-check.sql` run by Apache Spark 4.3.0's `spark-sql`
  (the official `spark-4.3.0-bin-hadoop3.tgz`, checked against Apache's SHA-512; the steps are in
  `spark-4.3.0-local-provenance.txt`) in local mode on JDK 21, with the table created `USING parquet` (the first statement replaced by
  `DROP TABLE IF EXISTS tmp_photon_math` and `CREATE TABLE ... USING parquet`); its output, tab
  separated, as comma-separated columns.
- `hypot-order.txt`: `hypot-order` (fdlibm 5.3's C on the input of issue #173, both argument
  orders; gcc 15.2.0, `-O0 -ffp-contract=off -fno-builtin -fno-strict-aliasing -D__LITTLE_ENDIAN`,
  x86-64) and `java HypotOrder.java` on JDK 17.0.21.1, 21.0.13.1 and 25.0.5.1.
- `hypot-pairs.txt`: `java HypotPairs.java 1000000 | ./hypot-order-check` and
  `java HypotPairsEdges.java 300000 | ./hypot-order-check` on JDK 25.0.5.1, the same gcc and flags.
- `nantier-jdk-25.0.5.1.txt`: `../NanTier.java`'s output, one run under `-Xint` and one under the
  default JIT tiers, on JDK 25.0.5.1, x86-64.
- `photon-2026-11-24.csv`: Photon's result for each input, from Databricks Free Edition, SQL
  warehouse with Photon, 24 November 2026: the owner's output of `photon-check.sql`'s query, pasted verbatim
  from the Databricks SQL editor, header and row order kept. On every row the `photon` value
  equals the `libm` column of `photon-check.sql`.

```sh
python3 ../figures.py
```

## hypot's argument order

`hypot-order.c` and `hypot-order-check.c` link Sun's `e_hypot.c` from
`../../../reference/fdlibm-5.3/`; `HypotOrder.java`, `HypotPairs.java` and `HypotPairsEdges.java`
run on any JDK. From a scratch directory:

```bash
d=$(pwd); R=$d/../../../reference/fdlibm-5.3
F="-O0 -ffp-contract=off -fno-builtin -fno-strict-aliasing -D__LITTLE_ENDIAN"
cd "$(mktemp -d)"
gcc $F -I "$R" -o hypot-order "$d/hypot-order.c" "$R/e_hypot.c" -lm && ./hypot-order
java "$d/HypotOrder.java"
gcc $F -I "$R" -o hypot-order-check "$d/hypot-order-check.c" "$R/e_hypot.c" -lm
java "$d/HypotPairs.java" 1000000 | ./hypot-order-check | tail -1
java "$d/HypotPairsEdges.java" 300000 | ./hypot-order-check | tail -1
```
