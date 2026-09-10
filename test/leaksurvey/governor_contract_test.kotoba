(ns leaksurvey.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate a valve/main directly... does NOT
  authorize or execute a real repair... does NOT self-issue a
  water-utility regulatory compliance filing... never accepts a
  self-attested leak verdict') implemented faithfully. The single
  invariant under test:

    Survey Advisor never schedules a repair recommendation, files an
    ungrounded leak verdict, or escalates a critical-leak concern the
    Water Infrastructure Governor would reject;
    `:leak-survey`/`:schedule-repair-recommendation`/
    `:escalate-critical-leak` NEVER auto-commit at any phase;
    `:log-pipe-condition` (no physical/financial risk) MAY auto-commit
    when clean; and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [leaksurvey.store :as store]
            [leaksurvey.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :survey-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- confirm-leak-1!
  "Test fixture: files + approves a GROUNDED :confirmed leak-survey for
  leak-1 on segment-001, so repair-recommendation scenarios have a real
  on-file confirmed leak to reference."
  [actor tid]
  (exec-op actor tid
           {:op :leak-survey :effect :propose :subject "leak-1"
            :value {:segment-id "segment-001" :verdict :confirmed
                    :sensor-basis ["reading-001" "reading-002"]
                    :flow-loss-lpm 42.5}}
           coordinator)
  (approve! actor tid))

(deftest clean-log-pipe-condition-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-pipe-condition :effect :propose :subject "segment-001"
                   :patch {:pipe-diameter-mm 300}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 300 (:pipe-diameter-mm (store/segment db "segment-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest grounded-confirmed-leak-survey-escalates-then-commits
  (testing "leak-survey is never in any phase's :auto set -- always human approval, even when grounded and clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :leak-survey :effect :propose :subject "leak-1"
                     :value {:segment-id "segment-001" :verdict :confirmed
                             :sensor-basis ["reading-001" "reading-002"]
                             :flow-loss-lpm 42.5}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :confirmed (:verdict (store/leak-survey-of db "leak-1"))))))))

(deftest schedule-repair-recommendation-always-needs-approval
  (testing "repair-recommendation scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          _ (confirm-leak-1! actor "seed-a")
          res (exec-op actor "t3"
                    {:op :schedule-repair-recommendation :effect :propose :subject "rpr-1"
                     :value {:segment-id "segment-001" :leak-id "leak-1"
                             :scheduled-date "2026-08-01" :actuate-valve? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:repair-recommended? (store/repair db "rpr-1"))))
        (is (= 1 (count (store/repair-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :log-pipe-condition :effect :direct-write :subject "segment-001"
                     :patch {:pipe-diameter-mm 300}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t5" {:op :operate-main-valve :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest ungrounded-confirmed-verdict-is-held-and-unoverridable
  (testing "a CONFIRMED verdict citing only ONE of the two required sensor metrics -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :leak-survey :effect :propose :subject "leak-2"
                     :value {:segment-id "segment-002" :verdict :confirmed
                             :sensor-basis ["reading-003"]
                             :flow-loss-lpm 10.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:leak-verdict-ungrounded} (-> (store/ledger db) last :basis)))
      (is (nil? (store/leak-survey-of db "leak-2")) "an ungrounded verdict never lands in the SSoT"))))

(deftest needs-more-data-verdict-is-exempt-from-grounding
  (testing "a :needs-more-data verdict needs no sensor basis -- the honest 'I don't know yet' outcome, still always escalates"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :leak-survey :effect :propose :subject "leak-3"
                     :value {:segment-id "segment-003" :verdict :needs-more-data
                             :sensor-basis []}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t7")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :needs-more-data (:verdict (store/leak-survey-of db "leak-3"))))))))

(deftest segment-not-verified-is-held-and-unoverridable
  (testing "recommending a repair against an unverified/unregistered segment -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          _ (confirm-leak-1! actor "seed-b")
          res (exec-op actor "t8"
                    {:op :schedule-repair-recommendation :effect :propose :subject "rpr-2"
                     :value {:segment-id "segment-003" :leak-id "leak-1"
                             :scheduled-date "2026-08-01" :actuate-valve? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:segment-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/repair-history db))))))

(deftest leak-not-confirmed-on-file-is-held-and-unoverridable
  (testing "recommending a repair citing a leak-id that was never confirmed on file -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t9"
                    {:op :schedule-repair-recommendation :effect :propose :subject "rpr-3"
                     :value {:segment-id "segment-001" :leak-id "leak-never-surveyed"
                             :scheduled-date "2026-08-01" :actuate-valve? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:leak-not-confirmed-on-file} (-> (store/ledger db) last :basis)))
      (is (empty? (store/repair-history db))))))

(deftest valve-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-valve? true -> HOLD, PERMANENT, never reaches request-approval even though the segment is verified and the leak confirmed"
    (let [[db actor] (fresh)
          _ (confirm-leak-1! actor "seed-c")
          res (exec-op actor "t10"
                    {:op :schedule-repair-recommendation :effect :propose :subject "rpr-4"
                     :value {:segment-id "segment-001" :leak-id "leak-1"
                             :scheduled-date "2026-09-01" :actuate-valve? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:valve-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/repair-history db))))))

(deftest schedule-repair-recommendation-double-recommendation-is-held
  (testing "recommending the SAME repair twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (confirm-leak-1! actor "seed-d")
          _ (exec-op actor "t11a" {:op :schedule-repair-recommendation :effect :propose :subject "rpr-1"
                                   :value {:segment-id "segment-001" :leak-id "leak-1"
                                           :scheduled-date "2026-08-01" :actuate-valve? false}} coordinator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :schedule-repair-recommendation :effect :propose :subject "rpr-1"
                                    :value {:segment-id "segment-001" :leak-id "leak-1"
                                            :scheduled-date "2026-08-01" :actuate-valve? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-recommended} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/repair-history db))) "still only the one earlier recommendation"))))

(deftest invalid-pipe-material-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t12" {:op :log-pipe-condition :effect :propose :subject "segment-001"
                                  :patch {:material :unobtanium-pipe}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-pipe-material} (-> (store/ledger db) last :basis)))
    (is (not= :unobtanium-pipe (:material (store/segment db "segment-001"))) "fabricated material never lands in the SSoT")))

(deftest invalid-flow-loss-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t13" {:op :leak-survey :effect :propose :subject "leak-4"
                                  :value {:segment-id "segment-001" :verdict :confirmed
                                          :sensor-basis ["reading-001" "reading-002"]
                                          :flow-loss-lpm 999999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-flow-loss} (-> (store/ledger db) last :basis)))
    (is (nil? (store/leak-survey-of db "leak-4")) "fabricated flow-loss never lands in the SSoT")))

(deftest critical-leak-always-escalates-even-high-confidence
  (testing "escalate-critical-leak always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :escalate-critical-leak :effect :propose :subject "concern-1"
                                    :value {:segment-id "segment-001" :severity :high
                                            :description "negative pressure signature, possible contamination ingress risk"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/critical-leak-log db))))))))

(deftest critical-leak-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t15" {:op :escalate-critical-leak :effect :propose :subject "concern-2"
                                :value {:segment-id "segment-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t15")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/critical-leak-log db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-pipe-condition :effect :propose :subject "segment-001"
                          :patch {:pipe-diameter-mm 300}} coordinator)
      (exec-op actor "b" {:op :log-pipe-condition :effect :propose :subject "segment-001"
                          :patch {:material :unobtanium-pipe}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
