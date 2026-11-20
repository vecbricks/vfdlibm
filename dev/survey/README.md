# Which one-step changes the checks catch

Each port shows that its checks can fail with three or four planted faults, chosen because
they change many results. These tables say what the checks do with the changes nobody chose:
every constant of a function's C one ulp up and one down, every threshold one step up and one
down, every correction term dropped, 205 mutants over exp, expm1, log and log10. log1p has no
table yet; it needs its scalar transcription first.

## How it is made

- `src/test/java/.../Mutants.java` holds each function's C in scalar Java, line by line, with
  its constants, thresholds and correction terms taken from a parameter set, and makes the
  mutants from that set. With nothing changed a transcription is `StrictMath` bit for bit; every
  run counts that again and fails if it is not so. log10's set holds log's after its own, since
  `e_log10.c` calls `__ieee754_log`; those mutants are named `log.`.
- `src/test/java/.../Survey.java` counts, for each mutant, the inputs on which its result is no
  longer `StrictMath`'s, in five sets of inputs:
  - `suite`: every distinct input the unit tests give `Oracle.assertUnary` and
    `assertUnaryMasked` for the function, recorded by a run of the tests with
    `-Dvfdlibm.survey.record=<dir>`; 9.1 to 11.5 million a function;
  - `hard`: the published hard cases, which are part of the suite, counted on their own;
  - `fuzz`: draws of `Fuzz.draw`, as the nightly fuzz makes them, with the place of the first
    draw that differs;
  - `sweep`: a stripe of the nightly `Sweep`, three low words per high word;
  - `search`: draws spread evenly over the function's common path. No check of the project
    draws these. They are the search for an input that shows a mutant the others let through.
- `dev/survey.sh` runs it in two passes. The first takes every mutant: 2^26 fuzz draws, sweep
  stripe `0/64` (2^26 high words) and 2^26 search draws. The second takes the mutants the first
  found on no input, one JVM run each: 2^30 fuzz draws, every high word of the sweep (as one
  night does) and 2^32 search draws, 1.8 * 10^10 inputs a mutant. A run takes up where an
  earlier one stopped. `SURVEY_LOCK`, `SURVEY_THREADS` and the sizes are in the script's header.

A mutant is a change to the C, not to a port. Where a port does the C's operations, an input
that shows the mutant shows the same change in the vector code. Where a port departs by a lemma
of `PROOFS.md`, the lemma's hinge tests guard the departure, and a mutant of the folded line
may have no counterpart in the port at all.

## The tables

`<function>.tsv` is the first pass and `<function>-uncaught.tsv` the second, tab-separated
after `#` lines that give the commit, the seed and the size of each set. A row is a mutant: its
name (`P1+ulp`, `hx>0x3fd62e42-1`, `drop:x=hi-lo`), its kind, the count in each set, and
`caught_by`, the first of `suite`, `fuzz`, `sweep` and `search` with a count above zero, or
`none`, with one input from that set as raw bits.

Made on 20 November 2026, on the laptop, seed `0x5eed`:

| function | mutants | suite | fuzz only | sweep only | search only | on no input |
|---|---|---|---|---|---|---|
| exp | 45 | 27 | 5 | 0 | 0 | 13 |
| expm1 | 57 | 31 | 2 | 0 | 3 | 21 |
| log | 45 | 30 | 4 | 0 | 0 | 11 (second pass: 7 of 13 run) |
| log10 | 58 | 35 | 2 | 0 | 0 | 21 (second pass running) |

"fuzz only" and "search only" count both passes. log's second pass was still running when
this was written: `log-uncaught.tsv` holds the mutants it has finished, and log's last cell
counts the 6 it had not yet run as found on no input. log10's second pass is smaller than the
others' (2^28 fuzz draws, sweep stripe `0/8` and 2^29 search draws a mutant) and its table
comes in a later commit.

## What it shows

- **Every dropped term is caught by the suite**, the least seen on 27,658 of its function's
  10.4 million inputs (log10's `drop:log.0.333*f`, the near path's cubic term).
- **The sweep adds nothing here.** No mutant is found by the sweep and by nothing before it,
  in a stripe or in all 2^32 high words. Three low words per high word is not where a constant
  one ulp off shows.
- **A coefficient one ulp off is close to invisible to inputs.** The suite sees `P1` and `P2` of exp
  (4,480 and 7 of 9.1 million inputs for `P1+ulp` and `P2+ulp`) and nothing of `P3` to `P5`; of
  expm1's `Q2` to `Q5` it sees nothing, and of log's `Lg3` to `Lg7` one input, for `Lg3+ulp`. The
  fuzz finds the next coefficient after millions of draws (`P3-ulp` at draw 1,285,716 of a stream,
  `P3+ulp` at 10,614,866, expm1's `Q2-ulp` at 34,914,759, log's `Lg4-ulp` at 8,306,894), and 1.8 *
  10^10 inputs find nothing for the ones after it. What holds these constants is not an input: each
  port's test pins them to the bits the C's comments give (`ExpTest.constantsAreTheSourcesBits`,
  `intWordsAreTheSources` and their like in `Expm1Test`, `LogTest` and `Log10Test`). A new port
  needs that test for every constant it names; sampling will not stand in for it.
