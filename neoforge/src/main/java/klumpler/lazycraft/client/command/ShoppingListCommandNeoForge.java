package klumpler.lazycraft.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import klumpler.lazycraft.client.config.LazyCraftConfigCommand;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;

public final class ShoppingListCommandNeoForge
        extends ShoppingListCommand<CommandSourceStack> {
    private static final ShoppingListCommandNeoForge INSTANCE =
            new ShoppingListCommandNeoForge();

    private ShoppingListCommandNeoForge() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext
    ) {
        dispatcher.register(Commands.literal("lazycraft")
                .then(Commands.literal("hand")
                        .then(Commands.literal("ingredients")
                                .executes(context -> INSTANCE.executeHeld(
                                        context.getSource(),
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(Commands.literal("raw")
                                .executes(context -> INSTANCE.executeHeld(
                                        context.getSource(),
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .then(Commands.literal("ingredients")
                                .executes(context -> INSTANCE.executeItem(
                                        context.getSource(),
                                        ItemArgument.getItem(context, "item").getItem(),
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(Commands.literal("raw")
                                .executes(context -> INSTANCE.executeItem(
                                        context.getSource(),
                                        ItemArgument.getItem(context, "item").getItem(),
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
                .then(Commands.literal("config")
                        .then(Commands.argument("setting", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        LazyCraftConfigCommand.suggestSettings(builder))
                                .executes(context -> reportConfigResult(
                                        context.getSource(),
                                        LazyCraftConfigCommand.query(
                                                StringArgumentType.getString(context, "setting"))))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                LazyCraftConfigCommand.suggestValues(
                                                        StringArgumentType.getString(context, "setting"),
                                                        builder))
                                        .executes(context -> reportConfigResult(
                                                context.getSource(),
                                                LazyCraftConfigCommand.set(
                                                        StringArgumentType.getString(context, "setting"),
                                                        StringArgumentType.getString(context, "value")))))))
        );
    }

    private static int reportConfigResult(
            CommandSourceStack source,
            LazyCraftConfigCommand.Result result
    ) {
        if (result.success()) {
            source.sendSuccess(result::message, false);
            return 1;
        }
        source.sendFailure(result.message());
        return 0;
    }

    public static void shutdown() {
        INSTANCE.shutdownCommand();
    }

    @Override
    protected Minecraft client(CommandSourceStack source) {
        return Minecraft.getInstance();
    }

    @Override
    protected LocalPlayer player(CommandSourceStack source) {
        return client(source).player;
    }

    @Override
    protected void sendFeedback(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    @Override
    protected void sendError(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }
}
