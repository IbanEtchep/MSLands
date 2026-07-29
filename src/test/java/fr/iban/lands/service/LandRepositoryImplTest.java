package fr.iban.lands.service;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.storage.SqlStorage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LandRepositoryImplTest {

    @Test
    void deleteLandSynchronizesEveryCapturedChunkAfterCacheRemoval() {
        LandsPlugin plugin = mock(LandsPlugin.class);
        ClaimVisualization visualization = mock(ClaimVisualization.class);
        when(plugin.getClaimVisualization()).thenReturn(visualization);

        try (var ignored = mockConstruction(SqlStorage.class)) {
            LandRepositoryImpl repository = new LandRepositoryImpl(plugin);
            PlayerLand land =
                    new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Home");
            SubLand subLand = new SubLand(UUID.randomUUID(), "Mine");
            subLand.setSuperLand(land);
            land.getSubLands().put(subLand.getId(), subLand);
            SChunk first = new SChunk("survival", "world", 1, 2);
            SChunk second = new SChunk("survival", "world_nether", -3, 4);
            SChunk subLandChunk = new SChunk("survival", "world", 8, 9);
            repository.addLand(land);
            repository.addLand(subLand);
            repository.addChunk(first, land);
            repository.addChunk(second, land);
            repository.addChunk(subLandChunk, subLand);
            reset(visualization);
            doAnswer(invocation -> {
                SChunk chunk = invocation.getArgument(0);
                assertEquals(repository.getWilderness(), repository.getLandAt(chunk));
                return null;
            }).when(visualization).syncChunk(org.mockito.ArgumentMatchers.any());

            repository.deleteLand(land);

            assertTrue(repository.getLandById(land.getId()).isEmpty());
            assertTrue(repository.getLandById(subLand.getId()).isEmpty());
            assertTrue(repository.getChunks(land).isEmpty());
            assertTrue(repository.getChunks(subLand).isEmpty());
            verify(visualization).syncChunk(first);
            verify(visualization).syncChunk(second);
            verify(visualization).syncChunk(subLandChunk);
            verifyNoMoreInteractions(visualization);
        }
    }
}
