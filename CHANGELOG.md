# Changelog

All notable changes to vfdlibm are listed here, newest first, in the form of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). vfdlibm is at 0.x: a minor version may
break the API and a patch may not (`AGENTS.md`, "Releases"). A pull request that changes what a
user sees adds its line under `Unreleased`; a change to tests, docs or the build alone does not.
A line for a breaking change starts with `**Breaking:**` and says what a client must do. The
contract of every version is the same: each lane of each function equals
`java.lang.StrictMath`'s result on that input, by `Double.doubleToRawLongBits`.

## [Unreleased]

### Added

- `VFdlibm.sinh`, in the two forms `sinh(x)` and `sinh(x, live)`, over `DoubleVector` lanes of any
  species of 128 bits or more. Every lane is bit-identical to `StrictMath.sinh`, NaN payloads and
  signed zeros included. Lanes of magnitude 22 or more, the infinities and NaNs are finished
  per lane by `StrictMath.sinh`.
- `VFdlibm.cosh`, in the two forms `cosh(x)` and `cosh(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.cosh`, NaN payloads
  and signed zeros included.
- `VFdlibm.atan`, in the two forms `atan(x)` and `atan(x, live)`, over `DoubleVector` lanes of
  any species of 128 bits or more. Every lane is bit-identical to `StrictMath.atan`, NaN payloads
  and signed zeros included. Lanes of magnitude `2^66` or more, the infinities and NaNs are
  finished per lane by `StrictMath.atan`.

### Changed

- The per-lane finish of `exp`, `expm1`, `log`, `log10`, `log1p`, `sinh` and `cosh` writes out its
  first eight lanes with constant indices, so that at `DoubleVector.SPECIES_MAX` (up to eight lanes)
  it allocates nothing on data with rare lanes, where it allocated 30 to 37 KB per 1024 doubles.
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

[Unreleased]: https://github.com/vecbricks/vfdlibm/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/vecbricks/vfdlibm/releases/tag/v0.1.0
