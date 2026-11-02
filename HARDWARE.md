# Hardware census

What vfdlibm's numbers are measured on, and what those machines can do. Adapted from
vecbricks/varka's `sql/varka/HARDWARE.md`, the census of the same role; the rows below start
from Varka's measurements, named as such, and vfdlibm's own runs extend them.

## How to read the columns

`bench/datapath.sh` prints a machine's CPU model, its vector flags, the JVM's `UseAVX` and
`MaxVectorSize`, and a datapath probe (`bench/Datapath.java`): the same integer vector loop timed
at 128, 256 and 512 bits, in lane-operations per nanosecond. `.github/workflows/benchmark.yml`
runs it before JMH on every benchmark run and copies its reading into the results header
(`Datapath:`), and `bench/pair.sh` does the same for a pair. Two ratios follow from it.

- **control** is 256:128 and should read about 2. It is the probe's check that it measures the
  datapath and nothing else; a control far from 2 makes the row unusable, and the script says
  so.
- **512:256** says what a 512-bit species is worth on that machine. About 2 means a full-width
  512-bit datapath; about 1 means 512-bit operations are issued as two 256-bit halves, or that
  there is no AVX-512 and the 512-bit reading is the 256-bit one; about 1.3 is what Intel's
  server cores read, which issue 256-bit integer vector operations on three ports and 512-bit
  ones on two (Varka's `VARKA-62.md`, section 11.9).

The flags say what the instruction set allows; the ratio says what the hardware does with it.
Each host decides for its own instruction set (`SKILLS.md`, "Where headline numbers come from").

## The machines

The host key is `benchmark.yml`'s `host` input, and the string after it is the substring of
`/proc/cpuinfo`'s model name that the workflow's CPU check requires, matched ignoring case.

| machine | host key and CPU string | vector flags | JVM | lane-ops/ns at 128 / 256 / 512 | control | 512:256 | source |
|---|---|---|---|---|---|---|---|
| Intel Xeon Platinum 8573C | `xeon8573c`, `INTEL(R) XEON(R) PLATINUM 8573C` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.00 to 2.03 | 1.34 to 1.36 | Varka, `VARKA-62.md` 11.9, 4 runs; the string from vecbricks/varka's fork runs 41465323581, 41255932044 and 41241977271 (MaxGekk/spark) |
| Intel Xeon 6973P-C (Granite Rapids) | `xeon6973p`, `Intel(R) Xeon(R) 6973P-C` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.00 to 2.01 | 1.33 to 1.35 | Varka, `VARKA-62.md` 11.9, 2 runs; the string from vfdlibm run 41442062308 |
| AMD EPYC 9V45 (Zen 5) | `epyc9v45`, `AMD EPYC 9V45` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.04 | 1.99 | Varka, `VARKA-62.md` 11.9, 1 run; the string ("AMD EPYC 9V45 96-Core Processor") from Varka's fork runs 41486339971, 41446843041 and 41329377429 |
| AMD EPYC 7763 (Zen 3) | `epyc7763`, `AMD EPYC 7763` | `avx2`, no `avx512` | `UseAVX=2`, `MaxVectorSize=32` | 45.0 / 90.0 / 90.0 | 1.99 to 2.01 | 1.00 | Varka, `VARKA-62.md` 11.9, 8 runs; the lane rates from 16 Varka fork runs, 31 October to 2 November 2026 (41236071283 to 41460150392) |
| AMD EPYC 9V74 (Zen 4) | none: not a benchmark host | no `avx512` exposed | `UseAVX=2`, `MaxVectorSize=32` | see source | 2.00 | 0.91 to 1.00 | Varka, `VARKA-62.md` 11.9, 3 runs; dropped as a host on 2 November 2026, since the runners expose no AVX-512 |
| AMD Ryzen AI 9 HX PRO 370 (Zen 5 mobile, 24 threads) | none: the owner's laptop, never a headline | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | 65.5 / 130.8 / 149.4 | 2.00 | 1.14 | `bench/datapath.sh`, 2 November 2026, not pinned to one core type; Varka read 68 / 136 / 156, 2.02, 1.14 |

The two Xeons are the AVX-512 hosts results wait for and are read first; their 512:256 of about
1.35 means a 512-bit arm can win there, but not by the factor of two its lane count suggests.
The EPYC 9V45 is the one full-width 512-bit datapath in the pool. It is recorded whenever it is
drawn, but nobody waits for it: `bench/dispatch-bench.sh --host epyc9v45` stops after 18
dispatches. The EPYC 7763 decides for AVX2. The laptop's 1.14 is why its 512-bit rows are not
what a native AVX-512 host would show (`SKILLS.md`).

## Adding a row

Every benchmark run's header carries the CPU, flags, JVM settings and datapath reading, so a new
row, or a range widened, cites the run's results file or run id in its source column. To
describe a machine with no benchmark run, on a quiet machine with a JDK 25 on the path:

    bench/datapath.sh

It needs nothing built and takes about ten seconds. On a machine with two core types (the
laptop's `distinct core max MHz` line lists two) pin it to one, for example with `taskset`, or
say in the row that it was not pinned.
