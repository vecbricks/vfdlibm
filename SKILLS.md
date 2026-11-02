# What vfdlibm has learned

Lessons about benchmarking, the JIT and the Vector API that every port follows. Most were learned
in Varka (github.com/vecbricks/varka, its `sql/varka/skills/`) and are rewritten here for a vector
math library; the last sections are what the `exp`, `log` and `log1p` ports taught. Each entry
says where its numbers come from. A new lesson goes in the section it belongs to, as a short
entry; a negative result belongs here too, since it stops the next port re-trying a settled
question.

## Benchmarking with JMH

- **The options.** `-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc`: two forks, five measured two-second
  iterations each, and the allocation profiler on every run. Report each row's minimum, average
  and maximum over the ten iterations, as `bench/results/*.txt` do, and keep JMH's summary table
  with its `gc.alloc.rate.norm` rows beside them.
- **Inline the body, as Varka emits it.** A port's body is larger than C2 inlines by default, so
  a call from the benchmark loop is a real call, and a real call passes its vectors as heap
  objects. Run with `-jvmArgsAppend -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*`
  to measure the body inline, and say so in the results header. Without it the rows measure the
  boxing, not the port.
- **Compare arms inside one run, never across runs.** An A/B whose arms run in the same JMH run
  shares the machine's state; a number compared with an earlier run does not. In Varka two runs
  of an unchanged benchmark, pinned to one core complex, had 22 of 211 rows more than 10% apart,
  the worst near 26%: a per-fork C2 lottery (JDK-8380195, closed Not an Issue), not noise inside
  a run. Every arm of a decision goes in one benchmark class and one run.
- **A ratio under about 1.3x is re-run.** Run the pair again with the same options and compare
  minimums before writing a direction down; the results file keeps both runs.
- **Write the prediction down, then measure.** JIT performance intuition loses often enough that
  the expectation belongs in the pull request before the numbers, so a reversal is visible.
- **Check what the benchmark never executes.** A rare path the inputs never reach is not
  measured. `ExpBenchmark`'s `mixed` inputs exist for that: one input in 16 is NaN, 800, -750 or
  -708.5, so the overflow, underflow and subnormal lanes run. A port with an array tail or a
  remainder path needs sizes that are not a multiple of the lane count.
- **One species of a lane type per JVM.** In Varka, a JVM that had run two species of the same
  lane type (256- and 128-bit bytes, say) boxed in loops that were clean alone: the shared
  `ByteVector` templates inlined bimorphically and the receiver had to exist as an object. JMH
  forks a JVM per benchmark method, so `finish256` and `finish512` never share one; never run
  vector benchmarks with `-f 0`. A port's own second species of another lane type (`exp`'s int
  lanes beside its doubles) is not this case.
- **The machine.** Record the CPU, JDK, kernel, vector flags and the load average at start and
  end (`bench/results/*.txt` show the header). On the laptop, start only when the one-minute
  load average is under 1.0, and between queued runs wait until it falls under 0.9: a JVM that
  just exited leaves it above 1.0 for a minute or two. A results file is checked by reading every
  section of it, not by the exit code of what wrote it.
- **Laptop numbers are not headlines.** See the four benchmark hosts below.

## The JIT: boxing, intrinsics and inlining

- **Allocation is the verdict.** A boxed vector body computes the right bits, so every test
  passes on code that runs as a per-lane Java loop. Only the allocation rate tells them apart:
  `gc.alloc.rate.norm` near 0 B/op means the body ran unboxed; a box is kilobytes per op (exp's
  128-bit rows allocate 1.36 to 1.90 MB per 1024 doubles). Read `-prof gc` on every row.
- **CI checks it.** `bench/alloc-check.py` runs every benchmark method named `*256` or
  `*Preferred`, on `inputs=common` and `mixed`, one fork under `-prof gc` with the body inlined,
  and fails if any measured iteration reaches 1 B/op; the `alloc` job of `ci.yml` runs it at the
  runner's flags and under `-XX:UseAVX=2`. On the laptop, under load, C2 finished exp's body
  within the first one-second iteration and later ones read about 0.02 B/op, so three warmup
  seconds leave two to spare; a row that fails only in its first iteration means C2 was late.
  Left out: 128 bits (masks box at two lanes on x86, below) and 512 bits (software on AVX2; the
  preferred arm covers it where it is real). Mixed inputs were left out too until the per-lane
  finish stopped allocating (below); common data alone never runs a rare path, so it could not
  see that path box. The `*Max` rows are left out by the regex, since the finish still boxes at
  `SPECIES_MAX` on mixed data (below). Arm is not checked yet: its preferred species is 128 bits,
  and no run has shown exp unboxed there.
