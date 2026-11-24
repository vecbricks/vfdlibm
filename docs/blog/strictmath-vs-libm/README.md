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

The aggregate card reads `aggregate-photon-2026-11-24.csv` and `aggregate-spark-4.3.0-jdk21.tsv`,
and checks them against the glibc prediction; those files are described under "The aggregate
query" below.

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
run on any JDK. From this directory (the block makes its own scratch directory for the
binaries):

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

## The aggregate query

`../aggregate-check.sql` reduces 1,000,000 results per function to an exact sum and a fingerprint;
its comment header says how to read them. The files in `data/`:

- `aggregate-photon-2026-11-24.csv`: its output on Databricks Free Edition, SQL warehouse with
  Photon, 24 November 2026, as pasted from the SQL editor.
- `aggregate-photon-explain-2026-11-24.txt`: `EXPLAIN` of the same query on the same warehouse,
  24 November 2026, as pasted. It ends "The query is fully supported by Photon."
- `aggregate-spark-4.3.0-jdk21.tsv` and `aggregate-spark-4.3.0-jdk25.tsv`: its output on Apache
  Spark 4.3.0 (`spark-4.3.0-bin-hadoop3.tgz`, git revision 98bc0212c94), `spark-sql -S`, local
  mode with two threads, on JDK 21.0.13.1 and 25.0.5.1, on my laptop (AMD Ryzen AI 9 HX PRO 370).
- `aggregate-glibc-2.43-predicted-jdk21.tsv` and `-jdk25.tsv`: what an engine returning glibc
  2.43's doubles would print, with four more columns: `x_mismatch` (inputs the C program derived
  differently, must be 0), `rows_differ` (results that differ from that Spark's), `spark_minus_glibc`
  and `glibc_higher`. Made by `aggregate-glibc-values.c` (gcc 15.2.0,
  `-O1 -ffp-contract=off -fno-builtin`, glibc 2.43), whose 465 MB output is not committed, and
  `aggregate-predict-glibc.sql`, run in Spark 4.3.0 on each JDK next to that output.

`aggregate-std-cbrt.cpp` prints `std::cbrt` of the same `cbrt` inputs and
`aggregate-std-cbrt-sum.py` sums them as the query does. `data/aggregate-std-cbrt-2026-11-24.txt`
holds the totals of 11 builds (g++ 15.2.0 and clang 21.1.8, glibc 2.43, each with
`-ffp-contract=off` added; the flags are on each line): all give the glibc prediction's total but
the two clang `-ffast-math` builds, which give 75000024.722734010199381500, and none gives
Photon's. For example:
`g++ -O2 -ffp-contract=off aggregate-std-cbrt.cpp -o std-cbrt && ./std-cbrt | python3 aggregate-std-cbrt-sum.py`.

`aggregate-cbrt-cr.py` prints the sum of the correctly rounded cube roots of the `cbrt` inputs,
75000024.722734006601528900, in about five seconds.

From this directory (the block makes its own scratch directory), with Spark 4.3.0's `bin` on the
`PATH`; run the two `spark-sql` lines once with `JAVA_HOME` at JDK 21 (the `-jdk21` files) and once
at JDK 25 (the `-jdk25` files):

```bash
d=$(pwd); cd "$(mktemp -d)"
gcc -O1 -ffp-contract=off -fno-builtin -o aggregate-glibc-values "$d/aggregate-glibc-values.c" -lm
./aggregate-glibc-values > glibc-values.csv
JAVA_HOME=/path/to/jdk-21 spark-sql --master 'local[2]' -S -f "$d/../aggregate-check.sql"
JAVA_HOME=/path/to/jdk-21 spark-sql --master 'local[2]' -S -f "$d/aggregate-predict-glibc.sql"
python3 "$d/aggregate-cbrt-cr.py"   # prints 75000024.722734006601528900
```

