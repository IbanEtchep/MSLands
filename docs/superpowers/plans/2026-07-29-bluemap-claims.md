# BlueMap Claims Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display player, guild, and system chunk claims on BlueMap and keep the overlay synchronized with all MSLands claim mutations.

**Architecture:** Keep BlueMap behind the generic `ClaimVisualization` boundary so MSLands still loads without BlueMap. Build and test BlueMap-independent marker descriptors and synchronization first, then adapt those descriptors to BlueMap `ShapeMarker`s and wire synchronization after repository mutations.

**Tech Stack:** Java 21, Paper API 1.21.11, BlueMap API 2.7.7, Gradle Kotlin DSL, JUnit 5, Mockito.

## Global Constraints

- BlueMap must remain optional; use `softdepend` and `compileOnly`.
- Display only `PLAYER`, `GUILD`, and `SYSTEM` lands; exclude `SUBLAND`.
- Use one exact 16×16 rectangle per claimed chunk at Y 64 with depth testing disabled.
- Use marker-set id `mslands-claims` and marker id `chunk:<x>:<z>`.
- Filter claims to `LandsPlugin#getServerName()` and the BlueMap map's Bukkit world.
- Details contain only escaped land name, localized type, and player/guild owner label.
- Rebuild markers on every BlueMap enable and discard API references on BlueMap disable.
- Synchronize primitive claim/unclaim operations, bulk claims, land deletion, and land rename.
- Preserve all pre-existing uncommitted edits, especially `build.gradle.kts` and `LandServiceImpl.java`.
- Follow TDD: add each failing test before its production implementation.

---

## File Structure

### Build and configuration

- Modify `build.gradle.kts`: BlueMap repository/API and test dependencies.
- Modify `src/main/resources/plugin.yml`: add `BlueMap` to `softdepend`.
- Modify `src/main/resources/config.yml`: add documented BlueMap defaults.

### BlueMap-independent claim visualization

- Create `src/main/java/fr/iban/lands/integration/claims/RgbColor.java`: validated RGB value.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerStyle.java`: line/fill style.
- Create `src/main/java/fr/iban/lands/integration/claims/BlueMapSettings.java`: configuration parser and defaults.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerDescriptor.java`: immutable marker data.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimOwnerLabelResolver.java`: owner-name boundary.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerFactory.java`: geometry, labels, details, and styles.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimSource.java`: minimal read view over claim data.
- Create `src/main/java/fr/iban/lands/integration/claims/RepositoryClaimSource.java`: `LandRepository` adapter.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerSink.java`: marker output boundary.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimVisualization.java`: synchronization contract and no-op.
- Create `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizer.java`: rebuild and delta synchronization.

### BlueMap adapter

- Create `src/main/java/fr/iban/lands/integration/bluemap/BlueMapShapeMarkerFactory.java`: descriptor-to-`ShapeMarker` conversion.
- Create `src/main/java/fr/iban/lands/integration/bluemap/BlueMapMapResolver.java`: Bukkit-world-to-BlueMap-map resolution.
- Create `src/main/java/fr/iban/lands/integration/bluemap/BlueMapMarkerSink.java`: marker-set mutation.
- Create `src/main/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualization.java`: BlueMap lifecycle and synchronization facade.

### Wiring

- Modify `src/main/java/fr/iban/lands/LandsPlugin.java`: optional bootstrap, getter, and shutdown.
- Modify `src/main/java/fr/iban/lands/service/LandServiceImpl.java`: synchronize successful claim, unclaim, and rename mutations.
- Modify `src/main/java/fr/iban/lands/service/LandRepositoryImpl.java`: remove markers for deleted-land chunks.

### Tests

- Create `src/test/java/fr/iban/lands/integration/claims/BlueMapSettingsTest.java`.
- Create `src/test/java/fr/iban/lands/integration/claims/ClaimMarkerFactoryTest.java`.
- Create `src/test/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizerTest.java`.
- Create `src/test/java/fr/iban/lands/integration/bluemap/BlueMapShapeMarkerFactoryTest.java`.
- Create `src/test/java/fr/iban/lands/integration/bluemap/BlueMapMarkerSinkTest.java`.

---

### Task 1: Establish the BlueMap and test configuration contract

**Files:**

- Modify: `build.gradle.kts:12-40`
- Modify: `build.gradle.kts:63-65`
- Modify: `src/main/resources/plugin.yml:8`
- Modify: `src/main/resources/config.yml`
- Create: `src/main/java/fr/iban/lands/integration/claims/RgbColor.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerStyle.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/BlueMapSettings.java`
- Test: `src/test/java/fr/iban/lands/integration/claims/BlueMapSettingsTest.java`

**Interfaces:**

- Produces: `RgbColor(int red, int green, int blue)`
- Produces: `ClaimMarkerStyle(RgbColor lineColor, RgbColor fillColor, float fillOpacity)`
- Produces: `BlueMapSettings.load(Function<String, Object>, Consumer<String>)`
- Produces: `BlueMapSettings#style(LandType)`

