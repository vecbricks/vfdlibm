#!/usr/bin/env python3
"""On the inputs where two result files differ, counts where two other files agree.

Each argument is a JdkScan output file of the same inputs, in input order. The script takes the
indices where FROM and TO differ (JDK 25's and JDK 27's StrictMath.pow), and counts on how many
of them A equals B:

    python3 witness_check.py out-25/pow.bin out-27/pow.bin out-26-nolibm/pow-Math.bin out-27/pow.bin
    python3 witness_check.py out-25/pow.bin out-27/pow.bin out-25-nolibm/pow-Math.bin out-25/pow.bin
"""
import struct
import sys


def bits(path):
    data = open(path, "rb").read()
    return struct.unpack(">%dq" % (len(data) // 8), data)


def main():
    frm, to, a, b = (bits(p) for p in sys.argv[1:5])
    where = [i for i in range(len(frm)) if frm[i] != to[i]]
    same = sum(a[i] == b[i] for i in where)
    print("%d inputs where %s and %s differ; %s equals %s on %d of them"
          % (len(where), sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], same))


if __name__ == "__main__":
    main()
