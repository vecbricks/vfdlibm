; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; exp's k == 0 path is the general tail with hi = x, lo = +0, k = 0 (PROOFS.md, L1): the
; hinges that are IEEE facts, not bounds on the polynomial.
;
; e_exp.c (reference/fdlibm-5.3) returns one-((x*c)/(c-2.0)-x) for hx in [0x3e300000,
; 0x3fd62e42] (line 147), and the port (Exp) computes the general tail there with k = 0 and
; t = +0: hi = x - fl(t*ln2HI[0]), lo = fl(t*ln2LO[0]), r = hi - lo,
; y = one-((lo-(r*c)/(2.0-c))-hi), and y's bits plus k << 52. The domain's ends,
; 0x3e300000_00000000 and 0x3fd62e42_ffffffff, are rendered from Exp's words.
;
; What is proven:
;   - Section A: a nonzero double less +0 is itself and +0 less a double q is -q, in
;     the PROOFS.md steps 1 and 3: the exact value is the double (or its negation),
;     which jdouble.near admits in every binade at once, and admits no other double in
;     its own binade or the ones next to it, for every double, either sign, of the binades
;     2^-60 to 2^-2. Those cover the domain's x, binades 2^-28 to 2^-2 (pinned below from
;     Exp's words), and q, whose binades from 2^-59 to 2^-4 follow from H2 and H3's bounds.
;   - Section B: the scale-back adds 0 << 52 = 0 to y's bits, for every long, and k = 0
;     is not below Exp's K_MIN_NORMAL, so neither the subnormal scale-back nor the finish
;     takes the lane (step 4); with k = 1 the bits would move.
;   - Section C: H1's witness. At the domain's top double the formula's
;     s = fl(fl(invln2 * x) + 0.5) rounds into binade 2^0, so (int) s = 1, not 0: the
;     high-word test, not the formula, forces k = 0 (exp_highword_compare.smt2 proves
;     that test).
;
; What is not machine-checked:
;   - H2 and H3, the bounds 0.94|x| <= |c| < 0.37 with c of x's sign, and q positive and
;     normal: they bound the polynomial's eight roundings, products of unknowns, and stay
;     the informal proof of PROOFS.md with ProofsTest's samples. So q's binades rest on
;     them, and so does step 2's hypothesis, c in [-2, 1], under which sign_symmetry.smt2
;     proves fl(c - 2.0) = -fl(2.0 - c) and the quotient's sign symmetry.
;   - fl(+0 * v) = +0 for a positive finite v, IEEE 754's sign rule for an exact zero
;     (section 6.3): zero is not in the prelude. Section A checks only that ln2HI[0] and
;     ln2LO[0] are positive and finite, which the rule needs.
;   - That D2I of +0.0 is 0 and I2D of 0 is +0.0 (JLS 5.1.3, 5.1.2), that the port puts
;     +0.0 in the cast's argument exactly on the lanes the high-word test leaves out, and
;     that the C's steps after these are the port's on the same operands: the code's text.
;   - That jdouble.near admits no double in a binade further off than the next one: the
;     arithmetic of rationals, as in sign_symmetry.smt2.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; Section A: x - (+0) = x and +0 - q = -q. The exact difference is the double S * M * 2^E
; or its negation, a / b with a = S * M * sn and b = sd.