- [ ] **Step 1: Add the test runtime without adding production behavior**

Add the BlueMap repository and dependencies while preserving the existing
QuickShop version edits:

```kotlin
repositories {
    maven { url = uri("https://repo.bluecolored.de/releases") }
}

dependencies {
    compileOnly("de.bluecolored:bluemap-api:2.7.7")

    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write failing configuration tests**

Create tests that pass raw values through `Map<String, Object>::get`:

```java
@Test
void usesDocumentedDefaultsWhenValuesAreMissing() {
    BlueMapSettings settings = BlueMapSettings.load(Map.<String, Object>of()::get, warning -> {});

    assertTrue(settings.enabled());
    assertEquals("Territoires", settings.markerSetLabel());
    assertFalse(settings.defaultHidden());
    assertEquals(2, settings.lineWidth());
    assertEquals(new RgbColor(0x34, 0x98, 0xDB), settings.style(LandType.PLAYER).lineColor());
    assertEquals(0.25F, settings.style(LandType.PLAYER).fillOpacity());
}

@Test
void invalidValuesWarnAndFallBackWhileOpacityIsClamped() {
    Map<String, Object> values = Map.of(
            "bluemap.line-width", 0,
            "bluemap.styles.player.line-color", "blue",
            "bluemap.styles.player.fill-opacity", 2.0
    );
    List<String> warnings = new ArrayList<>();

    BlueMapSettings settings = BlueMapSettings.load(values::get, warnings::add);

    assertEquals(2, settings.lineWidth());
    assertEquals(new RgbColor(0x34, 0x98, 0xDB), settings.style(LandType.PLAYER).lineColor());
    assertEquals(1.0F, settings.style(LandType.PLAYER).fillOpacity());
    assertEquals(2, warnings.size());
}
```

- [ ] **Step 3: Run the test and verify the red state**

Run:

```powershell
rtk .\gradlew.bat test --tests "*BlueMapSettingsTest"
```

Expected: compilation fails because `BlueMapSettings`, `ClaimMarkerStyle`, and
`RgbColor` do not exist.

- [ ] **Step 4: Implement immutable settings and strict parsing**

Implement `RgbColor.parse(String, RgbColor)` for exactly `#RRGGBB`. Implement
`BlueMapSettings.load` with these paths and defaults:

```java
public static final String PREFIX = "bluemap.";

public static BlueMapSettings load(
        Function<String, Object> valueAt,
        Consumer<String> warning
) {
    boolean enabled = booleanValue(valueAt, "enabled", true, warning);
    String label = stringValue(valueAt, "marker-set.label", "Territoires", warning);
    boolean hidden = booleanValue(valueAt, "marker-set.default-hidden", false, warning);
    int lineWidth = positiveInt(valueAt, "line-width", 2, warning);

    return new BlueMapSettings(enabled, label, hidden, lineWidth, Map.of(
            LandType.PLAYER, style(valueAt, warning, "player", "#3498DB"),
            LandType.GUILD, style(valueAt, warning, "guild", "#2ECC71"),
            LandType.SYSTEM, style(valueAt, warning, "system", "#E74C3C")
    ));
}
```

