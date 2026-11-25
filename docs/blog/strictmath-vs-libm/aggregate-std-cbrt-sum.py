# Sums aggregate-std-cbrt.cpp's output as ../aggregate-check.sql sums cbrt's results: the exact
# decimal sum of each double's shortest representation, printed with 18 digits after the point.
import sys, decimal
from decimal import Decimal as D
decimal.getcontext().prec = 60
t = D(0)
for l in sys.stdin:
    t += D(repr(float(l)))
print(t.quantize(D('0.000000000000000001')))
