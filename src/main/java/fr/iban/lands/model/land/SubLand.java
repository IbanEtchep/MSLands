package fr.iban.lands.model.land;

import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.utils.Cuboid;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class SubLand extends Land {

    private Land superLand;

    private Cuboid cuboid;
    private String server;

    public SubLand(UUID id, String name) {
        super(id, name);
    }

    public void setCuboid(Cuboid cuboid, String server) {
        this.cuboid = cuboid;
        this.server = server;
    }

    @Override
    public void refreshFrom(Land fresh) {
        super.refreshFrom(fresh);
        if (fresh instanceof SubLand sub) setCuboid(sub.cuboid, sub.server);
    }

    public Cuboid getCuboid() {
        return cuboid;
    }

    public String getServer() {
        return server;
    }

    public Land getSuperLand() {
        return superLand;
    }

    public void setSuperLand(Land superLand) {
        this.superLand = superLand;
    }

    @Override
    public boolean isBypassing(Player player, Action action) {
        UUID owner = getOwner();
        if (owner != null && player.getUniqueId().equals(owner)) {
            return true;
        }
        return super.isBypassing(player, action);
    }

    @Override
    public @Nullable UUID getOwner() {
        if (superLand instanceof PlayerLand pland) {
            return pland.getOwner();
        }
        return null;
    }

    @Override
    public LandType getType() {
        return LandType.SUBLAND;
    }
}
