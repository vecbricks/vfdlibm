#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Print README.md's table of speedups over StrictMath for a release, from the results files.

    bench/readme-table.py [--tag v<version>]   prints the table (the README's marker's tag)
    bench/readme-table.py --write [--tag ...]  puts it between the README's markers
    bench/readme-table.py --check              fails if the README's table is not what it prints
    bench/readme-table.py --selftest

The table describes one release, named by its git tag in the README's first marker,
"<!-- speedup-table: v<version> -->". Its code is the tag's: the functions are those of
VFdlibm.java at the tag, each function's default arm is read from the tag's code, and a figure
is shown only from a results file that times the tag's code. So nothing on main but a results
file or the README itself changes the output: a port, a new function or a Dependabot bump of
pom.xml leaves it as it is.

The release flow (AGENTS.md, "Releases", step 1): the release pull request, made before
dev/release.sh tags, sets the marker to the coming tag with --write --tag v<version>, after a
benchmark pass on both hosts against the release candidate. While the tag does not exist (in
this clone, nor at origin, from which it is fetched), the code compared is HEAD's, and HEAD's
pom.xml must say <version> or <version>-SNAPSHOT, or the script refuses: so a marker cannot
name a tag that is not coming next. Once dev/release.sh has made the tag, the code compared is
the tag's; the release commit changes only the pom's version and <scm><tag>, which the
comparison leaves out, so the table is the same in both states and needs no rewrite.

The table has a row per function and a column per host: xeon8573c (AVX-512) and epyc7763 (AVX2)
always, and xeon6973p, epyc9v45 and neoversen2 once a results file of theirs exists. A cell
holds two ratios, for the inputs common and mixed: the minimum of the file's scalarStrictMath row
over the minimum of the default arm at the preferred species (its <arm>Preferred row), both of
one run, in ns/op as the file's min/avg/max table gives them. Where a file holds a run and its
re-run, the ratio is taken within each run and the lower one is shown. A ratio under 1.40 from
a file of one run is marked: "(one run, near 1.3x)" from 1.30, "(one run, under 1.3x)" below,
since AGENTS.md asks a claim under about 1.3x to be re-run.

The file of a function and host is bench/results/<function>-<host>-<date>.txt whose benchmark
class is <Function>Benchmark (so -kinds, -pair, -onediv and laptop files are not read): the
newest one whose every run times the code of the tag for that benchmark, by bench/record.py's
rule (code_compared: the benchmark's closure the same but for comments and whitespace, pom.xml
but for its version and the parts no benchmark runs; here <scm><tag> too, which the release
commit sets). With no such file, a cell says "not measured" if the function and host have no
file, and otherwise "newer code" or "older code" by whether the newest file's commit is newer
than the tag's; a file with no Commit line (from before the workflow wrote one) is older code.
A file without the rows says which it lacks. A commit that git can neither find nor fetch is an
error, never a cell.

The default arm is read from the code: <Function>.java's entry point, fn(..., live), either
returns the private body called with an arm's constant (BLEND reads as blended), or computes in
place, and then the benchmark must have that one vector arm only. The benchmark's
<arm>Preferred method must call the entry point (directly or through VFdlibm) or a method of
<Function>.java that calls the body with that same constant; otherwise the row would time
another arm, and the script refuses.

