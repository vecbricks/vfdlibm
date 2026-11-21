#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Tally the machines GitHub's runner pool gave the nightly's jobs, and what the jobs cost.

Which CPU a GitHub-hosted job runs on is a draw from the pool, and the benchmark hosts are drawn
from the same pool (HARDWARE.md). Every sweep, fuzz and Arm job of .github/workflows/nightly.yml
starts with bench/datapath.sh, the step "Record the machine", which prints the CPU's model, its
vector flags, the JVM's vector settings and the datapath probe's reading. This script reads
those lines back from the jobs' logs (fetch) and tallies them by CPU (table).

The same logs say what a night costs. dev/nightly.sh brackets each species' JVM with lines
"== <mode>-<function>-<species>:", and Sweep and Fuzz print what they checked and in how many
seconds. With --cost, fetch writes a second file, one row per job and species run with the job's
own times, and cost tabulates it: runner-minutes per night, the time of a whole sweep and the
fuzz's rate per function and species.

GitHub keeps a run's logs for 90 days, so fetch's outputs are committed beside this script, and
table and cost work from those files alone.

fetch spends the account's REST quota, 5,000 calls an hour shared with everything else that
uses gh: one call for the list of runs, one per run for its jobs and one per job for its log.
So it keeps every answer in a directory (--cache), fetches the logs in batches with a pause
between them, asks the API before each batch how many calls are left (that question is free)
and stops, to be run again later, when fewer than --reserve remain.

Usage, from the repository root:
    bench/pool/pool.py fetch --since 2026-11-03 --until 2026-11-19 \
        --out rows.tsv --cost cost-rows.tsv                                     # needs gh
    bench/pool/pool.py table bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py cost bench/pool/nightly-cost-2026-11-03-to-2026-11-19.tsv
    bench/pool/pool.py selftest                                      # offline, no gh call
"""

import argparse
import collections
import concurrent.futures
import datetime
import json
import math
import os
import re
import subprocess
import sys
import time

WORKFLOW = "nightly.yml"
# The step that runs bench/datapath.sh; a job with no step of this name records no machine.
STEP = "Record the machine"
COLUMNS = ("date run job name cpu cores avx2 avx512f avx512dq flags jvm"
           " lanes128 lanes256 lanes512 control wide usable").split()
# --cost's columns. The first twelve say which run and job, of what commit, on what machine,
# when the job started and its seconds from start to end; the rest are one JVM of
# dev/nightly.sh, or "-" in a job that ran none.
COST_COLUMNS = ("date run attempt event commit job name conclusion cpu jvm started wall"
                " mode function species bits words inputs seconds exit jdk jvmargs").split()

# bench/datapath.sh's five lines, as a job's log holds them after the line's timestamp.
LINE = re.compile(r"Z (cpu|cores|vector flags|jvm|datapath): ?(.*?)\s*$")
FLAGS = re.compile(r"avx2=(yes|no) avx512f=(yes|no) avx512dq=(yes|no); all: ?(.*)$")
DATAPATH = re.compile(r"(\S+) / (\S+) / (\S+) lane-ops/ns at 128 / 256 / 512 bits;"
                      r" 256:128 = (\S+) \(control\), 512:256 = ([^\s;]+)(; unusable.*)?$")

# What dev/nightly.sh, Sweep and Fuzz print for one species: the bracket's two ends, the first
# line of the program, the JVM's own options, and the count with its seconds.
# A function of two arguments has an argument in its names: "sweep-atan2-arg1-256" and the job
# "sweep (atan2-arg1, stripe 0/2)"; the argument is left out of the function here.
BRACKET = re.compile(
    r"Z == (sweep|fuzz)-(\w+?)(?:-arg\d)?-(\w+): (?:(java) |exit (\d+)\s*$)")
AT_BITS = re.compile(r"Z (?:sweep|fuzz) \w+ at (\d+) bits")
JDK = re.compile(r"Z jdk: ?(.*?)\s*$")
JVM_ARGS = re.compile(r"Z jvm args: ?(.*?)\s*$")
SWEPT = re.compile(r"Z swept (\d+) high words \((\d+) inputs\) in ([\d.]+) s:")
FUZZED = re.compile(r"Z fuzzed (\d+) inputs in ([\d.]+) s:")
WHOLE = 2 ** 32   # the high words of a whole sweep
TIME = "%Y-%m-%dT%H:%M:%SZ"


# The seconds a gh call may take before it counts as failed, so one stalled download does not
# hold the whole fetch.
CALL_SECONDS = 120

# What gh said the first time a call that may fail did, for the message that counts them.
FIRST_FAILURE = []


def gh(args, repo, check=True, remember=True):
    """Runs gh on the repository; its output, or None when it fails and check is off."""
    cmd = ["gh"] + args + (["--repo", repo] if repo else [])
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, errors="replace",
                              timeout=CALL_SECONDS)
    except subprocess.TimeoutExpired:
        done = subprocess.CompletedProcess(cmd, 1, "", "no answer in %d s" % CALL_SECONDS)
    if done.returncode != 0:
        said = "%s failed: %s" % (" ".join(cmd), done.stderr.strip())
        if check:
            sys.exit(said)
        if remember and not FIRST_FAILURE:
            FIRST_FAILURE.append(said)
        return None
    return done.stdout


def planted(jvmargs):
    """Whether a JVM's own options plant a fault (-Dvfdlibm.fault=<name>, Faults.java). A
    dispatch of the nightly that plants one tests the alert job: it checks only the functions
    the fault reaches, its mismatches are expected, and it is no night."""
    return "vfdlibm.fault=" in jvmargs


def fault_runs(todo, found):
    """The ids of the runs among the jobs (date, run, job) whose log says (one reading each in
    found, or None) that a JVM of theirs planted a fault."""
    return {run for (_, run, _), f in zip(todo, found)
            if f and any(planted(one.get("jvmargs", "")) for one in f["species"])}


def host_of(repo):
    """gh api's option for the host of a --repo given as HOST/OWNER/REPO, or none."""
    parts = repo.split("/") if repo else []
    return ["--hostname", parts[0]] if len(parts) == 3 else []


