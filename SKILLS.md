# What vfdlibm has learned

Lessons about benchmarking, the JIT and the Vector API that every port follows. Most were learned
in Varka (github.com/vecbricks/varka, its `sql/varka/skills/`) and are rewritten here for a vector
math library; the last sections are what the `exp`, `log`, `log1p`, `log10` and `expm1` ports
taught. Each entry says where its numbers come from. A new lesson goes in the section it belongs
to, as a short entry; a negative result belongs here too, since it stops the next port re-trying
a settled question.

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
- **CI checks it.** `bench/alloc-check.py` runs every method named `*256` or `*Preferred` of a
  class named `*Benchmark`, on `inputs=common` and `mixed`, one fork under `-prof gc` with the
  body inlined, and fails if any measured iteration reaches 1 B/op; the `alloc` job of `ci.yml`
  runs it at the x86 runner's flags, under `-XX:UseAVX=2`, and on Arm (below). On the laptop,
  under load, C2 finished exp's body within the first one-second iteration and later ones read
  about 0.02 B/op, so three warmup seconds leave two to spare; a row that fails only in its
  first iteration means C2 was late. `ExpKindsBench` is left out by its class name: its rows
  call the bodies `ExpBenchmark`'s rows call; `--include` and `--inputs` check it on its own
  inputs values.
  Left out: 128 bits (masks box at two lanes on x86, below) and 512 bits (software on AVX2; the
  preferred arm covers it where it is real). Mixed inputs were left out too until the per-lane
  finish stopped allocating (below); common data alone never runs a rare path, so it could not
  see that path box. The `*Max` rows are left out by the regex, since the finish still boxes at
  `SPECIES_MAX` on mixed data (below).