Exit status: 0, 1 when --check finds the README's table different (it prints a diff), 2 on an
error (no markers, a file or source that cannot be read, a commit or tag not to be had, a
benchmark row that is not the default arm's).
"""

import argparse
import decimal
import difflib
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import record  # noqa: E402  (bench/record.py: the results file's parts, and the code rule)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGE_DIR = "io/github/vecbricks/vfdlibm"

# The hosts in the order of their columns, with their instruction set; the ones not ALWAYS get a
# column once a results file of theirs exists (AGENTS.md, "Measurements, not adjectives").
HOSTS = [("xeon8573c", "AVX-512"), ("xeon6973p", "AVX-512"), ("epyc9v45", "AVX-512"),
         ("epyc7763", "AVX2"), ("neoversen2", "Arm")]
ALWAYS = ("xeon8573c", "epyc7763")
KINDS = ("common", "mixed")

BEGIN = re.compile(r"^<!-- speedup-table: (v(\d+\.\d+\.\d+)) -->$")
END = "<!-- end of speedup-table -->"
NOT_MEASURED = "not measured"
OLDER, NEWER = "older code", "newer code"
NEAR, UNDER = decimal.Decimal("1.40"), decimal.Decimal("1.30")


RECORD_POM_SHAPE = record.pom_shape


def pom_shape(data, what):
    """record.pom_shape without <scm><tag>, which dev/release.sh sets to v<version> in the
    release commit and back to HEAD after it: no benchmark runs it, and the tag's own code would
    otherwise differ from every file's."""
    return RECORD_POM_SHAPE(re.sub(rb"(<scm>.*?<tag>)[^<]*(</tag>)", rb"\1\2", data,
                                   flags=re.S), what)


record.pom_shape = pom_shape  # what code_compared calls, in this process only


def read(root, path):
    with open(os.path.join(root, path), encoding="utf-8") as f:
        return f.read()


def class_of(function):
    return function[0].upper() + function[1:]


def rev(name):
    done = record.git("rev-parse", "--verify", "-q", name + "^{commit}", check=False)
    return done.stdout.decode().strip() if done.returncode == 0 else None


def pom_version(data):
    root = ET.fromstring(data)
    return (root.findtext(record.POM_NS + "version") or "").strip()


def reference(tag, version):
    """The commit whose code the table describes: the tag's, fetched from origin if this clone
    lacks it; while the tag does not exist, HEAD's, if HEAD's pom.xml is that version's."""
    sha = rev("refs/tags/" + tag)
    if not sha:
        record.git("fetch", "--quiet", "origin", "refs/tags/%s:refs/tags/%s" % (tag, tag),
                   check=False, timeout=record.FETCH_TIMEOUT)
        sha = rev("refs/tags/" + tag)
    if sha:
        return sha, False
    head = rev("HEAD")
    have = pom_version(record.tree(head)["pom.xml"])
    if have not in (version, version + "-SNAPSHOT"):
        raise record.Problem("the tag %s does not exist here or at origin, and HEAD's pom.xml"
                             " says %s, not %s or %s-SNAPSHOT: the marker names a release that"
                             " is neither made nor coming next" % (tag, have, version, version))
    return head, True


def java(src, path):
    if path not in src:
        raise record.Problem("%s is not there at the release's commit" % path)
    return record.java_code(src[path].decode("utf-8"), path)[0]


def functions(src):
    """VFdlibm's public functions at the release, in the order VFdlibm.java declares them."""
    out = []
    for name in re.findall(r"public static DoubleVector (\w+)\(",
                           java(src, "src/main/java/%s/VFdlibm.java" % PACKAGE_DIR)):
        if name not in out:
            out.append(name)
    if not out:
        raise record.Problem("VFdlibm.java declares no public function")
    return out


def arm_of_constant(constant):
    arm = constant.lower()
    return "blended" if arm == "blend" else arm


def default_arm(src, name):
    """The function's default arm, read from its code, and its benchmark's row checked to be
    that arm's."""
    cls = class_of(name)
    main = java(src, "src/main/java/%s/%s.java" % (PACKAGE_DIR, cls))
    bench = java(src, "src/jmh/java/%s/%sBenchmark.java" % (PACKAGE_DIR, cls))
    # Every method of <Function>.java that is one call of the private body with a constant.
    delegates = dict(re.findall(
        r"static DoubleVector (\w+)\([^)]*\)\{return %s\([^;]*,([A-Z]+)\);\}" % name, main))
    entry = re.search(r"static DoubleVector %s\([^)]*VectorMask<Double>live\)\{"
                      r"(?:return %s\([^;]*,([A-Z]+)\);\})?" % (name, name), main)
    if not entry:
        raise record.Problem("%s.java has no %s(..., VectorMask<Double> live)" % (cls, name))
    vector_arms = sorted(set(re.findall(r"public double\[\](\w+)Preferred\(\)", bench))
                         - {"scalarStrictMath"})
    if entry.group(1):
        arm = arm_of_constant(entry.group(1))
    elif len(vector_arms) == 1:
        arm = vector_arms[0]  # one arm, computed in place: the benchmark's only one
    else:
        raise record.Problem("%s.%s computes in place and %sBenchmark has the arms %s: which is"
                             " the default?" % (cls, name, cls, ", ".join(vector_arms) or "none"))
    body = re.search(r"public double\[\]%sPreferred\(\)\{(.*?)return" % arm, bench, re.S)
    if not body:
        raise record.Problem("%sBenchmark has no %sPreferred(), the default arm's row"
                             % (cls, arm))
    calls = set(re.findall(r"\b(?:%s|VFdlibm)\.(\w+)\(" % cls, body.group(1)))
    right = {m for m in calls if m == name or (entry.group(1)
                                               and delegates.get(m) == entry.group(1))}
    if not right or calls - right:
        raise record.Problem("%sBenchmark.%sPreferred calls %s, not %s's default arm, %s"
                             % (cls, arm, ", ".join(sorted(calls)) or "nothing", name, arm))
    return arm


def runs_of(lines):
    """Each run of a results file: (its header fields as a dict, its min/avg/max table)."""
    starts = [0] + [i for i, l in enumerate(lines) if record.RUN_LINE_RE.match(l)]
    out = []
    for start, end in zip(starts, starts[1:] + [len(lines)]):
        part = lines[start:end]
        top = record.split_file(part)[0]
        tables = record.tables_of(top)
        if len(tables) != 1:
            raise record.Problem("a run of the file has %d min/avg/max tables, not one"
                                 % len(tables))
        out.append((dict(record.fields(record.header_block(top))), tables[0]))
    return out


def width_of(header, path):
    m = re.match(r"MaxVectorSize=(\d+)\b", header.get("Vector") or "")
    if not m:
        raise record.Problem("%s: no 'Vector: MaxVectorSize=' line" % path)
    return int(m.group(1)) * 8


def cell(runs, cls, arm):
    """The cell's text from a file's runs: 'common / mixed', each the lowest of the runs'
    ratios, marked if one run gives it under 1.40, or what is missing."""
    out = []
    for kind in KINDS:
        need = ("%s.scalarStrictMath" % cls, "%s.%sPreferred" % (cls, arm))
        ratios, lacks = [], set()
        for _, table in runs:
            rows = {name: lo for name, params, lo, _, _ in table if params == kind}
            lacks |= {n.split(".")[1] for n in need if n not in rows}
            if not lacks:
                q = decimal.Decimal(rows[need[0]]) / decimal.Decimal(rows[need[1]])
                ratios.append(q.quantize(decimal.Decimal("0.01"),
                                         rounding=decimal.ROUND_HALF_EVEN))
        if lacks:
            out.append("no %s row" % " or ".join(sorted(lacks)))
            continue
        low = min(ratios)
        mark = ""
        if len(runs) == 1 and low < NEAR:
            mark = " (one run, %s 1.3x)" % ("near" if low >= UNDER else "under")
        out.append("%sx%s" % (low, mark))
    return " / ".join(out)


NAME = re.compile(r"^([a-z0-9]+)-([a-z0-9]+)-(\d{4}-\d{2}-\d{2})\.txt$")


def files(root, names):
    """{(function, host): [path relative to root, newest first]}."""
    out = {}
    hosts = [h for h, _ in HOSTS]
    for name in sorted(os.listdir(os.path.join(root, "bench/results"))):
        m = NAME.match(name)
        if m and m.group(1) in names and m.group(2) in hosts:
            out.setdefault((m.group(1), m.group(2)), []).append(
                (m.group(3), "bench/results/" + name))
    return {k: [p for _, p in sorted(v, reverse=True)] for k, v in out.items()}


def same_code(commit, ref, cls):
    try:
        record.code_compared(commit, ref, [cls])
        return True
    except record.CodeDiffers:
        return False


def commit_time(sha):
    record.have_commit(sha)
    return int(record.git("show", "-s", "--format=%ct", sha).stdout.decode().strip())


def table(root, tag):
    """The README's text between the markers, the markers included, for the release tag."""
    return table_and_state(root, tag)[0]


def table_and_state(root, tag):
    """table's lines, and whether the tag is still to come (the code compared HEAD's)."""
    record.REPO = root
    m = re.fullmatch(r"v(\d+\.\d+\.\d+)", tag)
    if not m:
        raise record.Problem("a release's tag is v<major>.<minor>.<patch>, not %s" % tag)
    version = m.group(1)
    ref, coming = reference(tag, version)
    src = record.tree(ref)
    names = functions(src)
    found = files(root, names)
    hosts = [(h, isa) for h, isa in HOSTS if h in ALWAYS or any(k[1] == h for k in found)]
    widths = {}
    rows = []
    for name in names:
        cls = class_of(name) + "Benchmark"
        arm = default_arm(src, name)
        cells = []
        for host, _ in hosts:
            text, newest = NOT_MEASURED if (name, host) not in found else OLDER, None
            for path in found.get((name, host), []):
                lines = [l.rstrip() for l in read(root, path).split("\n")]
                if not any(l.startswith("# Commit:") for l in lines):
                    continue  # from before the workflow wrote a Commit line: older code
                if record.classes_of(lines) != {cls}:
                    raise record.Problem("%s is named for %s and times %s" % (
                        path, name, ", ".join(sorted(record.classes_of(lines))) or "nothing"))
                runs = runs_of(lines)
                commits = [(h.get("Commit") or "").split(" ")[0] for h, _ in runs]
                if not all(commits):
                    continue  # a run with no Commit line in its header: older code
                newest = newest or commits[0]
                if all(same_code(c, ref, cls) for c in commits):
                    for h, _ in runs:
                        widths.setdefault(host, {}).setdefault(width_of(h, path), path)
                    text = cell(runs, cls, arm)
                    break
            else:
                if newest:
                    text = NEWER if commit_time(newest) > commit_time(ref) else OLDER
            cells.append(text)
        rows.append("| `%s` | %s |" % (name, " | ".join(cells)))
    for host, seen in widths.items():
        if len(seen) > 1:
            raise record.Problem("%s's files give two preferred species: %s" % (
                host, "; ".join("%d bits in %s" % kv for kv in sorted(seen.items()))))
    head_row = "| Function | %s |" % " | ".join(
        "%s (%s): common / mixed" % (h, isa) for h, isa in hosts)
    rule = "|---|" + "---|" * len(hosts)
    width_text = ", ".join("%d bits on %s" % (next(iter(widths[h])), h)
                           for h, _ in hosts if h in widths)
    note = (
        "For vfdlibm %s (tag %s). Each figure is `StrictMath` called once per lane (the"
        " `scalarStrictMath` row) over the function's default arm at the host's preferred"
        " species (its `<arm>Preferred` row), by minimum ns/op within one run; where a file"
        " holds a run and its re-run, the lower of the two runs' ratios is shown, and a ratio"
        " under 1.4x from one run is marked, since it is not re-run. `common`: no lane takes a"
        " rare path; `mixed`: rare lanes run. The preferred species is %s. \"older code\" and"
        " \"newer code\": the files of that function and host time code older or newer than"
        " the release's; \"not measured\": there is no file. The figures come from the newest"
        " file per function and host under [`bench/results/`](bench/results/) that times the"
        " release's code; `bench/readme-table.py --write` makes this table, and CI checks it."
        % (version, tag, width_text or "not measured"))
    note = textwrap.fill(note, width=100, break_long_words=False, break_on_hyphens=False)
    return (["<!-- speedup-table: %s -->" % tag, "", head_row, rule] + rows + [""]
            + note.split("\n") + ["", END]), coming


def between(text):
    """The README split at the markers: (before, the table's lines, after, the marker's tag)."""
    lines = text.split("\n")
    begins = [i for i, l in enumerate(lines) if BEGIN.match(l)]
    ends = [i for i, l in enumerate(lines) if l == END]
    if len(begins) != 1 or len(ends) != 1 or begins[0] > ends[0]:
        raise record.Problem("README.md has not one '<!-- speedup-table: v<version> -->' line"
                             " followed by one '%s' line" % END)
    b, e = begins[0], ends[0]
    return lines[:b], lines[b:e + 1], lines[e + 1:], BEGIN.match(lines[b]).group(1)


def check(root, readme):
    """The differences between the README's table and the script's, as unified diff lines."""
    _, have, _, tag = between(read(root, readme))
    return list(difflib.unified_diff(have, table(root, tag), readme, "bench/readme-table.py",
                                     lineterm=""))


def write(root, readme, tag=None):
    record.REPO = root
    before, _, after, marked = between(read(root, readme))
    tag = tag or marked
    lines, coming = table_and_state(root, tag)  # all of it, before the file is truncated
    if coming:
        # The code compared is HEAD's, and the working tree must be it.
        dirty = record.git("status", "--porcelain", "--", *record.ROOTS, *record.RESOURCES,
                           "pom.xml").stdout.decode().strip()
        if dirty:
            raise record.Problem("%s is not made yet, so the code is compared at HEAD, and"
                                 " these files differ from it: commit them first\n%s"
                                 % (tag, dirty))
    with open(os.path.join(root, readme), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(before + lines + after))


# The self-test: a scratch repository with three functions, their code and results files.

def selftest():
    saved = record.REPO
    try:
        with tempfile.TemporaryDirectory(prefix="readme-table-") as repo:
            selftest_in(repo)
    finally:
        record.REPO = saved
        record.CODE_CACHE.clear()
    print("bench/readme-table.py: self-test passed")
    return 0


VFDLIBM = """package p;
public final class VFdlibm {
  public static DoubleVector exp(DoubleVector x) { return Exp.exp(x, null); }
  public static DoubleVector exp(DoubleVector x, VectorMask<Double> live) { return Exp.exp(x, live); }
  public static DoubleVector atan(DoubleVector x) { return Atan.atan(x, null); }
  public static DoubleVector hypot(DoubleVector x, DoubleVector y) { return Hypot.hypot(x, y, null); }
}
"""
EXP = """package p;
final class Exp {
  private static final int FINISH = 0;
  private static final int BLENDED = 1;
  // the entry point
  static DoubleVector exp(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, FINISH);
  }
  static DoubleVector expBlended(DoubleVector x, VectorMask<Double> live) {
    return exp(x, live, BLENDED);
  }
  private static DoubleVector exp(DoubleVector x, VectorMask<Double> live, int arm) {
    return x;
  }
}
"""
EXP_BENCH = """package p;
public class ExpBenchmark {
  @Benchmark
  public double[] finishPreferred() {
    VFdlibm.exp(DoubleVector.fromArray(SP, in, 0)).intoArray(out, 0);
    return out;
  }
  @Benchmark
  public double[] blendedPreferred() {
    Exp.expBlended(DoubleVector.fromArray(SP, in, 0), null).intoArray(out, 0);
    return out;
  }
  @Benchmark
  public double[] scalarStrictMath() { return out; }
}
"""
ATAN = """package p;
final class Atan {
  static DoubleVector atan(DoubleVector x, VectorMask<Double> live) {
    VectorSpecies<Double> species = x.species();
    return x;
  }
}
"""
ATAN_BENCH = """package p;
public class AtanBenchmark {
  @Benchmark
  public double[] finishPreferred() {
    Atan.atan(DoubleVector.fromArray(SP, in, 0), null).intoArray(out, 0);
    return out;
  }
  @Benchmark
  public double[] scalarStrictMath() { return out; }
}
"""
HYPOT = ATAN.replace("Atan", "Hypot").replace("atan(DoubleVector x,",
                                              "hypot(DoubleVector x, DoubleVector y,")
HYPOT_BENCH = ATAN_BENCH.replace("Atan", "Hypot").replace("atan(", "hypot(")
POM = record.FAKE_POM.replace("</project>", "  <scm><tag>HEAD</tag></scm>\n</project>")


def results_file(cls, runs):
    """A results file of the committed form, enough for this script: per run, a header with a
    Commit and a Vector line, a min/avg/max table and a raw section naming the class.
    runs: [(commit, MaxVectorSize, {(method, inputs): min})]."""
    out = ["# %s on a host: a title" % cls, "#", "# Reading it: by hand.", "#"]
    for n, (commit, size, mins) in enumerate(runs, 1):
        if n > 1:
            out += ["#", record.RUN_LINE % (n, len(runs)), "# A later run's title", "#"]
        out += ["# Host:        somehost", "# Commit:      %s (main)" % commit,
                "# Vector:      MaxVectorSize=%d UseAVX=3" % size, "#"]
        out += record.table(sorted((cls + "." + m, k, v, v, v) for (m, k), v in mins.items()),
                            "inputs")
        out += ["#", "# JMH version: 1.37",
                "# Benchmark: %s%s.%s" % (record.PACKAGE, cls, sorted(mins)[0][0]), ""]
    return "\n".join(out) + "\n"


def mins(arm, common, mixed, scalar=(1000.0, 1100.0)):
    return {(arm, "common"): common, (arm, "mixed"): mixed,
            ("scalarStrictMath", "common"): scalar[0], ("scalarStrictMath", "mixed"): scalar[1]}


def selftest_in(repo):
    main, jmh, res = ("src/main/java/%s/" % PACKAGE_DIR, "src/jmh/java/%s/" % PACKAGE_DIR,
                      "bench/results/")

    def commit(changes, message):
        for path, text in changes.items():
            full = os.path.join(repo, path)
            if text is None:
                os.remove(full)
                continue
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as f:
                f.write(text)
        cfg = ["-c", "user.name=t", "-c", "user.email=t@example.invalid",
               "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]
        subprocess.run(["git", "-C", repo] + cfg + ["add", "-A"], check=True)
        # One second apart, so that commit times order the commits.
        env = dict(os.environ, GIT_COMMITTER_DATE="%d +0000" % (1700000000 + len(made)),
                   GIT_AUTHOR_DATE="%d +0000" % (1700000000 + len(made)))
        subprocess.run(["git", "-C", repo] + cfg + ["commit", "-q", "-m", message],
                       check=True, env=env)
        made.append(message)
        return rev("HEAD")

    def body(tag):
        record.CODE_CACHE.clear()
        lines = table(repo, tag)
        return lines[2:lines.index("", 2)]  # the heading, the rule and the rows

    def refused(tag, words, kind=record.Problem):
        try:
            body(tag)
        except kind as e:
            assert words in str(e), e
            return
        raise AssertionError("not refused: " + words)

    made = []
    subprocess.run(["git", "init", "-q", repo], check=True)
    record.REPO = repo  # what rev() and record's git read
    c0 = commit({"pom.xml": POM, main + "VFdlibm.java": VFDLIBM,
                 main + "Exp.java": EXP, jmh + "ExpBenchmark.java": EXP_BENCH,
                 main + "Atan.java": ATAN.replace("return x;", "return x.neg();"),
                 jmh + "AtanBenchmark.java": ATAN_BENCH,
                 main + "Hypot.java": HYPOT, jmh + "HypotBenchmark.java": HYPOT_BENCH,
                 "README.md": "# r\n\nStatus.\n\n<!-- speedup-table: v0.1.1 -->\n%s\n\n## Use\n"
                              % END}, "first")
    c1 = commit({main + "Atan.java": ATAN}, "atan's code")
    c2 = commit({main + "Exp.java": EXP.replace("// the entry point", "/* reworded */")},
                "comments")
    # exp on the Xeon: two runs, the second at a commit of other comments; on the EPYC, one
    # run, and a later file of no Commit line. atan on the Xeon: a file of older code; on the
    # EPYC, one run lacking a row. Not read: another kind of file, a laptop's, an unknown host's.
    commit({res + "exp-xeon8573c-2026-11-20.txt": results_file("ExpBenchmark", [
                (c1, 64, mins("finishPreferred", 200.0, 400.0)),
                (c2, 64, mins("finishPreferred", 250.0, 500.0, (900.0, 1200.0)))]),
            res + "exp-epyc7763-2026-11-20.txt": results_file("ExpBenchmark", [
                (c1, 32, mins("finishPreferred", 300.0, 800.0))]),
            res + "exp-epyc7763-2026-11-21.txt": "# An early file: no Commit line, no table\n",
            res + "exp-kinds-xeon8573c-2026-11-21.txt": results_file("ExpKindsBench", [
                (c1, 64, mins("finishPreferred", 1.0, 1.0))]),
            res + "exp-laptop-2026-11-21.txt": results_file("ExpBenchmark", [
                (c1, 32, mins("finishPreferred", 1.0, 1.0))]),
            res + "exp-somehost-2026-11-21.txt": results_file("ExpBenchmark", [
                (c1, 32, mins("finishPreferred", 1.0, 1.0))]),
            res + "atan-xeon8573c-2026-11-20.txt": results_file("AtanBenchmark", [
                (c0, 64, mins("finishPreferred", 1.0, 1.0))]),
            res + "atan-epyc7763-2026-11-20.txt": results_file("AtanBenchmark", [
                (c1, 32, {k: v for k, v in mins("finishPreferred", 800.0, 600.0).items()
                          if k != ("finishPreferred", "mixed")})])}, "files")

    # v0.1.1 is coming: no tag, and HEAD's pom says 0.1.1-SNAPSHOT, so HEAD's code is compared.
    # Each run's ratio: 1000/200 and 1100/400 in run 1, 900/250 and 1200/500 in run 2; the
    # lower of each kind is shown. One run's 1100/800 and 1000/800 are marked.
    coming = [
        "| Function | xeon8573c (AVX-512): common / mixed | epyc7763 (AVX2): common / mixed |",
        "|---|---|---|",
        "| `exp` | 3.60x / 2.40x | 3.33x / 1.38x (one run, near 1.3x) |",
        "| `atan` | older code | 1.25x (one run, under 1.3x) / no finishPreferred row |",
        "| `hypot` | not measured | not measured |"]
    got = body("v0.1.1")
    assert got == coming, got
    note = " ".join(table(repo, "v0.1.1"))
    assert "For vfdlibm 0.1.1 (tag v0.1.1). Each" in note, note
    assert "512 bits on xeon8573c, 256 bits on epyc7763" in note, note
    # A tag that is neither made nor coming next is refused, and so is one not of a release.
    refused("v0.1.2", "says 0.1.1-SNAPSHOT, not 0.1.2 or 0.1.2-SNAPSHOT")
    refused("v1", "not v1")

    # --write --tag sets the marker and the table, and --check then passes; a figure changed
    # by hand, or a marker dropped, fails.
    write(repo, "README.md", "v0.1.1")
    assert check(repo, "README.md") == []
    text = read(repo, "README.md")
    assert text.startswith("# r\n\nStatus.\n\n<!-- speedup-table: v0.1.1 -->\n\n| Function")
    assert text.endswith(END + "\n\n## Use\n")
    for old, new in (("3.60x", "3.70x"), ("| `hypot`", "| `hypotx`")):
        with open(os.path.join(repo, "README.md"), "w", encoding="utf-8") as f:
            f.write(text.replace(old, new))
        diff = check(repo, "README.md")
        assert any(l.startswith("-") and new in l for l in diff), diff
    for broken in (text.replace(END, ""), text.replace("v0.1.1 -->", "v0.1.1 -->\n" + END + "\n"
                                                       "<!-- speedup-table: v0.1.1 -->")):
        with open(os.path.join(repo, "README.md"), "w", encoding="utf-8") as f:
            f.write(broken)
        try:
            check(repo, "README.md")
            raise AssertionError("no markers, and no error")
        except record.Problem as e:
            assert "has not one" in str(e), e
    with open(os.path.join(repo, "README.md"), "w", encoding="utf-8") as f:
        f.write(text)
    # While the code is HEAD's, --write refuses code not committed; an error leaves the README.
    with open(os.path.join(repo, main + "Exp.java"), "a", encoding="utf-8") as f:
        f.write("// not committed\n")
    try:
        write(repo, "README.md")
        raise AssertionError("--write with code not committed")
    except record.Problem as e:
        assert "commit them first" in str(e) and "Exp.java" in str(e), e
    subprocess.run(["git", "-C", repo, "checkout", "-q", "--", main + "Exp.java"], check=True)

    # The default arm is read from the release's code: a benchmark row of another arm, and a
    # function of one arm whose benchmark has two, are refused, and the README stays.
    for changes, words in (
            ({jmh + "ExpBenchmark.java": EXP_BENCH.replace(
                "VFdlibm.exp(DoubleVector", "Exp.expBlended(DoubleVector")},
             "ExpBenchmark.finishPreferred calls expBlended, not exp's default arm"),
            ({jmh + "AtanBenchmark.java": ATAN_BENCH.replace(
                "public double[] scalarStrictMath", "public double[] blendedPreferred() {}\n"
                "  public double[] scalarStrictMath")}, "which is the default")):
        undo = {p: read(repo, p) for p in changes}
        commit(changes, "arm")
        refused("v0.1.1", words)
        try:
            write(repo, "README.md")
            raise AssertionError("written: " + words)
        except record.Problem:
            assert read(repo, "README.md") == text
        commit(undo, "undo")
    # A default moved to the blended arm is read from the code: a file of that code gives the
    # blendedPreferred row's ratio.
    moved = commit({main + "Exp.java": EXP.replace("live, FINISH)", "live, BLENDED)")},
                   "blended default")
    commit({res + "exp-xeon8573c-2026-11-21.txt": results_file("ExpBenchmark", [
        (moved, 64, {("blendedPreferred", "common"): 500.0, ("blendedPreferred", "mixed"): 500.0,
                     ("scalarStrictMath", "common"): 1000.0,
                     ("scalarStrictMath", "mixed"): 1100.0})])}, "its file")
    assert body("v0.1.1")[2] == "| `exp` | 2.00x / 2.20x | older code |", body("v0.1.1")
    commit({main + "Exp.java": EXP, res + "exp-xeon8573c-2026-11-21.txt": None}, "undo")

    # dev/release.sh: the release commit (version and <scm><tag>), the tag, the next snapshot.
    rel = commit({"pom.xml": POM.replace("0.1.1-SNAPSHOT", "0.1.1").replace(
        "<tag>HEAD</tag>", "<tag>v0.1.1</tag>")}, "Release 0.1.1")
    subprocess.run(["git", "-C", repo, "tag", "v0.1.1", rel], check=True)
    commit({"pom.xml": POM.replace("0.1.1-SNAPSHOT", "0.1.2-SNAPSHOT")}, "Start 0.1.2")
    # The tag's code is the same as the files', <scm><tag> and the version aside, so the
    # README of the release pull request, which dev/release.sh leaves as it is, still checks.
    assert body("v0.1.1") == coming, body("v0.1.1")
    assert check(repo, "README.md") == []

    # On main after the tag: a code change, a new function and another JMH change nothing.
    c3 = commit({main + "Exp.java": EXP.replace("return x;", "return x.abs();"),
                 main + "Atan.java": ATAN.replace("return x;", "return x.abs();"),
                 main + "VFdlibm.java": VFDLIBM.replace(
                     "}\n", "  public static DoubleVector log(DoubleVector x) { return x; }\n}\n"),
                 "pom.xml": POM.replace("0.1.1-SNAPSHOT", "0.1.2-SNAPSHOT").replace(
                     "<jmh.version>1.37", "<jmh.version>1.38")}, "ports")
    assert body("v0.1.1") == coming, body("v0.1.1")
    assert check(repo, "README.md") == []
    # A file of the new code changes no figure, as the older file still times the tag's code;
    # a host's first file gives it a column; hypot's only file, of newer code, says so.
    commit({res + "exp-xeon8573c-2026-11-22.txt": results_file("ExpBenchmark", [
                (c3, 64, mins("finishPreferred", 1.0, 1.0))]),
            res + "atan-epyc9v45-2026-11-22.txt": results_file("AtanBenchmark", [
                (c1, 64, mins("finishPreferred", 125.0, 1100.0))]),
            main + "Hypot.java": HYPOT.replace("return x;", "return y;")}, "hypot")
    hypot = commit({res + "hypot-epyc7763-2026-11-22.txt": results_file("HypotBenchmark", [
        (rev("HEAD"), 32, mins("finishPreferred", 1.0, 1.0))])}, "hypot's file")
    got = body("v0.1.1")
    assert got == [
        "| Function | xeon8573c (AVX-512): common / mixed | epyc9v45 (AVX-512): common / mixed"
        " | epyc7763 (AVX2): common / mixed |",
        "|---|---|---|---|",
        "| `exp` | 3.60x / 2.40x | not measured | 3.33x / 1.38x (one run, near 1.3x) |",
        "| `atan` | older code | 8.00x / 1.00x (one run, under 1.3x) | 1.25x (one run, under"
        " 1.3x) / no finishPreferred row |",
        "| `hypot` | not measured | not measured | newer code |"], got
    assert check(repo, "README.md") != []  # a results file changed the table: --write again
    assert hypot

    # A file of another species for the same host is refused, and so is one named for another
    # function's class; an unknown commit is an error, not a cell.
    for name, content, words in (
            ("exp-xeon8573c-2026-11-25.txt", results_file("ExpBenchmark", [
                (c2, 64, mins("finishPreferred", 1.0, 1.0)),
                (c2, 32, mins("finishPreferred", 1.0, 1.0))]), "two preferred species"),
            ("exp-xeon8573c-2026-11-25.txt", results_file("AtanBenchmark", [
                (c2, 64, mins("finishPreferred", 1.0, 1.0))]), "is named for exp"),
            ("hypot-xeon8573c-2026-11-25.txt", results_file("HypotBenchmark", [
                ("0" * 40, 64, mins("finishPreferred", 1.0, 1.0))]), "not in the clone")):
        commit({res + name: content}, "bad")
        refused("v0.1.1", words)
        commit({res + name: None}, "undo")
    body("v0.1.1")  # and back as it was


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true",
                       help="fail if README.md's table differs from what this prints")
    group.add_argument("--write", action="store_true",
                       help="put the table between README.md's markers")
    group.add_argument("--selftest", action="store_true")
    parser.add_argument("--tag", help="the release, v<version> (default: the README marker's);"
                                      " --check always takes the marker's")
    parser.add_argument("--readme", default="README.md", help="relative to the checkout's root")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.check:
        if args.tag:
            parser.error("--check takes the README marker's tag")
        diff = check(ROOT, args.readme)
        for line in diff:
            print(line)
        if diff:
            print("bench/readme-table.py: %s's speedup table is not the results files': run"
                  " bench/readme-table.py --write and commit it" % args.readme)
            return 1
        print("bench/readme-table.py: %s's speedup table is the results files'" % args.readme)
        return 0
    if args.write:
        write(ROOT, args.readme, args.tag)
        return 0
    tag = args.tag or between(read(ROOT, args.readme))[3]
    print("\n".join(table(ROOT, tag)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError:
        raise  # the self-test's
    except Exception as e:
        kind = "" if isinstance(e, record.Problem) else type(e).__name__ + ": "
        print("bench/readme-table.py: error: %s%s" % (kind, e), file=sys.stderr)
        sys.exit(2)
