(ns leaksurvey.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `leaksurvey.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [leaksurvey.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/segment s "segment-001"))))
    (is (true? (:registered? (store/segment s "segment-001"))))
    (is (true? (:verified? (store/segment s "segment-002"))))
    (is (true? (:registered? (store/segment s "segment-002"))))
    (is (false? (:verified? (store/segment s "segment-003"))))
    (is (false? (:registered? (store/segment s "segment-003"))))
    (is (= ["segment-001" "segment-002" "segment-003"] (mapv :id (store/all-segments s))))
    (is (true? (:verified? (store/sensor-unit s "acoustic-001"))))
    (is (true? (:registered? (store/sensor-unit s "acoustic-001"))))
    (is (false? (:verified? (store/sensor-unit s "acoustic-002"))))
    (is (false? (:registered? (store/sensor-unit s "acoustic-002"))))
    (is (= ["acoustic-001" "acoustic-002"] (mapv :id (store/all-sensor-units s))))
    (is (= 2 (count (store/readings-for-segment s "segment-001"))))
    (is (= 1 (count (store/readings-for-segment s "segment-002"))))
    (is (= 0 (count (store/readings-for-segment s "segment-003"))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/repair-history s)))
    (is (= [] (store/critical-leak-log s)))
    (is (zero? (store/next-repair-sequence s)))
    (is (zero? (store/next-escalation-sequence s)))
    (is (false? (store/repair-already-recommended? s "rpr-1")))
    (is (nil? (store/leak-survey-of s "leak-1")))))

(deftest fresh-store-has-no-segments-or-sensor-units
  (let [s (store/mem-store)]
    (is (= [] (store/all-segments s)))
    (is (nil? (store/segment s "segment-001")))
    (is (= [] (store/all-sensor-units s)))
    (is (nil? (store/sensor-unit s "acoustic-001")))
    (is (= [] (store/readings-for-segment s "segment-001")))))

(deftest segment-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :segment/upsert :path ["segment-001"]
                             :value {:pipe-diameter-mm 300}})
    (is (= 300 (:pipe-diameter-mm (store/segment s "segment-001"))))
    (is (true? (:verified? (store/segment s "segment-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/segment s "segment-001"))) "unrelated field preserved")))

(deftest leak-survey-set-replaces-not-merges
  (let [s (seeded)]
    (store/commit-record! s {:effect :leak/survey-set :path ["leak-1"]
                             :value {:segment-id "segment-001" :verdict :confirmed
                                     :sensor-basis ["reading-001" "reading-002"]}})
    (is (= :confirmed (:verdict (store/leak-survey-of s "leak-1"))))
    (store/commit-record! s {:effect :leak/survey-set :path ["leak-1"]
                             :value {:segment-id "segment-001" :verdict :no-leak
                                     :sensor-basis ["reading-001" "reading-002"]}})
    (is (= :no-leak (:verdict (store/leak-survey-of s "leak-1")))
        "the new filing fully replaces the stale one")))

(deftest repair-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :repair/schedule :path ["rpr-1"]
                               :value {:segment-id "segment-001" :leak-id "leak-1"
                                       :scheduled-date "2026-08-01"}})
      (is (= "RPR-000000" (get (first (store/repair-history s)) "record_id")))
      (is (= "repair-recommendation-draft" (get (first (store/repair-history s)) "kind")))
      (is (true? (:repair-recommended? (store/repair s "rpr-1"))))
      (is (= "segment-001" (:segment-id (store/repair s "rpr-1"))))
      (is (= 1 (count (store/repair-history s))))
      (is (= 1 (store/next-repair-sequence s)))
      (is (true? (store/repair-already-recommended? s "rpr-1"))))))

(deftest critical-leak-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :leak/escalate :path ["concern-1"]
                             :value {:segment-id "segment-001" :severity :high}})
    (is (= 1 (count (store/critical-leak-log s))))
    (is (= :high (:severity (first (store/critical-leak-log s)))))
    (store/commit-record! s {:effect :leak/escalate :path ["concern-2"]
                             :value {:segment-id "segment-002" :severity :moderate}})
    (is (= 2 (count (store/critical-leak-log s))) "append-only")))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
