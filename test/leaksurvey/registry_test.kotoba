(ns leaksurvey.registry-test
  (:require [clojure.test :refer [deftest is]]
            [leaksurvey.registry :as r]))

;; ----------------------------- segment-verified? / segment-registered? / segment-ready? -----------------------------

(deftest segment-is-verified-when-flagged
  (is (true? (r/segment-verified? {:id "s1" :verified? true}))))

(deftest segment-is-not-verified-when-false-or-missing
  (is (false? (r/segment-verified? {:id "s1" :verified? false})))
  (is (false? (r/segment-verified? {:id "s1"}))))

(deftest segment-is-registered-when-flagged
  (is (true? (r/segment-registered? {:registered? true}))))

(deftest segment-is-not-registered-when-false-or-missing
  (is (false? (r/segment-registered? {:registered? false})))
  (is (false? (r/segment-registered? {}))))

(deftest segment-ready-requires-both
  (is (true? (r/segment-ready? {:verified? true :registered? true})))
  (is (false? (r/segment-ready? {:verified? true :registered? false})))
  (is (false? (r/segment-ready? {:verified? false :registered? true})))
  (is (false? (r/segment-ready? {}))))

;; ----------------------------- sensor-unit-verified? / sensor-unit-registered? / sensor-unit-ready? -----------------------------

(deftest sensor-unit-is-verified-when-flagged
  (is (true? (r/sensor-unit-verified? {:id "u1" :verified? true}))))

(deftest sensor-unit-is-not-verified-when-false-or-missing
  (is (false? (r/sensor-unit-verified? {:id "u1" :verified? false})))
  (is (false? (r/sensor-unit-verified? {:id "u1"}))))

(deftest sensor-unit-is-registered-when-flagged
  (is (true? (r/sensor-unit-registered? {:registered? true}))))

(deftest sensor-unit-is-not-registered-when-false-or-missing
  (is (false? (r/sensor-unit-registered? {:registered? false})))
  (is (false? (r/sensor-unit-registered? {}))))

(deftest sensor-unit-ready-requires-both
  (is (true? (r/sensor-unit-ready? {:verified? true :registered? true})))
  (is (false? (r/sensor-unit-ready? {:verified? true :registered? false})))
  (is (false? (r/sensor-unit-ready? {:verified? false :registered? true})))
  (is (false? (r/sensor-unit-ready? {}))))

;; ----------------------------- pipe-material-valid? -----------------------------

(deftest known-pipe-materials-are-valid
  (doseq [m [:ductile-iron :cast-iron :pvc :hdpe :concrete :steel :asbestos-cement :copper]]
    (is (r/pipe-material-valid? m))))

(deftest fabricated-pipe-material-is-invalid
  (is (not (r/pipe-material-valid? :unobtanium-pipe)))
  (is (not (r/pipe-material-valid? nil))))

;; ----------------------------- flow-loss-valid? -----------------------------

(deftest typical-flow-loss-is-valid
  (is (r/flow-loss-valid? 0))
  (is (r/flow-loss-valid? 42.5))
  (is (r/flow-loss-valid? 10000.0)))

(deftest negative-flow-loss-is-invalid
  (is (not (r/flow-loss-valid? -1))))

(deftest excessive-flow-loss-is-invalid
  (is (not (r/flow-loss-valid? 999999.0)))
  (is (not (r/flow-loss-valid? 10001))))

(deftest non-numeric-or-missing-flow-loss-is-invalid
  (is (not (r/flow-loss-valid? nil)))
  (is (not (r/flow-loss-valid? "42.5"))))

;; ----------------------------- register-repair-recommendation -----------------------------

(deftest repair-recommendation-is-a-draft-not-a-real-actuation
  (let [result (r/register-repair-recommendation "rpr-1" "segment-001" "leak-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest repair-recommendation-assigns-repair-number
  (let [result (r/register-repair-recommendation "rpr-1" "segment-001" "leak-1" 7)]
    (is (= (get result "repair_number") "RPR-000007"))
    (is (= (get-in result ["record" "recommendation_id"]) "rpr-1"))
    (is (= (get-in result ["record" "segment_id"]) "segment-001"))
    (is (= (get-in result ["record" "leak_id"]) "leak-1"))
    (is (= (get-in result ["record" "kind"]) "repair-recommendation-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest repair-recommendation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-repair-recommendation "" "segment-001" "leak-1" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-repair-recommendation "rpr-1" "" "leak-1" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-repair-recommendation "rpr-1" "segment-001" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-repair-recommendation "rpr-1" "segment-001" "leak-1" -1))))

;; ----------------------------- register-escalation -----------------------------

(deftest escalation-is-a-draft-not-a-real-clearance
  (let [result (r/register-escalation "concern-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest escalation-assigns-escalation-number
  (let [result (r/register-escalation "concern-1" 7)]
    (is (= (get result "escalation_number") "ESC-000007"))
    (is (= (get-in result ["record" "concern_id"]) "concern-1"))
    (is (= (get-in result ["record" "kind"]) "critical-leak-escalation-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest escalation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "concern-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-repair-recommendation "rpr-1" "segment-001" "leak-1" 0)
        hist (r/append [] c1)
        c2 (r/register-repair-recommendation "rpr-2" "segment-002" "leak-2" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "RPR-000000" (get-in hist2 [0 "record_id"])))
    (is (= "RPR-000001" (get-in hist2 [1 "record_id"])))))
