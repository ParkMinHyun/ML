# Pacing / backlog-clock investigation — handoff

Date: 2026-09-02. Written to hand this work to a fresh session with no memory of the conversation
that produced it. Everything here is reproducible from the harness in
`.codex_sheet_analysis/pacing_replay_0829/` (gitignored, local only).

Read §1–§3 before touching anything. §7 is the trap list — several of these silently produce
wrong answers rather than errors.

---

## 1. Where things stand

### Shipped (pushed to `master`, `898ae37..12148eb`)

| commit | change |
|---|---|
| `9217444` | **Read the shared condition off the capture that just ran.** `WorkloadDurationTrend`'s condition factor was the recency-weighted median of every duration/base ratio in history; it is now the plain median of the ratios the latest capture measured (mean of the middle two when even). A capture whose every workload was cold teaches no ratio, and the last learned condition stands. `RecencyWeightedDistribution.median()` lost its only caller and was deleted. |
| `12148eb` | **Pool every frame size into one workload history.** `SizeBucket` removed from `WorkloadKey` (and the enum deleted). Bokeh/DynamicFunction/Filter/Decoding became `data object`s. The megapixel-ratio cold-workload estimator went with it. The pacer's session maximum is no longer size-scoped. Exporter: `replayWorkloadKey()` no longer parses `sizeBucket=`, two now-meaningless CaseStudyTrace columns removed (`sizeScopedObservedMaxDraftMs`, `draftSequenceReserveCrossSizeContaminationMs`), and the descriptive `sizeBucket` label now reads `resultImageSize`. `CaptureAvailablePacer.cancelDraftSequence()` deleted — it passed 0 into a setter guarded on positive durations, so it was a no-op. |

The two commits are individually compilable: the first keeps the size axis, the second removes it.
So the size-axis removal can be reverted alone.

**NOT compiled.** There is no Kotlin toolchain in this environment. Verification was the Python
replay (§3) plus reference greps. **Run a build.**

### Measured and deliberately reverted

A third change — apply the whole deficit (drop the `/2`) when the draft sequence has no OPTIONAL
workload left — was implemented, measured, and reverted. Do not re-propose it without new data.
Evidence in §5.2. The harness keeps the flag (`rmodel.compute_pacing_delay_ms(...,
full_deficit_without_optional=)`, default OFF) so the measurement stays reproducible.

### Open, not implemented

- **Backlog clock replacement.** Blocked on one design decision, not on data. See §6.1.
- **Exposing backlog to admission.** Blocked on a controller-interaction argument. See §6.2.

---

## 2. The data

