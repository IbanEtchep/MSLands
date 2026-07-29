package fr.iban.lands;

import fr.iban.lands.integration.bluemap.BlueMapClaimVisualization;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class LandsPluginTest {

    @Test
    void rebuildsClaimsAfterQueuedRepositoryLoadCompletes() throws Exception {
        LandsPlugin plugin = mock(LandsPlugin.class, CALLS_REAL_METHODS);
        FileConfiguration config = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        BlueMapClaimVisualization visualization = mock(BlueMapClaimVisualization.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        List<String> events = new ArrayList<>();

        doReturn(config).when(plugin).getConfig();
        when(config.getBoolean("bluemap.enabled", true)).thenReturn(true);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.isPluginEnabled("BlueMap")).thenReturn(true);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        doAnswer(invocation -> {
            queuedTasks.add(invocation.getArgument(0));
            return null;
        }).when(plugin).runAsyncQueued(any());
        doAnswer(invocation -> {
            events.add("rebuild");
            return null;
        }).when(visualization).rebuild();

        queuedTasks.add(() -> events.add("repository-ready"));
        try (MockedStatic<BlueMapClaimVisualization> blueMap =
                     mockStatic(BlueMapClaimVisualization.class)) {
            blueMap.when(() -> BlueMapClaimVisualization.create(plugin))
                    .thenReturn(visualization);

            invokeSetupClaimVisualization(plugin);
        }
        queuedTasks.forEach(Runnable::run);

        assertEquals(List.of("repository-ready", "rebuild"), events);
    }

    private void invokeSetupClaimVisualization(LandsPlugin plugin) throws Exception {
        Method setup = LandsPlugin.class.getDeclaredMethod("setupClaimVisualization");
        setup.setAccessible(true);
        setup.invoke(plugin);
    }
}
