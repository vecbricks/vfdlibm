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
        --out bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py table bench/pool/nightly-2026-11-03-to-2026-11-19.tsv

GitHub keeps a run's logs for 90 days, so the rows are committed, and the table can be rebuilt
when the logs are gone.

- `fetch` needs `gh`, authenticated for the repository. It spends the account's REST quota,
  5,000 calls an hour shared with everything else that uses `gh`: a call for the list of runs,
  a call or two per run for its jobs, and one call per job for its log, so about 700 calls
  for the 17 nights and 664 logs here.
- So it keeps every answer it gets under `--cache` (`target/pool-cache`, which git ignores)
  and asks for none of them twice; it reads the logs 50 at a time (`--batch`) with 10 seconds
  between batches (`--pause`); and before the job lists and before each batch it asks the API
  how many calls are left, which is free, and stops when they would leave fewer than 2,000
  (`--reserve`). It stops, too, when a whole batch got no log. A fetch that stopped
  writes no file and says when to run it again; the same command then goes on from the cache.
  `--out` and `--cost` are each written whole or not at all, which a shell's `>` is not: it
  empties the file before `fetch` starts.
- It takes the first of each of the probe's lines in a job's log. The step runs before the
  build, and a later step prints the CPU again.
- A night's date is the UTC day its run was created on. Every completed run of the workflow
  counts as a night, a dispatched one too.
- A job whose log is gone, or whose probe printed no line, keeps its row, with `-` in the cells
  it lacks; `table` counts it as "without a reading".

## The files

| file | what it holds |
| :--- | :--- |
| `pool.py` | `fetch`, `table` and `cost` |
| `nightly-2026-11-03-to-2026-11-19.tsv` | the 664 recording jobs of the nightly's first 17 nights, runs 41612916834 to 44505887053: 332 x86 and 332 Arm, every one with a reading |
| `nightly-cost-2026-11-03-to-2026-11-19.tsv` | every job of those nights, its times and runs |

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
        --out bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py cost bench/pool/nightly-cost-2026-11-03-to-2026-11-19.tsv

`nightly-cost-2026-11-03-to-2026-11-19.tsv` holds the 715 jobs of the same 17 nights, the plan,
proofs and alert jobs too, and their 1984 species runs, every one of which exited 0. It has one
row per species run, and one row for a job that ran none. Its columns: `date` and `run` as
above; `attempt` is the run's last attempt, the only one whose jobs are listed, `event` what
started it (`schedule` or `workflow_dispatch`) and `commit` the commit it ran; `job` and
`name`; `conclusion` is the job's; `cpu` and `jvm` are the probe's; `started` is the job's
start and `wall` its seconds from start to end (`-` and 0 for a job that ran no step, skipped
or cancelled while it waited); then, for one JVM of `dev/nightly.sh`, `mode` (`sweep` or
`fuzz`), `function`, `species` (`128`, `256`, `512` or `max`), the `bits` that species had,
the high `words` swept, the `inputs` checked, the `seconds` the program reports, its `exit`
status, the `jdk` it printed and the JVM's own `jvmargs`, which hold `-XX:UseAVX=2
-XX:MaxVectorSize=32` in an as-AVX2 fuzz.

The times here are not benchmark results, and no claim that one body is faster than another
rests on them: they are the nightly's own programs on whatever machine each job drew, and
`cost` ends with the commits and JDK builds the nights ran, a commit's nights as spans. The 17
nights ran two commits, f5dd1efbbf on 3 and 4 November and 0b31cd1e9a from 5 November, when
expm1 joined.

`fetch` writes no file when a job's log could not be read, and says what `gh` answered the
first time. Where the logs are gone for good, as after their 90 days, `--without-logs` writes
the rows all the same, with the jobs' times and without their species runs, and `cost` says
how many jobs have none.

`cost` prints five tables. A night is a run, so a night replayed by a dispatch counts as a
night of its own, and a run in which no sweep, fuzz or Arm job started is left out and
named. `--by-cpu` splits the last two tables by CPU model and its widest vector.

- **Runner-minutes a night by job kind.** A job's cost is its seconds from start to end; a
  night's is their sum over 60, beside the minutes the jobs started (each job's seconds
  rounded up to a minute) and the night's span, from the first job's start to the last job's
  end, which holds the wait for a runner.
- **Runner-minutes a night by function**, the mean over the nights that ran the function.
- **Runner-minutes a night by job kind and what the job was doing**: the seconds its sweeps
  and fuzzes report, by species, and the rest of the job's time, which is its setup, build,
  probe and JVM starts. An Arm job sweeps and fuzzes, so this table, not the first, says what
  the fuzz costs.
- **Minutes of one runner to sweep every high word of a function**, by species and by the
  machine's widest vector: each run's seconds scaled from the high words it swept to all 2^32.
  It leaves out a job's setup and build, which the first three tables hold.
- **The fuzz's millions of inputs a second.** A fuzz runs for a set time, so its cost is
  `fuzz-seconds` times the species; the rate says what the time buys.

What the 17 nights say:

- The nights since expm1 joined, 5 to 19 November, 43 jobs each, cost 347 to 372
  runner-minutes, 5.8 to 6.2 runner-hours, and 370 to 394 minutes started. The first two
  nights, 35 jobs and four functions, cost 272 and 285. A night of 43 jobs spans 50 to 58
  minutes, once 77.
- By job kind, the median fuzz jobs of a night take 137 minutes, the x86 sweep jobs 118 and
  the Arm jobs 105; each is its own column's median, so they are no one night's split. The
  plan, proofs and alert jobs together are under one minute.
- By what the jobs do, as means over the 17 nights: fuzzing is 168 minutes a night (129 in the
  fuzz jobs and 39 in the Arm jobs), sweeping 162 (109 on x86, 53 on Arm), and setup, build
  and the rest 21. The fuzz is the largest part of a night.
- By function, a night costs 62 minutes for log, 68 for log10, 69 for exp, 79 for log1p and 84
  for expm1.
- The 128-bit slice is 15% of the x86 sweep jobs' time, 17 minutes a night, for 1/32 of the
  high words. Swept whole at 128 bits on a 256-bit machine, a function would take 67.5 (log)
  to 262 (expm1) minutes by the median, against 6.6 to 13.2 at 256 bits. On Arm the same
  species takes 7.2 to 13.4.
- An x86 draw with 512-bit vectors is not one speed. exp's whole sweep at 512 bits reads 3.5 to
  7.1 minutes over 22 runs, and at 256 bits on those machines 5.0 to 10.2, against 9.2 to 9.6
  on the 256-bit machines. `--by-cpu` shows which CPU reads which, and keeps the EPYC 9V74's
  draws without AVX-512 apart from those with it.

## Adding nights

Fetch a later range into files of their own, named for the first and last night, and quote the
pool's table in `HARDWARE.md` beside the earlier one: the pool changes, and two tables show how.