- **Ask C2 what it refused.** `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics`, or scoped
  to the library, `-XX:CompileCommand=PrintIntrinsics,io.github.vecbricks.vfdlibm.*::*`. Add
  `-Xbatch` when lines must be attributed to one shape: compiles then happen on the calling
  thread, between markers the probe prints. Three kinds of line:
  - `** not supported: ...` is architectural: no lowering for that operation, lane count and
    element type on this host. It is the verdict.
  - `** missing constant: ...` is a first late-inline attempt that C2 may retry and win. In
    Varka a plain `i + 1` printed two at 128 bits and was fully vectorised; in `exp` the species
    from `VectorSpecies.of` printed one and did stay boxed (49152 B/op). Read the allocation
    rate before reading this line either way.
  - `** unbox failed: ...` is a vector reaching the call as a heap object.
- **Ask C2 what it did not inline.** `-XX:+PrintInlining` (diagnostic) and
  `-XX:+PrintCompilation`. A call refused with `callee is too large` or, late in a large method,
  `NodeCountInliningCutoff` passes its vectors boxed. `exp` makes every call into `Lanes` before
  its arithmetic, while the graph is small, and writes the exponent add out instead of calling
  `Lanes.addExponent`, so nothing is refused near the end of the body. `CompileCommand=inline`
  lifts the size limit, not `NodeCountInliningCutoff`: a call refused for the node count stays
  refused under it, which only a change to the code fixes.
- **A refused call boxes on every pass, not only when it runs.** `exp`'s per-lane finish was a
  private method, `finishPerLane(x, result, rare)`, called at the end of the body behind
  `rare.anyTrue()`. `PrintInlining` showed it refused with `NodeCountInliningCutoff` in the
  final compile, under `-XX:UseAVX=2` with the body force-inlined. Its three arguments were then
  boxed on every vector, rare or not: 256 vectors times 168 bytes (two 64-byte `DoubleVector`
  boxes and a 40-byte mask) plus, for the 62 vectors of the mixed data that hold a rare lane,
  160 bytes (two `toArray` copies and the returned box) sum to exactly the 52928 B/op measured;
  JFR's allocation-by-site named `fromArray0Template`, `blendTemplate`, `Mask.or` and `toArray`.
  On common data the branch never ran, C2 left it as an uncommon trap, and nothing boxed, which
  is why the allocation check, on common data only, never saw it. Written out in the body with
  the Vector API's own `toLong`, `lane` and `withLane`, which C2 inlines late instead of
  refusing, the finish allocates nothing at 256 and 512 bits; at `SPECIES_MAX` it still boxes
  (below). Any slow path called from a port's body belongs written out inline, as
  `Lanes.addExponent` is.
- **Two port bodies in one loop do not fit, whatever the order.** `ExpBenchmark`'s
  `finishPairBoxed256` calls `Exp.expRare` and `Exp.expCore` for two vectors per iteration. C2
  spent its budget inside the first `expCore`, after the two `expRare` calls: `Lanes.hiAbove`,
  `Lanes.hiBelow`, `Lanes.intSpecies` and then the second `expCore` were refused
  (`NodeCountInliningCutoff`), and the rows allocate even on common data, at the default flags
  and under `-XX:UseAVX=2`. The rule is in `opto/bytecodeInfo.cpp`: once the graph is over the
  cutoff (a develop flag, 18000 nodes, fixed in product JVMs), any callee larger than
  `MaxTrivialSize` (6 bytes of bytecode) is refused unless it is `@ForceInline`, and
  `@ForceInline` callees, the Vector API's own methods, are delayed to incremental inlining
  instead. That is why a single body's late Vector API calls survive while a vfdlibm call does
  not, why a 7-byte wrapper such as `expCore` is refused, and why `CompileCommand=inline` does not
  help. The consequence for callers: a kernel that inlines two vfdlibm calls can hit the same
  cutoff and box, so interleaving two vectors needs the body written out by the caller (a code
  generator, as Varka emits it), not two calls. The `*Boxed*` rows record this and
  `bench/alloc-check.py` leaves them out by name.
- **A forced inline changes the caller.** When a flag affects inlining, the method whose code
  moves is the one doing the calling; measure it, not only the callee.

## Species and vector width

- **Long-double conversions need AVX512DQ on x86.** Without it, C2 lowers no `D2L`, `L2D`, `L2F`
  or `F2L`; such a convert runs in Java and boxes. `PrintIntrinsics` says `not supported:
  op=cast#510/3 vlen2=4 etype2=double` (L2D) and `cast#512/3 vlen2=4 etype2=long` (D2L), on
  GitHub's EPYC 7763 runners and on the laptop under `-XX:UseAVX=2`. `D2I`, `I2D` and `I2L` are
  vector instructions on every AVX2 host. A port that needs an integer from a double goes
  through int lanes (below).