`C:\Users\algus\Documents\SEIP'27\data\B_ablation\` (see `rload.DIR`).

| workbook | captures | timeouts | runs | pacing rows | used? |
|---|---|---|---|---|---|
| `SM-S942B_metrics_24MP_memory_0829_original.xlsx` | 1969 | **14** | 69 | 1717 | **everything below** |
| `SM-S942B_metrics_24MP_memory_timeout_sessions.xlsx` | 217 | 14 | 14 | 178 | same 14 timeouts, filtered subset |
| `SM-S942B_metrics_24MP_memory_0829.xlsx` | 1802 | 9 | 60 | 1576 | **untouched — holdout** |
| `SM-S942B_metrics_12MP_normal_baseline_0829.xlsx` | 1028 | 46 | 59 | 858 | **untouched — holdout** |
| `SM-S942B_metrics_12MP_normal_admit_only_0829.xlsx` | 304 | 16 | 16 | 261 | untouched (admission-only arm) |
| `SM-S942B_metrics_12MP_normal_pacing_only_0829.xlsx` | 399 | 3 | 13 | 351 | untouched (pacing-only arm) |
| `SM-S942B_metrics.12MP_normal_0829_origin.xlsx` | 1888 | 0 | 58 | 1637 | untouched |
| `SM-S942B_metrics_12MP_normal_0829.xlsx` | 1803 | 0 | 56 | 1563 | untouched |

**Every conclusion in §5 came from the one workbook in bold.** The holdouts exist and were never
opened. That is the single biggest weakness of this work (§4.3).

Also note: in the 24MP_memory workbooks most captures are 4000x3000 (12 MP result) and only 88 are
5712x4284 (24 MP). The "24MP" in the filename is the operator's condition label, not the result
image size.

### The 0829 device build is AHEAD of this repo

Critical. Recovered from the recording, not from source:

1. **`WorkloadKey` already has no size axis.** Recorded keys are `BOKEH()`,
   `ENCODING(imageFormat=4101,isPendingRequest=true)`. Commit `12148eb` therefore aligns the repo
   *to the data*; it contributes no behavioural delta on this trace. It also un-breaks the
   exporter's `AdmissionReplay` sheet, whose `replayWorkloadKey()` required `sizeBucket=` in the
   persisted string and so produced nothing for 0829 data.
2. **Pacing reserve carries a ×1.1 factor**: `reserved = 1.1 * max(sessionMaxDraftMs - demoted,
   predicted + overhead)`. Evidence: 262 floor-branch rows where `reserved / 1.1 ==
   predicted + overhead` to 15 digits, and 1389 rows where `reserved / 1.1 + demoted` is an exact
   integer draft wall. 66 early-trace rows do not decompose.
3. **Admission bound carries a separate ×1.05 factor**: `bound = 1.05 * predicted * exp(residual)`.
   Evidence: at the first decision of a run, with an empty residual history, `ub / pred == 1.05`
   exactly. **Not fully reproducible** — the recorded residual score runs slightly above the sample
   this repo's rule would add (e.g. 0.003888273 recorded vs 0.003661331 computed for the full
   7-workload shape at capture 3). Nothing in pacing reads the bound, so the replay does not use it.

**Action item:** reconcile these two factors with whoever owns the device build before the next data
pull. Any replay scored against these workbooks silently measures a different model without them —
the reserve is off by up to 1.1 s and the delay by hundreds of ms.

---

## 3. The replay harness

`.codex_sheet_analysis/pacing_replay_0829/` — gitignored, so it does **not** travel with the repo.
Copy it manually if moving machines. Supersedes `../predictor_replay/`, which only replays the
predictor and assumes keys still carry `sizeBucket=`.

| file | role |
|---|---|
| `xl.py` | workbook loader that pads short rows (openpyxl trims trailing empties) |
| `rload.py` | pulls the node stream, capture timeline and pacing decisions; holds `DIR` and `WORKBOOK` |
| `rmodel.py` | port of `DraftSequenceExecutionPredictor` + `DraftSequenceAdmissionPolicy` + `computePacingDelayMs`. Change flags: `latest_capture_median` (change 1), `full_deficit_without_optional` (the reverted change) |
| `rvalidate.py` | **fidelity gate.** Predictor point predictions vs the recording. Run this first, always |
| `rpacer2.py` | event-ordered `CaptureAvailablePacer` replay. `clock_price='P0'` (default) is bit-exact; other prices switch to a per-draft-price structure for the ablation |
| `rsim.py` | recovers the session max and overhead the runtime held, from the recording; `err_table` helper |
| `rverdict.py` | per-timeout-run margin accounting |
| `rfinal.py` | three arms (base / reverted change alone / change 1 + reverted change) side by side |
| `rclock.py` | **passive** scoring of candidate backlog clocks against ground truth; `WallTrends` lives here |
| `rclockarm.py` | **active** arm: each candidate clock drives the delay; cost in delay vs timeouts cleared |

```bash
python .codex_sheet_analysis/pacing_replay_0829/rvalidate.py    # gate — must read error 0
python .codex_sheet_analysis/pacing_replay_0829/rverdict.py     # per-run verdict
python .codex_sheet_analysis/pacing_replay_0829/rfinal.py       # change-by-change breakdown
python .codex_sheet_analysis/pacing_replay_0829/rclock.py       # clock candidates, passive
python .codex_sheet_analysis/pacing_replay_0829/rclockarm.py    # clock candidates, active
```

### Fidelity reached (baseline arm, `clock_price='P0'`)

| quantity | agreement with the recording |
|---|---|
| `sequencePredictedDurationMs` | **exact, 13,778 / 13,778 decisions** |
| `draftSequenceKey` (demoted shape) | **exact, 1717 / 1717** |
| `draftSequenceOverheadDurationMs` | taken from the recording (the replay's own is within 7.6 ms) |
| `draftSequenceReservedDurationMs` | exact on 99.4% |
| `backlogMs` | exact on 98.8%, within 1 ms on 100% |
| `appliedDelayMs` | exact on 99.6%, within 1 ms on 99.9%, max 67 ms |

Residual causes: the export stamps `draftStartUptimeMs` a few ms after the pacer's own
`SystemClock.uptimeMillis()` read inside `rebaseBacklogClock`; plus ~10 early-trace rows whose
reserve does not decompose.

### Seven things the replay must get right

Each of these was found by a mismatch, and each silently corrupts results if wrong.

1. **The two build factors** (§2). Without them the reserve and delay are simply a different model.
2. **Model reset is `ppSequenceId == 0`.** With it, point predictions reproduce bit-exactly; without
   it, nothing matches (max error 1573 ms).
3. **The pacing metrics on capture k are FIFO-lagged.** `startDraftSequence` installs a snapshot and
   hands back the *oldest queued* decision, so the row persisted on capture k was priced with the
   model state of a strictly earlier draft start. Replaying per capture makes every reserve wrong.
   Replay in event order (`decision` / `draft start` / `draft end`) and the draft-sequence key
   matches 1717/1717.
4. **A blank `durationMs` means 0 ms, not "did not run"** on an admitted, non-timed-out node — the
   exporter writes `durationMs.takeIf { it > 0L }`. And `DECODING` occupies the same key twice in one
   chain, where last write wins. `admit == false` genuinely records nothing.
5. **Kotlin's `sumOf` is uncompensated.** CPython's `sum()` has been Neumaier-compensated since 3.12
   and picks a different sample at `quantile`'s `cum >= target` tie. Use `rmodel.naive_sum`.
6. **A draft ending after a pacer session opened feeds that session's maximum**, even when no session
   was open at its own draft start (`pacerSessionId` is null there). Hence the forward-filled session
   id in `rpacer2.effective_sessions`.
7. **`timeToDeadlineMs` is taken from the recording.** It is stamped by `addRequest`, whose time the
   export does not carry, and the newest committed capture at a decision is not at a fixed offset
   from the consuming one (measured offsets range −5..+3, scattered).

`clock_price='P0'` reproduces two shipped quirks a per-draft price cannot express:
`queuePacingDecision` ceils each queued draft's own `predicted + overhead`, while
`rebaseBacklogClock` takes ONE ceil of the whole sum and charges every queued draft the *newest*
snapshot's overhead. Any other `clock_price` switches to the per-draft form — same error
distribution (bias −126 vs −128 ms, MAE 495 both), different individual rows. **Only the P0 arm is
a fidelity gate.**

---

## 4. Methodology — what is and is not identifiable

### 4.1 The trace-conditioned contract

The exporter's own `ReplayNotes` sheet states it: a Full trace cannot reconstruct a factual
Baseline / Admission-only / Pacing-only outcome after the first policy action that differs, because
that action changes later arrivals, deadlines, queue state, thermal state, executed workloads and
predictor learning. Anything replayed past that point must be labelled
`TRACE_CONDITIONED_ESTIMATE`, **never empirical timeout data**.

Held fixed in every counterfactual arm here: draft walls, decision times, capture deadlines, the
learned between-node overhead (no change touches it), and the session max draft wall (a physical
observable, recovered from the recording via `reserved / 1.1`).

### 4.2 Ground truth that needs no counterfactual

`realQueueWaitMs = draftStart − decision − appliedDelay` is already exported. The clock's
`backlogEnd` is meant to predict when the pipeline frees up for the draft this decision admits,
i.e. `draftStart(k)`; in backlog-relative terms the target is **`realQueueWaitMs +
appliedDelayMs`** — the applied delay must be added back. This makes clock accuracy measurable on
n=1717 with no counterfactual at all. **This is where the statistical power is.** Prefer
mechanism-level claims scored this way over outcome-level (timeout-count) claims.

### 4.3 The two-bound margin accounting, and its assumption

For the timed-out capture k*: `margin(k*) = deadline(k*) − draftEnd(k*)`, and
`deadline(k*) = shutter(k*) + 7000`. Extra pacing delay released before `shutter(k*)` pushes that
shutter — and the deadline — out by that much. `draftEnd(k*)` is set by the draft server, which is
continuously busy through k* in all 14 runs (inter-draft gap median 13–28 ms), so its completion
time is the sum of the service times ahead of it and does not move when arrivals shift later. Hence
`margin'(k*) = margin(k*) + (extra delay released before shutter(k*))`.

