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
    bench/record.py write --run <id> --run <id of the re-run>    # one file, both runs
    bench/record.py check <results file> <artifact dir>  # or: check <results file> --run <id>
    bench/record.py --selftest

`write` gives one file per benchmark class of a plain run, <function>-<host>-<date>.txt, each
with its own class's blocks and rows only, and one file for a pair, <function>-pair-<host>-
<date>.txt; --name gives the part before <host> (exp-onediv, for a pair in place of
<function>-pair). It never overwrites a file without --force.
What it writes:

- the workflow's header lines, "Host" to "Command", with the load at the end of the run moved up
  beside the load at the start, and a line longer than 100 columns continued on the next;
- the table of each row's minimum, average and maximum, to one decimal with a tie to the even
  digit: for a plain run from the "Result" blocks of JMH's output, for a pair from every
  measured iteration in pair/<side>-<round>.json, one table a side under a line that names the
  side's commit and its count of iterations;
- the raw section: for a plain run, the artifact from its first JMH line with Maven's lines
  dropped; for a pair, bench/pair.sh's results file whole. JMH's plus-minus and approximately
  signs and its powers of ten are written in ASCII (+/-, ~, 10^-4), and anything else that is
  not ASCII in what is kept is an error.

The hand-written parts are left as lines that hold MARKER, each saying what belongs there.

Several plain runs of one host, a run and its re-run (AGENTS.md asks for one when a claim rests
on a ratio under about 1.3x), go in one file: the first run's file as above, then for each
later run that has the file's class a line "# Run N of M of this file: ..." and that run's
header, table and raw section. The file's name has the first run's date. Runs of two hosts or
CPUs are refused, since a file is of one CPU, and so are runs with other JMH options or vector
settings; so is a pair among them, whose re-run is a file of its own. A class that a later run
lacks, or that the first run lacks, is named in a note.

A later run at another commit than the first run's joins the file only if the JDK and the
command are the same and so is the code under the file's benchmark, but for comments and
whitespace. Otherwise it is refused: another JDK or command refuses the write; other code leaves
the run out of that class's file, in a note that names the files that differ (it is a first run
of new code, a file of its own), and check refuses a file that holds such a run.
The code under a benchmark class is its file in src/jmh/java and, transitively, every file of
src/main/java, src/jmh/java and src/test/java that declares at its top level a type whose name
is a word of that code (comments dropped, literals emptied), taken at both commits: the files
must be the same set, each the same once its comments are dropped and its whitespace is
dropped where no two tokens could join across it, literals kept verbatim. A \\u escape that
could open or close a comment or a literal is refused, not decoded. pom.xml must be the same as
XML but for the project's own version, project.build.outputTimestamp, the executions whose id
starts with fault- and the profile release. src/main/resources, src/jmh/resources, every file
under src/main/java and src/jmh/java that is not Java source, and module-info.java and
package-info.java there, must be the same too, and src/test/resources if a test class is under
the benchmark. git reads the files at both commits, in this script's clone, which must hold
both commits; one it lacks is fetched from origin (a network call), and if that fails the runs
are refused. A line "# Code:"
under the run's line says which commits and files were compared, and check computes it again.

`check` reads a results file's header lines, tables and raw section and compares them with what
`write` makes from the artifact, and fails if they differ or if a marker line is still there.
A file of several runs is checked against all of them, given in the file's order.
The table's figures are JMH's printed values (three decimals, itself rounded) rounded to one
decimal, a tie to the even digit; a pair's, from the JSON's full values, so the same
measurement may differ by 0.1 between the two kinds of file. A run's parameters are in the
order of its JMH lines in a plain file and sorted in a pair's.

`check` compares values, not layout: a header line may be wrapped another way, a table's
columns may be wider, and the file may hold header lines the workflow did not write (such as
"Array"). Exit status 1 lists the differences; 2 is an error (no artifact, no result in it, a
file that cannot be read or written).
"""

import argparse
import decimal
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
FIELD = re.compile(r"^# ([A-Z][A-Za-z0-9-]*(?: [a-z]+)?):(?: +(\S.*?))?\s*$")
CONTINUED = re.compile(r"^# {2,}(\S.*?)\s*$")
MAVEN = re.compile(r"^\[(INFO|WARNING|ERROR)\]")
TABLE_ROW = re.compile(r"^# (\S+\.\S+)\s+(\S.*?)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)$")
SIDE_LINE = re.compile(r"^# The (base|head), \S*: minimum, average and maximum of its .*"
                       r" measured iterations\.$")
LOAD = re.compile(r"(\d+\.\d+ \d+\.\d+ \d+\.\d+) at (start|end)")
CORES = re.compile(r" ?\(\d+ cores\)")
PAIR_TITLE = re.compile(r"^# Pair: \S.*, base against head, \d{4}-\d{2}-\d{2} ")
NAME_END = re.compile(r"-[^-]+-\d{4}-\d{2}-\d{2}\.txt$")
RUN_LINE = "# Run %d of %d of this file: its title, header, table and raw output follow."
RUN_LINE_RE = re.compile(r"^# Run \d+ of \d+ of this file: ")
TABLE_HEAD = re.compile(r"^# Row\s+\S+\s+min\s+avg\s+max\s+\(ns/op\)$")
RAW_NOTE = ["# Raw output of the run follows: the workflow's artifact from its first JMH line,"
            " with Maven's",
            "# lines dropped and JMH's plus-minus and approximately signs and powers of ten in"
            " ASCII."]
PAIR_NOTE = "# bench/pair.sh's results file follows."


# What write did not do and the user should know, printed after it wrote.
NOTES = []


class Problem(Exception):
    """The artifact or the file cannot be read as what it should be: exit status 2."""


SUPERSCRIPTS = {0x207b: "-", 0x2070: "0", 0xb9: "1", 0xb2: "2", 0xb3: "3", 0x2074: "4",
                0x2075: "5", 0x2076: "6", 0x2077: "7", 0x2078: "8", 0x2079: "9"}
POWER = re.compile("10([%s]+)" % "".join(map(chr, SUPERSCRIPTS)))


def ascii_lines(text):
    """The text's lines with JMH's signs in ASCII: plus-minus as +/-, approximately as ~, and
    the power of ten it prints for a value under 0.0005 (10 and a superscript) as 10^-4."""
    text = text.replace("\u00b1", "+/-").replace("\u2248", "~")
    text = POWER.sub(lambda m: "10^" + m.group(1).translate(SUPERSCRIPTS), text)
    return [line.rstrip() for line in text.split("\n")]


def require_ascii(lines, what):
    """Only what is written must be ASCII: Maven's lines, which are dropped, may hold more."""
    for line in lines:
        if not line.isascii():
            raise Problem("%s holds a non-ASCII character other than JMH's signs: %s"
                          % (what, ascii(line)))


def find_artifact(path):
    """The directory that holds bench-results.txt: path itself, or the directory gh run
    download makes under it for the artifact."""
    for d in (path, os.path.join(path, "bench-results")):
        if os.path.isfile(os.path.join(d, "bench-results.txt")):
            return d
    raise Problem("no bench-results.txt in %s or in its bench-results/" % path)


def download(run, repo, d):
    cmd = ["gh", "run", "download", str(run), "--name", "bench-results", "--dir", d]
    try:
        done = subprocess.run(cmd + (["--repo", repo] if repo else []), capture_output=True,
                              text=True)
    except OSError as e:
        raise Problem("--run needs the gh command (%s); download the artifact by hand and give"
                      " its directory instead" % e)
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
            open_field = [m.group(1), m.group(2) or ""]
            out.append(open_field)
            continue
        m = CONTINUED.match(line)
        if m and open_field:
            open_field[1] += "\n" + m.group(1)
        else:
            open_field = None
    return out


def header_block(lines):
    """The lines of the workflow's header: from "# Host:" to the bare "#" that ends it, a
    hand-added line of any form included. A sentence elsewhere that starts like a field is
    not in it."""
    start = next((i for i, l in enumerate(lines) if l.startswith("# Host:")), len(lines))
    end = next((i for i in range(start, len(lines)) if lines[i] == "#"), len(lines))
    return lines[start:end]


def same_field(key, a, b):
    a, b = " ".join(a.split()), " ".join(b.split())
    if key == "Load" and not CORES.search(a):
        # The files written before this script name no core count; one that is there counts.
        b = CORES.sub("", b).strip()
    return a == b


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
            out.append(line.rstrip())
            line = "#" + " " * 13
    return out


def tenth(x):
    """A figure to one decimal, a tie going to the even digit: from JMH's three decimals as
    printed (a string) or from a float of its JSON, never through a float's binary tie."""
    exact = decimal.Decimal(x if isinstance(x, str) else repr(x))
    return str(exact.quantize(decimal.Decimal("0.1"), rounding=decimal.ROUND_HALF_EVEN))


def table(rows, column):
    """rows: (benchmark, parameters, minimum, average, maximum), sorted. A row with no
    parameter has "-" there, so that every row has five fields."""
    rows = [(r[0], r[1] or "-") + tuple(tenth(x) for x in r[2:]) for r in rows]
    w = max(len(r[0]) for r in rows) + 1
    p = max([len(column)] + [len(r[1]) for r in rows])
    out = ["# %-*s %-*s %12s %12s %12s   (ns/op)" % (w, "Row", p, column, "min", "avg", "max")]
    for name, params, lo, avg, hi in rows:
        out.append("# %-*s %-*s %12s %12s %12s" % (w, name, p, params, lo, avg, hi))
    return out


def column_name(names):
    return names.pop() if len(names) == 1 else "params"


# A plain run.