- **`VectorSpecies.of` is not a constant to C2.** It reads a table, so a conversion whose target
  species comes from it is not intrinsified (`missing constant ... vlen_to=LoadI`). Return one
  of the `SPECIES_*` constants, chosen by a switch on `vectorBitSize()`, as `Lanes.intSpecies`
  does; once inlined, C2 sees a constant. Not by identity: `SPECIES_MAX` is none of the named
  species, and exp at `SPECIES_MAX` boxed that way. The same holds for any species loaded from a
  local or a field C2 cannot fold. `VectorSpecies.of` stays only as the fallback for an unusual
  shape.
- **At `SPECIES_MAX`, lane access at a variable index boxes.** `exp`'s finish reads and writes its
  rare lanes with `x.lane(i)` and `result.withLane(i, v)`, `i` from the rare mask's bits. At the
  fixed shapes C2 inlines `VectorSupport.extract` and `insert` as intrinsics; at `SPECIES_MAX` (the
  same 512 or 256 bits on the laptop) `PrintIntrinsics` prints `missing constant: ...
  idx=CountTrailingZerosL` and both stay calls, so `finishMax` on mixed data allocates 30912 B/op at
  512 bits and 36992 under `-XX:UseAVX=2`, where `finish512` and `finish256` read under 0.01
  (`bench/results/exp-finish-laptop-2026-11-02.txt`). Common data never runs that loop and does not
  allocate. A port meant to run at `SPECIES_MAX` measures its slow path there too.
- **At two 64-bit lanes x86 C2 lowers no mask.** Compare to mask, blend, mask cast, mask
  broadcast, logic and test are `not supported` at `vlen=2` for double and long masks, and fine
  at four lanes. So on x86 every 128-bit double port that branches through masks runs per lane
  and allocates megabytes per op: the 128-bit rows of `bench/results/` are correctness runs, not
  the port's speed. Varka's operator census on GitHub's Arm runners (Neoverse N2, 128 bits,
  native) found the mask-producing compares it measured, `IS_NAN` among them, intrinsic there,
  so this is an x86 fact; check `PrintIntrinsics` on Arm before assuming either way for a new
  operation.
- **Simulating a host's width.** `-XX:MaxVectorSize=32` caps vectors at 256 bits but keeps
  AVX-512's instructions (and its `D2L`). To compile as on an AVX2 host such as the EPYC 7763,
  pass `-XX:UseAVX=2` (with `-XX:MaxVectorSize=32`, the preferred species is then 256 bits), and
  read `-prof gc` and `PrintIntrinsics` under it. GitHub's x86 runners are not all AVX2: some
  draws are AVX-512 hosts (below), whose CI job runs the 512-bit cases and prefers 512 bits, so
  `ci.yml` has a row pinned to `-XX:UseAVX=2 -XX:MaxVectorSize=32` that is AVX2 on every draw.
  `-XX:MaxVectorSize=16` runs everything at 128 bits, as the Arm runners do.
- **The laptop's AVX-512 is 256 bits wide.** The owner's laptop (AMD Ryzen AI 9 HX PRO 370,
  Zen 5 mobile) has the whole AVX-512 instruction set and HotSpot picks `MaxVectorSize=64`, but
  its datapath is 256 bits: an integer vector loop timed at 128, 256 and 512 bits reads a
  256:128 ratio of 2.02 and a 512:256 ratio of 1.14 in lanes per nanosecond. Its 512-bit rows
  are not what a native AVX-512 host would show. The method generalises: to learn whether a
  machine's widest vector is real, time three widths, not two, so the 128-to-256 step proves the
  probe can see a doubling; `lscpu` and `MaxVectorSize` report the instruction set, not the
  datapath. `bench/datapath.sh` is that probe, ported from Varka; every benchmark run records
  its reading in the results header.
- **Hosts measured in Varka.** 512:256 in lanes per nanosecond: AMD EPYC 7763 (Zen 3, AVX2, no
  AVX-512) 1.00; EPYC 9V74 (Zen 4, no AVX-512 exposed) 0.91 to 1.00; Intel Xeon Platinum 8573C
  and Xeon 6973P-C (full AVX-512) 1.33 to 1.36, from issuing 512-bit operations on two ports
  against 256-bit ones on three; EPYC 9V45 (Zen 5) 1.99. GitHub's x86 runners draw from all of
  these. `HARDWARE.md` holds the table, with sources, and vfdlibm's runs extend it.

## Where headline numbers come from: four named hosts

- **Headline numbers are measured on four named CPUs**, each drawn from GitHub's standard
  `ubuntu-latest` pool and each with results files of its own (owner's decision, 2 November
  2026, revised the same day): the AVX-512 hosts, the Intel Xeon Platinum 8573C (host
  `xeon8573c`), the Intel Xeon 6973P-C (Granite Rapids, `xeon6973p`) and the AMD EPYC 9V45
  (Zen 5, full-width AVX-512, `epyc9v45`); and the AMD EPYC 7763 (Zen 3, AVX2, `UseAVX=2`,
  `MaxVectorSize=32`, preferred species 256 bits, `epyc7763`). vfdlibm cares about AVX-512
  first, so the AVX-512 hosts are read first; each host decides for its own instruction set, and
  none speaks for another's. Laptop numbers never headline; laptop files say "laptop numbers, not
  headlines" in their header. `HARDWARE.md` lists the hosts' flags, JVM settings and ratios.
