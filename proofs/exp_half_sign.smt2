; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; exp's halF[xsb] is 0.5 with x's sign bit (PROOFS.md, L5).
;
; e_exp.c (reference/fdlibm-5.3) takes halF[xsb] from its table halF[2] = {0.5, -0.5}, with
; xsb = (hx >> 31) & 1 for the unsigned hx = __HI(x) (lines 112-113). The port (Exp)
; computes the double whose bits are (bits & Long.MIN_VALUE) | HALF_BITS. The claim: for
; every long bits, that is the raw bits of the table's entry at xsb, and they are the
; doubles 0.5 and -0.5.
;
; What is proven: & and | as SMT-LIB's bit-vector operators on the 64-bit two's-complement
; pattern of bits (int2bv), read back as a signed long, so no definition of & or | is
; written here; xsb as the C computes it, over java.smt2's int operators; and the two
; entries' S, M and E as java.smt2 reads raw bits (jdouble.fromBits, pinned to the JVM by
; java_check.smt2), with M * 2^E exactly 1/2. HALF_BITS is rendered from Exp, and the
; table's raw bits from Java's 0.5 and -0.5, the C's literals. What is not: that the port
; reinterprets those bits as the lane's double (DoubleVector's viewAsFloatingLanes) is the
; code's text.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; The port's halF bits: (bits & Long.MIN_VALUE) | HALF_BITS over bit-vectors, read signed.
(define-fun port.half ((b Int) (half (_ BitVec 64))) Int
  (let ((r (bvor (bvand ((_ int2bv 64) b) #x8000000000000000) half)))
    (- (bv2nat r) (ite (bvslt r #x0000000000000000) 18446744073709551616 0))))
; e_exp.c line 113: xsb = (hx >> 31) & 1, hx = __HI(x) unsigned, so the shift is >>>.
(define-fun c.xsb ((b Int)) Int (jint.and.low (jint.ushr (l2i (jlong.shr b 32)) 31) 2))
; The table halF[2] = {0.5, -0.5}, as raw bits, at xsb.
(define-fun c.half ((b Int)) Int (ite (= (c.xsb b) 1) (- 4620693217682128896) 4602678819172646912))

(echo "HALF_BITS = 0x3fe00000_00000000: the port's bits are halF[xsb]'s, every long: expect unsat")
(push 1)
(declare-const b Int)
(assert (jlong.in b))
(assert (not (= (port.half b #x3fe0000000000000) (c.half b))))
(check-sat)
(pop 1)

(echo "the C's xsb is 1 exactly when the bits are negative, the sign bit set: expect unsat")
(push 1)
(declare-const b Int)
(assert (jlong.in b))
(assert (not (= (= (c.xsb b) 1) (< b 0))))
(check-sat)
(pop 1)

(echo "halF's entries are the doubles 0.5 and -0.5: S, M, E and M * 2^E = 1 / 2: expect unsat")
(push 1)
(assert (not (and
  (= (jdouble.fromBits.S 4602678819172646912) 1) (= (jdouble.fromBits.S (- 4620693217682128896)) (-
    1))
  (= (jdouble.fromBits.M 4602678819172646912) 4503599627370496) (= (jdouble.fromBits.M (-
    4620693217682128896)) 4503599627370496)
  (= (jdouble.fromBits.E 4602678819172646912) (- 53)) (= (jdouble.fromBits.E (-
    4620693217682128896)) (- 53))
  (= (* 2 4503599627370496) 9007199254740992))))
(check-sat)
(pop 1)

(echo "not vacuous: -1.0's bits give -0.5's and 1.0's give 0.5's: expect sat")
(push 1)
(assert (and (= (port.half (- 4616189618054758400) #x3fe0000000000000) (- 4620693217682128896))
  (= (port.half 4607182418800017408 #x3fe0000000000000) 4602678819172646912)))
(check-sat)
(pop 1)

; The constant changed by one, and the sign mask moved to the next bit down: some double
; tells each apart from the table.

(echo "refuted: HALF_BITS + 1 = 0x3fe00000_00000001 as the constant: expect sat")
(push 1)
(declare-const b Int)
(assert (jlong.in b))
(assert (not (= (port.half b #x3fe0000000000001) (c.half b))))
(check-sat)
(pop 1)

(echo "refuted: the mask 0x40000000_00000000, not the sign bit: expect sat")
(push 1)
(declare-const b Int)
(assert (jlong.in b))
(define-fun r () (_ BitVec 64) (bvor (bvand ((_ int2bv 64) b) #x4000000000000000)
  #x3fe0000000000000))
(assert (not (= (- (bv2nat r) (ite (bvslt r #x0000000000000000) 18446744073709551616 0)) (c.half
  b))))
(check-sat)
(pop 1)
