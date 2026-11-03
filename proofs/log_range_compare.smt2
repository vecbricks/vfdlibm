; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; log's common and subnormal lanes by one compare of the bits (PROOFS.md, L9).
;
; e_log.c (reference/fdlibm-5.3) tests the signed int hx = __HI(x): hx < 0x00100000
; (line 100) and hx >= 0x7ff00000 (line 107) leave the common lanes, and on the first branch
; ((hx&0x7fffffff)|lx) == 0 (line 101) and hx < 0 (line 103) leave the subnormals. The port
; (Log's body) tests lo <= bits < hi on the raw bits as bits + (Long.MIN_VALUE - lo) <
; Long.MIN_VALUE + (hi - lo), signed, with [lo, hi) = [COMMON_LO, COMMON_HI) =
; [0x00100000_00000000, 0x7ff00000_00000000) for the common lanes
; and [1, COMMON_LO) for the subnormals, rendered from Log.
;
; Java's int and long arithmetic is two's complement modulo 2^32 and 2^64 (JLS 4.2.2), so
; each operator here is its SMT-LIB bit-vector counterpart: + - as bvadd, bvsub; & | ^ as
; bvand, bvor, bvxor (JLS 15.22.1); << >> >>> by a constant count as bvshl, bvashr,
; bvlshr (JLS 15.19); a signed compare as bvslt and its kin; (int) of a long as the low 32
; bits, extract; (long) of an int as sign_extend (JLS 5.1.3, 5.1.2). The definitions are
; pinned to the JVM: a ground check per sampled bit pattern compares each C and port
; definition with the value Java computed while rendering. Java's integers are used here
; rather than java.smt2's: mixing the two through int2bv left both solvers at unknown for
; a minute, where over bit-vectors alone they decide each check at once.
;
; What is proven, for every long bits (every double, NaNs of either sign included): the
; biased compare is lo <= bits < hi for every lo <= hi; the common range is the C's two
; tests, and the subnormal range the C's three. The sat checks refute the compare without
; the bias (a negative bits passes it) and COMMON_LO and COMMON_HI moved by one.
;
; What is not: that __HI(x) and __LO(x) are the raw bits' high int and low word (the
; prelude's reading of fdlibm's macros), and that the port compares with these bounds,
; the code's text.
;
; The logic is ALL: dev/prove.sh inserts java.smt2's integer prelude, unused here.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; The biased compare, and the C's tests on hx = __HI(x) signed and lx = __LO(x).
(define-fun in.range ((b (_ BitVec 64)) (lo (_ BitVec 64)) (hi (_ BitVec 64))) Bool
  (bvslt (bvadd b (bvsub #x8000000000000000 lo)) (bvadd #x8000000000000000 (bvsub hi lo))))
(define-fun c.hx ((b (_ BitVec 64))) (_ BitVec 32) ((_ extract 63 32) b))
(define-fun c.lx ((b (_ BitVec 64))) (_ BitVec 32) ((_ extract 31 0) b))

(echo "the compare and the C's tests are Java's at sampled bits, on 93 operand sets: expect unsat")
(push 1)
(assert (not (and
  (= (in.range #x0000000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x0000000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x0000000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x0000000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x8000000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x8000000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x8000000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x8000000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x0000000000000001 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x0000000000000001 #x0000000000000001 #x0010000000000000) true)
  (= (let ((b #x0000000000000001)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x0000000000000001)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) true)
  (= (in.range #x0010000000000000 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x0010000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x0010000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x0010000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3fe0000000000000 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3fe0000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3fe0000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3fe0000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff0000000000000 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff0000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff0000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff0000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff6a09e667f3bcd #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff6a09e667f3bcd #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff6a09e667f3bcd)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff6a09e667f3bcd)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3fe6a09e667f3bcd #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3fe6a09e667f3bcd #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3fe6a09e667f3bcd)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3fe6a09e667f3bcd)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x4008000000000000 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x4008000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x4008000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x4008000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x7e37e43c8800759c #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x7e37e43c8800759c #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7e37e43c8800759c)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x7e37e43c8800759c)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x7fefffffffffffff #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x7fefffffffffffff #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7fefffffffffffff)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x7fefffffffffffff)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x7ff0000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x7ff0000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7ff0000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x7ff0000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x7ff8000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x7ff8000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7ff8000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x7ff8000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #xbff0000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #xbff0000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #xbff0000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #xbff0000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #xfff0000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #xfff0000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #xfff0000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #xfff0000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff6147a12345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff6147a12345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff6147a12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff6147a12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff6147912345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff6147912345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff6147912345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff6147912345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3fe6b85112345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3fe6b85112345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3fe6b85112345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3fe6b85112345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3fe6b85212345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3fe6b85212345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3fe6b85212345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3fe6b85212345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3fe6a09c12345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3fe6a09c12345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3fe6a09c12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3fe6a09c12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff6a09b12345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff6a09b12345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff6a09b12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff6a09b12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3feffffe12345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3feffffe12345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3feffffe12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3feffffe12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x3ff0000112345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x3ff0000112345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x3ff0000112345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x3ff0000112345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x000fffff12345678 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x000fffff12345678 #x0000000000000001 #x0010000000000000) true)
  (= (let ((b #x000fffff12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x000fffff12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) true)
  (= (in.range #x7fefffff12345678 #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x7fefffff12345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7fefffff12345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x7fefffff12345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x8010000012345678 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x8010000012345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x8010000012345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x8010000012345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #xfff8000012345678 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #xfff8000012345678 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #xfff8000012345678)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #xfff8000012345678)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x000fffffffffffff #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x000fffffffffffff #x0000000000000001 #x0010000000000000) true)
  (= (let ((b #x000fffffffffffff)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x000fffffffffffff)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) true)
  (= (in.range #x7fefffffffffffff #x0010000000000000 #x7ff0000000000000) true) (= (in.range
    #x7fefffffffffffff #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x7fefffffffffffff)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) true)
  (= (let ((b #x7fefffffffffffff)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #x8000000000000000 #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #x8000000000000000 #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #x8000000000000000)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #x8000000000000000)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
  (= (in.range #xffffffffffffffff #x0010000000000000 #x7ff0000000000000) false) (= (in.range
    #xffffffffffffffff #x0000000000000001 #x0010000000000000) false)
  (= (let ((b #xffffffffffffffff)) (and (not (bvslt (c.hx b) #x00100000)) (not (bvsge (c.hx b)
    #x7ff00000)))) false)
  (= (let ((b #xffffffffffffffff)) (and (bvslt (c.hx b) #x00100000) (not (= (bvor (bvand (c.hx b)
    #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b) #x00000000)))) false)
)))
(check-sat)
(pop 1)

(echo "the biased compare is lo <= bits < hi, every long and lo <= hi: expect unsat")
(push 1)
(declare-const b (_ BitVec 64))
(declare-const lo (_ BitVec 64)) (declare-const hi (_ BitVec 64))
(assert (bvsle lo hi))
(assert (not (= (in.range b lo hi) (and (bvsle lo b) (bvslt b hi)))))
(check-sat)
(pop 1)

(echo "the common range is the C's: not hx < 0x100000 and not hx >= 0x7ff00000: expect unsat")
(push 1)
(declare-const b (_ BitVec 64))
(assert (not (= (in.range b #x0010000000000000 #x7ff0000000000000) (and (not (bvslt (c.hx b)
  #x00100000)) (not (bvsge (c.hx b) #x7ff00000))))))
(check-sat)
(pop 1)

(echo "the subnormal range [1, COMMON_LO) is the C's three tests: expect unsat")
(push 1)
(declare-const b (_ BitVec 64))
(assert (not (= (in.range b #x0000000000000001 #x0010000000000000) (and (bvslt (c.hx b) #x00100000)
  (not (= (bvor (bvand (c.hx b) #x7fffffff) (c.lx b)) #x00000000)) (not (bvslt (c.hx b)
  #x00000000))))))
(check-sat)
(pop 1)

; Each part is needed.

(echo "refuted: without the bias a negative bits passes: expect sat")
(push 1)
(declare-const b (_ BitVec 64))
(assert (and (bvslt (bvsub b #x0010000000000000) #x7fe0000000000000) (not (and (not (bvslt (c.hx b)
  #x00100000)) (not (bvsge (c.hx b) #x7ff00000))))))
(check-sat)
(pop 1)

(echo "refuted: COMMON_LO - 1 as the bottom: expect sat")
(push 1)
(declare-const b (_ BitVec 64))
(assert (not (= (in.range b #x000fffffffffffff #x7ff0000000000000) (and (not (bvslt (c.hx b)
  #x00100000)) (not (bvsge (c.hx b) #x7ff00000))))))
(check-sat)
(pop 1)

(echo "refuted: COMMON_HI + 1 as the top: expect sat")
(push 1)
(declare-const b (_ BitVec 64))
(assert (not (= (in.range b #x0010000000000000 #x7ff0000000000001) (and (not (bvslt (c.hx b)
  #x00100000)) (not (bvsge (c.hx b) #x7ff00000))))))
(check-sat)
(pop 1)