def positive(text):
    """An argument that must be a whole number above zero."""
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("%s is not above zero" % text)
    return value


def iso_date(text):
    """An argument that must be a real day written YYYY-MM-DD. The shape is checked as well
    as the calendar, since date.fromisoformat takes other forms (20261120, 2026-W47-5)."""
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", text):
        raise argparse.ArgumentTypeError("%s is not a date as YYYY-MM-DD" % text)
    try:
        datetime.date.fromisoformat(text)
    except ValueError:
        raise argparse.ArgumentTypeError("%s is not a day of the calendar" % text) from None
    return text


def not_negative(kind):
    """The type of an argument that must be a number of that kind, zero or above."""
    def parse(text):
        value = kind(text)
        if not value >= 0:
            raise argparse.ArgumentTypeError("%s is not zero or above" % text)
        return value
    return parse


def kept(cache, name, ask):
    """The text the cache holds under the name, or what ask() returns, kept for the next time
    unless it is None. A completed run's jobs and logs do not change, so no answer gh gave
    is asked for twice, and a fetch that stopped goes on where it was. What gh refused is
    not kept, and is asked for again by the next fetch."""
    path = os.path.join(cache, name)
    if os.path.exists(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    text = ask()
    if text is not None:
        os.makedirs(cache, exist_ok=True)
        with open(path + ".part", "w", encoding="utf-8", errors="replace") as f:
            f.write(text)
        os.replace(path + ".part", path)
    return text


def cell(value):
    """A value as one cell of a row: "-" for none, and a tab or a character outside ASCII,
    which the files do not hold, as a space or a question mark."""
    text = str(value) if value not in (None, "") else "-"
    return text.replace("\t", " ").encode("ascii", "replace").decode("ascii")


def write_all(files):
    """Writes files of rows, each as (path, header, rows), all beside their places first and
    then into them, so that a fetch that fails while writing leaves the files that were
    there, and not one new file beside an old one."""
    for path, header, rows in files:
        with open(path + ".part", "w", encoding="ascii") as out:
            out.write("\t".join(header) + "\n")
            out.writelines("\t".join(cells) + "\n" for cells in rows)
    for path, _, _ in files:
        os.replace(path + ".part", path)


def calls_left(repo):
    """The REST calls the account has left this hour on the repository's host and the time
    they are renewed. Asking does not count against them. A fetch that cannot learn the
    number does not go on to spend it."""
    answer = gh(["api"] + host_of(repo) + [
        "rate_limit", "--jq", ".resources.core | \"\\(.remaining) \\(.reset)\""],
        None, check=False, remember=False)
    try:
        left, renewed = answer.split()
        return int(left), int(renewed)
    except (AttributeError, ValueError):
        sys.exit("gh api rate_limit did not say how many REST calls are left; nothing more"
                 " was asked for and no file was written")


def keep_reserve(args, calls, have):
    """Stops the fetch, to be run again later, when that many more calls would leave the
    account fewer than --reserve; have says what the cache holds, for the message."""
    left, renewed = calls_left(args.repo)
    if left - calls < args.reserve:
        sys.exit("%d REST calls are left and --reserve keeps %d; %s. Run the same command"
                 " again after %s UTC; no file was written."
                 % (left, args.reserve, have, time.strftime("%H:%M", time.gmtime(renewed))))


def nights(repo, since, until):
    """The nightly's completed runs created from since to until, as (date, run id, the run as
    gh lists it), oldest first. A run's date is the UTC day it was created on."""
    runs = json.loads(gh(["run", "list", "--workflow", WORKFLOW, "--limit", "1000", "--json",
                          "databaseId,createdAt,status,attempt,event,headSha"], repo))
    if len(runs) >= 1000 and (not since or min(r["createdAt"][:10] for r in runs) > since):
        sys.exit("the 1000 newest runs of %s are all gh lists, and %s; the earlier nights would"
                 " drop out of the range silently"
                 % (WORKFLOW, "--since reaches back past them" if since
                    else "with no --since the earliest is not known to be among them"))
    completed = [(r["createdAt"][:10], r["databaseId"], r) for r in runs
                 if r["status"] == "completed"]
    return sorted((n for n in completed
                   if (not since or n[0] >= since) and (not until or n[0] <= until)),
                  key=lambda n: n[:2])


def jobs_of(run, attempt, repo, cache):
    """Every job of the run's last attempt, as gh lists them. An earlier attempt's jobs are
    not listed, so a run that was run again counts for its last attempt only; --cost's
    attempt column says which runs those are."""
    return json.loads(kept(cache, "run-%s-%s.json" % (run, attempt), lambda: gh(
        ["run", "view", str(run), "--json", "jobs"], repo)))["jobs"]


def log_of(job, repo, cache):
    """The job's log as the API gives it, one line per line of output behind its time, or
    None when the API gives none. It is one call; `gh run view --log` makes more."""
    # --repo is [HOST/]OWNER/REPO; the API's path takes the last two, and the host goes apart.
    parts = repo.split("/") if repo else ["{owner}", "{repo}"]
    path = "repos/%s/actions/jobs/%s/logs" % ("/".join(parts[-2:]), job)
    return kept(cache, "job-%s.log" % job, lambda: gh(
        ["api", "--allow-escape-sequences"] + host_of(repo) + [path], None, check=False))


def records(job):
    """Whether the job has the step which records the machine."""
    return any(s["name"] == STEP for s in job.get("steps") or [])


def reading(job, repo, cache):
    """What a job's log says, or None with no log: the first of each of bench/datapath.sh's
    lines, and under "species" one dictionary per JVM that dev/nightly.sh ran.

    The first, because the step runs before the build and a later step may print a line that
    begins the same way."""
    log = log_of(job, repo, cache)
    if log is None:
        return None
    found = {"species": species_runs(log.splitlines())}
    for line in log.splitlines():
        m = LINE.search(line)
        if m and m.group(1) not in found:
            found[m.group(1)] = m.group(2)
    return found


def species_runs(lines):
    """One dictionary per "== <mode>-<function>-<species>:" bracket of the log, in its order.

    A bracket the log never closes, as when the job was cancelled, keeps the cells it has."""
    runs, now = [], None
    for line in lines:
        m = BRACKET.search(line)
        if m and m.group(4):
            now = {"mode": m.group(1), "function": m.group(2), "species": m.group(3)}
            runs.append(now)
        elif m and now and m.group(1, 2, 3) == (now["mode"], now["function"], now["species"]):
            now["exit"] = m.group(5)
            now = None
        elif now:
            for pattern, cells in ((AT_BITS, ("bits",)), (JDK, ("jdk",)),
                                   (JVM_ARGS, ("jvmargs",)),
                                   (SWEPT, ("words", "inputs", "seconds")),
                                   (FUZZED, ("inputs", "seconds"))):
                m = pattern.search(line)
                if m:
                    now.update(zip(cells, m.groups()))
    return runs


def seconds_between(start, end):
    """The whole seconds from one of the API's times to another; 0 when either is missing or
    the job never ran (a skipped job's end is before its start)."""
    try:
        when = [datetime.datetime.strptime(t, TIME) for t in (start, end)]
    except (TypeError, ValueError):
        return 0
    return max(0, int((when[1] - when[0]).total_seconds()))


def cost_rows(date, run, info, job, found):
    """The job's rows as the cells of COST_COLUMNS: one per species run, or one with "-" in the
    species' cells for a job that ran none.

    A job that ran no step, skipped or cancelled while it waited for a runner, never started:
    the API gives it a start all the same, the time it was created, so it gets "-" and 0. A
    step with no conclusion, or an empty one, did not run."""
    ran = job.get("conclusion") != "skipped" and any(
        (s.get("conclusion") or "skipped") not in ("skipped", "cancelled")
        for s in job.get("steps") or [])
    cells = {"date": date, "run": run, "attempt": info.get("attempt"),
             "event": info.get("event"), "commit": (info.get("headSha") or "")[:10],
             "job": job["databaseId"], "name": job["name"],
             "conclusion": job.get("conclusion"), "cpu": found.get("cpu"),
             "jvm": found.get("jvm"), "started": job.get("startedAt") if ran else None,
             "wall": seconds_between(job.get("startedAt"), job.get("completedAt")) if ran else 0}
    rows = []
    for one in found.get("species") or [{}]:
        both = dict(cells, **one)
        rows.append([cell(both.get(c)) for c in COST_COLUMNS])
    return rows


def row(date, run, job, found):
    """One job's reading as the cells of COLUMNS, "-" where the log had no such value."""
    flags = FLAGS.match(found.get("vector flags", ""))
    probe = DATAPATH.match(found.get("datapath", ""))
    cells = {"date": date, "run": run, "job": job["databaseId"], "name": job["name"],
             "cpu": found.get("cpu"), "cores": found.get("cores", "").split(";")[0],
             "jvm": found.get("jvm")}
    if flags:
        cells.update(zip(("avx2", "avx512f", "avx512dq", "flags"), flags.groups()))
    if probe:
        cells.update(zip(("lanes128", "lanes256", "lanes512", "control", "wide"), probe.groups()))
        cells["usable"] = "no" if probe.group(6) else "yes"
    return [cell(cells.get(c)) for c in COLUMNS]


def fetch(args):
    """Writes one row per recording job of the nights asked for, as tab-separated values, to
    --out or the standard output, and with --cost a second file of every job's times and
    species runs."""
    # gh pages the list 100 runs at a time, and a job list can take a second call.
    keep_reserve(args, 10, "the list of runs is not read yet")
    listed = nights(args.repo, args.since, args.until)
    # A run's job list is a call or two when the cache does not hold it; they are counted
    # against the reserve like the logs.
    unlisted = sum(1 for _, run, info in listed if not os.path.exists(
        os.path.join(args.cache, "run-%s-%s.json" % (run, info.get("attempt")))))
    if unlisted:
        keep_reserve(args, 2 * unlisted, "%d of %d runs' job lists are in %s"
                     % (len(listed) - unlisted, len(listed), args.cache))
    every = [(date, run, job, info) for date, run, info in listed
             for job in jobs_of(run, info.get("attempt"), args.repo, args.cache)]
    todo = [t[:3] for t in every if records(t[2])]
    if not todo:
        sys.exit("no job of %s has the step \"%s\" in that range" % (WORKFLOW, STEP))

    def read_one(t):
        return reading(t[2]["databaseId"], args.repo, args.cache)

    def in_cache(t):
        return os.path.exists(os.path.join(args.cache, "job-%s.log" % t[2]["databaseId"]))

    # The logs not in the cache yet, a batch at a time, each batch only while the account
    # keeps the reserve; what a batch read stays in the cache whether or not the next one runs.
    absent = [t for t in todo if not in_cache(t)]
    for start in range(0, len(absent), args.batch):
        batch = absent[start:start + args.batch]
        keep_reserve(args, len(batch), "%d of %d logs are in %s"
                     % (sum(1 for t in todo if in_cache(t)), len(todo), args.cache))
        if start:
            time.sleep(args.pause)
        with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:
            list(pool.map(read_one, batch))
        # A batch that got no log at all is the API refusing, or a gh that cannot ask: the
        # next batch would fare the same. Logs known to be gone are the one case to go on.
        if not any(in_cache(t) for t in batch) and not args.without_logs:
            sys.exit("none of a batch of %d logs could be read, so the fetch stopped and no"
                     " file was written; the first failure: %s. If the logs have expired,"
                     " --without-logs writes the rows without them."
                     % (len(batch), FIRST_FAILURE[0] if FIRST_FAILURE else "none said"))
    # A log the API did not give is not in the cache, and is not asked for a second time.
    found = [read_one(t) if in_cache(t) else None for t in todo]
    # Such a job's rows would look like a job with nothing to say, so no file is written for
    # them unless asked: logs that have expired leave the jobs' times, which are still worth
    # having.
    unlogged = sum(1 for f in found if f is None)
    if unlogged and not args.without_logs:
        sys.exit("%d of %d jobs' logs could not be read, so no file was written; the first"
                 " failure: %s. If the logs are gone for good, --without-logs writes those"
                 " jobs' rows without them."
                 % (unlogged, len(todo), FIRST_FAILURE[0] if FIRST_FAILURE else "none said"))
    # A run that planted a fault is a test of the alert, and is no night: out of both files.
    faulty = fault_runs(todo, found)
    if faulty:
        print("left out, a fault was planted: run %s" % ", ".join(map(str, sorted(faulty))),
              file=sys.stderr)
        every = [t for t in every if t[1] not in faulty]
        kept_rows = [(t, f) for t, f in zip(todo, found) if t[1] not in faulty]
        todo, found = [t for t, _ in kept_rows], [f for _, f in kept_rows]
        unlogged = sum(1 for f in found if f is None)
        if not todo:
            sys.exit("every run of that range planted a fault; no file was written")
    rows = sorted(row(date, run, job, f or {}) for (date, run, job), f in zip(todo, found))
    files = []
    if args.cost:
        read_of = {job["databaseId"]: f or {} for (_, _, job), f in zip(todo, found)}
        every.sort(key=lambda t: (t[0], t[1], t[2]["databaseId"]))
        files.append((args.cost, COST_COLUMNS,
                      [cells for date, run, job, info in every
                       for cells in cost_rows(date, run, info, job,
                                              read_of.get(job["databaseId"], {}))]))
    if args.out:
        files.append((args.out, COLUMNS, rows))
    try:
        write_all(files)
    except OSError as error:
        sys.exit("a file could not be written (%s); none was changed" % error)
    if not args.out:
        print("\t".join(COLUMNS))
        for cells in rows:
            print("\t".join(cells))
    unread = sum(1 for f in found if not f or "datapath" not in f)
    print("%d jobs of %d nights; %d without a reading" %
          (len(rows), len({t[1] for t in todo}), unread), file=sys.stderr)
    if unlogged:
        print("%d jobs' logs could not be read; their rows lack what a log says" % unlogged,
              file=sys.stderr)


def read(path, columns=COLUMNS):
    """The rows of a file that fetch wrote, as dictionaries."""
    with open(path, encoding="ascii") as f:
        lines = f.read().splitlines()
    if not lines or lines[0].split("\t") != columns:
        sys.exit("%s does not start with fetch's header" % path)
    return [dict(zip(columns, line.split("\t"))) for line in lines[1:]]


def arch(r):
    """"arm" or "x86" by the vector flags the job printed, or None when it printed none."""
    flags = r["flags"].split()
    if any(f.startswith(("asimd", "sve")) for f in flags):
        return "arm"
    if r["avx2"] != "-" and (r["cpu"] != "-" or any(f.startswith("avx") for f in flags)):
        return "x86"
    return None


def pretty(cpu):
    """A CPU's model name without its trademark signs, its core count and its frequency."""
    cpu = re.sub(r"\((R|TM)\)", "", cpu, flags=re.IGNORECASE)
    cpu = re.sub(r"\s+CPU @.*$|\s+\d+-Core Processor$", "", cpu)
    return " ".join(w.capitalize() if w in ("INTEL", "XEON", "PLATINUM") else w
                    for w in cpu.split())


def span(values, digits, sep):
    """The range of the values as text, or the one value when they are all equal."""
    lo, hi = "%.*f" % (digits, min(values)), "%.*f" % (digits, max(values))
    return lo if lo == hi else lo + sep + hi


def rates(group, column, digits=1, sep="-"):
    """The range of a column over the group's usable readings, or "-" when it has none."""
    values = [float(r[column]) for r in group if r["usable"] == "yes" and r[column] != "-"]
    return span(values, digits, sep) if values else "-"


def table(args):
    """Prints the x86 jobs by CPU, flags and JVM settings, then the Arm jobs by JVM settings."""
    rows = read(args.file)
    if not rows:
        sys.exit("%s holds no rows" % args.file)
    by_arch = collections.defaultdict(list)
    for r in rows:
        by_arch[arch(r)].append(r)
    all_nights = len({r["run"] for r in rows})   # a night is a run, as in cost
    runs = sorted(int(r["run"]) for r in rows)
    print("%d jobs of %d nights, %s to %s (runs %d to %d): %d x86, %d Arm, %d without a reading."
          % (len(rows), all_nights, min(r["date"] for r in rows), max(r["date"] for r in rows),
             runs[0], runs[-1], len(by_arch["x86"]), len(by_arch["arm"]), len(by_arch[None])))

    x86 = by_arch["x86"]
    groups = collections.defaultdict(list)
    for r in x86:
        groups[(pretty(r["cpu"]), r["avx512f"], r["avx512dq"], r["jvm"])].append(r)
    print()
    print("| CPU | jobs | share | nights of %d | `avx512f` / `avx512dq` | JVM |"
          " lane-ops/ns at 128 / 256 / 512 bits | 256:128 | 512:256 |" % all_nights)
    print("|---|---|---|---|---|---|---|---|---|")
    for (cpu, f, dq, jvm), group in sorted(groups.items(), key=lambda g: (-len(g[1]), g[0])):
        print("| %s | %d | %.1f%% | %d | %s / %s | `%s` | %s / %s / %s | %s | %s |" % (
            cpu, len(group), 100.0 * len(group) / len(x86), len({r["run"] for r in group}),
            f, dq, jvm, rates(group, "lanes128"), rates(group, "lanes256"),
            rates(group, "lanes512"), rates(group, "control", 2, " to "),
            rates(group, "wide", 2, " to ")))

    # On Arm the probe's three widths all run at the machine's one width, so its ratios say
    # nothing (bench/datapath.sh calls the reading unusable); the 128-bit rate is still a rate.
    groups = collections.defaultdict(list)
    for r in by_arch["arm"]:
        groups[(r["jvm"], r["flags"])].append(r)
    print()
    print("| Arm JVM | jobs | nights of %d | lane-ops/ns at 128 bits | vector flags |" % all_nights)
    print("|---|---|---|---|---|")
    for (jvm, flags), group in sorted(groups.items(), key=lambda g: (-len(g[1]), g[0])):
        values = [float(r["lanes128"]) for r in group if r["lanes128"] != "-"]
        print("| `%s` | %d | %d | %s | %s |" % (
            jvm, len(group), len({r["run"] for r in group}),
            span(values, 1, "-") if values else "-", flags))

    unusable = sum(1 for r in x86 if r["usable"] != "yes")
    if unusable:
        print()
        print("%d x86 readings are unusable (the control is not about 2) and are left out of"
              " the rates and ratios." % unusable)


def kind(name):
    """A job's kind, the first word of its name: plan, sweep, fuzz, arm, proofs or alert."""
    return name.split(" ")[0]


def function_of(name):
    """The function a sweep, fuzz or Arm job's name gives, "sweep (log1p, stripe 0/2)" or, of two
    arguments, "sweep (atan2-arg1, stripe 0/2)" (the argument is not part of it)."""
    m = re.match(r"\w+ \((\w+?)(?:-arg\d)?,", name)
    return m.group(1) if m else None


def machine(r):
    """The class of machine a row ran on, by the widest vector its JVM uses: the time of a
    sweep follows the species the host has before it follows the CPU's model."""
    size = re.search(r"MaxVectorSize=(\d+)", r["jvm"])
    if not size:
        return "-"
    # The JVM line says SVE on Arm and AVX on x86; a row with neither is not guessed at.
    if "UseSVE" not in r["jvm"] and "UseAVX" not in r["jvm"]:
        return "-"
    arm = "UseSVE" in r["jvm"]
    return "%s, %d bits" % ("Arm" if arm else "x86", 8 * int(size.group(1)))


def median(values):
    values = sorted(values)
    half = len(values) // 2
    return values[half] if len(values) % 2 else (values[half - 1] + values[half]) / 2.0


def low_mid_high(values, digits=1):
    return "%.*f | %.*f | %.*f" % (digits, min(values), digits, median(values),
                                   digits, max(values))


def species_label(r):
    """A run's species, the species "max" with the bits it had."""
    return r["species"] if r["species"] != "max" else "max (%s)" % r["bits"]


def cost(args):
    """Prints the nights' runner-minutes by job kind and by function, how long one job takes by
    kind, what the jobs do, the time of a whole sweep and the fuzz's rate by function, species
    and machine, for the nights from --since to --until."""
    rows = read(args.file, COST_COLUMNS)
    # --since and --until pick the nights, by the date of their run, to quote one set of them,
    # as the nights of five functions, apart from the two of four. The parser has checked them;
    # a caller that builds the arguments itself is checked here, as the strings are compared.
    for given in (args.since, args.until):
        if given:
            iso_date(given)
    rows = [r for r in rows if (not args.since or r["date"] >= args.since)
            and (not args.until or r["date"] <= args.until)]
    if not rows:
        sys.exit("%s holds no rows in that range" % args.file)
    jobs = {}
    for r in rows:
        jobs.setdefault(r["job"], r)
    jobs = list(jobs.values())
    # A run that planted a fault tests the alert job and is no night, though a file written
    # before that was known may hold it.
    faulty = {r["run"] for r in rows if planted(r["jvmargs"])}
    faulty_nights = sorted({(j["date"], int(j["run"])) for j in jobs if j["run"] in faulty})
    jobs = [j for j in jobs if j["run"] not in faulty]
    rows = [r for r in rows if r["run"] not in faulty]
    # A run in which no sweep, fuzz or Arm job started, cancelled as its jobs waited or
    # stopped by its plan, is no night: it is named at the end and left out, or it would
    # lower every mean and the least.
    busy = {j["run"] for j in jobs if function_of(j["name"]) and int(j["wall"]) > 0}
    idle = sorted({(j["date"], int(j["run"])) for j in jobs if j["run"] not in busy})
    jobs = [j for j in jobs if j["run"] in busy]
    rows = [r for r in rows if r["run"] in busy]
    if not jobs:
        sys.exit("%s holds no sweep, fuzz or Arm job that ran" % args.file)
    # A night is a run, not a date: a night replayed by a dispatch on the same day is a second
    # night, named with its run where the date alone would not tell them apart.
    runs = sorted({(j["date"], int(j["run"])) for j in jobs})
    dates = [date for date, _ in runs]
    night_of = {str(run): date if dates.count(date) == 1 else "%s, run %d" % (date, run)
                for date, run in runs}
    names = {kind(j["name"]) for j in jobs}
    kinds = [k for k in ("plan", "sweep", "fuzz", "arm", "proofs", "alert") if k in names]
    kinds += sorted(names - set(kinds))
    print("%d jobs of %d nights, %s to %s; %d species runs."
          % (len(jobs), len(runs), dates[0], dates[-1],
             sum(1 for r in rows if r["mode"] != "-")))

    # A night's cost is each job's seconds from start to end, summed as minutes, and the
    # minutes its jobs started, which is how a hosted runner's time is counted; its span is
    # the clock time from the first job's start to the last job's end, queueing included.
    print()
    print("Runner-minutes a night by job kind: the jobs' seconds from start to end, over 60.")
    print()
    print("| night | jobs | %s | all | runner-hours | minutes started | span, minutes |"
          % " | ".join(kinds))
    print("|---|---|%s---|---|---|---|" % ("---|" * len(kinds)))
    totals = []
    for date, run in runs:
        night = [j for j in jobs if j["run"] == str(run)]
        line = [sum(int(j["wall"]) for j in night if kind(j["name"]) == k) / 60.0
                for k in kinds]
        starts = [(datetime.datetime.strptime(j["started"], TIME), int(j["wall"]))
                  for j in night if j["started"] != "-"]
        # A night in which no job started, a run cancelled as it waited, spans nothing.
        begin = min([s for s, _ in starts], default=None)
        end = max([s + datetime.timedelta(seconds=w) for s, w in starts], default=None)
        line += [sum(line), sum(line) / 60.0,
                 sum(math.ceil(int(j["wall"]) / 60.0) for j in night),
                 (end - begin).total_seconds() / 60.0 if starts else 0.0]
        totals.append(line)
        print("| %s | %d | %s | %.0f | %.1f | %d | %.0f |" % (
            night_of[str(run)], len(night),
            " | ".join("%.0f" % m for m in line[:len(kinds)]), line[-4], line[-3], line[-2],
            line[-1]))
    for label, pick in (("least", min), ("median", median), ("most", max)):
        cells = [pick([t[i] for t in totals]) for i in range(len(totals[0]))]
        print("| %s | | %s | %.0f | %.1f | %.0f | %.0f |" % (
            label, " | ".join("%.0f" % m for m in cells[:len(kinds)]), cells[-4], cells[-3],
            cells[-2], cells[-1]))

    functions = sorted({function_of(j["name"]) for j in jobs} - {None})
    shown = [k for k in kinds if any(kind(j["name"]) == k and function_of(j["name"])
                                     for j in jobs)]
    print()
    print("Each of the least, median and most is its own column's, so a row of them is no one")
    print("night and its parts need not add up to its total.")
    print()
    print("Runner-minutes a night by function, the mean over the nights in which a job of it")
    print("ran.")
    print()
    print("| function | nights | %s | all |" % " | ".join(shown))
    print("|---|---|%s---|" % ("---|" * len(shown)))
    for f in functions:
        mine = [j for j in jobs if function_of(j["name"]) == f and int(j["wall"]) > 0]
        if not mine:
            continue
        count = len({j["run"] for j in mine})
        cells = [sum(int(j["wall"]) for j in mine if kind(j["name"]) == k) / 60.0 / count
                 for k in shown]
        print("| %s | %d | %s | %.0f |" % (f, count, " | ".join("%.0f" % m for m in cells),
                                          sum(cells)))

    # Where the minutes of each kind of job go: the seconds its sweeps and fuzzes report, by
    # species, and the rest of the job's time, which is its setup, build, probe and JVM starts.
    spent = collections.defaultdict(float)
    for j in jobs:
        spent[(kind(j["name"]), "the rest", "", 0)] += int(j["wall"])
    for r in rows:
        if r["mode"] != "-" and r["seconds"] != "-":
            bits = int(r["bits"]) if r["bits"] != "-" else 0
            spent[(kind(r["name"]), r["mode"], species_label(r), bits)] += float(r["seconds"])
            spent[(kind(r["name"]), "the rest", "", 0)] -= float(r["seconds"])
    # How long one job takes bounds the night's wall time with the slots a kind has
    # (max-parallel in nightly.yml): a kind's minutes over its slots, and its longest job.
    print()
    print("Minutes one job takes, by job kind, over the jobs that ran.")
    print()
    print("| job kind | jobs | least | median | most |")
    print("|---|---|---|---|---|")
    for k in kinds:
        walls = [int(j["wall"]) / 60.0 for j in jobs if kind(j["name"]) == k
                 and int(j["wall"]) > 0]
        if walls:
            print("| %s | %d | %s |" % (k, len(walls), low_mid_high(walls)))
    print()
    print("Runner-minutes a night by job kind and what the job was doing, the mean over all")
    print("%d nights; a species a night's machines lacked adds nothing for that night." % len(runs))
    print()
    print("| job kind | doing | species | minutes a night | share of the kind |")
    print("|---|---|---|---|---|")
    for k in shown:
        whole = sum(int(j["wall"]) for j in jobs if kind(j["name"]) == k)
        mine = sorted((key for key in spent if key[0] == k),
                      key=lambda key: (key[1] == "the rest", key[1] != "sweep", key[3], key[2]))
        for key in mine:
            share = "%.0f%%" % (100.0 * spent[key] / whole) if whole else "-"
            print("| %s | %s | %s | %.1f | %s |" % (
                k, key[1], key[2], spent[key] / 60.0 / len(runs), share))
    print()
    print("Over those kinds: %s minutes a night." % ", ".join(
        "%s %.0f" % (doing, sum(v for key, v in spent.items()
                                if key[0] in shown and key[1] == doing) / 60.0 / len(runs))
        for doing in ("sweep", "fuzz", "the rest")))

    # A sweep's seconds scaled from its stripe to all 2^32 high words: what one runner would
    # take for the whole function at that species. One CPU model is drawn with and without
    # its 512-bit vectors, so by CPU the machine's widest vector stays in the key.
    if args.by_cpu:
        def where(r):
            width = machine(r)
            if r["cpu"] == "-":
                return width
            return pretty(r["cpu"]) + (", " + width.split(", ")[1] if width != "-" else "")
    else:
        where = machine
    done = [r for r in rows if r["exit"] == "0" and r["seconds"] != "-"
            and float(r["seconds"]) > 0 and r["bits"] != "-"
            and (r["inputs"] if r["mode"] == "fuzz" else r["words"]) not in ("-", "0")]
    sweeps = collections.defaultdict(list)
    for r in done:
        if r["mode"] == "sweep":
            sweeps[(r["function"], where(r), int(r["bits"]), species_label(r))].append(
                float(r["seconds"]) * WHOLE / int(r["words"]) / 60.0)
    print()
    print("Minutes of one runner to sweep every high word of a function: each run's seconds,")
    print("scaled from the high words it swept to all 2^32.")
    print()
    print("| function | machine | species | runs | least | median | most |")
    print("|---|---|---|---|---|---|---|")
    for (f, place, _, label), values in sorted(sweeps.items()):
        print("| %s | %s | %s | %d | %s |" % (f, place, label, len(values),
                                             low_mid_high(values)))

    fuzzes = collections.defaultdict(list)
    for r in done:
        if r["mode"] == "fuzz":
            flags = "as AVX2" if "UseAVX=2" in r["jvmargs"] else "its own"
            fuzzes[(r["function"], where(r), flags, int(r["bits"]), species_label(r))].append(
                int(r["inputs"]) / float(r["seconds"]) / 1e6)
    print()
    print("The fuzz's millions of inputs a second. A fuzz runs for a set time, so its cost is")
    print("the seconds asked for times the species; the rate says what that time buys.")
    print()
    print("| function | machine | flags | species | runs | least | median | most |")
    print("|---|---|---|---|---|---|---|---|")
    for (f, place, flags, _, label), values in sorted(fuzzes.items()):
        print("| %s | %s | %s | %s | %d | %s |" % (f, place, flags, label, len(values),
                                                  low_mid_high(values)))

    kept_ids = {id(r) for r in done}
    odd = sum(1 for r in rows if r["mode"] != "-" and id(r) not in kept_ids)
    if odd:
        print()
        print("%d species runs did not exit 0, or lack their seconds, bits or count, and are"
              " left out of the last two tables; a run that reports no seconds, stopped by a"
              " timeout or a cancel, has its time under \"the rest\" in the third." % odd)
    blank = sum(1 for j in jobs if function_of(j["name"]) and int(j["wall"]) > 0
                and j["mode"] == "-")
    if blank:
        print()
        print("%d sweep, fuzz or Arm jobs ran and have no species run in the file (the job"
              " ended before its first, or its log could not be read): their time is in the"
              " nights' tables, as \"the rest\" in the third, and the last two tables have"
              " no run of theirs." % blank)
    if faulty_nights:
        print()
        print("Left out, a fault was planted in them: %s." % "; ".join(
            "%s, run %d" % night for night in faulty_nights))
    if idle:
        print()
        print("Left out, since no sweep, fuzz or Arm job of theirs started: %s." % "; ".join(
            "%s, run %d" % night for night in idle))
    again = sorted({night_of[j["run"]] for j in jobs if j["attempt"] not in ("1", "-")})
    if again:
        print()
        print("Run again, and counted for the last attempt only: %s." % "; ".join(again))
    # The commit of each night in order, as spans of nights that ran one commit one after
    # another: a commit that came back after another gets a second span, not one across both.
    spans = []
    for date, run in runs:
        commit = next(j["commit"] for j in jobs if j["run"] == str(run))
        if spans and spans[-1][0] == commit:
            spans[-1][2] = date
        else:
            spans.append([commit, date, date])
    jdks = sorted({re.sub(r"\s*\(\w+\)$", "", r["jdk"]) for r in rows if r["jdk"] != "-"})
    print()
    print("Commits: %s." % "; ".join(
        "%s (%s)" % (c, first if first == last else "%s to %s" % (first, last))
        for c, first, last in spans))
    print("JDK: %s." % ("; ".join(jdks) if jdks else "none recorded"))


def selftest(args):
    """Checks the readers and the fault-run rule on rows made up here, with no gh call: a
    night that planted a fault is left out by fetch's rule and by cost, a night that did not
    stays, and what a log's lines say is read as the cost file's cells."""
    import io
    import contextlib
    import tempfile

    def log(jvmargs, code):
        stamp = "2026-11-19T10:00:00.0Z "
        return [stamp + "== sweep-exp-256: java --add-modules x Sweep ...",
                stamp + "sweep exp at 256 bits, --stripe 0/2: 2147483648 high words",
                stamp + "jdk: OpenJDK 64-Bit Server VM 25 (amd64)",
                stamp + "jvm args: " + jvmargs,
                stamp + "swept 2147483648 high words (6442450944 inputs) in 300.0 s: 7/s",
                stamp + "== sweep-exp-256: exit %d" % code]

    plain = species_runs(log("--add-modules=x", 0))
    assert len(plain) == 1 and plain[0]["seconds"] == "300.0" and plain[0]["bits"] == "256"
    assert plain[0]["jdk"].endswith("(amd64)") and plain[0]["exit"] == "0", plain
    hit = species_runs(log("--add-modules=x -Dvfdlibm.fault=exp.dropCorrection", 1))
    assert hit[0]["exit"] == "1" and planted(hit[0]["jvmargs"]), hit
    assert not planted(plain[0]["jvmargs"])

    # A function of two arguments has the argument in its names (the sweep's bracket and the job's
    # name), which is not part of the function.
    stamp = "2026-11-19T10:00:00.0Z "
    pair = species_runs([stamp + "== sweep-atan2-arg1-256: java --add-modules x Sweep ...",
                         stamp + "sweep atan2 at 256 bits, --part 0/2 --argument 1: 4096 words",
                         stamp + "swept 4096 high words (12288 inputs) in 3.0 s: 7/s",
                         stamp + "== sweep-atan2-arg1-256: exit 0"])
    assert len(pair) == 1 and pair[0]["function"] == "atan2" and pair[0]["species"] == "256", pair
    assert pair[0]["exit"] == "0" and pair[0]["bits"] == "256", pair
    for name, want in (("sweep (atan2-arg2, stripe 1/4)", "atan2"), ("arm (atan2-arg1, stripe 0/4)",
                       "atan2"), ("fuzz (atan2, as AVX2)", "atan2"), ("sweep (log1p, stripe 0/2)",
                       "log1p"), ("proofs", None)):
        assert function_of(name) == want, (name, function_of(name))

    # fetch's rule: the run whose reading planted a fault goes, the other stays.
    todo = [("d", 1, {}), ("d", 1, {}), ("d", 2, {}), ("d", 3, {})]
    found = [{"species": plain}, {"species": hit}, {"species": plain}, None]
    assert fault_runs(todo, found) == {1}, fault_runs(todo, found)

    # cost's rule: the same two nights as rows, the faulty one named and not counted.
    def job_row(date, run, name, jvmargs, wall=600):
        cells = dict(zip(COST_COLUMNS, ["-"] * len(COST_COLUMNS)))
        cells.update(date=date, run=str(run), attempt="1", event="schedule", commit="abc",
                     job=str(run * 10 + len(name)), name=name, conclusion="success",
                     jvm="MaxVectorSize=32 UseAVX=2", started="2026-11-19T10:00:00Z",
                     wall=str(wall), mode="sweep", function="exp", species="256", bits="256",
                     words="2147483648", inputs="6442450944", seconds="300.0", exit="0",
                     jdk="JDK", jvmargs=jvmargs)
        return "\t".join(cells[c] for c in COST_COLUMNS)

    lines = ["\t".join(COST_COLUMNS),
             job_row("2026-11-18", 1, "sweep (exp, stripe 0/2)", "--add-modules=x"),
             job_row("2026-11-19", 2, "sweep (exp, stripe 0/2)",
                     "--add-modules=x -Dvfdlibm.fault=exp.dropCorrection"),
             job_row("2026-11-20", 3, "sweep (exp, stripe 0/2)", "--add-modules=x")]
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "cost.tsv")
        with open(path, "w", encoding="ascii") as out:
            out.write("\n".join(lines) + "\n")
        text = io.StringIO()
        with contextlib.redirect_stdout(text):
            cost(argparse.Namespace(file=path, by_cpu=False, since=None, until=None))
        said = text.getvalue()
        assert said.startswith("2 jobs of 2 nights, 2026-11-18 to 2026-11-20;"), said[:80]
        assert "Minutes one job takes" in said
        # --since picks nights by date.
        text = io.StringIO()
        with contextlib.redirect_stdout(text):
            cost(argparse.Namespace(file=path, by_cpu=False, since="2026-11-20", until=None))
        assert text.getvalue().startswith("1 jobs of 1 nights, 2026-11-20 to 2026-11-20;")
        assert "Left out, a fault was planted in them: 2026-11-19, run 2." in said, said[-400:]
        assert "| 2026-11-19 |" not in said
    # A date must be a real day written YYYY-MM-DD: the shape alone, 2026-13-45, a week date and
    # a compact one are refused, and a real day is kept.
    assert iso_date("2026-11-20") == "2026-11-20"
    for bad in ("2026-11-5", "2026-13-45", "2026-02-30", "20261120", "2026-W47-5", ""):
        try:
            iso_date(bad)
        except argparse.ArgumentTypeError:
            continue
        raise AssertionError("a date was taken: " + repr(bad))
    # The command line takes a date only through iso_date, on all four options: a real day
    # parses, and a bad one is refused with a message that names it.
    for command in ("fetch", "cost"):
        for option in ("--since", "--until"):
            argv = [command] + (["x.tsv"] if command == "cost" else []) + [option]
            assert getattr(build_parser().parse_args(argv + ["2026-11-20"]),
                           option[2:]) == "2026-11-20"
            for given in ("2026-11-5", "2026-13-45"):
                said_err = io.StringIO()
                try:
                    with contextlib.redirect_stderr(said_err):
                        build_parser().parse_args(argv + [given])
                except SystemExit:
                    assert given + " is not a" in said_err.getvalue(), said_err.getvalue()
                    continue
                raise AssertionError("the command line took " + " ".join(argv + [given]))
    print("pool.py selftest: the fault-run rule, the log readers and the dates passed")


