# The runner pool

Which CPU a GitHub-hosted job runs on is a draw from the `ubuntu-latest` pool, and the
benchmark hosts of `HARDWARE.md` are draws from the same pool. This directory tallies what the
pool gave the nightly's jobs, so that the choice of hosts can rest on counts. `HARDWARE.md`,
"What the pool gives", quotes the table and reads it.

## Where the readings come from

Every sweep, fuzz and Arm job of `.github/workflows/nightly.yml` starts with the step "Record
the machine", which runs `bench/datapath.sh`. It prints the CPU's model name, the core count,
the vector flags, the JVM's `MaxVectorSize` and `UseAVX` or `UseSVE` at the machine's own flags,
and the datapath probe's lane rates with their two ratios (`HARDWARE.md`, "How to read the
columns"). `pool.py fetch` reads those lines back from each job's log with `gh`, one row per
job, and `pool.py table` tallies a file of rows:

    bench/pool/pool.py fetch --since 2026-11-03 --until 2026-11-19 \
        > bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py table bench/pool/nightly-2026-11-03-to-2026-11-19.tsv

GitHub keeps a run's logs for 90 days, so the rows are committed, and the table can be rebuilt
when the logs are gone.

- `fetch` needs `gh`, authenticated for the repository, and makes one `gh` call per job: a few
  minutes for the 664 jobs here.
- It takes the first of each of the probe's lines in a job's log. The step runs before the
  build, and a later step prints the CPU again.
- A night's date is the UTC day its run was created on. Every completed run of the workflow
  counts, a dispatched one too.
- A job whose log is gone, or whose probe printed no line, keeps its row, with `-` in the cells
  it lacks; `table` counts it as "without a reading".

## The files

| file | what it holds |
| :--- | :--- |
| `pool.py` | `fetch`, `table` and `cost` |
| `nightly-2026-11-03-to-2026-11-19.tsv` | the 664 recording jobs of the nightly's first 17 nights, runs 41612916834 to 44505887053: 332 x86 and 332 Arm, every one with a reading |
| `nightly-cost-2026-11-03-to-2026-11-19.tsv` | every job of those nights with its times and species runs ("What a night costs") |

The columns: `date`, `run`, `job` and `name` say which job; `cpu` is `/proc/cpuinfo`'s model
name (`-` on Arm, which has none); `cores`; `avx2`, `avx512f` and `avx512dq` are `yes` or `no`;
`flags` is every `avx`, `sve` and `asimd` flag; `jvm` is the JVM's vector settings; `lanes128`,
`lanes256` and `lanes512` are the probe's lane-operations per nanosecond; `control` is 256:128
and `wide` is 512:256; `usable` is `no` where the probe's control is not about 2, which is
every Arm job, whose one width leaves nothing to compare.

## What a night costs

The same logs time the night. `dev/nightly.sh` brackets each species' JVM with
`== <mode>-<function>-<species>:` lines, `Sweep` prints `swept <n> high words (<m> inputs) in
<t> s` and `Fuzz` prints `fuzzed <n> inputs in <t> s`. With `--cost <file>`, `fetch` writes a
second file from the logs it has already read, and `pool.py cost` tabulates it:

    bench/pool/pool.py fetch --since 2026-11-03 --until 2026-11-19 \
        --cost bench/pool/nightly-cost-2026-11-03-to-2026-11-19.tsv \
        > bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py cost bench/pool/nightly-cost-2026-11-03-to-2026-11-19.tsv

`nightly-cost-2026-11-03-to-2026-11-19.tsv` holds the 715 jobs of the same 17 nights, the plan,
proofs and alert jobs too, and their 1984 species runs, every one of which exited 0. It has one
row per species run, and one row for a job that ran none. Its columns: `date`, `run`, `job` and
`name` as above; `conclusion` is the job's; `cpu` and `jvm` are the probe's; `started` is the
job's start (`-` for a skipped job) and `wall` its seconds from start to end; then, for one JVM
of `dev/nightly.sh`, `mode` (`sweep` or `fuzz`), `function`, `species` (`128`, `256`, `512` or
`max`), the `bits` that species had, the high `words` swept, the `inputs` checked, the
`seconds` the program reports, its `exit` status and the JVM's own `jvmargs`, which hold
`-XX:UseAVX=2 -XX:MaxVectorSize=32` in an as-AVX2 fuzz.

`cost` prints four tables. `--by-cpu` splits the last two by CPU model.

- **Runner-minutes a night by job kind.** A job's cost is its seconds from start to end; a
  night's is their sum over 60, beside the minutes the jobs started (each job's seconds
  rounded up to a minute) and the night's span, from the first job's start to the last job's
  end, which holds the wait for a runner.
- **Runner-minutes a night by function**, the mean over the nights that ran the function.
- **Minutes of one runner to sweep every high word of a function**, by species and by the
  machine's widest vector: each run's seconds scaled from the high words it swept to all 2^32.
  It leaves out a job's setup and build, which the first two tables hold.
- **The fuzz's millions of inputs a second.** A fuzz runs for a set time, so its cost is
  `fuzz-seconds` times the species; the rate says what the time buys.

What the 17 nights say:

- The nights since expm1 joined, 5 to 19 November, 43 jobs each, cost 347 to 372
  runner-minutes, 5.8 to 6.2 runner-hours, and 370 to 394 minutes started. The first two
  nights, 35 jobs and four functions, cost 272 and 285. A night of 43 jobs spans 50 to 58
  minutes, once 77.
- By the medians of the 17 nights, a night is 363 minutes: 137 of fuzz, 118 of x86 sweep and
  105 of Arm. The plan, proofs and alert jobs together are under one minute.
- By function, a night costs 62 minutes for log, 68 for log10, 69 for exp, 79 for log1p and 84
  for expm1.
- The x86 sweep's cost is its 128-bit slice. Swept whole at 128 bits on a 256-bit machine, a
  function would take 68 (log) to 262 (expm1) minutes by the median, against 6.6 to 13.2 at
  256 bits; the nightly sweeps 1/32 of it a night. On Arm the same species takes 7.2 to 13.4.
- An x86 draw with 512-bit vectors is not one speed. exp's whole sweep at 512 bits reads 3.5 to
  7.1 minutes over 22 runs, and at 256 bits on those machines 5.0 to 10.2, against 9.2 to 9.6
  on the 256-bit machines. `--by-cpu` shows which CPU reads which.

## Adding nights

Fetch a later range into files of their own, named for the first and last night, and quote the
pool's table in `HARDWARE.md` beside the earlier one: the pool changes, and two tables show how.
