package fr.iban.lands.commands.parametertypes;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.model.land.Land;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.parameter.ParameterType;
import revxrsal.commands.parameter.PrioritySpec;
import revxrsal.commands.stream.MutableStringStream;

import java.util.List;

public class LandParameterType implements ParameterType<BukkitCommandActor, Land> {

    private final LandsPlugin plugin;

    public LandParameterType(LandsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Land parse(@NotNull MutableStringStream input, @NotNull ExecutionContext<BukkitCommandActor> executionContext) {
        String value = input.readString();
        BukkitCommandActor actor = executionContext.actor();
        Player sender = actor.asPlayer();

        if (sender == null) {
            throw new CommandErrorException("Vous devez être un joueur pour exécuter cette commande.");
        }

        Land land = plugin.getLandRepository().getManageableLandFromName(sender, value);

        if (land == null) {
            throw new CommandErrorException("Le territoire " + value + " n''existe pas.");
        }

        return land;
    }

    @Override
    public @NotNull SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
        return (context) -> {
            Player player = context.actor().asPlayer();

            if (player == null) return List.of();

            return plugin.getLandRepository().getManageableLandsNames(player);
        };
    }

    @Override
    public @NotNull PrioritySpec parsePriority() {
        return PrioritySpec.highest();
    }
}