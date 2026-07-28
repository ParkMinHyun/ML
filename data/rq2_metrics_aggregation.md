# RQ2 Admission Table Calculation

## Scope

This document records how the values in `tables/tab_rq2.tex` were calculated.
The tables are a pilot analysis of the current Full-controller traces, not yet the
planned Always-admit audit. Consequently, executed admits can receive factual
labels, whereas skipped optional groups cannot.

The manuscript separates RQ2 evidence into two tables. The first combines
factual admit outcomes with predicted-upper-bound calibration before and after
warm-up. The second reports dagger-marked strict immediate-previous-shot proxy
classifications for actually skipped work. Calibration explains the decision
mechanism but is not itself a decision-correctness outcome, and the proxy table
is sensitivity evidence rather than a factual counterfactual.

The tables report one device because the admission-policy implementation is
shared. The measured execution times and calibration results remain specific to
the evaluated device and conditions.

## Input workbooks

### 12MP normal

1. `48U_metrics_12MP_normal_0727_1.xlsx`
2. `48U_metrics_12MP_normal_0727_2.xlsx`
3. `48U_metrics_12MP_normal_0727_3.xlsx`

### 24MP memory pressure

1. `48U_metrics_24MP_memory_0727_1.xlsx`
2. `48U_metrics_24MP_memory_0727_2.xlsx`
3. `48U_metrics_24MP_memory_0727_3.xlsx`
4. `48U_metrics_24MP_memory_0727_4.xlsx`

The calculation reads `AdmissionReplay` for admission decisions and
`PacingReplay` for Draft completion and capture-timeout outcomes.

## Experimental-session reconstruction

The current exporter does not contain a separate experiment-run identifier.
Rows were ordered by `captureIndex`, and a new session was inferred whenever
`ppSequenceId` did not increase. Only the first 30 captures of each inferred
session were included.

The three 12MP workbooks contained 45 inferred sessions in total. The first
three 24MP workbooks contained 29 sessions. The fourth 24MP workbook contained
14 sessions, of which the following three were excluded in full because they
contained `beforeCaptureTimedOut = true`:

| Inferred session | Starting overheat level | Timeout shot |
|---:|---:|---:|
| 7 | 2 | 18 |
| 9 | 3 | 12 |
| 13 | 3 | 22 |

Thus, the 24MP calculation retained 40 inferred sessions: 29 from the first
three workbooks and 11 from the fourth workbook. No capture-timeout session was
found in the three 12MP workbooks or the first three 24MP workbooks.

## Decision unit

Exporter rows are not treated as independent decisions. Each capture contributes
at most two group-entry decisions:

- **Multi-frame group:** the Bokeh row, which is the entry of the `PORTRAIT`
  admission group.
- **Single-frame group:** the earliest Decoding, Filter, or Overlay Watermark
  row, which is the entry of the `DECORATION` admission group.

Later rows in the same group are excluded to avoid counting one sticky group
decision multiple times.

## Factual admission outcome

For an admitted group decision, the remaining budget and factual remaining wall
time are:

```text
remaining budget = beforeBudgetMs
remaining wall time = draftEndUptimeMs - nodeStartUptimeMs
```

`beforeSequenceActualDurationMs` was not used because it sums node durations and
does not directly represent the remaining wall-clock time to Draft completion.

The outcome classes are mutually exclusive:

```text
successful admit:
    no watchdog and remaining wall time <= remaining budget

watchdog-contained unsafe admit:
    watchdog invoked

deadline-miss unsafe admit:
    no watchdog and remaining wall time > remaining budget
```

If an admitted group hit the watchdog, its forced workload continued outside
the measured Draft path and its late completion cost was unavailable. The event
is nevertheless unsafe for RQ2 because the admitted optional feature did not
complete within its protected allowance. The word *contained* records the
different RQ1 fact that the safeguard prevented a Capture Timeout; it does not
turn the event into a correct admission.

