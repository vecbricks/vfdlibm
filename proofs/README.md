# `proofs`

Machine-checked proofs, in SMT-LIB, that an implementation which departs from fdlibm's C computes
the same double for every input. vfdlibm's rule is fdlibm's order of operations (`AGENTS.md`); a
port may differ from the C only where a proof shows the result is the same, and a proof that a
port relies on goes here. `dev/prove.sh` runs them in CI under Z3 and fails on any verdict other
than the one each check expects.

The tooling, the prelude and this approach are adapted from vecbricks/varka's
`sql/varka/proofs/` and `dev/varka_prove.sh`, which check Varka's arithmetic lowerings the same
way.

| file | what it holds | written by |
| :--- | :--- | :--- |
| `java.smt2` | the prelude: Java's integer operators and doubles over integers | hand |
| `java_check.smt2` | the prelude against the JVM's results | `ProofFiles` |
| `sign_symmetry.smt2` | round to nearest commutes with negation; exp's two quotients | hand |

- `java.smt2`: Java's `int` and `long` operators over SMT-LIB's integers, each as the JLS
  defines it, and doubles as `S * M * 2^E`, with round to nearest stated on the number line for
  either sign (`jdouble.near`, and `jdouble.near.sub` for the subnormals) and the raw bits.
- `java_check.smt2`: each of the prelude's operators over boundary operands, the doubles at both
  signs and at the edge of the subnormals, each rounding with both neighbours refused; the
  expected values computed by Java while rendering.
- `sign_symmetry.smt2`: round to nearest commutes with negation, for every rational and every
  binade at once, normal and subnormal; and for every double `c` in `[-2, 1]`,
  `fl(c - 2) = -fl(2 - c)`, negative, normal and in `[-4, -1]`. So exp's `a / (c - 2.0)` has the
  bits of `-(a / (2.0 - c))`, unless the quotient rounds to zero, where IEEE 754's sign rule
  gives the same (stated, not checked). The file's header says exactly what is proven and what
  is not.

## Running them

    dev/prove.sh --install z3          # once: Z3 5.1.0 into target/solvers/
    dev/prove.sh                       # every proof under Z3
    dev/prove.sh --install both && dev/prove.sh --solver both   # and under cvc5 1.4.1
    dev/prove.sh --self-test           # the checker of verdicts, checked
    dev/prove.sh --lint                # as CI runs them

By hand, the prelude goes after the file's `set-logic` line:
`sed '/^(set-logic/r proofs/java.smt2' proofs/sign_symmetry.smt2 | z3 -in`. A solver's line
numbers count the inserted prelude.

CI (`.github/workflows/ci.yml`, job `proofs`) installs Z3 and runs `--self-test` and `--lint`.

TODO: a nightly run of every proof under both solvers, `dev/prove.sh --install both &&
dev/prove.sh --solver both`, in the nightly workflow that is planned; until then, run it by hand
before merging a change to a proof or to the prelude.

## What a proof looks like

A file opens with a header naming the C or the Java it encodes, what it proves and what it
leaves out, then its `set-logic` line: `QF_NIA`, or `ALL` where cvc5 decides the checks only
under it. Every check is an `(echo "<what it shows>: expect sat|unsat")` followed by its
`(check-sat)` between `push` and `pop`, and the echo stays on one line of at most 100 columns,
since the runner matches it whole. An `unsat` check is a proof: the negation of the claim has no
model. A `sat` check shows the statement is not vacuous: the domain admits its boundary inputs
and every binade the proof names is reached, and the claim with a constant changed by one is
refuted. The script holds every verdict to the expectation before it, and fails on `unknown`, on
a check past its time limit, and on any other output, since Z3 goes on answering after an error,
and an assertion it dropped can turn a refutation into `sat`.

A refutation is worth writing against the part of the definition a claim rests on, not only
against a constant: `sign_symmetry.smt2` also shows its claim false under a rounding that drops
the negative side's half step at a binade's bottom, so the proof could not pass with that case
wrong.

## Why integers, not SMT-LIB's floating-point theory

