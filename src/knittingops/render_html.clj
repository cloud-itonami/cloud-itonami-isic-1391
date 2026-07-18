(ns knittingops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout, iteration 11): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`knittingops.operation` -> `knittingops.governor` -> `knittingops.store`)
  through a scenario adapted from this repo's own `knittingops.sim` demo
  driver (`clojure -M:run`, confirmed by actually running it before this
  file was written -- unlike `cloud-itonami-isic-851`'s `schoolops.sim`,
  this repo's own sim driver uses ids that DO match
  `knittingops.store/sample-batches`/`sample-equipment`'s seeded demo data
  exactly, and every disposition it produces (auto-commit / escalate+
  approve / HARD hold, and the exact `:rule` on each hold) matches
  `knittingops.governor`'s own documented checks precisely, so it was safe
  to reuse rather than author from scratch), trimmed to a representative
  subset (the one clean phase-3 auto-commit this domain has, the
  maintenance-scheduling / safety-concern-flagging / shipment-coordination
  lifecycle -- all three of which ALWAYS escalate, never auto, at any
  phase -- and three distinct HARD-hold reasons that never reach a human)
  and rendered deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [knittingops.store :as store]
            [knittingops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :mill-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real ids from
  `knittingops.store/sample-batches`/`sample-equipment`:

  batch-001 (grade-a cotton-jersey-interlock, verified+registered, 20000.0
  yd own recorded production yardage, 5000.0 yd already shipped): a
  `:log-production-batch` clean patch is a phase-3, no-physical/financial-
  risk auto-commit (governor clean, `:log-production-batch` is the ONLY
  op in phase 3's `:auto` set -- `knittingops.phase`).

  mnt-1 on equip-001 (verified+registered circular-knitting-machine):
  `:schedule-maintenance` is NEVER a member of any phase's `:auto` set,
  including phase 3 (a permanent structural fact, not a rollout
  milestone -- `knittingops.phase` docstring) -- ALWAYS escalates and is
  approved by a human mill supervisor.

  concern-1 on equip-001: `:flag-safety-concern` ALWAYS carries `:stake
  :coordination/safety-concern`, in `knittingops.governor/high-stakes` --
  ALWAYS escalates regardless of confidence, approved by a human mill
  supervisor.

  ship-1 on batch-001 (5000.0 yd claim; 5000.0 yd already shipped + 5000.0
  yd claimed = 10000.0 yd, within batch-001's own recorded 20000.0 yd
  production yardage): `:coordinate-shipment` is also never auto-eligible
  at any phase -- ALWAYS escalates, approved by a human shipping
  approver.

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - mnt-2 on equip-002 (seeded `:verified? false :registered? false`
      crocheting-line): `:schedule-maintenance` HARD-holds on
      `:equipment-not-verified` -- the advisor's own report of
      verified?/registered? is never trusted, the governor independently
      re-derives it from equip-002's own stored fields.
    - ship-2 on batch-003 (seeded `:verified? false :registered? false`
      irregular-grade batch): `:coordinate-shipment` HARD-holds on
      `:batch-not-verified` -- same independent-verification discipline,
      applied to the batch side.
    - ship-3 on batch-002 (own recorded 6000.0 yd production yardage,
      5700.0 yd already shipped; a further 1000.0 yd claim would total
      6700.0 yd): `:coordinate-shipment` HARD-holds on
      `:shipment-volume-exceeded` -- the governor independently
      recomputes shipped-to-date + claimed against the batch's own
      recorded yardage, never trusts the claim.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; batch-001: clean production-batch log patch -- phase-3 auto-commit,
    ;; no physical/financial risk yet.
    (exec! actor "b1-log" {:op :log-production-batch :effect :propose :subject "batch-001"
                            :patch {:quality-grade :grade-a :last-assessed "2026-07-14"}})

    ;; mnt-1: maintenance scheduling against equip-001 (verified+registered
    ;; circular-knitting-machine) -- ALWAYS escalates, approved by a human
    ;; mill supervisor.
    (exec! actor "m1-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                 :value {:equipment-id "equip-001" :maintenance-type :needle-replacement
                                         :scheduled-date "2026-08-01" :direct-operate? false}})
    (approve! actor "m1-schedule")

    ;; concern-1: materials-safety concern flagged against equip-001 --
    ;; ALWAYS escalates regardless of confidence, approved by a human mill
    ;; supervisor.
    (exec! actor "c1-flag" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                             :value {:equipment-id "equip-001" :concern-type :materials-safety
                                     :severity :moderate
                                     :description "編み立て済み生地に規格外の毛羽立ちを確認"}})
    (approve! actor "c1-flag")

    ;; ship-1: shipment coordination against batch-001 (within its own
    ;; recorded capacity) -- ALWAYS escalates, approved by a human
    ;; shipping approver.
    (exec! actor "s1-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                   :value {:batch-id "batch-001" :volume-yards 5000.0
                                           :destination "buyer-warehouse-north"}})
    (approve! actor "s1-coordinate")

    ;; mnt-2: maintenance scheduling against equip-002 (seeded
    ;; UNVERIFIED/unregistered) -> HARD hold :equipment-not-verified,
    ;; never reaches a human.
    (exec! actor "m2-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                                 :value {:equipment-id "equip-002" :maintenance-type :tension-calibration
                                         :scheduled-date "2026-08-01" :direct-operate? false}})

    ;; ship-2: shipment coordination against batch-003 (seeded
    ;; UNVERIFIED/unregistered) -> HARD hold :batch-not-verified, never
    ;; reaches a human.
    (exec! actor "s2-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                                   :value {:batch-id "batch-003" :volume-yards 1000.0
                                           :destination "buyer-warehouse-south"}})

    ;; ship-3: shipment coordination against batch-002 (1000.0 yd claim
    ;; would push 5700.0 yd already-shipped past its own recorded 6000.0
    ;; yd production yardage) -> HARD hold :shipment-volume-exceeded,
    ;; independently recomputed, never reaches a human.
    (exec! actor "s3-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                                   :value {:batch-id "batch-002" :volume-yards 1000.0
                                           :destination "buyer-warehouse-east"}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id fabric quality-grade volume-yards fabric-weight-gsm
                                  defect-rate-percent verified? registered? shipped-volume-yards]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc fabric) (esc (name (or quality-grade :n-a)))
          (esc volume-yards) (esc fabric-weight-gsm) (esc defect-rate-percent)
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (esc shipped-volume-yards)
          (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind verified? registered? last-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (esc (or last-maintenance-date "—"))
          (status-cell ledger id)))