- **On Arm the check runs the preferred rows, and they pass.** GitHub's Arm runners have 128-bit
  vectors (`MaxVectorSize=16 UseSVE=2` on all 332 Arm jobs of `HARDWARE.md`, "What the pool
  gives"), so the preferred species is 128 bits there and a `*256` row would run in the Java
  fallback, as 512 bits does on an AVX2 host. Run once on such a runner, all 24 `*Preferred`
  rows of the five ports, every arm on common and on mixed data, read 0.04 to 0.10 B/op at the
  worst of three iterations (`bench/results/alloc-arm-2026-11-20.txt`). So at two 64-bit lanes
  the bodies run unboxed on Arm, where on x86 every masked one boxes (below); that is read from
  the allocation alone, and no `PrintIntrinsics` line was read there. `ci.yml`'s `alloc` job
  has that row since, as `bench/alloc-check.py --preferred-only`. The file is an allocation
  reading, not a timing: no Arm host is a benchmark host, and nothing here says how fast those
  bodies are.
- **When the check fails, ask it why.** `bench/alloc-check.py --why` reads the failed run's
  result and runs each failing row once more, under the JVM options the result records for it,
  with the two flags of the next entries and C2 alone compiling (`-XX:-TieredCompilation`:
  `PrintInlining` prints for every tier, and C1's refusals, which box nothing, outnumber C2's;
  it passes back the `InlineSmallCode` the JVM has with the tiers on, 2500 against 1000 on the
  laptop's JDK 25, since that limit of C2's inlining drops with them).
  It prints each distinct `PrintIntrinsics` line with its count, then the refused calls that
  name the library, `NodeCountInliningCutoff` or a refused intrinsic, then the other refused
  calls into the Vector API. CI runs it after a failing check, so the job's log holds C2's
  account.
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
- **Every port's finish boxes at `SPECIES_MAX`, Log's counted loop too; constant lane indices do
  not.** With `*Max` rows in all five benchmarks, read on the laptop under `-prof gc`
  (`bench/results/max-finish-laptop-2026-11-20.txt`, allocation readings on a loaded machine,
  no timings): on mixed data `finishMax` allocates 30912 B/op at 512 bits for exp, expm1 and
  log1p and 30432 for log and log10, and 36992 and 36672 under `-XX:UseAVX=2`; on common data
  it allocates nothing, and neither do the blended rows (log has none) or the guarded rows
  of log1p and expm1, on either data. Log's finish is a counted loop over `species.length()` that
  tests each bit where the others walk the set bits with `numberOfTrailingZeros`; it boxes the
  same, so the loop's shape is not the cause, the lane index not being a constant is. Tried and
  not merged: the first eight lanes written out with literal indices, `if (lanes > 0 &&
  (rareLanes & 1L) != 0) result = result.withLane(0, f(x.lane(0)));` and so on, the loop
  kept for lanes past eight. With it every `*Max` row of every port read under 0.5 B/op, on
  mixed data and under both flag sets, and the five ports' tests passed. Owed before a port
  takes it: the `*256` and `*Preferred` rows through `bench/alloc-check.py` on a quiet machine
  (under load they ended under 0.4 B/op, with first iterations where C2 was late, which the
  check counts as failures), `mvn verify` with the planted faults, and a pair on a host, since
  the fixed shapes' finish changes too.
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
  Three runs were too few to describe the pool: of the 9V74's 104 draws in the nightly's
  jobs of 3 to 19 November, 98 expose AVX-512 and run at `UseAVX=3`, their 512-bit lanes at
  0.90 to 0.91 of the 256-bit rate (`HARDWARE.md`, "What the pool gives"). Whether that makes it
  a host again is the owner's decision.
- **The EPYC 9V45 is recorded whenever drawn, and nobody waits for it.** It is the one
  full-width 512-bit datapath in the pool (512:256 of 1.99), and a scarce draw: one in eighteen
  in Varka's survey of 11 September 2026, though 7 of Varka's last 30 benchmark dispatches (31
  October to 2 November) and 15 of 109 option-matrix jobs on 28 October drew it. So
  `bench/dispatch-bench.sh --host epyc9v45` stops after 18 dispatches, an hourly try for about a
  day (at one in eighteen, a draw with a chance of about 64%; at the recent rates, almost
  surely), with exit status 3 if none drew it (1 if the last try failed before the CPU check);
  `--attempts 1` is a single opportunistic try. The CPU check comes first in the job,
  after only the fetch of its script and that script's own test; what a miss costs on the
  current workflow has not been measured. No decision waits on a 9V45 file; when
  one exists, it is read with the other AVX-512 hosts.
- **The benchmark fails on any other CPU, and is retried, never relaxed.**
  `.github/workflows/benchmark.yml` takes a `host` input with exactly four choices and maps each
  to a model-name string fixed in `bench/host.sh`'s table (the workflow's probe step runs
  `bench/host.sh --expect <host>`, and CI runs `bench/host.sh --self-test`, which classifies
  every kind of machine in the pool's census), as runs of that host printed it, matched ignoring
  case: `INTEL(R) XEON(R) PLATINUM 8573C` (Varka's fork runs 41465323581, 41255932044 and
  41241977271, in capitals as Azure printed it), `Intel(R) Xeon(R) 6973P-C` (vfdlibm run
  41442062308), `AMD EPYC 9V45 96-Core Processor` (Varka's fork runs 41486339971, 41446843041,
  41329377429) and `AMD EPYC 7763 64-Core Processor` (`bench/results/exp-epyc7763-2026-11-02.txt`).
  It checks `/proc/cpuinfo` before anything but the fetch of that script and a step that runs
  the script's self-test and looks for the host's key in it, and fails unless the
  model name ends with the host's string, for the two EPYCs, whose full names the runs printed, or
  contains it, for the two Xeons, whose full names no run had recorded then (the pool's census,
  `bench/pool/`, has since printed both as whole names);
  `bench/dispatch-bench.sh --host <host>` dispatches it and, when the run failed on that check,
  waits an hour and dispatches again, until a run passes, fails on something else, or (for the
  9V45, or with `--attempts`) the attempts run out (`bench/dispatch-epyc.sh` is the same with
  `--host epyc7763`). A run that failed while fetching the script, before any step looked at
  the machine, is tried again too, each counting as an attempt; the third in a row stops it.
  Other CPUs (the EPYC 9V74, the Xeon Platinum 8370C) are common draws, and
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
  the other's lanes; count the result with `bench/asm/` before timing it. Timed as a pair
  against its base on the Xeon Platinum 8573C
  (`bench/results/exp-onediv-xeon8573c-2026-11-03.txt`, five rounds a side), the folded body is
  1.33x to 1.48x faster by the median on common data at 256 and 512 bits and 1.18x to 1.31x on
  mixed data, and no vector row is slower.
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

- **Int words can live in long lanes, with an exact dk.** log first kept its words in int lanes
  for the `I2D`; moving them to the long lanes of the bits drops `L2I`, `I2L`, `I2D` and the
  int-to-double mask casts, and `dk` comes from `fromBits(k + bits(0x1.8p52)) - 0x1.8p52`,
  exact for `|k| < 2^51` (`PROOFS.md`, L7, L8). With AVX-512 at 512 bits the long form tied the
  int form by count (89 each: three fewer operations, three more constant reloads), and 88 with
  the range compares as one biased signed compare of the bits (L9). Under `UseAVX=2` there is
  no 64-bit arithmetic shift, so a long `ASHR` costs three instructions: the count rose from 116
  to 123 until the shifts were `LSHR`, equal where the value is non-negative, as `hx` and `i`
  are; then 112, and 110 with the biased compares (`bench/asm/README.md`). Count both widths
  before choosing a lane type.
- **A `k == 0` form is the `k != 0` form with `dk = +0`.** fdlibm writes several returns twice,
  as `f - B` for `k == 0` and `dk*ln2_hi - ((B - dk*ln2_lo) - f)` otherwise; with `dk = +0` the
  second gives the first's bits whenever `f` is not `-0` (`PROOFS.md`, L6), which removed log's
  `k == 0` compare and four blends. Look for the hinge in signed zeros: the one counter-case is
  `f = -0, B = +0`.

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
  keeps them within 1.3x at 256 bits too, so the AVX-512 hosts decide (`HARDWARE.md`). The first,
  the Xeon Platinum 8573C (`bench/results/log1p-xeon8573c-2026-11-03.txt`), keeps the arms within
  1.3x on every row too; the guarded arm has the lowest minimum on each, from one run, so no
  direction is read yet, and a second 8573C run and the Xeon 6973P-C run are owed. A port whose
  rare paths are cheap in vector form should measure this shape beside the other two.
- **The in-body finish allocates nothing in log1p too.** log1p's finish was first a call,
  `finishPerLane(x, result, rare)`, and boxed: 52,928 B/op on mixed data at 256 bits under
  `UseAVX=2`, 2.0x slower than the guarded arm there (the superseded runs kept at the end of
  `bench/results/log1p-laptop-avx2-2026-11-02.txt`). Written out in the body as exp's and log's
  are, a loop over the set bits of `rare.toLong()` with `result.withLane(lane,
  StrictMath.log1p(x.lane(lane)))`, it reads ~0.01 B/op on mixed data at 256 and 512 bits, so
  nothing in it was refused in log1p's large body (the current runs in the same files). Its
  `SPECIES_MAX` form boxes as exp's does: 30912 B/op on mixed data at 512 bits and 36993 under
  `-XX:UseAVX=2` (`bench/results/max-finish-laptop-2026-11-20.txt`). An earlier exploratory run had
  measured a `withLane` loop at 47,232 B/op (quoted in the same superseded runs); whether that
  loop sat in a helper taking vectors was not recorded, and it most likely measured that call's
  boxing, not `withLane`.
- **The `hu == 0` path is not redundant: a sample said it might be, every input of a window
  found the witnesses.** s_log1p.c's `|f| < 2^-20` path stops its series at `f^3/3` (line 156),
  and a sample of 199,804,321 `hu == 0` inputs with `1+x` between 0.5 and 2^1024 showed 0 bit
  differences against `StrictMath.log1p`. The C sent down its general polynomial path instead
  differs from the C as written all the same, on about one input in 2^29 of the windows
  searched: 56 of the 2^35 doubles with `1+x` in `[2 - 3*2^-20, 2 + 2^-19)` (`k = 1`) and 20 of
  the 15,032,385,536 with `1+x` in `(0.5 - 3*2^-22, 0.5 + 2^-21]` (`k = -1`); at each the C as
  written is `StrictMath.log1p` and the general path gives the next double. The counts, the
  hosts and the commands are in `bench/results/log1p-hu0-search-laptop-2026-11-20.txt`, four
  of the witnesses in `Log1pTest.shortPathIsNotTheGeneralPath`. The other windows (one for each
  power of two of `1+x`) are not searched. A sample of 2^27.6 inputs was not expected to show
  a rate of 2^-29, so its silence said nothing; what settles such a question is every input of
  a window, which the scalar transcription `Log1pTest.cLog1p` and the program `Log1pShortPath`
  make cheap: 2^35 doubles took 207 s on 8 threads. The port keeps the C's operations, and no
  lemma folds this path (`PROOFS.md`, L12).

## What the log10 port taught

- **A port that computes through another port nests both bodies in one graph.** e_log10.c
  rescales `x` into `[0.5, 2)` and calls `__ieee754_log`, so `Log10` calls `Log.logCore`, log's
  common path alone, as soon as the rescaled `x` exists. That was not enough: with the body
  inlined, on the laptop's default flags, `PrintInlining` showed `Lanes::withHi (25 bytes) failed
  to inline: NodeCountInliningCutoff` at bytecode 180 of Log's body, the high-word write-back
  that log alone inlines, and `Log10Benchmark.blended256` allocated 45056 B/op on common data
  (176 bytes per 4-lane vector). Moving every operation not needed for the argument after the
  call did not save it. Writing `Lanes.withHi` out, in Log's body and in Log10's (an `I2L`, a
  shift, an `and` and an `or`, as log1p's is), did: 0.024 B/op. A body meant to be nested inside
  another port makes no vfdlibm call past its first lines; a `Lanes` helper above
  `MaxTrivialSize` (6 bytes) there is written out. The allocation check covers the nesting, since
  it runs the outer port's rows; a change to the inner body is checked through both.
- **A planted threshold must sit where the result changes.** Moving log10's `hx < 0x00100000`
  test up by a word is invisible to both arms: the finish arm sends the extra normal lanes to
  `StrictMath.log10`, and the blended arm scales them by `2^54` and subtracts 54 from `k`, which
  rescales to the same `x` and the same `y`. So `log10.thresholdMoved` moves line 85's split
  from `k < 0` to `k < 1` instead: the lanes of `[1, 2)` are rescaled to `[0.5, 1)` with `y = 1`,
  where the sum cancels, and the suite and the fuzz catch it at every width. Before planting a
  threshold, check that some lane's result depends on it.
- **Moved down, the same test does change results, but only far from the edge.** At
  `hx < 0x000fffff` the subnormals of high word 0x000fffff are rescaled as if normal, and
  `0x000fffff00000000` gives `0xc0733a71472ec139` for `0xc0733a7147665832`. Near the largest
  subnormal the wrong rescale rounds to the same double, so the 64 neighbours of
  `fromHi(0x00100000)` passed with the fault planted in both arms. `Log10Test.thresholds()` now
  adds high word 0x000fffff with low words 0, 1 and seeded random ones, and
  `log10.subnormalThresholdMoved` plants the move in both arms (the finish arm's `common` test
  and the blended arm's subnormal mask), caught at every width. A threshold's neighbours by ulp
  are not its neighbours by word: test the far end of the word on the other side too.
- **The invalid operation's NaN is the hardware's here, as in log.** For a negative `x`, -inf
  and a negative NaN of any payload, `StrictMath.log10` returns the bits of `(x-x)/zero`, so the
  blended arm keeps the C's expression, unlike log1p (above).

## What the expm1 port taught

- **A reduction shared with another port inherits its proofs through a pinned hinge.**
  s_expm1.c reduces exactly as e_exp.c does, with the same `invln2`, `ln2_hi`, `ln2_lo` and band
  words, so exp's far-form fold (L2) and its sign-bit `halF` (L5) hold for expm1 once a test
  pins the five values to exp's (`PROOFS.md`, L10, H13), and the SMT files rendered from `Exp`
  check those steps for expm1 too; `proofs/expm1_reduction.smt2` holds the values to exp's
  under the solvers as well, and checks the unreduced lanes' step (`c = +0`) and, at four
  doubles, that the formula's `k` would be wrong there; the forcing over the whole word is the
  port's blend and a hinge test. The forcing still needs its own
  witness: the C reduced with the formula's `k` first differs from `StrictMath.expm1` at
  `0x3fd62e42_ffffffec`, and for negative x at `0xbfd62e42_fffffff3`, not at the word's top
  double, so a test of the top alone would pass without the forcing.
- **A scalar transcription of the C in the test sources pays for itself.** `Expm1Test.cExpm1`
  is s_expm1.c line by line in Java, checked against `StrictMath.expm1`; with one switch it is
  the C reduced without the forcing, which finds the hinge witnesses. An earlier scratch
  transcription of the same C, with a switch per candidate fault, sized each fault before it
  was planted (below).
- **A planted fault must change results, measured before it is wired.** On 2^22 scalar draws
  each, with the transcription edited: `Q1` one ulp up changed 8 results in (-3, 3), so
  `expm1.constantOffByOneUlp` moves `ln2_hi` instead (2782337 of the same draws); line 203's
  `k < 20` moved to `k < 21` changed 3 results in (19.4, 20.6) ln2 and to `k < 19` none, so
  `expm1.thresholdMoved` moves line 197's `k > 56` to `k > 55` (178903 of 2^22 draws in (55.4,
  57.6) ln2). The guarded arm's fault leaves out the filter's finite lanes: far past either limit
  `k` saturates and the common path returns garbage there (`Expm1Test.loneFilterInputs`, with
  `-1e300` and `1e300`).
- **Seven return forms, one exponent add.** Lines 198 and 205 are `t - (e-x)` with `t` = one or
  `1-2^-k`, and line 208 needs `2^-k`: one int word per lane, picked by the masks of `k` and
  widened once, gives all three `t`, and the three exponent adds are one long add of `k << 52`.
  Each form is computed in every lane and blended, in the int pipeline of log1p (`L2I`, `D2I`,
  `I2D`, `I2L` and int-lane shifts by `k`); every arm passes `bench/alloc-check.py` (no
  measured iteration at 1 B/op, above) at 256 bits and the preferred species, on common and
  mixed data, with the laptop's default flags and under `-XX:UseAVX=2 -XX:MaxVectorSize=32`; the
  two runner files record `gc.alloc.rate.norm`: no vector row on the native species reads over
  0.035 B/op.
- **expm1 on the benchmark hosts, 4 November 2026.** On the Xeon Platinum 8573C
  (`bench/results/expm1-xeon8573c-2026-11-04.txt`) the guarded arm has the lowest minimum on the
  preferred rows, 5% ahead of finish on common data and 3% ahead of blended on mixed; on the EPYC
  7763 (`bench/results/expm1-epyc7763-2026-11-04.txt`) finish is ahead of guarded, by 14% and 8%.
  Every arm is within 1.3x of the others on every native row of both, from one run each, so no
  direction is read and `VFdlibm.expm1` keeps the guarded arm; a second 8573C run and the Xeon
  6973P-C run are owed. The EPYC file's 512-bit rows run in the Java fallback and measure no
  expm1.
- **Lefevre's direction bit is the 54th bit of the exact |result|.** testlibm-data's last field
  agreed with that for all 7578 `em1` lines (3836 ones, 3742 zeros), not with "the exact
  result is above the rounded one", which splits about evenly.

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
  `dev/nightly.sh sweep log stripe:255/256 0x5eed` sweeps a stripe of 2^24 high words at
  every width the host has (`NIGHTLY_EXCEPT=128` leaves one out, as the nightly does);
  `dev/nightly.sh fuzz log1p 30 0x5eed` fuzzes for 30 seconds a species. A width the host lacks
  is named in the output (`512: not on this host`). `NIGHTLY_JVM_ARGS` passes JVM options,
  `-XX:UseAVX=2 -XX:MaxVectorSize=32` or a planted fault:
  `NIGHTLY_JVM_ARGS=-Dvfdlibm.fault=exp.dropCorrection dev/nightly.sh sweep exp 1023/4096 0x5eed
  256` must exit 1 and write the mismatches to `target/nightly/sweep-exp-256.txt`; it sweeps the
  same 2^20 high words of `[1, 2)` as above, each with `Sweep`'s three low words, so 3 * 2^20
  inputs per arm, at 256 bits only (no committed record gives its mismatch count). That file is
  `Inputs.hardCases`'s form: each mismatch is a `# MISMATCH` line (function, species, seed, arm,
  masked and the mask, lane, input, expected and actual as raw hex) and then the input alone, so
  the inputs go into `src/test/resources/.../hard-cases/<function>.txt` as they are. Each is
  flushed as it is written, so a JVM killed by a timeout keeps what it found, without the
  closing `# total mismatches` line. A run stops after 100,000 mismatches. A replay finds the
  same mismatches but not the same file: the threads reach them in no fixed order, so which 200
  per arm and mask form are written, and a stopped run's total, vary. On the laptop, take the
  heavy lock and pin to one core type, for example
  `flock ~/proj/.varka-locks/heavy.lock taskset -c 0,1,12,13 dev/nightly.sh ...`.
