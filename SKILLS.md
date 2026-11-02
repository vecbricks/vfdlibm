# What vfdlibm has learned

Lessons about benchmarking, the JIT and the Vector API that every port follows. Most were learned
in Varka (github.com/vecbricks/varka, its `sql/varka/skills/`) and are rewritten here for a vector
math library; the last sections are what the `exp` and `log` ports taught. Each entry says where
its numbers come from. A new lesson goes in the section it belongs to, as a short entry; a
negative result belongs here too, since it stops the next port re-trying a settled question.

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
  41442062308), `AMD EPYC 9V45` (Varka's fork runs 41486339971, 41446843041, 41329377429, as
  "AMD EPYC 9V45 96-Core Processor") and `AMD EPYC 7763`. It checks `/proc/cpuinfo` before
  anything else and fails unless the model name contains the host's string;
  `bench/dispatch-bench.sh --host <host>` dispatches it and, when the run failed on that check,
  waits an hour and dispatches again, until a run passes, fails on something else, or (for the
  9V45, or with `--attempts`) the attempts run out (`bench/dispatch-epyc.sh` is the same with
  `--host epyc7763`). Other CPUs (the EPYC 9V74, the Xeon Platinum 8370C) are common draws, and
  accepting one would put a result on a different machine from every result before it. If a
  host stays scarce for days, changing the hosts is a decision for the owner, not a change to
  the check.
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