- **The suite's inputs miss a few mutants that an input does show.** They are the rows whose
  `caught_by` is `fuzz` or `search`, each with its input:

  | function | mutant | found by | input |
  |---|---|---|---|
  | exp | `ln2LO-ulp` | fuzz, 1 of 2^26 | `0x40862e41b4b81a53` |
  | exp | `invln2+ulp` | fuzz, 24,690 of 2^26 | `0x4080b08f291b07d7` |
  | exp | `invln2-ulp` | fuzz, 40,554 of 2^26 | `0xc0853d0be66558b9` |
  | exp | `P3-ulp` | fuzz, 1 of 2^26 | `0x3fd62e455f911a51` |
  | exp | `P3+ulp` | fuzz, 6 of 2^30 | see `exp-uncaught.tsv` |
  | expm1 | `ln2_lo+ulp` | search, 3 of 2^26 | `0x40813d11ecc9bf58` |
  | expm1 | `ln2_lo-ulp` | search, 2 of 2^26 | `0x40613becdec29a5c` |
  | expm1 | `Q2+ulp` | fuzz, 1 of 2^26 | `0x405033caf119c34c` |
  | expm1 | `Q2-ulp` | fuzz, 2 of 2^30 | `0x4012059671dffac3` |
  | expm1 | `k<20-1` | search, 1 of 2^26 | `0x402a129199ab1c18` |
  | log | `Lg3-ulp` | fuzz, 20 of 2^26 | `0x3fe6a0dc5d06b879` |
  | log | `hx<0x00100000-1` | fuzz, 908,884 of 2^26 | `0x000ffffffffece5e` |
  | log | `Lg4+ulp` | fuzz, 6 of 2^30 | `0x3ff6a09e66436ea9` |
  | log | `Lg4-ulp` | fuzz, 3 of 2^30 | `0x3ff6a0995fd5e385` |
  | log10 | `log.Lg3+ulp` | fuzz, 12 of 2^26 | `0x3fe6a09e667f6b7d` |
  | log10 | `log.Lg3-ulp` | fuzz, 9 of 2^26 | `0x3fe6a09e66b789a6` |

  log's `hx<0x00100000-1` moves the subnormal test down a word, so the largest subnormals
  (high word `0x000fffff`) skip the scaling; the fuzz finds it at its first draw, and no
  input of the suite shows it.
  exp's `invln2` is the one the suite should not miss: one ulp either way changes `k` near the
  doubles `(k + 1/2) ln2`, the fuzz finds it within 250 draws, and `ExpTest` takes the neighbours of
  three of them only (`0.5 ln2`, `1.5 ln2`, `-1021.5 ln2`). expm1's suite has the 16 neighbours of
  every such double and catches its `invln2`.
- **Mutants found on no input** are in the `-uncaught.tsv` tables. Read with the C, and none
  of it proved here, they fall into four groups:
  - changes that cannot reach a result: `huge` and `tiny` one ulp off (they only raise flags
    or overflow), a filter word moved to admit a high word whose inner tests are false
    (`hx>=0x40862E42-1`), expm1's `k<=-2` moved to `k<=-1` (the `k == -1` lanes have returned
    by then), exp's `u_threshold-ulp` (its one input, the double below the threshold, gives the
    same zero either way) and, under log10, log's own tests of the smallest normal and of
    non-finite words and its `two54`, which log10's rescaled argument never meets;
  - a line that agrees with its neighbour on the inputs that move: the near reduction's last
    word taking the far form (`hx<0x3FF0A2B2-1`, which `PROOFS.md`'s L2 proves for exp's whole
    band), exp's `k>=-1021+1`, expm1's `hx>=0x4043687A` and `hx<0x3c900000` a word either way
    and `k<20+1`, log's and log10's `hx<0x00100000+1`. Each is a fold a lemma could state;
  - `hx>=0x7ff00000+1`, which sends a NaN whose high word is `0x7ff00000` down the arithmetic.
    The result kept the input's payload on this host, which Java does not promise;
  - constants whose change is below what any sampling here resolves: the coefficients above,
    exp's `halF`, expm1's `-0.25`, log's `ln2_lo` and `0.33333333333333333`, log10's
    `log10_2lo`. The 64 neighbours of every `(k + 1/2) ln2` show nothing for `halF`, and the
    2^20 neighbours of `ln2_hi - 0.25` nothing for `-0.25`; these two and the `k < 20` counts
    below come from a probe run by hand over `Mutants`, which is not committed.

## One note against SKILLS.md

SKILLS.md ("A planted fault must change results, measured before it is wired") records that
expm1's line 203 moved from `k < 20` to `k < 21` changed 3 of 2^22 draws in (19.4, 20.6) ln2 and
moved to `k < 19` none. Here `k<20+1` changed none of 2^22 draws in that interval, nor any of
1.8 * 10^10 inputs, and `k<20-1` changed 8 of 2^22 draws in (18.4, 19.6) ln2, where the lanes
with `k = 19` are. The two readings disagree on `k < 21`; the earlier one's transcription is not
in the repository, so which is right is open.

## Running it

    dev/survey.sh                # all four functions, both passes, into dev/survey/
    dev/survey.sh exp            # one function
    SURVEY_DEEP_FUZZ=0 dev/survey.sh exp    # the first pass only

The first pass of one function is one to three minutes on four threads; a mutant of the second
pass is about four. A new port adds its transcription to `Mutants`, its name to this script and
its tables here in its own pull request.
