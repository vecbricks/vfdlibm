#!/usr/bin/env python3
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
"""Count the instructions of a loop's hot path in C2's -XX:+PrintAssembly output.

Reads the last standard (non-OSR) compile of METHOD from the dump, takes the loop as the
instructions from the target of its widest backward conditional branch up to that branch, and
drops each block a forward conditional branch inside the loop skips over when that branch's
target is also inside the loop: there, the D2I saturation fix-up C2 emits after vcvttpd2dq, run
only when a lane converted to 0x80000000. Out-of-line code (the per-lane finish, uncommon
traps) is outside the range already. Prints the counts the census in README.md reports.

Usage: census.py <dump> [METHOD]   (METHOD defaults to AsmDriver::loopExp)
"""

import re
import sys

LINE = re.compile(r"^\s*0x([0-9a-f]+):\s+(\S+)\s*(.*)$")


def last_compile(lines, method):
    start = None
    for i, line in enumerate(lines):
        if line.startswith("Compiled method (c2)") and method + " (" in line and " % " not in line:
            start = i
    if start is None:
        sys.exit(f"no standard c2 compile of {method}")
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if lines[i].startswith(("Compiled method", "[/Disassembly]")):
            end = i
            break
    return lines[start:end]


def instructions(block):
    out = []
    for line in block:
        m = LINE.match(line)
        if m:
            out.append((int(m.group(1), 16), m.group(2), m.group(3)))
    return out


def target(operands):
    m = re.match(r"0x([0-9a-f]+)$", operands.strip())
    return int(m.group(1), 16) if m else None


def main():
    dump = sys.argv[1]
    method = sys.argv[2] if len(sys.argv) > 2 else "AsmDriver::loopExp"
    with open(dump, encoding="utf-8", errors="replace") as f:
        insns = instructions(last_compile(f.read().splitlines(), method))
    best = None
    for addr, op, operands in insns:
        t = target(operands)
        if op.startswith("j") and op != "jmp" and t is not None and t < addr:
            if best is None or addr - t > best[1] - best[0]:
                best = (t, addr)
    if best is None:
        sys.exit("no backward branch")
    loop = [i for i in insns if best[0] <= i[0] <= best[1]]
    skipped = set()
    for addr, op, operands in loop:
        t = target(operands)
        if op.startswith("j") and op != "jmp" and t is not None and addr < t <= best[1]:
            skipped.update(a for a, _, _ in loop if addr < a < t)
    hot = [i for i in loop if i[0] not in skipped]
    ops = [op for _, op, _ in hot]
    spills = sum(1 for _, op, o in hot if "[rsp" in o)
    print(f"loop instructions:     {len(loop)}")
    print(f"skipped fix-up:        {len(skipped)}")
    print(f"hot path instructions: {len(hot)}")
    print(f"vdivpd:                {ops.count('vdivpd')}")
    print(f"blends:                {sum(1 for op in ops if 'blend' in op)}")
    print(f"broadcasts:            {sum(1 for op in ops if 'broadcast' in op)}")
    print(f"stack accesses:        {spills} (spill stores and reloads)")
    print(f"integer compares:      {sum(1 for op in ops if op.startswith('vpcmp'))}")
    print(f"double compares:       {sum(1 for op in ops if op.startswith('vcmp'))}")
    print(f"mask-register ops:     {sum(1 for op in ops if op.startswith('k'))}")
    for _, op, o in hot:
        print(f"    {op:16} {o}")


if __name__ == "__main__":
    main()