BENCHMARK = re.compile(r"^# Benchmark: (\S+)$")
PARAMETERS = re.compile(r"^# Parameters: \((.*)\)$")
RESULT = re.compile(r'^Result "(\S+)":$')
MIN_AVG_MAX = re.compile(r"^\s*\(min, avg, max\) = \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)")
# The error may be "~ 0" or "~ 10^-4": JMH's approximately sign and a value, two words.
UNIT = re.compile(r"^\s*\S+ \+/-\(99\.9%\) (?:~ )?\S+ (\S+) \[Average\]$")
SUMMARY_ROW = re.compile(r"^(\w+)\.\w+(:\S+)?\s")


def short(benchmark):
    """Class.method, as JMH's summary table and bench/pair.py name a benchmark."""
    return ".".join(benchmark.split(".")[-2:])


def plain_raw(lines):
    """JMH's output: from its first line on, without Maven's lines and the workflow's last."""
    start = next((i for i, l in enumerate(lines) if l.startswith("# JMH version:")), None)
    if start is None:
        raise Problem("no JMH output in bench-results.txt: did the run fail before JMH?")
    raw = [l for l in lines[start:] if not MAVEN.match(l) and not l.startswith("# Load:")]
    while raw and not raw[-1]:
        raw.pop()
    # A run that was cancelled or crashed has its finished rows and no summary table, and
    # would read as a results file of fewer rows.
    if not any(l.startswith("# Run complete.") for l in raw):
        raise Problem("the run did not finish (JMH printed no '# Run complete' line): it is"
                      " not a results file")
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


def plain_rows(raw, cls):
    """(benchmark, parameters, min, avg, max) from each Result block, and the column's name."""
    rows, names, params, owed = [], set(), "", None
    for i, line in enumerate(raw):
        m = BENCHMARK.match(line)
        if m or line.startswith("# Run complete."):
            # JMH goes on to the next benchmark when one fails, and still ends the run.
            if owed:
                raise Problem("%s has no Result block: it failed in the run, and the file"
                              " would lack its row" % owed)
            owed = m and short(m.group(1))
        m = PARAMETERS.match(line)
        if m:
            pairs = [p.partition(" = ") for p in m.group(1).split(", ")]
            if not all(sep for _, sep, _ in pairs):
                raise Problem("a parameter value holds ', ' and cannot be told from two"
                              " parameters: %s" % line)
            names.add(",".join(k for k, _, _ in pairs))
            params = ",".join(v for _, _, v in pairs)
        m = RESULT.match(line)
        if m:
            following = raw[i + 1:i + 3] + ["", ""]
            unit, n = UNIT.match(following[0]), MIN_AVG_MAX.match(following[1])
            if not unit or not n:
                raise Problem("the Result block of %s is not JMH's three lines" % m.group(1))
            if unit.group(1) != "ns/op":
                raise Problem("%s is in %s, and the table is in ns/op"
                              % (m.group(1), unit.group(1)))
            rows.append((short(m.group(1)), params) + tuple(n.group(j) for j in (1, 2, 3)))
            owed = None
        if line.startswith("# JMH version:"):
            params = ""
    if owed or not rows:
        raise Problem("no Result block of %s in JMH's output" % (owed or cls))
    return sorted(rows), column_name(names) if names else "params"


def plain_header(lines):
    """The workflow's header fields, the end's load beside the start's."""
    head = lines[:next((i for i, l in enumerate(lines) if not l.startswith("#")), len(lines))]
    for line in head[1:]:
        if line != "#" and not FIELD.match(line) and not CONTINUED.match(line):
            raise Problem("a header line of the workflow that this script does not know, and"
                          " would drop: %s" % line)
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


def host_and_date(title, header, named=True):
    host = dict((k, v) for k, v in header).get("Host", "").split()
    m = re.search(r"(\d{4}-\d{2}-\d{2}) \d{2}:\d{2} UTC", title)
    if not named and (not host or not m):
        return "unknown", "unknown"
    if not host or not m:
        raise Problem("the artifact's header has no Host line or its title no date: the file's"
                      " name is <function>-<host>-<date>.txt, of the run's host and date")
    if host[0] == "local":
        raise Problem("a local run is not a host's: its file is named by hand, laptop with a"
                      " suffix for its flags (AGENTS.md)")
    return host[0], m.group(1)


def mark(text):
    return "# %s %s" % (MARKER, text)


def plain_files(lines, name, named=True):
    """{file name: lines} for a plain run, one file per benchmark class."""
    title, header = lines[0], plain_header(lines)
    host, date = host_and_date(title, header, named)
    raw = plain_raw(lines)
    parts = plain_parts(raw)
    classes = sorted({cls for cls, _ in parts if cls})
    if name and len(classes) > 1:
        raise Problem("--name names one file and the run has %d classes: %s"
                      % (len(classes), ", ".join(classes)))
    files = {}
    for cls in classes:
        own = [l for c, part in parts if c in (cls, None) for l in part]
        while own and not own[-1]:
            own.pop()
        rows, column = plain_rows(own, cls)
        out = [title,
               mark("Replace the line above with the title: the class, the CPU, the date, and"),
               mark("why the run was made. Then say what dispatched it and at which commit,"),
               mark("and add an 'Array:' line if the benchmark's inputs need describing (see"),
               mark("the other files). Delete these lines.")]
        if len(classes) > 1:
            out += ["#",
                    "# The run held %d classes; this file has %s's blocks and rows of it."
                    % (len(classes), cls)]
        out += ["#"] + header_lines(header) + ["#"]
        out += [mark('Write the reading here ("Reading it ...:" and its bullets), every figure'),
                mark("from the table below or from JMH's table at the end, and delete these"),
                mark("lines."), "#"]
        out += table(rows, column) + ["#"] + RAW_NOTE + ["#"] + own
        file_name = "%s-%s-%s.txt" % (name or function_of(cls), host, date)
        if file_name in files:
            raise Problem("two classes of the run give one file name, %s" % file_name)
        files[file_name] = out
    return files


# A pair.

def pair_sides(directory):
    """{side: {(benchmark, parameters): [each measured iteration]}} from pair/*.json."""
    sides, names, modes = {}, set(), set()
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
                if params:
                    names.add(",".join(sorted(params)))
                modes.add(e.get("mode"))
                if len(modes) > 1 or "rawData" not in metric:
                    # Two modes of one method are two rows (bench/pair.py), and a mode that
                    # keeps no iterations has no minimum to give.
                    raise Problem("%s: the table takes one benchmark mode with its measured"
                                  " iterations, and %s has %s" % (
                                      path, e["benchmark"],
                                      "no rawData" if "rawData" not in metric else
                                      "another mode than the rows before it"))
                key = (short(e["benchmark"]), ",".join(params[k] for k in sorted(params)))
                rows.setdefault(key, []).extend(x for fork in metric["rawData"] for x in fork)
    return sides, column_name(names) if names else "params"


def pair_tables(directory, header):
    sides, column = pair_sides(directory)
    commits = dict((k, v) for k, v in header)
    out = []
    for side in ("base", "head"):
        rows = sides[side]
        if not rows:
            raise Problem("the %s's pair/%s-*.json hold no benchmark" % (side, side))
        counts = sorted({len(v) for v in rows.values()})
        count = "%d" % counts[0] if len(counts) == 1 else "%d to %d" % (counts[0], counts[-1])
        sha = commits.get(side.capitalize(), "").split(" ")[0][:7]
        out.append("# The %s, %s: minimum, average and maximum of its %s measured iterations."
                   % (side, sha, count))
        out += table(sorted((k[0], k[1], min(v), statistics.mean(v), max(v))
                            for k, v in rows.items()), column)
        out.append("#")
    return out


def pair_files(lines, directory, name, named=True):
    while lines and not lines[-1]:
        lines = lines[:-1]
    # A commit's subject is the author's text: what is not ASCII in it is written escaped.
    lines = [l.encode("ascii", "backslashreplace").decode("ascii")
             if re.match(r"# (Base|Head): ", l) else l for l in lines]
    head = lines[:next((i for i, l in enumerate(lines) if not l.startswith("#")), len(lines))]
    header = fields(head[1:])
    host, date = host_and_date(lines[0], header, named)
    # The classes that ran, from the rows of the rounds' JSON, not from the regex's text.
    classes = sorted({k[0].split(".")[0] for side in pair_sides(directory)[0].values()
                      for k in side})
    if not name:
        if len(classes) != 1:
            raise Problem("the pair ran %s: not one class, so pass --name"
                          % (", ".join(classes) or "no benchmark"))
        name = function_of(classes[0]) + "-pair"
    out = ["# %s" % lines[0][2:],
           mark("Replace the line above with the title: what was paired, the CPU, the date,"),
           mark("and the change measured. Then say what dispatched it, what the base and the"),
           mark("head are, and how the run ended; then the reading. Delete these lines."),
           "#"]
    out += pair_tables(directory, header) + [PAIR_NOTE, "#"] + lines
    return {"%s-%s-%s.txt" % (name, host, date): out}


def artifact_lines(directory):
    """bench-results.txt's lines and whether it is a pair's, which its title says. A byte that
    is not UTF-8 (bench/pair.sh cuts a commit's subject by bytes) reads as U+FFFD."""
    path = os.path.join(directory, "bench-results.txt")
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = ascii_lines(f.read())
    if not lines or not lines[0].startswith("# "):
        raise Problem("bench-results.txt does not start with a header")
    return lines, bool(PAIR_TITLE.match(lines[0]))


def files_of(directory, name=None, named=True):
    lines, pair = artifact_lines(directory)
    files = (pair_files(lines, directory, name, named) if pair
             else plain_files(lines, name, named))
    for file_name, written in files.items():
        require_ascii(written, "the artifact, in what goes to " + file_name)
    return files