Non-numeric opacity falls back to `0.25F`; numeric opacity is clamped to
`0.0F..1.0F`. A non-positive or non-numeric line width falls back to `2`.
`style(LandType.SUBLAND)` returns `null`.

- [ ] **Step 5: Add resource configuration**

Append:

```yaml
bluemap:
  enabled: true
  marker-set:
    label: "Territoires"
    default-hidden: false
  line-width: 2
  styles:
    player:
      line-color: "#3498DB"
      fill-color: "#3498DB"
      fill-opacity: 0.25
    guild:
      line-color: "#2ECC71"
      fill-color: "#2ECC71"
      fill-opacity: 0.25
    system:
      line-color: "#E74C3C"
      fill-color: "#E74C3C"
      fill-opacity: 0.25
```

Add `BlueMap` to the existing soft dependency list:

```yaml
softdepend: [ QuickShop, HeadDatabase, MSGuilds, Vault, BlueMap ]
```

- [ ] **Step 6: Run the focused and full tests**

Run:

```powershell
rtk .\gradlew.bat test --tests "*BlueMapSettingsTest"
rtk .\gradlew.bat test
```

Expected: both commands pass.

- [ ] **Step 7: Commit**

```powershell
rtk git add src/main/resources/plugin.yml src/main/resources/config.yml src/main/java/fr/iban/lands/integration/claims src/test/java/fr/iban/lands/integration/claims/BlueMapSettingsTest.java
rtk proxy git add -p build.gradle.kts
rtk proxy git diff --cached
rtk git commit -m "build: add optional BlueMap API"
```

In patch mode, stage the BlueMap/JUnit additions and leave the pre-existing
QuickShop version hunk unstaged. The cached diff must not contain the QuickShop
version change.

---

### Task 2: Build deterministic claim marker descriptors

**Files:**

- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerDescriptor.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimOwnerLabelResolver.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerFactory.java`
- Test: `src/test/java/fr/iban/lands/integration/claims/ClaimMarkerFactoryTest.java`

**Interfaces:**

- Consumes: `BlueMapSettings#style(LandType)`
- Produces:

```java
public record ClaimMarkerDescriptor(
        String id,
        String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String label,
        String detail,
        ClaimMarkerStyle style
) {}
```

- Produces: `Optional<ClaimMarkerDescriptor> ClaimMarkerFactory#create(SChunk, Land)`
- Produces: `String ClaimMarkerFactory.markerId(SChunk)`

- [ ] **Step 1: Write failing geometry, identity, and filtering tests**

Cover both coordinate signs and excluded sublands:

```java
@ParameterizedTest
@CsvSource({
        "2,-3,32,-48,48,-32,chunk:2:-3",
        "-2,3,-32,48,-16,64,chunk:-2:3"
})
void createsExactChunkRectangles(
        int chunkX, int chunkZ,
        int minX, int minZ, int maxX, int maxZ,
        String markerId
) {
    SChunk chunk = new SChunk("survival", "world", chunkX, chunkZ);
    Land land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Maison");

    ClaimMarkerDescriptor marker = factory.create(chunk, land).orElseThrow();

    assertAll(
            () -> assertEquals(markerId, marker.id()),
            () -> assertEquals(minX, marker.minX()),
            () -> assertEquals(minZ, marker.minZ()),
            () -> assertEquals(maxX, marker.maxX()),
            () -> assertEquals(maxZ, marker.maxZ())
    );
}

@Test
void excludesSublands() {
    assertTrue(factory.create(chunk, new SubLand(UUID.randomUUID(), "Mine")).isEmpty());
}
```

