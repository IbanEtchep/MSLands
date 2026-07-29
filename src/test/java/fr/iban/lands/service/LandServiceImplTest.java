package fr.iban.lands.service;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.PlayerLand;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LandServiceImplTest {

    private LandsPlugin plugin;
    private LandRepository repository;
    private ClaimVisualization visualization;
    private LandServiceImpl service;

    @BeforeEach
    void setUp() {
        plugin = mock(LandsPlugin.class);
        repository = mock(LandRepository.class);
        visualization = mock(ClaimVisualization.class);
        when(plugin.getLandRepository()).thenReturn(repository);
        when(plugin.getClaimVisualization()).thenReturn(visualization);
        service = new LandServiceImpl(plugin);
    }

    @Test
    void successfulRenameSynchronizesLandAfterRepositoryUpdate() {
        PlayerLand land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "OldName");
        Player player = mock(Player.class);
        when(repository.getLands()).thenReturn(List.of());

        service.renameLand(land, player, "NewName");

        InOrder order = inOrder(repository, visualization);
        order.verify(repository).updateLand(land);
        order.verify(visualization).syncLand(land);
    }

    @Test
    void primitiveClaimSynchronizesChunkAfterRepositoryAddition() {
        SChunk chunk = new SChunk("survival", "world", 3, -5);
        PlayerLand land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Home");

        service.claim(chunk, land);

        InOrder order = inOrder(repository, visualization);
        order.verify(repository).addChunk(chunk, land);
        order.verify(visualization).syncChunk(chunk);
    }

    @Test
    void primitiveUnclaimSynchronizesChunkAfterRepositoryRemoval() {
        SChunk chunk = new SChunk("survival", "world", 3, -5);

        service.unclaim(chunk);

        InOrder order = inOrder(repository, visualization);
        order.verify(repository).removeChunk(chunk);
        order.verify(visualization).syncChunk(chunk);
    }
}
