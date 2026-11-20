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
| `pool.py` | `fetch` and `table` |
| `nightly-2026-11-03-to-2026-11-19.tsv` | the 664 recording jobs of the nightly's first 17 nights, runs 41612916834 to 44505887053: 332 x86 and 332 Arm, every one with a reading |

The columns: `date`, `run`, `job` and `name` say which job; `cpu` is `/proc/cpuinfo`'s model
name (`-` on Arm, which has none); `cores`; `avx2`, `avx512f` and `avx512dq` are `yes` or `no`;
`flags` is every `avx`, `sve` and `asimd` flag; `jvm` is the JVM's vector settings; `lanes128`,
`lanes256` and `lanes512` are the probe's lane-operations per nanosecond; `control` is 256:128
and `wide` is 512:256; `usable` is `no` where the probe's control is not about 2, which is
every Arm job, whose one width leaves nothing to compare.

## Adding nights

Fetch a later range into a file of its own, named for its first and last night, and quote its
table in `HARDWARE.md` beside the earlier one: the pool changes, and two tables show how.