Two bounds are reported everywhere:
- **lo** — only extra delay actually released before the timed-out shutter counts. Conservative.
- **hi** — every extra ms of delay in the run converts to deadline headroom. Loose upper bound,
  **not a prediction.** (An earlier version of this analysis reported `hi` alongside `lo` in a way
  that read as "all timeouts prevented". It is not.)

And `timeToDeadlineMs` is run two ways, because injected delay pushes later deadlines out and that
feeds back negatively on the next decision:
- **frozen** — recorded ttd. Over-states the new delay (ignores the feedback) → optimistic.
- **relaxed** — recorded ttd plus the extra delay already injected in the run → pessimistic.

**The load-bearing assumption is that draft walls stay fixed.** They will not: pacing more gives
more thermal recovery, so real walls would be *shorter*. That makes the estimates conservative in
the helpful direction — but it is an assumption, not a measurement, and it breaks entirely for any
change that alters the executed workload (§6.2).

### 4.4 Known weaknesses of what was done

State these plainly in any writeup:

- All 14 timeout events come from **one workbook**. Holdouts untouched (§2).
- Candidate clocks were selected **in-sample**: 7 prices were scored on those 14 timeouts and the
  winner picked. Differences of 1–3 timeouts at n=14 are noise-level; the ranking may not survive
  the holdout.
