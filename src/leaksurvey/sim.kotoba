(ns leaksurvey.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean inspector through
  intake -> a GROUNDED leak-survey confirming a leak (escalate/approve)
  -> repair-recommendation scheduling against the confirmed leak
  (escalate/approve) -> critical-leak escalation (escalate/approve),
  then shows HARD-hold scenarios: a mis-wired request whose own
  `:effect` is not `:propose`, an unrecognized op, an UNGROUNDED
  leak-survey verdict (partial sensor basis, only one of two required
  metrics), a repair recommended against an UNVERIFIED/unregistered
  segment, a repair recommended against a leak that was never
  confirmed on file, a proposal that tries to ACTUATE a valve/main
  directly (permanently blocked, no override), a double-recommendation
  of the same repair, a pipe-condition patch with a fabricated
  material, and a leak-survey with an implausible flow-loss reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [leaksurvey.store :as store]
            [leaksurvey.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :survey-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-pipe-condition segment-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-pipe-condition :effect :propose :subject "segment-001"
                        :patch {:pipe-diameter-mm 300}}
                       coordinator))

    (println "== leak-survey leak-1 on segment-001 (FULL sensor basis, both metrics -> grounded CONFIRMED, escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :leak-survey :effect :propose :subject "leak-1"
                       :value {:segment-id "segment-001" :verdict :confirmed
                               :sensor-basis ["reading-001" "reading-002"]
                               :flow-loss-lpm 42.5}}
                      coordinator)]
      (println r)
      (println "-- human survey coordinator approves --")
      (println (approve! actor "t2")))

    (println "== schedule-repair-recommendation rpr-1 on segment-001+leak-1 (verified segment, confirmed leak on file -- escalates, approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :schedule-repair-recommendation :effect :propose :subject "rpr-1"
                       :value {:segment-id "segment-001" :leak-id "leak-1"
                               :scheduled-date "2026-08-01" :actuate-valve? false}}
                      coordinator)]
      (println r)
      (println "-- human survey coordinator approves --")
      (println (approve! actor "t3")))

    (println "== escalate-critical-leak concern-1 on segment-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :escalate-critical-leak :effect :propose :subject "concern-1"
                       :value {:segment-id "segment-001" :severity :high
                               :description "負圧発生の兆候、二次汚染混入リスクあり"}}
                      coordinator)]
      (println r)
      (println "-- human survey coordinator approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-pipe-condition with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-pipe-condition :effect :direct-write :subject "segment-001"
                        :patch {:pipe-diameter-mm 300}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :operate-main-valve :effect :propose :subject "segment-001"}
                       coordinator))

    (println "== leak-survey leak-2 on segment-002 (PARTIAL sensor basis, only acoustic-signature -> UNGROUNDED, HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :leak-survey :effect :propose :subject "leak-2"
                        :value {:segment-id "segment-002" :verdict :confirmed
                                :sensor-basis ["reading-003"]
                                :flow-loss-lpm 10.0}}
                       coordinator))

    (println "== schedule-repair-recommendation rpr-2 on segment-003 (UNVERIFIED/unregistered segment -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :schedule-repair-recommendation :effect :propose :subject "rpr-2"
                        :value {:segment-id "segment-003" :leak-id "leak-1"
                                :scheduled-date "2026-08-01" :actuate-valve? false}}
                       coordinator))

    (println "== schedule-repair-recommendation rpr-3 citing a leak-id that was never confirmed -> HARD hold ==")
    (println (exec-op actor "t9"
                       {:op :schedule-repair-recommendation :effect :propose :subject "rpr-3"
                        :value {:segment-id "segment-002" :leak-id "leak-never-surveyed"
                                :scheduled-date "2026-08-01" :actuate-valve? false}}
                       coordinator))

    (println "== schedule-repair-recommendation rpr-4 on segment-001+leak-1 with :actuate-valve? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-repair-recommendation :effect :propose :subject "rpr-4"
                        :value {:segment-id "segment-001" :leak-id "leak-1"
                                :scheduled-date "2026-09-01" :actuate-valve? true}}
                       coordinator))

    (println "== schedule-repair-recommendation rpr-1 AGAIN (double-recommendation -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-repair-recommendation :effect :propose :subject "rpr-1"
                        :value {:segment-id "segment-001" :leak-id "leak-1"
                                :scheduled-date "2026-08-01" :actuate-valve? false}}
                       coordinator))

    (println "== log-pipe-condition on segment-001 with a fabricated material -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-pipe-condition :effect :propose :subject "segment-001"
                        :patch {:material :unobtanium-pipe}}
                       coordinator))

    (println "== leak-survey leak-3 on segment-001 with an implausible flow-loss reading -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :leak-survey :effect :propose :subject "leak-3"
                        :value {:segment-id "segment-001" :verdict :confirmed
                                :sensor-basis ["reading-001" "reading-002"]
                                :flow-loss-lpm 999999.0}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft repair-recommendation records ==")
    (doseq [r (store/repair-history db)] (println r))

    (println "\n== critical-leak log ==")
    (doseq [c (store/critical-leak-log db)] (println c))))