- **The EPYC 9V74 is not a host: its runners expose no AVX-512.** The three GitHub 9V74 VMs that
  Varka measured had no `avx512` flags and ran at `UseAVX=2`, `MaxVectorSize=32`, so a 9V74 file
  could only ever decide for Zen 4 at AVX2 width, which the EPYC 7763 already covers. It was
  proposed as a host on 2 November 2026 and dropped before any run. The lesson stands for any new
  host: read its flags in a run's log before naming it for an instruction set; every results
  header has an `AVX-512:` line (`avx512f=yes avx512dq=yes` or `no`) and a `Datapath:` line.
- **The EPYC 9V45 is recorded whenever drawn, and nobody waits for it.** It is the one
  full-width 512-bit datapath in the pool (512:256 of 1.99), and a scarce draw: one in eighteen
  in Varka's survey of 11 September 2026, though 7 of Varka's last 30 benchmark dispatches (31
  October to 2 November) and 15 of 109 option-matrix jobs on 28 October drew it. So
  `bench/dispatch-bench.sh --host epyc9v45` stops after 18 dispatches, an hourly try for about a
  day (at one in eighteen, a draw with a chance of about 64%; at the recent rates, almost
  surely), with exit status 3 if none drew it; `--attempts 1` is a single opportunistic try. A
  miss costs about a minute of runner time, since the CPU check is the job's first step. No
  decision waits on a 9V45 file; when one exists, it is read with the other AVX-512 hosts.
- **The benchmark fails on any other CPU, and is retried, never relaxed.**
  `.github/workflows/benchmark.yml` takes a `host` input with exactly four choices and maps each
  to a model-name string fixed in the file, as runs of that host printed it, matched ignoring
  case: `INTEL(R) XEON(R) PLATINUM 8573C` (Varka's fork runs 41465323581, 41255932044 and
  41241977271, in capitals as Azure printed it), `Intel(R) Xeon(R) 6973P-C` (vfdlibm run
  41442062308), `AMD EPYC 9V45 96-Core Processor` (Varka's fork runs 41486339971, 41446843041,
  41329377429) and `AMD EPYC 7763 64-Core Processor` (`bench/results/exp-epyc7763-2026-11-02.txt`).
  It checks `/proc/cpuinfo` before anything else and fails unless the model name ends with the
  host's string, for the two EPYCs, whose full names the runs printed, or contains it, for the
  two Xeons, whose full names no run has recorded;
  `bench/dispatch-bench.sh --host <host>` dispatches it and, when the run failed on that check,
  waits an hour and dispatches again, until a run passes, fails on something else, or (for the
  9V45, or with `--attempts`) the attempts run out (`bench/dispatch-epyc.sh` is the same with
  `--host epyc7763`). Other CPUs (the EPYC 9V74, the Xeon Platinum 8370C) are common draws, and
  accepting one would put a result on a different machine from every result before it. If a
  host stays scarce for days, changing the hosts is a decision for the owner, not a change to
  the check.