- The hot-start / cold-start discriminator (§5.4) was found **post hoc** by inspection.
- The "baseline" arm is a **reconstruction** of a build we do not have, with unexplained residue
  (66 reserve rows, and the ×1.05 bound rule).
- The two shipped changes have an **ordering dependency** with the proposed clock work, so effects
  cannot be attributed independently.

---

## 5. Findings

Ordered by how much weight they can carry.

### 5.1 STRONG — the backlog clock is a biased estimator (n=1717, no counterfactual)

Scored against §4.2 ground truth:

```
clock                                   bias   p05err  p50err  p95err   MAE   under%
recorded (== S0_P0, current)            -128   -1419     -99   +1054    495    57.7%
P5  (predicted+overhead) x learned        -21   -1309     +10   +1274    496    49.2%
      wall/priced ratio
P6  max(P0, freshest completed wall)      +43   -1189     +51   +1314    484    45.7%
P2  max(P0, recency-mean of walls)       +237   -1354    +168   +1772    645    36.5%
P4  predicted*exp(residual)+overhead     +491    -908    +326   +2200    741    26.6%
P3  max(P0, expectedMaximum of walls)   +1032   -1042    +410   +3986   1299    23.4%
P1  max(P0, session max wall)           +1336   -1009    +520   +5080   1593    22.1%
```

The current clock **under-reads the real queue 57.7% of the time**, by up to 3.0 s
(`backlogEstimateErrorMs` over all 1717 rows: p05 −1363, p50 −75, p95 +1084, min −3022). On 8 of
the 14 timed-out captures it under-read, by up to 2.4 s. Capture 558 timed out with margin −284 ms
having applied **zero** delay all run, because the clock projected 2829 ms of backlog against a real
5253 ms.

**Root cause of the low bias:** `overhead` is learned as `draftWall − actual nodeSum` but is added
to the *predicted* nodeSum. When `predicted` under-predicts, the overhead does not compensate and
the error passes straight through. That is a composition defect, and `P5` (learn `wall / (predicted
+ overhead)` and correct by it) is its direct fix.

### 5.2 STRONG (negative) — the clock's *structure* is not the problem

