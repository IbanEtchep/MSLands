package fr.iban.lands.commands;

import net.artisanmc.modules.api.playercommand.CommandArgument;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;

import java.util.Map;

/** {@code /sayinchat <message>}: the player says the message (a land's enter command can use it). */
public class MiscellaneousCommands {

    public static final String SAY_IN_CHAT = "lands:sayinchat";

    public PlayerCommandDeclaration declaration() {
        return PlayerCommandDeclaration.builder(SAY_IN_CHAT)
                .name("sayinchat")
                .description(Map.of("en", "Say a message in the chat", "fr", "Dire un message dans le chat"))
                .argument(CommandArgument.text("message"))
                .executor(CommandSupport.player((player, ctx) -> player.chat(CommandSupport.string(ctx, "message"))))
                .build();
    }
}
