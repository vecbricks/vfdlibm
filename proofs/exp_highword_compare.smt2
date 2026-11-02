; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; exp's high-word compares are one signed 64-bit compare of |x|'s bits (PROOFS.md, L3).
;
; e_exp.c (reference/fdlibm-5.3) reads hx = __HI(x) & 0x7fffffff, the high word of |x|,
; and compares it with constant words: hx >= 0x40862E42 (the filter), hx >= 0x7ff00000
; (non-finite), hx > 0x3fd62e42 (reduced) and hx < 0x3e300000 (tiny). The port
; (Lanes.hiAtLeast, hiBelow, hiAbove) instead compares absBits = bits & Long.MAX_VALUE,
; as a signed long, with fromHiBits(h) - 1 or fromHiBits(h), fromHiBits(h) being
; (h & 0xffffffffL) << 32. The claim: for every long bits - every double, NaNs of either
; sign and every payload included - and every h in [0, 0x7fffffff], the port's compare
; and the C's agree; hiAbove(h) is hiAtLeast(h + 1) for h < 0x7fffffff.
;
; What is proven: the claim for symbolic bits and h, each Java operator as java.smt2
; defines it (java_check.smt2 pins them to the JVM, the masks used here among them); then
; the claim at each word Exp compares with, rendered from Exp, so a word moved out of the
; range fails here. The C's hx is unsigned and its words are int literals, compared
; unsigned; both lie in [0, 2^31), where that compare is the integer one. What is not:
; that __HI(x) is the high int of the raw bits is the prelude's reading of fdlibm's
; macro, and that Lanes and Exp call these compares with these words is the code's text.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic QF_NIA)

; Lanes.absBits: bits & Long.MAX_VALUE, the low 63 bits.
(define-fun abs.bits ((b Int)) Int (jlong.and.low b 9223372036854775808))
; e_exp.c lines 112 and 114: hx = __HI(x) & 0x7fffffff.
(define-fun c.hx ((b Int)) Int (jint.and.low (l2i (jlong.shr b 32)) 2147483648))
; Lanes.fromHiBits: (h & 0xffffffffL) << 32, h an int widened to long.
(define-fun from.hi ((h Int)) Int (jlong.shl (jlong.and.low (i2l h) 4294967296) 32))
; Lanes.hiAtLeast, hiBelow and hiAbove on absBits.
(define-fun hi.at.least ((ab Int) (h Int)) Bool (> ab (jlong.sub (from.hi h) 1)))
(define-fun hi.below ((ab Int) (h Int)) Bool (< ab (from.hi h)))
(define-fun hi.above ((ab Int) (h Int)) Bool (hi.at.least ab (jint.add h 1)))
(define-fun h.in ((h Int)) Bool (and (<= 0 h) (<= h 2147483647)))

(echo "absBits is non-negative and its high word is the C's hx, every long: expect unsat")
(push 1)
(declare-const b Int)
(assert (jlong.in b))
(assert (not (and (<= 0 (abs.bits b)) (= (jlong.ushr (abs.bits b) 32) (c.hx b)))))
(check-sat)
(pop 1)

(echo "hiAtLeast(h) is hx >= h, every long and every h in [0, 0x7fffffff]: expect unsat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h)))
(assert (not (= (hi.at.least (abs.bits b) h) (>= (c.hx b) h))))
(check-sat)
(pop 1)

(echo "hiBelow(h) is hx < h, every long and every h in [0, 0x7fffffff]: expect unsat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h)))
(assert (not (= (hi.below (abs.bits b) h) (< (c.hx b) h))))
(check-sat)
(pop 1)

(echo "hiAbove(h) is hx > h, every long and every h in [0, 0x7fffffff): expect unsat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h) (< h 2147483647)))
(assert (not (= (hi.above (abs.bits b) h) (> (c.hx b) h))))
(check-sat)
(pop 1)

(echo "HX_FILTER = 0x40862e42: the port's compare is hx >= 0x40862e42: expect unsat")
(push 1)
(declare-const b Int) (define-fun h () Int 1082535490)
(assert (jlong.in b))
(assert (not (and (h.in h) (= (hi.at.least (abs.bits b) h) (>= (c.hx b) h)))))
(check-sat)
(pop 1)

(echo "HX_NON_FINITE = 0x7ff00000: the port's compare is hx >= 0x7ff00000: expect unsat")
(push 1)
(declare-const b Int) (define-fun h () Int 2146435072)
(assert (jlong.in b))
(assert (not (and (h.in h) (= (hi.at.least (abs.bits b) h) (>= (c.hx b) h)))))
(check-sat)
(pop 1)

(echo "HX_HALF_LN2 = 0x3fd62e42: the port's compare is hx > 0x3fd62e42: expect unsat")
(push 1)
(declare-const b Int) (define-fun h () Int 1071001154)
(assert (jlong.in b))
(assert (not (and (and (h.in h) (< h 2147483647)) (= (hi.above (abs.bits b) h) (> (c.hx b) h)))))
(check-sat)
(pop 1)

(echo "HX_TINY = 0x3e300000: the port's compare is hx < 0x3e300000: expect unsat")
(push 1)
(declare-const b Int) (define-fun h () Int 1043333120)
(assert (jlong.in b))
(assert (not (and (h.in h) (= (hi.below (abs.bits b) h) (< (c.hx b) h)))))
(check-sat)
(pop 1)

(echo "every NaN and infinity, either sign, is a filter and a non-finite lane: expect unsat")
(push 1)
(declare-const b Int)
(assert (and (jlong.in b) (= (jdouble.fromBits.E b) 972)))
(assert (not (and (hi.at.least (abs.bits b) 1082535490) (hi.at.least (abs.bits b) 2146435072))))
(check-sat)
(pop 1)

; The claim rests on each part of the compare: with the comparand changed by one, with
; hiAbove's + 1 dropped, or on the raw bits with the sign left set, a double is told
; apart.

(echo "refuted: hiAtLeast with comparand fromHiBits(h), not minus one: expect sat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h)))
(assert (not (= (> (abs.bits b) (from.hi h)) (>= (c.hx b) h))))
(check-sat)
(pop 1)

(echo "refuted: hiBelow with comparand fromHiBits(h) - 1: expect sat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h)))
(assert (not (= (< (abs.bits b) (jlong.sub (from.hi h) 1)) (< (c.hx b) h))))
(check-sat)
(pop 1)

(echo "refuted: hiAbove(h) as hiAtLeast(h), the + 1 dropped: expect sat")
(push 1)
(declare-const b Int) (declare-const h Int)
(assert (and (jlong.in b) (h.in h) (< h 2147483647)))
(assert (not (= (hi.at.least (abs.bits b) h) (> (c.hx b) h))))
(check-sat)
(pop 1)

(echo "refuted: the raw bits of the NaN 0xfff80000_00000000, unmasked, miss the filter: expect sat")
(push 1)
(define-fun b () Int (- 2251799813685248))
(assert (and (>= (c.hx b) 1082535490) (not (hi.at.least b 1082535490))))
(check-sat)
(pop 1)
