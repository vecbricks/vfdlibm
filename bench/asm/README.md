# The assembly census

What C2 emits for a port's hot loop, counted, so a change to a body's shape can be checked in
instructions before it is timed. `AsmDriver.java` warms `VFdlibm.exp`'s and `VFdlibm.log`'s
256-bit loops on common data (exp draws from `[-20, 20]`, as `ExpBenchmark`'s common inputs do)
until C2 compiles them; `dump.sh` prints `AsmDriver::loopExp` with the body inlined, as an AVX2
host such as the EPYC 7763 compiles it (`-XX:UseAVX=2 -XX:MaxVectorSize=32`, no tiered
compilation), and `census.py` counts the loop's hot path.

    mvn -B -q compile
    bench/asm/dump.sh target/classes target/asm ~/hsdis

The disassembler is `hsdis-amd64.so`, built from OpenJDK's `src/utils/hsdis` with binutils, in
the directory given as the third argument (the default is `~/hsdis`). Without it HotSpot prints
raw bytes and the census finds no loop. The census reads only the code; it says nothing about
speed, which only the EPYC 7763 benchmark decides (`AGENTS.md`).

## How the census counts

`census.py` takes the last standard (not on-stack-replacement) compile of the method, and in it
the loop from the target of its widest backward conditional branch to that branch: the load, the
body, the store and the loop's own add, compare and branch. Inside that range it leaves out any
block a forward conditional branch skips when the branch's target is also in the range; in exp's
loop that is the 13-instruction fix-up C2 emits after `vcvttpd2dq` for Java's `D2I` semantics,
run only when a lane converted to `0x80000000` (a NaN or an out-of-range value, filter lanes
only). The per-lane finish and the uncommon traps are out of line, outside the range. It reports
the hot path's instructions, `vdivpd`s, blends (`vblendvps`, `vpblendvb`, ...), broadcasts of a
constant (`vbroadcastsd`, `vpbroadcastq`), stack accesses (spill stores and reloads, `[rsp ...]`),
and the integer and double compares, then lists the hot path.

## exp, 2 Nov 2026

Laptop (AMD Ryzen AI 9 HX PRO 370) under `-XX:UseAVX=2 -XX:MaxVectorSize=32`, JDK 25, 256 bits.
Before: `main` at 282813b. After: the folds of `PROOFS.md` (exp, L1 to L5).

| hot path | before | after |
| :--- | ---: | ---: |
| instructions | 107 | 64 |
| `vdivpd` | 2 | 1 |
| blends | 11 | 2 |
| constant broadcasts | 25 | 12 |
| stack accesses (spills) | 4 | 0 |
| `vpcmpgtq` | 2 | 4 |
| double compares (`vcmp*pd`) | 4 | 0 |

The near form and the `k == 0` form were computed for every lane and blended; now every lane takes
one form, so the second division, the near form's constant blends and the `k == 0` blends are
gone. The four high-word compares are each one `vpcmpgtq` (two of them were a double compare, an
all-ones vector and an xor). The two blends left are the reduced-lane zeroing of `k`'s argument
and the tiny lanes' `one + x`. The `D2I` fix-up check (`vpcmpeqd`, `vptest`, `je`) is still in
the hot path.
