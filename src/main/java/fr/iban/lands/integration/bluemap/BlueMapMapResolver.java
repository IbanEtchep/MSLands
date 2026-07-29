package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.List;

public class BlueMapMapResolver {

    public Collection<BlueMapMap> resolve(BlueMapAPI api, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return List.of();
        }

        return api.getWorld(world)
                .<Collection<BlueMapMap>>map(BlueMapWorld::getMaps)
                .orElseGet(List::of);
    }
}
