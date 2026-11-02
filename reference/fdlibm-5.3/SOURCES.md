# fdlibm 5.3: the reference sources

These are Sun's fdlibm 5.3 C sources for the thirteen functions in `PLAN.md` section 3, with the
header they share and netlib's `readme`. They are reference only: never compiled, never part of
the build or the jar, and kept byte for byte as netlib serves them. They are the only source a
port in this repository may be translated from, and a port cites the file here that it follows.

## Where they come from

Each file was fetched from netlib's fdlibm directory, `https://netlib.org/fdlibm/<name>`, on
2 November 2026 (`https://www.netlib.org/fdlibm/` redirects there). The version is netlib's own:
its `readme` opens with "Announcing FDLIBM Version 5.3". The `@(#)` column is the SCCS stamp in
each file's first lines.

| File | `@(#)` stamp |
|------|--------------|
| `fdlibm.h` | fdlibm.h 1.5 04/04/22 |
| `e_exp.c` | e_exp.c 1.6 04/04/22 |
| `s_expm1.c` | s_expm1.c 1.5 04/04/22 |
| `e_sinh.c` | e_sinh.c 1.3 95/01/18 |
| `e_cosh.c` | e_cosh.c 1.3 95/01/18 |
| `e_log.c` | e_log.c 1.3 95/01/18 |
| `e_log10.c` | e_log10.c 1.3 95/01/18 |
| `s_log1p.c` | s_log1p.c 1.3 95/01/18 |
| `s_atan.c` | s_atan.c 1.3 95/01/18 |
| `e_atan2.c` | e_atan2.c 1.3 95/01/18 |
| `e_asin.c` | e_asin.c 1.3 95/01/18 |
| `e_acos.c` | e_acos.c 1.3 95/01/18 |
| `e_hypot.c` | e_hypot.c 1.3 95/01/18 |
| `e_pow.c` | e_pow.c 1.5 04/04/22 SMI |
| `readme` | none |

Their SHA-256 hashes, in `sha256sum` format:

    64113c472c2676499288bc35a821b0867916552930bc52c7445d2c50dddf0e71  fdlibm.h
    04c96195b7ced914d659b5fd204724c182f091af8631fe42016ad6e27cb302ef  e_exp.c
    7b1daee13811866caee47e2f9b9b2deccdde3debd646d14db54b11a7938c9dc7  s_expm1.c
    9f6a04f80045a5c0a6d134fdb56d89e65e2a862cdc03fef40505194504ab10e8  e_sinh.c
    381b01b9244180a726bc6282125a31c049a9c12b553eaf758df7c674e7b6b816  e_cosh.c
    a2b1bd1b367026a35f36da53425cd618386c3bf69d23ac224a40791aa9855ac9  e_log.c
    0d636f23e0e70430344cfb003aebce3317260e28c716bbfb8c0839117e533efc  e_log10.c
    f72f255a022fad06c3fe222ad90f961ac5624958373a4fbdae9df3f344135373  s_log1p.c
    8583c253aed6318dee2a48135e3f3b1328d838a390372a69414ac41eb5ce5ce1  s_atan.c
    61dbf9edc927468280ccde872ecfb7cb1ea7cbaf6e1b28e7021c968abe5f7c6e  e_atan2.c
    4ec96c4c7747df94911404b1577e404e13efc51e104f1934e72b536b61adc911  e_asin.c
    bc9fbb24c214030604c0df4c811370f37d511e0c72580767d8c81bd68b0c3a5a  e_acos.c
    87425adcaeabfebcf9f78ba774f4b29d2a158c1f948e2b3129e74b5d6e9cc2ff  e_hypot.c
    763b86baa63eb3518b43f7f3e44e5ed10992b62650f4a45c3a2ea6015e6d5122  e_pow.c
    c6f96d4eea94d45a47d5852ac96d3ec55fc1a04cff25d909c4264c7a8c5c11b9  readme

To check the copies here, run `sha256sum -c` over those lines in this directory; to check them
against netlib, fetch each URL with `curl -sSL` and `cmp` it with the file here.

## Sun's notice

The files carry one of two forms of Sun's notice. The 1993 form is in `e_acos.c`, `e_asin.c`,
`e_atan2.c`, `e_cosh.c`, `e_hypot.c`, `e_log.c`, `e_log10.c`, `e_sinh.c`, `s_atan.c` and
`s_log1p.c`:

    Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.

    Developed at SunSoft, a Sun Microsystems, Inc. business.
    Permission to use, copy, modify, and distribute this
    software is freely granted, provided that this notice
    is preserved.

The 2004 form is in `fdlibm.h`, `e_exp.c`, `e_pow.c` and `s_expm1.c`:

    Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.

    Permission to use, copy, modify, and distribute this
    software is freely granted, provided that this notice
    is preserved.

`readme` carries no notice. A port keeps, in its source header, the notice of the file it ports.
