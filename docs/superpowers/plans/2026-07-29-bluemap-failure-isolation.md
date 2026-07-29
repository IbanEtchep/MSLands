# BlueMap Failure Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every BlueMap failure is contained so MSLands keeps starting, processing land mutations, and shutting down normally.

**Architecture:** A BlueMap-specific circuit breaker catches `RuntimeException` and `LinkageError`, logs the first failure once, and skips later visualization work for the session. A `ClaimVisualization` decorator protects calls originating in MSLands, while the concrete BlueMap lifecycle owner shares the same breaker for callbacks originating in BlueMap. Bootstrap and shutdown retain outer guards so linkage or construction failures cannot escape into the plugin lifecycle.

**Tech Stack:** Java 21, Paper/Bukkit, BlueMap API 2.7.7, JUnit 5, Mockito, Gradle Kotlin DSL

## Global Constraints

- BlueMap remains optional through `softdepend` and `compileOnly`.
- A first supported failure disables visualization until the next server restart.
- Catch only `RuntimeException` and `LinkageError`; do not swallow JVM-fatal errors.
- Land persistence and mutation results must never depend on marker synchronization.
- Log the first integration failure once and avoid repeated warning spam.
- Preserve repository-load-before-initial-rebuild ordering.
- Do not change claim persistence, marker appearance, or BlueMap configuration.

---

## File Structure

- Create `src/main/java/fr/iban/lands/integration/bluemap/BlueMapFailureCircuitBreaker.java`: thread-safe failure state and single-warning policy.
- Create `src/main/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualization.java`: protects MSLands-originated visualization calls.
- Modify `src/main/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualization.java`: shares the breaker with BlueMap-originated lifecycle callbacks and performs best-effort listener cleanup.
- Modify `src/main/java/fr/iban/lands/LandsPlugin.java`: protects optional class construction and plugin shutdown.
- Create `src/test/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualizationTest.java`: circuit-breaker regression tests.
- Modify `src/test/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualizationTest.java`: callback, registration rollback, and cleanup failure tests.
- Modify `src/test/java/fr/iban/lands/LandsPluginTest.java`: bootstrap and shutdown containment tests.

### Task 1: Circuit Breaker and Visualization Decorator

**Files:**
- Create: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapFailureCircuitBreaker.java`
- Create: `src/main/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualization.java`
- Create: `src/test/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualizationTest.java`

**Interfaces:**
- Produces: `BlueMapFailureCircuitBreaker(Logger logger)`
- Produces: `void execute(String operation, Runnable action)`
- Produces: `void cleanup(String operation, Runnable action)`
- Produces: `boolean isOpen()`
- Produces: `FaultTolerantClaimVisualization(ClaimVisualization delegate, BlueMapFailureCircuitBreaker breaker)`

- [ ] **Step 1: Write failing tests for runtime failure, linkage failure, log-once, and cleanup**

Create tests using a recording `ClaimVisualization` and a `Logger` with a custom `Handler`:

```java
@Test
void firstRuntimeFailureOpensCircuitAndSkipsLaterCalls() {
    delegate.rebuildFailure = new IllegalStateException("BlueMap unavailable");
    ClaimVisualization safe = new FaultTolerantClaimVisualization(delegate, breaker);

    assertDoesNotThrow(safe::rebuild);
    safe.syncChunk(chunk);

    assertEquals(1, delegate.rebuildCalls);
    assertEquals(0, delegate.syncChunkCalls);
    assertEquals(1, logRecords.size());
}

@Test
void linkageFailureIsContainedButFatalVmErrorEscapes() {
    delegate.rebuildFailure = new NoClassDefFoundError("BlueMapAPI");
    assertDoesNotThrow(safe::rebuild);

    FaultTolerantClaimVisualization fatal = visualizationThrowing(
            new OutOfMemoryError("fatal")
    );
    assertThrows(OutOfMemoryError.class, fatal::rebuild);
}

@Test
void closeStillAttemptsCleanupAfterCircuitOpens() {
    delegate.rebuildFailure = new IllegalStateException("broken");
    safe.rebuild();

    assertDoesNotThrow(safe::close);
    assertEquals(1, delegate.closeCalls);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
rtk .\gradlew.bat test --tests "*FaultTolerantClaimVisualizationTest"
```

Expected: compilation failure because the breaker and decorator do not exist.

- [ ] **Step 3: Implement the minimal thread-safe circuit breaker**

Implement:

```java
final class BlueMapFailureCircuitBreaker {
    private final Logger logger;
    private final AtomicBoolean open = new AtomicBoolean();

    void execute(String operation, Runnable action) {
        if (open.get()) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            trip(operation, failure);
        }
    }

    void cleanup(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            trip(operation, failure);
        }
    }

    boolean isOpen() {
        return open.get();
    }

    private void trip(String operation, Throwable failure) {
        if (open.compareAndSet(false, true)) {
            logger.log(
                    Level.SEVERE,
                    "BlueMap integration failed during " + operation
                            + " and is disabled until restart.",
                    failure
            );
        }
    }
}
```

Implement the decorator so `rebuild`, `syncChunk`, and `syncLand` use `execute`, while `close` always uses `cleanup`.

- [ ] **Step 4: Run focused and existing claim synchronization tests**

Run:

```powershell
rtk .\gradlew.bat test --tests "*FaultTolerantClaimVisualizationTest" --tests "*ClaimMarkerSynchronizerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
rtk git add src/main/java/fr/iban/lands/integration/bluemap/BlueMapFailureCircuitBreaker.java src/main/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualization.java src/test/java/fr/iban/lands/integration/bluemap/FaultTolerantClaimVisualizationTest.java
rtk git commit -m "feat: isolate BlueMap visualization failures"
```

### Task 2: Guard BlueMap Lifecycle Callbacks

**Files:**
- Modify: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualization.java`
- Modify: `src/test/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualizationTest.java`

