# Correctly rounded cbrt on ../aggregate-check.sql's cbrt inputs: the cube root to 50 digits by Newton in decimal,
# then float(str) (correctly rounded); summed as Spark sums DECIMAL(38,18) of each double's shortest repr.
import decimal
from decimal import Decimal as D
decimal.getcontext().prec = 50
three = D(3)
tot = D(0)
for i in range(1000000):
    u = float(i) / 1000000.0
    x = 1.0 + 999999.0 * u
    X = D(x)                       # exact value of the double
    r = D(repr(x ** (1.0/3.0)))
    for _ in range(4):
        r = r - (r*r*r - X) / (three*r*r)
    y = float(str(r))
    tot += D(repr(y))
print(tot.quantize(D('0.000000000000000001')))