- [ ] **Step 2: Write failing detail and escaping tests**

Inject a resolver returning `Alice & Bob` and assert exact detail output:

```java
assertEquals(
        "<b>Maison &lt;nord&gt;</b><br>Type: Joueur<br>Propriétaire: Alice &amp; Bob",
        marker.detail()
);
```

Add guild and system assertions:

```java
assertEquals("<b>Citadelle</b><br>Type: Guilde<br>Guilde: Les Bleus", guild.detail());
assertEquals("<b>Spawn</b><br>Type: Système", system.detail());
```

Also cover escaping of `&`, `<`, `>`, `"`, and `'`.

- [ ] **Step 3: Run the test and verify the red state**

Run:

```powershell
rtk .\gradlew.bat test --tests "*ClaimMarkerFactoryTest"
```

Expected: compilation fails because the descriptor and factory do not exist.

- [ ] **Step 4: Implement the descriptor factory**

Use exact chunk geometry:

```java
int minX = Math.multiplyExact(chunk.getX(), 16);
int minZ = Math.multiplyExact(chunk.getZ(), 16);

return Optional.of(new ClaimMarkerDescriptor(
        markerId(chunk),
        chunk.getWorld(),
        minX,
        minZ,
        Math.addExact(minX, 16),
        Math.addExact(minZ, 16),
        land.getName(),
        detail(land),
        settings.style(land.getType())
));
```

`markerId` returns `"chunk:" + chunk.getX() + ":" + chunk.getZ()`.
`create` returns empty when `settings.style(land.getType())` is `null`.
Escape dynamic strings before concatenating the fixed French HTML labels.

- [ ] **Step 5: Run focused and full tests**

```powershell
rtk .\gradlew.bat test --tests "*ClaimMarkerFactoryTest"
rtk .\gradlew.bat test
```

Expected: both pass.

- [ ] **Step 6: Commit**

```powershell
rtk git add src/main/java/fr/iban/lands/integration/claims src/test/java/fr/iban/lands/integration/claims/ClaimMarkerFactoryTest.java
rtk git commit -m "feat: describe BlueMap claim markers"
```

---

### Task 3: Synchronize repository state through testable boundaries

**Files:**

- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimSource.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/RepositoryClaimSource.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerSink.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimVisualization.java`
- Create: `src/main/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizer.java`
- Test: `src/test/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizerTest.java`

**Interfaces:**

- Consumes: `ClaimMarkerFactory#create(SChunk, Land)`
- Produces:

```java
public interface ClaimVisualization extends AutoCloseable {
    void rebuild();
    void syncChunk(SChunk chunk);
    void syncLand(Land land);
    @Override void close();

    ClaimVisualization NO_OP = new ClaimVisualization() {
        @Override public void rebuild() {}
        @Override public void syncChunk(SChunk chunk) {}
        @Override public void syncLand(Land land) {}
        @Override public void close() {}
    };

    static ClaimVisualization noop() {
        return NO_OP;
    }
}

public interface ClaimSource {
    Map<SChunk, Land> claims();
    Land landAt(SChunk chunk);
    Collection<SChunk> chunks(Land land);
    boolean isWilderness(Land land);
}

public interface ClaimMarkerSink extends AutoCloseable {
    void clear();
    void put(ClaimMarkerDescriptor marker);
    void remove(String world, String markerId);
    @Override void close();
}
```

- [ ] **Step 1: Write failing delta synchronization tests**

Use in-memory `FakeClaimSource` and `RecordingSink` test doubles. Verify:

```java
@Test
void replacesMarkerAfterSuccessfulClaim() {
    source.put(chunk, playerLand);

    synchronizer.syncChunk(chunk);

    assertEquals(Set.of("world/chunk:4:-2"), sink.putKeys());
    assertTrue(sink.removedKeys().isEmpty());
}

@Test
void removesMarkerWhenChunkIsWilderness() {
    source.put(chunk, wilderness);

    synchronizer.syncChunk(chunk);

    assertEquals(Set.of("world/chunk:4:-2"), sink.removedKeys());
    assertTrue(sink.putKeys().isEmpty());
}
```

