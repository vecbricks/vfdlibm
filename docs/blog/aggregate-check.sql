-- Same query, different answer? Order-independent aggregates of exp, ln, log10, expm1 and pow
-- (Spark computes these with java.lang.StrictMath), sin, tanh and cbrt (Spark: java.lang.Math)
-- and sqrt (correctly rounded everywhere: a control), over 1,000,000 inputs per function.
--
-- Runs as is in Apache Spark's spark-sql and in a Databricks SQL editor. No table is created.
-- Every argument is derived from range()'s id column, so no engine can fold a call to a constant
-- at planning time; constants carry the D suffix, since 1000000.0 would be a DECIMAL.
--
-- Columns: n rows; sum_y, the exact sum of each result cast to DECIMAL(38,18), which no order of
-- summation can change (every result is in [0.1, 100), where a double's shortest decimal has at
-- most 18 digits after the point, so the cast keeps each result whole and a 1-ulp change moves
-- the sum); xor_y, the XOR of xxhash64 over the results' bits, a fingerprint of the whole column;
-- min_y and max_y as strings, so no UI rounds them; cast_lossy, the rows whose cast to decimal
-- does not read back as the same double (must be 0); and xor_x and sum_x, the same fingerprint
-- and sum of the arguments.
--
-- CONTROLS, compare these first: xor_x and sum_x must equal Spark's on every row, and the sqrt
-- row must equal Spark's in every column. If they do not, the engines differ in how they make
-- the inputs, cast or hash, and the other rows say nothing about the math library.
-- On Databricks, check in the query profile that the plan ran in Photon: a query that fell back
-- to the JVM would show no difference for exp, ln, log10, expm1 and pow because it is Spark.
-- All nine functions share one CASE, so one function Photon lacks could send the whole
-- projection to the JVM; if the profile shows no Photon, rerun without the sin, tanh and cbrt rows.
-- Spark's own answer for sin, tanh and cbrt (java.lang.Math) depends on the JDK: on Spark 4.3.0
-- local, tanh and cbrt give different sum_y and xor_y on JDK 21 and JDK 25 (sin does not);
-- compare those rows with a Spark run on the same JDK as the cluster's.
WITH t AS (
  SELECT id, CAST(id AS DOUBLE) / 1000000D AS u FROM range(1000000)
), v AS (
  --   f        argument(s), all derived from id           result
  SELECT 'exp'   AS f, id, 4.6D * u           AS x, 0D AS x2 FROM t UNION ALL  -- exp(x)   in [1, 99.5)
  SELECT 'ln',         id, 3D + 1000D * u,          0D       FROM t UNION ALL  -- ln(x)    in [1.09, 6.91)
  SELECT 'log10',      id, 2D + 999998D * u,        0D       FROM t UNION ALL  -- log10(x) in [0.30, 6.0)
  SELECT 'expm1',      id, 0.1D + 4.5D * u,         0D       FROM t UNION ALL  -- expm1(x) in [0.105, 98.5)
  SELECT 'pow',        id, 1.5D + u,  1D + CAST(id % 1000 AS DOUBLE) / 1000D * 3D FROM t UNION ALL
                                                                                -- pow(x, x2) in [1.5, 39)
  SELECT 'sin',        id, 0.2D + 2.7D * u,         0D       FROM t UNION ALL  -- sin(x)   in [0.198, 1]
  SELECT 'tanh',       id, 0.2D + 4.8D * u,         0D       FROM t UNION ALL  -- tanh(x)  in [0.197, 1)
  SELECT 'cbrt',       id, 1D + 999999D * u,        0D       FROM t UNION ALL  -- cbrt(x)  in [1, 100)
  SELECT 'sqrt',       id, 1D + 9999D * u,          0D       FROM t            -- sqrt(x)  in [1, 100): CONTROL
)
SELECT f,
       count(*)                                                   AS n,
       sum(CAST(y AS DECIMAL(38, 18)))                            AS sum_y,
       hex(bit_xor(xxhash64(y)))                                  AS xor_y,
       CAST(min(y) AS STRING)                                     AS min_y,
       CAST(max(y) AS STRING)                                     AS max_y,
       count_if(CAST(CAST(y AS DECIMAL(38, 18)) AS DOUBLE) <> y)  AS cast_lossy,
       hex(bit_xor(xxhash64(x, x2)))                              AS xor_x,
       sum(CAST(x AS DECIMAL(38, 18)) + CAST(x2 AS DECIMAL(38, 18))) AS sum_x
FROM (
  SELECT f, x, x2,
         CASE f WHEN 'exp'   THEN exp(x)   WHEN 'ln'    THEN ln(x)
                WHEN 'log10' THEN log10(x) WHEN 'expm1' THEN expm1(x)
                WHEN 'pow'   THEN pow(x, x2)
                WHEN 'sin'   THEN sin(x)   WHEN 'tanh'  THEN tanh(x)
                WHEN 'cbrt'  THEN cbrt(x)  WHEN 'sqrt'  THEN sqrt(x) END AS y
  FROM v)
GROUP BY f
ORDER BY f;
