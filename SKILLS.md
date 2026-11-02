# What vfdlibm has learned

Lessons about benchmarking, the JIT and the Vector API that every port follows. Most were learned
in Varka (github.com/vecbricks/varka, its `sql/varka/skills/`) and are rewritten here for a vector
math library; the last section is what the `exp` port taught. Each entry says where its numbers
come from. A new lesson goes in the section it belongs to, as a short entry; a negative result
belongs here too, since it stops the next port re-trying a settled question.

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
- **Laptop numbers are not headlines.** See the EPYC 7763 rule below.

## The JIT: boxing, intrinsics and inlining

- **Allocation is the verdict.** A boxed vector body computes the right bits, so every test
  passes on code that runs as a per-lane Java loop. Only the allocation rate tells them apart:
  `gc.alloc.rate.norm` near 0 B/op means the body ran unboxed; a box is kilobytes per op (exp's
  128-bit rows allocate 1.36 to 1.90 MB per 1024 doubles). Read `-prof gc` on every row.
- **CI checks it.** `bench/alloc-check.py` runs every benchmark method named `*256` or
  `*Preferred`, on `inputs=common`, one fork under `-prof gc` with the body inlined, and fails if
  any measured iteration reaches 1 B/op; the `alloc` job of `ci.yml` runs it at the runner's
  flags and under `-XX:UseAVX=2`. On the laptop, under load, C2 finished exp's body within the
  first one-second iteration and later ones read about 0.02 B/op, so three warmup seconds leave
  two to spare; a row that fails only in its first iteration means C2 was late. Left
  out: 128 bits (masks box at two lanes on x86, below), 512 bits (software on AVX2; the preferred
  arm covers it where it is real) and `mixed` inputs (the finish arm's fallback allocates its
  arrays by design). Arm is not checked yet: its preferred species is 128 bits, and no run has
  shown exp unboxed there.
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
  species comes from it is not intrinsified (`missing constant ... vlen_to=LoadI`). Pick the
  species by identity against the `SPECIES_*` constants, as `Lanes.intSpecies` does; once
  inlined, C2 sees a constant. The same holds for any species loaded from a local or a field C2
  cannot fold. `VectorSpecies.of` stays only as the fallback for an unusual shape.
- **At two 64-bit lanes x86 C2 lowers no mask.** Compare to mask, blend, mask cast, mask
  broadcast, logic and test are `not supported` at `vlen=2` for double and long masks, and fine
  at four lanes. So on x86 every 128-bit double port that branches through masks runs per lane
  and allocates megabytes per op: the 128-bit rows of `bench/results/` are correctness runs, not
  the port's speed. Varka's operator census on GitHub's Arm runners (Neoverse N2, 128 bits,
  native) found the mask-producing compares it measured, `IS_NAN` among them, intrinsic there,
  so this is an x86 fact; check `PrintIntrinsics` on Arm before assuming either way for a new
  operation.
- **Simulating a host's width.** `-XX:MaxVectorSize=32` caps vectors at 256 bits but keeps
  AVX-512's instructions (and its `D2L`). To compile as on an AVX2 host, which GitHub's x86
  runners and the EPYC 7763 are, pass `-XX:UseAVX=2` (with `-XX:MaxVectorSize=32`, the preferred
  species is then 256 bits), and read `-prof gc` and `PrintIntrinsics` under it.
  `-XX:MaxVectorSize=16` runs everything at 128 bits, as the Arm runners do.
- **The laptop's AVX-512 is 256 bits wide.** The owner's laptop (AMD Ryzen AI 9 HX PRO 370,
  Zen 5 mobile) has the whole AVX-512 instruction set and HotSpot picks `MaxVectorSize=64`, but
  its datapath is 256 bits: an integer vector loop timed at 128, 256 and 512 bits reads a
  256:128 ratio of 2.02 and a 512:256 ratio of 1.14 in lanes per nanosecond. Its 512-bit rows
  are not what a native AVX-512 host would show. The method generalises: to learn whether a
  machine's widest vector is real, time three widths, not two, so the 128-to-256 step proves the
  probe can see a doubling; `lscpu` and `MaxVectorSize` report the instruction set, not the
  datapath.
- **Hosts measured in Varka.** 512:256 in lanes per nanosecond: AMD EPYC 7763 (Zen 3, AVX2, no
  AVX-512) 1.00; EPYC 9V74 (Zen 4, no AVX-512 exposed) 0.91 to 1.00; Intel Xeon Platinum 8573C
  and Xeon 6973P-C (full AVX-512) 1.33 to 1.36, from issuing 512-bit operations on two ports
  against 256-bit ones on three; EPYC 9V45 (Zen 5) 1.99. GitHub's x86 runners draw from all of
  these.

## Where headline numbers come from: the EPYC 7763

