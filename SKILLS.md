# What vfdlibm has learned

Lessons about benchmarking, the JIT and the Vector API that every port follows. Most were learned in
Varka (github.com/vecbricks/varka, its `sql/varka/skills/`) and are rewritten here for a vector math
library; the last sections are what the `exp`, `log`, `log1p`, `log10`, `expm1`, `sinh`, `cosh`,
`asin`, `atan`, `acos`, `atan2`, `hypot` and `pow` ports taught. Each entry says where its numbers
come from. A new lesson goes in the section it belongs to, as a short entry; a negative result
belongs here too, since it stops the next port re-trying a settled question.

## Benchmarking with JMH

- **The options.** `-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc`: two forks, five measured two-second
  iterations each, and the allocation profiler on every run. Report each row's minimum, average
  and maximum over the ten iterations, as `bench/results/*.txt` do, and keep JMH's summary table
  with its `gc.alloc.rate.norm` rows beside them. CI, `bench/pair.sh` and `bench/alloc-check.py`
  add `-foe true`, and so should a run by hand: without it a benchmark whose `setUp` throws
  prints `<failure>`, the run goes on and exits 0, and its row is missing from a green run.
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
  see that path box. The `*Max` rows are in, since the finish writes out its first eight lanes
  with constant indices (below).
- **An oracle that allocates gets a baseline.** A port that finishes rare lanes with an oracle
  that itself allocates on them (the reason is `pow`) cannot read under 1 B/op on mixed data,
  boxed or not. Its `*Benchmark` class then holds `rareOracle`, a scalar loop calling
  `StrictMath` on exactly the lanes the arms finish with it (its Javadoc states this; the script
  cannot read lanes), and `bench/alloc-check.py` runs it with the arms and fails an arm whose
  lowest iteration is not within 1 B/op of the baseline's lowest, either way, or whose worst
  reaches the baseline's lowest plus 1 B/op (the absolute bound where that lowest is under
  1 B/op, as on common data), printing both. A box adds a whole vector on top; an
  arm that does not finish with the oracle reads under it; both fail, so every arm of such a
  class must finish with it. The script refuses a `rareOracle` of any other shape than its one
  template, `ORACLE_SHAPE` in the script (the loop `for (int i = 0; i < N; i++) { if (rare[i]) {
  out[i] = java.lang.StrictMath.<method>(xs[i]); } }` or with `(xs[i], ys[i])`, then `return
  out;`, comments and spacing free), and fails a run without its row.
