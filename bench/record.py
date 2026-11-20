#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Write a results file from a benchmark run's artifact, or check a committed one against it.

.github/workflows/benchmark.yml uploads an artifact, "bench-results": bench-results.txt (the
run's header and Maven's whole output, or bench/pair.sh's results file), datapath.txt, and for a
pair the directory pair/ with each round's JSON. A file under bench/results/ is that artifact
with its machine-made parts put in the committed form and a title, the reason for the run and a
reading written by hand. This script writes the machine-made parts, so that nobody copies a
number by hand, and checks them in a file that is about to be committed.

    bench/record.py write --run 41915514750              # downloads the artifact (needs gh)
    bench/record.py write <artifact dir> [--out-dir bench/results] [--name exp-onediv]
    bench/record.py check <results file> <artifact dir>  # or: check <results file> --run <id>
    bench/record.py --selftest

`write` gives one file per benchmark class of a plain run, <function>-<host>-<date>.txt, each
with its own class's blocks and rows only, and one file for a pair, <function>-pair-<host>-
<date>.txt; --name replaces the <function> part. It never overwrites a file without --force.
What it writes:

- the workflow's header lines, "Host" to "Command", with the load at the end of the run moved up
  beside the load at the start, and a line longer than 100 columns continued on the next;
- the table of each row's minimum, average and maximum: for a plain run from the "Result" blocks
  of JMH's output, for a pair from every measured iteration in pair/<side>-<round>.json, one
  table a side;
- the raw section: for a plain run, the artifact from its first JMH line with Maven's lines
  dropped; for a pair, bench/pair.sh's results file whole. JMH's plus-minus and approximately
  signs are written in ASCII, and anything else that is not ASCII is an error.

The hand-written parts are left as lines that hold MARKER, each saying what belongs there.

`check` reads a results file's header lines, tables and raw section and compares them with what
`write` makes from the artifact, and fails if they differ or if a marker line is still there.
It compares values, not layout: a header line may be wrapped another way, a table's columns may
be wider, and the file may hold header lines the workflow did not write (such as "Array").
Exit status 1 lists the differences; 2 is an error (no artifact, no result in it).
"""

import argparse
import datetime
import glob
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile

MARKER = "[bench/record.py]"
WIDTH = 100
PACKAGE = "io.github.vecbricks.vfdlibm."
# A header line of the workflow or of bench/pair.sh, and a line that continues one.
FIELD = re.compile(r"^# ([A-Z][A-Za-z0-9-]*(?: [a-z]+)?): +(\S.*?)\s*$")
CONTINUED = re.compile(r"^# {14}(\S.*?)\s*$")
MAVEN = re.compile(r"^\[(INFO|WARNING|ERROR)\]")
TABLE_ROW = re.compile(r"^# (\S+\.\S+)\s+(\S+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)$")
LOAD = re.compile(r"(\d+\.\d+ \d+\.\d+ \d+\.\d+) at (start|end)")
RAW_NOTE = ["# Raw output of the run follows: the workflow's artifact from its first JMH line,"
            " with Maven's",
            "# lines dropped and JMH's plus-minus and approximately signs written in ASCII."]
PAIR_NOTE = "# bench/pair.sh's results file follows."


class Problem(Exception):
    """The artifact or the file cannot be read as what it should be: exit status 2."""


def ascii_lines(text, what):
    text = text.replace("\u00b1", "+/-").replace("\u2248", "~")
    try:
        text.encode("ascii")
    except UnicodeEncodeError as e:
        raise Problem("%s holds a non-ASCII character other than JMH's two signs: %r"
                      % (what, text[e.start:e.start + 20]))
    return [line.rstrip() for line in text.split("\n")]


def find_artifact(path):
    """The directory that holds bench-results.txt: path itself, or the directory gh run
    download makes under it for the artifact."""
    for d in (path, os.path.join(path, "bench-results")):
        if os.path.isfile(os.path.join(d, "bench-results.txt")):
            return d
    raise Problem("no bench-results.txt in %s or in its bench-results/" % path)


def download(run, repo):
    d = tempfile.mkdtemp(prefix="bench-record-%s-" % run)
    cmd = ["gh", "run", "download", str(run), "--name", "bench-results", "--dir", d]
    done = subprocess.run(cmd + (["--repo", repo] if repo else []), capture_output=True, text=True)
    if done.returncode != 0:
        raise Problem("%s failed: %s" % (" ".join(cmd), done.stderr.strip()))
    return d


def fields(lines):
    """The header fields of some lines, in order: [key, value], a continued value joined with
    a newline. A line that is neither a field nor a continuation ends the field before it."""
    out, open_field = [], None
    for line in lines:
        m = FIELD.match(line)
        if m:
            open_field = [m.group(1), m.group(2)]
            out.append(open_field)
            continue
        m = CONTINUED.match(line)
        if m and open_field:
            open_field[1] += "\n" + m.group(1)
        else:
            open_field = None
    return out


def same_field(key, a, b):
    if key == "Load":
        return LOAD.findall(a) == LOAD.findall(b)
    return a.split() == b.split()


def header_lines(pairs):
    out = []
    for key, value in pairs:
        line = "# %-12s" % (key + ":")
        for part in value.split("\n"):
            words = 0
            for word in part.split():
                if words and len(line) + 1 + len(word) > WIDTH:
                    out.append(line)
                    line, words = "#" + " " * 13, 0
                line, words = line + " " + word, words + 1
            out.append(line)
            line = "#" + " " * 13
    return out


def table(rows, column):
    """rows: (benchmark, parameters, minimum, average, maximum), sorted."""
    w = max(len(r[0]) for r in rows) + 1
    p = max([len(column)] + [len(r[1]) for r in rows])
    out = ["# %-*s %-*s %12s %12s %12s   (ns/op)" % (w, "Row", p, column, "min", "avg", "max")]
    for name, params, lo, avg, hi in rows:
        out.append("# %-*s %-*s %12.1f %12.1f %12.1f" % (w, name, p, params, lo, avg, hi))
    return out


def column_name(names):
    return names.pop() if len(names) == 1 else "params"


# A plain run.

BENCHMARK = re.compile(r"^# Benchmark: (\S+)$")
PARAMETERS = re.compile(r"^# Parameters: \((.*)\)$")
RESULT = re.compile(r'^Result "(\S+)":$')
MIN_AVG_MAX = re.compile(r"^\s*\(min, avg, max\) = \(([^,]+), ([^,]+), ([^)]+)\)")
UNIT = re.compile(r"^\s*\S+ \+/-\(99\.9%\) \S+ (\S+) \[Average\]$")
SUMMARY_ROW = re.compile(r"^(\w+)\.\w+(:\S+)?\s")


def short(benchmark):
    return benchmark[len(PACKAGE):] if benchmark.startswith(PACKAGE) else benchmark


def plain_raw(lines):
    """JMH's output: from its first line on, without Maven's lines and the workflow's last."""
    start = next((i for i, l in enumerate(lines) if l.startswith("# JMH version:")), None)
    if start is None:
        raise Problem("no JMH output in bench-results.txt: did the run fail before JMH?")
    raw = [l for l in lines[start:] if not MAVEN.match(l) and not l.startswith("# Load:")]
    while raw and not raw[-1]:
        raw.pop()
    return raw


def plain_parts(raw):
    """The raw lines split in order into (class or None, lines): a class's benchmark blocks,
    and the lines every class shares (the end of the run and the summary table's heading)."""
    parts, tail = [], False
    for line in raw:
        if line.startswith("# Run complete."):
            tail = True
        if tail:
            m = SUMMARY_ROW.match(line)
            cls = m.group(1) if m and not line.startswith("Benchmark ") else None
            parts.append([cls, [line]])
            continue
        if line.startswith("# JMH version:"):
            parts.append(["?", []])
        parts[-1][1].append(line)
        m = BENCHMARK.match(line)
        if m:
            parts[-1][0] = short(m.group(1)).rsplit(".", 1)[0]
    if any(cls == "?" for cls, _ in parts):
        raise Problem("a JMH block names no benchmark")
    return parts


def plain_rows(raw):
    """(benchmark, parameters, min, avg, max) from each Result block, and the column's name."""
    rows, names, params = [], set(), ""
    for i, line in enumerate(raw):
        m = PARAMETERS.match(line)
        if m:
            pairs = [p.split(" = ", 1) for p in m.group(1).split(", ")]
            names.add(",".join(k for k, _ in pairs))
            params = ",".join(v for _, v in pairs)
        m = RESULT.match(line)
        if m:
            following = raw[i + 1:i + 3] + ["", ""]
            unit, n = UNIT.match(following[0]), MIN_AVG_MAX.match(following[1])
            if not unit or not n:
                raise Problem("the Result block of %s is not JMH's three lines" % m.group(1))
            if unit.group(1) != "ns/op":
                raise Problem("%s is in %s, and the table is in ns/op"
                              % (m.group(1), unit.group(1)))
            rows.append((short(m.group(1)), params) + tuple(float(n.group(j)) for j in (1, 2, 3)))
        if line.startswith("# JMH version:"):
            params = ""
    if not rows:
        raise Problem("no Result block in JMH's output")
    return sorted(rows), column_name(names) if names else "params"


def plain_header(lines):
    """The workflow's header fields, the end's load beside the start's."""
    head = lines[:next(i for i, l in enumerate(lines) if not l.startswith("#"))]
    out = fields(head[1:])
    end = [l for l in lines if l.startswith("# Load:") and l.endswith("at end")]
    for f in out:
        if f[0] == "Load":
            m = re.match(r"(.*) at start( \(\d+ cores\))?$", f[1])
            if not m:
                raise Problem("the header's Load line is not the workflow's: %s" % f[1])
            at_end = LOAD.search(end[-1]).group(1) + " at end" if end else "not recorded at end"
            f[1] = "%s at start, %s%s" % (m.group(1), at_end, m.group(2) or "")
    return out


def function_of(cls):
    name = cls.rsplit(".", 1)[-1]
    return (name[:-len("Benchmark")] if name.endswith("Benchmark") else name).lower()


def host_and_date(title, header):
    host = dict((k, v) for k, v in header).get("Host", "unknown").split()[0]
    m = re.search(r"(\d{4}-\d{2}-\d{2}) \d{2}:\d{2} UTC", title)
    return host, m.group(1) if m else datetime.date.today().isoformat()


def mark(text):
    return "# %s %s" % (MARKER, text)


def plain_files(lines, name):
    """{file name: lines} for a plain run, one file per benchmark class."""
    title, header = lines[0], plain_header(lines)
    host, date = host_and_date(title, header)
    raw = plain_raw(lines)
    parts = plain_parts(raw)
    classes = sorted({cls for cls, _ in parts if cls})
    if name and len(classes) > 1:
        raise Problem("--name names one file and the run has %d classes: %s"
                      % (len(classes), ", ".join(classes)))
    files = {}
    for cls in classes:
        own = [l for c, part in parts if c in (cls, None) for l in part]
        rows, column = plain_rows(own)
        out = [title,
               mark("Replace the line above with the title: the class, the CPU, the date, and"),
               mark("why the run was made. Then say what dispatched it and at which commit,"),
               mark("and delete these lines.")]
        if len(classes) > 1:
            out += ["#",
                    "# The run held %d classes; this file has %s's blocks and rows of it."
                    % (len(classes), cls)]
        out += ["#"] + header_lines(header) + ["#"]
        out += [mark('Write the reading here ("Reading it ...:" and its bullets), every figure'),
                mark("from the table below or from JMH's table at the end, and delete these"),
                mark("lines."), "#"]
        out += table(rows, column) + ["#"] + RAW_NOTE + ["#"] + own
        files["%s-%s-%s.txt" % (name or function_of(cls), host, date)] = out
    return files


# A pair.

def pair_sides(directory):
    """{side: {(benchmark, parameters): [each measured iteration]}} from pair/*.json."""
    sides, names = {}, set()
    for side in ("base", "head"):
        paths = sorted(glob.glob(os.path.join(directory, "pair", side + "-*.json")))
        if not paths:
            raise Problem("no pair/%s-*.json in %s" % (side, directory))
        rows = sides.setdefault(side, {})
        for path in paths:
            with open(path, encoding="utf-8") as f:
                entries = json.load(f)
            for e in entries:
                metric = e["primaryMetric"]
                if metric["scoreUnit"] != "ns/op":
                    raise Problem("%s is in %s, and the table is in ns/op"
                                  % (e["benchmark"], metric["scoreUnit"]))
                params = e.get("params") or {}
                names.add(",".join(sorted(params)))
                key = (short(e["benchmark"]), ",".join(params[k] for k in sorted(params)))
                rows.setdefault(key, []).extend(x for fork in metric["rawData"] for x in fork)
    return sides, column_name(names) if names else "params"


def pair_tables(directory, header):
    sides, column = pair_sides(directory)
    commits = dict((k, v) for k, v in header)
    out = []
    for side in ("base", "head"):
        rows = sides[side]
        counts = sorted({len(v) for v in rows.values()})
        count = "%d" % counts[0] if len(counts) == 1 else "%d to %d" % (counts[0], counts[-1])
        sha = commits.get(side.capitalize(), "").split(" ")[0][:7]
        out.append("# The %s, %s: minimum, average and maximum of its %s measured iterations."
                   % (side, sha, count))
        out += table(sorted((k[0], k[1], min(v), statistics.mean(v), max(v))
                            for k, v in rows.items()), column)
        out.append("#")
    return out


def pair_files(lines, directory, name):
    while lines and not lines[-1]:
        lines = lines[:-1]
    head = lines[:next((i for i, l in enumerate(lines) if not l.startswith("#")), len(lines))]
    header = fields(head[1:])
    host, date = host_and_date(lines[0], header)
    m = re.match(r"# Pair: (.*), base against head", lines[0])
    classes = sorted({c.rsplit(".", 1)[0] for c in re.findall(r"\b(\w+Benchmark)\b", m.group(1))}
                     ) if m else []
    if not name:
        if len(classes) != 1:
            raise Problem("the pair's benchmarks (%s) name no one class: pass --name"
                          % (m.group(1) if m else lines[0]))
        name = function_of(classes[0]) + "-pair"
    out = ["# %s" % lines[0][2:],
           mark("Replace the line above with the title: what was paired, the CPU, the date,"),
           mark("and the change measured. Then say what dispatched it, what the base and the"),
           mark("head are, and how the run ended; then the reading. Delete these lines."),
           "#"]
    out += pair_tables(directory, header) + [PAIR_NOTE, "#"] + lines
    return {"%s-%s-%s.txt" % (name, host, date): out}


def files_of(directory, name=None):
    with open(os.path.join(directory, "bench-results.txt"), encoding="utf-8") as f:
        lines = ascii_lines(f.read(), "bench-results.txt")
    if not lines or not lines[0].startswith("# "):
        raise Problem("bench-results.txt does not start with a header")
    if lines[0].startswith("# Pair:"):
        return pair_files(lines, directory, name)
    return plain_files(lines, name)


# Checking a file.

def tables_of(lines):
    """The min/avg/max tables of some lines, each a list of (name, parameters, 'min', ...)."""
    out = []
    for line in lines:
        if re.match(r"^# Row\s", line):
            out.append([])
        m = TABLE_ROW.match(line)
        if m and out:
            out[-1].append(m.groups())
    return out


def classes_of(lines):
    return {short(m.group(1)).rsplit(".", 1)[0] for m in map(BENCHMARK.match, lines) if m}


def split_file(lines):
    """A results file as (the lines above the raw section, the raw section). A pair's raw
    section is bench/pair.sh's file, from its title, the last line of that form; a plain
    run's starts at JMH's first line."""
    pair = [i for i, line in enumerate(lines) if re.match(r"# Pair: \S", line)]
    jmh = [i for i, line in enumerate(lines) if line.startswith("# JMH version:")]
    for i in pair[-1:] + jmh[:1]:
        return lines[:i], lines[i:]
    raise Problem("the file has no raw section: no line starts with '# JMH version:' or"
                  " '# Pair:'")


def check(path, directory):
    """The differences between a results file and what the artifact gives, as lines."""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    try:
        text.encode("ascii")
    except UnicodeEncodeError as e:
        return ["the file is not ASCII at character %d: %r" % (e.start, text[e.start])]
    actual = [l.rstrip() for l in text.split("\n")]
    while actual and not actual[-1]:
        actual.pop()
    top, raw = split_file(actual)
    made = list(files_of(directory, name=None if raw[0].startswith("# JMH") else "x").values())
    if len(made) > 1:
        # A run of several classes: the file is checked against the class its raw section has.
        mine = classes_of(raw)
        made = [lines for lines in made if classes_of(lines) == mine]
        if len(made) != 1:
            return ["the file's raw section is of %s, and the artifact has no file of that"
                    % (", ".join(sorted(mine)) or "no benchmark")]
    expected = made[0]
    e_top, e_raw = split_file([l for l in expected if MARKER not in l])
    diffs = ["line %d is a marker still to be replaced: %s" % (i + 1, l)
             for i, l in enumerate(actual) if MARKER in l]
    have = {}
    for key, value in fields(top):
        have.setdefault(key, value)
    for key, value in fields(e_top[1:]):
        if key not in have:
            diffs.append("header: no '%s' line; the artifact gives: %s" % (key, value))
        elif not same_field(key, have[key], value):
            diffs.append("header: %s is '%s'; the artifact gives '%s'" % (key, have[key], value))
    a_tables, e_tables = tables_of(top), tables_of(e_top)
    if len(a_tables) != len(e_tables):
        diffs.append("%d min/avg/max tables; the artifact gives %d"
                     % (len(a_tables), len(e_tables)))
    for n, (a, e) in enumerate(zip(a_tables, e_tables), 1):
        a_rows, e_rows = {r[:2]: r[2:] for r in a}, {r[:2]: r[2:] for r in e}
        for key in sorted(set(a_rows) | set(e_rows)):
            if a_rows.get(key) != e_rows.get(key):
                diffs.append("table %d: %s %s is %s; the artifact gives %s"
                             % (n, key[0], key[1], " ".join(a_rows.get(key, ["absent"])),
                                " ".join(e_rows.get(key, ["no such row"]))))
    if raw != e_raw:
        at = next((i for i, (a, e) in enumerate(zip(raw, e_raw)) if a != e),
                  min(len(raw), len(e_raw)))
        diffs.append("raw section: %d lines against the artifact's %d; they first differ at the"
                     " section's line %d:" % (len(raw), len(e_raw), at + 1))
        diffs.append("  file:     %s" % (raw[at] if at < len(raw) else "(ends)"))
        diffs.append("  artifact: %s" % (e_raw[at] if at < len(e_raw) else "(ends)"))
    return diffs


# The self-test.

def fake_plain(directory):
    def block(cls, method, inputs, scores):
        out = ["# JMH version: 1.37", "# Benchmark: %s%s.%s" % (PACKAGE, cls, method),
               "# Parameters: (inputs = %s)" % inputs, ""]
        out += ["Iteration   %d: %.3f ns/op" % (i + 1, s) for i, s in enumerate(scores)]
        out += ["                 gc.count:           \u2248 0 counts", "", "",
                'Result "%s%s.%s":' % (PACKAGE, cls, method),
                "  %.3f \u00b1(99.9%%) 1.000 ns/op [Average]" % statistics.mean(scores),
                "  (min, avg, max) = (%.3f, %.3f, %.3f), stdev = 1.000"
                % (min(scores), statistics.mean(scores), max(scores)), ""]
        return out

    lines = ["# (Exp|Log1p)Benchmark on a GitHub runner, 2026-11-20 01:02 UTC", "#",
             "# Host:        xeon8573c (expected CPU 'INTEL(R) XEON(R) PLATINUM 8573C')",
             "# Commit:      0123456789abcdef0123456789abcdef01234567 (main)",
             "# CPU flags:   " + " ".join("avx512_flag%02d" % i for i in range(12)) + " ",
             "# Datapath:    34.8 / 69.2 / 94.1 lane-ops/ns at 128 / 256 / 512 bits",
             "#              (bench/datapath.sh, at the machine's own JVM flags)",
             "# Load:        0.56 0.24 0.09 at start (4 cores)",
             "# Command:     mvn -B -Pjmh test-compile exec:exec", "#",
             "[INFO] Scanning for projects..."]
    lines += block("ExpBenchmark", "finish256", "common", [10.04, 12.0, 11.0])
    lines += block("ExpBenchmark", "finish256", "mixed", [20.0, 22.0, 21.0])
    lines += block("Log1pBenchmark", "guarded256", "common", [30.0, 32.0, 31.0])
    lines += ["# Run complete. Total time: 00:00:09", "",
              "Benchmark                    (inputs)  Mode  Cnt   Score   Error  Units",
              "ExpBenchmark.finish256         common  avgt    3  11.013 \u00b1 1.000  ns/op",
              "ExpBenchmark.finish256:gc.count common avgt    3  \u2248 0          counts",
              "ExpBenchmark.finish256          mixed  avgt    3  21.000 \u00b1 1.000  ns/op",
              "Log1pBenchmark.guarded256      common  avgt    3  31.000 \u00b1 1.000  ns/op",
              "[INFO] BUILD SUCCESS", "# Load:        1.11 1.09 0.69 at end", ""]
    with open(os.path.join(directory, "bench-results.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def fake_pair(directory):
    os.mkdir(os.path.join(directory, "pair"))
    for side, scale in (("base", 2.0), ("head", 1.0)):
        for r in (1, 2):
            entries = [{"benchmark": PACKAGE + "ExpBenchmark." + method, "mode": "avgt",
                        "params": {"inputs": inputs},
                        "primaryMetric": {"score": scale * 10, "scoreUnit": "ns/op",
                                          "rawData": [[scale * 10, scale * 12],
                                                      [scale * (8 + r), scale * 11]]}}
                       for method in ("finish256", "blended256")
                       for inputs in ("common", "mixed")
                       if side == "head" or method == "finish256"]
            with open(os.path.join(directory, "pair", "%s-%d.json" % (side, r)), "w") as f:
                json.dump(entries, f)
    lines = ["# Pair: ExpBenchmark, base against head, 2026-11-20 01:02 UTC", "#",
             "# Host:        epyc7763", "# Base:        aaaaaaabbbbbbb (Before)",
             "# Head:        cccccccddddddd (After)",
             "# Load:        0.61 0.35 0.15 at start, 1.18 1.18 1.18 at end (4 cores)", "#",
             " median    best  row", "  +100.0%  +100.0%  ExpBenchmark.finish256", "",
             "ExpBenchmark.finish256  common  avgt  4  10.000 \u00b1 1.000  ns/op", ""]
    with open(os.path.join(directory, "bench-results.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def selftest():
    def written(directory, lines, change=lambda l: l):
        path = os.path.join(directory, "file.txt")
        with open(path, "w", encoding="ascii") as f:
            f.write("\n".join(change(l) for l in lines if MARKER not in l) + "\n")
        return path

    with tempfile.TemporaryDirectory() as d:
        fake_plain(d)
        files = files_of(d)
        assert sorted(files) == ["exp-xeon8573c-2026-11-20.txt",
                                 "log1p-xeon8573c-2026-11-20.txt"], sorted(files)
        exp, log1p = files["exp-xeon8573c-2026-11-20.txt"], files["log1p-xeon8573c-2026-11-20.txt"]
        text = "\n".join(exp)
        # The load at the end joins the load at the start; a long line is continued.
        assert "# Load:        0.56 0.24 0.09 at start, 1.11 1.09 0.69 at end (4 cores)" in exp
        assert "#              avx512_flag06 avx512_flag07" in text, text
        assert "#              (bench/datapath.sh, at the machine's own JVM flags)" in exp
        flags = [l for l in exp if "avx512_flag" in l]
        assert len(flags) == 2 and all(len(l) <= WIDTH for l in flags), flags
        # The table is the Result blocks', one decimal, sorted.
        assert "# ExpBenchmark.finish256  common         10.0         11.0         12.0" in exp
        assert "# ExpBenchmark.finish256  mixed          20.0         21.0         22.0" in exp
        # Maven's lines go, JMH's signs are ASCII, and each class has its own rows only.
        assert not any(MAVEN.match(l) for l in exp) and text.isascii()
        assert "ExpBenchmark.finish256:gc.count common avgt    3  ~ 0          counts" in exp
        assert not any("Log1p" in l for l in exp[8:])
        assert not any("ExpBenchmark." in l for l in log1p)
        assert exp[-1].startswith("ExpBenchmark.finish256          mixed"), exp[-1]
        assert sum(MARKER in l for l in exp) == 6
        # A file as written still has its markers; without them it checks.
        with open(os.path.join(d, "kept.txt"), "w") as f:
            f.write("\n".join(exp) + "\n")
        kept = check(os.path.join(d, "kept.txt"), d)
        assert len(kept) == 6 and all("marker" in x for x in kept), kept
        assert check(written(d, exp), d) == []
        assert check(written(d, log1p), d) == []
        # Another wrapping and a hand-added header line are the same file.
        rewrapped = "\n".join(l for l in exp if MARKER not in l).replace(
            "\n#              avx512_flag06", " avx512_flag06").replace(
            "# Command:", "# Array:       1024 doubles\n# Command:").split("\n")
        assert check(written(d, rewrapped), d) == []
        # A changed figure is found, wherever it is.
        for old, new, where in (("         10.0  ", "         10.1  ", "table 1"),
                                ("0.56 0.24", "0.57 0.24", "header: Load"),
                                ("Iteration   2: 12.000", "Iteration   2: 12.001", "raw section"),
                                ("0123456789abcdef", "1123456789abcdef", "header: Commit")):
            found = check(written(d, exp, lambda l: l.replace(old, new)), d)
            assert len([x for x in found if not x.startswith("  ")]) == 1 \
                and found[0].startswith(where), (old, found)
        missing = check(written(d, [l for l in exp if not l.startswith("# Commit:")]), d)
        assert missing and missing[0].startswith("header: no 'Commit' line"), missing
        try:
            plain_files(ascii_lines(open(os.path.join(d, "bench-results.txt"),
                                         encoding="utf-8").read(), "x"), "one-name")
            raise AssertionError("one name for two classes")
        except Problem:
            pass
    with tempfile.TemporaryDirectory() as d:
        fake_pair(d)
        files = files_of(d)
        assert list(files) == ["exp-pair-epyc7763-2026-11-20.txt"], list(files)
        pair = files["exp-pair-epyc7763-2026-11-20.txt"]
        text = "\n".join(pair)
        # Each side's table is over every measured iteration of its rounds: 2 rounds of 2 x 2.
        assert "# The base, aaaaaaa: minimum, average and maximum of its 8 measured" \
               " iterations." in pair, text
        assert "# ExpBenchmark.finish256  common         18.0         21.2         24.0" in pair
        assert "# ExpBenchmark.finish256   common          9.0         10.6         12.0" in pair
        assert "# ExpBenchmark.blended256  mixed           9.0         10.6         12.0" in pair
        assert len(tables_of(pair)) == 2 and [len(t) for t in tables_of(pair)] == [2, 4]
        # bench/pair.sh's file follows whole, its signs in ASCII.
        at = pair.index(PAIR_NOTE)
        assert pair[at + 2] == "# Pair: ExpBenchmark, base against head, 2026-11-20 01:02 UTC"
        assert pair[-1] == "ExpBenchmark.finish256  common  avgt  4  10.000 +/- 1.000  ns/op"
        assert check(written(d, pair), d) == []
        found = check(written(d, pair, lambda l: l.replace("  21.2  ", "  21.3  ")), d)
        # Both of the base's rows hold that average.
        assert len(found) == 2 and all(x.startswith("table 1") for x in found), found
        found = check(written(d, pair, lambda l: l.replace("+100.0%  +", "+100.1%  +")), d)
        assert found and found[0].startswith("raw section"), found
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write("# X on a GitHub runner, 2026-11-20 01:02 UTC\n#\n[ERROR] no build\n")
        try:
            files_of(d)
            raise AssertionError("an artifact with no JMH output")
        except Problem:
            pass
    print("bench/record.py: self-test passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--selftest", action="store_true")
    sub = parser.add_subparsers(dest="command")
    for command in ("write", "check"):
        p = sub.add_parser(command)
        if command == "check":
            p.add_argument("file", help="the results file to check")
        p.add_argument("artifact", nargs="?", help="the downloaded artifact's directory")
        p.add_argument("--run", help="the run to download the artifact of, with gh")
        p.add_argument("--repo", help="the repository of --run, if not the checkout's")
        if command == "write":
            p.add_argument("--out-dir", default="bench/results")
            p.add_argument("--name", help="the file's <function> part, such as exp-onediv")
            p.add_argument("--force", action="store_true", help="overwrite an existing file")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    if not args.command:
        parser.error("write, check or --selftest")
    if bool(args.artifact) == bool(args.run):
        parser.error("give the artifact's directory or --run, one of them")
    directory = find_artifact(args.artifact or download(args.run, args.repo))
    if args.command == "check":
        diffs = check(args.file, directory)
        for line in diffs:
            print(line)
        print("%s: %s" % (args.file, "differs from the artifact" if diffs
                          else "its header, tables and raw section are the artifact's"))
        return 1 if diffs else 0
    for name, lines in files_of(directory, args.name).items():
        path = os.path.join(args.out_dir, name)
        if os.path.exists(path) and not args.force:
            raise Problem("%s exists; pass --force to overwrite it, or --name" % path)
        with open(path, "w", encoding="ascii", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print("wrote %s: %d lines, %d of them markers to replace by hand"
              % (path, len(lines), sum(MARKER in l for l in lines)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Problem as e:
        print("bench/record.py: error: %s" % e, file=sys.stderr)
        sys.exit(2)