- **Its cost, measured (the logs of 3 to 19 November 2026).** The first sizing was a laptop's,
  from a sample of high words, before any night had run; the nights' own logs replaced it.
  `bench/pool/pool.py cost --since 2026-11-05
  bench/pool/nightly-cost-2026-11-03-to-2026-11-19.tsv` prints the tables the figures here are
  read or summed from (`bench/pool/README.md`, "What a night costs"); a sum is of its rows.
  Those are the 15 nights of five functions, from the night expm1 joined; the two nights
  before them ran four, at 272 and 285 runner-minutes.
  The figures are what the nightly's jobs took on whatever machine each drew, to size the night
  with, and no comparison of one body with another.
  - A night costs 347 to 372 runner-minutes, 5.8 to 6.2 runner-hours. By the median of each
    column, the 10 x86 sweep jobs take 119 minutes, the 10 fuzz jobs 138 and the 20 Arm jobs
    105; the plan and proofs jobs add at most one. The night's span, from its first job's start
    to its last job's end, is 54 minutes by the median and 77 at most.
  - By what the jobs do, as means: fuzzing is 172.5 minutes (132.5 in the fuzz jobs, 40.0 in
    the Arm jobs), sweeping 167.7 (112.9 on x86, 54.8 on Arm) and setup, build and the machine's
    probe 21, about half a minute a job. The fuzz is the largest part, and it is exactly what
    `fuzz-seconds` sets: a species costs those seconds.
  - By function, a night costs 61 minutes for log, 68 for exp and for log10, 79 for log1p and
    84 for expm1, 72 on average.
  - A sweep job takes 10.9 minutes by the median and 19.8 at most, a fuzz job 12.6 and 17.0, an
    Arm job 3.8 and 12.1 (the first shard of a function, which also fuzzes).
  - One runner sweeping every high word of a function takes, by the median, at 256 bits on a
    256-bit x86 machine, 6.5 minutes for log, 8.2 for log10, 9.3 for exp, 12.5 for expm1 and
    13.2 for log1p; at 128 bits on the same machine 67.5, 88.2, 80.7, 262.1 and 199.5; and at
    128 bits on Arm, natively, 7.2, 9.5, 9.7, 13.4 and 12.7. An x86 draw with 512-bit vectors
    is faster and its range wide (exp at 512 bits: 3.5 to 7.1 minutes), and `--by-cpu` splits
    the draws.