- **On Arm the check runs the preferred rows, and they pass.** GitHub's Arm runners have 128-bit
  vectors (`MaxVectorSize=16 UseSVE=2` on all 332 Arm jobs of `HARDWARE.md`, "What the pool
  gives"), so the preferred species is 128 bits there and a `*256` row would run in the Java
  fallback, as 512 bits does on an AVX2 host. Run once on such a runner, all 24 `*Preferred`
  rows of the five ports, every arm on common and on mixed data, read 0.04 to 0.10 B/op at the
  worst of three iterations (`bench/results/alloc-arm-2026-11-20.txt`). So at two 64-bit lanes
  the bodies run unboxed on Arm, where on x86 every masked one boxes (below); that is read from
  the allocation alone, and no `PrintIntrinsics` line was read there. `ci.yml`'s `alloc` job
  has that row since, as `bench/alloc-check.py --preferred-only`. The file is an allocation
  reading, not a timing: the Arm host (`neoversen2`) has no timing result, and nothing here says
  how fast those bodies are.
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
  `rare.anyTrue()`. `PrintInlining` showed it refused with `NodeCountInliningCutoff` in the final
  compile, under `-XX:UseAVX=2` with the body force-inlined. Its three arguments were then boxed on
  every vector, rare or not: 256 vectors times 168 bytes (two 64-byte `DoubleVector` boxes and a
  40-byte mask) plus, for the 62 vectors of the mixed data that hold a rare lane, 160 bytes (two
  `toArray` copies and the returned box) sum to the allocation the run read; JFR's
  allocation-by-site named `fromArray0Template`, `blendTemplate`, `Mask.or` and `toArray`. On common
  data the branch never ran, C2 left it as an uncommon trap, and nothing boxed, which is why the
  allocation check, on common data only, never saw it. Written out in the body with the Vector API's
  own `toLong`, `lane` and `withLane`, which C2 inlines late instead of refusing, the finish
  allocates nothing at 256 and 512 bits, and at `SPECIES_MAX` with its first eight lanes written
  out, in the laptop reading `bench/results/max-finish-alloc-free-laptop-2026-11-21.txt` (below;
  allocation only, no timing). Any slow path called from a port's body belongs written out inline,
  as `Lanes.addExponent` is.
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
  idx=CountTrailingZerosL` and both stay calls, so `finishMax` on mixed data allocated 30912 B/op at
  512 bits and 36992 under `-XX:UseAVX=2`, where `finish512` and `finish256` read under 0.01
  (`bench/results/exp-finish-laptop-2026-11-02.txt`). Common data never runs that loop and does not
  allocate. A constant index does lower there (the next entry); a port that has to visit lanes
  at a variable index measures `SPECIES_MAX` too.
- **The finish of exp, expm1, log, log10 and log1p boxed at `SPECIES_MAX`, Log's counted loop too;
  constant lane indices fix it.** With `*Max` rows in all five benchmarks, read on the laptop under
  `-prof gc` (`bench/results/max-finish-laptop-2026-11-20.txt`, allocation readings on a loaded
  machine, no timings): on mixed data `finishMax` allocates 30912 B/op at 512 bits for exp, expm1
  and log1p and 30432 for log and log10, and 36992 and 36672 under `-XX:UseAVX=2`; on common data it
  allocates nothing, and neither do the blended rows (log has none) or the guarded rows of log1p and
  expm1, on either data. The host files of 20 and 21 November 2026 read the old finish the same way:
  `finishMax` on mixed data allocated 30,912 B/op on the Xeon Platinum 8573C (the five files `exp-`,
  `expm1-`, `log-`, `log10-` and `log1p-xeon8573c-2026-11-21.txt`) for exp, expm1 and log1p and
  30,432 for log and log10, and, at 256 bits on the EPYC 7763, 36,992 for exp and 36,672 for log10
  (`exp-epyc7763-2026-11-20.txt` and `log10-epyc7763-2026-11-20.txt`; the other EPYC files have no
  `*Max` row). Log's finish is a counted loop over `species.length()` that tests each bit where the
  others walk the set bits with `numberOfTrailingZeros`; it boxes the same, so the loop's shape is
  not the cause, the lane index not being a constant is. Merged: the first eight lanes are written
  out with literal indices, `if (lanes > 0 && (rareLanes & 1L) != 0) result = result.withLane(0,
  f(x.lane(0)));` and so on, in the seven ports that had one (sinh's and cosh's finishes had the
  same loop; asin, which joined after, was written in this shape), the loop kept for lanes past
  eight. With it every `*Max` row reads under 0.1 B/op on mixed data at 512 bits and as an AVX2 host
  at 256, 0.033 and 0.072 at most, and all 94 rows of the check pass under both flag sets on a
  lightly loaded machine, the `*Max` rows now among them
  (`bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`). Lanes past eight, and 128 bits, are
  not read. The fixed shapes' finish changes too, and the change is not free there. The pair on the
  EPYC 7763 (`bench/results/exp-log10-pair-epyc7763-2026-11-21.txt` and its re-run
  `exp-log10-pair-rerun-epyc7763-2026-11-21.txt`, base `f2907e5`, head `1cec059` in the first run
  and `5a452fa` in the re-run, one AVX2 host, `MaxVectorSize=32`) reads exp's two mixed finish
  rows (`finish256`, `finishPreferred`) 7.4% to 9.3% slower by median and 8.3% to 8.9% slower by
  best in both runs, log10's 2.6% to 4.0% slower by median and 3.7% to 4.1% by best, and the
  `*Max` rows on mixed data faster, since they no longer box. Those slowdowns are under the
  pair's 10% threshold, whose verdict counts none of them, and its scalar control row moves 3% to
  6% by median between the sides, so the bests (8.3% to 8.9% for exp) are the steadier figure.
  The cause is not known: the re-run's file names the eight tests that replace a bit walk and
  code placement, and decides neither. Only exp and log10 were paired, on that one host: no
  AVX-512 host and no other port
  (`expm1`, `log`, `log1p`, `sinh`, `cosh`) has a pair, so the other ports' cost is unread. The
  host files of today's finish read its `*Max` rows on mixed data as allocation-free too: the Xeon
  Platinum 8573C's of 22 November for expm1, log, log10 and log1p (`expm1-xeon8573c-2026-11-22.txt`
  and its three siblings, a run and a re-run) and the EPYC 7763's of 23 November for expm1 and
  log1p (`expm1-epyc7763-2026-11-23.txt`, `log1p-epyc7763-2026-11-23.txt`, a run and a re-run
  too), every `finishMax` row
  under 0.05 B/op.
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

## The assembly check

`AsmTest` reads the machine code C2 emits for each vector arm of every port and asserts on it
(issue #139; the idea is Varka's assembly suite, the code vfdlibm's own). `AsmProbe` runs in a JVM
of its own per function and per fixed `VectorWidth` the host has, under `-Xbatch` and the row's
`-XX` options, with vfdlibm forced inline, the probe's own methods kept out of line and
`-XX:CompileCommand=print` on its loops; `AsmCheck` parses the last standard `C2-compiled nmethod`
of each, from its entry point to its stubs, in Intel syntax. `AsmCheckSelfTest` shows each rule
failing on crafted disassembly. To run it here: `dev/hsdis-build.sh`, then `mvn -q test
-Dtest='AsmTest,AsmCheckSelfTest'`. A new port's arms go in `AsmProbe` (static methods named
`<function><Arm>`, with the function's common data in `fill`) and in `AsmTest.ARMS`, which must
agree.

- **The arms.** Each port's benchmark arms, unmasked and masked, and the cores the benchmarks call:
  exp (finish, blended, core), expm1 (finish, guarded, blended, core), log and log10 (finish,
  blended), log1p (finish, guarded, blended), sinh, asin, atan, acos and hypot (finish), cosh
  (slice and finish by name, and `VFdlibm.cosh`, the default, as `default`) and atan2 (guarded,
  blended); the default arm goes through `VFdlibm`, the others through the port's class.
- **The rules.** Every byte decoded; packed-double arithmetic (`v(add|sub|mul|div|sqrt)pd`)
  present and on the width's registers only (`xmm`, `ymm`, `zmm`), no scalar `*sd` arithmetic or
  square root; no fused multiply-add of any form (`vfmadd*`, `vfmsub*`, `vfnmadd*`, `vfnmsub*`,
  `vfmaddsub*`, `vfmsubadd*`); no call but `{runtime_call UncommonTrapBlob}` and `{runtime_call
  Stub::method_entry_barrier}`, and no other instruction with a call's relocation (`{runtime_call
  ...}`, `{static_call}`, `{optimized virtual_call}`, `{virtual_call}`) but the safepoint poll
  stub's `jmp` to `{runtime_call SafepointBlob}`, which C2 places after the body and the return's
  poll enters only when a safepoint is pending; no inlined frame of `StrictMath` or `FdLibm`.
  Prefixes (`notrack`, `lock`, `data16`, ...) are read off before the mnemonic, so `notrack call`
  is a call. Each relocation is matched whole: a call naming an allowed stub and another fails.
  Narrower registers elsewhere are allowed: a 512-bit body keeps its int lanes in a `ymm`. Counts
  are not asserted (Varka: a register-allocation roll moves them with nothing wrong).
- **Common data makes the call rule exact.** On each benchmark's common data no lane is rare, so
  C2 compiles the per-lane finish as an uncommon trap, a deoptimizing exit the common path never
  takes; any other call anywhere in the method fails. The same data keep `StrictMath`'s code out of
  the C2 body that is read (no rare lane, no call to compile), which the licence rule needs; the
  last rule is a backstop, since HotSpot names inlined frames only at calls, traps and safepoints.
  The output also holds each loop's C1 compile, which is not read: it keeps the finish, with a
  call into `StrictMath`, whose code is not in it. One range is not its benchmark's: cosh's
  common data hold 0.6% of lanes in the small range `|x| < 0.5*ln2`, which `coshFinish`
  finishes per lane, so the probe draws `0.35 <= |x| < 60`.
- **At 128 bits on x86 the call rule is not asserted.** Every arm calls the Vector API's Java
  fallback, 60 to 444 calls per method (the census below), as "At two 64-bit lanes x86 C2 lowers
  no mask" predicts. Width, FMA and the rest are asserted there.
- **CI splits the widths between its x86 rows.** `VFDLIBM_ASM_WIDTHS` (ci.yml's `asm-widths`)
  names the widths a run checks: `256,512` on the default row, `256` on the AVX2 row (its vectors
  stop there), `128` on the `MaxVectorSize=16` row, where the 128-bit cases, most of the check's
  time, run once. A width left out skips with the row that checks it;
  `AsmTest.ciRowsCoverEveryWidth` fails if a row names a width its `jvm-args` do not reach, or the
  rows together leave one out. Unset, as here, every width runs. So in CI 128-bit code is read
  under `MaxVectorSize=16` only, on whatever runner is drawn, an accepted trade-off; the census
  below has it under all three flag sets. 512 bits is read only when the default row draws an
  AVX-512 runner, as before the split.
- **The preferred species is not run again.** It is the same species object as one of the fixed
  widths, and exp's census read the same at both; a second run would only add time.
- **Keep the probe's own methods out of line.** The probe's `main` calls each arm by reflection,
  `Method.invoke`, whose compiled `LambdaForm$DMH::invokeStatic` calls the arm, and the forced
  `inline,io.github.vecbricks.vfdlibm.*::*` covers the probe too: that caller's compile took the
  arm's loop in (`force inline by CompileCommand`), and the arm's own compile, the one read, might
  never have come. With `dontinline` on the probe after the forced inline, `PrintInlining` shows
  `@ 18 AsmProbe::expBlended ... failed to inline: disallowed by CompileCommand` at every such
  site, while `Exp::exp` still inlines into the arm. The order decides it: exp's probe at 256 bits
  with the two commands swapped printed `force inline by CompileCommand` at those sites instead
  (JDK 25.0.5.1), so the later of two matching commands wins.
- **Print the caller's loop, not exp's methods.** Without the forced inline, `Exp`'s shared
  three-argument body compiles as a method of its own whose `arm` is no constant; it takes and
  returns heap vectors, calls `blend` and `convert` unintrinsified and allocates (`C2 Runtime
  new_instance`, `new_array`), and the public `VFdlibm.exp` is a `{static_call}` into it. With the
  forced inline, whether any `Exp` method gets a C2 compile of its own depends on timing (one run
  printed only `expBlended`). So the probe's loops, in `ExpBenchmark`'s shape, are what is read.
- **`expRare` before `expCore`, or it boxes.** The first run of the check failed the `core` arm at
  256 and 512 bits: a loop calling `Exp.expCore(x)` and then `Exp.expRare(x)` on one vector has a
  `{static_call}` to `expRare` and to `AbstractMask.intoArray`, which `PrintInlining` shows refused
  for `NodeCountInliningCutoff` once `expCore` is inlined. In `ExpBenchmark`'s order, `expRare`
  first, both inline and the rules pass. "Two port bodies in one loop do not fit" above is the
  same budget; here one body and a small one fit only in that order.
- **hsdis needs Capstone 5.** `dev/hsdis-build.sh` compiles the JDK's
  `src/utils/hsdis/capstone/hsdis-capstone.c` and `hsdis.h` (jdk-25-ga, fetched alone, SHA-256
  pinned, compiled and never read) against Capstone. Ubuntu 24.04's Capstone 4.0.2, GitHub's
  ubuntu-latest, printed exp's 256-bit AVX-512 bodies with 19 to 43 undecoded `.byte` lines each, an
  EVEX prefix it did not know followed by garbage with a `call` in it (read in an ubuntu:24.04
  container on the laptop). So the script uses the system's Capstone only at 5 and otherwise builds
  the pinned 5.0.7 from source with cmake; in that container the census then matched the laptop's to
  the instruction; and the check fails on any `.byte` or `(bad)`.
- **Skips say which.** Not Linux on x86-64 (a skip even when required); hsdis not found (the
  script's `target/hsdis`, which `mvn clean` deletes, or `$VFDLIBM_HSDIS_DIR`; a binutils hsdis such
  as `bench/asm`'s prints another format, which the rules were not written for); or found but not
  loaded, decided by the output holding no `[Disassembly]` instruction line, not by HotSpot's
  `Loading hsdis library failed`, which it printed both with no library and with a file that was
  none. An hsdis in the JDK's own `lib` would be loaded first, so the check skips (fails, required)
  when one is there. CI's x86 rows build hsdis and set `VFDLIBM_ASM_REQUIRED=true`, which makes the
  last three fail instead; the Arm row skips.

**The census of exp's arms, 22 November 2026, on the laptop** (AMD Ryzen AI 9 HX PRO 370, JDK
25.0.5.1, hsdis on Capstone 5.0.7). Not a headline and no timing: counts of instructions only, one
run, the same in a second. Each is the whole nmethod of the probe's loop, prologue and out-of-line
paths included, so the constant broadcasts C2 hoists before the loop count, and from 256 bits up the
stack accesses are the stack bang and the uncommon traps' saves, not spills; `bench/asm/README.md`
counts the hot loop alone. The columns: all instructions, packed-double arithmetic, `vdivpd`,
`vsqrtpd` (added with the other ports' census below; 0 in every exp row), blends, broadcasts, compares (`vpcmp*`, `vcmp*`), mask-register operations (`k*`), conversions
(`vcvt*`, `vpmov*`), operands on `[rsp`, calls, FMA. The preferred species read the same as its
fixed width (512 bits on the host's flags, 256 as an AVX2 host). The rows are the test's output
(`grep '^| exp '`), less its function and width columns, under shorter headers.

On the host's own flags (AVX-512, `MaxVectorSize=64`):

| bits | arm | insns | pd | div | sqrt | blend | bcast | cmp | k | cvt | stack | calls | fma |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | finish | 1195 | 24 | 1 | 0 | 0 | 9 | 3 | 2 | 19 | 351 | 60 | 0 |
| 128 | finishMasked | 1366 | 24 | 1 | 0 | 0 | 9 | 3 | 2 | 19 | 421 | 71 | 0 |
| 128 | blended | 2289 | 26 | 1 | 0 | 0 | 14 | 3 | 2 | 20 | 535 | 126 | 0 |
| 128 | blendedMasked | 2469 | 26 | 1 | 0 | 0 | 14 | 3 | 2 | 20 | 682 | 132 | 0 |
| 128 | core | 1872 | 24 | 1 | 0 | 0 | 14 | 6 | 4 | 24 | 555 | 91 | 0 |
| 256 | finish | 137 | 24 | 1 | 0 | 2 | 17 | 7 | 5 | 4 | 8 | 3 | 0 |
| 256 | finishMasked | 159 | 24 | 1 | 0 | 3 | 17 | 7 | 6 | 4 | 10 | 3 | 0 |
| 256 | blended | 145 | 26 | 1 | 0 | 8 | 24 | 12 | 4 | 4 | 2 | 2 | 0 |
| 256 | blendedMasked | 157 | 26 | 1 | 0 | 9 | 24 | 12 | 4 | 4 | 3 | 2 | 0 |
| 256 | core | 133 | 24 | 1 | 0 | 2 | 17 | 7 | 3 | 4 | 3 | 2 | 0 |
| 512 | finish | 140 | 24 | 1 | 0 | 2 | 17 | 7 | 5 | 4 | 8 | 3 | 0 |
| 512 | finishMasked | 155 | 24 | 1 | 0 | 3 | 17 | 7 | 7 | 4 | 10 | 3 | 0 |
| 512 | blended | 145 | 26 | 1 | 0 | 8 | 24 | 12 | 4 | 4 | 2 | 2 | 0 |
| 512 | blendedMasked | 155 | 26 | 1 | 0 | 9 | 24 | 12 | 4 | 4 | 3 | 2 | 0 |
| 512 | core | 134 | 24 | 1 | 0 | 2 | 17 | 7 | 3 | 4 | 3 | 2 | 0 |

As an AVX2 host (`-XX:UseAVX=2 -XX:MaxVectorSize=32`):

| bits | arm | insns | pd | div | sqrt | blend | bcast | cmp | k | cvt | stack | calls | fma |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | finish | 1147 | 24 | 1 | 0 | 2 | 9 | 4 | 0 | 18 | 295 | 60 | 0 |
| 128 | finishMasked | 1321 | 24 | 1 | 0 | 2 | 9 | 4 | 0 | 18 | 374 | 71 | 0 |
| 128 | blended | 2313 | 26 | 1 | 0 | 2 | 14 | 4 | 0 | 19 | 573 | 126 | 0 |
| 128 | blendedMasked | 2272 | 26 | 1 | 0 | 2 | 14 | 4 | 0 | 19 | 505 | 132 | 0 |
| 128 | core | 1745 | 24 | 1 | 0 | 4 | 14 | 8 | 0 | 22 | 438 | 91 | 0 |
| 256 | finish | 144 | 24 | 1 | 0 | 5 | 17 | 8 | 0 | 3 | 8 | 3 | 0 |
| 256 | finishMasked | 167 | 24 | 1 | 0 | 6 | 17 | 8 | 0 | 4 | 10 | 3 | 0 |
| 256 | blended | 152 | 26 | 1 | 0 | 10 | 25 | 13 | 0 | 3 | 2 | 2 | 0 |
| 256 | blendedMasked | 167 | 26 | 1 | 0 | 11 | 25 | 13 | 0 | 4 | 5 | 2 | 0 |
| 256 | core | 140 | 24 | 1 | 0 | 5 | 17 | 8 | 0 | 3 | 3 | 2 | 0 |

What it reads: no FMA in any arm at any width under either flag set. From 256 bits up every arm is
one copy of the body (24 packed-double operations, one `vdivpd`; the blended arm's two more are its
filter's `x + x` and the subnormal scale's product) and calls only its traps and the entry barrier:
three for the finish arms (the finish's trap, the loop's range check, the barrier), two for the
others. The blended arm has 6 more blends than the finish on AVX-512 and 5 more on AVX2, and 7 to 8
more broadcasts, its filter's constants. AVX2 has no mask registers, so its masks are vector
compares and `vblendvps`, 2 to 3 more blends per arm than AVX-512's `vpblendmq`. Masking adds one
blend and 10 to 23 instructions. At 128 bits the bodies are 8 to 16 times longer: the Java fallback.

**The census of the other ports' arms, 23 November 2026, on the laptop** (the same machine, JDK
and hsdis as exp's). Not a headline and no timing: counts of instructions only, one run each, read
as exp's are, with one column more, `vsqrtpd` (sqrt), which the width rule counts as packed-double
arithmetic since this run. The rows are the test's output (`grep '^| <function> '`), less its width
column. exp's rows of the same run matched its census above in every column but the instruction
count, which moved by 1 to 13 on four masked arms. Every arm below passed every rule, at every
width, under all three flag sets. cosh's `default` arms, `VFdlibm.cosh`, came in a later run
with CI's widths per row, so the 128-bit ranges hold them under `MaxVectorSize=16` only (6 arms
there, 4 under the other two); they read as the slice arm, which `VFdlibm.cosh` calls today, to
within 5 instructions at 256 and 512 bits.

On the host's own flags (AVX-512, `MaxVectorSize=64`), 256 and 512 bits:

| function | bits | arm | insns | pd | div | sqrt | blend | bcast | cmp | k | cvt | stack | calls | fma |
| :--- | ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| expm1 | 256 | blended | 227 | 50 | 1 | 0 | 15 | 39 | 21 | 27 | 6 | 16 | 2 | 0 |
| expm1 | 256 | blendedMasked | 252 | 50 | 1 | 0 | 16 | 39 | 21 | 31 | 6 | 22 | 2 | 0 |
| expm1 | 256 | core | 185 | 45 | 1 | 0 | 9 | 28 | 13 | 15 | 6 | 8 | 2 | 0 |
| expm1 | 256 | finish | 235 | 45 | 1 | 0 | 9 | 33 | 19 | 32 | 6 | 24 | 3 | 0 |
| expm1 | 256 | finishMasked | 253 | 45 | 1 | 0 | 10 | 33 | 19 | 37 | 6 | 30 | 3 | 0 |
| expm1 | 256 | guarded | 258 | 45 | 1 | 0 | 9 | 33 | 19 | 38 | 6 | 35 | 3 | 0 |
| expm1 | 256 | guardedMasked | 280 | 45 | 1 | 0 | 10 | 33 | 19 | 41 | 6 | 40 | 3 | 0 |
| expm1 | 512 | blended | 223 | 50 | 1 | 0 | 15 | 39 | 21 | 24 | 6 | 16 | 2 | 0 |
| expm1 | 512 | blendedMasked | 244 | 50 | 1 | 0 | 16 | 39 | 21 | 28 | 6 | 22 | 2 | 0 |
| expm1 | 512 | core | 187 | 45 | 1 | 0 | 9 | 28 | 13 | 18 | 6 | 12 | 2 | 0 |
| expm1 | 512 | finish | 224 | 45 | 1 | 0 | 9 | 33 | 19 | 23 | 6 | 18 | 3 | 0 |
| expm1 | 512 | finishMasked | 240 | 45 | 1 | 0 | 10 | 33 | 19 | 25 | 6 | 20 | 3 | 0 |
| expm1 | 512 | guarded | 250 | 45 | 1 | 0 | 9 | 33 | 19 | 35 | 6 | 32 | 3 | 0 |
| expm1 | 512 | guardedMasked | 272 | 45 | 1 | 0 | 10 | 33 | 19 | 40 | 6 | 38 | 3 | 0 |
| log | 256 | blended | 181 | 46 | 2 | 0 | 7 | 31 | 7 | 14 | 0 | 8 | 2 | 0 |
| log | 256 | blendedMasked | 198 | 46 | 2 | 0 | 8 | 31 | 7 | 17 | 0 | 13 | 2 | 0 |
| log | 256 | finish | 170 | 42 | 1 | 0 | 3 | 26 | 4 | 7 | 0 | 8 | 3 | 0 |
| log | 256 | finishMasked | 191 | 42 | 1 | 0 | 4 | 26 | 4 | 8 | 0 | 10 | 3 | 0 |
| log | 512 | blended | 182 | 46 | 2 | 0 | 7 | 31 | 7 | 14 | 0 | 8 | 2 | 0 |
| log | 512 | blendedMasked | 195 | 46 | 2 | 0 | 8 | 31 | 7 | 17 | 0 | 13 | 2 | 0 |
| log | 512 | finish | 169 | 42 | 1 | 0 | 3 | 26 | 4 | 7 | 0 | 8 | 3 | 0 |
| log | 512 | finishMasked | 189 | 42 | 1 | 0 | 4 | 26 | 4 | 8 | 0 | 10 | 3 | 0 |
| log10 | 256 | blended | 187 | 51 | 2 | 0 | 7 | 34 | 8 | 3 | 3 | 2 | 2 | 0 |
| log10 | 256 | blendedMasked | 207 | 51 | 2 | 0 | 8 | 34 | 8 | 3 | 3 | 3 | 2 | 0 |
| log10 | 256 | finish | 195 | 47 | 1 | 0 | 3 | 31 | 5 | 8 | 3 | 8 | 3 | 0 |
| log10 | 256 | finishMasked | 227 | 47 | 1 | 0 | 4 | 31 | 5 | 16 | 3 | 16 | 3 | 0 |
| log10 | 512 | blended | 192 | 51 | 2 | 0 | 7 | 34 | 8 | 3 | 3 | 2 | 2 | 0 |
| log10 | 512 | blendedMasked | 202 | 51 | 2 | 0 | 8 | 34 | 8 | 3 | 3 | 3 | 2 | 0 |
| log10 | 512 | finish | 195 | 47 | 1 | 0 | 3 | 31 | 5 | 8 | 3 | 8 | 3 | 0 |
| log10 | 512 | finishMasked | 222 | 47 | 1 | 0 | 4 | 31 | 5 | 12 | 3 | 12 | 3 | 0 |
| log1p | 256 | blended | 190 | 48 | 2 | 0 | 19 | 31 | 14 | 8 | 4 | 6 | 2 | 0 |
| log1p | 256 | blendedMasked | 206 | 48 | 2 | 0 | 20 | 31 | 14 | 8 | 4 | 7 | 2 | 0 |
| log1p | 256 | finish | 186 | 36 | 2 | 0 | 10 | 26 | 11 | 14 | 4 | 12 | 3 | 0 |
| log1p | 256 | finishMasked | 211 | 36 | 2 | 0 | 11 | 26 | 11 | 17 | 4 | 16 | 3 | 0 |
| log1p | 256 | guarded | 215 | 36 | 2 | 0 | 10 | 26 | 11 | 17 | 4 | 26 | 3 | 0 |
| log1p | 256 | guardedMasked | 237 | 36 | 2 | 0 | 11 | 26 | 11 | 19 | 4 | 31 | 3 | 0 |
| log1p | 512 | blended | 193 | 48 | 2 | 0 | 19 | 31 | 14 | 8 | 4 | 6 | 2 | 0 |
| log1p | 512 | blendedMasked | 209 | 48 | 2 | 0 | 20 | 31 | 14 | 8 | 4 | 7 | 2 | 0 |
| log1p | 512 | finish | 185 | 36 | 2 | 0 | 10 | 26 | 11 | 15 | 4 | 12 | 3 | 0 |
| log1p | 512 | finishMasked | 209 | 36 | 2 | 0 | 11 | 26 | 11 | 17 | 4 | 16 | 3 | 0 |
| log1p | 512 | guarded | 212 | 36 | 2 | 0 | 10 | 26 | 11 | 17 | 4 | 26 | 3 | 0 |
| log1p | 512 | guardedMasked | 235 | 36 | 2 | 0 | 11 | 26 | 11 | 19 | 4 | 31 | 3 | 0 |
| sinh | 256 | finish | 241 | 52 | 2 | 0 | 12 | 30 | 16 | 26 | 7 | 20 | 3 | 0 |
| sinh | 256 | finishMasked | 259 | 52 | 2 | 0 | 13 | 30 | 16 | 29 | 7 | 24 | 3 | 0 |
| sinh | 512 | finish | 240 | 52 | 2 | 0 | 12 | 30 | 16 | 26 | 7 | 22 | 3 | 0 |
| sinh | 512 | finishMasked | 259 | 52 | 2 | 0 | 13 | 30 | 16 | 28 | 7 | 24 | 3 | 0 |
| cosh | 256 | default | 202 | 56 | 3 | 0 | 8 | 30 | 10 | 11 | 5 | 12 | 3 | 0 |
| cosh | 256 | defaultMasked | 221 | 56 | 3 | 0 | 9 | 30 | 10 | 13 | 5 | 14 | 3 | 0 |
| cosh | 256 | finish | 160 | 27 | 2 | 0 | 3 | 20 | 8 | 10 | 5 | 10 | 3 | 0 |
| cosh | 256 | finishMasked | 182 | 27 | 2 | 0 | 4 | 20 | 8 | 14 | 5 | 14 | 3 | 0 |
| cosh | 256 | slice | 207 | 56 | 3 | 0 | 8 | 30 | 10 | 11 | 5 | 12 | 3 | 0 |
| cosh | 256 | sliceMasked | 221 | 56 | 3 | 0 | 9 | 30 | 10 | 13 | 5 | 14 | 3 | 0 |
| cosh | 512 | default | 205 | 56 | 3 | 0 | 8 | 30 | 10 | 11 | 5 | 12 | 3 | 0 |
| cosh | 512 | defaultMasked | 226 | 56 | 3 | 0 | 9 | 30 | 10 | 17 | 5 | 19 | 3 | 0 |
| cosh | 512 | finish | 158 | 27 | 2 | 0 | 3 | 20 | 8 | 12 | 5 | 12 | 3 | 0 |
| cosh | 512 | finishMasked | 177 | 27 | 2 | 0 | 4 | 20 | 8 | 14 | 5 | 14 | 3 | 0 |
| cosh | 512 | slice | 205 | 56 | 3 | 0 | 8 | 30 | 10 | 11 | 5 | 12 | 3 | 0 |
| cosh | 512 | sliceMasked | 226 | 56 | 3 | 0 | 9 | 30 | 10 | 18 | 5 | 19 | 3 | 0 |
| asin | 256 | finish | 160 | 44 | 2 | 1 | 5 | 22 | 5 | 5 | 1 | 8 | 3 | 0 |
| asin | 256 | finishMasked | 179 | 44 | 2 | 1 | 6 | 22 | 5 | 6 | 1 | 10 | 3 | 0 |
| asin | 512 | finish | 159 | 44 | 2 | 1 | 5 | 22 | 5 | 5 | 1 | 8 | 3 | 0 |
| asin | 512 | finishMasked | 173 | 44 | 2 | 1 | 6 | 22 | 5 | 6 | 1 | 10 | 3 | 0 |
| atan | 256 | finish | 174 | 37 | 1 | 0 | 16 | 30 | 7 | 7 | 1 | 10 | 3 | 0 |
| atan | 256 | finishMasked | 193 | 37 | 1 | 0 | 17 | 30 | 7 | 8 | 1 | 12 | 3 | 0 |
| atan | 512 | finish | 168 | 37 | 1 | 0 | 16 | 30 | 7 | 7 | 1 | 10 | 3 | 0 |
| atan | 512 | finishMasked | 187 | 37 | 1 | 0 | 17 | 30 | 7 | 8 | 1 | 12 | 3 | 0 |
| acos | 256 | finish | 153 | 42 | 2 | 1 | 5 | 21 | 4 | 5 | 1 | 8 | 3 | 0 |
| acos | 256 | finishMasked | 172 | 42 | 2 | 1 | 6 | 21 | 4 | 6 | 1 | 10 | 3 | 0 |
| acos | 512 | finish | 153 | 42 | 2 | 1 | 5 | 21 | 4 | 5 | 1 | 8 | 3 | 0 |
| acos | 512 | finishMasked | 168 | 42 | 2 | 1 | 6 | 21 | 4 | 6 | 1 | 10 | 3 | 0 |
| atan2 | 256 | blended | 299 | 42 | 2 | 0 | 38 | 43 | 19 | 43 | 3 | 42 | 3 | 0 |
| atan2 | 256 | blendedMasked | 347 | 42 | 2 | 0 | 39 | 43 | 19 | 47 | 3 | 49 | 3 | 0 |
| atan2 | 256 | guarded | 359 | 41 | 2 | 0 | 23 | 37 | 19 | 71 | 3 | 77 | 4 | 0 |
| atan2 | 256 | guardedMasked | 381 | 41 | 2 | 0 | 24 | 37 | 19 | 78 | 3 | 91 | 4 | 0 |
| atan2 | 512 | blended | 293 | 42 | 2 | 0 | 38 | 43 | 19 | 44 | 3 | 43 | 3 | 0 |
| atan2 | 512 | blendedMasked | 348 | 42 | 2 | 0 | 39 | 43 | 19 | 51 | 3 | 50 | 3 | 0 |
| atan2 | 512 | guarded | 351 | 41 | 2 | 0 | 23 | 37 | 19 | 69 | 3 | 75 | 4 | 0 |
| atan2 | 512 | guardedMasked | 410 | 41 | 2 | 0 | 24 | 37 | 19 | 82 | 3 | 90 | 4 | 0 |
| hypot | 256 | finish | 212 | 23 | 0 | 1 | 21 | 14 | 9 | 17 | 8 | 18 | 3 | 0 |
| hypot | 256 | finishMasked | 236 | 23 | 0 | 1 | 22 | 14 | 9 | 21 | 8 | 22 | 3 | 0 |
| hypot | 512 | finish | 202 | 23 | 0 | 1 | 21 | 14 | 9 | 10 | 8 | 15 | 3 | 0 |
| hypot | 512 | finishMasked | 245 | 23 | 0 | 1 | 22 | 14 | 9 | 11 | 8 | 17 | 3 | 0 |
| pow | 256 | finish | 343 | 88 | 2 | 0 | 9 | 48 | 16 | 31 | 9 | 22 | 3 | 0 |
| pow | 256 | finishMasked | 369 | 88 | 2 | 0 | 10 | 48 | 16 | 40 | 9 | 31 | 3 | 0 |
| pow | 512 | finish | 334 | 88 | 2 | 0 | 9 | 48 | 16 | 29 | 9 | 22 | 3 | 0 |
| pow | 512 | finishMasked | 383 | 88 | 2 | 0 | 10 | 48 | 16 | 32 | 9 | 27 | 3 | 0 |

As an AVX2 host (`-XX:UseAVX=2 -XX:MaxVectorSize=32`), 256 bits:

| function | bits | arm | insns | pd | div | sqrt | blend | bcast | cmp | k | cvt | stack | calls | fma |
| :--- | ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| expm1 | 256 | blended | 281 | 50 | 1 | 0 | 17 | 48 | 30 | 0 | 15 | 26 | 2 | 0 |
| expm1 | 256 | blendedMasked | 299 | 50 | 1 | 0 | 18 | 48 | 30 | 0 | 16 | 32 | 2 | 0 |
| expm1 | 256 | core | 212 | 45 | 1 | 0 | 11 | 36 | 19 | 0 | 10 | 8 | 2 | 0 |
| expm1 | 256 | finish | 267 | 45 | 1 | 0 | 12 | 41 | 28 | 0 | 12 | 24 | 3 | 0 |
| expm1 | 256 | finishMasked | 296 | 45 | 1 | 0 | 13 | 41 | 28 | 0 | 13 | 31 | 3 | 0 |
| expm1 | 256 | guarded | 311 | 45 | 1 | 0 | 13 | 41 | 28 | 0 | 12 | 36 | 3 | 0 |
| expm1 | 256 | guardedMasked | 339 | 45 | 1 | 0 | 14 | 41 | 28 | 0 | 13 | 45 | 3 | 0 |
| log | 256 | blended | 184 | 46 | 2 | 0 | 8 | 33 | 8 | 0 | 0 | 10 | 2 | 0 |
| log | 256 | blendedMasked | 203 | 46 | 2 | 0 | 9 | 33 | 8 | 0 | 1 | 16 | 2 | 0 |
| log | 256 | finish | 176 | 42 | 1 | 0 | 4 | 28 | 5 | 0 | 0 | 8 | 3 | 0 |
| log | 256 | finishMasked | 193 | 42 | 1 | 0 | 5 | 28 | 5 | 0 | 1 | 13 | 3 | 0 |
| log10 | 256 | blended | 226 | 51 | 2 | 0 | 8 | 38 | 9 | 0 | 4 | 14 | 2 | 0 |
| log10 | 256 | blendedMasked | 244 | 51 | 2 | 0 | 9 | 38 | 9 | 0 | 5 | 19 | 2 | 0 |
| log10 | 256 | finish | 221 | 47 | 1 | 0 | 4 | 35 | 7 | 0 | 3 | 14 | 3 | 0 |
| log10 | 256 | finishMasked | 238 | 47 | 1 | 0 | 5 | 35 | 7 | 0 | 4 | 19 | 3 | 0 |
| log1p | 256 | blended | 233 | 48 | 2 | 0 | 19 | 38 | 19 | 0 | 11 | 10 | 2 | 0 |
| log1p | 256 | blendedMasked | 258 | 48 | 2 | 0 | 20 | 38 | 19 | 0 | 12 | 13 | 2 | 0 |
| log1p | 256 | finish | 213 | 36 | 2 | 0 | 11 | 31 | 16 | 0 | 7 | 8 | 3 | 0 |
| log1p | 256 | finishMasked | 240 | 36 | 2 | 0 | 12 | 31 | 16 | 0 | 8 | 14 | 3 | 0 |
| log1p | 256 | guarded | 259 | 36 | 2 | 0 | 12 | 32 | 16 | 0 | 7 | 24 | 3 | 0 |
| log1p | 256 | guardedMasked | 283 | 36 | 2 | 0 | 13 | 32 | 16 | 0 | 8 | 32 | 3 | 0 |
| sinh | 256 | finish | 282 | 52 | 2 | 0 | 15 | 44 | 23 | 0 | 14 | 24 | 3 | 0 |
| sinh | 256 | finishMasked | 303 | 52 | 2 | 0 | 16 | 44 | 23 | 0 | 15 | 28 | 3 | 0 |
| cosh | 256 | default | 220 | 56 | 3 | 0 | 11 | 36 | 12 | 0 | 8 | 10 | 3 | 0 |
| cosh | 256 | defaultMasked | 239 | 56 | 3 | 0 | 12 | 36 | 12 | 0 | 9 | 15 | 3 | 0 |
| cosh | 256 | finish | 168 | 27 | 2 | 0 | 6 | 20 | 10 | 0 | 6 | 8 | 3 | 0 |
| cosh | 256 | finishMasked | 187 | 27 | 2 | 0 | 7 | 20 | 10 | 0 | 7 | 10 | 3 | 0 |
| cosh | 256 | slice | 220 | 56 | 3 | 0 | 11 | 36 | 12 | 0 | 8 | 10 | 3 | 0 |
| cosh | 256 | sliceMasked | 239 | 56 | 3 | 0 | 12 | 36 | 12 | 0 | 9 | 15 | 3 | 0 |
| asin | 256 | finish | 166 | 44 | 2 | 1 | 6 | 23 | 7 | 0 | 5 | 8 | 3 | 0 |
| asin | 256 | finishMasked | 186 | 44 | 2 | 1 | 7 | 24 | 7 | 0 | 6 | 10 | 3 | 0 |
| atan | 256 | finish | 182 | 37 | 1 | 0 | 17 | 32 | 8 | 0 | 7 | 8 | 3 | 0 |
| atan | 256 | finishMasked | 199 | 37 | 1 | 0 | 18 | 35 | 8 | 0 | 8 | 10 | 3 | 0 |
| acos | 256 | finish | 159 | 42 | 2 | 1 | 6 | 22 | 6 | 0 | 4 | 8 | 3 | 0 |
| acos | 256 | finishMasked | 183 | 42 | 2 | 1 | 7 | 23 | 6 | 0 | 5 | 10 | 3 | 0 |
| atan2 | 256 | blended | 354 | 42 | 2 | 0 | 40 | 63 | 23 | 0 | 12 | 39 | 3 | 0 |
| atan2 | 256 | blendedMasked | 407 | 42 | 2 | 0 | 41 | 63 | 23 | 0 | 12 | 55 | 3 | 0 |
| atan2 | 256 | guarded | 461 | 41 | 2 | 0 | 36 | 52 | 23 | 0 | 12 | 68 | 4 | 0 |
| atan2 | 256 | guardedMasked | 527 | 41 | 2 | 0 | 37 | 52 | 23 | 0 | 13 | 88 | 4 | 0 |
| hypot | 256 | finish | 256 | 23 | 0 | 1 | 22 | 24 | 13 | 0 | 14 | 33 | 3 | 0 |
| hypot | 256 | finishMasked | 301 | 23 | 0 | 1 | 23 | 24 | 13 | 0 | 14 | 36 | 3 | 0 |
| pow | 256 | finish | 379 | 88 | 2 | 0 | 10 | 71 | 22 | 0 | 9 | 12 | 3 | 0 |
| pow | 256 | finishMasked | 429 | 88 | 2 | 0 | 11 | 71 | 22 | 0 | 10 | 23 | 3 | 0 |

At 128 bits, the ranges over each function's arms (all instructions, packed-double arithmetic,
calls, operands on `[rsp`, FMA), on the host's own flags:

| function | arms | insns | pd | calls | stack | fma |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| expm1 | 7 | 4327 to 5760 | 45 to 50 | 199 to 268 | 1609 to 2290 | 0 |
| log | 4 | 1597 to 2672 | 42 to 46 | 70 to 134 | 551 to 867 | 0 |
| log10 | 4 | 1987 to 3094 | 47 to 51 | 88 to 151 | 684 to 899 | 0 |
| log1p | 6 | 3779 to 5350 | 36 to 48 | 166 to 243 | 1334 to 1986 | 0 |
| sinh | 2 | 6906 to 7418 | 52 | 255 to 264 | 2897 to 3367 | 0 |
| cosh | 4 | 2239 to 3542 | 27 to 56 | 99 to 152 | 724 to 1219 | 0 |
| asin | 2 | 1645 to 1808 | 44 | 86 to 95 | 468 to 554 | 0 |
| atan | 2 | 3402 to 3694 | 37 | 163 to 172 | 1111 to 1309 | 0 |
| acos | 2 | 1555 to 1716 | 49 | 78 to 89 | 436 to 509 | 0 |
| atan2 | 4 | 8138 to 10288 | 41 to 42 | 360 to 444 | 3093 to 4197 | 0 |
| hypot | 2 | 4171 to 4300 | 23 | 217 to 226 | 1321 to 1330 | 0 |

As an AVX2 host:

| function | arms | insns | pd | calls | stack | fma |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| expm1 | 7 | 4287 to 5730 | 45 to 50 | 199 to 268 | 1599 to 2290 | 0 |
| log | 4 | 1423 to 2542 | 42 to 46 | 70 to 134 | 411 to 771 | 0 |
| log10 | 4 | 1928 to 3045 | 47 to 51 | 88 to 151 | 654 to 878 | 0 |
| log1p | 6 | 3659 to 5291 | 36 to 48 | 166 to 243 | 1273 to 1968 | 0 |
| sinh | 2 | 6850 to 7375 | 52 | 255 to 264 | 2896 to 3367 | 0 |
| cosh | 4 | 2206 to 3526 | 27 to 56 | 99 to 152 | 703 to 1218 | 0 |
| asin | 2 | 1539 to 1728 | 44 | 86 to 95 | 386 to 491 | 0 |
| atan | 2 | 3382 to 3640 | 37 | 163 to 172 | 1110 to 1266 | 0 |
| acos | 2 | 1523 to 1628 | 49 | 78 to 89 | 413 to 439 | 0 |
| atan2 | 4 | 8098 to 10228 | 41 to 42 | 360 to 444 | 3094 to 4197 | 0 |
| hypot | 2 | 4029 to 4134 | 23 | 217 to 226 | 1233 to 1252 | 0 |

With `-XX:MaxVectorSize=16`:

| function | arms | insns | pd | calls | stack | fma |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| expm1 | 7 | 4280 to 5722 | 45 to 50 | 199 to 268 | 1600 to 2290 | 0 |
| log | 4 | 1583 to 2656 | 42 to 46 | 70 to 134 | 569 to 869 | 0 |
| log10 | 4 | 1965 to 3075 | 47 to 51 | 88 to 151 | 684 to 907 | 0 |
| log1p | 6 | 3737 to 5352 | 36 to 48 | 166 to 243 | 1334 to 2009 | 0 |
| sinh | 2 | 6854 to 7375 | 52 | 255 to 264 | 2896 to 3368 | 0 |
| cosh | 6 | 2224 to 3520 | 27 to 56 | 99 to 152 | 724 to 1220 | 0 |
| asin | 2 | 1624 to 1790 | 44 | 86 to 95 | 468 to 555 | 0 |
| atan | 2 | 3387 to 3673 | 37 | 163 to 172 | 1115 to 1297 | 0 |
| acos | 2 | 1591 to 1701 | 49 | 78 to 89 | 474 to 510 | 0 |
| atan2 | 4 | 8098 to 10228 | 41 to 42 | 360 to 444 | 3094 to 4197 | 0 |
| hypot | 2 | 4081 to 4176 | 23 | 217 to 226 | 1285 to 1295 | 0 |
| pow | 2 | 4576 to 4669 | 88 | 228 to 241 | 1342 to 1428 | 0 |

What it reads: no FMA and no scalar double arithmetic in any arm at any width under any of the three
flag sets. pow's arms came in a later run (23 November 2026, the same laptop, CI's widths per row),
so its 128-bit row is under `MaxVectorSize=16` only. From 256 bits up each arm is one copy of its
body and calls only its uncommon traps and the entry barrier: 2 calls for the blended arms of log,
log10, log1p and expm1 and for expm1's core, 4 for atan2's guarded arm, 3 for every other. asin,
acos and hypot take their square root as one `vsqrtpd`. Masking adds one blend and 10 to 59
instructions on AVX-512, 17 to 66 on AVX2, which has no mask registers (its `k` column is 0). At 128
bits every arm runs the Java fallback, 60 to 444 calls per method, as for exp. acos's 49
packed-double operations at 128 bits against 42 above are seven more `vsqrtpd`, 8 against 1: around
the fallback's allocations of boxed vectors C2 computes `sqrt(z)` again at its uses, each from the
same register, instead of keeping the one result (read in acos's 128-bit `finish` compile, by
mnemonic); the other operations count the same at both widths.

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
- **The Arm Neoverse N2 is a host for allocation readings, and has no timing result.** GitHub's
  `ubuntu-24.04-arm` runner was one CPU in three probe draws, and the same features in all 332
  jobs of the census (`HARDWARE.md`, "The Arm host"), with 128-bit vectors, so `benchmark.yml`
  runs it on `host: neoversen2` and `ci.yml`'s `alloc` job runs the check there. The four hosts
  above stay the only ones headline numbers come from; no file under `bench/results/` has a timing
  from this one, and whether the ports' arms should differ on Arm waits for host runs.
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
  `.github/workflows/benchmark.yml` takes a `host` input with exactly five choices (four x86
  hosts and the Arm one above) and maps each to a CPU string fixed in `bench/host.sh`'s
  table (the workflow's probe step runs
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
  `bench/pool/`, has since printed both as whole names); the Arm host's runner prints no model
  name, so its string is the kernel's `implementer 0x41 part 0xd49` for every core, the whole
  string, with `sve` and `sve2` (`HARDWARE.md`, "The Arm host");
  `bench/dispatch-bench.sh --host <host>` dispatches it and, when the run failed on that check,
  waits an hour and dispatches again, until a run passes, fails on something else, or (for the
  9V45 and the Arm host, or with `--attempts`) the attempts run out (`bench/dispatch-epyc.sh` is
  the same with `--host epyc7763`). A run that failed while fetching the script, before any step looked at
  the machine, is tried again too, each counting as an attempt; the third in a row stops it.
  The Arm host's default is 3 attempts, not none: its label gave one CPU in three probe draws, so
  a miss is most likely a changed pool, not a draw to wait out. Other CPUs (the EPYC 9V74, the
  Xeon Platinum 8370C) are common draws, and
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
  common path and, when any lane is rare, sends those lanes to `StrictMath.exp`. The finish was
  first a call that took vectors and boxed (above); written out in the body it allocates nothing at
  256 and 512 bits. By minimums in ns per 1024 doubles at 256 bits, blended against finish: on the
  EPYC 7763 (`bench/results/exp-epyc7763-2026-11-20.txt`, two runs) 5,581 and 5,424 against 2,749
  and 2,727 on common data, and 6,429 and 6,369 against 4,292 and 4,256 on mixed data; on the Xeon
  Platinum 8573C (`bench/results/exp-xeon8573c-2026-11-21.txt`, one run) 2,670 against 2,177 and
  5,891 against 3,446. The blend over the finish is 1.99 to 2.13 on the EPYC on common data and 1.50
  to 1.57 on mixed data, at 256 bits and the preferred species; on the Xeon it is 1.23, 1.29 and
  1.37 on common data (at 256 bits, the preferred species and 512 bits), where 1.3x lies inside the
  range, and 1.70 to 1.84 on mixed data. The finish is lower on every one of those rows on both
  hosts. Mixed over common data, by minimums, the blended arm reads 1.15 to 1.19 on the EPYC and
  2.21 to 2.62 on the Xeon, and the finish arm 1.55 to 1.60 and 1.58 to 1.99, in the same files;
  they give no cause for the Xeon's larger slowing, and
  `bench/results/exp-kinds-xeon8573c-2026-11-21.txt` times each kind of rare input apart. Both files
  time `exp` as of a5683c3 and a1db6df, and `Exp.java` has changed since by a fault constant, a
  local and the finish's first eight lanes written out with constant indices (issue #37), so no run
  has timed today's body as a whole: the pair above times rows of it after that change, on one
  host. `VFdlibm.exp` keeps the finish arm, which those files read lower than the blend by more than
  1.3x on mixed data at 256 bits and the preferred species on both hosts and at 512 bits on the
  8573C (the `*Max` rows of the files committed so far time the finish that boxed before issue #37
  and are left out), and to which issue #30's rules send a tie;
  provisionally, until a host run revises it: a second run of the Xeon 8573C, a first of the
  other AVX-512 hosts or a pair. Each
  port measures both on common and mixed
  data, decides per branch, and records the decision in its pull request (`PLAN.md` section 5).
- **A blended alternative costs its whole form in every lane.** exp computed the C's near form and
  its `k == 0` form for every lane and blended them. Proving that the far form and the general tail
  already give those lanes' results (`PROOFS.md`, L1 and L2) left one form. With native AVX-512 at
  512 bits the hot path went from 77 instructions to 52, two `vdivpd` to one, 11 blends to 2 and 5
  mask spills to none; at 256 bits under `-XX:UseAVX=2`, from 107 to 64, with 11 blends to 2, 25
  constant broadcasts to 12 and 4 spills to none (`bench/asm/README.md`). Before blending two of the
  C's forms, look for a proof that one covers the other's lanes; count the result with `bench/asm/`
  before timing it. Timed as a pair against its base on the Xeon Platinum 8573C
  (`bench/results/exp-onediv-xeon8573c-2026-11-03.txt`, five rounds a side), the folded body is
  1.33x to 1.48x faster by the median on common data at 256 bits, 512 bits, the preferred species
  and `SPECIES_MAX`, and 1.18x to 1.31x on mixed data, and none of those rows is slower by the
  median or by the best iteration.
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
- **The per-lane finish must not be a call that takes vectors.** Late in a large body C2 refuses to
  inline the finish (`Log::finishPerLane ... failed to inline: NodeCountInliningCutoff`, under
  `UseAVX=2`), and a refused call receives its vector arguments boxed. log's first finish took `(x,
  result, rare)` and, on mixed data at 256 bits under `UseAVX=2`, cost 9150 ns and 51648 B/op per
  1024 doubles against 3062 ns for the blend and 4104 for the scalar loop (2.2x slower than not
  vectorising; laptop numbers, the FIRST VERSION block of
  `bench/results/log-laptop-avx2-2026-11-02.txt`). Passing arrays and `rare.toLong()` instead cut it
  to 3062 ns and 5184 B/op. Doing the finish in the body with no helper, a loop over the set bits of
  `rare.toLong()` with `result.withLane(lane, StrictMath.log(x.lane(lane)))`, made it ~0 B/op and
  2921 ns: the 256- and 512-bit `lane` and `withLane` switch on the index to a constant lane, which
  C2 intrinsifies (`extract`, `insert`), and the only call left is `StrictMath.log` on a double.
  exp's finish used to be such a call and boxed on mixed data, and cost more there than the blend
  did on the EPYC 7763 (the old code's run is `bench/results/exp-epyc7763-2026-11-02.txt`). The same
  in-body loop removed that at 256 and 512 bits; at `SPECIES_MAX` exp's finish boxed on mixed
  data until its first eight lanes were written out (above).
- **The laptop understated the boxed finish's cost on mixed data.** Under `UseAVX=2` on the laptop
  exp's boxed finish tied the blend on mixed data; on the EPYC 7763 it lost to the blend
  (`bench/results/exp-epyc7763-2026-11-02.txt`). Read each finish arm's `gc.alloc.rate.norm` on
  mixed data, where the rare lanes run, not only on common data: an in-body finish allocates nothing
  at 256 and 512 bits, so anything above zero there is boxing (or arrays the finish makes on
  purpose), and its cost grows on the EPYC.
- **The arm choice is per function, and a fix can reverse it.** With the boxed finish the data said
  to blend log; with the finish in registers the laptop read it ahead on common data, level on mixed
  at 256 bits (1.18x and 1.05x under `UseAVX=2`), and the blend ahead at 512 bits on mixed data
  (1.36x on the laptop's AVX-512, `bench/results/log-laptop-2026-11-02.txt`; laptop numbers). The
  host files read the same shape by minimums. On the EPYC 7763
  (`bench/results/log-epyc7763-2026-11-20.txt`, two runs) the blend is 1.21 to 1.24 times the finish
  on common data and 1.01 to 1.03 on mixed data, at 256 bits and the preferred species, and the
  finish reads 0.017 B/op or less on its 256-bit and preferred rows. On the Xeon Platinum 8573C
  (`bench/results/log-xeon8573c-2026-11-21.txt`, one run) blended over finish is 1.24 (256 bits),
  1.32 (preferred species) and 1.32 (512 bits) on common data, the last two just outside 1.3x with
  the finish lower, and 0.97, 0.77 and 0.77 on mixed data, where the blend is lower and the last two
  sit at the 1.3x line (finish over blended 1.30 and 1.29). At 256 bits, the one width both hosts
  run natively, they read alike: the finish is lower on common data (blended over finish 1.22 to
  1.24 on the EPYC, 1.24 on the Xeon) and level on mixed data (1.02 to 1.03 and 0.97, within 5%, so
  no sign is read). On the Xeon the blend is lower on mixed data at all three widths, by more only
  at the two widths with no native EPYC counterpart (its preferred species is 256 bits, and its
  512-bit rows run in the Java fallback), so the files show no disagreement between the hosts at
  equal width. Both files time the `log` of a5683c3 and a1db6df, before the finish's first eight
  lanes written out with constant indices (issue #37); `LogBenchmark` gained its `finishMax` row
  after the EPYC run, which is why that file has none. The Xeon's file of today's `log`
  (`bench/results/log-xeon8573c-2026-11-22.txt`, a run and a re-run) reads no direction between
  the arms: blended over finish at 256 bits is 1.29 and 1.30 on common data and 0.97 on mixed
  data; at 512 bits, which its preferred species is, 1.28 to 1.36 on common data, the preferred row
  of the re-run inside 1.3x, and on mixed data the finish 1.13 to 1.17 times the blend, inside the
  line the 21 November file sat on. `VFdlibm.log` keeps the finish arm by the rules issue #30 set
  (a tie within 1.3x goes to the finish arm), provisionally until a host run revises it: an EPYC
  run of today's code, a first of the other AVX-512 hosts or a pair. Measure the finish only once
  it allocates nothing.

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
- **A third shape: blend the rare paths behind one test.** `log1p`'s rare paths (`x <= -1`, `|x| <
  2^-29`, `+inf` and NaN, and `|f| < 2^-20`) are a few vector operations each. Computing and
  blending them only when a vector holds a rare lane (`if (rare.anyTrue())`) never allocates.
  Against the per-lane finish written in the body (below) and the blend of every lane, by minimums
  in ns per 1024 doubles over two runs each, laptop numbers: under `UseAVX=2` at 256 bits the
  guarded and finish arms are level on common data (2770 and 2745) and on mixed data (3034 and
  2952), and the blend's minimums are 3189 to 3264
  (`bench/results/log1p-laptop-avx2-2026-11-02.txt`). The laptop's AVX-512 build was measured the
  same way at 256 and 512 bits (`bench/results/log1p-laptop-2026-11-02.txt`). In both files the arms
  are within 1.3x of each other on every row, and no direction is read. The host files of today's
  `Log1p` read the three arms by minimums at 256 bits, the preferred species and `SPECIES_MAX`,
  and on the Xeon 512 bits. On the Xeon Platinum 8573C
  (`bench/results/log1p-xeon8573c-2026-11-22.txt`, a run and a re-run) guarded over finish is 0.96
  to 1.02 on common data and 0.85 to 0.93 on mixed data, and blended over finish 1.13 to 1.22 and
  0.93 to 1.00; on the EPYC 7763 (`bench/results/log1p-epyc7763-2026-11-23.txt`, a run and a
  re-run) guarded over finish is 0.98 to 1.03 and 0.92 to 0.95, and blended over finish 1.19 to
  1.20 and 0.99 to 1.00. Every arm is within 1.3x of the finish on every one of those rows in every
  run, so no direction is read, and the guarded arm reads lower on mixed data on both hosts, inside
  1.3x. By issue #30's rule 1 (a
  tie within 1.3x on every host that has a file goes to the finish arm) `VFdlibm.log1p` runs the
  finish arm since then: the owner had deferred the rule for log1p until the 8573C's second run,
  and neither file reads the guarded arm lower by more than 1.3x at any species. That is a tie,
  not a speed-up; a first run of the other AVX-512 hosts or a pair may revise the choice. The
  earlier host files time the code before the finish's first eight lanes were written out with
  constant indices (issue #37), and read the arms within 1.3x too: the EPYC 7763's
  (`log1p-epyc7763-2026-11-20.txt`, two runs, `Log1p` as of a5683c3, before a fault constant and a
  mask local were added), the Xeon's of 21 November (`log1p-xeon8573c-2026-11-21.txt`, one run,
  as of a1db6df) and of 3 November (`log1p-xeon8573c-2026-11-03.txt`, one run, at another commit
  and on a draw whose datapath read higher, `HARDWARE.md`); the last of them alone had guarded
  lowest on every row.
  A port whose rare paths are cheap in vector form should measure this shape beside the other two.
- **The in-body finish allocates nothing in log1p too.** log1p's finish was first a call,
  `finishPerLane(x, result, rare)`, and boxed: 52,928 B/op on mixed data at 256 bits under
  `UseAVX=2`, 2.0x slower than the guarded arm there (the superseded runs kept at the end of
  `bench/results/log1p-laptop-avx2-2026-11-02.txt`). Written out in the body as exp's and log's
  are, a loop over the set bits of `rare.toLong()` with `result.withLane(lane,
  StrictMath.log1p(x.lane(lane)))`, it reads ~0.01 B/op on mixed data at 256 and 512 bits, so
  nothing in it was refused in log1p's large body (the current runs in the same files). Its
  `SPECIES_MAX` form boxed as exp's did: 30912 B/op on mixed data at 512 bits and 36992 under
  `-XX:UseAVX=2` (`bench/results/max-finish-laptop-2026-11-20.txt`). An earlier exploratory run had
  measured a `withLane` loop at 47,232 B/op (quoted in the same superseded runs); whether that
  loop sat in a helper taking vectors was not recorded, and it most likely measured that call's
  boxing, not `withLane`.
- **The `hu == 0` path is not redundant: a sample said it might be, every input of a window
  found the witnesses.** s_log1p.c's `|f| < 2^-20` path stops its series at `f^3/3` (line 156),
  and a sample of 199,804,321 `hu == 0` inputs with `1+x` between 0.5 and 2^1024 showed 0 bit
  differences against `StrictMath.log1p`. The C sent down its general polynomial path instead
  differs from the C as written all the same, on about one input in 2^29 of the windows
  searched: 56 of the 2^35 doubles of raw bits `[0x3feffffa_00000000, 0x3ff00002_00000000)`,
  `1+x` in about `[2 - 3*2^-20, 2 + 2^-19)` (`k = 1`), and 20 of the 15,032,385,536 of raw bits
  `[0xbfdffffe_00000000, 0xbfe00001_80000000)`, `1+x` in about `(0.5 - 3*2^-22, 0.5 + 2^-21]`
  (`k = -1`); at each the C as written is `StrictMath.log1p` and the general path gives the
  next double. The counts, the
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

- **log10 on the benchmark hosts, 20 and 21 November 2026.** By minimums at 256 bits, the preferred
  species and, on the Xeon, 512 bits. On the EPYC 7763
  (`bench/results/log10-epyc7763-2026-11-20.txt`, two runs) blended over finish is 1.24 to 1.28 on
  common data and 1.06 to 1.13 on mixed data; on the Xeon Platinum 8573C
  (`bench/results/log10-xeon8573c-2026-11-21.txt`, one run) 1.18 to 1.25 on common data and 0.92 to
  0.98 on mixed data. Every ratio is within 1.3x, the EPYC's 1.28 on common data being the nearest
  to it, so no direction is read. The finish is lower on common data on both hosts. On mixed data at
  256 bits, the one width both run natively, blended over finish is 1.13 and 1.10 on the EPYC and
  0.98 on the Xeon: the hosts differ in size, the EPYC reading the finish lower by more than 5% and
  the Xeon level (within 5%), both inside 1.3x. At the Xeon's preferred species and 512 bits, which
  have no native EPYC counterpart, blended over finish is 0.94 and 0.92. Both files time `Log10` as
  of a5683c3 and a1db6df, before the finish's first eight lanes written out with constant indices
  (issue #37); the pair of that change (the exp entry above) reads log10's mixed finish rows 2.6%
  to 4.0% slower by median and 3.7% to 4.1% by best on the EPYC 7763, the cause not known.
  The Xeon's file of today's `Log10` (`bench/results/log10-xeon8573c-2026-11-22.txt`, a run and a
  re-run, with `*Max` rows of today's finish) reads blended over finish 1.15 to 1.26 on common data
  and 0.89 to 1.01 on mixed data, all inside 1.3x in both runs: no direction. `VFdlibm.log10` calls
  the finish arm by the rules issue #30 set (a tie within 1.3x goes to the finish arm),
  provisionally until a host run revises it: an EPYC run of today's code, a first of the other
  AVX-512 hosts or a pair.

## What the expm1 port taught

- **A reduction shared with another port inherits its proofs through a pinned hinge.**
  s_expm1.c reduces exactly as e_exp.c does, with the same `invln2`, `ln2_hi`, `ln2_lo` and band
  words, so exp's far-form fold (L2) and its sign-bit `halF` (L5) hold for expm1 once a test
  pins the five values to exp's (`PROOFS.md`, L10, H13), and the SMT files rendered from `Exp`
  check those steps for expm1 too; `proofs/expm1_reduction.smt2` holds the values to exp's
  under the solvers as well, and checks the unreduced lanes' step (`c = +0`) and, at four
  doubles, that the formula's `k` would be wrong there; the forcing over the whole word is the
  port's blend, which `Expm1Test.foldBoundaries` samples and the planted fault
  `expm1.dropKForcing` removes (six `Expm1Test` cases fail with it in a run of
  `-Dtest=Expm1Test`, which CI does not make: 13 of the 20935 inputs of `thresholds()` differ and
  822 of the 28698 of `foldBoundaries()`; CI requires every arm to fail at each width on
  `faultRunInputs()`; exp's twin is `exp.dropKForcing`, `PROOFS.md`, L1).
  The forcing still needs its own witness: the C reduced with the formula's `k` first differs
  from `StrictMath.expm1` at `0x3fd62e42_ffffffec`, and for negative x at
  `0xbfd62e42_fffffff3`, not at the word's top double, so a test of the top alone would pass
  without the forcing.
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
- **expm1 on the benchmark hosts, 20 and 21 November 2026.** By minimums at 256 bits, the preferred
  species and, on the Xeon, 512 bits. On the EPYC 7763
  (`bench/results/expm1-epyc7763-2026-11-20.txt`, two runs) the finish arm is lowest on every row:
  blended over finish is 1.17 to 1.18 on common data and 1.18 to 1.20 on mixed data, guarded over
  finish 1.13 to 1.15 and 1.07 to 1.08 (the EPYC's run of 21 November, below, reads the first of
  those 1.02). On the Xeon Platinum 8573C
  (`bench/results/expm1-xeon8573c-2026-11-21.txt`, one run) blended over finish is 1.04 to 1.16 on
  common data and 0.90 to 0.94 on mixed data, guarded over finish 0.97 to 1.05 and 0.85 to 0.87: on
  common data guarded is lowest at 256 bits and finish at the preferred species and 512 bits, on
  mixed data guarded is lowest at all three. Every arm is within 1.3x of the others on every one of
  those rows in both files, so no direction is read on either host, and the hosts differ in which
  arm has the lowest minimum on mixed data (finish on the EPYC, guarded on the Xeon). The Xeon file
  of 4 November (`bench/results/expm1-xeon8573c-2026-11-04.txt`, one run, at another commit) had
  guarded lowest on the preferred rows of both kinds of data; the file of 21 November has finish
  lowest there on common data. Both files time `Expm1`
  before its core entry (below) and before the finish's first eight lanes written out with
  constant indices (issue #37), and `Expm1.java` changed between their two commits too (a fault
  constant and a local in the k conversion, as for `Exp`), so the cross-host comparison rests on
  two code versions, and the EPYC file below on a third. The EPYC 7763's run of 21 November at 9b9cc7b
  (`bench/results/expm1-epyc7763-2026-11-21.txt`, one run) was the first with core rows
  (`expm1Core`, no arm of `VFdlibm`); it still times the finish before the eight lanes, and reads
  blended over finish 1.19, guarded over finish 1.02 on common data and 1.07 to 1.08 on mixed
  data, all within 1.3x, so no direction either. Its guarded arm reads about 12% lower on common
  data than in the 20 November runs, which no pair or re-run backs and the file does not
  attribute to the code. The EPYC files' 512-bit rows run in the Java fallback and measure no
  expm1.
- **expm1 on the benchmark hosts, 22 and 23 November 2026: the finish arm by rule 1.** The files
  of today's `Expm1`, after the finish's first eight lanes were written out, read the three arms
  by minimums at 256 bits, the preferred species and `SPECIES_MAX`, and on the Xeon 512 bits. On
  the Xeon Platinum 8573C (`bench/results/expm1-xeon8573c-2026-11-22.txt`, a run and a re-run)
  guarded over finish is 0.93 to 1.07 on common data and 0.84 to 0.92 on mixed data, and blended
  over finish 1.08 to 1.18 and 0.90 to 1.00; on the EPYC 7763
  (`bench/results/expm1-epyc7763-2026-11-23.txt`, a run and a re-run) guarded over finish is 1.02
  to 1.03 and 1.04, and blended over finish 1.18 to 1.20 and 1.14, the finish lowest on every row
  there. Every arm is within 1.3x of the finish on every one of those rows in every run, so no
  direction is read, and the hosts differ in which arm is lowest on mixed data (guarded on the
  Xeon, finish on the EPYC). By
  issue #30's rule 1 (a tie within 1.3x on every host that has a file goes to the finish arm)
  `VFdlibm.expm1` runs the finish arm since then: the owner had deferred the rule for expm1 until
  the 8573C's second run, and neither file reads the guarded arm lower by more than 1.3x at any
  species. That is a tie, not a speed-up; a first run of the other AVX-512 hosts or a pair may
  revise the choice. Both files have core rows: finish over core is 0.99 to 1.08 on the Xeon and
  1.17 to 1.23 on the EPYC on common data, inside 1.3x.
- **Lefevre's direction bit is the 54th bit of the exact |result|.** testlibm-data's last field
  agreed with that for all 7578 `em1` lines (3836 ones, 3742 zeros), not with "the exact
  result is above the rounded one", which splits about evenly.
- **A core entry for a port that nests expm1 (21 November 2026).** `Expm1.expm1Core` is the
  finish arm's body in a fourth mode, without its rare block, the `live` blend and the finish,
  and `Expm1.expm1Rare` is the mask of the lanes it leaves to a per-lane finish, as `Exp.expCore`
  and `Exp.expRare` are: `StrictMath.expm1` in every lane outside the mask, an unspecified value
  inside it. The same body gives no new departure, and the mask is written apart from it, with
  the body's own expressions, so C2 computes them once when a caller uses both. Its rows in
  `Expm1Benchmark` (`core256`, `corePreferred`) pass `bench/alloc-check.py`, at the host's flags
  and as AVX2, on `common` and `mixed` data. Four of expm1's five faults reach it, so its test
  arm is caught by them as the other arms are; `guardMissesFilter` belongs to the guarded arm.

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
  catches it at input 16,384 of the first thread at seed 0x5eed (`NightlyTest`'s fault runs;
  `NightlyTest.DRAWS` has the indices of the others). The sweep is for bands nothing
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
- **`Fuzz --draws N`.** It bounds each thread to N inputs besides `--seconds`, so that a run
  draws the same inputs on a slower or busier machine, which a time limit does not.
  `NightlyTest`'s planted-fault test passes it; `dev/nightly.sh` passes only `--seconds`, so to
  replay a bounded run call `Fuzz` directly (its class comment has the command).
- **Functions of two arguments** (`Nightly.PairFunction`, test-only code like the rest: a port
  registers its function in `PairFunction.KNOWN`; `atan2` is the first, and `PairHarnessTest` runs
  the harness on a toy, `x * 0.5 + y`, with planted faults). The workflow lists such a function in
  the plan job's `pairs`, as well as in `all`, and gives its sweep jobs an `argument` and an
  artifact suffix (`dev/nightly.sh` reads `NIGHTLY_ARGUMENT`, and names its log
  `sweep-<function>-arg<N>-`): the input `pair-passes` is `alternate` (the default: the first
  argument for a rotation of `nights` nights, then the second for as many), `both` (two jobs a
  stripe) or `1` or `2`. A function of one argument has no `argument` field, so its entries are what
  they were. The sweep takes `--argument 1` (the default) or `2`: the high words swept are that
  argument's, each with its three low words, and each swept value gets a partner for the other
  argument drawn from the seed, the argument and the input's index alone (`Sweep.partner`; a quarter
  each of special values, the swept value's exponent, an exponent within 64 of it, and random bits),
  so a pair replays from the seed and its replay line says `--argument`. A pass checks 3 * 2^32 of
  the 2^128 pairs, so **what it can miss**: a bug that needs a high word of each argument at once,
  which a partner drawn as above meets with probability about 2^-32 for a random partner and more
  where the two exponents are near, and which the fuzz meets because its draws put both arguments at
  their edges at once (`toy2-xy` in `PairHarnessTest`: both sweeps pass it, the fuzz finds it). The
  masked form of an arm keeps the first argument's bits in a lane that is not live
  (`Oracle.assertPairMasked`'s contract). A pair function's mismatch file, and a hard-cases file of
  one, say `# arity: 2` before the first input and hold `0x<x> 0x<y>` on a line:
  `Inputs.hardCasePairs` reads one, and `Inputs.hardCases` refuses it by that line.
  `hard-cases/atan2.txt` is one: Lefevre's data has no tag of a function of two arguments, so
  `dev/testlibm-extract.py atn atan2` derives its 20820 pairs from the 1735 `atn` inputs `t`, as
  `(+-t * 2^j, +-2^j)` for `j` in 0, 1, -5 (so `|y/x| = t` exactly), which are inputs of atan's core
  through `atan2`'s own code and not hard cases of its quadrants; `HardCasePairsTest` checks the
  file's shape. A branch on the two arguments' high words relative to each other (`e_atan2.c`'s `k =
  (iy - ix) >> 20`, which the edges of each argument cannot reach) is listed in
  `Fuzz.PairEdges.wordGaps`, the first argument's absolute high word less the second's at the values
  the C tests (for an `atan2`: `61 * 2^20` and `-60 * 2^20`, and the word below each, which
  `Nightly.PairFunction.ATAN2` lists); one pair in ten is then drawn at one of them, and a function
  that lists none draws as before. `FuzzWordGapsTest` shows a fault at exactly one gap found by the
  fuzz at every width and not found when the gaps are not listed.
- **Pairs of one high word, both low words random** (issue 173). hypot's port was one ulp off
  `StrictMath` where `x` and `y` share a high word and `|y|` is the larger (PROOFS.md, L21), and
  that shows only when both low words are random: 18 of 9 * 10^8 such pairs, 0 of 10^8 with the
  fuzz's patterned low words (0, 1, all ones, all ones less one, random one time in five). hypot's
  gap of 0 drew such pairs with patterned low words, and the fuzz found it once in about 5 * 10^9
  draws. So every function of two arguments now draws them: in the fuzz one pair in ten of those
  not drawn at a gap (`Fuzz.drawSharedWord`: the first argument from its edges or random bits,
  the second of its absolute high word, both low words and the second's sign random;
  `FuzzWordGapsTest` checks the share), in the sweep one partner in twelve (`Sweep.partner`'s
  same-exponent kind, on the third input of a high word, whose swept low word is random, takes
  the swept value's high word; `PairHarnessTest` checks it), so a pass checks about 10^9 such
  pairs, and in the unit tests `Inputs.sharedWordPairs` (`sharedHighWords` in `Atan2Test`,
  `HypotTest` and `PowTest`). The same black box found no such pair for atan2 or pow in 2 * 10^6
  each. The change moved every pair function's streams: a fuzz seed or a sweep slice of a pair
  function recorded before it (issue 173's night included) draws other pairs now, so replay an
  old night's failures by their bits (its mismatch file), not by its seed.
- **One comparator for a function of two arguments** (`PairBits.matches(candidates, x, y, want,
  got)`, test code). Every comparison of a pair function's lane goes through it: `Oracle.assertPair`
  and the live lanes of `assertPairMasked` (a lane that is not live keeps `x`'s raw bits), and the
  nightly's `PairChecker`, so its mismatch file and the replay of that file use it too. A new
  comparison of such a function written with `doubleToRawLongBits` is a defect. A function declares
  its `PairBits.Candidates` on its `PairFunction` and passes them to the asserts, and there is no
  default: `OPERANDS` for `atan2` and `pow`, `MAGNITUDES` for `hypot`. A mismatch row of a lane of
  two NaNs says "one of" its two candidates and the oracle's own bits. Which candidate wins is not
  specified and varies with the tier, for `hypot` too, where it is not the NaN of the larger high
  word. `pow(x, +-0)` is 1 whatever `x` is, and nothing else returns before the NaN case: `pow(1.0,
  NaN)` is NaN. The `jar-check.sh` consumer cannot use `PairBits` (the jar holds no test classes):
  its `same` compares one argument's functions, and when `atan2` arrives its two-argument form takes
  a copy of the rule written in the consumer, with a lane of two NaNs of different bits in its
  inputs. `PairNanTest` is the proof it can fail: toys with one change fail it, as do a function
  declared the wrong way and one that declares nothing, and a comparator changed to take the
  oracle's bits unchecked, or to keep the sign under `MAGNITUDES`, fails `PairNanTest` (tried by
  hand on this version).
- **What a pass over a function of two arguments costs** (laptop, 21 November 2026,
  `bench/results/pair-sweep-laptop-2026-11-21.txt`; it was taken before `atan2` was ported, so it is
  two stand-ins run through `PairCostProbe`, not the port, which it does not time; the port's
  timings are `bench/results/atan2-xeon8573c-2026-11-22.txt` and `atan2-epyc7763-2026-11-22.txt`). A
  stripe of 2^24 high words at 256 bits took 0.9 s for the harness alone (`toy2`) and 1.8 s for an
  arm that calls `StrictMath.atan2` lane by lane, against 2.9 s and 3.6 s for the unary sweeps of
  exp and log1p, at 4 threads on 4 CPUs. Scaled to a whole pass (256 stripes), the x86 sweep jobs of
  the arm-bound stand-in come to, on that loaded laptop and so only as an indication, about 16
  runner-minutes on an AVX2 draw and 23 on an AVX-512 one, and the second argument's pass costs what
  the first does: both passes in one night are twice that, one alternating pass is once. The file
  has the method, the three runs of each row and its limits; the nightly's own logs replace it once
  a pair function runs. It also records that, on two NaNs, the oracle's `StrictMath.atan2` and a
  loop of calls to it differed in the payload in 12 of 12 runs (cause not read), so a pair
  function's check may need a canonical NaN.
- **Checking that the unary nightly is unchanged** (`dev/unary-output-check.sh`, run by a person
  after a change to `Sweep`, `Fuzz`, `Nightly` or `Inputs`, not by CI). `check <base-ref>` builds
  the base in a worktree, runs every unary function's sweep and fuzz on fixed slices with one
  thread at 128, 256 and max bits, four planted faults and the usage errors on both trees, and
  compares the 101 logs and mismatch files with the lines that vary by machine dropped: it took 48
  seconds on the laptop. `--self-test` shows the comparison failing on a changed log and on a
  planted fault. The workflow's plan step is checked the other way: its `run:` block, run on the
  same inputs before and after a change, must write the same `GITHUB_OUTPUT`.
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
    the draws, keeping the EPYC 9V74's draws without AVX-512 apart from those with it.
- **Why x86 sweeps 128 bits by a slice (the owner's decision, 2 November 2026).** The 128-bit
  species is 80 to 91% of a whole sweep's cost on a 256-bit x86 machine (the medians above: exp 80.7
  of 100.2 minutes, expm1 262.1 of 288.0): x86 C2 lowers no mask at two 64-bit lanes (above), so
  that width runs lane by lane in Java, and Varka declines 128-bit doubles on x86, so no user of the
  port runs it there. So on x86 the nightly sweeps every high word at 256 bits and max (and 512 on
  an AVX-512 draw), and at 128 bits a rotating slice: on night n of 32, stripe `n*shards + j` of
  `32*shards` in shard j's job, every high word in 32 nights. At 1/32 the slice costs 17.9 minutes a
  night, 16% of the x86 sweep jobs' sweeping, while covering it every month; the 128-bit logic
  itself is swept whole every night on Arm, natively, so what the slice alone guards is x86's
  per-lane fallback. The fuzz still runs at 128 bits on x86 every night. The dispatch inputs
  `slice-nights` and `slice-night` change or replay the rotation; `nights` and `shards` do the same
  for the rest.
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
    most 20 jobs of the night at once (the matrices' `max-parallel` 19, and the proofs), and a
    median span of at most 90 minutes**. (Until 21 November 2026 the first number was 16, with
    the matrices' 15.) It replaces the earlier target of 2 to 3
    runner-hours a night, which the five functions overran at 6 and which would not have been
    kept at 13.
  - **How the span follows the schedule.** A kind of job (sweep, fuzz, Arm) runs on its
    `max-parallel` slots, and the span is its slowest kind's minutes over its slots, plus 8
    minutes (the night's 54 against the fuzz's 138 over 3 slots, 46). At 13 functions of the
    average 72 minutes, that is 13/5 of the night's medians (sweep 309, fuzz 359, Arm 273: 941
    runner-minutes, 15.7 hours; 13 times 72 is 936), the old slots 8, 3 and 4 give 120 minutes
    of fuzz and a span of 128. Slots 5, 6 and 4, the best split of 15 by this model, give 62,
    60 and 68, a span of 76 (and 34 for five functions, from 24, 23 and 26). So the defaults
    keep `fuzz-seconds` 240, `nights` 1, `shards` 2 and `arm-shards` 4, and `max-parallel` was
    5 for the sweep, 6 for the fuzz and 4 for Arm until 21 November 2026 (the entry on slots 6,
    8 and 5 below).
  - **How firm that is: not very.** (This entry and the next are the state of 20 November 2026,
    on slots 5, 6 and 4; the slots are now 6, 8 and 5, below.) The 13-function fit is
    unverified, and no night had run on the 5, 6 and 4 slots then. The model divides a kind's
    minutes by its slots and counts no whole waves of jobs, so 76 is a lower bound; the sweep's
    62, plus its longest job, 19.8, plus the 8 minutes, gives 90 as an estimate of the worst case, which is the budget itself: with no
    margin. (It adds a median to a longest job, so it is neither a median nor a maximum.) The 8
    minutes are one fit (54 against 46). Each further average function adds about 5 minutes
    (Arm's 273 over 13 functions and 4 slots). For the five functions the table measured the
    prediction was 34 minutes, Arm's 105 over 4 slots (26) plus the 8; the sweep's 119 over 5
    slots (24) and the fuzz's 138 over 6 (23) are below it. The sweep's slots go from 8 to 5, and
    its 10 jobs of about 11 minutes run in two waves on either, so for five functions that move
    costs nothing. It pays for the fuzz's 6 and Arm's 4 slots under the then cap of 15 matrix jobs,
    and at 13 functions the sweep's 26 jobs take 6 waves on 5 slots where 8 would take 4. The
    first nights on the new slots check both: fetch them with `pool.py fetch` and read the span from
    `pool.py cost`. The budget is a median span of 90 minutes, so a median of the nights above
    90 breaks it and calls for one of the levers below; a single long night does not. At 13
    functions a median between 76 and 90 is inside it, and shows how far the model is off.
  - **Seven functions' figures; the eighth's cost is not measured.** The table is of five; sinh
    and cosh join it, so the nightly ran seven, and atan makes eight. At the five's average cost
    the model gives 167, 193 and 147 minutes for the sweep, the fuzz and Arm, over 5, 6 and 4
    slots 33, 32 and 37, a span of 45 (a lower bound, as above) for seven functions. The model
    has not been rerun for eight, and atan's own cost is not measured, nor are sinh's and
    cosh's: the first nights with them are, with `pool.py fetch` and `cost`, and this entry is
    requoted from those.
  - **Eleven functions: unmeasured when written (21 November 2026).** This entry was written before
    a night ran them, and its extrapolations below, the 99 minutes for eleven among them, are of
    that day: the first scheduled night of eleven took a span of 57.8 minutes and 720 runner-minutes
    (the entry on twelve functions, below, with its file). asin, acos and atan2 join the night's
    list, so the scheduled night runs eleven (atan2 sweeps one argument a night by
    `pair-passes=alternate`, so its jobs are one function's worth at the defaults), and no night has
    run them. Everything here is an extrapolation, not a reading. An estimate made for ten functions
    of one argument, not derived in this document, was about 730 runner-minutes at the default 2
    shards and 4 Arm shards (the 72 runner-minutes a function of the budget entry above would give
    720); atan2 adds a function whose cost is not estimated. The plan job at the defaults makes 22
    sweep entries, 44 for Arm and 22 for the fuzz, against the 256 each may hold; a run of nine or
    more functions at 32 shards (288 for nine, more for eleven) exceeds that cap and the plan job
    refuses it, and eight fit. No span is modelled here: the entry below computes its figures for
    eight functions (about 72 minutes); scaling that by the count of functions gives about 90 for
    ten and 99 for eleven, the budget's 90 and over it, which is arithmetic on an extrapolation and
    no prediction. The first nights with eleven are the measurement, read as above; the levers below
    are the answer if the median passes 90. **What `alternate` does about the slice:** the argument
    it picks is `days / nights % 2 + 1` and the slice's night is `days % slice-nights`, and at the
    defaults (1 and 32) the parity of the day is the parity of the slice's index, so a slice would
    keep one argument for good, half of each argument's 128-bit slices never swept. The rotation
    therefore turns over once every `slice-nights` nights when `nights` is below it (`(days / nights
    + days / slice-nights) % 2 + 1`), so a slice meets both arguments in two cycles of 32 nights,
    and the same argument is swept on two nights running at each turn. Simulated over 20000 days for
    `nights` and `slice-nights` of 1 and 32, 2 and 32, 4 and 32, 32 and 32, 64 and 32, 1 and 1, and
    2 and 1, every slice meets both; the plan job was not run on a real night. `pair-passes=both`
    sweeps both every night.
  - **Twelve functions: an estimate from the eleven's night (23 November 2026).** hypot joins the
    list as a second function of two arguments (`pairs`), swept one argument a night by `alternate`,
    the same argument as atan2's every night (one rotation for both). The scheduled night of 22
    November (run 44994568244, eleven functions, slots 6, 8 and 5) took 720 runner-minutes and a
    span of 57.8 minutes, by `bench/pool/pool.py cost bench/pool/nightly-cost-2026-11-22.tsv` (its
    row for that run; the file also holds the day's dispatch, run 44999281363, of six functions at 8
    shards with both arguments of atan2, no night of the defaults): 210 in the sweep, 301 in the
    fuzz and 209 on Arm, 65.4 a function by the mean, and atan2 the most, 87, summed from that run's
    rows of atan2's jobs in the file (35.1 of sweep in 2 jobs, 29.2 of fuzz in 2, 22.7 on Arm in 4).
    hypot adds 8 jobs (2 sweep, 2 fuzz, 4 Arm), and at atan2's cost about 87 runner-minutes, 807 a
    night. For the span, replaying that night's jobs in the order they started on 6, 8 and 5 slots,
    hypot's appended at the end, gives 46 to 47 minutes for each kind where the night took 49 (fuzz)
    to 58 (Arm), since the gaps between one job and the next are not modelled; so the model's
    increase is added to the measured 57.8, not its total: with hypot's jobs at atan2's durations
    Arm, the kind that ended last, gains 4.4 minutes (the sweep 1.1, the fuzz 0.1), and with them at
    each kind's longest job 8.4 (3.8 and 4.1), a span of about 62 to 66 minutes, under the budget's
    90. It is an estimate on one night, with hypot's cost assumed to be atan2's, not a reading, and
    high for the sweep, since atan2's sweep checks two arms and hypot's one (the fuzz runs a fixed
    time whatever the arms, so its minutes are the same); the first nights with twelve are the
    measurement, read as above. The plan job at the defaults makes 24 sweep entries, 48 for Arm and
    24 for the fuzz; 16 shards make 192 sweep entries (224 with `both`), and 32 shards of all twelve
    (384) are refused.
  - **Thirteen functions: an estimate from the eleven's two nights (23 November 2026).** pow joins
    the list as a third function of two arguments (`pairs`), swept one argument a night by
    `alternate`, the same argument as atan2's and hypot's every night (one rotation for all three).
    No night has run hypot or pow yet; the two scheduled nights of eleven are the readings,
    `bench/pool/pool.py cost` on `bench/pool/nightly-cost-2026-11-22.tsv` (run 44994568244: 720
    runner-minutes, a span of 57.8 minutes) and `nightly-cost-2026-11-23.tsv` (run 45131086418: 724
    and 66.2; sweep 211, fuzz 305, Arm 208), and atan2 cost 87 runner-minutes on each, summed from
    its jobs' rows (the most of any function on 22 November; on 23 November expm1 cost 88.2 to
    atan2's 87.0). **How pow's cost is estimated:** as hypot's was, at atan2's, the costliest
    function of two arguments measured and within a runner-minute of the costliest of all, since
    pow's own sweep is not measured on a runner; pow has one arm (atan2 has two) but finishes its
    rare lanes, a negative `x` among them, by `StrictMath.pow` lane by lane, so its sweep may cost
    more than hypot's, and the bounds below cover that. hypot and pow add 16 jobs (4 sweep, 4 fuzz,
    8 Arm), about 174 runner-minutes at atan2's cost, 894 and 898 a night. For the span, each
    night's jobs are replayed in the order they started (jobs that started in the same second by
    name; that order moves a figure by up to half a minute) on 6, 8 and 5 slots, with copies of
    atan2's jobs appended in the same order, once for hypot and once for pow, and the model's
    increase is added to the night's measured span (the model reads 46 to 47 minutes a kind where
    the nights took 49 to 66, the gaps between jobs not modelled): with hypot's and pow's jobs at
    atan2's durations, Arm, the kind that ended last both nights, gains 8.7 and 8.4 minutes (the
    fuzz 8.7 and 8.5, the sweep 3.1 and 2.2), a span of about 66.5 and 74.6; with them at each
    kind's longest job, Arm gains 20.4 and 19.9, about 78 and 86; and with pow's sweep at twice
    atan2's on x86 and on Arm, the sweep gains 20.2 and 19.3, ending at about 77 both nights, and
    Arm 11.8 and 11.5. So the estimate is about 66 to 75 minutes, under the budget's 90, with its
    bounds at 86, near it. It is an estimate on two nights, with hypot's and pow's costs assumed,
    not a reading; the first nights with thirteen are the measurement, and if their median passes 90
    the levers below are the answer. The plan job at the defaults makes 26 sweep entries, 52 for Arm
    and 26 for the fuzz; 16 shards make 208 sweep entries, and 256 with `both`, exactly the cap, so
    a fourteenth function is refused there; 32 shards of all thirteen (416) are refused.
  - **Slots 6, 8 and 5, and the 20 jobs (21 November 2026).** The matrices' `max-parallel` is 6
    for the sweep, 8 for the fuzz and 5 for Arm: 19, and the proofs job makes 20, all of the free
    plan's jobs while the matrices are full. The plan job runs before them and the fault-caught
    and alert jobs after, so nothing else of the night overlaps. Other jobs (a CI run on a pull
    request, a benchmark dispatch) share the same 20 and queue ahead of or behind the night's.
    The split (not redone for eleven) uses eight functions at the five's average cost, 191, 220 and
    174 minutes for the sweep, the fuzz and Arm in 16, 16 and 32 jobs, which is an extrapolation:
    sinh, cosh and atan are not measured. Over 19 slots the best split by whole waves of jobs is 6,
    8 and 5, at 36, 27 and 38 minutes, where 5, 6 and 4 give 48, 41 and 43; by minutes over slots,
    with no waves, 6, 8 and 5 give 32, 27 and 35 where 5, 6 and 4 give 38, 37 and 43 (6, 7 and 6
    would give 32, 31 and 29, but 41 minutes by waves). As a prediction only, scaling the 90 minutes
    that the same extrapolation gives for eight functions on 5, 6 and 4 by the slowest kind's ratio
    (38 to 48 by waves, 35 to 43 by minutes) gives about 72. The anchor night (run 44891868803, five
    functions) peaked at 11 jobs at once on 5, 6 and 4 slots, so the slots are not the only limit.
    The first night on these slots is the first measurement.
  - **What gives when it does not fit,** cheapest first, with what each buys at 13 functions
    (the table's means times 2.6): `fuzz-seconds` 120 saves 224 runner-minutes a night (172 in
    the fuzz jobs, 52 in Arm's) and halves what a species is fuzzed; `nights` 2 saves 192, 124
    of them on x86, and sweeps every high word every second night; `slice-nights` 64 saves 26;
    `arm-shards` 2 saves 14 and buys nothing, since a job's setup is half a minute, so the slots
    and not the shards bound the span. Which of these to give up first is the owner's.
  - **Not in it.** A cost of atan2's pair sweep: 13 times 72 assumes functions of one argument, and
    atan2 joined with no night of its own run, so its place in the arithmetic is an assumption (one
    function's worth at the defaults), not a reading. Two nights have read it since (87
    runner-minutes each, the entries on twelve and on thirteen functions), and the 13-function
    figures here are not requoted from it.
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
  the `night` whose stripes hold it. `log1p.foldShortPath` is one the nightly almost certainly
  misses: its inputs are about one in 2^29 of a window, so dispatching it normally reports a
  missed fault, and `Log1pTest`'s four witnesses are its check. The plan job refuses a fault
  that `pom.xml` gives no fault run, since a mistyped name would plant nothing and be reported
  as a fault the night missed. A run with a fault checks only the functions the fault reaches,
  of those asked for, and leaves the proofs out, so every mismatch it reports is expected from
  the fault (a real bug in the same functions would be among them, so only a clean night clears
  them): its own function and those that compute through it. log's three faults also fail
  log10, which calls `Log.logCore` (on the
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

## What the sinh port taught

- **One arm, because the rare range needs a second body.** `sinh` below 22 is `expm1(|x|)` and a
  dozen operations, so it nests `Expm1.expm1Core` as `Log10` nests `Log.logCore`: the `Lanes`
  calls first, the core as soon as its argument exists, then only Vector API operations. From 22
  up the C needs `exp`, and `Exp.expCore` beside `Expm1`'s body would be two port bodies in one
  graph (above), so those lanes, the infinities and the NaNs are finished per lane by
  `StrictMath.sinh`. The rows of `SinhBenchmark` (`finish256`, `finishPreferred`) are
  held by `bench/alloc-check.py`, which CI runs on common and mixed data at the runner's flags
  and as AVX2, and no figure of theirs is committed; they were run locally before any test was
  written, so a body that boxed would have shown first.
- **The two forms of the first range share one division.** `h*(2.0*t-t*t/(t+one))` below `|x| =
  1` and `h*(t+t/(t+one))` from it on differ in the numerator and in the sign of `q`'s term; each
  lane's numerator is picked and the one quotient is formed, and both sums are computed and
  blended, so every lane does its form's operations.
- **A planted threshold must sit where the result changes (again).** The two forms are equal in
  exact arithmetic, so moving their split by one word changes the lanes of that one word only:
  none of 2^22 draws of `[-30, 30]`, 16 of the 512 neighbours of `1` and `22`. Moving it to 2
  changed 32877 of those draws. Likewise `22` moved by one word changed none, and moved to 32
  changed 63885 of 2^22 draws of `[22, 32)` (1.5%). `sinh.splitMoved` and `sinh.thresholdMoved`
  are those two; `sinh.halfOneUlpOff` changes 73% of the draws. The sizing was a throwaway
  program over the C in scalar Java, as `SinhTest.cSinh` is.
- **Which expm1 faults reach sinh.** Those that change a lane of `|x| < 22`: `dropCorrection`,
  `constantOffByOneUlp` and `dropKForcing`. `expm1.thresholdMoved` moves the form of `k = 56`,
  which sinh's `k` (at most 32) never takes, and `guardMissesFilter` belongs to the guarded arm.
  `SinhTest` fails under the first three and passes under the other two (run with `-Dtest=SinhTest
  -Dvfdlibm.test.jvmArgs=-Dvfdlibm.fault=<name>`). The nightly's `reach` map is per fault for that
  reason.
- **Lefevre's list has `sh` and `ch` lines.** 2215 and 2026 lines of mode N, positive inputs
  only, so `SinhTest` draws the negations too. Rechecked with 400-digit decimal arithmetic as the
  other files were: every result is the correctly rounded value, every direction bit the 54th
  bit of the exact result, and every input has 46 or more bits after the round bit pointing at
  the midpoint.

## What the cosh port taught

- **One `Exp.expCore`, and expm1's lines written out, not nested.** cosh needs `exp(|x|)` from
  0.5 ln2 up and `expm1(|x|)` below it. Below 0.5 ln2 the C's `expm1` reduces nothing, so it is
  s_expm1.c's lines 177-189, which the port transcribes with `Expm1`'s constants: `Expm1`'s
  body, with its reduction and its seven forms, beside `Exp`'s would be two port bodies in one
  graph (above). With one `expCore` and about 35 more operations, `CoshBenchmark`'s rows are
  held by `bench/alloc-check.py` (CI runs it, and it was run locally before any test was
  written), at the runner's flags and as AVX2.
- **Two arms, one provisional default.** `coshSlice` computes the small range in the vector and
  `coshFinish` finishes its lanes per lane; the default arm is the slice arm. The arms differ
  only on `|x| < 0.5*ln2`, so their cost follows the share of that range, which is 3.5% in
  Varka's baseline benchmark and 17% in Spark's unit tests (issue #32): at 8 lanes a vector
  holds one in 27% and 82% of the cases. `CoshKindsBench` has those two shares, and
  `CoshBenchmark`'s common data 0.6% of them. A laptop run under load read no direction and was not
  kept. The slice arm stays the default, provisionally, as the named exception to issue #30's tie
  rule (its per-lane set is not rare, so a host run decides; [issue #30's
  comment](https://github.com/vecbricks/vfdlibm/issues/30#issuecomment-7091848602)). The readings,
  by minimums: on `CoshBenchmark` the EPYC 7763's two runs agree, outside 1.3x, on the finish arm
  lower on common data (slice over finish 1.33 to 1.37), a direction, and give no direction on mixed
  data (`bench/results/cosh-epyc7763-2026-11-21.txt`); on `CoshKindsBench` the finish arm reads
  lower outside 1.3x with no small lane (1.46 to 1.70 on both hosts), lower inside 1.3x at 3.5% of
  small lanes, and the slice arm lower inside 1.3x at 17% (`cosh-kinds-epyc7763-2026-11-21.txt`,
  `cosh-kinds-xeon8573c-2026-11-21.txt`). The Xeon 8573C has one `CoshBenchmark` run
  (`cosh-xeon8573c-2026-11-21.txt`); a second run decides.
- **Which exp faults reach cosh.** `exp.dropCorrection` and `exp.constantOffByOneUlp`, which
  change `expCore`'s lanes of `|x| >= 0.5 ln2`. `exp.thresholdMoved` moves the subnormal
  scale-back, which `expCore` leaves out, and `exp.dropKForcing` the lanes of the word of 0.5
  ln2, which cosh computes from expm1's lines. `CoshTest` fails under the first two and passes
  under the other two (run with `-Dtest=CoshTest -Dvfdlibm.test.jvmArgs=-Dvfdlibm.fault=<name>`;
  the runs' output is not committed), and the nightly's `reach` map says the same.
- **Sizing the faults, on 2^22 draws each.** `cosh.splitMoved` moves the small range's end from
  0.5 ln2 to 1: 2070784 of the draws of `(-1, 1)` change, about three quarters of those in
  `[0.3466, 1)`, and none do when it moves by one word.
  `cosh.halfOneUlpOff` changes 4144395 of the draws of `(-30, 30)`, `cosh.dropHalfOverT` 2568400
  and `cosh.finishMissesSmall` 503123 of those of `(-0.34, 0.34)`; `cosh.dropSmallTerm`, which drops
  the `- hxs` of the small range's polynomial, changes 4194245 of those of `(-0.34, 0.34)` and
  1793307 of `(-1e-5, 1e-5)`. `finishMissesSmall` is the finish arm's alone, and `splitMoved` and
  `dropSmallTerm` the slice arm's alone, so their catchers name one arm each.
- **Lefevre's list has 2026 `ch` lines,** positive inputs only, rechecked and written by
  `dev/testlibm-extract.py` as sinh's were, each with 46 or more bits after the round bit
  pointing at the midpoint.

## What the atan port taught

- **Blend the operands of the division, not its results.** atan's four reductions each divide
  once, with their own numerator and denominator, and every lane takes one of them. The port
  blends the operands by range and divides once, so each lane divides the C's operands; the other
  ranges' operands are computed and the quotient of the lanes below 7/16, which the C does not
  divide, is dropped. The argument is cosh's, with the blend before the division.
- **One finish arm.** The rare lanes (`|x| >= 2^66`, the infinities and the NaNs) have results
  that are a constant or `x+x`, so a guarded arm that blends them is possible; none is built,
  because the tie-break rule of #30 sends a tie to the finish arm and no timing says otherwise.
  The host files of 22 November (`bench/results/atan-xeon8573c-2026-11-22.txt` and
  `atan-epyc7763-2026-11-22.txt`, one run on the Xeon, a run and a re-run on the EPYC) time
  the one arm against the scalar loop, and read every vector row at 0.02 B/op or less, but for
  the EPYC's 512-bit rows, which run in Java there.
- **Sizes of its faults, each of 2^22 uniform draws from its own interval.** `atan.dropLo`:
  855028 changed of 2^22 draws from `(-5, 5)`, none of those below 7/16. `atan.splitMoved`
  (19/16 moved to 2): 431802 changed of 2^22 draws from `[19/16, 2)`, none from the ranges
  below 19/16 or above 2. `atan.hiOneUlpOff` (`atanhi[1]` one ulp up): 3972483 changed of 2^22
  draws from `[11/16, 19/16)`, none from the other ranges. The unplanted port changed none of
  any of these draws. The throwaway program that counted them is not committed.
- **Lefevre's list has 1735 `atn` lines,** positive inputs only, distinct, each with 45 or more
  bits after the round bit pointing at the midpoint, rechecked and written by
  `dev/testlibm-extract.py`, which derives atan's exact value itself.

## What the mutant survey taught

- **What it is.** `dev/survey.sh` changes one constant, threshold or correction term of a
  function's C by one step and counts the inputs whose result is no longer `StrictMath`'s, in the
  unit tests' inputs, a fuzz, the sweep and a search over the common path (`dev/survey/README.md`
  has the tables, for exp, expm1, log and log10 as of 20 November 2026; log1p needs its scalar
  transcription first). A mutant changes the C, not a port: where a port follows the C, an input
  that shows the mutant shows the same change in the vector code.
- **Inputs do not hold the coefficients.** One ulp on a polynomial coefficient late in its series
  changed no result in 1.8 * 10^10 inputs for exp's `P4` and `P5`, expm1's `Q3` to `Q5` and log's
  `Lg5` to `Lg7`, and the suite saw none of exp's `P3`, expm1's `Q2` or log's `Lg4` either, until
  the hard cases gained one input each (the last section of their files). The test that pins every
  constant to the C's hex (`ExpTest.constantsAreTheSourcesBits` and its like) is what guards them,
  so a new port names every constant in such a test.
- **A planted fault is chosen among the mutants the suite catches.** About seven in ten are:
  every dropped correction term, the first coefficient of each series, the thresholds the suite
  has neighbours of. A survey row whose `caught_by` is `suite` and whose count is large is a fault
  already sized, though the 18 rows that moved to `suite` by a witness (README, "Rows that
  moved") are caught by that one input; the README says what a `none` row does and does not show.
- **The sweep caught nothing the fuzz and the suite did not.** No mutant showed on the sweep's
  inputs and on none of the suite's or the fuzz's: stripe `0/64` for every mutant, and all 2^32 high
  words (three low words each) for those nothing else had found in exp, expm1 and log (stripe `0/8`
  in log10). The sweep is for bands of wrong results, not for a constant one ulp off.
- **The suite missed exp's `invln2` one ulp off**, which the fuzz finds within 250 draws: it moves
  `k` at the doubles `(k + 1/2) ln2`, and `ExpTest` takes the neighbours of three of them only,
  where expm1's has 16 of each and caught it. The 17 mutants that only the fuzz or the search
  showed now have a witness each among the hard cases, and `MutantsTest` pins them; a port's
  survey row that reads `fuzz` or `search` is a hard case waiting to be added.

## What the asin port taught

- **The first port over `sqrt`.** The body calls `VectorOperators.SQRT` on `t`; `SqrtTest`
  checks the operator's raw bits against `StrictMath.sqrt` (PROOFS.md, (R4)), and
  `bench/alloc-check.py` passed on `AsinBenchmark`'s rows (`finish256`, `finishPreferred` and
  `finishMax`, common and mixed data) and on `SqrtBenchmark`'s (`sqrtMax` has no row: the class
  has no `SPECIES_MAX` arm), at this laptop's flags and as an AVX2 host (`-XX:UseAVX=2
  -XX:MaxVectorSize=32`); CI runs it. Those local readings are not committed, so no figure of
  them is quoted. The host files of 22 November (`bench/results/asin-xeon8573c-2026-11-22.txt`
  and `asin-epyc7763-2026-11-22.txt`, one run on the Xeon, a run and a re-run on the EPYC) read
  every vector row at 0.02 B/op or less, but for the EPYC's 512-bit rows, which run in Java
  there, and time the arm against the scalar loop. None of it says anything of 128 bits or of
  the masked form's lowering.
- **One arm, no tie to break.** The three ranges below 1 share `t`'s polynomials and the one
  division `p/q`, and the split range's second division is computed on every lane; the rare lanes
  (`|x| >= 1`, NaN) are finished per lane. There is no second body to compare, so the arm needs
  no default rule, and the benchmark has no `slice` row.
- **e_asin.c's misleading indentation.** The `else` of lines 86-89 belongs to the tiny test and
  governs `t = x*x;` alone: `p`, `q`, `w` and the return after it are not in it, though they are
  indented as if they were. The tiny lanes return `x` first (`huge+x>one` is true there). The
  port, `AsinTest.cAsin` and PROOFS.md transcribe what the C does, not what its indentation
  suggests.
- **The faults, sized on 2^22 draws** (a throwaway program over `VFdlibm.asin`, run once per
  fault): `asin.dropPio2Lo` changed 970339 of the draws of `[-1, 1]`, `asin.skipLowClear`
  234026 (467198 of `[0.5, 1)`, none above `0.975`, where the root form has no low-word clear) and
  `asin.splitMoved` 20855 of `[-1, 1]` and 833300 of `[0.975, 1)`. The split is moved to the word
  `0x3FFF3333`, above every lane of the range: moved by one word it changes only the lanes of one
  word, as the sinh section says, and that move is not planted. Tried once by hand, as
  `0x3FEF3334`, the threshold inputs failed on that word's lanes.
- **Hard cases: 1655 inputs**, from `dev/testlibm-extract.py asn asin` (the tag `asn` is asin's;
  the extractor's recheck passes on all 1655 lines). All are positive, between 6.5e-8 and 1, and
  the one at 1 is a rare lane.

## What the acos port taught

- **The sibling of asin, with its own branches.** acos splits `|x| < 0.5` from `x <= -0.5`
  (`hx < 0`) and `x >= 0.5`, so one pass computes `z` for the three, the polynomials and `r = p/q`
  once, and the blends keep each lane's form; the `x >= 0.5` form divides a second time, for `c`.
  The sign decides two forms above 0.5, so the hard cases (all positive, 1569 of them) reach the
  `x <= -0.5` form only by their negations, which `AcosTest` draws. One arm: no tie to break.
- **The C's comments are not its tests.** The comment says `x < -0.5` and `x > 0.5` where the
  tests send `-0.5` to the `hx < 0` form and `+0.5` to the last one; the port and `AcosTest.cAcos`
  use the tests, and the thresholds include both `+-0.5`.
- **Which of the four planted faults changes what** (sized on 2^22 draws, a throwaway program over
  `VFdlibm.acos`, run once per fault; the counts are of 4194304 draws each).
  `acos.dropPio2Lo` changed 842961 of `[-1, 1]` and none of `[0.5, 1)`, where the form has no
  `pio2_lo`; `acos.halfMoved` (the split moved to `0x3fe80000`) 954010 of `[-1, 1]` and none of
  `(-0.5, 0.5)`; `acos.skipLowClear` 140899 of `[-1, 1]` (564449 of `[0.5, 1)`, none of
  `[-1, -0.5)`, whose form has no low-word clear); `acos.tinyMoved` (the constant return's word
  moved to `0x3cd00000`) none of `[-1, 1]` and 935119 of the magnitudes `2^-60` to `2^-50`, which
  is why the fault run's inputs include those magnitudes. A word moved by one is not planted.
- **Hard cases: 1569 inputs**, from `dev/testlibm-extract.py acs acos` (the extractor's recheck
  passes on all of them). 1265 of them have fewer than 40 bits after the round bit pointing at the
  midpoint, so most are not hard to round; the file keeps every line. All are positive, between
  0.0143 and 0.999999999999999.
- **Allocation.** CI runs `bench/alloc-check.py` on `AcosBenchmark`'s rows (`finish256`,
  `finishPreferred`, `finishMax`, common and mixed data). The host files of 22 November
  (`bench/results/acos-xeon8573c-2026-11-22.txt` and `acos-epyc7763-2026-11-22.txt`, one run
  on the Xeon, a run and a re-run on the EPYC) read every vector row at 0.02 B/op or less, but
  for the EPYC's 512-bit rows, which run in Java there, and time the arm against the scalar
  loop.

## What the atan2 port taught

- **The first function of two arguments, and no lane finished by a call.** The special lanes
  (NaN, zeros, infinities) are blends of constants, of `y` and of `x + y`; the general path calls
  `Atan.atan` on `t = fabs(y/x)` and the mask `t < 2^66` (and live), and the lanes of `t >= 2^66`,
  which only `x = 1.0` has, take the constant `atanhi[3] + atanlo[3]` that s_atan.c returns there.
  Every lane the tests drew matched on the first run of `Atan2Test`, at every width, masked and
  unmasked, with the hard-case pairs and every subset of lanes special.
- **The first body boxed on every row.** It made its masks, then called `Atan.atan` and, last, a
  private `specials` method. `bench/alloc-check.py --why` named C2's refusals: `Atan::atan` and
  `Atan2::specials` both failed to inline with `NodeCountInliningCutoff`. The fix is the rule in
  `Atan`'s comment (every call a body makes comes first), taken further: the call into atan
  comes second, right
  after the quotient and one compare (`t < 2^66`) that is its whole mask, the classification masks
  and the special blends follow inline (no second call), and the body then allocates nothing:
  `bench/alloc-check.py` passes on the `Atan2Benchmark` rows (`blended` and `guarded`, at 256 bits,
  the preferred species and `SPECIES_MAX`, common and mixed data) at this laptop's flags and as an
  AVX2 host, and CI runs it. Those local readings are not committed, so no figure of them is
  quoted. The Xeon has timed both arms in a run and a re-run and the EPYC in a run and two re-runs
  (`bench/results/atan2-xeon8573c-2026-11-22.txt` and `atan2-epyc7763-2026-11-22.txt`), and neither
  file gives them a direction (the EPYC's third run reads blended over guarded at 1.27 at the
  preferred species on common data). atan2 has no finish arm (both arms compute the special lanes
  in the vector; blended runs their blends in every vector, guarded behind a test), so rule 1 of
  issue #30 does not pick between them ([the issue's corrected
  comment](https://github.com/vecbricks/vfdlibm/issues/30#issuecomment-7085437272)). So guarded
  stays the default at every species by the owner's decision of 22 November.
- **A lane the core must not see.** A NaN, an infinity or a huge `t` in a lane the special blends
  overwrite would send the vector to atan's per-lane finish. The mask `t < 2^66` keeps them out
  (a NaN compares false), and a vector with no special lane never reaches the finish.
- **The shortcuts of `k` are redundant in bits.** `atan(t)` is `pi_o_2 + 0.5*pi_lo` from `t` near
  `2^54`, and the quadrant sums with `z = 0.0` and with `z = atan(t)` agree below `t` near `2^-55`,
  so the C's `k > 60` and `hx < 0 && k < -60`, and its early return for `x = 1.0`, change no
  result, and a fault that moves a threshold by one cannot be seen (PROOFS.md, atan2). The
  design note's fault on `x = 1.0` taking the shortcut was dropped for that reason, and so was a
  fault that took `k` from the exponent fields; `atan2.kHighMoved` and `atan2.kLowMoved` move the
  thresholds by ten.
- **The faults, sized on 2^22 draws** (a throwaway program over `VFdlibm.atan2`, once per fault,
  at 256 bits; the counts are of pairs the fault changes). Over pairs whose exponents differ by
  -70 to 70: `atan2.kHighMoved` 40791, `atan2.kLowMoved` 20438,
  `atan2.lowShortcutForPositiveX` 155961, `atan2.dropPiLo` 1253851, `atan2.noSignFlip` 1049118,
  `atan2.infOrderSwapped`, `atan2.zeroSignOfX` and `atan2.diagonalInf` none. Over the nightly's
  own draws at the `k` gaps (`Nightly.PairFunction.ATAN2`'s edges): 1452, 738, 594538, 807566,
  832241, and 240, 96 and 152 for the three that need an infinity or a zero. Over the 7225 pairs of
  the special values: 4, 2 and 3 for those three, 650 for `lowShortcutForPositiveX`, 817 for
  `dropPiLo` and 1150 for `noSignFlip`, and 0 for the two thresholds. The fault run's inputs
  hold all of them, and the unplanted port changes 0 of each.
- **Hard cases.** Lefevre's data has no `atan2` tag, so `hard-cases/atan2.txt` is derived from its
  `atn` lines (`dev/testlibm-extract.py atn atan2`): `atan2(+-t * 2^j, +-2^j)` for `j` in 0, 1,
  -5, 20,820 pairs. They are inputs of atan's core through `atan2`'s own code, not hard cases of
  its quadrants.
- **The masked form keeps `y`, the first argument,** in a lane that is not live, as the harness's
  `assertPairMasked` requires, and a lane that is not live never takes a special blend's test: the
  guard reads `special & live`.
- **The consumer of `dev/jar-check.sh`** calls `atan2` over every pair of its inputs and two NaNs of
  different bits, unmasked and under four masks, and compares by a copy of `PairBits.matches`.

## What the hypot port taught

- **Words and values in separate vectors, written as the C writes them.** e_hypot.c's 2^1022
  scaling multiplies `a` and `b` and leaves `ha` and `hb` stale, and the splits read the stale
  words (PROOFS.md, L16: refreshing them changes 42,876 of 1,048,576 subnormal-`b` pairs). Kept
  as int lanes beside the values, with every `__HI(v) = word` written out (`I2L`, shift, or), the
  port has that behaviour with no special case. Every compare, nesting and `k` update is the C's
  own, so no exclusivity of the scalings is assumed and no lemma is needed beyond L13; line 110's
  `k != 0` is a blend too, since multiplying every lane by `2^k` would be a departure needing a
  lemma of its own.
- **One arm, the finish, and it reached every finite lane on the first run.** The rare lanes are
  an infinity or a NaN in either argument (`ha >= 0x7ff00000`); everything finite, the ratio
  return, the zero `b` and the three scalings included, is in the vector. A blended non-finite arm
  would rest on the hardware's NaN propagation for one-NaN lanes and has no timing to justify it,
  and #30 sends a tie to the finish. The first run of the vector tests passed on every input.
  CI's `bench/alloc-check.py` covers `HypotBenchmark`'s rows (`finish256`, `finishPreferred`,
  `finishMax`, common and mixed data); no file under `bench/results/` reads or times them yet.
- **The faults, sized on 2^22 draws each** (a throwaway program over `VFdlibm.hypot` at 256 bits,
  once per fault; counts of pairs that differ from `StrictMath.hypot`; the unplanted port 0 on
  each). `hypot.dropEarlyReturn`: 0 of Varka's range `[-10, 10)`, 1,779,887 of sampled bits,
  1,620,600 of the fuzz's edges, 14,850 of the 40,401 special pairs. `hypot.refreshedWords`: 170,825
  of subnormal-`b` pairs, 16,550 at the scalings, 24,077 of equal words, 0 of the special pairs.
  `hypot.dropT2`: 2,096,152 of `[-10, 10)`, 3,096,883 of subnormal-`b` pairs. `hypot.dropY2`
  (line 108's `t1*y2` dropped): 2,098,152 of `[-10, 10)`, 2,004,618 of equal words, 2,200,389 of
  `a` near `2b`. The fault run's inputs (`HypotTest.faultRunInputs`) hold the special pairs and
  draws of the scalings, subnormal `b`, equal words, `a` near `2b` and the dense families.
- **A swap by value was a fault of the finish, not of the swap.** Line 60 swapped by `|y| > |x|`
  instead of by the high words was a candidate, sized as above at 0 of 4,194,304 equal-word draws,
  1,995 of sampled bits and 2,316 of the special pairs. A review found that some of those hits
  came from a NaN `y` beside a finite `x`: a NaN compares false, so nothing swaps, `ha` stays
  finite and the rare mask misses the lane. So the fault tested the finish's mask more than the
  swap, and was replaced by `dropY2`. Whether a finite lane shows it (two zero high words, where
  the value swap picks the other form) was not measured. The port later took a swap by magnitude
  (next item), but by the sign-cleared bits compared as longs, which a NaN does not escape.
- **StrictMath is not the C at line 60** (issue 173). The nightly fuzz found
  `hypot(0x5f201b45bbc5eac3, 0x5f201b45fffffffe)` one ulp off: line 60 swaps by the high words
  alone, so with equal words `a` is `x` even when `|y|` is larger, and lines 101-108 then split
  the low words the other way round. `StrictMath.hypot` gave the C with the larger argument first
  in either order on every pair tried (the JDK's own port since JDK-7130085 is commutative), so
  the port now swaps where `|y| > |x|` (PROOFS.md, L21; fault `hypot.highWordOrder`). Such pairs
  are rare: 18 of 9 * 10^8 random pairs of one high word with random low words, 0 of 10^8 with the
  fuzz's patterned low words, 0 of 8 * 10^8 subnormal ones, so the earlier "0 of 4,194,304
  equal-word draws" above was too few to see it, and the fuzz's gap 0 found it once in about 5 *
  10^9 draws. With the fault planted the port is wrong on one order of each pair of the file, 76
  of its 152. `hard-cases/hypot-order.txt` holds 19 such pairs, found by a scratch C program over
  Sun's C; the lesson is that the oracle is `StrictMath`, and a port that follows the C exactly
  can still differ from it where the JDK's code is not the C.
- **Hard cases, computed.** `dev/hypot-hard-cases.py` derives 8,400 pairs with exact integer
  arithmetic (near-midpoint families of both forms of the medium path, each within 2^-50 ulp of
  a midpoint, exact triples with nearly equal legs, scaled into each range), and
  `HardCasePairsTest` rechecks the claim on the committed file with `BigDecimal`. The owner
  accepted self-computed files for hypot on 23 November 2026
  ([issue 34](https://github.com/vecbricks/vfdlibm/issues/34#issuecomment-7088116761)) and asked
  for one for pow the same day
  ([issue 34](https://github.com/vecbricks/vfdlibm/issues/34#issuecomment-7100457001)).
- **Hard cases, published** (issue 144). A published list does exist, CORE-MATH's
  `src/binary64/hypot/hypot.wc` (MIT): `hard-cases/hypot-coremath.txt` holds 20 of its pairs,
  each in its eight forms (both orders, four sign combinations, as CORE-MATH's check expands
  them), which `dev/coremath-hard-cases.py` takes by line number at a pinned commit and sha256: the
  RNDN and RNDZ worst cases (within 2^-105 ulp of a midpoint and of a double), eight exact ties,
  and the ten pairs of its large section within 2^-60 ulp of a midpoint. `HardCasePairsTest`
  rechecks each with `BigInteger` arithmetic, by its place in the file. The whole list (33,202
  exactly parsed lines, 265,616 inputs with signs and orders) matched `StrictMath` at every width
  when it was evaluated, so the file is a selection, not a fix. The list is no harder for fdlibm
  than any input: it is hard for correct rounding, and `e_hypot.c` does not round correctly.

## What the pow port taught

- **The common path alone, prototyped first.** By the owner's decisions of 23 November 2026 on issue
  34, the first body computes e_pow.c's common path only (a positive normal `x` that is not 1, a
  finite `y` of `iy <= 0x41e00000` that is not 0, +-1, 2 or 0.5, `z` inside lines 266 and 272, a
  normal result) and finishes every other lane per lane by `StrictMath.pow`; the guard is
  `PROOFS.md`'s L19. Before any test, its body (two divisions, six low-word clears, int-lane shifts
  by per-lane counts and `I2D`) was run through `bench/alloc-check.py` and read with the assembly
  check: at 256 and 512 bits on the laptop's AVX-512 flags and at 256 bits as an AVX2 host, each
  loop is one copy of the body with two `vdivpd`, no FMA and no call but the traps and the entry
  barrier (`AsmTest` holds pow's arms, and the census above has their rows). `--why` named no
  refused call of `Pow` or `Lanes`, but `NodeCountInliningCutoff` refused the benchmark methods
  themselves and `Blackhole::consume`: the graph is past the cutoff with pow's body alone, so
  nothing more fits beside it.
- **`StrictMath.pow` allocates, which the allocation check cannot tell from a box.** On JDK 25.0.5.1
  the scalar `StrictMath.pow` loop of `PowBenchmark` allocates, and in a standalone probe only the
  calls that reach the oracle's main path did (a negative `x` with an integer `y`, a subnormal `x`,
  overflow, underflow, a subnormal result), and not a common-path call there, so the allocation
  depends on how the JIT compiled the oracle; its cause is not read. pow's finish calls the oracle
  on exactly those lanes of the `mixed` data, so a threshold on the finish rows alone cannot tell
  those calls from a box. By the owner's decision of 23 November 2026 (issue 34) the check compares
  such rows with an oracle baseline: `PowBenchmark.rareOracle` calls `StrictMath.pow` on the lanes
  the finish takes, over the same arrays, and no timing is claimed of it. Its reading, at the
  laptop's flags and as an AVX2 host: every arm reads what `rareOracle` reads, about 1,824 B/op on
  `mixed` data, 19 lanes at 96 bytes a call (`bench/results/pow-alloc-laptop-2026-11-23.txt`, an
  allocation reading, no timing and no headline).
- **Which guard parts are hinges.** Run through the common path alone, every part of the guard but
  two changes results on pairs it alone keeps out (`PROOFS.md`, L19, with the counts): a `|y|` past
  2^31 beside `x` within 2^-40 of 1 only rarely (42 of 5,952 pairs), where the C's short log and the
  main log mostly agree. `x = 1` and a zero `y` give the C's 1.0 through the common path, and stay
  in the guard because the C returns early there.
- **The faults, sized on 2^22 draws each** (a throwaway program over `Pow.pow` at 256 bits, once per
  fault; counts of pairs that differ from `StrictMath.pow`; the unplanted port 0 on each).
  `pow.guardAdmitsNegativeX` (the guard tests `ix` for `hx`): 0 of Varka's range, 4,194,304 of
  negative `x` in `[-1e6, -1e-6)`, 398,271 of `PowTest.integerPairs`, 1,047,567 of sampled bits,
  15,420 of the 88,804 special pairs. `pow.guardMissesSubnormal` (line 306's test dropped): 0 of
  Varka's range, 1,190,254 of `PowTest.targetPairs`, 34 of sampled bits, 158 of the special pairs.
  `pow.dropT2` (line 261's `y*t2` dropped): 4,194,304 of Varka's range, 1,329,994 of the target
  pairs, 8,669 of the special pairs. The fault run's inputs (`PowTest.faultRunInputs`) hold the
  special pairs, integer and target pairs, the pairs of each guard part and dense draws.
- **Hard cases, computed** (the owner's decision of 23 November 2026, issue 34). A published
  binary64 pow list exists, CORE-MATH's `src/binary64/pow/pow.wc` (MIT), from which the next
  entry's file is taken; this file is self-computed: `dev/pow-hard-cases.py` derives
  `hard-cases/pow.txt` with exact integer arithmetic: exact results (integer powers below 2^53
  scaled into each binade,
  and powers of two with integer, quarter and eighth exponents down to 2^-1074), exact ties (odd
  54-bit powers, and odd multiples of 2^-1075 among the subnormals), results within 2^-28 ulp of a
  midpoint, and the doubles either side of the overflow, smallest-normal and zero thresholds.
  `HardCasePairsTest` rechecks each pair's claim with `BigInteger` arithmetic. The near family stops
  at 2^-28 and not hypot's 2^-50: from `x = 2^52 + d` the distance is `|C(e,2) d^2 - (2j+1) 2^51| /
  2^52` to leading order, and the squares are too sparse modulo 2^51 for it to come nearer (about
  2^-30 at best over 20,000 steps); a nearer family needs another construction.
- **Hard cases, published** (issue 144). `hard-cases/pow-coremath.txt` holds 848 pairs of
  CORE-MATH's `pow.wc`, which `dev/coremath-hard-cases.py` takes by line number at a pinned commit
  and sha256: the list's first section of worst cases, Gombala's targeted-search cases of 110 or
  more identical bits after the round bit and his near-rational ones of 100 or more (the source's
  `# N`), the 278 exact results and ties at `y = 3/2`, and three of the 7,396 midpoint cases
  for each of their 41 values of `y` (the first and last of its run, results near 2^-1022 or
  2^1024, and the one nearest 1); 32 lines that repeat an earlier pair are not written again.
  The subnormal sections are left out: by decision A on issue 34 a subnormal result is finished
  per lane by `StrictMath.pow`, so those lanes would compare `StrictMath` with itself; they come
  back when a widening moves subnormal results into the vector. `HardCasePairsTest` rechecks
  the exact results and ties with `BigInteger` arithmetic (`c^(2^k) = |x|^p` for `y = p / 2^k`);
  the near pairs' `# N` is the source's claim,
  which needs `x**y` to well over 100 bits to recheck, so only their count and a SHA-256 of
  their lines are pinned. The whole
  list (1,000,874 exactly parsed lines) matched `StrictMath` at every width when it was
  evaluated, so the file is a selection, not a fix; its 2,305 lines that do not spell exactly
  one double (strtod would round them) matched too, read as strtod rounds them, and none is in
  the file.
