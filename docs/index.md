---
layout: default
title: vfdlibm
---

vfdlibm ports Sun's fdlibm 5.3 to the Java Vector API: math functions over `DoubleVector` lanes
whose every lane is bit-identical to `java.lang.StrictMath`, but for a lane of two different NaNs
in a function of two arguments. The source is at
[github.com/vecbricks/vfdlibm](https://github.com/vecbricks/vfdlibm).

## Posts

- [StrictMath at SIMD speed: the same bits as Spark's exp, log and pow](blog/2026-11-strictmath-at-simd-speed.md)