- **Why x86 sweeps 128 bits by a slice (Max, 2 November 2026).** The 128-bit species is 80 to
  91% of a whole sweep's cost on a 256-bit x86 machine (the medians above: exp 80.7 of 100.2
  minutes, expm1 262.1 of 288.0): x86 C2 lowers no mask at two 64-bit lanes (above), so that
  width runs lane by lane in Java, and Varka declines 128-bit doubles on x86, so no user of the
  port runs it there. So on x86 the nightly sweeps every high word at 256 bits and max (and 512
  on an AVX-512 draw), and at 128 bits a rotating slice: on night n of 32, stripe `n*shards + j`
  of `32*shards` in shard j's job, every high word in 32 nights. At 1/32 the slice costs 17.9
  minutes a night, 16% of the x86 sweep jobs' sweeping, while covering it every month; the
  128-bit logic itself is swept whole every night on Arm, natively, so what the slice alone
  guards is x86's per-lane fallback. The fuzz still runs at 128 bits on x86 every night. The
  dispatch inputs `slice-nights` and `slice-night` change or replay the rotation; `nights` and
  `shards` do the same for the rest.
- **The Arm job** sweeps every high word at 128 bits every night, in 4 stripes per function
  (`arm-shards`), the same rotating 1/32 slice at max (128 bits wide on Arm too, a second
  species of the same width), and fuzzes both species once per function, in shard 0's job. It
  costs 105 minutes a night: 52.8 of the whole sweep, 2.0 of the slice, 40.0 of fuzz and 10.5 of
  setup, over 20 jobs. Arm lowers the masks natively, so its 128 bits costs about what x86's
  256 bits does (log1p 12.7 minutes a whole sweep against 13.2, exp 9.7 against 9.3). A stripe
  of 4 is 1.8 to 3.4 minutes of sweep, and no job of the 15 nights took more than 12.1.