(defn- concern-row [ledger {:keys [id equipment-id concern-type severity description]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (or equipment-id "—")) (esc (name (or concern-type :n-a)))
          (esc (name (or severity :n-a))) (esc description)
          (status-cell ledger id)))

(defn- draft-record-row [kind {:strs [record_id maintenance_id shipment_id equipment_id immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc kind) (esc record_id) (esc (or maintenance_id shipment_id))
          (esc (or equipment_id "—"))
          (if immutable "<span class=\"ok\">immutable draft</span>" "<span class=\"warn\">draft</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`knittingops.governor`/`knittingops.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no physical/financial risk yet -- the ONLY auto-eligible op in this domain</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment verified?/registered? independently re-derived from its own stored record, never trusted from the advisor &middot; `:direct-operate? true` is a PERMANENT, unconditional block, no phase or approval can ever override it</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval regardless of confidence &middot; materials-safety/equipment-safety concerns are never gated on the referenced equipment being verified</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified?/registered? independently re-derived &middot; claimed yardage independently recomputed against the batch's own logged shipped-to-date + production yardage, never trusted from the proposal</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        concerns (store/safety-concerns db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map (partial equipment-row ledger) equipment))
        concern-rows (str/join "\n" (map (partial concern-row ledger) concerns))
        maintenance-rows (str/join "\n" (map (partial draft-record-row "maintenance-schedule")
                                              (store/maintenance-history db)))
        shipment-rows (str/join "\n" (map (partial draft-record-row "shipment-coordination")
                                           (store/shipment-history db)))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-1391 &middot; manufacture of knitted and crocheted fabrics</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of knitted and crocheted fabrics (ISIC 1391) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance scheduling/safety-concern flagging/shipment coordination always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>knittingops.store</code> via <code>knittingops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Fabric</th><th>Quality grade</th><th>Volume (yd)</th><th>Fabric weight (gsm)</th><th>Defect rate (%)</th><th>Verified</th><th>Registered</th><th>Shipped (yd)</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Equipment</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Verified</th><th>Registered</th><th>Last maintenance date</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Append-only materials-safety/equipment-safety concern log — always human-reviewed, never gated on equipment verification status.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Type</th><th>Severity</th><th>Description</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     concern-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft maintenance-schedule / shipment-coordination records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the mill supervisor's/shipping approver's own act of signing is outside this actor's authority (see README <code>What this actor does NOT do</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Subject</th><th>Equipment</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     maintenance-rows (when (seq maintenance-rows) "\n")
     shipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Knitting Mill Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Equipment/batch verification+registration status, shipment-volume arithmetic, quality-grade and defect-rate plausibility are independently recomputed, never trusted from the advisor's proposal; maintenance scheduling, safety-concern flagging and shipment coordination are always a human mill supervisor's/shipping approver's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