**Interfaces:**
- Consumes: `BlueMapFailureCircuitBreaker.execute`, `cleanup`, and `isOpen`
- Consumes: `FaultTolerantClaimVisualization`
- Changes: `BlueMapClaimVisualization.create(LandsPlugin)` returns `ClaimVisualization`
- Produces: stable callbacks that never propagate supported BlueMap failures

- [ ] **Step 1: Write failing lifecycle containment tests**

Add tests that make `sink.attach`, `synchronizer.rebuild`, `sink.detach`, listener registration, unregister, and `synchronizer.close` throw:

```java
@Test
void enableFailureIsContainedAndDisablesLaterLifecycleWork() {
    doThrow(new IllegalStateException("attach failed")).when(sink).attach(api);
    BlueMapClaimVisualization visualization =
            new BlueMapClaimVisualization(sink, synchronizer, listeners, breaker);

    assertDoesNotThrow(() -> listeners.onEnable.accept(api));
    listeners.onEnable.accept(api);

    verify(sink, times(1)).attach(api);
    assertTrue(breaker.isOpen());
}

@Test
void failedSecondRegistrationRollsBackFirstListener() {
    listeners.onDisableFailure = new IllegalStateException("register failed");

    assertThrows(
            IllegalStateException.class,
            () -> new BlueMapClaimVisualization(sink, synchronizer, listeners, breaker)
    );

    assertEquals(List.of(listeners.onEnable), listeners.unregistered);
}

@Test
void closeAttemptsEveryCleanupStepWhenEarlierStepFails() {
    listeners.unregisterFailure = new IllegalStateException("unregister failed");
    BlueMapClaimVisualization visualization =
            new BlueMapClaimVisualization(sink, synchronizer, listeners, breaker);

    assertDoesNotThrow(visualization::close);

    assertEquals(2, listeners.unregisterAttempts);
    verify(synchronizer).close();
}
```

- [ ] **Step 2: Run lifecycle tests and verify RED**

Run:

```powershell
rtk .\gradlew.bat test --tests "*BlueMapClaimVisualizationTest"
```

Expected: FAIL because callbacks and cleanup currently propagate failures and registration has no rollback.

- [ ] **Step 3: Share the breaker across factory, decorator, and callbacks**

In `create`:

```java
BlueMapFailureCircuitBreaker breaker =
        new BlueMapFailureCircuitBreaker(plugin.getLogger());
BlueMapClaimVisualization delegate = new BlueMapClaimVisualization(
        sink,
        synchronizer,
        BLUE_MAP_LISTENERS,
        breaker
);
return new FaultTolerantClaimVisualization(delegate, breaker);
```

Build callbacks as:

```java
onEnable = api -> breaker.execute("BlueMap enable", () -> {
    sink.attach(api);
    synchronizer.rebuild();
});
onDisable = api ->
        breaker.cleanup("BlueMap disable", () -> sink.detach(api));
```

Register `onEnable` first. If `onDisable` registration fails, unregister
`onEnable` in a best-effort nested `try`, attach any cleanup failure as
suppressed, then rethrow the original supported failure.

Implement `close` as three independent `breaker.cleanup` calls for the enable
listener, disable listener, and synchronizer.

- [ ] **Step 4: Run lifecycle, sink concurrency, and decorator tests**

Run:

```powershell
rtk .\gradlew.bat test --tests "*BlueMapClaimVisualizationTest" --tests "*BlueMapMarkerSinkTest" --tests "*FaultTolerantClaimVisualizationTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```powershell
rtk git add src/main/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualization.java src/test/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualizationTest.java
rtk git commit -m "fix: contain BlueMap lifecycle failures"
```

### Task 3: Guard Plugin Bootstrap and Shutdown

**Files:**
- Modify: `src/main/java/fr/iban/lands/LandsPlugin.java`
- Modify: `src/test/java/fr/iban/lands/LandsPluginTest.java`

**Interfaces:**
- Consumes: `BlueMapClaimVisualization.create(LandsPlugin): ClaimVisualization`
- Preserves: `queueInitialClaimVisualizationRebuild()` FIFO ordering
- Produces: bootstrap fallback to `ClaimVisualization.noop()`
- Produces: shutdown that continues after visualization failure

- [ ] **Step 1: Write failing bootstrap and shutdown tests**

Extend the existing reflection-based plugin test:

```java
@Test
void bootstrapFailureFallsBackWithoutEscapingOrQueueingRebuild() {
    try (MockedStatic<BlueMapClaimVisualization> blueMap =
                 mockStatic(BlueMapClaimVisualization.class)) {
        blueMap.when(() -> BlueMapClaimVisualization.create(plugin))
                .thenThrow(new NoClassDefFoundError("BlueMapAPI"));

        assertDoesNotThrow(() -> invokeSetupClaimVisualization(plugin));
    }

    verify(plugin, never()).runAsyncQueued(any());
    assertDoesNotThrow(() -> plugin.getClaimVisualization().syncChunk(chunk));
}

