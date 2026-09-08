(ns leaksurvey.advisor
  "Survey Advisor -- the *contained intelligence node* for the water-
  infrastructure leak-detection actor.

  It normalizes pipe-condition patches (material/install-year/
  diameter), drafts a leak-survey verdict GROUNDED in cited sensor
  readings (`leaksurvey.telemetry`), drafts a repair-recommendation
  scheduling proposal against a segment with an on-file confirmed
  leak, and drafts a critical-leak escalation flag. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real valve/main actuation or a water-utility regulatory compliance
  filing -- see README `What this actor does NOT do`. Every output is
  censored downstream by `leaksurvey.governor` before anything touches
  the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `leaksurvey.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:segment/upsert :leak/survey-set
                                 ; :repair/schedule :leak/escalate}
                                 ; propose-shaped effects, NEVER a
                                 ; direct valve/main-control effect
     :stake      kw|nil         ; :leak/critical-safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `leaksurvey.
  governor` HARD-holds any request that doesn't, so a mis-wired caller
  can never reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [leaksurvey.registry :as registry]
            [leaksurvey.store :as store]
            [leaksurvey.telemetry :as telemetry]
            [langchain.model :as model]))

(defn- log-pipe-condition
  "Pipe-segment condition intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the segment's material,
  install-year or verification status. High confidence, low stakes --
  administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "配管区間記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :segment/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- leak-survey
  "Draft a leak-survey verdict for a segment, GROUNDED in cited sensor
  readings when the verdict asserts a condition (`:confirmed`/
  `:no-leak`). The advisor reports whether it believes its own cited
  basis grounds the verdict, but `leaksurvey.governor` NEVER trusts
  this report -- it independently re-derives grounding from the
  segment's own stored readings via `leaksurvey.telemetry/grounds-
  verdict?` before any commit is possible. A `:needs-more-data`
  verdict needs no basis -- the honest 'I could not determine'
  outcome."
  [db {:keys [subject value]}]
  (let [segment-id (:segment-id value)
        verdict (:verdict value)
        cited (:sensor-basis value)
        readings (store/readings-for-segment db segment-id)
        grounded? (or (= verdict :needs-more-data)
                      (telemetry/grounds-verdict? segment-id cited readings))]
    {:summary    (str subject " 向け漏水調査結果 (" (name (or verdict :unknown)) ")"
                      (when segment-id (str " segment=" segment-id)))
     :rationale  (str "verdict=" verdict " sensor-basis=" (pr-str cited)
                      " grounded?=" grounded?)
     :cites      (vec cited)
     :effect     :leak/survey-set
     :value      value
     :stake      nil
     :confidence (if grounded? 0.85 0.25)}))

(defn- schedule-repair-recommendation
  "Draft a repair-recommendation scheduling proposal against a segment
  with an on-file confirmed leak. The advisor reports what it can see
  (segment verified?/registered?, leak on-file confirmed?) in its
  rationale, but `leaksurvey.governor` NEVER trusts this report -- it
  independently re-derives verified?/registered? from the segment's
  own stored fields and the leak's own committed verdict before any
  commit is possible."
  [db {:keys [subject value]}]
  (let [segment-id (:segment-id value)
        leak-id (:leak-id value)
        seg (store/segment db segment-id)
        survey (store/leak-survey-of db leak-id)
        confirmed? (and survey (= :confirmed (:verdict survey)) (= segment-id (:segment-id survey)))
        ready? (and seg (registry/segment-ready? seg))]
    {:summary    (str subject " 向け修理提案"
                      (when seg (str " segment=" segment-id))
                      (when leak-id (str " leak=" leak-id)))
     :rationale  (str "segment-verified?=" (some-> seg registry/segment-verified?)
                      " segment-registered?=" (some-> seg registry/segment-registered?)
                      " leak-confirmed-on-file?=" confirmed?
                      " actuate-valve?=" (boolean (:actuate-valve? value)))
     :cites      (cond-> [] seg (conj segment-id) survey (conj leak-id))
     :effect     :repair/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? confirmed? (not (:actuate-valve? value))) 0.9 0.3)}))

(defn- escalate-critical-leak
  "Draft an acute water-safety/pressure-loss critical-leak concern.
  ALWAYS `:stake :leak/critical-safety-concern` -- a critical-leak
  concern is NEVER a proposal the advisor may quietly downgrade to
  low-stakes, and it is never gated on the referenced segment being
  verified (a concern can be raised about ANY segment, verified or
  not -- see README `What this actor does NOT do` re: never blocking
  safety-relevant reporting on an administrative technicality). See
  `leaksurvey.phase`: no phase ever adds this op to a phase's `:auto`
  set; `leaksurvey.governor` also always escalates on `:leak/critical-
  safety-concern`. Two independent layers agree, deliberately."
  [db {:keys [subject value]}]
  (let [segment-id (:segment-id value)
        seg (and segment-id (store/segment db segment-id))]
    {:summary    (str subject " 向け緊急漏水懸念報告 (" (:severity value) ")"
                      (when seg (str " segment=" segment-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if seg [segment-id] [])
     :effect     :leak/escalate
     :value      value
     :stake      :leak/critical-safety-concern
     :confidence 0.9}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-pipe-condition               (log-pipe-condition db request)
    :leak-survey                      (leak-survey db request)
    :schedule-repair-recommendation   (schedule-repair-recommendation db request)
    :escalate-critical-leak           (escalate-critical-leak db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは水道インフラ漏水検知調査アドバイザーの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:segment/upsert|:leak/survey-set|"
       ":repair/schedule|:leak/escalate) "
       ":stake(:leak/critical-safety-concern か nil) :confidence(0..1)。\n"
       "重要: CONFIRMED/NO-LEAKの判定は必ず実測センサー読み取り値の引用が"
       "必要で、引用の無い判定を提案してはいけません。"
       "未検証または未登録の配管区間・センサーユニットに対する修理提案を"
       "してはいけません。バルブ・幹線の直接操作(actuate)を絶対に提案しては"
       "いけません(この actor は提案のみを行い、実行は一切行いません)。"
       "水道事業体の規制コンプライアンス申告を自己発行する提案をしては"
       "いけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-pipe-condition               {:segment (store/segment st subject)}
    :leak-survey                      {:segment (store/segment st (:segment-id value))
                                        :readings (store/readings-for-segment st (:segment-id value))}
    :schedule-repair-recommendation   {:segment (store/segment st (:segment-id value))
                                        :leak-survey (store/leak-survey-of st (:leak-id value))}
    :escalate-critical-leak           {:segment (and (:segment-id value) (store/segment st (:segment-id value)))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `leaksurvey.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a repair,
  auto-file a leak verdict, or auto-escalate/downgrade a critical-leak
  concern."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :survey-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
