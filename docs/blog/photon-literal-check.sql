-- exp(1) twice in one query: once of a literal, once of the same value read from a table.
-- Spark's optimizer folds exp(1.0D) on the driver, in the JVM, with StrictMath; exp(x) is computed
-- by the engine. A Delta table, as on Databricks; on Apache Spark without Delta, create it with
-- CREATE TABLE tmp_exp1 (x DOUBLE) USING parquet after DROP TABLE IF EXISTS tmp_exp1.
-- The output on Databricks Free Edition with Photon, 25 November 2026, is in
-- strictmath-vs-libm/data/photon-literal-vs-column-2026-11-25.txt, the EXPLAIN's in
-- photon-literal-vs-column-explain-2026-11-25.txt beside it.
CREATE OR REPLACE TABLE tmp_exp1 (x DOUBLE);
INSERT INTO tmp_exp1 VALUES (1.0D);
SELECT CAST(exp(1.0D) AS STRING) AS literal, CAST(exp(x) AS STRING) AS from_column, exp(1.0D) = exp(x) AS same FROM tmp_exp1;
EXPLAIN SELECT exp(1.0D), exp(x) FROM tmp_exp1;
DROP TABLE tmp_exp1;
