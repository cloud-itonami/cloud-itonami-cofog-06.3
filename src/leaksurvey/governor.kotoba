(ns leaksurvey.governor
  "Water Infrastructure Governor -- the independent compliance layer
  that earns the Survey Advisor the right to commit. The advisor has
  no notion of whether a segment it wants to recommend a repair
  against has actually been surveyed/registered, whether a sensor unit
  it relies on has actually been inspected/registered, whether a
  CONFIRMED/NO-LEAK verdict it filed is actually grounded in measured
  sensor readings rather than self-attested, whether a repair
  recommendation secretly tries to ACTUATE (rather than merely
  schedule) a valve/main, whether the leak record it cites as the
  repair's own basis has actually been confirmed on file, or when an
  act stops being a survey-coordination proposal and becomes direct
  valve/main control, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:water-infrastructure-governor`
  (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else --
                                       HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:valve/actuate` or
                                       `:main/isolate`) is the 'direct
                                       valve/main control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Valve-actuate blocked       -- for `:schedule-repair-
                                       recommendation`, does the
                                       proposal's own `:value` declare
                                       `:actuate-valve? true`? Directly
                                       actuating a valve/main is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `leaksurvey.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Leak-verdict ungrounded     -- for `:leak-survey`, when the
                                       proposal's own `:value` declares
                                       a `:verdict` of `:confirmed` or
                                       `:no-leak`, INDEPENDENTLY
                                       re-derive whether the cited
                                       `:sensor-basis` reading-ids
                                       actually ground that verdict via
                                       `leaksurvey.telemetry/grounds-
                                       verdict?` -- never trust the
                                       advisor's own claim that its
                                       basis is sufficient. A
                                       `:needs-more-data` verdict is
                                       exempt (an honest 'I could not
                                       determine' needs no basis).
    6. Segment not verified/
       registered                  -- for `:schedule-repair-
                                       recommendation`, INDEPENDENTLY
                                       verify the referenced segment's
                                       own `:verified?` AND
                                       `:registered?` are both true
                                       (`leaksurvey.registry/segment-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    7. Leak not confirmed on file  -- for `:schedule-repair-
                                       recommendation`, INDEPENDENTLY
                                       verify the referenced leak-id's
                                       own committed `leaksurvey.store/
                                       leak-survey-of` verdict is
                                       actually `:confirmed` for the
                                       SAME segment -- never taken on
                                       the advisor's self-report. A
                                       repair can never be recommended
                                       against a leak that was never
                                       actually confirmed.
    8. Already recommended         -- for `:schedule-repair-
                                       recommendation`, refuses to
                                       schedule the SAME repair
                                       recommendation twice, off a
                                       dedicated `:repair-recommended?`
                                       fact (never a `:status` value).
    9. Invalid pipe material       -- for `:log-pipe-condition`, if the
                                       patch declares a `:material`
                                       outside the closed known set
                                       (`leaksurvey.registry/pipe-
                                       material-valid?`), the segment
                                       record is rejected rather than
                                       let a fabricated material
                                       through.
   10. Invalid flow-loss reading   -- for `:leak-survey`, if the value
                                       declares a `:flow-loss-lpm` that
                                       is not a physically plausible
                                       reading (`leaksurvey.registry/
                                       flow-loss-valid?`), the leak
                                       record is rejected rather than
                                       let fabricated/sensor-error data
                                       through.
   11. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake`
                                       is in `high-stakes`
                                       (`:leak/critical-safety-
                                       concern`, ALWAYS set for
                                       `:escalate-critical-leak`) --
                                       escalate to a human survey
                                       coordinator. SOFT: the human may
                                       approve."
  (:require [leaksurvey.registry :as registry]
            [leaksurvey.store :as store]
            [leaksurvey.telemetry :as telemetry]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-pipe-condition :leak-survey
    :schedule-repair-recommendation :escalate-critical-leak})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  valve/main-control effect."
  #{:segment/upsert :leak/survey-set
    :repair/schedule :leak/escalate})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Critical-leak concerns are the one op in this domain that always
  demands human eyes regardless of confidence."
  #{:leak/critical-safety-concern})

(def condition-verdicts
  "Leak-survey verdicts that assert a real condition and therefore
  REQUIRE sensor grounding. `:needs-more-data` is deliberately absent
  -- the honest 'I don't know yet' outcome needs no basis."
  #{:confirmed :no-leak})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- valve-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct valve/main control, a fabricated actuation
  effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :valve-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") はバルブ・幹線の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- valve-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-repair-recommendation`
  proposal whose own `:value` declares `:actuate-valve? true` is
  attempting to directly actuate a valve/main -- this actor may only
  ever propose/schedule a DRAFT repair recommendation, never actuate
  the valve/main directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-repair-recommendation)
             (true? (:actuate-valve? (:value proposal))))
    [{:rule :valve-actuate-blocked
      :detail "バルブ・幹線の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- leak-verdict-ungrounded-violations
  "For `:leak-survey`, when the proposal's own `:value` declares a
  `:verdict` of `:confirmed` or `:no-leak`, INDEPENDENTLY re-derive
  whether the cited `:sensor-basis` actually grounds that verdict --
  never trust the advisor's own claim. `:needs-more-data` is exempt."
  [{:keys [op]} proposal st]
  (when (= op :leak-survey)
    (let [{:keys [segment-id verdict sensor-basis]} (:value proposal)]
      (when (and (contains? condition-verdicts verdict)
                 (not (telemetry/grounds-verdict?
                       segment-id sensor-basis (store/readings-for-segment st segment-id))))
        [{:rule :leak-verdict-ungrounded
          :detail (str verdict " 判定には acoustic-signature と pressure-differential 両方をカバーする"
                       "実測センサー引用が必要 -- 引用不足または未検証")}]))))

(defn- segment-not-verified-violations
  "For `:schedule-repair-recommendation`, INDEPENDENTLY verify the
  referenced segment exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-repair-recommendation)
    (let [segment-id (:segment-id (:value proposal))
          seg (and segment-id (store/segment st segment-id))]
      (when-not (and seg (registry/segment-ready? seg))
        [{:rule :segment-not-verified
          :detail (str segment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み区間記録が無い状態での修理提案")}]))))

(defn- leak-not-confirmed-on-file-violations
  "For `:schedule-repair-recommendation`, INDEPENDENTLY verify the
  referenced leak-id's own COMMITTED verdict is actually `:confirmed`
  for the SAME segment -- never taken on the advisor's self-report. A
  repair can never be recommended against a leak that was never
  actually confirmed on file."
  [{:keys [op]} proposal st]
  (when (= op :schedule-repair-recommendation)
    (let [segment-id (:segment-id (:value proposal))
          leak-id (:leak-id (:value proposal))
          survey (and leak-id (store/leak-survey-of st leak-id))]
      (when-not (and survey (= :confirmed (:verdict survey)) (= segment-id (:segment-id survey)))
        [{:rule :leak-not-confirmed-on-file
          :detail (str leak-id " は " segment-id " に対する確定済み(confirmed)漏水記録として存在しない")}]))))

(defn- already-recommended-violations
  "For `:schedule-repair-recommendation`, refuses to schedule the SAME
  repair recommendation twice, off a dedicated `:repair-recommended?`
  fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-repair-recommendation)
    (when (store/repair-already-recommended? st subject)
      [{:rule :already-recommended
        :detail (str subject " は既に修理提案済み")}])))

(defn- invalid-pipe-material-violations
  "For `:log-pipe-condition`, if the patch declares a `:material`
  outside the closed known set, reject rather than let a fabricated
  material through."
  [{:keys [op]} proposal]
  (when (= op :log-pipe-condition)
    (let [material (:material (:value proposal))]
      (when (and (some? material) (not (registry/pipe-material-valid? material)))
        [{:rule :invalid-pipe-material
          :detail (str material " は既知の pipe material 値ではない")}]))))

(defn- invalid-flow-loss-violations
  "For `:leak-survey`, if the value declares a `:flow-loss-lpm` that is
  not a physically plausible reading, reject rather than let
  fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :leak-survey)
    (let [lpm (:flow-loss-lpm (:value proposal))]
      (when (and (some? lpm) (not (registry/flow-loss-valid? lpm)))
        [{:rule :invalid-flow-loss
          :detail (str lpm "L/分 は物理的に妥当な漏水流量の範囲外")}]))))

(defn check
  "Censors a Survey Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (valve-control-blocked-violations proposal)
                           (valve-actuate-blocked-violations request proposal)
                           (leak-verdict-ungrounded-violations request proposal st)
                           (segment-not-verified-violations request proposal st)
                           (leak-not-confirmed-on-file-violations request proposal st)
                           (already-recommended-violations request st)
                           (invalid-pipe-material-violations request proposal)
                           (invalid-flow-loss-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
