-- How Spark's round() treats two neighbouring doubles, and two rows whose cents depend on the
-- math library: StrictMath's exp and power (Spark's) against glibc 2.43's (flips.c).
CREATE TEMPORARY VIEW t AS SELECT * FROM VALUES
  ('exp', 874292.44D, 0.1342D, 39D, 163933300.405D),
  ('pow', 857486.34D, 0.1820D, 40D, 688535397.44499993D)
  AS t(fn, amount, rate, years, glibc_value);
SELECT fn,
       CASE fn WHEN 'exp' THEN amount * exp(rate * years) ELSE amount * power(1 + rate, years) END AS spark_value,
       round(CASE fn WHEN 'exp' THEN amount * exp(rate * years) ELSE amount * power(1 + rate, years) END, 2) AS spark_cents,
       glibc_value, round(glibc_value, 2) AS glibc_cents
FROM t ORDER BY fn;
SELECT round(24471612.324999999D, 2) AS round_below, round(24471612.325000003D, 2) AS round_above,
       bround(24471612.324999999D, 2) AS bround_below;
