# Draft sequence execution refactoring plan

## Current usage flow

`PostProcessor` creates a `DraftSequenceExecutionProfiler` for each draft request and stores it in the extra bundle.
The configured node chain calls `profileNodeExecution()` and executes the returned session. The saving-task manager
then completes or cancels the sequence. `CaptureAvailableApmPolicy` consumes the process-wide
`CaptureAvailablePacer` independently of the profiler.

The Java-facing profiler constructor, lifecycle methods, session `execute(Callable, Callable)`, pacer data-class
getters, and `getInstance()` singletons are compatibility boundaries and should remain unchanged.

## Applied first step

- Prevent a zero-timeout optional worker from leaving the node-chain lifecycle waiting on a completion future that
  can never finish when the worker is cancelled before it starts.
- Avoid repeated suffix summation and copied suffix lists while computing predictor upper bounds.
- Keep an admitted-backlog running total so pacer draft-start rebasing does not sum the full waiting queue.

## Deferred refactoring candidates

### Predictor

- Calculate admission and watchdog reservation from one locked model snapshot, so a concurrent model update cannot
  make one node use inconsistent decisions.
- Keep residual samples score-sorted and prune empty sequence histories; this removes prediction-time
  `filter`/`map`/`sort` allocations.
- Revisit workload-family coupling. The current class-only sibling match deliberately shares watermark and encoding
  variants; changing it to include watermark type, image format, or pending state would change model behavior and
  needs replay data first.
- Define or test duplicate `WorkloadKey` handling. Current maps keep one duration and prediction per key, so repeated
  equal workloads in a path need either an explicit invariant or occurrence-based metrics.

### Profiler

- Build an immutable configured-node suffix plan at initialization to avoid node-by-node identity scans and
  reclassification. This requires the node-chain contract to guarantee that configured order and workload properties
  do not change before deinitialization.
- Extract the JPEG/YUV dependency rules from `effectiveAdmit()` into a small policy object.
- Replace the nullable pending RESERVED session with a synchronized owner, and make node-chain deinitialization wait
  for every timed-out worker rather than only the latest one.
- Use a named model-update snapshot instead of an anonymous `Pair`, and explicitly define late completion behavior
  after capture-end learning has drained the buffer.

### Session

- Make completion state explicit (`active`, `completed`, `cancelled`, `skipped`), cache the first metrics result, and
  invoke callbacks outside the session monitor.
- Evaluate a shared optional-worker executor only with burst and interrupt-ignoring workload tests. It reduces thread
  startup overhead but requires a clear idle-retention and saturation policy.

### Pacer

- Separate delay calculation from admission recording, or return an admission token. Today `decideDelay()` changes
  backlog state, so duplicate callers or cancelled callbacks cannot be distinguished from real admissions.
- Add cancellation or start matching if callback order can differ from draft-start order.
- Inject a monotonic clock for deterministic unit tests.

## Recommended validation before each deferred step

- Add unit tests for cold start, learned upper bounds, watchdog boundaries, timeout worker completion, and pacer
  backlog/rebase behavior.
- Run the parent Camera project's Gradle build and Java/Kotlin ABI checks; this snapshot alone has no Gradle wrapper,
  build file, or test source.
- Compare watchdog rate, capture timeout rate, draft duration, CPU, GC, and run-queue metrics using the existing
  `CaptureMetricsExcelExporter` output and device burst scenarios.