def joined_files(directories, name=None):
    """{file name: lines} for one run, or for several plain runs of one host: the first run's
    files, each followed by every later run's header, table and raw output of that class
    under a RUN_LINE. A file holds one CPU, so the runs' Host and CPU lines must agree."""
    first = files_of(directories[0], name)
    if len(directories) == 1:
        return first
    seen = []
    for d in directories:
        lines, pair = artifact_lines(d)
        if pair:
            raise Problem("several runs go in one file only if each is a plain run, and %s is"
                          " a pair's" % d)
        header = dict((k, v) for k, v in plain_header(lines))
        # The host's key, and the CPU as the workflow's check reads it, ignoring case.
        # What makes it one measurement again: the host's key and CPU (case aside), the JMH
        # options and the JVM's vector settings; and the run itself differs. At another commit
        # than the first run's, the JDK too, and the code (code_note).
        seen.append(((header.get("Host") or "?").split()[0], (header.get("CPU") or "").lower(),
                     " ".join((header.get("JMH") or "").split()), header.get("Vector"),
                     header.get("Run") or lines[0],  # an older run: its title, with its time
                     (header.get("Commit") or "").split(" ")[0],
                     (" ".join((header.get("JDK") or "").split()),
                      " ".join((header.get("Command") or "").split()))))
    runs = [x[4] for x in seen]
    if len({x[:4] for x in seen}) > 1 or len(set(runs)) < len(runs) or any(
            x[5] != seen[0][5] and x[6] != seen[0][6] for x in seen):
        raise Problem("several runs go in one file only if they are different runs of one host"
                      " and CPU, with the same JMH options and vector settings, and, at"
                      " different commits, the same JDK and command; these are %s"
                      % "; ".join(" ".join(map(str, x)) for x in seen))
    commits = [x[5] for x in seen]
    # A later run joins the file of its class, whatever the files are named; a class that a
    # run lacks, or that only a later run has, is said and never joined to another's.
    blocks = {file_name: [] for file_name in first}
    of_class = {frozenset(classes_of(lines)): file_name for file_name, lines in first.items()}
    for n, d in enumerate(directories[1:], 2):
        # The name is the first run's: it names no file of a later run.
        later = {frozenset(classes_of(lines)): lines for lines in files_of(d).values()}
        for cls in sorted(set(of_class) - set(later), key=sorted):
            NOTES.append("run %d has no %s: %s does not hold that run"
                         % (n, ", ".join(sorted(cls)), of_class[cls]))
        for cls in sorted(set(later) - set(of_class), key=sorted):
            NOTES.append("run %d's %s is in no file, since the first run has none: write that"
                         " run by itself" % (n, ", ".join(sorted(cls))))
        if not set(later) & set(of_class):
            raise Problem("run %d has none of the first run's classes (%s), so it joins no file"
                          % (n, "; ".join(", ".join(sorted(c)) for c in of_class)))
        for cls, block in later.items():
            if cls in of_class:
                # The later run's lines from its header on, without the places for
                # hand-written text, which the file has once, above.
                title = block[0]
                block = [l for l in block[block.index(header_block(block)[0]):]
                         if MARKER not in l]
                # The note numbers the run by its place in this file, as RUN_LINE does.
                try:
                    note = code_note(len(blocks[of_class[cls]]) + 2, commits[0],
                                     commits[n - 1], cls)
                except CodeDiffers as e:
                    NOTES.append("run %d is not in %s: %s" % (n, of_class[cls], e))
                    continue
                blocks[of_class[cls]].append(note + [title, "#"] + [
                    l for i, l in enumerate(block) if not (l == "#" and block[i - 1] == "#")])
    out = {}
    for file_name, lines in first.items():
        out[file_name] = list(lines)
        for k, block in enumerate(blocks[file_name], 2):
            out[file_name] += ["#", RUN_LINE % (k, 1 + len(blocks[file_name]))] + block
    return out


# The code at two commits: a run and its re-run at different commits share a file only if the
# code under the benchmark is the same but for comments and whitespace.

# The clone whose git holds the runs' commits: this script's checkout (the self-test's own).
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The source roots a benchmark's closure is taken from, and the files that must be the same.
ROOTS = ("src/main/java", "src/jmh/java", "src/test/java")
POM_NS = "{http://maven.apache.org/POM/4.0.0}"
# A character that, written as a \u escape, could open or close a comment or a literal.
HIDING = set("/*\"'\\\n\r{}")
UNICODE = re.compile(r"\\+u+([0-9A-Fa-f]{4})")
# Characters that join with a neighbour of their kind into another token, so that a space
# between two of them is kept: words, and the characters of Java's operators.
OPERATOR = set("+-*/%=<>!&|^~:.?")
DECLARATION = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
IDENTIFIER = re.compile(r"[A-Za-z_$][\w$]*")
CODE_RULE = ("{seed} and every type it names in %s and %s, transitively, without comments and"
             " whitespace, and pom.xml without the project's version,"
             " project.build.outputTimestamp, the fault-* executions and the release profile"
             % (", ".join(ROOTS[:-1]), ROOTS[-1]))


def char_kind(c):
    if c.isalnum() or c in "_$":
        return "word"
    return "operator" if c in OPERATOR else "other"


def java_code(text, what):
    """A Java source without its comments, as two strings: the code with each run of
    whitespace dropped where no token could join across it (a space kept between two word
    characters and between two operator characters), the literals verbatim; and the same with
    each literal emptied, which names the types the code uses. A \\u escape that could hide a
    comment or a literal's end is refused, since Java reads it before it reads either."""
    for m in UNICODE.finditer(text):
        slashes = len(m.group(0)) - len(m.group(0).lstrip("\\"))
        if slashes % 2 == 1 and chr(int(m.group(1), 16)) in HIDING:
            raise Problem("%s: the escape %s is a character that may open or close a comment"
                          " or a literal; bench/record.py does not decode escapes, and refuses"
                          % (what, m.group(0)))
    pieces, i, n = [], 0, len(text)  # (kind, text): "code" or "literal"

    def ends(start, close):
        # The index past a literal's closing quote, a backslash escaping the next character.
        j = start
        while j < n:
            if text[j] == "\\":
                j += 2
            elif text.startswith(close, j):
                return j + len(close)
            elif close != '"""' and text[j] in "\n\r":
                break
            else:
                j += 1
        raise Problem("%s: a literal opened at character %d is not closed" % (what, start))

    while i < n:
        if text.startswith("//", i):
            # Java ends a line at CR, LF or both.
            i = min([k for k in (text.find("\n", i), text.find("\r", i)) if k >= 0] or [n])
            pieces.append(("code", " "))
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                raise Problem("%s: a comment opened at character %d is not closed" % (what, i))
            i = j + 2
            pieces.append(("code", " "))
        elif text.startswith('"""', i):
            j = ends(i + 3, '"""')
            pieces.append(("literal", text[i:j]))
            i = j
        elif text[i] in "\"'":
            j = ends(i + 1, text[i])
            pieces.append(("literal", text[i:j]))
            i = j
        else:
            pieces.append(("code", text[i]))
            i += 1
    out, names, last, space = [], [], "", False
    for kind, piece in pieces:
        if kind == "code" and piece.isspace():
            space = True
            continue
        first = piece[0] if kind == "code" else '"'
        if space and last and char_kind(last) == char_kind(first) != "other":
            out.append(" ")
            names.append(" ")
        out.append(piece)
        names.append(piece if kind == "code" else ' "" ')
        last, space = (piece[-1] if kind == "code" else '"'), False
    return "".join(out), "".join(names)


def top_level_types(names):
    """The types a source declares at its top level, from its code with the literals emptied:
    a nested type is named through its outer type, which brings that type's file in."""
    depth, found = 0, []
    for m in re.finditer(r"[{}]|" + DECLARATION.pattern, names):
        if m.group(0) == "{":
            depth += 1
        elif m.group(0) == "}":
            depth -= 1
        elif depth == 0:
            found.append(m.group(1))
    return found


def git(*args, check=True):
    done = subprocess.run(["git", "-C", REPO] + list(args), capture_output=True)
    if check and done.returncode != 0:
        raise Problem("git %s failed: %s" % (" ".join(args),
                                             done.stderr.decode("utf-8", "replace").strip()))
    return done


def have_commit(sha):
    """The commit is in the clone, fetched from origin if it is not."""
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha or ""):
        raise Problem("a run's Commit line names no commit: '%s'" % sha)
    if git("cat-file", "-e", sha + "^{commit}", check=False).returncode != 0:
        git("fetch", "--quiet", "origin", sha, check=False)
        if git("cat-file", "-e", sha + "^{commit}", check=False).returncode != 0:
            raise Problem("commit %s is not in the clone at %s, nor can it be fetched from"
                          " origin: the code of the runs cannot be compared" % (sha, REPO))


RESOURCES = ("src/main/resources", "src/jmh/resources", "src/test/resources")


def tree(sha):
    """{path: bytes} of the files under ROOTS and RESOURCES, and pom.xml, at a commit."""
    entries = [e for e in git("ls-tree", "-r", "-z", sha, "--", *ROOTS, *RESOURCES, "pom.xml"
                              ).stdout.decode("utf-8").split("\0") if e]
    paths = []
    for entry in entries:
        meta, _, path = entry.partition("\t")
        if meta.split()[1] != "blob":
            raise Problem("%s at %s is a %s, not a file: bench/record.py compares files only"
                          % (path, sha[:7], meta.split()[1]))
        paths.append(path)
    done = subprocess.run(["git", "-C", REPO, "cat-file", "--batch"], capture_output=True,
                          input="".join("%s:%s\n" % (sha, p) for p in paths).encode("utf-8"))
    if done.returncode != 0:
        raise Problem("git cat-file --batch failed at %s: %s"
                      % (sha[:7], done.stderr.decode("utf-8", "replace").strip()))
    out, data = {}, done.stdout
    for path in paths:
        head, _, data = data.partition(b"\n")
        parts = head.split()
        if len(parts) != 3 or parts[1] != b"blob":
            raise Problem("git cat-file gave '%s' for %s at %s"
                          % (head.decode("utf-8", "replace"), path, sha[:7]))
        size = int(parts[2])
        out[path], data = data[:size], data[size + 1:]
    return out


