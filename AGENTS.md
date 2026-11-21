# Working in vfdlibm, for agents and people alike

vfdlibm ports fdlibm's double functions to the Java Vector API, each lane bit-identical to
`java.lang.StrictMath`. `PLAN.md` is the plan and the rules; read it before changing anything.

## The rules that matter most

- **Licence.** Port from Sun's fdlibm 5.3 C sources only, and keep each file's Sun notice. Never
  read OpenJDK's `FdLibm.java`, HotSpot's math stubs or glibc in order to write code here; they are
  GPL or LGPL. `StrictMath` is the oracle, called from tests, never a source to copy.
- **Bit identity.** A function is done when every test compares its lanes with `StrictMath` by
  `Double.doubleToRawLongBits` and passes, at every vector width the host has.
- **Same result, proven.** Every lane's result stays bit-identical to `StrictMath`. A port
  follows fdlibm's C operation by operation, or computes differently where a written proof shows
  the result equal for every input. The proof goes in `PROOFS.md` as a numbered lemma: its
  hypotheses (the input domain, each hinge condition with the range where it holds and why the
  lanes are in it), the IEEE property it rests on, the proof, and the test that checks its
  hinges, which must fail if a hinge were false; the code carries a short Javadoc pointer to it.
  A proof is informal (step by step, citing each IEEE property used) or formal: machine-checked,
  or reduced to a finite check by a theorem, such as monotonicity of correctly rounded operations
  plus evaluation at the boundary doubles. Machine-checked proofs follow the conventions of
  Varka's `sql/varka/proofs/` (SMT-LIB over integers, a double as `M * 2^E` with a round-to-nearest
  relation per binade, `unsat` checks as the proofs and `sat` checks for non-vacuity and for a
  constant changed by one being refuted); here they live in `proofs/`, run by `dev/prove.sh` as
  `proofs/README.md` describes. Fused multiply-add, reassociation and division by a
  reciprocal stay out because they change results in general, so no proof covers them as a
  rule; a specific use proven equal for every input would follow the proof rule like any other.

## Build and test

- `mvn -B verify` builds and runs the tests; the Vector API module is added by `pom.xml`. It
  needs Java 25 and Maven 3.9 or later: `build-helper-maven-plugin` 3.8.0 and, for the `jmh`
  profile, `exec-maven-plugin` 3.7.0 declare 3.9.0 as their minimum (the other plugins were not
  checked for a higher one, and no older Maven was tried). `pom.xml` enforces nothing.
- One test: `mvn -q test -Dtest=ExpTest`. A `-Dtest` filter skips the fault runs below (the
  profile `one-test` in `pom.xml`), since it would leave them no test, and a fault run that runs
  no test fails.