(echo "a double, either sign, is a double jdouble.near admits for itself, any binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))
(assert (not (jdouble.near (* S M sn) sd S M sn sd)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-60, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
  5192296858534827628530496329220096) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
    10384593717069655257060992658440192)
  (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
    2596148429267413814265248164610048)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-59, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
  2596148429267413814265248164610048) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
    5192296858534827628530496329220096)
  (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
    1298074214633706907132624082305024)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-58, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
  1298074214633706907132624082305024) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
    2596148429267413814265248164610048)
  (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
    649037107316853453566312041152512)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-57, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
  649037107316853453566312041152512) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
    1298074214633706907132624082305024)
  (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
    324518553658426726783156020576256)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-56, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
  324518553658426726783156020576256) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
    649037107316853453566312041152512)
  (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
    162259276829213363391578010288128)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-55, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
  162259276829213363391578010288128) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
    324518553658426726783156020576256)
  (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
    81129638414606681695789005144064)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-54, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
  81129638414606681695789005144064) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
    162259276829213363391578010288128)
  (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
    40564819207303340847894502572032)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-53, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
  40564819207303340847894502572032) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
    81129638414606681695789005144064)
  (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
    20282409603651670423947251286016)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-52, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
  20282409603651670423947251286016) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
    40564819207303340847894502572032)
  (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
    10141204801825835211973625643008)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-51, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
  10141204801825835211973625643008) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
    20282409603651670423947251286016)
  (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
    5070602400912917605986812821504)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-50, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
  5070602400912917605986812821504) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
    10141204801825835211973625643008)
  (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
    2535301200456458802993406410752)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-49, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
  2535301200456458802993406410752) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
    5070602400912917605986812821504)
  (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
    1267650600228229401496703205376)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-48, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
  1267650600228229401496703205376) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
    2535301200456458802993406410752)
  (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
    633825300114114700748351602688)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-47, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
  633825300114114700748351602688) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
    1267650600228229401496703205376)
  (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
    316912650057057350374175801344)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-46, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
  316912650057057350374175801344) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
    633825300114114700748351602688)
  (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
    158456325028528675187087900672)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-45, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
  158456325028528675187087900672) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
    316912650057057350374175801344)
  (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
    79228162514264337593543950336)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-44, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
  79228162514264337593543950336) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
    158456325028528675187087900672)
  (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
    39614081257132168796771975168)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-43, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
  39614081257132168796771975168) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
    79228162514264337593543950336)
  (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
    19807040628566084398385987584)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-42, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
  19807040628566084398385987584) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
    39614081257132168796771975168)
  (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
    9903520314283042199192993792)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-41, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
  9903520314283042199192993792) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
    19807040628566084398385987584)
  (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
    4951760157141521099596496896)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-40, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
  4951760157141521099596496896) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
    9903520314283042199192993792)
  (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
    2475880078570760549798248448)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-39, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
  2475880078570760549798248448) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
    4951760157141521099596496896)
  (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
    1237940039285380274899124224)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-38, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
  1237940039285380274899124224) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
    2475880078570760549798248448)
  (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
    618970019642690137449562112)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-37, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
  618970019642690137449562112) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
    1237940039285380274899124224)
  (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
    309485009821345068724781056)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-36, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
  309485009821345068724781056) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
    618970019642690137449562112)
  (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
    154742504910672534362390528)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-35, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
  154742504910672534362390528) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
    309485009821345068724781056)
  (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
    77371252455336267181195264)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-34, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
  77371252455336267181195264) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
    154742504910672534362390528)
  (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
    38685626227668133590597632)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-33, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1
  38685626227668133590597632) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1 77371252455336267181195264)
  (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1
    19342813113834066795298816)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-32, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1
  19342813113834066795298816) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1 38685626227668133590597632)
  (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1
    9671406556917033397649408)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-31, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1
  9671406556917033397649408) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1 19342813113834066795298816)
  (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1 4835703278458516698824704)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-30, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1
  4835703278458516698824704) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1 9671406556917033397649408)
  (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1 2417851639229258349412352)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-29, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1
  2417851639229258349412352) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1 4835703278458516698824704)
  (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1 1208925819614629174706176)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-28, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1
  1208925819614629174706176) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1 2417851639229258349412352)
  (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1 604462909807314587353088)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-27, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1
  604462909807314587353088) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1 1208925819614629174706176)
  (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1 302231454903657293676544)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-26, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1
  302231454903657293676544) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1 604462909807314587353088)
  (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1 151115727451828646838272)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-25, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1
  151115727451828646838272) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1 302231454903657293676544)
  (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1 75557863725914323419136)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-24, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1
  75557863725914323419136) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1 151115727451828646838272)
  (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1 37778931862957161709568)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-23, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1
  37778931862957161709568) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1 75557863725914323419136)
  (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1 18889465931478580854784)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-22, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1
  18889465931478580854784) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1 37778931862957161709568)
  (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1 9444732965739290427392)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-21, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1
  9444732965739290427392) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1 18889465931478580854784)
  (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1 4722366482869645213696)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-20, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1
  4722366482869645213696) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1 9444732965739290427392)
  (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1 2361183241434822606848)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-19, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1
  2361183241434822606848) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1 4722366482869645213696)
  (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1 1180591620717411303424)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-18, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1
  1180591620717411303424) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1 2361183241434822606848)
  (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1 590295810358705651712)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-17, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1
  590295810358705651712) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1 1180591620717411303424)
  (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1 295147905179352825856)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-16, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1
  295147905179352825856) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1 590295810358705651712)
  (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1 147573952589676412928)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-15, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1
  147573952589676412928) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1 295147905179352825856)
  (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1 73786976294838206464)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-14, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1
  73786976294838206464) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1 147573952589676412928)
  (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1 36893488147419103232)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-13, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1
  36893488147419103232) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1 73786976294838206464)
  (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1 18446744073709551616)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-12, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1
  18446744073709551616) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1 36893488147419103232)
  (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1 9223372036854775808)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-11, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1
  9223372036854775808) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1 18446744073709551616)
  (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1 4611686018427387904)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-10, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1
  4611686018427387904) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1 9223372036854775808)
  (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1 2305843009213693952)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-9, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1
  2305843009213693952) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1 4611686018427387904)
  (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1 1152921504606846976)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-8, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1
  1152921504606846976) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1 2305843009213693952)
  (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1 576460752303423488)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-7, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 576460752303423488)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 1152921504606846976)
  (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 288230376151711744)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-6, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 288230376151711744)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 576460752303423488)
  (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 144115188075855872)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-5, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 144115188075855872)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 288230376151711744)
  (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 72057594037927936)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-4, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 72057594037927936)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 144115188075855872)
  (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 36028797018963968)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-3, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 36028797018963968)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 72057594037927936)
  (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 18014398509481984)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-2, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 18014398509481984)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 36028797018963968)
  (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 9007199254740992)))
