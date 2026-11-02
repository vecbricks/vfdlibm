# The assembly census

What C2 emits for a port's hot loop, counted, so a change to a body's shape can be checked in
instructions before it is timed. `AsmDriver.java` warms `VFdlibm.exp`'s and `VFdlibm.log`'s
loops on common data (exp draws from `[-20, 20]`, as `ExpBenchmark`'s common inputs do) until C2
compiles them; `dump.sh` prints `AsmDriver::loopExp` with the body inlined and no tiered
compilation, and `census.py` counts the loop's hot path. Two modes: `avx512`, 512 bits on the
host's own flags (native AVX-512 on an AVX-512 host), and `avx2`, 256 bits as an AVX2 host such
as the EPYC 7763 compiles it (`-XX:UseAVX=2 -XX:MaxVectorSize=32`).

    mvn -B -q compile
    bench/asm/dump.sh avx512 target/classes target/asm ~/hsdis
    bench/asm/dump.sh avx2 target/classes target/asm ~/hsdis

The disassembler is `hsdis-amd64.so`, built from OpenJDK's `src/utils/hsdis` with binutils, in
the directory given as the third argument (the default is `~/hsdis`). Without it HotSpot prints
raw bytes and the census finds no loop. The census reads only the code; it says nothing about
speed, which only the EPYC 7763 benchmark decides (`AGENTS.md`).

## How the census counts

`census.py` takes the last standard (not on-stack-replacement) compile of the method, and in it
the loop from the target of its widest backward conditional branch to that branch: the load, the
body, the store and the loop's own add, compare and branch. Inside that range it leaves out any
block a forward conditional branch skips when the branch's target is also in the range; in exp's
loop that is the fix-up C2 emits after the conversion for Java's `D2I` semantics (13
instructions at 256 bits under AVX2, 7 at 512 bits), run only when a lane converted to
`0x80000000` (a NaN or an out-of-range value, filter lanes only). The per-lane finish and the
uncommon traps are out of line, outside the range. It reports
the hot path's instructions, `vdivpd`s, blends (`vblendvps`, `vpblendvb`, ...), broadcasts of a
constant (`vbroadcastsd`, `vpbroadcastq`), stack accesses (spill stores and reloads, `[rsp ...]`),
the integer and double compares and the AVX-512 mask-register operations (`k*`), then lists the
hot path. Both the whole loop and the hot path are reported below.

## exp, 2 Nov 2026

The owner's laptop (AMD Ryzen AI 9 HX PRO 370, Zen 5, AVX-512 on a 256-bit datapath, see
`SKILLS.md`), JDK 25. Before: `main` at 282813b. After: the folds of `PROOFS.md` (exp, L1 to L5).

### AVX-512, 512 bits, the host's own flags (8 doubles per iteration)

| | before | after |
| :--- | ---: | ---: |
| loop instructions (with the `D2I` fix-up) | 84 | 59 |
| hot path instructions | 77 | 52 |
| `vdivpd` | 2 | 1 |
| blends (`vpblendmq`) | 11 | 2 |
| constant broadcasts | 0 | 0 |
| stack accesses (spills) | 5 | 0 |
| integer compares (`vpcmp*q`) | 4 | 5 |
| double compares (`vcmp*pd`) | 4 | 0 |
| mask-register operations (`k*`) | 12 | 3 |

With 32 vector registers C2 keeps every constant in a register across the loop, so no broadcast
appears in either column (AVX2's 16 do not hold them, hence its broadcasts and spills). The
spills before were masks (`kmovq` to and from the stack). The high-word compares are integer
compares into mask registers (`vpcmpnleq`, `vpcmpltq`), where four were double compares
(`vcmplt_oqpd`), negated with `kxorb` where the C tests `>=` or `>`; those negations and the mask
moves of the blended forms are gone.

### AVX2, 256 bits, `-XX:UseAVX=2 -XX:MaxVectorSize=32` (4 doubles per iteration)

| | before | after |
| :--- | ---: | ---: |
| loop instructions (with the `D2I` fix-up) | 120 | 77 |
| hot path instructions | 107 | 64 |
| `vdivpd` | 2 | 1 |
| blends | 11 | 2 |
| constant broadcasts | 25 | 12 |
| stack accesses (spills) | 4 | 0 |
| integer compares (`vpcmp*`) | 8 | 5 |
| double compares (`vcmp*pd`) | 4 | 0 |

The near form and the `k == 0` form were computed for every lane and blended; now every lane takes
one form, so the second division, the near form's constant blends and the `k == 0` blends are
gone. Each high-word compare is one `vpcmpgtq` (two of them were a double compare, an all-ones
vector and an xor). At both widths the two blends left are the reduced-lane zeroing of `k`'s
argument and the tiny lanes' `one + x`, and the `D2I` fix-up's entry check (a compare, a test and
a branch) is still in the hot path.