- **Before and after on one machine: a pair.** A change that should be faster, or simpler at no
  cost (one division instead of two in exp, say), is measured with `bench/pair.sh`, ported from
  Varka's `dev/varka_bench_pair.sh`: the base and the head built side by side, JMH run on each in
  alternating rounds (the order swapped every round), and per row the change by the median of
  the rounds and by the best iteration; a row is slower only when both are past the threshold
  (default 10%), and after three rounds the pair stops early once every row is within 3%. Two
  separate dispatches cannot do this, since each draws a new VM and a host's VMs differ by more
  than most changes. On a host: `bench/dispatch-bench.sh --host xeon8573c --base <base ref>
  <head ref> '<regex>'` (the workflow's `base` input). Narrow the regex: each round costs two
  full JMH runs of it. An A/A pair (`--base HEAD`) measures the machine's own spread.
- **A CPU check holds only for its own job.** Every GitHub-hosted job is a fresh VM, so a check
  in one job says nothing about another job's machine. The job that measures checks its own CPU
  and writes it into the results header.

## What the exp port taught

- **exp's k in int lanes.** fdlibm's `k = (int)(invln2*x + halF[xsb])` first went through `D2L`
  and `L2D`, which box on AVX2 (above). Through int lanes instead, `D2I`, `I2D` and the exponent
  add through `I2L`, every 256-bit arm allocates ~0 B/op under `-XX:UseAVX=2`, and on common
  data `blended256` fell from 11608 to 3690 ns and `finish256` from 10139 to 1939 ns per 1024
  doubles, by minimums (`bench/results/exp-laptop-avx2-2026-11-02.txt`). It is exact because
  the C's `k` is an `int` too: `D2I` truncates toward zero like the C cast and `I2D` is exact; a
  NaN gives 0 and a huge `|x|` saturates, but those are the filter's lanes, whose `k` is never
  used. The int vector has the same lane count at half the bits, so each conversion is part 0.
  Any port whose C casts a double to an `int`, or an `int` to a double, should do the same.
- **Per-lane finish against blend.** `exp` has both arms in one body, selected by a constant
  `boolean`, so they share every line but the rare paths and the unused arm folds away once inlined.
  The blended arm computes every branch of `e_exp.c` in every lane; the finish arm computes the
  common path and, when any lane is rare, sends those lanes to `StrictMath.exp`. On the EPYC 7763
  (`bench/results/exp-epyc7763-2026-11-02.txt`, minimums in ns per 1024 doubles at 256 bits), the
  finish arm as first merged was 1.57x faster than the blend on common data, 4203 against 6590, and
  lost once rare lanes were common: on mixed data (one rare input in 16, about a quarter of the
  4-lane vectors holding a rare lane) it was 1.64x slower, 12331 against 7512, while allocating
  52928 B/op. The allocation was the refused call above; with the finish written out inline it is
  gone at 256 and 512 bits, and on the laptop under `UseAVX=2` mixed data now reads 2591 against
  4639 for the blend, and 1925 against 3199 at 512 bits on the default flags, where the old method
  allocated 44032 B/op (`bench/results/exp-finish-laptop-2026-11-02.txt`, laptop numbers). Which arm
  `VFdlibm.exp` keeps is decided by the EPYC run of that change, not by the laptop. Each port
  measures both on common and mixed data, decides per branch, and records the decision in its pull
  request (`PLAN.md` section 5).
- **A blended alternative costs its whole form in every lane.** exp computed the C's near form
  and its `k == 0` form for every lane and blended them. Proving that the far form and the
  general tail already give those lanes' results (`PROOFS.md`, L1 and L2) left one form. With
  native AVX-512 at 512 bits the hot path went from 77 instructions to 52, two `vdivpd` to one,
  11 blends to 2 and 5 mask spills to none; at 256 bits under `-XX:UseAVX=2`, from 107 to 64,
  with 11 blends to 2, 25 constant broadcasts to 12 and 4 spills to none
  (`bench/asm/README.md`). Before blending two of the C's forms, look for a proof that one covers
  the other's lanes; count the result with `bench/asm/` before timing it.
- **Laptop first, then the EPYC.** The `D2L` boxing never showed under the laptop's default
  flags, where AVX512DQ is present; it showed only under `-XX:UseAVX=2`. Run a new port's
  benchmark under both before calling it done.

## What the log port taught

- **A whole int word pipeline lowers on AVX2.** log keeps the C's `int` words (`hx`, `k`, `i`,
  `j`) in int lanes of half the double vector's size: `L2I` narrows the high word in, the masks,
  shifts, adds and compares run as ints, an int mask is cast to a double mask of the same lane
  count, `I2L` writes the new high word back and `I2D` gives `dk`. Under `-XX:UseAVX=2` at four
  lanes `PrintIntrinsics` shows every `convert`, `compare`, `blend` and mask cast "late inline
  succeeded" and nothing refused, and both 256-bit arms allocate ~0 B/op
  (`bench/results/log-laptop-avx2-2026-11-02.txt`). So `L2I` and the int-to-double mask cast join
  `D2I`, `I2D` and `I2L` as safe on AVX2; only `D2L`, `L2D`, `L2F` and `F2L` are not.
- **The per-lane finish must not be a call that takes vectors.** Late in a large body C2 refuses
  to inline the finish (`Log::finishPerLane ... failed to inline: NodeCountInliningCutoff`, under
  `UseAVX=2`), and a refused call receives its vector arguments boxed. log's first finish took
  `(x, result, rare)` and, on mixed data at 256 bits under `UseAVX=2`, cost 9150 ns and 51648
  B/op per 1024 doubles against 3062 ns for the blend and 4104 for the scalar loop (2.2x slower
  than not vectorising). Passing arrays and `rare.toLong()` instead cut it to 3062 ns and 5184
  B/op. Doing the finish in the body with no helper, a loop over the set bits of `rare.toLong()`
  with `result.withLane(lane, StrictMath.log(x.lane(lane)))`, made it ~0 B/op and 2921 ns: the
  256- and 512-bit `lane` and `withLane` switch on the index to a constant lane, which C2
  intrinsifies (`extract`, `insert`), and the only call left is `StrictMath.log` on a double.
  exp's finish used to be such a call and boxed: 52928 B/op on mixed data on the EPYC 7763, where
  it cost 12331 ns against 7512 for the blend (`bench/results/exp-epyc7763-2026-11-02.txt`). The
  same in-body loop removed that at 256 and 512 bits; at `SPECIES_MAX` exp's finish still boxes
  on mixed data (above).