- Widths: `-Dvfdlibm.test.jvmArgs=-XX:MaxVectorSize=32` skips the 512-bit cases, as an AVX2
  host does (GitHub's x86 runners are a mix of AVX2 and AVX-512 hosts); `=16` runs everything at
  128 bits, as the Arm runners do.
- Planted faults: a port reads each of its faults once, into a `static final` constant of its own
  class (`static final boolean DROPPING_CORRECTION = Faults.is("exp.dropCorrection")`), and tests
  that constant where the fault would go wrong. The vector body then holds no call, which C2 could
  refuse to inline and so box the vectors, and an unplanted fault folds away. The fault comes from
  `-Dvfdlibm.fault=<name>`, fixed for the JVM's life, so `verify` runs the normal suite once
  and then one forked surefire execution per fault, running only the tests tagged `fault`.
  A new fault goes in `PlantedFaultTest.CATCHERS` with the check that must catch it, and gets an
  execution in `pom.xml`; `PlantedFaultTest` fails if either is missing.
- The harness is in `src/test/java`: `Oracle` compares lanes with `StrictMath` by raw bits,
  `Inputs` makes the inputs, `VectorWidth` runs a test at 128, 256 and 512 bits and the preferred
  species, and `PlantedFaultTest` requires each fault planted through `Faults` (in the main
  sources) to be caught by its check. `HarnessSelfTest` tests them.
- A width wider than the host's vectors is skipped. To see an AVX2 host's skips on an AVX-512
  one: `mvn -B test -Dvfdlibm.test.jvmArgs=-XX:MaxVectorSize=32`. That keeps AVX-512's
  instructions at 256 bits, though: to make C2 compile as on an AVX2 host such as the EPYC
  7763, pass `-XX:UseAVX=2`, and check `-prof gc` and `-XX:+PrintIntrinsics` under it.
  Without AVX512DQ, C2 lowers no `D2L`, `L2D`, `L2F` or `F2L`; such a convert boxes there.
- The nightly correctness run, `.github/workflows/nightly.yml`, is `main` programs in
  `src/test/java` (`Sweep`, `Fuzz`, run per species by `dev/nightly.sh`), which `verify` does
  not run beyond `NightlyTest`'s few thousand inputs; a failing night opens an issue labelled
  `nightly-failure`. How to run a slice by hand is in `SKILLS.md`, "The nightly sweep and fuzz".
- The jar is checked from outside by `dev/jar-check.sh`, which CI runs after `verify`: it must
  hold `LICENSE` and `NOTICE` under `META-INF/`, its module name, the library's classes and
  nothing else but the manifest and Maven's metadata, and a consumer compiled against it alone,
  on the class path and on the module path, must get `StrictMath`'s bits. A new public function
  goes in that consumer too, which fails until it is there; `--self-test` shows the checks of
  the jar's contents failing, each on a copy of the jar with one defect.
- The README's client example is run against the latest release on Maven Central every week by
  `.github/workflows/readme-check.yml` (`dev/readme-check.sh`, which you can run by hand). It
  takes the fenced blocks of "Use" in their order and kinds (a pom, `Main.java`, commands, their
  output, `module-info.java`, commands), so a change to those blocks changes what it expects.
- Benchmarks are JMH, in `src/jmh/java`; `verify` compiles them. Run them with
  `mvn -B -Pjmh test-compile exec:exec`, passing JMH options as `-Djmh.args="..."`, e.g.
  `-Djmh.args="ExpBenchmark.finish256 -p inputs=common"`.
- A port's benchmark must pass the allocation check, which CI runs: `bench/alloc-check.py
  --mvn <mvn>` (add `--jvm-args="-XX:UseAVX=2 -XX:MaxVectorSize=32"` to compile as an AVX2 host).
  It covers each vector arm at 256 bits and at the preferred species if the class is named
  `<Function>Benchmark`, the methods `<arm>256` and `<arm>Preferred`, and the `@Param` `inputs`
  takes `common` (no lane takes a rare path) and `mixed` (rare lanes run); see `SKILLS.md`.
  CI runs it on x86 both ways and, for the `<arm>Preferred` methods, on the Arm runner, where
  the preferred species is 128 bits.
  A class that measures a port's bodies on other data and is not the port's benchmark is named
  otherwise (`ExpKindsBench`), so CI leaves it out; check it by name and on its own inputs
  before reading its times (`--include` with `--inputs`; the command is in its Javadoc). Its
  `setUp` must throw on an `inputs` value it does not know, and a test must pin that, or a row
  named for one kind could pass on another's data.

## Lessons

`SKILLS.md` holds what earlier ports learned about JMH, C2 and the Vector API: how to tell a
boxed body from a vector one, which conversions and masks lower at which width, and where
headline numbers come from. Read it before writing or benchmarking a port, and add to it what a
port teaches.

## Measurements, not adjectives

- Any performance claim, in a comment, a document or a pull request, traces to a committed
  results file under `bench/results/`. "Faster" with no file behind it is not written.
- A results file opens with its provenance: what was run and why, the host, the commit, JDK, CPU,
  CPU flags (whether `avx512f` and `avx512dq` are present), vector flags, the datapath reading
  (`bench/datapath.sh`, `HARDWARE.md`), kernel, load average at start and end, the JMH options
  and the command, then each row's minimum, average and maximum and JMH's summary table with
  `gc.alloc.rate.norm`. The existing files are the template. A file from the laptop says it is
  not a headline.
- A results file is named `bench/results/<function>-<host>-<date>.txt`, one CPU per file, the
  host being the workflow's host key (`xeon8573c`, `xeon6973p`, `epyc9v45`, `epyc7763`) or
  `laptop` with a suffix for its flags (`laptop-avx2`); `<function>` may name the comparison too
  (`exp-finish`, `exp-onediv`). The files written before the newer hosts keep their names. An
  allocation reading from GitHub's Arm runner, which is no benchmark host and has no key, uses
  `arm` (`alloc-arm-2026-11-20.txt`); it is no timing, and says so.
- The method is fixed: `-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc`, the body inlined with
  `-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*`, on an otherwise idle machine.
  The arms of a comparison run in one JMH run. A claim resting on a ratio under about 1.3x is
  re-run and compared by minimums before it is written down, and the file keeps both runs.
- Headline numbers come from `.github/workflows/benchmark.yml` on four named GitHub hosts,
  dispatched with `bench/dispatch-bench.sh --host <host>`: the AVX-512 hosts, read first, the
  Intel Xeon Platinum 8573C (`xeon8573c`) and Xeon 6973P-C (`xeon6973p`), and the AMD EPYC 9V45
  (`epyc9v45`, full-width AVX-512, recorded whenever drawn but never waited for, so its
  dispatcher stops after 18 tries); and the AMD EPYC 7763 (`epyc7763`, the default) for AVX2.
  Each host decides for its own instruction set, in files of its own; laptop numbers never
  headline. Never relax a host's CPU check (`SKILLS.md`, "Where headline numbers come from").
  `HARDWARE.md` lists the hosts with their flags, JVM settings and datapath ratios.
- A change meant to be faster or simpler at no cost is measured as a pair: `bench/pair.sh`
  runs the benchmarks on the base and on the head in alternating rounds on one machine and
  compares medians and bests per row, failing a row slower by both past the threshold. On a
  host, dispatch it with `bench/dispatch-bench.sh --host <host> --base <base ref> <head ref>`;
  the pair's results file goes to `bench/results/` like any other. An A/A pair (`--base HEAD`)
  gives the machine's own spread to read a real pair against.
- A host run becomes a results file with `bench/record.py write --run <run id>`, which writes
  the header, the min/avg/max tables and the raw section from the run's artifact, one file per
  benchmark class, and leaves marked lines for the title, the reason and the reading. No figure
  of those parts is copied by hand: before the file is committed,
  `bench/record.py check <file> --run <run id>` must pass. A run and its re-run on one host go
  in one file, with `--run <run id>` given once per run, in order, to `write` and to `check`;
  a pair's re-run is a file of its own.
- When a change moves committed numbers, measure again and commit the new file, then requote
  every document from it, rather than patching quoted figures one by one.

## Papers are transcribed, not paraphrased from memory

`papers/` holds mechanical transcriptions of the third-party papers this work reads closely, so
a port or a pull request can cite a section, a constant or a measurement with the argument at
hand. They carry their own authors and licence and are not vfdlibm's documentation: nothing in
`papers/` is under this project's Apache licence, compiled or shipped. And they are lossy, since
a PDF's typography carries part of every formula, so **when a constant, an exponent or an
inequality decides something, read the PDF, not the transcription**. Each file opens with what
its conversion lost. See `papers/README.md` before adding one. Notes on other vector math
libraries go in `readings/`, with each source's licence named; never notes on GPL or LGPL code.

## Changes

Changes go through pull requests on branches, never straight to `main` (the owner's two
release commits, made by `dev/release.sh`, are the one exception: "Releases"). Each function
comes in its own PR, paired with the Varka PR that emits it.

A pull request's title reads `[VFDLIBM-<issue number>] <what the change achieves>` when it
belongs to an issue, and `[VFDLIBM] <what it achieves>` when it belongs to none: short and
goal-oriented. Its description follows `.github/PULL_REQUEST_TEMPLATE` (adapted from Varka's),
fills all five sections and explains the mechanism in prose, not only the diff. Commit messages
and the last section of the description end with `Generated-by: <tool> (<model and version>)`
when AI tooling was used (Apache's convention); no `Co-Authored-By`. The pull requests that
Dependabot opens (`.github/dependabot.yml`) keep Dependabot's own title after the `[VFDLIBM] `
tag and its own body, and add no changelog line.

## Releases

vfdlibm is released to Maven Central as `io.github.vecbricks:vfdlibm`, the way vecbricks/jegg is.
`CHANGELOG.md` is kept in the form of Keep a Changelog: a pull request that changes what a user
sees (the API, a behaviour, a speed worth quoting) adds one line under `Unreleased`, in Added,
Changed or Fixed; a change to tests, docs or the build alone does not.

**The promise, while the version is 0.x.** A minor version may break the public API and a patch
may not. Adding a function is a patch. Changing an arm (how a function reaches its result: the
blend, the per-lane finish, the order of a branch) is a patch too, because every arm gives the
same bits. 0.1.x supports JDK 25 only (`README.md`, "Supported JDKs"). What no version may
change is the contract: **each lane of each function equals
`StrictMath`'s result on that input, by `Double.doubleToRawLongBits`**. A result that differs
from `StrictMath`'s is a bug and a fix, never a breaking change. The property `vfdlibm.fault`
and the fault names are for this project's tests only: they are not part of the public API or of
the compatibility promise, and any release, a patch included, may rename or remove them. With the
property unset, the results are unchanged, and each lane still equals `StrictMath`'s. A breaking
change has a changelog line that starts with `**Breaking:**` and says what a client must do, and
its pull request says the same. 1.0.0 promises that the surface stays; it is not scheduled.

**The release is owner-run, from a tag.** `.github/workflows/release.yml` is a dry run only: it
publishes nothing and holds no secret; it shows on GitHub's runners that a tag would build, sign
and pass `dev/jar-check.sh --release`. The steps:

1. In a pull request, move the `Unreleased` lines under a `## [<version>]` heading of
   `CHANGELOG.md`, add its `[<version>]:` link line, and update `README.md`: its status
   paragraph and the sentence "Released in ...: ..." name the release's functions, and the
   sentence "On `main`, unreleased: ..." goes when nothing is left on `main` only.
2. On an up-to-date `main`, `dev/release.sh <version>` checks the tree, sets the pom's version
   (from any `-SNAPSHOT`, so a minor is released the same way),
   its `<scm><tag>` and `project.build.outputTimestamp` (so that the jars are reproducible), runs
   `mvn -B -Prelease -Dgpg.skip=true clean verify` with every test and `dev/jar-check.sh
   --release` on the jars, commits "Release <version>", tags `v<version>` and commits the next
   `-SNAPSHOT`: the two commits, "Release" and "Start", go to `main` without a pull request, end
   with a `Generated-by:` line that `RELEASE_GENERATED_BY` sets (empty: no line), and touch only
   the pom and the changelog. It pushes nothing; `dev/release.sh --check <version>` only checks, and
   `dev/release.sh --self-test` runs the script's own checks in scratch repositories (CI runs
   it in `release.yml`'s `version` job).
3. `git push origin main v<version>`. The push of the tag runs the dry run, which checks that
   the tag is `v<version>`, the pom says `<version>` and the tagged commit is on `main`
   (`dev/release-version.sh`), and builds the release's jars as a release would; ci.yml's jobs
   and the proofs under both solvers run on it too. Wait for it and for CI to be green: nothing
   is uploaded yet, and a red tag is dropped by deleting it on origin and here.
4. `git checkout v<version> && mvn -B -Prelease clean deploy` builds the sources and Javadoc
   jars, signs them with the owner's key and uploads them to Central's portal; publish the
   deployment there, then `git checkout main`. The jars `dev/release.sh` checked and the jars
   this uploads are one build twice: the build is reproducible (`project.build.outputTimestamp`),
   which is the link between them, and signing is the one step the dry run does not cover (it
   signs with a key of its own, without gpg or an agent).

   What the dry run cannot catch: the central plugin runs only at `deploy`, and Central's reply
   to an upload exists only on a real upload. 0.1.0 was deployed with
   `central-publishing-maven-plugin` 0.7.0, whose client failed after the upload with
   `UnrecognizedPropertyException: Unrecognized field "warnings"` (its `DeploymentApiResponse`
   knew six properties; Central's status reply had gained a seventh). The bundle was already
   staged, and the owner published it by hand in the portal. The pom now pins 0.12.0 (the version
   jegg uses; `autoPublish` still defaults to false, so an upload still waits at the portal for
   the owner). A newer Central reply can break a pinned plugin the same way: if `deploy` fails
   after the upload, look at the deployment in the portal before running it again, since a
   second upload of the same version may be refused.

What the owner needs once, on the machine that releases: the namespace `io.github.vecbricks`
verified at the Central portal, a user token in `~/.m2/settings.xml` as the server `central`
(`pom.xml`'s `publishingServerId`), and a GPG key whose public half is on a key server and whose
passphrase is cached in the agent (`export GPG_TTY=$(tty)` and sign something once). The same
setup serves jegg's releases.

Before a release, look for newer versions of what `.github/dependabot.yml` leaves out, which
then move by hand with their security fixes: JMH, `central-publishing-maven-plugin` and
`maven-gpg-plugin`. Nothing tracks the solvers pinned in `dev/prove.sh`, the JDK and Python
versions of the workflows or their runner images either.

Varka pins a released version and never a snapshot.