The current Full-controller traces do not execute skipped groups. Therefore,
they cannot factually determine whether skipped work would have completed
before the deadline or overrun it. The manuscript fills those cells with
dagger-marked strict previous-shot proxy estimates, but does not present them as
factual ground truth. Always-admit audit data are still required for factual
counterfactual outcomes.

## Factual admission outcomes and skip-consequence definitions

All admission-outcome rates use all effective admits of the corresponding group
as their denominator:

```text
outcome rate = admits in the outcome class / all effective admits * 100
```

Intermediate decision counts are:

| Condition | Optional group | Group decisions | Effective admits | Effective skips | Successful admits | Watchdog-contained unsafe admits | Deadline-miss unsafe admits |
|---|---|---:|---:|---:|---:|---:|---:|
| 12MP normal | Multi-frame | 1,341 | 828 | 513 | 827 | 1 | 0 |
| 12MP normal | Single-frame | 1,340 | 1,297 | 43 | 1,297 | 0 | 0 |
| 24MP memory pressure | Multi-frame | 1,193 | 554 | 639 | 553 | 1 | 0 |
| 24MP memory pressure | Single-frame | 1,192 | 1,047 | 145 | 1,047 | 0 | 0 |

Factual admission-outcome values copied to the manuscript table are:

| Condition | Optional group | Factual successful / unsafe admit | Watchdog activations associated with the unsafe result |
|---|---|---:|---:|
| 12MP normal | Multi-frame | 99.9% / 0.1% | 1 |
| 12MP normal | Single-frame | 100.0% / 0.0% | 0 |
| 24MP memory pressure | Multi-frame | 99.8% / 0.2% | 1 |
| 24MP memory pressure | Single-frame | 100.0% / 0.0% | 0 |

In the manuscript cell, the watchdog activation count appears in parentheses
after the percentage pair and belongs to the right-hand unsafe-admit result.
The underlying successful/unsafe event counts remain available in the
intermediate decision-count table above but are not printed in the manuscript.

For the Always-admit audit, let `C` be the factual forced-execution cost of a
group that the shadow controller would have skipped. Its deadline-relative
outcome is:

```text
completed before deadline when C <= B:
    remaining margin = B - C

deadline overrun when C > B:
    overrun magnitude = C - B
```

The factual Always-admit audit will report the median and inclusive 95th
percentile of each positive magnitude. A large overrun magnitude indicates that
executing the skipped group would have caused a substantial deadline violation.
A large remaining margin indicates that the group would have completed well
before the deadline. Counts report how often each outcome occurred; the
millisecond magnitudes report its severity. The second manuscript table maps the
positive-margin subset to `Over-conservative skip` and reports its median unused
deadline margin. It maps the negative-margin subset to `Deadline-protecting
skip` and reports its median avoided overrun. The dagger, rather than the column
wording, marks that both classifications are sensitivity proxies rather than
factual counterfactual executions. Counts, rates, and 95th percentiles are
retained here for reproducibility but omitted from the manuscript presentation.

For a skipped group estimated to complete before the deadline, predictor
conservatism can additionally be diagnosed as `predicted upper bound - C`, but
this is secondary: `B - C` more directly measures the remaining deadline margin.

## Previous-shot proxy sensitivity analysis

The second RQ2 table reports sensitivity classifications for skipped work.
Every proxy-derived skip column is marked with a dagger so it cannot be mistaken
for a factual counterfactual. The target event is work that the controller
actually skipped, but its displayed value is not observed by executing that
work. A skipped decision at shot `t` is assigned a proxy cost only when the same
optional group actually completed without a watchdog in the immediately
preceding shot of the same inferred session:

```text
proxy eligible:
    same group was admitted and completed without a watchdog at shot t - 1

proxy cost:
    C_hat(t) = C(t - 1)

estimated before-deadline completion:
    C_hat(t) <= B(t)
    remaining margin = B(t) - C_hat(t)
    normalized margin = 100 * (B(t) - C_hat(t)) / B(t)
    manuscript classification = over-conservative skip

estimated deadline overrun:
    C_hat(t) > B(t)
    overrun magnitude = C_hat(t) - B(t)
    normalized overrun = 100 * (C_hat(t) - B(t)) / B(t)
    manuscript classification = deadline-protecting skip
```

The normalized proxy magnitudes require `B(t) > 0`. If `B(t) <= 0`, the event
retains its millisecond classification and magnitude but is excluded from the
normalized-percentage median because a nonpositive remaining deadline budget is
not a meaningful normalization denominator. No such event occurred: all 93
strict proxy events had a positive `B(t)` (0/34, 0/5, 0/42, and 0/12
nonpositive-budget events in table order). Millisecond and percentage medians
are computed independently from their event-level arrays; one aggregate median
is never divided by another aggregate value.

The current shot's budget is used, so the proxy asks whether the immediately
preceding observed cost would have fit the current decision. Proxy coverage is
the number of proxy-labeled skips divided by all effective skips. Outcome rates
and consequence percentiles are conditional on the proxy-labeled subset, not
all skips. The same timeout-session exclusions and group-entry decision units
used by the factual table are applied.

| Condition | Optional group | Proxy coverage, rate (labeled/all skips) | Estimated before-deadline completion, rate (count) | Estimated deadline overrun, rate (count) | Remaining margin, median / 95th percentile | Overrun magnitude, median / 95th percentile |
|---|---|---:|---:|---:|---:|---:|
| 12MP normal | Multi-frame | 6.6% (34/513) | 38.2% (13) | 61.8% (21) | 65 / 225 ms | 133 / 970 ms |
| 12MP normal | Single-frame | 11.6% (5/43) | 40.0% (2) | 60.0% (3) | 75 / 131 ms | 17 / 74 ms |
| 24MP memory pressure | Multi-frame | 6.6% (42/639) | 45.2% (19) | 54.8% (23) | 253 / 800 ms | 120 / 576 ms |
| 24MP memory pressure | Single-frame | 8.3% (12/145) | 83.3% (10) | 16.7% (2) | 87 / 332 ms | 190 / 317 ms |

Event-level normalization audit values before manuscript rounding are:

| Condition | Optional group | Nonpositive budget / strict proxy events | Before-deadline subset, count | Median margin (ms) | Median margin (% of budget) | Overrun subset, count | Median overrun (ms) | Median overrun (% of budget) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 12MP normal | Multi-frame | 0 / 34 | 13 | 65.000 | 6.021% | 21 | 133.000 | 15.118% |
| 12MP normal | Single-frame | 0 / 5 | 2 | 74.500 | 10.372% | 3 | 17.000 | 5.842% |
| 24MP memory pressure | Multi-frame | 0 / 42 | 19 | 253.000 | 12.886% | 23 | 120.000 | 11.498% |
| 24MP memory pressure | Single-frame | 0 / 12 | 10 | 87.000 | 9.930% | 2 | 190.000 | 13.925% |

The manuscript projects the reproducibility table above into the following
compact second-table format. Counts, outcome-rate percentages, proxy coverage,
all-skip denominators, and 95th percentiles are deliberately not copied into it;
the displayed percentages instead normalize magnitude by decision-time budget:

| Condition | Optional group | Over-conservative skip$^\dagger$, median unused deadline margin (ms / % of decision-time budget) | Deadline-protecting skip$^\dagger$, median avoided overrun (ms / % of decision-time budget) |
|---|---|---:|---:|
| 12MP normal | Multi-frame | 65 / 6.0% | 133 / 15.1% |
| 12MP normal | Single-frame | 75 / 10.4% | 17 / 5.8% |
| 24MP memory pressure | Multi-frame | 253 / 12.9% | 120 / 11.5% |
| 24MP memory pressure | Single-frame | 87 / 9.9% | 190 / 13.9% |