`S1` = exact forward Lindley recursion, recomputed on demand, seeded by the running draft's
completion (`cursor = max(now, runningStart + price)`, then each queued draft
`cursor = max(cursor, release) + price`). Result: **`S1 ≈ S0` for every price** (e.g. `S1_P0` bias
−116 vs `S0_P0` −126, MAE 487 vs 495). This kills the whole "restructure the clock" family,
including the hypothesis that an in-flight draft's overrun is lost when its remaining time hits 0.
Only the *price* is a lever.

Note: an earlier wrong version of `S1` took a max *against* the running draft instead of stacking
the queue on top of it, which silently dropped the entire queue and produced −1.2 to −2.3 s bias.
If you re-derive this, check that the running draft's completion **seeds** the recursion.

### 5.3 STRONG — the reverted change never fires where it would matter

The "no OPTIONAL workload left → apply the whole deficit" rule:

- narrow form (every workload RESERVED, i.e. Encoding-only): **0 / 1717** decisions fire.
- broad form (no workload with `OPTIONAL` policy): **401 / 1717 (23.4%)** fire — the shape is
  `DYNAMIC_FUNCTION>ENCODING`, since `DynamicFunction` is REQUIRED and never demotes. Recorded draft
  shapes: 1204 full chain, 401 `DYNAMIC_FUNCTION>ENCODING`, 105 no-Bokeh, 7 `BOKEH>DYNAMIC_FUNCTION>ENCODING`.
- but **0 firing decisions occur inside any of the 14 timeout runs.**
- that shape first appears at **shot 10 at the earliest, median shot 14**, while the timeout runs end
  at shots 7, 8, 9, 9, 10, 11, 11, 14, 20, 22, 22, 23, 25, 26.
- **26 of 69 runs reach the shape and none of them timed out** — it is a survival signature, not a
  distress one. In the timeout runs, 176 of 178 pacing decisions still carried the full undemoted
  chain.
- where it does fire, 345 of 401 decisions had a non-positive projected deficit anyway (the demoted
  draft is cheap: reserve p50 857 ms vs 1011 ms), so there was nothing to double. Only 56 gained delay.
- cost if shipped: +4.5% total delay. Benefit: 0/14 timeouts, both bounds.

### 5.4 MEDIUM (post-hoc discriminator) — two different failure modes

Splitting the 14 timeout runs by whether the P6 clock + change 1 clears them:

| | n | starting overheat level | level at timeout | shots | draft wall | shot cadence | queue growth/shot | shots to 7 s |
|---|---|---|---|---|---|---|---|---|
| cleared | 6 | [1,1,2,2,2,2] med **2** | all 3 | 20–26 | 747 ms | 572 ms | **+175 ms** | 40 |
| remain | 8 | [3,3,4,5,6,6,6,6] med **6** | 3,3,4,6,6,6,6,6 | 7–14 | 1024 ms | 541 ms | **+484 ms** | 14.5 |

No overlap in starting level. Cold-start bursts *warm into* a timeout (level 1→3) over 20+ shots, so
the pacer has runway. Hot-start bursts begin already throttled and die at shot 7–14 at the same
level — no ramp to observe, and no runway. The shot-count correlation is a consequence of this, not
a separate fact.

For reference, starting level over all 69 runs — level alone does **not** predict a timeout:
timeout runs `{1:2, 2:4, 3:2, 4:1, 5:1, 6:4}`; no-timeout runs `{0:5, 1:5, 2:3, 3:11, 4:12, 5:11, 6:8}`.

Also measured: in hot-start runs the clock is already accurate from shot 3 (1331 ms priced vs
1255 ms actual, 6% *over*). The hot-start problem is runway, not pricing. A cold-start-pricing
hypothesis was tested and rejected.

### 5.5 MEDIUM — admission's budget is exactly right, and the wrong quantity

Over the 395 OPTIONAL admission decisions in the 8 hot-start timeout runs:

- `budgetMs` equals `deadline − nodeStart` on **395 / 395** rows (median 2419 ms, min 291, max 6631).
  There is no missing-deadline default, no bug.
- 77 / 395 were rejected because `upperBound > budgetMs`; 91 / 395 have `admit == false` overall.
- but `budgetMs = deadline − now` is **backward-looking**: it shrinks only after the queue wait has
  already been paid, and never accounts for work already committed *behind* this capture. Early in a
  burst the budget is 5000–6600 ms against a full-chain upper bound of 1400–2600 ms, so the gate
  passes trivially. By the time the budget is small enough to reject, the queue is already deep.