- [ ] **Step 2: Write failing rebuild, server-filter, and rename tests**

Assert that `rebuild()` clears first, ignores a chunk from another server, and
adds supported local claims. Assert that `syncLand(land)` refreshes every chunk
returned by `source.chunks(land)`.

- [ ] **Step 3: Run the test and verify the red state**

```powershell
rtk .\gradlew.bat test --tests "*ClaimMarkerSynchronizerTest"
```

Expected: compilation fails because synchronization interfaces do not exist.

- [ ] **Step 4: Implement repository and sink orchestration**

The synchronizer must apply this order:

```java
public void syncChunk(SChunk chunk) {
    if (!serverName.equals(chunk.getServer())) return;

    Land land = source.landAt(chunk);
    if (source.isWilderness(land)) {
        sink.remove(chunk.getWorld(), ClaimMarkerFactory.markerId(chunk));
        return;
    }

    markerFactory.create(chunk, land).ifPresentOrElse(
            sink::put,
            () -> sink.remove(chunk.getWorld(), ClaimMarkerFactory.markerId(chunk))
    );
}
```

`rebuild()` calls `sink.clear()` and then synchronizes a stable
`List.copyOf(source.claims().keySet())`. `RepositoryClaimSource` delegates only
to `LandRepository#getChunks`, `getLandAt`, `getChunks(Land)`, and
`isWilderness`.

- [ ] **Step 5: Run focused and full tests**

```powershell
rtk .\gradlew.bat test --tests "*ClaimMarkerSynchronizerTest"
rtk .\gradlew.bat test
```

Expected: both pass.

- [ ] **Step 6: Commit**

```powershell
rtk git add src/main/java/fr/iban/lands/integration/claims src/test/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizerTest.java
rtk git commit -m "feat: synchronize claim markers"
```

---

### Task 4: Adapt descriptors to BlueMap and its lifecycle

**Files:**

- Create: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapShapeMarkerFactory.java`
- Create: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapMapResolver.java`
- Create: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapMarkerSink.java`
- Create: `src/main/java/fr/iban/lands/integration/bluemap/BlueMapClaimVisualization.java`
- Test: `src/test/java/fr/iban/lands/integration/bluemap/BlueMapShapeMarkerFactoryTest.java`
- Test: `src/test/java/fr/iban/lands/integration/bluemap/BlueMapMarkerSinkTest.java`

**Interfaces:**

- Consumes: `ClaimMarkerDescriptor`
- Consumes: `ClaimMarkerSink`
- Consumes: `ClaimMarkerSynchronizer`
- Produces: `BlueMapShapeMarkerFactory(int lineWidth)`
- Produces: `ShapeMarker BlueMapShapeMarkerFactory#create(ClaimMarkerDescriptor)`
- Produces: `Collection<BlueMapMap> BlueMapMapResolver#resolve(BlueMapAPI, String)`
- Produces: `BlueMapClaimVisualization implements ClaimVisualization`

- [ ] **Step 1: Write failing ShapeMarker conversion tests**

Create a descriptor with known geometry and style, then assert:

```java
ShapeMarker marker = factory.create(descriptor);

assertAll(
        () -> assertEquals("Maison", marker.getLabel()),
        () -> assertEquals(64.0F, marker.getShapeY()),
        () -> assertEquals(new Vector2d(32, -48), marker.getShape().getMin()),
        () -> assertEquals(new Vector2d(48, -32), marker.getShape().getMax()),
        () -> assertEquals(2, marker.getLineWidth()),
        () -> assertFalse(marker.isDepthTestEnabled()),
        () -> assertEquals(descriptor.detail(), marker.getDetail())
);
```