Each manuscript pair contains independently computed subset medians: ms on the
left and event-level percentage of `B(t)` on the right. Without the omitted
subset counts, these values describe conditional severity only; they do not
compare outcome frequency or establish overall skip accuracy.

This proxy preserves temporal locality but covers only 6.6--11.6% of skips and
does not observe the skipped work in the same shot. It may therefore be biased
when workload, temperature, or throttling changes between adjacent shots. Its
results fill the pilot table only as explicitly marked sensitivity estimates;
they do not become factual skip outcomes or replace the Always-admit audit.

A carry-forward variant using the most recent earlier execution was also
checked but deliberately omitted from the manuscript table. Although it nearly
eliminated missing proxy labels, its median observation age was 5--9 shots, its
95th-percentile age was 10--19 shots, and its maximum age was 12--27 shots,
depending on condition and group. It classified 89.5--98.6% of proxy-labeled
skips as before-deadline completions, far above the strict previous-shot
estimates. That divergence is evidence of stale-observation sensitivity rather
than a factual counterfactual, so those values are not reported as RQ2 outcomes.

The deadline-miss rates are conditional on retained non-timeout sessions because
the three timeout-bearing sessions from the fourth 24MP workbook were
excluded as requested. A final paper should either retain those failures in a
sensitivity analysis or state this conditioning explicitly; otherwise the zero
rate can appear more reassuring than the experiment supports.

## Actual-cost coverage by the predicted upper bound

For every currently admitted decision with a factual non-watchdog outcome:

```text
upper-bound covered = remaining wall time <= beforeSequencePredictedUpperBoundMs
upper-bound underestimation
    = max(0, remaining wall time - beforeSequencePredictedUpperBoundMs)

normalized upper-bound underestimation
    = 100 * max(0, remaining wall time - beforeSequencePredictedUpperBoundMs)
      / remaining wall time
```

Equivalently, for factual actual cost `C` and upper bound `U`, the two
amount-above-upper-bound event values are `d_ms = max(0, C - U)` and
`d_pct = 100 * max(0, C - U) / C`. For `C = 0` with `d_ms = 0`, `d_pct` is
defined as 0%. A `C = 0` event with a positive deficit is treated as invalid and
excluded from the percentage percentile while its millisecond deficit is
retained; negative `C` is likewise invalid for normalization. No zero or
negative `C` occurred among the 3,554 pre/post calibration events, so the ms and
percentage percentiles use identical event counts here.

The calibration periods are:

- First shot: shot 1 within an inferred session.
- Early warm-up: shots 2--5 within an inferred session.
- Post-warm-up: shots 6--30 within an inferred session.

Actual-cost coverage is the percentage of factual executions whose predicted
upper bound covers the factual remaining wall time. It diagnoses predictor
calibration; it is not itself an admission-correctness rate because a coverage
miss can still fit within the remaining budget. The amount-above-upper-bound
columns report the inclusive 95th percentile of `d_ms` and `d_pct` in each
warm-up period. Covered executions contribute zero to both arrays; the two
percentiles are computed independently at event level, rather than dividing an
ms percentile by a representative cost. Manuscript ms values are rounded to the
nearest millisecond and percentages to one decimal. First-shot coverage is
retained in this audit document but omitted from the manuscript table: every
session began with an uninitialized zero upper bound, so all four first-shot
coverage values are identically 0% and do not differentiate conditions or
optional groups.

Intermediate sample counts and covered counts are:

| Condition | Optional group | First shot | Shots 2--5 | Shots 6--30 |
|---|---|---:|---:|---:|
| 12MP normal | Multi-frame | 0 / 45 | 132 / 178 | 530 / 604 |
| 12MP normal | Single-frame | 0 / 45 | 128 / 180 | 952 / 1,072 |
| 24MP memory pressure | Multi-frame | 0 / 40 | 111 / 149 | 308 / 364 |
| 24MP memory pressure | Single-frame | 0 / 40 | 113 / 155 | 761 / 852 |