- **The budget (20 November 2026, from the table above).**
  - **What it protects.** The repository is public, so GitHub's standard runners, Arm's too
    (its changelog of 7 August 2025), cost no money, and a night's runner-hours are not the
    limit. The limits are the 20 jobs the free plan runs at once, shared by the night, CI and
    the benchmark dispatches, and the night's wall time. So the budget is two numbers: **at
    most 16 jobs of the night at once (the matrices' `max-parallel` 15, and the proofs), and a
    median span of at most 90 minutes**. It replaces the earlier target of 2 to 3
    runner-hours a night, which the five functions overran at 6 and which would not have been
    kept at 13.
  - **How the span follows the schedule.** A kind of job (sweep, fuzz, Arm) runs on its
    `max-parallel` slots, and the span is its slowest kind's minutes over its slots, plus 8
    minutes (the night's 54 against the fuzz's 138 over 3 slots, 46). At 13 functions of the
    average 72 minutes, that is 13/5 of the night's medians (sweep 309, fuzz 359, Arm 273: 941
    runner-minutes, 15.7 hours; 13 times 72 is 936), the old slots 8, 3 and 4 give 120 minutes
    of fuzz and a span of 128. Slots 5, 6 and 4, the best split of 15 by this model, give 62,
    60 and 68, a span of 76 (and 34 for five functions, from 24, 23 and 26). The model is
    linear and counts no whole waves of jobs, so 76 is a lower bound; the sweep's 62, plus its
    longest job, 19.8, plus the 8 minutes, gives 90 as the upper. The 8 minutes are one fit
    (54 against 46). So the defaults keep `fuzz-seconds` 240, `nights` 1, `shards` 2
    and `arm-shards` 4, and `max-parallel` is 5 for the sweep, 6 for the fuzz and 4 for Arm. At
    13 functions the span is within 90 by the upper bound, with no room above it: each further
    average function adds about 5 minutes (Arm's 273 over 13 functions and 4 slots).
  - **What gives when it does not fit,** cheapest first, with what each buys at 13 functions
    (the table's means times 2.6): `fuzz-seconds` 120 saves 224 runner-minutes a night (172 in
    the fuzz jobs, 52 in Arm's) and halves what a species is fuzzed; `nights` 2 saves 192, 124
    of them on x86, and sweeps every high word every second night; `slice-nights` 64 saves 26;
    `arm-shards` 2 saves 14 and buys nothing, since a job's setup is half a minute, so the slots
    and not the shards bound the span. Which of these to give up first is the owner's.
  - **Not in it.** The two-argument functions (#33, #34) need another sweep and a cost of their
    own, measured before they join; 13 times 72 assumes none. The prediction of 34 minutes for
    five functions under the new slots is checked by the first nights after this change.
- **When a night fails,** the `alert` job (the only one with `issues: write`) opens an issue
  labelled `nightly-failure`, or comments on the open one, with the run's link, the seed, each
  job's result, the dispatch inputs that replay the run (seed, `nights`, `shards`, `arm-shards`,
  `night`, `slice-nights`, `slice-night`, `fuzz-seconds`, and `functions` when the run checked
  fewer than every function, and `fault` when one was planted), and for each mismatch file its
  replay and JVM arguments (an as-AVX2 fuzz replays only with `-XX:UseAVX=2
  -XX:MaxVectorSize=32`), its total (or, when a killed JVM wrote none, the count it wrote) and
  its first mismatches; the artifacts `nightly-*` hold the rest. A dispatch with those inputs
  replays every job, Arm's too. That the job's `issues: write` and its `gh` calls work has not
  been verified: no night has failed, and no fault has been planted in one (next entry).
- **The functions are named once,** in the plan job's `all`, from which it makes the sweep's, the
  fuzz's and Arm's matrices, the only list they read; a new port joins the night there, once
  `Sweep`, `Fuzz` and `Nightly` take it, and the workflow's header and `dev/nightly.sh`'s usage
  name it too. The dispatch input `functions` (names separated by commas) narrows a run to some
  of them, and `fault` plants one of the ports' faults, as `-Dvfdlibm.fault=<name>` in every
  sweep and fuzz JVM, so a dispatch can show the night catching it and the alert job at work
  without a real failure: `gh workflow run nightly.yml -f fault=exp.dropCorrection -f
  fuzz-seconds=5`. A fault that changes most inputs, as every `dropCorrection` and
  `constantOffByOneUlp` does, ends each JVM early; a narrow one (the `thresholdMoved` ones,
  `log10.subnormalThresholdMoved`, the `guardMisses` ones, for example) lets every stripe that
  misses it sweep to its end, a night's cost for its functions, so give it a larger `nights` and
  the `night` whose stripes hold it. The plan job refuses a fault that `pom.xml` gives no fault
  run, since a mistyped name would plant nothing and be reported as a fault the night missed. A
  run with a fault checks only the functions the fault reaches, of those asked for, and leaves
  the proofs out, so every mismatch it reports is expected from the fault (a real bug in the same
  functions would be among them, so only a clean night clears them): its own function and those
  that compute through it. log's three faults also fail log10, which calls `Log.logCore` (on the
  laptop, a two-second fuzz at 256 bits of each function under each function's `dropCorrection`
  failed its own function, and log10 under log's, and nothing else; a three-second one of log10
  under each of log's three faults failed every time; each is
  `NIGHTLY_JVM_ARGS=-Dvfdlibm.fault=<name> dev/nightly.sh fuzz <function> 3 0x1235 256`, whose
  exit status 1 is the failure); the plan job's `reach` holds that map, and a port that computes
  through another adds a line to it. When every sweep and fuzz of such a run passes, the job
  `fault-caught` fails, so a missed fault is a red run with an issue and not a green one. Its
  passing says only that some job failed: the mismatch files the issue lists say whether that was
  the fault, in which functions, and the issue says so when none records a mismatch. The issue
  has its own label, `nightly-fault-test`, in another colour, and opens by saying that the fault
  was planted, so it is never mixed with a night's `nightly-failure` issue; a fault dispatch
  whose plan job fails opens none. The run shares the night's concurrency group, where a new run
  replaces one that is waiting: dispatch it when no night is running or waiting. Close the test's
  issue once read: a later test comments on an open one. No such dispatch has been made yet.

## What the mutant survey taught

- **What it is.** `dev/survey.sh` changes one constant, threshold or correction term of a
  function's C by one step and counts the inputs whose result is no longer `StrictMath`'s, in the
  unit tests' inputs, a fuzz, the sweep and a search over the common path (`dev/survey/README.md`
  has the tables, for exp, expm1, log and log10 as of 20 November 2026; log1p needs its scalar
  transcription first). A mutant changes the C, not a port: where a port follows the C, an input
  that shows the mutant shows the same change in the vector code.