The pacer's projected deficit turns positive **earlier** than admission rejects:

| | admission first rejects | deficit first > 0 | lead |
|---|---|---|---|
| hot-start (8 runs) | shot 5–12 | shot 4–6 | **+0 … +3** |
| cold-start (6 runs) | shot 17–20 | shot 11–16 | **+4 … +8** |

### 5.6 MEDIUM — shedding is a far cheaper lever than pacing, for hot bursts

Measured draft walls by executed shape × thermal band:

| executed shape | band | n | p50 | p95 |
|---|---|---|---|---|
| full chain (BOKEH+DECODING+DYNAMIC_FUNCTION+ENCODING+FILTER+WATERMARK) | hot (lv≥3) | 823 | **906** | 1490 |
| no BOKEH (DECODING+DYNAMIC_FUNCTION+ENCODING+FILTER+WATERMARK) | hot | 170 | **724** | 1018 |
| DYNAMIC_FUNCTION+ENCODING | hot | 634 | **486** | 709 |
| full chain | cool (lv≤2) | 331 | 625 | 834 |

At the hot-start cadence of 414–498 ms, shedding moves queue growth from +364…+492 ms/shot to
+38…+72 ms/shot (3 of 8 runs converge outright), pushing the deadline crossing from ~14 shots out to
**97–185 shots** — beyond any 30-shot burst. All 8 stabilise.

To get the same stability from pacing alone you would have to raise the cadence from 541 ms to
≥1024 ms, i.e. **+483 ms on every shot from shot 1** — nearly doubling shot-to-shot time.

Note the built-in graceful progression: the suffix head is evaluated first, so BOKEH (largest upper
bound) sheds first (906→724 ms) and DECORATION only follows under continued pressure (→486 ms).

### 5.7 WEAK (in-sample, n=14) — active clock arms

Each candidate clock driving the delay, change 2 excluded (it is reverted). "OK runs" = the 55 runs
that never timed out, i.e. delay paid for nothing. **All arms including the baseline use the
per-draft price structure (`clock_price='P0x'`) so the price is the only thing that varies** — do
not mix in the bit-exact P0 baseline here, it shifts individual rows by up to 185 ms and moved
P5's verdict by one run when accidentally used.

| arm | total delay | vs base | p50 on OK runs | p95 on OK runs | TO lo | TO hi |
|---|---|---|---|---|---|---|
| P0 (current) | 406 s | +0% | **0** | 927 | 0/14 | 0/14 |
| P5 alone | 476 s | +17% | 6 | 1061 | 3/14 | 8/14 |
| P6 alone | 513 s | +26% | 49 | 1134 | 5/14 | 10/14 |
| P2 alone | 611 s | +50% | 220 | 1172 | 6/14 | 12/14 |
| P4 alone | 800 s | +97% | 250 | 1526 | 6/14 | 11/14 |
| P3 alone | 1354 s | +233% | 713 | 2162 | 7/14 | 14/14 |
| P1 alone | 1785 s | +339% | 1034 | 2729 | 7/14 | 14/14 |
| **P5 + change 1** | 515 s | +27% | 15 | 1184 | 4/14 | 7/14 |
| **P6 + change 1** | 528 s | +30% | 52 | 1156 | 6/14 | 11/14 |
| **P2 + change 1** | 659 s | +62% | 250 | 1302 | 6/14 | 12/14 |

Under the pessimistic relaxed-ttd arm the same combinations give: P5 + c1 → **3/14** (total 292 s),
P6 + c1 → **4/14** (264 s), P2 + c1 → **5/14** (247 s). So the honest bands against 0/14 today are
**P5 + c1: 3–4**, **P6 + c1: 4–6**, **P2 + c1: 5–6** of 14.

Note the efficiency frontier: P3 and P1 buy one extra timeout for 4–6× the delay and a p50 cost of
0.7–1.0 s on healthy bursts. They are not worth it.

