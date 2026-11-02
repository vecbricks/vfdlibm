# The assembly census

What C2 emits for a port's hot loop, counted, so a change to a body's shape can be checked in
instructions before it is timed. `AsmDriver.java` warms `VFdlibm.exp`'s and `VFdlibm.log`'s
loops on common data (exp draws from `[-20, 20]`, as `ExpBenchmark`'s common inputs do, and log
from `[1, 2) * 2^[-30, 30]`) until C2 compiles them; `dump.sh` prints `AsmDriver::loopExp` or
`AsmDriver::loopLog` with the body inlined and no tiered compilation, and `census.py` counts the
loop's hot path. Two modes: `avx512`, 512 bits on the
host's own flags (native AVX-512 on an AVX-512 host), and `avx2`, 256 bits as an AVX2 host such
as the EPYC 7763 compiles it (`-XX:UseAVX=2 -XX:MaxVectorSize=32`).

    mvn -B -q compile
    bench/asm/dump.sh avx512 target/classes target/asm ~/hsdis
    bench/asm/dump.sh avx2 target/classes target/asm ~/hsdis
    bench/asm/dump.sh avx512 target/classes target/asm ~/hsdis log
    bench/asm/dump.sh avx2 target/classes target/asm ~/hsdis log

The fifth argument names the function, `exp` (the default) or `log`; log's files are
`asm-log-<mode>.txt` and `census-log-<mode>.txt`.

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

## log, 3 Nov 2026

The same laptop and JDK. Before: `main` at 44c899e. After: branch `log-proven-folds`, the folds
of `PROOFS.md` (log, L6 to L9), merged with `port-log10`'s `Log` (the arms as an `int`, the
high word written back in the body). In log's loop the per-lane finish is out of line and no
`D2I` fix-up exists, so the loop and the hot path are the same instructions.

### AVX-512, 512 bits, the host's own flags (8 doubles per iteration)

| | before | after |
| :--- | ---: | ---: |
| hot path instructions | 99 | 88 |
| `vdivpd` | 1 | 1 |
| blends (`vpblendmq`) | 7 | 3 |
| constant broadcasts | 7 | 9 |
| stack accesses (spills) | 1 | 0 |
| integer compares (`vpcmp*`) | 3 | 3 |
| double compares (`vcmp*pd`) | 3 | 1 |
| mask-register operations (`k*`) | 6 | 4 |
| conversions (`vpmovqd`, `vpmovsxdq`, `vcvtdq2pd`) | 3 | 0 |

The `k == 0` fold (L6) alone, int lanes kept, read 89 with 6 broadcasts: four blends and the
`k == 0` compare gone. Moving the words to long lanes (L7, L8) was a tie by count at 512 bits:
89 again, but with 9 broadcasts, so 80 other instructions against 83. The three conversions go
(`L2I`, `I2L`, `I2D`) and the add of `0x1.8p52`'s bits joins the `vsubpd`, while the two new
constants are among those C2 reloads from memory as broadcasts inside the loop rather than
keeping in registers. The biased
compares (L9) took one more: 88. So on AVX-512 the long-lane form is kept for having fewer
operations at an equal count, three fewer non-load instructions; whether broadcasts that are
loads cost more than the conversions they replace is for the hosts' paired run to say.

### AVX2, 256 bits, `-XX:UseAVX=2 -XX:MaxVectorSize=32` (4 doubles per iteration)

| | before | after |
| :--- | ---: | ---: |
| hot path instructions | 131 | 110 |
| `vdivpd` | 1 | 1 |
| blends (`vblendvps`) | 7 | 3 |
| constant broadcasts | 27 | 28 |
| stack accesses (spills) | 5 | 1 |
| integer compares (`vpcmp*`) | 4 | 4 |
| double compares (`vcmp*pd`) | 3 | 1 |
| conversions and permutes (`vpmov*`, `vcvt*`, `vperm*`) | 7 | 0 |

The fold alone read 116. The long lanes first read 123: AVX2 has no 64-bit arithmetic shift,
and C2 emulates each `ASHR` on a long in three instructions. Writing the C's `>>` as `>>>`,
equal on the non-negative `hx` and `i` (L8), and adding `0x1.8p52`'s bits and the C's `-1023`
as one constant gave 112, and the biased compares 110. The three blends left are the band, the
`|f| < 2^-20` lanes and the `f == 0` lanes; the one double compare left is `f == 0`'s.