def pom_shape(data, what):
    """pom.xml without what no benchmark runs: the project's own version, the property
    project.build.outputTimestamp, each execution whose id starts with fault- and the profile
    release, read as XML (its comments dropped) and written canonically without whitespace."""
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(data)
    except ET.ParseError as e:
        raise Problem("%s is not XML: %s" % (what, e))
    for parent in list(root.iter()):
        for child in list(parent):
            tag = child.tag.replace(POM_NS, "")
            ident = (child.findtext(POM_NS + "id") or "").strip()
            if ((parent is root and tag == "version")
                    or (parent.tag == POM_NS + "properties"
                        and tag == "project.build.outputTimestamp")
                    or (tag == "execution" and ident.startswith("fault-"))
                    or (tag == "profile" and ident == "release")):
                parent.remove(child)
    return ET.canonicalize(ET.tostring(root, encoding="unicode"), strip_text=True)


CODE_CACHE = {}


class CodeDiffers(Problem):
    """The code under a benchmark is not the same at two commits."""


def always_compared(path):
    """A file compared whatever the closure: a module's or a package's declarations, and
    anything but Java source under src/main/java or src/jmh/java, which the build may copy."""
    if path.startswith(("src/main/java/", "src/jmh/java/")):
        return not path.endswith(".java") or os.path.basename(path) in (
            "module-info.java", "package-info.java")
    return path.startswith(("src/main/resources/", "src/jmh/resources/"))


def code_compared(first, later, classes):
    """The files under the benchmark classes compared between two commits, sorted: their
    closures (each class's src/jmh/java file and every type its code names, transitively) at
    both commits, pom.xml, and the resources the closure could read. Refuses, naming them, if
    any differs but for comments and whitespace (pom.xml: but for pom_shape's parts)."""
    key = (first, later, tuple(sorted(classes)))
    if key in CODE_CACHE:
        return CODE_CACHE[key]
    trees, code, closure = {}, {}, set()
    for sha in (first, later):
        have_commit(sha)
        trees[sha] = tree(sha)
        declares = {}
        unread = {}
        for path, data in trees[sha].items():
            if path.endswith(".java") and path.startswith(ROOTS):
                what = "%s at %s" % (path, sha[:7])
                try:
                    code[sha, path] = java_code(data.decode("utf-8"), what)
                    names = top_level_types(code[sha, path][1])
                except (Problem, UnicodeDecodeError) as e:
                    # A file this script cannot read is refused only if the closure needs it;
                    # until then it declares every name it holds, so that it is not missed.
                    unread[path] = ("%s is not UTF-8" % what) if isinstance(
                        e, UnicodeDecodeError) else str(e)
                    names = DECLARATION.findall(data.decode("utf-8", "replace"))
                for name in names:
                    declares.setdefault(name, set()).add(path)
        todo = set()
        for cls in classes:
            seeds = {p for p in declares.get(cls, ()) if p.startswith("src/jmh/java/")}
            if not seeds:
                raise Problem("no file of src/jmh/java declares %s at %s" % (cls, sha[:7]))
            todo |= seeds
        mine = set()
        while todo:
            path = todo.pop()
            if path in unread:
                raise Problem(unread[path])
            mine.add(path)
            for name in set(IDENTIFIER.findall(code[sha, path][1])):
                todo |= declares.get(name, set()) - mine
        closure |= mine
    compared = sorted(closure) + ["pom.xml"]
    compared += sorted({p for t in trees.values() for p in t if always_compared(p)
                        or (p.startswith("src/test/resources/")
                            and any(c.startswith("src/test/") for c in closure))} - closure)
    differ = []
    for path in compared:
        a, b = (trees[sha].get(path) for sha in (first, later))
        if a is None or b is None:
            differ.append("%s (only at %s)" % (path, (first if b is None else later)[:7]))
        elif path == "pom.xml":
            if pom_shape(a, "pom.xml at " + first[:7]) != pom_shape(b, "pom.xml at "
                                                                     + later[:7]):
                differ.append(path)
        elif path.endswith(".java"):
            if code[first, path][0] != code[later, path][0]:
                differ.append(path)
        elif a != b:
            differ.append(path)
    if differ:
        raise CodeDiffers("the runs are at %s and %s, and the code under %s differs between"
                          " them in more than comments, so they do not go in one file (the"
                          " later run is a first run of new code, a file of its own): %s"
                          % (first[:7], later[:7], ", ".join(sorted(classes)),
                             ", ".join(differ)))
    CODE_CACHE[key] = compared
    return compared


def code_note(n, first, later, classes):
    """The lines under run n's RUN_LINE that say its code is the first run's, which check
    recomputes; none if the two runs are at one commit."""
    if first == later:
        return []
    by_dir = {}
    for path in code_compared(first, later, classes):
        by_dir.setdefault(os.path.dirname(path), []).append(os.path.basename(path))
    files = "; ".join(("%s: %s" % (d, ", ".join(names))) if d else ", ".join(names)
                      for d, names in sorted(by_dir.items()))
    text = ("Run 1 is at %s and run %d at %s, and bench/record.py found the code under the"
            " benchmark the same at both: it compared %s. The files compared: %s."
            % (first[:7], n, later[:7],
               CODE_RULE.format(seed=" and ".join("%s.java" % c for c in sorted(classes))),
               files))
    return header_lines([["Code", text]])


# Checking a file.

def tables_of(lines):
    """The min/avg/max tables of some lines, each a list of (name, parameters, 'min', ...)."""
    out = []
    for line in lines:
        if TABLE_HEAD.match(line):
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
    pair = [i for i, line in enumerate(lines) if PAIR_TITLE.match(line)]
    jmh = [i for i, line in enumerate(lines) if line.startswith("# JMH version:")]
    for i in pair[-1:] + jmh[:1]:
        return lines[:i], lines[i:]
    raise Problem("the file has no raw section: no line starts with '# JMH version:' or"
                  " '# Pair:'")


def check(path, directories):
    """The differences between a results file and what its runs' artifacts give, as lines:
    the artifacts in the order of the file's runs."""
    if isinstance(directories, str):
        directories = [directories]
    with open(path, encoding="utf-8") as f:
        text = f.read()
    try:
        text.encode("ascii")
    except UnicodeEncodeError as e:
        return ["the file is not ASCII at character %d: %r" % (e.start, text[e.start])]
    actual = [l.rstrip() for l in text.split("\n")]
    while actual and not actual[-1]:
        actual.pop()
    starts = [0] + [i for i, l in enumerate(actual) if RUN_LINE_RE.match(l)]
    if len(directories) > len(starts):
        # A run that lacks the file's class is not in the file (write noted it).
        mine = classes_of(actual)
        directories = [d for d in directories
                       if any(classes_of(v) == mine for v in files_of(
                           d, "x" if artifact_lines(d)[1] else None, False).values())]
        # Nor is one whose code under the file's class is not the first run's (write noted it).
        directories = directories[:1] + [d for d in directories[1:]
                                         if not code_differs(directories[0], d, mine)]
    if len(starts) != len(directories):
        return ["the file holds %d run%s (a later one starts at a '# Run N of M of this file:'"
                " line), and %d artifact%s given"
                % (len(starts), "" if len(starts) == 1 else "s", len(directories),
                   " was" if len(directories) == 1 else "s were")]
    if len(directories) > 1:
        joined_files(directories)  # refuses runs that do not belong in one file
        del NOTES[:]
    diffs, of_first = [], None
    for n, (start, end, d) in enumerate(zip(starts, starts[1:] + [len(actual)], directories), 1):
        part = actual[start:end]
        if n > 1 and part[0] != RUN_LINE % (n, len(directories)):
            diffs.append("run %d: its first line is not '%s'" % (n, RUN_LINE % (n, len(starts))))
        of_first = of_first or classes_of(part)
        if classes_of(part) != of_first:
            diffs.append("run %d is of %s and the file's first run of %s: a file is of one"
                         " class" % (n, ", ".join(sorted(classes_of(part))) or "no benchmark",
                                     ", ".join(sorted(of_first))))
        while part and part[-1] in ("", "#"):
            part.pop()
        found = check_note(part, n, directories[0], d) if n > 1 else []
        found += check_run(path, part, d, n == 1)
        diffs += [("run %d: " % n if len(starts) > 1 else "") + x for x in found]
    return diffs


def commit_of(directory):
    lines = artifact_lines(directory)[0]
    return (dict((k, v) for k, v in plain_header(lines)).get("Commit") or "").split(" ")[0]


def code_differs(first, directory, classes):
    try:
        code_note(2, commit_of(first), commit_of(directory), classes)
        return False
    except CodeDiffers:
        return True


def check_note(part, n, first, directory):
    """A later run's lines between its RUN_LINE and its title: the code note, recomputed from
    git at the two runs' commits, its words compared; nothing if they are at one commit."""
    # The code is compared first: a re-run of other code is refused whatever the file holds.
    want = " ".join(" ".join(code_note(n, commit_of(first), commit_of(directory),
                                       classes_of(part))).split())
    title = artifact_lines(directory)[0][0]
    if title not in part:
        return []  # check_run says the title is missing
    have = " ".join(" ".join(part[1:part.index(title)]).split())
    if have != want:
        return ["the lines between the run's first line and its title are '%s'; bench/record.py"
                " gives '%s'" % (have, want)]
    return []


