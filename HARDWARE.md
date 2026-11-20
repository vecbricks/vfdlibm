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

The host key is `benchmark.yml`'s `host` input, and the string after it is what the workflow's
CPU check requires of `/proc/cpuinfo`'s model name, matched ignoring case: the two EPYC strings
are full names and must end it, the two Xeon strings may sit anywhere in it.

| machine | host key and CPU string | vector flags | JVM | lane-ops/ns at 128 / 256 / 512 | control | 512:256 | source |
|---|---|---|---|---|---|---|---|
| Intel Xeon Platinum 8573C | `xeon8573c`, `INTEL(R) XEON(R) PLATINUM 8573C` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | 41.5 / 82.7 / 113.0 | 1.99 to 2.03 | 1.34 to 1.37 | Varka, `VARKA-62.md` 11.9, 4 runs; the lane rates, 1.99 and 1.37 from `bench/results/log1p-xeon8573c-2026-11-03.txt`; the string from vecbricks/varka's fork runs 41465323581, 41255932044 and 41241977271 (MaxGekk/spark) |
| Intel Xeon 6973P-C (Granite Rapids) | `xeon6973p`, `Intel(R) Xeon(R) 6973P-C` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.00 to 2.01 | 1.33 to 1.35 | Varka, `VARKA-62.md` 11.9, 2 runs; the string from vfdlibm run 41442062308 |
| AMD EPYC 9V45 (Zen 5) | `epyc9v45`, `AMD EPYC 9V45 96-Core Processor` | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.04 | 1.99 | Varka, `VARKA-62.md` 11.9, 1 run; the string from Varka's fork runs 41486339971, 41446843041 and 41329377429 |
| AMD EPYC 7763 (Zen 3) | `epyc7763`, `AMD EPYC 7763 64-Core Processor` | `avx2`, no `avx512` | `UseAVX=2`, `MaxVectorSize=32` | 45.0-46.5 / 90.0-92.6 / 90.0-92.6 | 1.99 to 2.01 | 1.00 | Varka, `VARKA-62.md` 11.9, 8 runs; the lane rates, a range, from 16 Varka fork runs, 31 October to 2 November 2026 (41236071283 to 41460150392); the string from `bench/results/exp-epyc7763-2026-11-02.txt` and `log-epyc7763-2026-11-02.txt` |
| AMD EPYC 9V74 (Zen 4) | none: not a benchmark host | `avx512f`, `avx512dq` on 98 of the pool's 104 draws below; neither on 6, nor on Varka's 3 | `UseAVX=3`, `MaxVectorSize=64` with the flags; `UseAVX=2`, `MaxVectorSize=32` without | 54.1-57.6 / 105.0-115.2 / 95.7-103.7 with the flags; 40.6-40.7 / 81.5-81.7 / 81.5-81.6 without | 1.94 to 2.02 | 0.90 to 0.91 with the flags; 1.00 without | "What the pool gives" below, 104 draws, 3 to 19 November 2026; Varka, `VARKA-62.md` 11.9, 3 runs (2.00, and 0.91 to 1.00), after which it was dropped as a host on 2 November 2026, as exposing no AVX-512 |
| AMD Ryzen AI 9 HX PRO 370 (Zen 5 mobile, 24 threads) | none: the owner's laptop, never a headline | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | 65.5 / 130.8 / 149.4 | 2.00 | 1.14 | `bench/datapath.sh`, 2 November 2026, not pinned to one core type; Varka read 68 / 136 / 156, 2.02, 1.14 |

The two Xeons are the AVX-512 hosts results wait for and are read first; their 512:256 of about
1.35 means a 512-bit arm can win there, but not by the factor of two its lane count suggests.
The EPYC 9V45 is the one full-width 512-bit datapath in the pool. It is recorded whenever it is
drawn, but nobody waits for it: `bench/dispatch-bench.sh --host epyc9v45` stops after 18
dispatches. The EPYC 7763 decides for AVX2. The laptop's 1.14 is why its 512-bit rows are not
what a native AVX-512 host would show (`SKILLS.md`).

## What the pool gives

