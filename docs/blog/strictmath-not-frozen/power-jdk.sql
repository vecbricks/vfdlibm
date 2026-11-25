-- Spark's power() on an input whose StrictMath.pow result JDK 27 changed (Moderate.java's
-- witness): 0.01920251216909316 on JDK 25 and 0.019202512169093165 on JDK 27.
CREATE TEMPORARY VIEW w AS SELECT * FROM VALUES (1.0000000005093685D, -7.760027956194805E9D) AS w(x, y);
SELECT x, y, power(x, y) AS p, java_method('java.lang.System', 'getProperty', 'java.runtime.version') AS jdk FROM w;
