# RQ1 CaptureMetrics aggregation protocol

This document records the aggregation rule used for
`tables/tab_rq1.tex`. Apply the same rule to every added
workbook so that results remain comparable across devices, resolutions, and
test sessions.

## 1. Input data

Use the `Capture` and `PacingReplay` sheets exported by
`CaptureMetricsExcelExporter`.

Required `Capture` columns:

- `captureIndex`
- `ppSequenceId`
- `firstNodeOverheatLevel`
- `timeoutMarginMs`
- `isTimeout`
- `hasWatchdogTimeout`
- `bokehAdmitted`
- `bokehCompleted`
- `filterAdmitted`
- `filterCompleted`

Required `PacingReplay` columns:

- `captureIndex`
- `beforeAppliedDelayMs`

Join the two sheets by `captureIndex`. Treat a missing
`beforeAppliedDelayMs` as zero. Boolean values are true when exported as
`true`, `TRUE`, or `1`.

Do not mix device or scenario groups. The current table groups the workbooks
by `(device, resolution/scenario, starting overheat level)`.

Current input manifest:

- 12MP normal: `48U_metrics_12MP_normal_0727_1.xlsx`,
  `48U_metrics_12MP_normal_0727_2.xlsx`, and
  `48U_metrics_12MP_normal_0727_3.xlsx`
- 24MP memory pressure: `48U_metrics_24MP_memory_0727_1.xlsx`,
  `48U_metrics_24MP_memory_0727_2.xlsx`,
  `48U_metrics_24MP_memory_0727_3.xlsx`, and
  `48U_metrics_24MP_memory_0727_4.xlsx`

Use workbook basenames in the protocol; do not store machine-specific absolute
paths in the paper repository.

## 2. Inferring experiment runs

Process the `Capture` rows in worksheet order. Start a new run whenever the
current `ppSequenceId` is less than or equal to the preceding value. In
pseudocode:

```text
current_run = []
previous_pp = none

for capture in Capture rows:
    if current_run is not empty and capture.ppSequenceId <= previous_pp:
        emit current_run
        current_run = []
    append capture to current_run
    previous_pp = capture.ppSequenceId

emit current_run
```

The run's starting level is the first row's `firstNodeOverheatLevel`. This
reset-based inference is necessary because the current exporter has no
explicit experiment-run identifier. Record the inferred run count for every
workbook as an audit check.

## 3. Eligibility and invalid-run screen

The pilot table uses the following eligibility rule:

1. Inspect shots 1 through 30 of each inferred run.
2. Apply the maintained invalid-run manifest before computing aggregates. Do
   not infer invalidity from `isTimeout` or another measured outcome.
3. Retain every `isTimeout=true` row in the remaining runs and use it when
   computing Controller timeout E/M.
4. Do not exclude a run for `hasWatchdogTimeout=true`. A watchdog timeout is a
   protective node-level termination rather than a capture-timeout failure.
5. Include complete 30-shot runs. Per the current pilot convention, a
   29-record run is treated as a completed 30-shot run, and its 30-shot
   completion denominator is 29.
6. Mark any shorter non-timeout run as incomplete and exclude it from the
   table rather than silently padding it.

The current pilot manifest excludes runs 7, 9, and 13 from the fourth
24MP-memory workbook. No remaining included run contains a capture timeout,
so Controller timeout E/M is `--/--` in every row. Future exclusion manifests
must be fixed independently of the measured outcome, and the attempted-run
audit should retain included, invalid, timeout, watchdog, and incomplete counts.

## 4. Prefixes

Each slash-separated cell reports the first `5 / 10 / 30` shots. For a prefix
of k, use captures 1 through k, except for the accepted 29-record special case
described above.

The delay stored on capture i gates the transition to shot i+1. Therefore,
pacing statistics for the first k shots use the delay decisions on captures 1
through k-1.

## 5. Table metrics

### Controller timeout E/M

