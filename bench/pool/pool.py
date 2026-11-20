#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Tally the machines GitHub's runner pool gave the nightly's jobs.

Which CPU a GitHub-hosted job runs on is a draw from the pool, and the benchmark hosts are drawn
from the same pool (HARDWARE.md). Every sweep, fuzz and Arm job of .github/workflows/nightly.yml
starts with bench/datapath.sh, the step "Record the machine", which prints the CPU's model, its
vector flags, the JVM's vector settings and the datapath probe's reading. This script reads
those lines back from the jobs' logs (fetch) and tallies them by CPU (table).

GitHub keeps a run's logs for 90 days, so fetch's output, one row per job, is committed beside
this script, and table works from that file alone.

Usage, from the repository root:
    bench/pool/pool.py fetch --since 2026-11-03 --until 2026-11-19 > rows.tsv   # needs gh
    bench/pool/pool.py table bench/pool/nightly-2026-11-03-to-2026-11-19.tsv
"""

import argparse
import collections
import concurrent.futures
import json
import re
import subprocess
import sys

WORKFLOW = "nightly.yml"
# The step that runs bench/datapath.sh; a job with no step of this name records no machine.
STEP = "Record the machine"
COLUMNS = ("date run job name cpu cores avx2 avx512f avx512dq flags jvm"
           " lanes128 lanes256 lanes512 control wide usable").split()

# bench/datapath.sh's five lines, as a job's log holds them after the line's timestamp.
LINE = re.compile(r"Z (cpu|cores|vector flags|jvm|datapath): ?(.*?)\s*$")
FLAGS = re.compile(r"avx2=(yes|no) avx512f=(yes|no) avx512dq=(yes|no); all: ?(.*)$")
DATAPATH = re.compile(r"(\S+) / (\S+) / (\S+) lane-ops/ns at 128 / 256 / 512 bits;"
                      r" 256:128 = (\S+) \(control\), 512:256 = ([^\s;]+)(; unusable.*)?$")


def gh(args, repo, check=True):
    """Runs gh on the repository; its output, or None when it fails and check is off."""
    cmd = ["gh"] + args + (["--repo", repo] if repo else [])
    done = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    if done.returncode != 0:
        if check:
            sys.exit("%s failed: %s" % (" ".join(cmd), done.stderr.strip()))
        return None
    return done.stdout


def nights(repo, since, until):
    """The nightly's completed runs created from since to until, as (date, run id), oldest
    first. A run's date is the UTC day it was created on."""
    runs = json.loads(gh(["run", "list", "--workflow", WORKFLOW, "--limit", "1000",
                          "--json", "databaseId,createdAt,status"], repo))
    kept = [(r["createdAt"][:10], r["databaseId"]) for r in runs if r["status"] == "completed"]
    return sorted(n for n in kept if (not since or n[0] >= since) and (not until or n[0] <= until))


def recording_jobs(run, repo):
    """The run's jobs that have the step which records the machine."""
    jobs = json.loads(gh(["run", "view", str(run), "--json", "jobs"], repo))["jobs"]
    return [j for j in jobs if any(s["name"] == STEP for s in j.get("steps") or [])]


def reading(job, repo):
    """The first of each of bench/datapath.sh's lines in the job's log, or None with no log.

    The first, because the step runs before the build and a later step may print a line that
    begins the same way."""
    log = gh(["run", "view", "--job", str(job), "--log"], repo, check=False)
    if log is None:
        return None
    found = {}
    for line in log.splitlines():
        m = LINE.search(line)
        if m and m.group(1) not in found:
            found[m.group(1)] = m.group(2)
    return found


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
    return [str(cells.get(c) or "-").replace("\t", " ") for c in COLUMNS]


def fetch(args):
    """Writes one row per recording job of the nights asked for, as tab-separated values."""
    todo = [(date, run, job) for date, run in nights(args.repo, args.since, args.until)
            for job in recording_jobs(run, args.repo)]
    if not todo:
        sys.exit("no job of %s has the step \"%s\" in that range" % (WORKFLOW, STEP))
    with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:
        found = list(pool.map(lambda t: reading(t[2]["databaseId"], args.repo), todo))
    rows = sorted(row(date, run, job, f or {}) for (date, run, job), f in zip(todo, found))
    print("\t".join(COLUMNS))
    for cells in rows:
        print("\t".join(cells))
    unread = sum(1 for f in found if not f or "datapath" not in f)
    print("%d jobs of %d nights; %d without a reading" %
          (len(rows), len({t[0] for t in todo}), unread), file=sys.stderr)


def read(path):
    """The rows of a file that fetch wrote, as dictionaries."""
    with open(path, encoding="ascii") as f:
        lines = f.read().splitlines()
    if not lines or lines[0].split("\t") != COLUMNS:
        sys.exit("%s does not start with fetch's header" % path)
    return [dict(zip(COLUMNS, line.split("\t"))) for line in lines[1:]]


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
    by_arch = collections.defaultdict(list)
    for r in rows:
        by_arch[arch(r)].append(r)
    all_nights = len({r["date"] for r in rows})
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
            cpu, len(group), 100.0 * len(group) / len(x86), len({r["date"] for r in group}),
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
            jvm, len(group), len({r["date"] for r in group}),
            span(values, 1, "-") if values else "-", flags))

    unusable = sum(1 for r in x86 if r["usable"] != "yes")
    if unusable:
        print()
        print("%d x86 readings are unusable (the control is not about 2) and are left out of"
              " the rates and ratios." % unusable)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    f = commands.add_parser("fetch", help="read the jobs' logs with gh; rows go to stdout")
    f.add_argument("--repo", help="OWNER/REPO; default the checkout's repository")
    f.add_argument("--since", help="the first night, YYYY-MM-DD (UTC); default the earliest")
    f.add_argument("--until", help="the last night, YYYY-MM-DD (UTC); default the latest")
    f.add_argument("--parallel", type=int, default=8, help="logs fetched at once (default 8)")
    f.set_defaults(run=fetch)
    t = commands.add_parser("table", help="tally a file of rows that fetch wrote")
    t.add_argument("file")
    t.set_defaults(run=table)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