@Test
void visualizationCloseFailureDoesNotSkipExecutorShutdown() {
    ClaimVisualization visualization = mock(ClaimVisualization.class);
    ExecutorService executor = mock(ExecutorService.class);
    SeeClaims display = mock(SeeClaims.class);
    doThrow(new IllegalStateException("close failed")).when(visualization).close();
    doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();

    assertDoesNotThrow(() -> plugin.shutdownComponents(
            visualization,
            executor,
            List.of(display)
    ));

    verify(executor).shutdown();
    verify(display).stop();
}
```

Retain `rebuildsClaimsAfterQueuedRepositoryLoadCompletes`.

- [ ] **Step 2: Run plugin tests and verify RED**

Run:

```powershell
rtk .\gradlew.bat test --tests "*LandsPluginTest"
```

Expected: FAIL because bootstrap and shutdown currently propagate supported failures.

- [ ] **Step 3: Implement guarded bootstrap and shutdown**

Wrap construction:

```java
try {
    ClaimVisualization candidate = BlueMapClaimVisualization.create(this);
    claimVisualization = candidate;
    queueInitialClaimVisualizationRebuild();
    getLogger().info("Intégration BlueMap effectuée.");
} catch (RuntimeException | LinkageError failure) {
    claimVisualization = ClaimVisualization.noop();
    getLogger().log(
            Level.SEVERE,
            "Impossible d'initialiser BlueMap; l'intégration est désactivée.",
            failure
    );
}
```

If queueing fails after construction, best-effort close the candidate before
installing the no-op fallback.

Extract a package-private lifecycle seam and call it from `onDisable`:

```java
void shutdownComponents(
        ClaimVisualization visualization,
        ExecutorService executor,
        Collection<SeeClaims> displays
) {
    try {
        visualization.close();
    } catch (RuntimeException | LinkageError failure) {
        getLogger().log(
                Level.SEVERE,
                "Erreur lors de l'arrêt de l'intégration BlueMap.",
                failure
        );
    }
    executor.shutdown();
    displays.forEach(SeeClaims::stop);
}
```

This guarantees a supported BlueMap close failure returns normally before
executor and display cleanup.

- [ ] **Step 4: Run focused plugin and integration tests**

Run:

```powershell
rtk .\gradlew.bat test --tests "*LandsPluginTest" --tests "fr.iban.lands.integration.*"
```

Expected: PASS.

- [ ] **Step 5: Run the full quality gate and inspect the JAR**

Run:

```powershell
rtk .\gradlew.bat clean check shadowJar
```

Expected: `BUILD SUCCESSFUL`.

Inspect `build/libs/MSLands-1.1.1.jar` and assert:

- at least one `fr/iban/lands/integration/bluemap/*.class` entry exists;
- zero `de/bluecolored/bluemap/api/*.class` entries exist.

- [ ] **Step 6: Commit Task 3**

```powershell
rtk git add src/main/java/fr/iban/lands/LandsPlugin.java src/test/java/fr/iban/lands/LandsPluginTest.java
rtk git commit -m "fix: keep MSLands running when BlueMap fails"
```

### Task 4: Final Review and Publication Readiness

**Files:**
- Verify only; no production file changes expected.

**Interfaces:**
- Consumes: all preceding tasks
- Produces: reviewed, buildable branch with a non-shaded BlueMap API

- [ ] **Step 1: Run final clean verification**

```powershell
rtk .\gradlew.bat clean check shadowJar
rtk git diff --check
rtk git status --short
```

Expected: build succeeds, diff check is silent, and the worktree has no
uncommitted tracked changes.

- [ ] **Step 2: Review failure scenarios**

Confirm from tests and code:

- BlueMap absent at class load;
- BlueMap construction failure;
- initial rebuild failure;
- claim, unclaim, rename, and delete synchronization failure;
- enable and disable callback failure;
- partial listener registration failure;
- listener and sink cleanup failure;
- MSLands executor shutdown still runs.

- [ ] **Step 3: Request a whole-range code review**

Review from commit `cf8415f` through the final implementation HEAD against
`docs/superpowers/specs/2026-07-29-bluemap-failure-isolation-design.md`.
Fix all Critical and Important findings before publication.