For every included run, find the first shot whose `isTimeout` value is true.
Runs without a capture timeout in the first 30 shots are right-censored at
shot 30.

`E` is the earliest observed first-timeout shot across runs. `M` is the
Kaplan--Meier median first-timeout onset. Report `--/--` when no included run
contains a capture timeout within 30 shots.

Do not treat `hasWatchdogTimeout=true` as a capture timeout; watchdog is a
protective node-level safeguard.

### Slack P5

Pool all non-missing `timeoutMarginMs` values from shots 1 through 30 of the
included runs in a row. Compute the inclusive fifth percentile and round to
the nearest millisecond using half-up rounding.

### Admission-denial onset E/M

The admission-denial onset of a run is the first shot satisfying:

```text
bokehAdmitted != true OR filterAdmitted != true
```

`E` is the earliest observed admission-denial onset across runs. `M` is the
Kaplan--Meier median onset, with a run censored at shot 30 when no denial is
observed. Report `--` when the statistic is not reached.

### Pacing-delay onset E/M

The pacing-delay onset is shot i+1 for the first capture i whose joined
`beforeAppliedDelayMs` is greater than zero. `E` is the earliest onset and
`M` is the Kaplan--Meier median onset. Runs without nonzero pacing by shot 30
are right-censored at 30.

### M and M+S completed

For each included run and prefix k:

```text
M completed rate   = 100 * count(bokehCompleted) / denominator
M+S completed rate = 100 * count(bokehCompleted AND filterCompleted) / denominator
```

Average the per-run rates with equal weight (macro mean). The denominator is
k, except that the 30-shot denominator is 29 for the accepted 29-record run.
Display exact 100 as `100`; otherwise retain one decimal place.

### Pacing activation rate

Pool the transitions available in the prefix and calculate:

```text
100 * count(beforeAppliedDelayMs > 0) / count(all transition delays)
```

This column measures how often a nonzero pacing delay was actually applied;
zeros remain in the denominator.

### Applied-delay median

Within each included run and prefix:

1. Retain only `beforeAppliedDelayMs > 0`.
2. Compute the run-level median of those applied delays.
3. Compute the median of the available run-level medians.
4. Round to the nearest millisecond using half-up rounding.

Runs with no applied pacing in the prefix do not contribute a delay median.
Report `--` when no included run has an applied delay. Retain the
zero-inclusive mean, P95, and maximum for appendix or audit reporting.

## 6. Current 24MP-memory audit

The fourth workbook, `48U_metrics_24MP_memory_0727_4.xlsx`, contains 14
inferred runs. Eleven are included after applying the invalid-run manifest:

| Starting level | Included from file 4 | Invalid run index |
|---|---:|---|
| Lv0 | 2 | -- |
| Lv1 | 1 | -- |
| Lv2 | 1 | 7 |
| Lv3 | 4 | 9, 13 |
| Lv4 | 3 | -- |
| Lv5 | 0 | -- |
| Lv6 | 0 | -- |
| Total | 11 | 7, 9, 13 |

After combining all four 24MP-memory workbooks, the included run counts are:

| Level | Lv0 | Lv1 | Lv2 | Lv3 | Lv4 | Lv5 | Lv6 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Included N | 3 | 2 | 3 | 9 | 10 | 5 | 8 |

## 7. Reuse checklist

For every new workbook:

1. Verify that `Capture` and `PacingReplay` contain the required columns.
2. Infer runs from `ppSequenceId` resets and record each run's length and
   starting level.
3. Apply the maintained invalid-run manifest independently of outcome values.
4. Audit capture-timeout, watchdog, and incomplete runs among the remaining
   included runs.
5. Join pacing values by `captureIndex` and confirm that delay i is mapped to
   shot i+1.
6. Recompute all 5/10/30 prefixes from the full set of included workbooks; do
   not average previously rounded table cells.
7. Record the workbook basenames, included N, and exclusions alongside the
   generated table.
8. Compare the generated cells with the prior table and manually inspect every
   changed row before rendering the PDF.