- **The laptop understated the boxed finish's cost on mixed data.** Under `UseAVX=2` on the
  laptop exp's boxed finish tied the blend on mixed data; on the EPYC 7763 it lost by 1.6x. Read
  each finish arm's `gc.alloc.rate.norm` on mixed data, where the rare lanes run, not only on
  common data: an in-body finish allocates nothing at 256 and 512 bits, so anything above zero
  there is boxing (or arrays the finish makes on purpose), and its cost grows on the EPYC.
- **The arm choice is per function, and a fix can reverse it.** With the boxed finish the data
  said to blend log; with the finish in registers it is ahead on common data, level on mixed at
  256 bits (1.18x and 1.05x under `UseAVX=2`), and the blend wins at 512 bits on mixed data (1.36x
  on the laptop's AVX-512, `bench/results/log-laptop-2026-11-02.txt`). The EPYC 7763 run
  (`bench/results/log-epyc7763-2026-11-02.txt`) kept the finish for `VFdlibm.log`: level with the
  blend on mixed data at 256 bits and allocating nothing, where exp's boxed finish had lost by
  1.6x. Measure the finish only once it allocates nothing.

## What the log1p port taught

- **The same int pipeline, with `hu` beside `hx`.** `log1p` keeps `hx`, `ax`, `hu` and `k` as
  int lanes the way log does (above), reading both high words with `Lanes.hiInt`. Under
  `-XX:UseAVX=2` every 256-bit arm allocates about 0.01 B/op on common and mixed data
  (`bench/results/log1p-laptop-avx2-2026-11-02.txt`). The C's signed compares, such as `hx <=
  (int)0xbfd2bec3` for a negative `x`, are then written as the C writes them.
- **A helper that inlines in one body can be refused in another.** log calls the int
  `Lanes.withHi` early in its body and stays unboxed. log1p writes `u`'s high word back after the
  reduction, and a call to the same helper there was refused (`Lanes::withHi (25 bytes) failed to
  inline: NodeCountInliningCutoff`, under `-XX:+PrintInlining`), and every arm then allocated in
  `bench/alloc-check.py`, the refused call taking its vectors boxed. The same `I2L`, shift and
  `or` written out in the body read ~0 B/op. `Lanes.hiInt`, called before the arithmetic, inlines
  in both. Run the allocation check after moving any code into a helper.
- **An invalid operation's NaN is not the hardware's.** For `x < -1` the C returns `(x-x)/(x-x)`.
  x86's `divpd` gives `0xfff8000000000000` there, and a negative NaN `x` comes back quieted with
  its payload, but `StrictMath.log1p` returns `Double.NaN`, `0x7ff8000000000000`, for every such
  lane, negative NaNs of any payload included; a positive NaN takes `x+x` and keeps its payload.
  Where the C makes a NaN by an invalid operation, check the oracle's bits on NaN inputs of both
  signs and several payloads (`Inputs.specials` has them) before computing it.
- **A third shape: blend the rare paths behind one test.** `log1p`'s rare paths (`x <= -1`,
  `|x| < 2^-29`, `+inf` and NaN, and `|f| < 2^-20`) are a few vector operations each. Computing
  and blending them only when a vector holds a rare lane (`if (rare.anyTrue())`) never allocates.
  Against the per-lane finish written in the body (below) and the blend of every lane, by
  minimums in ns per 1024 doubles over two runs each, laptop numbers: under `UseAVX=2` at 256
  bits the guarded and finish arms are level on common data (2770 and 2745) and on mixed data
  (3034 and 2952), and the blend's minimums are 3189 to 3264
  (`bench/results/log1p-laptop-avx2-2026-11-02.txt`). The laptop's AVX-512 build was measured the
  same way at 256 and 512 bits (`bench/results/log1p-laptop-2026-11-02.txt`). In both files the
  arms are within 1.3x of each other on every row, and no direction is read: `VFdlibm.log1p` uses
  the guarded arm provisionally. The EPYC 7763 run (`bench/results/log1p-epyc7763-2026-11-02.txt`)
  keeps them within 1.3x at 256 bits too, so the AVX-512 hosts decide (`HARDWARE.md`). A port whose
  rare paths are cheap in vector form should measure this shape beside the other two.
- **The in-body finish allocates nothing in log1p too.** log1p's finish was first a call,
  `finishPerLane(x, result, rare)`, and boxed: 52,928 B/op on mixed data at 256 bits under
  `UseAVX=2`, 2.0x slower than the guarded arm there (the superseded runs kept at the end of
  `bench/results/log1p-laptop-avx2-2026-11-02.txt`). Written out in the body as exp's and log's
  are, a loop over the set bits of `rare.toLong()` with `result.withLane(lane,
  StrictMath.log1p(x.lane(lane)))`, it reads ~0.01 B/op on mixed data at 256 and 512 bits, so
  nothing in it was refused in log1p's large body (the current runs in the same files). Its
  `SPECIES_MAX` form, which boxes in exp, is not measured. An earlier exploratory run had
  measured a `withLane` loop at 47,232 B/op (quoted in the same superseded runs); whether that
  loop sat in a helper taking vectors was not recorded, and it most likely measured that call's
  boxing, not `withLane`.
- **Possibly redundant, supported by sampling, unproved: the `hu == 0` path.** s_log1p.c's
  `|f| < 2^-20` path may give the same bits as its general polynomial path. For `f == 0` both
  paths reduce exactly to `k*ln2_hi + (k*ln2_lo + c)`. A sample of 199,804,321 `hu == 0` inputs
  with `1+x` between 0.5 and 2^1024 showed 0 bit differences against `StrictMath.log1p`; `x` in
  (-1, -0.5) was not sampled, and sampling is not proof. The port keeps the C's operations.

## The nightly sweep and fuzz

- **What runs.** `.github/workflows/nightly.yml` runs `Sweep` and `Fuzz` (in `src/test/java`,
  `main` programs, not tests) through `dev/nightly.sh`, one JVM per species, since a second
  species in one JVM makes the shared vector templates bimorphic (above). Each checks every arm
  (`VFdlibm`'s and the alternatives, `Exp.expCore+expRare` included) unmasked, and masked under
  one mask per vector drawn from the seed and the vector's position, against `StrictMath` by raw
  bits; all-true and all-false masks are the unit tests'. The sweep takes each of the 2^32 high
  words with low words 0, `0xffffffff` and `mix(seed + hi * 0x9e3779b97f4a7c15L)`, so any of its
  inputs replays from the seed alone. The fuzz takes random bits and the neighbourhoods of the
  high words, significand splits and doubles each function branches on (`Fuzz.java` lists
  them), for a fixed time.
- **What a night can miss.** Each input meets one mask per arm, so it is an active or an
  inactive lane with probability 1/2 each: a bug of a masked form that a single input shows is
  caught with probability about 1/2 per species and night, though a bug a band of inputs shows
  is caught all but surely. `Exp.expCore+expRare` catches `expRare` missing a lane, never
  marking too many: an extra lane is finished by `StrictMath` and matches. And taking every
  high-word branch is not seeing a moved split. The planted `log.thresholdMoved`, a split one
  step off on the high word, gave no mismatch on sweep stripe `255/256` and 2 on stripe `0/4`,
  both through the seed's low word, while the fuzz, which draws each split's neighbourhood,
  catches it within a second (`NightlyTest`'s fault runs). The sweep is for bands nothing
  samples; the fuzz is for splits.
- **A slice by hand.** After `mvn -B -q test-compile`, `dev/nightly.sh sweep exp 1023/4096 0x5eed
  256` sweeps the 2^20 high words of `[1, 2)` at 256 bits (a second or two);
  `dev/nightly.sh sweep log stripe:255/256 0x5eed` sweeps the stripe the sizing below used at
  every width the host has (`NIGHTLY_EXCEPT=128` leaves one out, as the nightly does);
  `dev/nightly.sh fuzz log1p 30 0x5eed` fuzzes for 30 seconds a species. A width the host lacks
  is named in the output (`512: not on this host`). `NIGHTLY_JVM_ARGS` passes JVM options,
  `-XX:UseAVX=2 -XX:MaxVectorSize=32` or a planted fault:
  `NIGHTLY_JVM_ARGS=-Dvfdlibm.fault=exp.dropCorrection dev/nightly.sh sweep exp 1023/4096 0x5eed
  256` must exit 1 and write the mismatches to `target/nightly/sweep-exp-256.txt`. That file is
  `Inputs.hardCases`'s form: each mismatch is a `# MISMATCH` line (function, species, seed, arm,
  masked and the mask, lane, input, expected and actual as raw hex) and then the input alone, so
  the inputs go into `src/test/resources/.../hard-cases/<function>.txt` as they are. Each is
  flushed as it is written, so a JVM killed by a timeout keeps what it found, without the
  closing `# total mismatches` line. A run stops after 100,000 mismatches. A replay finds the
  same mismatches but not the same file: the threads reach them in no fixed order, so which 200
  per arm and mask form are written, and a stopped run's total, vary. On the laptop, take the
  heavy lock and pin to one core type, for example
  `flock ~/proj/.varka-locks/heavy.lock taskset -c 0,1,12,13 dev/nightly.sh ...`.