Assert RGB conversion uses alpha `1.0F` for the line and the configured opacity
for the fill.

- [ ] **Step 2: Run the ShapeMarker test and verify the red state**

```powershell
rtk .\gradlew.bat test --tests "*BlueMapShapeMarkerFactoryTest"
```

Expected: compilation fails because `BlueMapShapeMarkerFactory` does not exist.

- [ ] **Step 3: Implement exact BlueMap marker conversion**

Use API 2.7.7 types:

```java
return ShapeMarker.builder()
        .label(descriptor.label())
        .detail(descriptor.detail())
        .shape(Shape.createRect(
                descriptor.minX(),
                descriptor.minZ(),
                descriptor.maxX(),
                descriptor.maxZ()
        ), 64)
        .lineWidth(lineWidth)
        .lineColor(toColor(descriptor.style().lineColor(), 1.0F))
        .fillColor(toColor(
                descriptor.style().fillColor(),
                descriptor.style().fillOpacity()
        ))
        .depthTestEnabled(false)
        .build();
```

- [ ] **Step 4: Write failing marker-set mutation tests**

Mock `BlueMapAPI` and `BlueMapMap`. Inject a resolver returning two maps for
`world`, each backed by its own mutable marker-set map. Verify:

- `attach(api)` followed by `put(descriptor)` creates `mslands-claims` on both maps;
- the set is toggleable, has the configured label, and respects `defaultHidden`;
- `remove("world", id)` removes from both maps;
- `clear()` replaces/removes only `mslands-claims`, preserving unrelated sets;
- an unresolved world produces no marker and one warning;
- one map throwing during an update is logged without preventing updates to the
  other resolved maps;
- `detach(api)` drops the active reference, so later writes are no-ops.

- [ ] **Step 5: Run the sink test and verify the red state**

```powershell
rtk .\gradlew.bat test --tests "*BlueMapMarkerSinkTest"
```

Expected: compilation fails because the sink and resolver do not exist.

- [ ] **Step 6: Implement map resolution and marker-set mutation**

Production resolution must use the Bukkit world object, not a guessed BlueMap
world id:

```java
World world = Bukkit.getWorld(worldName);
if (world == null) return List.of();

return api.getWorld(world)
        .<Collection<BlueMapMap>>map(BlueMapWorld::getMaps)
        .orElseGet(List::of);
```

`BlueMapMarkerSink` keeps a `volatile BlueMapAPI activeApi`. `put` and `remove`
resolve all maps for the descriptor's world. Each marker set is created with:

```java
MarkerSet.builder()
        .label(settings.markerSetLabel())
        .toggleable(true)
        .defaultHidden(settings.defaultHidden())
        .build();
```

Wrap each individual map mutation in `try/catch (RuntimeException exception)`.
Log the map id, world, and marker id, then continue to the next map.

- [ ] **Step 7: Implement BlueMap lifecycle ownership**

`BlueMapClaimVisualization` stores stable callback instances:

```java
private final Consumer<BlueMapAPI> onEnable = api -> {
    sink.attach(api);
    synchronizer.rebuild();
};

private final Consumer<BlueMapAPI> onDisable = sink::detach;
```

Its constructor registers both callbacks with `BlueMapAPI.onEnable` and
`BlueMapAPI.onDisable`. `close()` unregisters both with
`BlueMapAPI.unregisterListener`, removes the MSLands marker set from any active
maps, and closes the synchronizer. Delegate `rebuild`, `syncChunk`, and
`syncLand` to the synchronizer.

Provide `BlueMapClaimVisualization.create(LandsPlugin)` as the composition root.
It must:

1. load `BlueMapSettings` from `plugin.getConfig()::get`, logging warnings
   through `plugin.getLogger()`;
2. wrap `plugin.getLandRepository()` in `RepositoryClaimSource`;
3. resolve player owners with `Bukkit.getOfflinePlayer(uuid).getName()`, falling
   back to `uuid.toString()`;