def check_run(path, actual, directory, first):
    """One run of a file, its lines up to the next run's, against that run's artifact. The
    first run's part has the file's title and hand-written text; a later one starts at its
    RUN_LINE."""
    top, raw = split_file(actual)
    pair = artifact_lines(directory)[1]
    if pair != bool(PAIR_TITLE.match(raw[0])):
        return ["the file is of a %s and the artifact of a %s: is it the file's run?"
                % (("plain run", "pair") if pair else ("pair", "plain run"))]
    made = list(files_of(directory, name="x" if pair else None, named=False).items())
    if len(made) > 1:
        # A run of several classes: the file is checked against the class its raw section has.
        mine = classes_of(raw)
        made = [(name, lines) for name, lines in made if classes_of(lines) == mine]
        if len(made) != 1:
            return ["the file's raw section is of %s, and the artifact has no file of that"
                    % (", ".join(sorted(mine)) or "no benchmark")]
    expected = made[0][1]
    e_top, e_raw = split_file([l for l in expected if MARKER not in l])
    diffs = ["a marker is still to be replaced: %s" % l for l in actual if MARKER in l]
    if first and actual[0] == expected[0]:
        diffs.append("line 1 is still the workflow's title, and the file has none of its own")
    if not first and expected[0] not in actual:
        diffs.append("the run's title line is not there: %s" % expected[0])
    # A run from before the workflow wrote a Host line has no name to compare.
    suffix = NAME_END.search(made[0][0])
    suffix = suffix.group(0) if suffix else None
    if first and suffix and not os.path.basename(path).endswith(suffix):
        diffs.append("the file's name does not end in %s, the first run's host and date"
                     % suffix)
    have = {}
    for key, value in fields(header_block(top)):
        have.setdefault(key, []).append(value)
    for key, value in fields(header_block(e_top)):
        if key not in have:
            diffs.append("header: no '%s' line; the artifact gives: %s" % (key, value))
        for mine in have.get(key, []):
            # Every line of that name, so that a second one cannot say otherwise.
            if not same_field(key, mine, value):
                diffs.append("header: %s is '%s'; the artifact gives '%s'"
                             % (key, " ".join(mine.split()), " ".join(value.split())))
    a_tables, e_tables = tables_of(top), tables_of(e_top)
    if not e_tables or not all(e_tables):
        raise Problem("no min/avg/max table can be read back from what the artifact gives")
    if len(a_tables) != len(e_tables):
        diffs.append("%d min/avg/max tables; the artifact gives %d"
                     % (len(a_tables), len(e_tables)))
    for n, (a, e) in enumerate(zip(a_tables, e_tables), 1):
        a_rows, e_rows = {r[:2]: r[2:] for r in a}, {r[:2]: r[2:] for r in e}
        if not a:
            diffs.append("table %d: no row of it can be read" % n)
        keys = [r[:2] for r in a]
        for key in sorted({k for k in keys if keys.count(k) > 1}):
            diffs.append("table %d: %s %s is there more than once" % (n, key[0], key[1]))
        for key in sorted(set(a_rows) | set(e_rows)):
            if a_rows.get(key) != e_rows.get(key):
                diffs.append("table %d: %s %s is %s; the artifact gives %s"
                             % (n, key[0], key[1], " ".join(a_rows.get(key, ["absent"])),
                                " ".join(e_rows.get(key, ["no such row"]))))
    a_sides, e_sides = ([l for l in t if SIDE_LINE.match(l)] for t in (top, e_top))
    if a_sides != e_sides:
        diffs.append("the lines above a pair's tables are %s; the artifact gives %s"
                     % (a_sides or "absent", e_sides))
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
                "  %.3f \u00b1(99.9%%) %s ns/op [Average]" % (
                    statistics.mean(scores), "\u2248 10\u207b\u2074" if method == "guarded256"
                    else "1.000"),
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
             "# Vector:      ",
             "# Command:     mvn -B -Pjmh test-compile exec:exec", "#",
             "[INFO] Scanning for projects...", "[WARNING] a source line: caf\u00e9"]
    lines += block("ExpBenchmark", "finish256", "common", [10.04, 12.0, 11.0])
    lines += block("ExpBenchmark", "finish256", "mixed", [20.0, 22.0, 21.0])
    lines += block("Log1pBenchmark", "guarded256", "common", [30.0, 32.0, 31.0])
    # A benchmark with no @Param has no Parameters line.
    lines += [l for l in block("Log1pBenchmark", "plain", "", [40.0, 42.0, 40.05])
              if not l.startswith("# Parameters")]
    lines += ["# Run complete. Total time: 00:00:09", "",
              "Benchmark                    (inputs)  Mode  Cnt   Score   Error  Units",
              "ExpBenchmark.finish256         common  avgt    3  11.013 \u00b1 1.000  ns/op",
              "ExpBenchmark.finish256:gc.count common avgt    3  \u2248 0          counts",
              "ExpBenchmark.finish256:gc.alloc common avgt    3  \u2248 10\u207b\u2074    B/op",
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


FAKE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <artifactId>vfdlibm</artifactId>
  <version>0.1.1-SNAPSHOT</version>
  <!-- a comment -->
  <properties>
    <project.build.outputTimestamp>2026-11-20T22:37:56Z</project.build.outputTimestamp>
    <jmh.version>1.37</jmh.version>
  </properties>
  <build><plugins><plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <executions>
      <execution><id>default-test</id></execution>
      <execution><id>fault-exp.dropCorrection</id></execution>
    </executions>
  </plugin></plugins></build>
  <profiles>
    <profile><id>release</id><build><plugins><plugin>
      <artifactId>maven-gpg-plugin</artifactId><version>3.2.7</version>
    </plugin></plugins></build></profile>
    <profile><id>jmh</id></profile>
  </profiles>
</project>
"""


def selftest_code(repo):
    """The code note's parts: the comment stripper, pom.xml's shape, the closure of a benchmark
    at two commits of a scratch clone, and a run and a re-run joined at different commits."""
    def same(a, b):
        return java_code(a, "a")[0] == java_code(b, "b")[0]

    def refused(text, words):
        try:
            java_code(text, "x.java")
            raise AssertionError("not refused: %r" % text)
        except Problem as e:
            assert words in str(e), e

    # Comments go, of either kind, and a comment between two words still parts them.
    assert same("int a = 1; // one\n/* two */ int b;", "int a = 1;\nint b;")
    assert same("/** Javadoc {@link Exp} */\nclass A {}", "class A {}")
    assert not same("a/**/b", "ab") and same("a/**/b", "a b")
    # Whitespace goes where no token joins across it, and stays where one would.
    assert same("f(a,\n      b)", "f(a, b)") and same("x = a+b;", "x = a + b ;")
    assert not same("y = - -x;", "y = --x;") and not same("int x;", "intx;")
    # A code change is a change.
    assert not same("x = 1;", "x = 2;") and not same("f(a, b)", "f(b, a)")
    # A comment marker in a string, a char or a text block is the literal's, not a comment.
    assert java_code('s = "// not a comment";', "s")[0] == 's="// not a comment";'
    assert not same('s = "/* a */";', 's = "/* b */";')
    assert not same('s = "a // b";', 's = "a";')
    assert same('s = "a\\"//b"; // c', 's = "a\\"//b";')
    assert same("c = '\"'; // \"", "c = '\"';") and same("c = '\\''; /* ' */", "c = '\\'';")
    assert same("c = '/'; d = '*';", "c='/';d='*';")
    block = 's = """\n    /* in the block */ "q" \\""" // still in it\n    """; // a comment'
    assert java_code(block, "t")[0] == block[:block.rindex(";") + 1].replace("s = ", "s=")
    assert not same(block, block.replace("in the block", "in a block"))
    # Whitespace inside a literal is the literal's.
    assert not same('s = "a  b";', 's = "a b";')
    # A \u escape that could open or close a comment or a literal is refused, not decoded.
    refused("int a; \\u002f\\u002f hidden\nint b;", "escape \\u002f")
    refused('s = "\\u0022";', "escape \\u0022")
    refused("// \\u000a int b;", "escape \\u000a")
    assert same('s = "\\u0041";', 's = "\\u0041";')
    assert java_code('s = "\\\\u002f";', "e")[0] == 's="\\\\u002f";'  # an escaped backslash
    refused("class A { \\u007d class B {}", "escape \\u007d")  # a brace sets what is top level
    # A line comment ends at CR as at LF, so code after it on the next line is code.
    assert not same("int a; // c\rint b = 1;", "int a; // c\rint b = 2;")
    assert same("int a; // c\r\nint b;", "int a;\nint b;")
    refused("int a; /* open", "comment opened")
    refused('s = "open\n";', "literal opened")
    # The top-level types, not the nested ones, and not a name in a literal.
    names = java_code('class A { static class B {} String s = "class C"; }\nenum D {}', "n")[1]
    assert top_level_types(names) == ["A", "D"], top_level_types(names)
    # pom.xml: its version, timestamp, fault executions and release profile, and comments and
    # whitespace, are not what a benchmark runs; anything else is.
    base = pom_shape(FAKE_POM.encode(), "pom")
    for old, new in (("0.1.1-SNAPSHOT", "0.2.1-SNAPSHOT"),
                     ("2026-11-20T22:37:56Z", "2026-11-22T11:50:06Z"),
                     ("<version>3.2.7", "<version>3.2.8"),
                     ("a comment", "another comment"),
                     ("</executions>", "<execution><id>fault-atan.dropLo</id></execution>"
                                       "</executions>"),
                     ("<jmh.version>", "\n\n    <jmh.version>")):
        assert pom_shape(FAKE_POM.replace(old, new).encode(), "pom") == base, (old, new)
    for old, new in (("<jmh.version>1.37", "<jmh.version>1.38"),
                     ("<id>default-test</id>", "<id>default-test</id><phase>none</phase>"),
                     ("<id>jmh</id>", "<id>jmh</id><activation/>")):
        assert pom_shape(FAKE_POM.replace(old, new).encode(), "pom") != base, (old, new)

    # A scratch clone: commits of a benchmark, the classes it names, and one it does not.
    def commit(files, message):
        for path, text in files.items():
            os.makedirs(os.path.join(repo, os.path.dirname(path)), exist_ok=True)
            with open(os.path.join(repo, path), "w", encoding="utf-8") as f:
                f.write(text)
        cfg = ["-c", "user.name=t", "-c", "user.email=t@example.invalid",
               "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]
        subprocess.run(["git", "-C", repo] + cfg + ["add", "-A"], check=True)
        subprocess.run(["git", "-C", repo] + cfg + ["commit", "-q", "-m", message], check=True)
        return git("rev-parse", "HEAD").stdout.decode().strip()

    main, jmh = "src/main/java/p/", "src/jmh/java/p/"
    files = {"pom.xml": FAKE_POM,
             jmh + "ExpBenchmark.java": "package p;\n/** Not {@link Other}. */\n"
                                        "public class ExpBenchmark {\n"
                                        "  @State public static class S {}\n"
                                        "  double f(double x) { return Exp.exp(x); }\n}\n",
             jmh + "Log1pBenchmark.java": "package p;\npublic class Log1pBenchmark {\n"
                                          "  double f(double x) { return Log1p.log1p(x); }\n}\n",
             main + "Exp.java": "package p;\nfinal class Exp {\n  // the core\n"
                                "  static double exp(double x) { return Lanes.one() * x; }\n}\n",
             main + "Lanes.java": "package p;\nfinal class Lanes {\n"
                                  "  static double one() { return 1.0; }\n}\n",
             main + "Log1p.java": "package p;\nfinal class Log1p {\n"
                                  "  static double log1p(double x) { return x; }\n}\n",
             main + "Other.java": "package p;\nfinal class Other { int s = 1; }\n",
             "src/test/resources/p/hard.txt": "1\n"}
    subprocess.run(["git", "init", "-q", repo], check=True)
    c1 = commit(files, "first")
    c2 = commit({main + "Exp.java": files[main + "Exp.java"].replace(
                     "// the core", "/* the core,\n     reworded */"),
                 main + "Other.java": "package p;\nfinal class Other { int s = 2; }\n",
                 "src/test/resources/p/hard.txt": "2\n",
                 "pom.xml": FAKE_POM.replace("0.1.1", "0.2.1")}, "comments")
    assert code_compared(c1, c2, ["ExpBenchmark"]) == [
        jmh + "ExpBenchmark.java", main + "Exp.java", main + "Lanes.java", "pom.xml"]
    c3 = commit({main + "Lanes.java": files[main + "Lanes.java"].replace("1.0", "2.0")}, "code")
    for later, words in ((c3, "differs between them in more than comments"),
                         (c3, ": src/main/java/p/Lanes.java")):
        try:
            code_compared(c1, later, ["ExpBenchmark"])
            raise AssertionError("a code change")
        except Problem as e:
            assert words in str(e), e
    assert code_compared(c1, c3, ["Log1pBenchmark"])  # Lanes is not under Log1pBenchmark
    c4 = commit({main + "Lanes.java": files[main + "Lanes.java"],
                 main + "Exp.java": files[main + "Exp.java"].replace("Lanes.one()",
                                                                     "Helper.one()"),
                 main + "Helper.java": "package p;\nfinal class Helper {\n"
                                       "  static double one() { return 1.0; }\n}\n"}, "new file")
    try:
        code_compared(c1, c4, ["ExpBenchmark"])
        raise AssertionError("a new file under the benchmark")
    except Problem as e:
        assert "Helper.java (only at %s)" % c4[:7] in str(e) and "Exp.java" in str(e), e
    # A file outside the closure that cannot be read refuses nothing, and one in it does; a
    # module's declarations are compared whatever the closure.
    c5 = commit({main + "Lanes.java": files[main + "Lanes.java"],
                 main + "Exp.java": files[main + "Exp.java"],
                 "src/test/java/p/Odd.java": 'final class Odd { String s = "\\u0022"; }\n'},
                "unread")
    assert code_compared(c1, c5, ["ExpBenchmark"])[-1] == "pom.xml"
    c6 = commit({"src/test/java/p/Odd.java": 'final class Odd { String s = "\\u0022"; }\n'
                                            "final class Lanes2 {}\n",
                 main + "Exp.java": files[main + "Exp.java"].replace("Lanes.one()",
                                                                     "Odd.one()")}, "read")
    try:
        code_compared(c1, c6, ["ExpBenchmark"])
        raise AssertionError("an unreadable file under the benchmark")
    except Problem as e:
        assert "Odd.java at %s" % c6[:7] in str(e) and "escape" in str(e), e
    c7 = commit({main + "Exp.java": files[main + "Exp.java"],
                 main + "module-info.java": "module p { requires jdk.incubator.vector; }\n"},
                "module")
    try:
        code_compared(c1, c7, ["ExpBenchmark"])
        raise AssertionError("a new module-info.java")
    except CodeDiffers as e:
        assert "module-info.java (only at %s)" % c7[:7] in str(e), e
    note = code_note(2, c1, c2, {"ExpBenchmark"})
    assert note[0].startswith("# Code:        Run 1 is at %s and run 2 at %s" % (c1[:7], c2[:7]))
    assert all(len(l) <= WIDTH for l in note) and "src/main/java/p: Exp.java, Lanes.java" in \
        " ".join(" ".join(note).split()), note
    assert code_note(2, c1, c1, {"ExpBenchmark"}) == []

    # A run and its re-run at those commits go in one file, under a note that check
    # recomputes; at a commit whose code differs they are refused, naming the files.
    with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as again:
        def artifact(directory, sha, run, change=lambda t: t):
            fake_plain(directory)
            path = os.path.join(directory, "bench-results.txt")
            with open(path, encoding="utf-8") as f:
                text = f.read().replace("0123456789abcdef0123456789abcdef01234567", sha)
            with open(path, "w", encoding="utf-8") as f:
                f.write(change(text.replace("(main)", "(main)\n# Run:         " + run)))

        artifact(d, c1, "r1")
        artifact(again, c2, "r2", lambda t: t.replace("2026-11-20", "2026-11-21"))
        both = joined_files([d, again])
        two = both["exp-xeon8573c-2026-11-20.txt"]
        at = two.index(RUN_LINE % (2, 2))
        assert two[at + 1:at + 1 + len(note)] == note, two[at:at + 12]
        assert two[at + 1 + len(note)].endswith("2026-11-21 01:02 UTC")

        def written(lines, change=lambda l: l):
            path = os.path.join(d, "exp-xeon8573c-2026-11-20.txt")
            with open(path, "w", encoding="ascii") as f:
                f.write("\n".join(["# A title"] + [change(l) for l in lines[1:]
                                                   if MARKER not in l]) + "\n")
            return path

        assert check(written(two), [d, again]) == []
        log1p = both["log1p-xeon8573c-2026-11-20.txt"]
        assert check(written(log1p), [d, again]) == []
        # The note is the machine's: a word changed in it, or the note gone, is found.
        found = check(written(two, lambda l: l.replace("Lanes.java", "Lanes2.java")), [d, again])
        assert len(found) == 1 and "between the run's first line and its title" in found[0], found
        found = check(written([l for l in two if l not in note[1:]]), [d, again])
        assert len(found) == 1 and "between the run's first line and its title" in found[0], found
        # Run at c3, whose Lanes.java differs in code under ExpBenchmark only: write leaves it
        # out of exp's file, naming the file, and still joins it to log1p's; check refuses it.
        artifact(again, c3, "r2")
        del NOTES[:]
        mixed = joined_files([d, again])
        assert mixed["exp-xeon8573c-2026-11-20.txt"] == files_of(d)[
            "exp-xeon8573c-2026-11-20.txt"]
        assert RUN_LINE % (2, 2) in mixed["log1p-xeon8573c-2026-11-20.txt"]
        assert len(NOTES) == 1 and NOTES[0].startswith(
            "run 2 is not in exp-xeon8573c-2026-11-20.txt") and \
            "src/main/java/p/Lanes.java" in NOTES[0], NOTES
        del NOTES[:]
        assert check(written(mixed["log1p-xeon8573c-2026-11-20.txt"]), [d, again]) == []
        assert check(written(mixed["exp-xeon8573c-2026-11-20.txt"]), [d, again]) == []
        try:
            check(written(two), [d, again])
            raise AssertionError("a re-run of other code")
        except CodeDiffers as e:
            assert "src/main/java/p/Lanes.java" in str(e), e
        # A middle run that lacks the class: the note numbers the run by its place in the file,
        # and the file checks against all three runs.
        with tempfile.TemporaryDirectory() as middle:
            artifact(middle, c1, "r-mid")
            path = os.path.join(middle, "bench-results.txt")
            with open(path, encoding="utf-8") as f:
                text = f.read()
            cut = text.index("# JMH version: 1.37\n# Benchmark: %sExpBenchmark" % PACKAGE)
            end = text.index("# JMH version: 1.37\n# Benchmark: %sLog1p" % PACKAGE)
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(l for l in (text[:cut] + text[end:]).split("\n")
                                  if "ExpBenchmark" not in l))
            artifact(again, c2, "r2", lambda t: t.replace("2026-11-20", "2026-11-21"))
            three = joined_files([d, middle, again])["exp-xeon8573c-2026-11-20.txt"]
            del NOTES[:]
            assert three == two, [l for l in three if l not in two]
            assert check(written(three), [d, middle, again]) == []
        # At different commits the JDK and the command must be the same too.
        for old, new in (("# Load:", "# JDK:         25.0.6\n# Load:"),
                         ("exec:exec", "exec:exec -Dother")):
            artifact(again, c2, "r2", lambda t: t.replace(old, new))
            try:
                joined_files([d, again])
                raise AssertionError("another JDK or command at another commit")
            except Problem as e:
                assert "the same JDK and command" in str(e), e


def selftest():
    global REPO
    saved = REPO
    with tempfile.TemporaryDirectory() as repo:
        # A clone of the self-test's own, never this checkout: CI's is one commit deep.
        REPO = os.path.join(repo, "clone")
        try:
            selftest_code(REPO)
            CODE_CACHE.clear()
            return selftest_files()
        finally:
            REPO = saved


def selftest_files():
    def written(directory, lines, change=lambda l: l, name="exp-xeon8573c-2026-11-20.txt",
                title="# A title of its own"):
        # A file as it would be committed: the markers gone, the title written.
        path = os.path.join(directory, name)
        with open(path, "w", encoding="ascii") as f:
            f.write("\n".join([title] + [change(l) for l in lines[1:] if MARKER not in l])
                    + "\n")
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
        # A value under 0.0005, which JMH prints as a power of ten, is ASCII too.
        assert "ExpBenchmark.finish256:gc.alloc common avgt    3  ~ 10^-4    B/op" in exp
        # A row with no parameter holds "-", and a tie rounds to the even digit: 40.05 is 40.0.
        assert "# Log1pBenchmark.plain       -              40.0         40.7         42.0" \
            in log1p, "\n".join(log1p)
        assert [tenth(x) for x in ("10.050", "10.150", "10.250", 10.35, 0.04)] == [
            "10.0", "10.2", "10.2", "10.4", "0.0"]
        found = check(written(d, log1p, lambda l: l.replace("  40.7  ", "  40.8  ")), d)
        assert len(found) == 1 and found[0].startswith("table 1: Log1pBenchmark.plain -"), found
        assert not any("ExpBenchmark." in l for l in log1p)
        assert exp[-1].startswith("ExpBenchmark.finish256          mixed"), exp[-1]
        assert sum(MARKER in l for l in exp) == 7
        # A header line with no value is kept, and a value typed into it is found.
        assert "# Vector:" in exp
        found = check(written(d, exp, lambda l: l.replace("# Vector:", "# Vector: UseAVX=3")), d)
        assert len(found) == 1 and found[0].startswith("header: Vector"), found
        # A file as written still has its markers; without them it checks.
        with open(os.path.join(d, "kept.txt"), "w") as f:
            f.write("\n".join(exp) + "\n")
        kept = check(os.path.join(d, "kept.txt"), d)
        assert len(kept) == 9 and sum("marker" in x for x in kept) == 7, kept
        # With the markers deleted and no title written, or under another host's name, it
        # still does not check.
        found = check(written(d, exp, title=exp[0]), d)
        assert found == ["line 1 is still the workflow's title, and the file has none of its"
                         " own"], found
        found = check(written(d, exp, name="exp-epyc7763-2026-11-20.txt"), d)
        assert found == ["the file's name does not end in -xeon8573c-2026-11-20.txt, the first"
                         " run's host and date"], found
        assert check(written(d, exp, name="exp-finish-xeon8573c-2026-11-20.txt"), d) == []
        # A hand-added header line of any form does not end the header.
        odd = [x for l in exp for x in ([l, "# CPU MHz:     3000"]
                                        if l.startswith("# Commit:") else [l])]
        assert check(written(d, odd), d) == []
        assert check(written(d, exp), d) == []
        assert check(written(d, log1p), d) == []
        # A run and its re-run in one file: the second run's header, table and raw output
        # under its line, each checked against its own artifact, in the file's order.
        with tempfile.TemporaryDirectory() as again:
            fake_plain(again)
            with open(os.path.join(again, "bench-results.txt"), encoding="utf-8") as f:
                rerun = f.read().replace("10.04", "10.24").replace("2026-11-20", "2026-11-21")
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(rerun)
            try:
                joined_files([d, d])
                raise AssertionError("one run twice")
            except Problem:
                pass
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(rerun.replace("01234567 (main)", "01234567 (main)\n# Run:         r2"))
            both = joined_files([d, again])
            assert sorted(both) == sorted(files), sorted(both)
            two = both["exp-xeon8573c-2026-11-20.txt"]
            at = two.index(RUN_LINE % (2, 2))
            assert two[:at - 1] == exp and two[at + 1:at + 4] == [
                "# (Exp|Log1p)Benchmark on a GitHub runner, 2026-11-21 01:02 UTC", "#",
                "# Host:        xeon8573c (expected CPU 'INTEL(R) XEON(R) PLATINUM 8573C')"]
            assert sum(MARKER in l for l in two) == 7 and len(tables_of(two)) == 2
            assert "# ExpBenchmark.finish256  common         10.2         11.0         12.0" \
                in two[at:], two[at:at + 20]
            assert check(written(d, two), [d, again]) == []
            found = check(written(d, two), [again, d])
            assert found and all(x.startswith("run ") for x in found), found
            found = check(written(d, two, lambda l: l.replace("  10.2  ", "  10.3  ")),
                          [d, again])
            assert len(found) == 1 and found[0].startswith("run 2: table 1"), found
            found = check(written(d, two), d)
            assert len(found) == 1 and "holds 2 runs" in found[0], found
            found = check(written(d, exp), [d, again])
            assert len(found) == 1 and "holds 1 run " in found[0], found
            # A later run's block goes by its class, not by the file's name; a class only
            # one of the runs has is noted and joined to nothing.
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                cut = rerun.index("# JMH version: 1.37\n# Benchmark: %sLog1p" % PACKAGE)
                f.write("\n".join(
                    l for l in (rerun[:cut] + rerun[rerun.index("# Run complete."):]).split("\n")
                    if "Log1pBenchmark" not in l).replace(
                        "01234567 (main)", "01234567 (main)\n# Run:         r2"))
            del NOTES[:]
            narrow = joined_files([d, again])
            assert narrow["exp-xeon8573c-2026-11-20.txt"] == two
            assert narrow["log1p-xeon8573c-2026-11-20.txt"] == log1p
            assert NOTES == ["run 2 has no Log1pBenchmark: log1p-xeon8573c-2026-11-20.txt does"
                             " not hold that run"], NOTES
            del NOTES[:]
            wide = joined_files([again, d])
            assert list(wide) == ["exp-xeon8573c-2026-11-21.txt"] and len(NOTES) == 1 \
                and NOTES[0].startswith("run 2's Log1pBenchmark is in no file"), NOTES
            del NOTES[:]
            # A file whose second run is of another class does not check.
            mixed = two[:at + 1] + [l for l in both["log1p-xeon8573c-2026-11-20.txt"]
                                    if MARKER not in l][at + 1:]
            found = check(written(d, mixed), [d, again])
            assert any("a file is of one class" in x for x in found), found
            # The file of a class the re-run lacks checks with both artifacts given.
            assert check(written(d, narrow["log1p-xeon8573c-2026-11-20.txt"]), [d, again]) == []
            assert check(written(d, narrow["exp-xeon8573c-2026-11-20.txt"]), [d, again]) == []
            # A re-run at a commit the clone does not have cannot have its code compared.
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(rerun.replace("0123456789abcdef", "ffffffffffffffff"))
            try:
                joined_files([d, again])
                raise AssertionError("another commit")
            except Problem as e:
                assert "is not in the clone" in str(e), e
            # A run from before the workflow wrote a Host line still checks, by its content.
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(rerun.replace("# Host:        xeon8573c (expected CPU 'INTEL(R) XEON(R)"
                                      " PLATINUM 8573C')\n", ""))
            hostless = [l for l in files_of(again, named=False)["exp-unknown-unknown.txt"]
                        if MARKER not in l]
            assert not any(l.startswith("# Host:") for l in hostless)
            assert check(written(again, hostless, name="exp-epyc7763-2026-11-02.txt"),
                         again) == [], check(written(again, hostless, name="exp-epyc7763-"
                                                     "2026-11-02.txt"), again)
            # Runs of two hosts are not one file's.
            with open(os.path.join(again, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(rerun.replace("# Host:        xeon8573c", "# Host:        epyc7763"))
            try:
                joined_files([d, again])
                raise AssertionError("two hosts in one file")
            except Problem as e:
                assert "one host" in str(e), e
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
        # A row there twice is found, whichever copy is the artifact's.
        twice = [x for l in exp for x in (
            [l.replace("  10.0  ", "  77.7  "), l] if l.startswith("# ExpBenchmark.finish256  c")
            else [l])]
        found = check(written(d, twice), d)
        assert found == ["table 1: ExpBenchmark.finish256 common is there more than once"], found
        # A table none of whose rows can be read is not an empty comparison.
        found = check(written(d, [l for l in exp if not TABLE_ROW.match(l)]), d)
        assert "table 1: no row of it can be read" in found, found
        # A sentence of the reading that starts like a machine-made line is not one.
        prose = exp[:1] + ["# Row by row, the finish arm leads.",
                           "# Pair: none; this is one run.",
                           "# Commit: main, dispatched by hand.",
                           "# The head, which has it, is ahead.", "#"] + exp[1:]
        assert check(written(d, prose), d) == []
        found = check(written(d, exp, lambda l: l.replace("(4 cores)", "(64 cores)")), d)
        assert len(found) == 1 and found[0].startswith("header: Load"), found
        assert check(written(d, exp, lambda l: l.replace(" (4 cores)", "")), d) == []
        # The Load line is compared whole, however it is wrapped.
        rewrapped = "\n".join(l for l in exp if MARKER not in l).replace(
            "at end (4 cores)", "at\n#              end (4 cores)").split("\n")
        assert check(written(d, rewrapped), d) == []
        found = check(written(d, exp, lambda l: l.replace("at end (4", "at end, 7.0 (4")), d)
        assert len(found) == 1 and found[0].startswith("header: Load"), found
        # A second line of one name is compared too.
        second = [x for l in exp for x in ([l, l.replace("0123", "ffff")]
                                           if l.startswith("# Commit:") else [l])]
        found = check(written(d, second), d)
        assert len(found) == 1 and found[0].startswith("header: Commit is 'ffff"), found
        missing = check(written(d, [l for l in exp if not l.startswith("# Commit:")]), d)
        assert missing and missing[0].startswith("header: no 'Commit' line"), missing
        try:
            plain_files(ascii_lines(open(os.path.join(d, "bench-results.txt"),
                                         encoding="utf-8").read()), "one-name")
            raise AssertionError("one name for two classes")
        except Problem:
            pass
    with tempfile.TemporaryDirectory() as d:
        fake_pair(d)
        files = files_of(d)
        assert list(files) == ["exp-pair-epyc7763-2026-11-20.txt"], list(files)
        plain_written = written

        def written(directory, lines, change=lambda l: l):
            if PAIR_TITLE.match(lines[0]):
                return plain_written(directory, lines, change, "exp-x-epyc7763-2026-11-20.txt")
            return plain_written(directory, lines, change)

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
        # The line above a side's table, its commit and its count, is checked too.
        for old, new in (("The base, aaaaaaa", "The base, bbbbbbb"), ("its 8 m", "its 9 m")):
            found = check(written(d, pair, lambda l: l.replace(old, new)), d)
            assert len(found) == 1 and found[0].startswith("the lines above"), found
        found = check(written(d, pair, lambda l: l.replace("+100.0%  +", "+100.1%  +")), d)
        assert found and found[0].startswith("raw section"), found
        # A pair's file against a plain run's artifact, and the other way, is said so.
        with tempfile.TemporaryDirectory() as plain:
            fake_plain(plain)
            found = check(written(d, pair), plain)
            assert len(found) == 1 and "pair and the artifact of a plain run" in found[0], found
            found = check(written(plain, list(files_of(plain).values())[0]), d)
            assert len(found) == 1 and "plain run and the artifact of a pair" in found[0], found
        # A commit subject that is not ASCII is written escaped, and the file checks.
        with open(os.path.join(d, "bench-results.txt"), encoding="utf-8") as f:
            whole = f.read()
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(whole.replace("(Before)", "(Fix caf\u00e9 rounding)"))
        accent = list(files_of(d).values())[0]
        assert "# Base:        aaaaaaabbbbbbb (Fix caf\\xe9 rounding)" in accent, accent[:12]
        assert check(written(d, accent), d) == []
        # A pair's artifact without its pair/ is an error, not a plain run.
        os.rename(os.path.join(d, "pair"), os.path.join(d, "elsewhere"))
        try:
            check(written(d, accent), d)
            raise AssertionError("a pair with no pair/")
        except Problem as e:
            assert "no pair/base-*.json" in str(e), e
        os.rename(os.path.join(d, "elsewhere"), os.path.join(d, "pair"))
        # With no @Param the column is "params" and each row holds "-"; two modes are refused.
        for r in (1, 2):
            for side in ("base", "head"):
                path = os.path.join(d, "pair", "%s-%d.json" % (side, r))
                with open(path) as f:
                    entries = json.load(f)
                for e in entries:
                    del e["params"]
                with open(path, "w") as f:
                    json.dump(entries, f)
        bare = files_of(d)["exp-pair-epyc7763-2026-11-20.txt"]
        assert [len(t) for t in tables_of(bare)] == [1, 2], "\n".join(bare[:14])
        found = check(written(d, bare, lambda l: l.replace("  18.0  ", "  99.9  ")), d)
        assert len(found) == 1 and found[0].startswith("table 1"), found
        entries[0]["mode"] = "ss"
        with open(path, "w") as f:
            json.dump(entries, f)
        try:
            files_of(d)
            raise AssertionError("two modes in one table")
        except Problem:
            pass
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write("# X on a GitHub runner, 2026-11-20 01:02 UTC\n#\n[ERROR] no build\n")
        try:
            files_of(d)
            raise AssertionError("an artifact with no JMH output")
        except Problem:
            pass
        # A run that stopped before its end is not a results file.
        fake_plain(d)
        with open(os.path.join(d, "bench-results.txt"), encoding="utf-8") as f:
            cut = f.read().split("# Run complete.")[0]
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(cut + "[ERROR] Failed to execute goal\n")
        try:
            files_of(d)
            raise AssertionError("a run that did not finish")
        except Problem as e:
            assert "did not finish" in str(e), e
        # Nor a run with no host or no date to name the file by.
        fake_plain(d)
        with open(os.path.join(d, "bench-results.txt"), encoding="utf-8") as f:
            whole = f.read()
        for old in ("# Host:        xeon8573c (expected CPU 'INTEL(R) XEON(R) PLATINUM 8573C')",
                    ", 2026-11-20 01:02 UTC"):
            with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
                f.write(whole.replace(old, "# Host:" if old.startswith("#") else ""))
            try:
                files_of(d)
                raise AssertionError("no host or no date")
            except Problem as e:
                assert "no Host line" in str(e), e
        # A header line of a form the script does not know is not dropped silently.
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(whole.replace("# Command:", "# Turbo/boost: off\n# Command:"))
        try:
            files_of(d)
            raise AssertionError("an unknown header line")
        except Problem as e:
            assert "does not know" in str(e), e
        # A benchmark of another package is its class's all the same.
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(whole.replace(PACKAGE, "org.example.inner."))
        assert sorted(files_of(d)) == ["exp-xeon8573c-2026-11-20.txt",
                                       "log1p-xeon8573c-2026-11-20.txt"]
        # A parameter value that reads as two parameters is refused.
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(whole.replace("(inputs = mixed)", "(inputs = a, b)"))
        try:
            files_of(d)
            raise AssertionError("a parameter value with a comma")
        except Problem as e:
            assert "cannot be told" in str(e), e
        # Nor is a run in which one benchmark failed: its row would be missing.
        fake_plain(d)
        with open(os.path.join(d, "bench-results.txt"), encoding="utf-8") as f:
            whole = f.read()
        at = whole.index("Result", whole.index("(inputs = mixed)"))
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write(whole[:at] + "<failure>\n\n" + whole[whole.index("# JMH version", at):])
        try:
            files_of(d)
            raise AssertionError("a benchmark with no Result block")
        except Problem as e:
            assert "ExpBenchmark.finish256 has no Result block" in str(e), e
        # Only JMH's own signs are rewritten: another character in a kept line is an error.
        with open(os.path.join(d, "bench-results.txt"), "w", encoding="utf-8") as f:
            f.write("# X on a GitHub runner, 2026-11-20 01:02 UTC\n#\n# JMH version: 1.37\n"
                    "# Benchmark: %sXBenchmark.a\ncaf\u00e9\n# Run complete.\n" % PACKAGE)
        try:
            files_of(d)
            raise AssertionError("a non-ASCII character in a kept line")
        except Problem:
            pass
    print("bench/record.py: self-test passed")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--selftest", action="store_true")
    sub = parser.add_subparsers(dest="command")
    for command in ("write", "check"):
        p = sub.add_parser(command)
        if command == "check":
            p.add_argument("file", help="the results file to check")
        p.add_argument("artifact", nargs="*",
                       help="a downloaded artifact's directory; several for a file of"
                            " several runs of one host, in the file's order")
        p.add_argument("--run", action="append",
                       help="a run to download the artifact of, with gh; may be repeated")
        p.add_argument("--repo", default="vecbricks/vfdlibm",
                       help="the repository of --run (default: %(default)s)")
        if command == "write":
            p.add_argument("--out-dir", default=os.path.join(
                os.path.dirname(os.path.abspath(__file__)), "results"),
                help="where the files go (default: bench/results of this checkout)")
            p.add_argument("--name", help="the file's name before <host>, such as exp-onediv")
            p.add_argument("--force", action="store_true", help="overwrite an existing file")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if not args.command:
        parser.error("write, check or --selftest")
    if bool(args.artifact) == bool(args.run):
        parser.error("give artifact directories or --run, one of the two")
    with tempfile.TemporaryDirectory(prefix="bench-record-") as scratch:
        directories = args.artifact or [
            download(run, args.repo, os.path.join(scratch, "%d-%s" % (i, run)))
            for i, run in enumerate(args.run)]
        return act(args, [find_artifact(d) for d in directories])


def act(args, directories):
    if args.command == "check":
        diffs = check(args.file, directories)
        for line in diffs:
            print(line)
        print("%s: %s" % (args.file, "differs from the artifact" if diffs
                          else "its header, tables and raw section are the artifact's"))
        return 1 if diffs else 0
    if args.name and os.sep in args.name:
        raise Problem("--name is a file's name, not a path: %s" % args.name)
    files = {os.path.join(args.out_dir, name): lines
             for name, lines in joined_files(directories, args.name).items()}
    there = [path for path in files if os.path.exists(path)]
    if there and not args.force:
        raise Problem("%s: there already, and nothing was written; pass --force to overwrite"
                      " (a file's hand-written lines are then to be put back from git), or %s"
                      % (", ".join(there), "--name" if len(files) == 1 else "--out-dir"))
    for note in NOTES:
        print("bench/record.py: note: %s" % note, file=sys.stderr)
    os.makedirs(args.out_dir, exist_ok=True)
    for path, lines in files.items():
        with open(path, "w", encoding="ascii", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print("wrote %s: %d lines, %d of them markers to replace by hand"
              % (path, len(lines), sum(MARKER in l for l in lines)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError:
        raise  # the self-test's
    except Exception as e:
        # An unreadable file or a malformed artifact is an error, never "differs" (status 1).
        kind = "" if isinstance(e, Problem) else type(e).__name__ + ": "
        print("bench/record.py: error: %s%s" % (kind, e), file=sys.stderr)
        sys.exit(2)