- **Its cost, measured once (laptop, 2 November 2026).** Stripe `255/256` of each function, 2^24
  high words over 16 exponents of both signs, with 4 threads on two Zen 5 cores and their SMT
  siblings (`taskset -c 0,1,12,13`, the shape of a 4-vCPU runner), took in seconds at 128 / 256
  / 512 bits / max: exp 10.7 / 1.9 / 1.5 / 1.6, log 11.6 / 1.7 / 1.6 / 1.6, log1p 24.1 / 2.2 /
  1.8 / 1.9; stripe `0/256` read within 0.6 s of these. On another 2^24 sample, exp at 256 bits
  swept 4.7 million high words a second on one thread and 9.7 million on the four, 2.1 times as
  many. Times 256, the whole sweep at every width is 67 minutes for exp, 70 for log and 128 for
  log1p on those two laptop cores, 6 to 8 runner-hours a night on GitHub's x86 runners.
- **Why x86 sweeps 128 bits by a slice (Max, 2 November 2026).** The 128-bit species is 68 to
  80% of that cost: x86 C2 lowers no mask at two 64-bit lanes (above), so that width runs lane
  by lane in Java, and Varka declines 128-bit doubles on x86, so no user of the port runs it
  there. So on x86 the nightly sweeps every high word at 256 bits and max (and 512 on an AVX-512
  draw), and at 128 bits a rotating slice: on night n of 32, stripe `n*shards + j` of
  `32*shards` in shard j's job, every high word in 32 nights. 1/32 keeps the slice near a tenth
  of the x86 sweep while covering it every month; the 128-bit logic itself is swept whole every
  night on Arm, natively, so what the slice alone guards is x86's per-lane fallback. The fuzz
  still runs at 128 bits on x86 every night. The dispatch inputs `slice-nights` and
  `slice-night` change or replay the rotation; `nights` and `shards` do the same for the rest.