4. resolve guild owners with `GuildLand#getGuildName()` only when
   `plugin.isGuildsHookEnabled()`, falling back to the guild UUID;
5. create the descriptor factory, BlueMap shape factory, map resolver, sink, and
   synchronizer exactly once.

- [ ] **Step 8: Run focused and full tests**

```powershell
rtk .\gradlew.bat test --tests "*BlueMapShapeMarkerFactoryTest"
rtk .\gradlew.bat test --tests "*BlueMapMarkerSinkTest"
rtk .\gradlew.bat test
```

Expected: all pass.

- [ ] **Step 9: Commit**

```powershell
rtk git add src/main/java/fr/iban/lands/integration/bluemap src/test/java/fr/iban/lands/integration/bluemap
rtk git commit -m "feat: render claims with BlueMap"
```

---

### Task 5: Wire every claim mutation into the visualization

**Files:**

- Modify: `src/main/java/fr/iban/lands/LandsPlugin.java:47-113`
- Modify: `src/main/java/fr/iban/lands/LandsPlugin.java:230-249`
- Modify: `src/main/java/fr/iban/lands/service/LandServiceImpl.java:107-123`
- Modify: `src/main/java/fr/iban/lands/service/LandServiceImpl.java:193-200`
- Modify: `src/main/java/fr/iban/lands/service/LandRepositoryImpl.java:124-142`

**Interfaces:**

- Consumes: `ClaimVisualization#syncChunk(SChunk)`
- Consumes: `ClaimVisualization#syncLand(Land)`
- Consumes: `ClaimVisualization#close()`
- Produces: `ClaimVisualization LandsPlugin#getClaimVisualization()`

- [ ] **Step 1: Add a no-op bootstrap test case to the synchronizer suite**

Extend the existing test to prove calls are safe without BlueMap:

```java
@Test
void noOpVisualizationAcceptsEveryLifecycleCall() {
    ClaimVisualization visualization = ClaimVisualization.noop();

    assertDoesNotThrow(() -> {
        visualization.rebuild();
        visualization.syncChunk(chunk);
        visualization.syncLand(playerLand);
        visualization.close();
    });
}
```

- [ ] **Step 2: Run the focused test**

```powershell
rtk .\gradlew.bat test --tests "*ClaimMarkerSynchronizerTest"
```

Expected: pass, establishing the safe fallback before plugin wiring.

- [ ] **Step 3: Bootstrap the optional integration**

Initialize the plugin field eagerly:

```java
private ClaimVisualization claimVisualization = ClaimVisualization.noop();
```

After repository, service, and guild hooks are ready:

```java
if (getConfig().getBoolean("bluemap.enabled", true)
        && getServer().getPluginManager().isPluginEnabled("BlueMap")) {
    claimVisualization = BlueMapClaimVisualization.create(this);
    getLogger().info("Intégration BlueMap effectuée.");
}
```

Keep this guarded construction in a private method so BlueMap-specific classes
are not initialized when the optional plugin is absent. In `onDisable`, call
`claimVisualization.close()` before shutting down the executor.

- [ ] **Step 4: Synchronize primitive service mutations**

Preserve the user's current trim/name-validation edits. Add only:

```java
land.setName(newName);
landRepository.updateLand(land);
plugin.getClaimVisualization().syncLand(land);
```

```java
landRepository.addChunk(chunk, land);
plugin.getClaimVisualization().syncChunk(chunk);
```

```java
landRepository.removeChunk(chunk);
plugin.getClaimVisualization().syncChunk(chunk);
```

Because bulk claiming calls `claim(SChunk, Land)`, it now updates BlueMap without
extra code.

- [ ] **Step 5: Synchronize land deletion**

Capture the affected chunks before deleting them:

```java
List<SChunk> deletedChunks = List.copyOf(getChunks(land));
deletedChunks.forEach(chunks::remove);
deletedChunks.forEach(plugin.getClaimVisualization()::syncChunk);
```