(check-sat)
(pop 1)

(echo "the domain's ends 0x3e300000_00000000, 0x3fd62e42_ffffffff are in 2^-28, 2^-2: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.E 4481081629233643520) (- 80))
  (= (jdouble.fromBits.E 4599914934703226879) (- 54)))))
(check-sat)
(pop 1)

(echo "ln2HI[0] and ln2LO[0] are positive and finite, as the zero product needs: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4604418534311723008) 1) (< (jdouble.fromBits.E
  4604418534311723008) 972)
  (= (jdouble.fromBits.S 4461442080421002358) 1) (< (jdouble.fromBits.E 4461442080421002358) 972))))
(check-sat)
(pop 1)

(echo "not vacuous: the double 1.5 * 2^-2 admits itself in its binade: expect sat")
(push 1)
(declare-const M2 Int)
(assert (jdouble.near 6755399441055744 18014398509481984 1 M2 1 18014398509481984))
(check-sat)
(pop 1)

(echo "refuted: a double less 2^-54, one step of its binade, is itself: expect sat")
(push 1)
(declare-const M Int) (declare-const M2 Int)
(assert (jdouble.M.in M))
(assert (jdouble.near (- M 1) 18014398509481984 1 M2 1 18014398509481984))
(assert (not (= M2 M)))
(check-sat)
(pop 1)

; Section B: the scale-back with k = 0.

(echo "y's bits plus 0 << 52 are y's bits, every long: expect unsat")
(push 1)
(declare-const y Int)
(assert (jlong.in y))
(assert (not (= (jlong.add y (jlong.shl 0 52)) y)))
(check-sat)
(pop 1)

(echo "k = 0 is not below K_MIN_NORMAL = -1021, the scale-back's plain path: expect unsat")
(push 1)
(assert (< 0 (- 1021)))
(check-sat)
(pop 1)

(echo "refuted: with k = 1 the scale-back moves some y's bits: expect sat")
(push 1)
(declare-const y Int)
(assert (jlong.in y))
(assert (not (= (jlong.add y (jlong.shl 1 52)) y)))
(check-sat)
(pop 1)

; Section C: H1's witness, at the domain's top double x: the formula's k is 1. The
; product rounds into its binade (or its neighbours, which are refused), then the sum
; with 0.5 rounds into binade 2^0, and not below it.

(echo "at the top, invln2 * x rounds into 2^-1 only, positive: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 S2
  M2 1 18014398509481984)
  (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 (- 1) M2 1
    9007199254740992)))
(check-sat)
(pop 1)

(echo "at the top, s rounds into 2^0, so (int) s = 1, and not into 2^-1, k = 0: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at the top, s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496))
(check-sat)
(pop 1)
