# papers

Machine transcriptions of third-party papers that vfdlibm reads closely enough that quoting them
from memory would be a mistake. They are here so that a port, a comment or a pull request can
cite a section, a constant or a measurement with the argument at hand, in a form that greps and
diffs.

## What these files are not

- **Not vfdlibm's documentation.** Nothing here is written by this project, and nothing here is
  under the Apache License that covers the rest of the repository. Each file names its authors,
  its venue, its DOI or where the copy came from, and the terms the copy carries.
- **Not authoritative.** They are conversions of PDFs, and each loses something, most often the
  parts of a formula carried by typography rather than by characters. Each file opens with what
  its conversion lost. **When a constant, an exponent or an inequality decides something, read
  the PDF.**
- **Not shipped.** Nothing in this directory is compiled or packaged into the jar.
- **Not a library.** A paper earns a place by bearing on a port, a benchmark or a rule here, and
  its file says which.

## Licensing

These are third-party works, kept under the terms their open-access copies print. A paper whose
terms do not permit a copy is not transcribed: keep the citation and the reading notes (in
`readings/` or the pull request) instead.

## How they are made

Mechanically, not by a language model: an extractor that drops things beats one that invents
them. The files here were rebuilt from `pdftotext -bbox-layout` word geometry, which recovers
superscripts and subscripts from glyph size and baseline and cannot fabricate a number; it loses
figures, table grids and formulas built from tall delimiters. A model-based conversion was
tried on another paper and silently closed half-open intervals and dropped a digit from an
eleven-digit bound, each of which read as plausible. If a model-based conversion of a section is
ever wanted, it goes in a separate file that says so.

Both files below were converted for Varka and copied from its `sql/varka/papers/`; vfdlibm's
copies have one fixed character map applied to make them ASCII, as each file's header says.

## Adding one

Convert it, then open the file with: title, authors, venue, DOI, where the copy came from, the
terms it carries, why vfdlibm reads it, how it was converted, and what the conversion loses. Do
not keep a conversion script: these files are read, not regenerated, and a rerun would lose
different things and silently invalidate the header. Add a row below.

## Contents

- `lang-2020-simd-control-flow-divergence.md`: Lang, Passing, Kipf, Boncz, Neumann and Kemper,
  "Make the most out of your SIMD investments: counter control flow divergence in compiled query
  pipelines", VLDB Journal 29, 2020; CC BY 4.0. Read for blending every branch against
  finishing the rare lanes apart (`PLAN.md` section 5).
- `benson-2023-simd-compiler-intrinsics.md`: Benson, Ebeling and Rabl, "Evaluating SIMD
  Compiler-Intrinsics for Database Systems", ADMS 2023; CC BY 4.0. Read for a portable vector
  layer measured per target, and the places it falls short: wider vectors and mask conversion.