Mechanism confirmed: the better clocks fire **earlier**, so the delay lands on shutters that have not
happened yet. Onset shot moves e.g. run 22 never→11–14, run 53 16→11, run 55 13→10, run 69 13→10.
This matters because in most timeout runs the captureAvailable callbacks lag the shutters by 2–5 s,
so a delay decided late in the run cannot move the timed-out capture's deadline at all.

Change 1's own effect, for reference: total pacing delay **+6.7%** (406,435 → 433,526 ms); delay
rises on 479 decisions, falls on 364, unchanged on 874; `Δpredicted` p05 −108, p50 0, p95 +131 ms.
It is *more responsive*, not uniformly more conservative. Admission gate flips 170/9841 = 1.73%.

---

## 6. Open decisions

### 6.1 Backlog clock — decide the semantics first, then the statistic

**This is a design decision, not a data question.** The code comment on `backlogEndTimeMs` says the
clock is *"an estimate of elapsed work rather than a safety bound"*, with bounds living in the
reserve. Two coherent positions:

- **Clock stays an unbiased estimate** → **P5** (`(predicted + overhead) × learned wall/priced
  ratio`). Fixes the composition defect at its root (§5.1), keeps the semantic role the code comment
  declares, uses the existing `RecencyWeightedDistribution` idiom, cheapest cost (+27% with change 1,
  p50 15 ms on healthy runs). Weakest outcome: **3–4 of 14**.
- **Clock becomes a conservative price, consistent with the reserve** → **P2** (`max(predicted +
  overhead, recency-weighted mean of observed walls)`). The reserve already prices a draft as
  `max(observed wall − demoted, estimate)`; two quantities describing the same physical thing should
  use the same estimator. Best outcome of the defensible options: **5–6 of 14**, but +62% delay and
  p50 250 ms on healthy runs.

**Do not ship P6** (`max(predicted + overhead, freshest completed wall)`). It scores between the two
(4–6 of 14 at p50 52 ms) and was recommended earlier in the session, but on mechanism grounds it is
the weakest candidate: `max(estimate, single latest observation)` silently converts the clock from an
estimate into a bound, and using one sample as a floor bypasses the codebase's own "every learned
trend keeps a `RecencyWeightedDistribution`" idiom, making the clock jumpy on one slow draft (a GC
pause or storage hiccup inflates the next decision). It only wins on the metric that was over-fitted.
If the answer is "price, not estimate", P2 is the principled version of the same idea.

Implementation sketch (either choice): give the snapshot a per-draft clock price, which *simplifies*
`CaptureAvailablePacingSession` — `queuedPredictedWorkMs + overhead * size` collapses into one sum of
prices. `rpacer2.Session` already contains both structures; mirror the non-exact branch.

### 6.2 Exposing backlog to admission — blocked

Proposed rule:

```kotlin
internal fun admitsOptionalWorkload(
    workloadSequencePredictedMs: Double,
    workloadSequenceUpperBoundMs: Double,
    budgetMs: Long,
    queuePressureMs: Long,
): Boolean = workloadSequencePredictedMs <= 0.0 ||
    workloadSequenceUpperBoundMs <= budgetMs - queuePressureMs

// queuePressureMs = max(0, backlogMs + draftSequenceReservedDurationMs - timeToDeadlineMs)
```

Plumbing: subtract in `DraftSequenceExecutionProfiler.readBudgetMs()`, which already owns both the
pacer and `captureMetrics`, so the predictor stays pure and the stateless replay gate stays a pure
function. `queuePressureMs == 0` reproduces today's behaviour exactly.

Measured cost: the deficit crosses zero in 68–69 of 69 runs at median shot 9, so healthy runs would
shed effects for 22 of 31 shots — against **19 of 31 today**, i.e. roughly **3 extra shots**. Smaller
than it first appears, because admission already sheds aggressively in long bursts.

**Why it is blocked, not merely unfinished:**

1. **Two controllers on one error signal.** The pacer already acts on that deficit by delaying. The
   existing `no half-deficit value is passed to Admission` comment is not conservatism — read again,
   it is a controller-conflict guard. Removing it needs an interaction analysis that does not exist.
2. **It penalises the wrong capture.** `queuePressure` is caused by drafts *behind* this one;
   rejecting *this* capture's optional work degrades this photo to protect later ones. Defensible as
   a product call, not obviously correct as a mechanism.