Keep existing subland, default-world, cache, and storage deletion behavior
unchanged.

- [ ] **Step 6: Run all tests and compile the production integration**

```powershell
rtk .\gradlew.bat clean test shadowJar
```

Expected: `BUILD SUCCESSFUL` and
`build/libs/MSLands-1.1.1.jar` exists.

- [ ] **Step 7: Verify optional dependency packaging**

Run:

```powershell
rtk jar tf build/libs/MSLands-1.1.1.jar
```

Expected:

- MSLands integration classes are present;
- no `de/bluecolored/bluemap/api/` classes are present, proving the API was not shaded.

- [ ] **Step 8: Inspect the complete diff**

```powershell
rtk proxy git diff --check
rtk git status --short
rtk proxy git diff -- build.gradle.kts src/main/java/fr/iban/lands/service/LandServiceImpl.java
```

Expected: no whitespace errors, no unrelated files staged, and the pre-existing
QuickShop/name-validation changes remain intact.

- [ ] **Step 9: Commit**

```powershell
rtk git add src/main/java/fr/iban/lands/LandsPlugin.java src/main/java/fr/iban/lands/service/LandRepositoryImpl.java src/test/java/fr/iban/lands/integration/claims/ClaimMarkerSynchronizerTest.java
rtk proxy git add -p src/main/java/fr/iban/lands/service/LandServiceImpl.java
rtk proxy git diff --cached
rtk git commit -m "feat: keep BlueMap claims synchronized"
```

In patch mode, stage only the three visualization calls. Leave the user's
pre-existing rename-validation edits unstaged and verify they are absent from
the cached diff.

---

### Task 6: Final verification

**Files:**

- Verify only; no new production files.

**Interfaces:**

- Verifies the complete feature contract.

- [ ] **Step 1: Run the complete quality gate**

```powershell
rtk .\gradlew.bat clean check shadowJar
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify resource expansion**

```powershell
rtk rg -n "BlueMap|bluemap|Territoires" build/resources/main/plugin.yml build/resources/main/config.yml
```

Expected: the processed `plugin.yml` contains `BlueMap` in `softdepend`, and the
processed `config.yml` contains the complete documented `bluemap` section.

- [ ] **Step 3: Review runtime scenarios**

Confirm from code and tests that:

1. MSLands loads with BlueMap absent and uses `ClaimVisualization.noop()`.
2. BlueMap enable/reload attaches a fresh marker set and performs a full rebuild.
3. BlueMap disable prevents later writes from using the stale API.
4. Single and bulk claims insert markers.
5. Unclaim and land deletion remove markers.
6. Rename refreshes every marker for that land.
7. Other-server chunks and sublands never render.

- [ ] **Step 4: Check repository state**

```powershell
rtk git status --short
rtk git log -6 --oneline
```

Expected: only the user's pre-existing unrelated edits remain uncommitted; the
BlueMap implementation is covered by the task commits above.

## Official API References

- [BlueMap API 2.7.7 lifecycle](https://github.com/BlueMap-Minecraft/BlueMapAPI/blob/v2.7.7/src/main/java/de/bluecolored/bluemap/api/BlueMapAPI.java)
- [BlueMap map marker sets](https://github.com/BlueMap-Minecraft/BlueMapAPI/blob/v2.7.7/src/main/java/de/bluecolored/bluemap/api/BlueMapMap.java)
- [BlueMap ShapeMarker API](https://github.com/BlueMap-Minecraft/BlueMapAPI/blob/v2.7.7/src/main/java/de/bluecolored/bluemap/api/markers/ShapeMarker.java)
- [BlueMap MarkerSet API](https://github.com/BlueMap-Minecraft/BlueMapAPI/blob/v2.7.7/src/main/java/de/bluecolored/bluemap/api/markers/MarkerSet.java)
