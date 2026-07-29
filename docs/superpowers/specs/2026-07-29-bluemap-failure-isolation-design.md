# BlueMap Failure Isolation Design

## Goal

BlueMap is an optional visualization dependency. A failure in its API,
configuration, lifecycle callbacks, marker synchronization, or shutdown must
never prevent MSLands from starting, serving land operations, or stopping
cleanly.

## Failure Policy

The integration uses a fail-open circuit breaker:

- MSLands starts with a no-op claim visualization.
- BlueMap initialization replaces it only after construction succeeds.
- The first `RuntimeException` or `LinkageError` raised by the BlueMap
  integration is logged once and disables BlueMap visualization for the rest
  of the server session.
- JVM-fatal errors such as `VirtualMachineError` and `ThreadDeath` are not
  swallowed.
- Land repository mutations remain authoritative and complete independently
  of marker synchronization.
- A server restart is required to retry the integration after it is disabled.

## Architecture

Add a fault-isolating `ClaimVisualization` decorator around the concrete
BlueMap implementation. It owns the circuit-breaker state, catches supported
failures from `rebuild`, `syncChunk`, `syncLand`, and `close`, and emits a
single warning through the plugin logger.

BlueMap lifecycle callbacks also cross the same failure boundary because they
are invoked by BlueMap rather than through the decorator. Listener
registration must roll back any partially registered callback if construction
fails.

`LandsPlugin` guards BlueMap class loading and construction. Any bootstrap
failure leaves the existing no-op visualization in place. Plugin shutdown
uses best-effort cleanup so a visualization failure cannot skip executor
shutdown or the cleanup of active claim displays.

## Runtime Flow

1. MSLands creates repositories and services with a no-op visualization.
2. If BlueMap is enabled, MSLands attempts guarded construction.
3. A successful integration is wrapped by the circuit breaker and receives
   the initial rebuild after repository loading.
4. Land mutations call the wrapper. When BlueMap succeeds, markers update.
5. On the first supported BlueMap failure, the wrapper logs the cause,
   disables further BlueMap work, and allows the land operation to complete.
6. Shutdown independently attempts visualization cleanup, executor shutdown,
   and active display cleanup.

## Tests

Tests must be written first and demonstrate:

- bootstrap failure keeps the no-op integration and does not escape;
- failures from rebuild and mutation synchronization do not escape;
- only the first failure is logged and later BlueMap calls are skipped;
- lifecycle callback failures do not escape into BlueMap;
- partial listener registration is rolled back;
- close failures cannot prevent the remaining MSLands shutdown steps;
- the existing repository-load-before-rebuild ordering remains intact.

The final quality gate is `gradlew.bat clean check shadowJar`. The built JAR
must contain the MSLands integration classes and must not contain shaded
`de.bluecolored.bluemap.api` classes.

## Non-goals

- Automatic BlueMap recovery or periodic retries during the same session.
- Swallowing fatal JVM errors.
- Changing claim persistence, marker appearance, or BlueMap configuration.