3. **It reads the signal we just called broken.** Gating quality on a clock that under-reads by up to
   2.4 s is building on sand. **§6.1 is a hard prerequisite.**
4. **It breaks the measurement.** Shedding changes the executed workload, hence draft durations,
   hence the only accounting available (§4.3). Verification would have to fall back to the growth
   arithmetic of §5.6 rather than the margin accounting.

---

## 7. Traps

Things that fail silently.

1. **Do not replay per capture.** The pacing row on capture k was priced by an earlier draft start's
   snapshot (§3, item 3). Per-capture replay makes every reserve wrong while looking plausible.
2. **Do not forget the two build factors** (§2). Without ×1.1 the reserve identity fails on ~96% of
   rows; without ×1.05 the bound is nonsense.
3. **Compare `backlogMs` against `realQueueWaitMs + appliedDelayMs`**, not `realQueueWaitMs`
   (§4.2). Getting this wrong flipped the measured bias sign in an early pass.
4. **`git checkout -- .` restores from the index, not HEAD.** Something in this environment stages
   modifications; `git reset` first. A backup-and-restore cycle in this session silently dropped two
   unrelated KDoc blocks from `CaptureAvailablePacingSession.kt` — caught in review before pushing,
   but check `git diff` removed-lines per file before every commit.
5. **Long heredocs to `bash` truncate** and fail with `unexpected EOF`. Write patch scripts to a file
   and run them.
6. **`inspect.py` as a filename shadows the stdlib** and breaks openpyxl's import.
7. **`Capture` sheet has no `isLowMemory` column** — it is on the node sheets and `RQ3Pacing`.
8. **The `hi` bound is not a prediction** (§4.3). Report `lo`.
9. **`.codex_sheet_analysis/` is gitignored.** The harness will not travel with a `git clone`.

---

## 8. Recommended next steps, in order

1. **Build the two pushed commits.** Nothing here was compiled. `data object` requires Kotlin ≥1.9,
   which the pre-change `SizeBucket.entries` usage implies but does not prove.
2. **Reconcile the ×1.1 / ×1.05 device-build factors** with whoever owns the build, and decide
   whether they belong in the repo. Until then no replay of new data is trustworthy.
3. **Decide §6.1: is the clock an estimate or a price?** Then implement exactly one of P5 / P2.
   Do not ship P6.
4. **Validate on the holdout before believing any ranking.** Re-run `rclock.py` / `rclockarm.py`
   against `24MP_memory_0829.xlsx` (9 timeouts, 60 runs) and
   `12MP_normal_baseline_0829.xlsx` (46 timeouts, 59 runs). The 1–3 timeout differences that
   separated the candidates are noise at n=14.
5. **Get an identifiable measurement instead of a counterfactual.** A clock change is
   *pacing-internal* — it does not alter executed work — so a dedicated run with the new clock on vs
   off is a clean A/B: same workload shot by shot, only arrival times differ. That is the one
   experiment that would settle §6.1 empirically rather than as a trace-conditioned estimate. The
   `pacing_only` / `admit_only` arms show this protocol already exists.
6. **Only then revisit §6.2**, with a controller-interaction argument written down first.

For a paper, the defensible material is the **diagnosis**, not the fixes: the clock is measurably
biased against its own exported ground truth (n=1717, no counterfactual); the clock's structure is
not the lever, only its price (negative result); admission's budget is correct but backward-looking
(n=395); the projected deficit leads admission by 1–8 shots; and hot-start and cold-start bursts fail
by different mechanisms. Every fix number in §5.7 is a `TRACE_CONDITIONED_ESTIMATE` and must be
labelled as one.

---

## 9. Memory notes written this session

`C:\Users\algus\.claude\projects\C--Users-algus-Documents-ML\memory\`

- `device-build-ahead-of-repo-0829.md` — the ×1.1 / ×1.05 / no-sizeBucket discovery and what it
  invalidates.
- `backlog-underprediction-dominates-timeouts.md` — the under-read measurements, the
  captureAvailable-lags-shutter finding, and the reverted change marked "do not re-propose without
  new data".

Both are indexed in `MEMORY.md`.