- **The Arm job** sweeps every high word at 128 bits every night, in 4 stripes per function
  (`arm-shards`), the same rotating 1/32 slice at max (128 bits wide on Arm too, a second
  species of the same width), and fuzzes both species once per function, in shard 0's job. Its
  time is not measured yet. It is sized by the laptop's x86 128-bit time as an upper bound: that
  path runs per lane, where Arm lowers the masks natively, and the bound assumes a Neoverse N2
  core is not slower than a Zen 5 core with its SMT sibling, which nobody has measured. Under it
  a log1p stripe sweeps for 26 minutes and an exp or log stripe for 12, plus the slice (under 2)
  and 8 minutes of fuzz in shard 0 (30 at the 900-second cap), against a 120-minute timeout.
- **The projection, in runner-hours a night (the first nights' logs, which print each species'
  time, replace it).** From the laptop numbers, with GitHub's cores taken as 1.5 to 2 times
  slower (not measured), the x86 sweep is 16, 16 and 21 laptop-minutes for exp, log and log1p
  (256 bits, max and the 128 slice), 79 to 105 runner-minutes in all, plus 31 to 42 on an
  AVX-512 draw; 2 jobs per function of 12 to 30 minutes. That is 2 shards, not 4: the jobs got
  short, and each pays its setup and build (about 2 minutes) and is billed by the started
  minute. The fuzz is unchanged: 6 jobs of 240 seconds times 3 or 4 species, 72 to 84 minutes.
  The proofs and every job's setup add about half an hour. So x86 is 1.3 to 1.8 runner-hours of
  sweep (6 to 8 before) and 3 to 3.7 with fuzz and setup. Arm is at most 4.2 under the bound
  above (198 minutes of sweep, 6 of slice, 24 of fuzz, 24 of setup for its 12 jobs) and, if its
  native 128 bits costs twice x86's 256 bits per vector (half the lanes) on cores 1.5 to 2 times
  slower than the laptop's, 2 to 2.5. The target is 2 to 3 runner-hours a night:
  the sweep is within it; the whole night is above it until the logs say otherwise, mostly from
  the fuzz, which this schedule does not change.
- **When a night fails,** the `alert` job (the only one with `issues: write`) opens an issue
  labelled `nightly-failure`, or comments on the open one, with the run's link, the seed, each
  job's result, the dispatch inputs that replay the run (seed, `nights`, `shards`, `arm-shards`,
  `night`, `slice-nights`, `slice-night`, `fuzz-seconds`), and for each mismatch file its replay
  and JVM arguments (an as-AVX2 fuzz replays only with `-XX:UseAVX=2 -XX:MaxVectorSize=32`), its
  total (or, when a killed JVM wrote none, the count it wrote) and its first mismatches; the
  artifacts `nightly-*` hold the rest. A dispatch with those inputs replays every job, Arm's
  too. That the job's `issues: write` and its `gh` calls work is verified by the first real
  failure, not before.