Calibration values copied to the combined factual-admit/calibration table are:

| Condition | Optional group | Pre-warm-up actual-cost coverage | Post-warm-up actual-cost coverage | Pre-warm-up 95th-percentile amount above upper bound (ms / % of actual cost) | Post-warm-up 95th-percentile amount above upper bound (ms / % of actual cost) |
|---|---|---:|---:|---:|---:|
| 12MP normal | Multi-frame | 74.2% | 87.7% | 194 / 22.6% | 39 / 4.2% |
| 12MP normal | Single-frame | 71.1% | 88.8% | 131 / 24.0% | 21 / 3.6% |
| 24MP memory pressure | Multi-frame | 74.5% | 84.6% | 352 / 29.2% | 141 / 10.2% |
| 24MP memory pressure | Single-frame | 72.9% | 89.3% | 208 / 24.3% | 65 / 9.4% |

Raw event-level percentile results before manuscript rounding are:

| Condition | Optional group | Pre events / nonpositive `C` | Pre P95 `d_ms` | Pre P95 `d_pct` | Post events / nonpositive `C` | Post P95 `d_ms` | Post P95 `d_pct` |
|---|---|---:|---:|---:|---:|---:|---:|
| 12MP normal | Multi-frame | 178 / 0 | 194.462 ms | 22.633% | 604 / 0 | 39.152 ms | 4.248% |
| 12MP normal | Single-frame | 180 / 0 | 130.888 ms | 24.030% | 1,072 / 0 | 21.421 ms | 3.558% |
| 24MP memory pressure | Multi-frame | 149 / 0 | 352.363 ms | 29.160% | 364 / 0 | 140.850 ms | 10.189% |
| 24MP memory pressure | Single-frame | 155 / 0 | 208.473 ms | 24.276% | 852 / 0 | 65.134 ms | 9.379% |

After the Always-admit audit, coverage and amount above upper bound should be
recomputed over all audited group entries, including shadow skip decisions.
Restricting calibration to the currently admitted subset is one-sided and can
hide overestimation on skipped cases.

The formerly reported 1,307 ms value for 24MP Multi-frame pooled all
shots and was dominated by cold-start error. In the same group, the first-shot
95th-percentile underestimation is 2,476 ms, the shots 2--5 value is 352 ms, and
the post-warm-up value is 141 ms. The updated table makes the zero-coverage cold
start explicit while avoiding the misleading interpretation that a 1,307 ms
tail persists after the predictor has warmed up.

## Metrics deliberately omitted from the main table

- First-shot upper-bound coverage is an invariant initialization result rather
  than a comparative metric. It is disclosed in the table note and retained in
  this calculation record.
- Factual-outcome coverage measures observability under the current controller,
  not whether its admissions are correct.
- Fifth-percentile deadline slack is omitted because, with the current exported
  definitions, `B - C` for an executed capture reduces to the same final Capture
  Timeout margin already reported as Slack P5 in RQ1. Repeating it for each
  optional group would duplicate an end-to-end metric rather than add
  admission-specific evidence.
- Median upper-bound excess is a secondary calibration diagnostic. The
  deadline-overrun magnitude and before-deadline remaining margin are more
  direct measures of decision consequence.
- Prior-demotion share among skips attributes decisions to the sticky policy but
  does not establish whether the skipped work would have met the deadline. It is
  useful only for a dedicated sticky-policy ablation or an appendix, so it is
  not a main RQ2 quality metric.

## Statistical interpretation

The displayed pilot percentages pool group-decision events after session-level
filtering. Captures within a session are correlated, so the final paper should
compute confidence intervals with sessions as clusters. After the Always-admit
audit is collected, the second table can replace the dagger-marked proxy estimates
with factual outcome-specific median magnitudes. Outcome frequencies would need
to be reported separately if they are used to claim overall skip accuracy. The
Always-admit run is also needed to remove the present one-sided observability
limitation.