The benchmark hosts are draws from GitHub's `ubuntu-latest` pool, so how often a CPU is drawn,
and what else the pool holds, decide what can be measured. The nightly's sweep, fuzz and Arm
jobs each run `bench/datapath.sh` first, and `bench/pool/pool.py` tallies their readings
(`bench/pool/README.md`). The 332 x86 jobs of the 17 nights from 3 to 19 November 2026, runs
41612916834 to 44505887053 (`bench/pool/nightly-2026-11-03-to-2026-11-19.tsv`):

| CPU | jobs | share | nights of 17 | `avx512f` / `avx512dq` | JVM | lane-ops/ns at 128 / 256 / 512 bits | 256:128 | 512:256 |
|---|---|---|---|---|---|---|---|---|
| AMD EPYC 7763 | 116 | 34.9% | 17 | no / no | `MaxVectorSize=32 UseAVX=2` | 46.0-46.3 / 91.7-92.5 / 91.8-92.5 | 1.98 to 2.01 | 0.99 to 1.01 |
| AMD EPYC 9V74 | 98 | 29.5% | 17 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 54.1-57.6 / 105.0-115.2 / 95.7-103.7 | 1.94 to 2.02 | 0.90 to 0.91 |
| AMD EPYC 9V45 | 42 | 12.7% | 15 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 54.8-60.6 / 109.3-121.1 / 218.2-242.4 | 1.94 to 2.05 | 1.95 to 2.06 |
| Intel Xeon Platinum 8573C | 25 | 7.5% | 14 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 34.5-41.7 / 68.9-83.2 / 93.6-113.0 | 1.95 to 2.06 | 1.32 to 1.36 |
| Intel Xeon 6975P-C | 19 | 5.7% | 7 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 44.8-45.4 / 89.9-91.1 / 120.7-122.2 | 2.00 to 2.02 | 1.34 to 1.35 |
| Intel Xeon Platinum 8370C | 17 | 5.1% | 10 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 39.4-39.7 / 79.1-79.3 / 105.2-105.5 | 1.99 to 2.01 | 1.33 |
| Intel Xeon 6973P-C | 9 | 2.7% | 7 | yes / yes | `MaxVectorSize=64 UseAVX=3` | 43.1-48.8 / 88.5-98.2 / 116.1-131.5 | 1.83 to 2.08 | 1.31 to 1.47 |
| AMD EPYC 9V74 | 6 | 1.8% | 6 | no / no | `MaxVectorSize=32 UseAVX=2` | 40.6-40.7 / 81.5-81.7 / 81.5-81.6 | 2.01 | 1.00 |

- A share is a share of those jobs. A night's jobs start together, so the table is 17 samples of
  about twenty draws at one hour of the day, not 332 independent ones.
- The EPYC 9V74 is two kinds of machine under one model name: 98 of its 104 draws expose
  `avx512f` and `avx512dq` and run at `UseAVX=3`, their 512-bit lanes at 0.90 to 0.91 of the
  256-bit rate, and 6 expose neither, as the three runs Varka measured did.
- The Xeon 6975P-C and the Xeon Platinum 8370C have no row in "The machines". Both have AVX-512
  and read 1.33 to 1.35, within the two Xeon hosts' range.
- The 332 Arm jobs of the same nights all read `MaxVectorSize=16 UseSVE=2` and 26.6 to 26.9
  lane-ops/ns at 128 bits. `/proc/cpuinfo` names no model there, and the probe's ratios read
  1.00 to 1.01, since its three widths all run at the machine's one.

Which of these CPUs are hosts is the owner's decision (`SKILLS.md`, "Where headline numbers come
from"); this section records what was drawn.

## Adding a row

Every benchmark run's header carries the CPU, flags, JVM settings and datapath reading, so a new
row, or a range widened, cites the run's results file or run id in its source column. To
describe a machine with no benchmark run, on a quiet machine with a JDK 25 on the path:

    bench/datapath.sh

It needs nothing built and takes about ten seconds. On a machine with two core types (the
laptop's `distinct core max MHz` line lists two) pin it to one, for example with `taskset`, or
say in the row that it was not pinned.
