# Changelog

All notable changes to vfdlibm are listed here, newest first, in the form of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). vfdlibm is at 0.x: a minor version may
break the API and a patch may not (`AGENTS.md`, "Releases"). A pull request that changes what a
user sees adds its line under `Unreleased`; a change to tests, docs or the build alone does not.
A line for a breaking change starts with `**Breaking:**` and says what a client must do. The
contract of every version is the same: each lane of each function equals
`java.lang.StrictMath`'s result on that input, by `Double.doubleToRawLongBits`, except a lane of
two NaNs with different bits in a function of two arguments (`AGENTS.md`, "Releases").

## [Unreleased]

### Changed

- `hypot` computes the lanes of an infinity or a NaN in either argument in the vector, lines
  66-70 of fdlibm's C as blends, where it called `StrictMath.hypot` per lane; the results are
  unchanged (#186).

## [0.3.0] - 2026-11-25

### Added

- `VFdlibm.hypot`, in the two forms `hypot(x, y)` and `hypot(x, y, live)`, over `DoubleVector`
  lanes of any species of 128 bits or more; a lane that is not live is `x`'s. Every lane is
  bit-identical to `StrictMath.hypot`, NaN payloads and signed zeros included, except a lane where
  both arguments are NaN with different bits: it is one of the two arguments' bits with the quiet
  bit set and the sign cleared, and which one is not specified, since `StrictMath`'s own choice
  varies by JIT tier. Lanes with an infinity or a NaN in either argument are finished per lane by
  `StrictMath.hypot`.
- `VFdlibm.pow`, in the two forms `pow(x, y)` and `pow(x, y, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more; a lane that is not live is `x`'s. Every lane is bit-identical
  to `StrictMath.pow`, NaN payloads and signed zeros included, except a lane where both arguments
  are NaN with different bits: it is one of the two arguments' bits with the quiet bit set, and
  which one is not specified. Lanes off fdlibm's common path (a special, negative or subnormal
  `x`, a special or huge `y`, overflow, underflow and subnormal results) are finished per lane by
  `StrictMath.pow`.

### Changed

- `VFdlibm.expm1` and `VFdlibm.log1p` run the finish arm, which finishes their rare lanes per lane
  by `StrictMath`, instead of the guarded arm; every lane gives the same bits as before (issue
  #30's rule 1: a tie within 1.3x goes to the finish arm, and on both hosts that have a file, the
  Xeon 8573C and the EPYC 7763, no other arm is lower than it by more than 1.3x). Like the other
  finish arms, it writes its first eight lanes with constant indices and any past the eighth at a
  variable index, so at `DoubleVector.SPECIES_MAX` it is read as allocation-free up to eight lanes
  only.

### Known issues

- `hypot` is not shown to be faster than `StrictMath.hypot` on an AVX2 host, and there, on
  `HypotBenchmark`'s mixed data, where about one pair in 16 takes a rare path of fdlibm's C (a NaN
  or an infinity, a ratio past 2^60, a zero smaller argument, a scaled or subnormal argument), it is
  slower. On the AMD EPYC 7763, `HypotBenchmark` reads `StrictMath` over `hypot` at the preferred
  species at 1.25x to 1.29x on common data, under 1.3x, which the project reads as no direction, and
  0.70x to 0.75x on mixed data (`bench/results/hypot-epyc7763-2026-11-24.txt`). There its vector
  rows on mixed data take about 1.45 times the time they took before `hypot` was made to order its
  arguments by magnitude, as `StrictMath` does, with a compare of the whole magnitudes in place of
  the C's compare of their high words (`bench/results/hypot-order-pair-epyc7763-2026-11-24.txt`). In
  diagnostic runs the cost needs both that new compare and the loop inlined into the benchmark's
  measuring loop: the same loop as a method of its own, with the body inlined into it, runs at the
  old level, and so does the inlined loop with the high-word compare
  (`bench/results/hypot-harness-epyc7763-2026-11-24.txt`); why is not known. On the Intel Xeon 8573C
  (AVX-512) `hypot` at the preferred species, 512 bits there, is 2.16x faster than `StrictMath` on
  common data, with no direction on mixed data (`bench/results/hypot-xeon8573c-2026-11-24-2.txt`).
  The results are not affected, only the speed; a fix of the speed would be a patch.

## [0.2.0] - 2026-11-22

### Added

- `VFdlibm.sinh`, in the two forms `sinh(x)` and `sinh(x, live)`, over `DoubleVector` lanes of any
  species of 128 bits or more. Every lane is bit-identical to `StrictMath.sinh`, NaN payloads and
  signed zeros included. Lanes of magnitude 22 or more, the infinities and NaNs are finished
  per lane by `StrictMath.sinh`.
- `VFdlibm.cosh`, in the two forms `cosh(x)` and `cosh(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.cosh`, NaN payloads
  and signed zeros included.
- `VFdlibm.asin`, in the two forms `asin(x)` and `asin(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.asin`, NaN payloads
  and signed zeros included. Lanes of magnitude 1 or more and NaNs are finished per lane by
  `StrictMath.asin`.
- `VFdlibm.atan`, in the two forms `atan(x)` and `atan(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.atan`, NaN payloads
  and signed zeros included. Lanes of magnitude `2^66` or more, the infinities and NaNs are
  finished per lane by `StrictMath.atan`.
- `VFdlibm.atan2`, in the two forms `atan2(y, x)` and `atan2(y, x, live)`, over `DoubleVector`
  lanes of any species of 128 bits or more; a lane that is not live is `y`'s. Every lane is
  bit-identical to `StrictMath.atan2`, NaN payloads and signed zeros included, except a lane where
  both arguments are NaN with different bits: it is one of the two arguments' bits with the quiet
  bit set, and which one is not specified, since `StrictMath`'s own choice varies by JIT tier. No
  lane is finished by a call to `StrictMath`.
- `VFdlibm.acos`, in the two forms `acos(x)` and `acos(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.acos`, NaN payloads
  and signed zeros included. Lanes of magnitude 1 or more and NaNs are finished per lane by
  `StrictMath.acos`.

### Changed

- The contract is stated for functions of two arguments (`atan2`; `pow` and `hypot` later): a lane
  where both arguments are NaN with different bits is one of two candidates the function declares,
  and no other lane differs from `StrictMath`'s bits. No result of a released function changes, and
  a client does nothing.
- The per-lane finish of `exp`, `expm1`, `log`, `log10`, `log1p`, `sinh` and `cosh` writes out its
  first eight lanes with constant indices. Before the change, at `DoubleVector.SPECIES_MAX` (up to
  eight lanes) the finish of `exp`, `expm1`, `log`, `log10` and `log1p` allocated 30,432 to 36,992
  B/op (one op is 1024 doubles) on mixed data (`bench/results/max-finish-laptop-2026-11-20.txt`, a
  laptop reading); after it, every `*Max` row of those seven reads under 0.1 B/op there
  (`bench/results/max-finish-alloc-free-laptop-2026-11-21.txt`, a laptop reading, allocation only,
  no timing).
  Every lane's result is unchanged.

## [0.1.0] - 2026-11-20

### Added

- `VFdlibm.exp`, `expm1`, `log`, `log1p` and `log10` over `DoubleVector` lanes of any species of
  128 bits or more, each in two forms, `f(x)` and `f(x, live)` (only the lanes set in `live`
  are computed; the others keep `x`'s bits). Every lane is bit-identical to `StrictMath`'s
  result, NaN payloads and signed zeros included. They port Sun's fdlibm 5.3, operation by
  operation except where `PROOFS.md` proves a different computation equal for every input.
- The jar holds `LICENSE` and `NOTICE` (Sun's fdlibm notice) under `META-INF/`, and names its
  module `io.github.vecbricks.vfdlibm`. A client needs the incubating Vector API module
  (`--add-modules jdk.incubator.vector`) at compile time and at run time.

[Unreleased]: https://github.com/vecbricks/vfdlibm/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/vecbricks/vfdlibm/releases/tag/v0.3.0
[0.2.0]: https://github.com/vecbricks/vfdlibm/releases/tag/v0.2.0
[0.1.0]: https://github.com/vecbricks/vfdlibm/releases/tag/v0.1.0