def build_parser():
    """The command line's parser, apart from main so that selftest can try it."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    f = commands.add_parser("fetch", help="read the jobs' logs with gh; rows go to stdout")
    f.add_argument("--repo", help="OWNER/REPO; default the checkout's repository")
    f.add_argument("--since", type=iso_date,
                   help="the first night, YYYY-MM-DD (UTC); default the earliest")
    f.add_argument("--until", type=iso_date,
                   help="the last night, YYYY-MM-DD (UTC); default the latest")
    f.add_argument("--parallel", type=positive, default=4,
                   help="logs fetched at once (default 4)")
    f.add_argument("--without-logs", action="store_true",
                   help="write the rows even when some jobs' logs cannot be read (a run that"
                        " planted a fault is then known only by its logs, so it stays in)")
    f.add_argument("--cache", default="target/pool-cache", metavar="DIR",
                   help="where the runs' job lists and the jobs' logs are kept, so that none"
                        " is fetched twice (default target/pool-cache)")
    f.add_argument("--batch", type=positive, default=50,
                   help="logs fetched between two looks at the calls left (default 50)")
    f.add_argument("--pause", type=not_negative(float), default=10.0,
                   help="seconds to wait between batches (default 10)")
    f.add_argument("--reserve", type=not_negative(int), default=2000,
                   help="stop when a batch would leave the account fewer REST calls than this"
                        " (default 2000)")
    f.add_argument("--out", metavar="FILE",
                   help="write the census rows to FILE, whole or not at all; default the"
                        " standard output")
    f.add_argument("--cost", metavar="FILE",
                   help="also write every job's times and species runs to FILE, for cost")
    f.set_defaults(run=fetch)
    t = commands.add_parser("table", help="tally a file of rows that fetch wrote")
    t.add_argument("file")
    t.set_defaults(run=table)
    c = commands.add_parser("cost", help="tabulate a file that fetch --cost wrote")
    c.add_argument("file")
    c.add_argument("--since", type=iso_date,
                   help="the first night to count, YYYY-MM-DD; default the earliest")
    c.add_argument("--until", type=iso_date,
                   help="the last night to count, YYYY-MM-DD; default the latest")
    c.add_argument("--by-cpu", action="store_true",
                   help="split the sweep and fuzz tables by CPU model, not by vector width")
    c.set_defaults(run=cost)
    st = commands.add_parser("selftest", help="check the readers and the fault-run rule offline")
    st.set_defaults(run=selftest)
    return parser


def main():
    args = build_parser().parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
