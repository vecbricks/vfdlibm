-- Predicts aggregate-check.sql's output from an engine that returns glibc libm's doubles: runs
-- the same aggregates over glibc-values.csv (aggregate-glibc-values.c's output, in the current
-- directory), and compares it row by row
-- with what this Spark computes. x_mismatch must be 0 (the C program derived the same inputs).
CREATE TEMPORARY VIEW g (f STRING, id BIGINT, x DOUBLE, x2 DOUBLE, y DOUBLE)
  USING csv OPTIONS (path 'glibc-values.csv', header 'false');
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
, s AS (
  SELECT f, id, x, x2,
         CASE f WHEN 'exp'   THEN exp(x)   WHEN 'ln'    THEN ln(x)
                WHEN 'log10' THEN log10(x) WHEN 'expm1' THEN expm1(x)
                WHEN 'pow'   THEN pow(x, x2)
                WHEN 'sin'   THEN sin(x)   WHEN 'tanh'  THEN tanh(x)
                WHEN 'cbrt'  THEN cbrt(x)  WHEN 'sqrt'  THEN sqrt(x) END AS y
  FROM v)
SELECT g.f,
       count(*)                                                       AS n,
       sum(CAST(g.y AS DECIMAL(38, 18)))                              AS sum_y,
       hex(bit_xor(xxhash64(g.y)))                                    AS xor_y,
       CAST(min(g.y) AS STRING)                                       AS min_y,
       CAST(max(g.y) AS STRING)                                       AS max_y,
       count_if(CAST(CAST(g.y AS DECIMAL(38, 18)) AS DOUBLE) <> g.y)  AS cast_lossy,
       hex(bit_xor(xxhash64(g.x, g.x2)))                              AS xor_x,
       sum(CAST(g.x AS DECIMAL(38, 18)) + CAST(g.x2 AS DECIMAL(38, 18))) AS sum_x,
       count_if(g.x <> s.x OR g.x2 <> s.x2)                           AS x_mismatch,
       count_if(g.y <> s.y)                                           AS rows_differ,
       sum(CAST(s.y AS DECIMAL(38, 18))) - sum(CAST(g.y AS DECIMAL(38, 18))) AS spark_minus_glibc,
       count_if(g.y > s.y)                                            AS glibc_higher
FROM g JOIN s ON g.f = s.f AND g.id = s.id
GROUP BY g.f
ORDER BY g.f;