In floating-point theory, and over bit-vectors, a solver bit-blasts each operation into a
circuit. Varka measured the cost: a double division by 1000 took one solver eight minutes in
floating-point theory, and a multiply-high division timed out over bit-vectors under every
solver tried, where the same statements over integers take milliseconds. So the prelude states
Java's wrapping with `mod`, which also keeps the meaning right where bit-vector operators differ
from Java's (a bit-vector shift does not mask its count).

A double is `S * M * 2^E`, and since `2^E` for an unknown `E` is not linear, the prelude returns
no double: `jdouble.near` relates an exact rational `a / b` to the double it rounds to in a binade
the proof names, passing `2^E` as `sn / sd`. Two ways to cover the binades:

- **Per binade, with a coverage check.** A proof that rounds a value it cannot place has one
  check per binade the value can fall in, and a check that those binades cover every value its
  domain gives, without which a check could pass by leaving a value out. A value in
  `[2^j, 2^(j+1))` rounds into binade `j` or to `2^(j+1)`, the bottom of the next, so the list
  runs one binade past the values' span unless the top is itself a double. Each binade gets a
  `sat` check that some input reaches it.
- **Every binade at once.** `jdouble.near` does not need `sn / sd` to be a power of two, so a
  claim that holds for every positive scale holds in every binade. Where both solvers decide the
  check with `sn` and `sd` symbolic, one check replaces the 2046; `sign_symmetry.smt2`'s
  section A is such a check.

A rational with a fixed power-of-two denominator keeps a check linear: every double is an integer
multiple of `2^-1074`, so a sum or difference of doubles is `n / 2^1074` exactly
(`jdouble.SUB`). A symbolic denominator multiplies two unknowns, and the solvers may answer
`unknown`: the uniqueness of `jdouble.near`'s double within a binade, stated so, was out of reach
for both in a minute, and rests instead on `java_check.smt2` refusing both neighbours of every
double it checks.

Zero is not in the prelude: a proof whose value can round to zero states its own case.

## Adding a proof

1. One file per claim, its header naming the C (with its file under `reference/fdlibm-5.3/`) or
   the Java it encodes, and saying exactly what is proven and what is not. Constants that live in
   the code are rendered from it: a method in `ProofFiles`, and the file in its `render()`; a
   proof whose constants are fdlibm's, fixed in the C, may be written by hand.
2. The claim as `unsat` checks, and `sat` checks that its domain admits its boundary inputs, that
   each binade it names is reached, and that a constant changed by one is refuted.
3. A definition it needs that the prelude lacks goes in `java.smt2`, with a check in
   `ProofFiles.javaCheck()` that pins it to the JVM's results at both signs and at the edges,
   each double's neighbours refused. A wrong definition with the right name would let a proof
   prove the wrong thing.
4. `dev/prove.sh --solver both` holds, in seconds. `--lint` runs a file under Z3 unless the
   script's `CVC5_IN_LINT` names it, which it should for a file cvc5 proves much faster than Z3.
5. cvc5 is the fragile one. Where it answers `unknown`, try in order: `(set-logic ALL)` in place
   of `QF_NIA` (it decides `sign_symmetry.smt2` under `ALL` and not under `QF_NIA`); a concrete
   binade or denominator for a `sat` check, since a symbolic scale makes it nonlinear; one lemma
   per step over named digits or quotients, so each check is linear arithmetic over a few small
   variables; a residue written as a multiple of a declared integer rather than through `mod`;
   and fewer wrapping operations per check. A check that holds alone can still come back
   `unknown` after other checks in the same run, so a new file is run whole, and each of its
   checks alone, under both solvers.
6. A planted fault shows a check can fail: change a definition in a copy of `java.smt2` (a half
   step dropped, a midpoint moved) and see the proof's checks come back with the wrong verdict.

`ProofFiles` writes the rendered files and `ProofFilesTest` fails when a committed one differs
from its rendering; `mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true` rewrites them.

## The solvers

Z3 5.1.0 (MIT) from PyPI's `z3-solver`, whose wheel ships the binary, and cvc5 1.4.1
(BSD-3-Clause) from its GitHub release archive, its SHA-256 checked; the pins are Varka's.
`dev/prove.sh` installs them under `target/solvers/` and refuses other versions. They are
development tools: nothing here is compiled, packaged or shipped, and `pom.xml` does not know
them.