- **Inputs do not hold the coefficients.** One ulp on a polynomial coefficient late in its
  series changed no result in 1.8 * 10^10 inputs for exp's `P4` and `P5`, expm1's `Q3` to `Q5`
  and log's `Lg5` to `Lg7`, and the suite saw none of exp's
  `P3`, expm1's `Q2` or log's `Lg4` either. The test that pins every constant to the C's hex
  (`ExpTest.constantsAreTheSourcesBits` and its like) is what guards them, so a new port names
  every constant in such a test.
- **A planted fault is chosen among the mutants the suite catches.** About three in five are: every
  dropped correction term, the first coefficient of each series, the thresholds the suite has
  neighbours of. A survey row whose `caught_by` is `suite` and whose count is large is a fault
  already sized; the README says what a `none` row does and does not show.
- **The sweep caught nothing the fuzz and the suite did not.** No mutant showed on the sweep's
  inputs and on none of the suite's or the fuzz's: stripe `0/64` for every mutant, and all 2^32 high
  words (three low words each) for those nothing else had found in exp, expm1 and log (stripe `0/8`
  in log10). The sweep is for bands of wrong results, not for a constant one ulp off.
- **The suite misses exp's `invln2` one ulp off**, which the fuzz finds within 250 draws: it moves
  `k` at the doubles `(k + 1/2) ln2`, and `ExpTest` takes the neighbours of three of them only,
  where expm1's has 16 of each and catches it. The README lists one input for each.
