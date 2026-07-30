package fr.iban.lands;

import fr.iban.lands.integration.bluemap.BlueMapClaimVisualization;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.utils.SeeClaims;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LandsPluginTest {

    @Test
    void missingBlueMapClassFallsBackWithoutQueueingARebuild() {
        SetupContext context = configuredPlugin();
        NoClassDefFoundError failure = new NoClassDefFoundError("BlueMap API missing");

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenThrow(failure);

            assertDoesNotThrow(() -> invokeSetupClaimVisualization(context.plugin()));
        }

        assertTrue(context.queuedTasks().isEmpty());
        assertUsableNoop(context.plugin());
        verifyInitializationFailureLogged(context.logger(), failure);
    }

    @Test
    void runtimeConstructionFailureFallsBackWithoutQueueingARebuild() {
        SetupContext context = configuredPlugin();
        IllegalStateException failure = new IllegalStateException("BlueMap unavailable");

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenThrow(failure);

            assertDoesNotThrow(() -> invokeSetupClaimVisualization(context.plugin()));
        }

        assertTrue(context.queuedTasks().isEmpty());
        assertUsableNoop(context.plugin());
        verifyInitializationFailureLogged(context.logger(), failure);
    }

    @Test
    void queueRejectionClosesCandidateAndFallsBack() {
        SetupContext context = configuredPlugin();
        ClaimVisualization candidate = mock(ClaimVisualization.class);
        RejectedExecutionException failure = new RejectedExecutionException("executor stopped");
        doThrow(failure).when(context.plugin()).runAsyncQueued(any());

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenReturn(candidate);

            assertDoesNotThrow(() -> invokeSetupClaimVisualization(context.plugin()));
        }

        verify(candidate).close();
        assertUsableNoop(context.plugin());
        verifyInitializationFailureLogged(context.logger(), failure);
    }

    @Test
    void candidateCloseFailureIsSuppressedOnQueueFailure() {
        SetupContext context = configuredPlugin();
        ClaimVisualization candidate = mock(ClaimVisualization.class);
        RejectedExecutionException queueFailure = new RejectedExecutionException("executor stopped");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        doThrow(queueFailure).when(context.plugin()).runAsyncQueued(any());
        doThrow(closeFailure).when(candidate).close();

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenReturn(candidate);

            assertDoesNotThrow(() -> invokeSetupClaimVisualization(context.plugin()));
        }

        assertEquals(1, queueFailure.getSuppressed().length);
        assertSame(closeFailure, queueFailure.getSuppressed()[0]);
        assertUsableNoop(context.plugin());
        verifyInitializationFailureLogged(context.logger(), queueFailure);
    }

    @Test
    void sameCandidateCloseFailureIsNotSelfSuppressed() {
        SetupContext context = configuredPlugin();
        ClaimVisualization candidate = mock(ClaimVisualization.class);
        RejectedExecutionException failure = new RejectedExecutionException("shared failure");
        doThrow(failure).when(context.plugin()).runAsyncQueued(any());
        doThrow(failure).when(candidate).close();

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenReturn(candidate);

            assertDoesNotThrow(() -> invokeSetupClaimVisualization(context.plugin()));
        }

        assertEquals(0, failure.getSuppressed().length);
        assertUsableNoop(context.plugin());
        verifyInitializationFailureLogged(context.logger(), failure);
    }

    @Test
    void rebuildsClaimsAfterQueuedRepositoryLoadCompletes() throws Throwable {
        SetupContext context = configuredPlugin();
        ClaimVisualization visualization = mock(ClaimVisualization.class);
        List<String> events = new ArrayList<>();

        doAnswer(invocation -> {
            events.add("rebuild");
            return null;
        }).when(visualization).rebuild();

        context.queuedTasks().add(() -> events.add("repository-ready"));
        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenReturn(visualization);

            invokeSetupClaimVisualization(context.plugin());
        }
        context.queuedTasks().forEach(Runnable::run);

        assertEquals(List.of("repository-ready", "rebuild"), events);
        verify(context.logger()).info(anyString());
    }

    @Test
    void visualizationCloseFailureDoesNotSkipRemainingShutdown() {
        LandsPlugin plugin = pluginWithLogger();
        ClaimVisualization visualization = mock(ClaimVisualization.class);
        ExecutorService executor = mock(ExecutorService.class);
        Collection<SeeClaims> displays = mockDisplays();
        IllegalStateException failure = new IllegalStateException("BlueMap close failed");
        doThrow(failure).when(visualization).close();

        assertDoesNotThrow(() ->
                invokeShutdownComponents(plugin, visualization, executor, displays));

        verify(executor).shutdown();
        verify(displays).forEach(any());
        verifyInitializationFailureLogged(plugin.getLogger(), failure);
    }

    @Test
    void displayStopFailureEscapesWithoutBeingLoggedAsBlueMapFailure() {
        LandsPlugin plugin = pluginWithLogger();
        ClaimVisualization visualization = mock(ClaimVisualization.class);
        ExecutorService executor = mock(ExecutorService.class);
        Collection<SeeClaims> displays = mockDisplays();
        IllegalStateException failure = new IllegalStateException("display stop failed");
        doThrow(failure).when(displays).forEach(any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                invokeShutdownComponents(plugin, visualization, executor, displays));

        assertSame(failure, thrown);
        verify(executor).shutdown();
        verifyNoInteractions(plugin.getLogger());
    }

    @Test
    void outOfMemoryErrorFromBootstrapEscapes() {
        SetupContext context = configuredPlugin();
        OutOfMemoryError failure = new OutOfMemoryError("fatal");

        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(context.plugin()))
                    .thenThrow(failure);

            OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                    () -> invokeSetupClaimVisualization(context.plugin()));
            assertSame(failure, thrown);
        }

        assertTrue(context.queuedTasks().isEmpty());
        verifyNoInteractions(context.logger());
    }

    @Test
    void outOfMemoryErrorFromVisualizationCloseEscapes() {
        LandsPlugin plugin = pluginWithLogger();
        ClaimVisualization visualization = mock(ClaimVisualization.class);
        ExecutorService executor = mock(ExecutorService.class);
        Collection<SeeClaims> displays = mockDisplays();
        OutOfMemoryError failure = new OutOfMemoryError("fatal");
        doThrow(failure).when(visualization).close();

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
                invokeShutdownComponents(plugin, visualization, executor, displays));

        assertSame(failure, thrown);
        verify(executor, never()).shutdown();
        verify(displays, never()).forEach(any());
        verifyNoInteractions(plugin.getLogger());
    }

    private SetupContext configuredPlugin() {
        LandsPlugin plugin = pluginWithLogger();
        FileConfiguration config = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();

        doReturn(config).when(plugin).getConfig();
        when(config.getBoolean("bluemap.enabled", true)).thenReturn(true);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.isPluginEnabled("BlueMap")).thenReturn(true);
        doAnswer(invocation -> {
            queuedTasks.add(invocation.getArgument(0));
            return null;
        }).when(plugin).runAsyncQueued(any());

        return new SetupContext(plugin, plugin.getLogger(), queuedTasks);
    }

    private LandsPlugin pluginWithLogger() {
        LandsPlugin plugin = mock(LandsPlugin.class, CALLS_REAL_METHODS);
        Logger logger = mock(Logger.class);
        doReturn(logger).when(plugin).getLogger();
        return plugin;
    }

    @SuppressWarnings("unchecked")
    private Collection<SeeClaims> mockDisplays() {
        return mock(Collection.class);
    }

    private void assertUsableNoop(LandsPlugin plugin) {
        ClaimVisualization visualization = plugin.getClaimVisualization();
        assertNotNull(visualization);
        assertSame(ClaimVisualization.noop(), visualization);
        assertDoesNotThrow(visualization::rebuild);
        assertDoesNotThrow(visualization::close);
    }

    private void verifyInitializationFailureLogged(Logger logger, Throwable failure) {
        verify(logger).log(eq(Level.SEVERE), anyString(), same(failure));
        verifyNoMoreInteractions(logger);
    }

    private void invokeSetupClaimVisualization(LandsPlugin plugin) throws Throwable {
        invokePluginMethod(plugin, "setupClaimVisualization", new Class<?>[0]);
    }

    private void invokeShutdownComponents(
            LandsPlugin plugin,
            ClaimVisualization visualization,
            ExecutorService executor,
            Collection<SeeClaims> displays
    ) {
        plugin.shutdownComponents(visualization, executor, displays);
    }

    private void invokePluginMethod(
            LandsPlugin plugin,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Throwable {
        Method method = LandsPlugin.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            method.invoke(plugin, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private record SetupContext(
            LandsPlugin plugin,
            Logger logger,
            Queue<Runnable> queuedTasks
    ) {
    }

}