- **Headline numbers are measured on an AMD EPYC 7763**, a GitHub-hosted `ubuntu-latest` runner:
  AVX2, `UseAVX=2`, `MaxVectorSize=32`, preferred species 256 bits. Laptop files say "laptop
  numbers, not headlines" in their header.
- **The benchmark fails on any other CPU, and is retried, never relaxed.**
  `.github/workflows/benchmark.yml` checks `/proc/cpuinfo` before anything else and fails unless
  it names an AMD EPYC 7763; `bench/dispatch-epyc.sh` dispatches it and, when the run failed on
  that check, waits an hour and dispatches again, until a run passes or fails on something else.
  Newer EPYCs (9V45, 9V74) are common draws, and accepting one would put a result on a different
  machine from every result before it. If the 7763 stays scarce for days, moving the headline
  host is a decision for the owner, not a change to the check.
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
  `boolean`, so they share every line but the rare paths and the unused arm folds away once
  inlined. The blended arm computes every branch of `e_exp.c` in every lane; the finish arm
  computes the common path and, when any lane is rare, sends those lanes to `StrictMath.exp` via
  `toArray` and `fromArray`. By minimums, in ns per 1024 doubles: on common data the finish arm
  wins clearly, 1864 against 4036 at 256 bits and 1269 against 2834 at 512 on the laptop's
  default flags, 1939 against 3690 under `UseAVX=2`. On mixed data (one rare input in 16) the
  round trip costs about 53 KB/op and the arms close up: 3060 against 3269 at 512 bits
  (re-run), level at 4528 against 4577 under `UseAVX=2` (re-run), and at 256 bits on the
  AVX-512 build the blend wins, 4597 against 7688.
  `VFdlibm.exp` uses the finish arm. Each port measures both on common and mixed data, decides
  per branch, and records the decision in its pull request (`PLAN.md` section 5).
- **Laptop first, then the EPYC.** The `D2L` boxing never showed under the laptop's default
  flags, where AVX512DQ is present; it showed only under `-XX:UseAVX=2`. Run a new port's
  benchmark under both before calling it done.

## What the log1p port taught

- **The C's ints in int lanes, end to end.** `log1p` keeps `hx`, `ax`, `hu` and `k` as
  `IntVector` lanes, with the double vector's lane count at half its bits: a high word comes in
  by `L2I` from the long view shifted right 32, goes back by `I2L` and a shift left 32, `k`
  becomes a double by `I2D`, and the int compares' masks are cast to double masks. Under
  `-XX:UseAVX=2` every 256-bit arm allocates about 0.01 B/op on common data, so `L2I`, `I2L`,
  `I2D` and the int-to-double mask cast all lower at four lanes on AVX2
  (`bench/results/log1p-laptop-avx2-2026-11-02.txt`). The C's signed compares, such as `hx <=
  (int)0xbfd2bec3` for a negative `x`, are then written as the C writes them.
- **An invalid operation's NaN is not the hardware's.** For `x < -1` the C returns `(x-x)/(x-x)`.
  x86's `divpd` gives `0xfff8000000000000` there, and a negative NaN `x` comes back quieted with
  its payload, but `StrictMath.log1p` returns `Double.NaN`, `0x7ff8000000000000`, for every such
  lane, negative NaNs of any payload included; a positive NaN takes `x+x` and keeps its payload.
  Where the C makes a NaN by an invalid operation, check the oracle's bits on NaN inputs of both
  signs and several payloads (`Inputs.specials` has them) before computing it.
- **A third shape: blend the rare paths behind one test.** `log1p`'s rare paths (`x <= -1`,
  `|x| < 2^-29`, `+inf` and NaN, and `|f| < 2^-20`) are a few vector operations each. Computing
  and blending them only when a vector holds a rare lane (`if (rare.anyTrue())`) never allocates
  and, under `UseAVX=2` at 256 bits, by minimums in ns per 1024 doubles, ties the per-lane finish
  on common data (2760 against 2716) and beats it 2.0x on mixed data (3011 against 6038, the
  finish allocating 52,928 B/op); it beats blending every lane by 1.15x on common data and
  1.07x on mixed (3185 and 3215). `VFdlibm.log1p` uses it. A port whose rare paths are cheap in
  vector form should measure this shape beside the other two.
- **`lane` and `withLane` in a loop do not avoid the finish's allocation.** A first per-lane
  finish read the rare lanes from `rare.toLong()` and wrote each `StrictMath.log1p` result back
  with `result.withLane(lane, ...)`, looping over the set bits. Under `UseAVX=2` it allocated
  47,232 B/op on mixed data at 256 bits, against 52,928 for `toArray`/`fromArray`, at the same
  speed, in a short exploratory run kept in the same results file. A version with a constant
  lane index per step (no loop) was not tried.
