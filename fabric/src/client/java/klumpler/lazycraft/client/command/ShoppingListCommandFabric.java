package klumpler.lazycraft.client.command;

import com.mojang.brigadier.CommandDispatcher;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;

public final class ShoppingListCommandFabric
        extends ShoppingListCommand<FabricClientCommandSource> {
    private static final ShoppingListCommandFabric INSTANCE = new ShoppingListCommandFabric();

    private ShoppingListCommandFabric() {
    }

    public static void register(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            CommandBuildContext buildContext
    ) {
        dispatcher.register(ClientCommands.literal("lazycraft")
                .then(ClientCommands.literal("hand")
                        .then(ClientCommands.literal("ingredients")
                                .executes(context -> INSTANCE.executeHeld(
                                        context.getSource(),
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(ClientCommands.literal("raw")
                                .executes(context -> INSTANCE.executeHeld(
                                        context.getSource(),
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
                .then(ClientCommands.argument("item", ItemArgument.item(buildContext))
                        .then(ClientCommands.literal("ingredients")
                                .executes(context -> INSTANCE.executeItem(
                                        context.getSource(),
                                        ItemArgument.getItem(context, "item").item().value(),
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(ClientCommands.literal("raw")
                                .executes(context -> INSTANCE.executeItem(
                                        context.getSource(),
                                        ItemArgument.getItem(context, "item").item().value(),
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
        );
    }

    public static void shutdown() {
        INSTANCE.shutdownCommand();
    }

    @Override
    protected Minecraft client(FabricClientCommandSource source) {
        return source.getClient();
    }

    @Override
    protected LocalPlayer player(FabricClientCommandSource source) {
        return source.getPlayer();
    }

    @Override
    protected void sendFeedback(FabricClientCommandSource source, Component message) {
        source.sendFeedback(message);
    }

    @Override
    protected void sendError(FabricClientCommandSource source, Component message) {
        source.sendError(message);
    }
}
