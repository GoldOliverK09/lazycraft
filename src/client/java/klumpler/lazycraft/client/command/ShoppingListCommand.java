package klumpler.lazycraft.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.concurrent.*;

/**
 * Registers and renders the client-side shopping-list command. Recipe analysis remains in
 * {@link RecipePlanner}; this class only captures command inputs and reports its result.
 */
public final class ShoppingListCommand {
    private static final ExecutorService PLANNING_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "LazyCraft shopping-list planner");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });

    private static volatile long requestGeneration;
    private static CompletableFuture<?> currentTask;
    private static boolean shuttingDown;

    private ShoppingListCommand() {
    }

    public static void register(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            CommandBuildContext buildContext
    ) {
        dispatcher.register(ClientCommands.literal("lazycraft")
                .then(ClientCommands.literal("hand")
                        .then(ClientCommands.literal("ingredients")
                                .executes(context -> executeHeld(
                                        context,
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(ClientCommands.literal("raw")
                                .executes(context -> executeHeld(
                                        context,
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
                .then(ClientCommands.argument("item", ItemArgument.item(buildContext))
                        .then(ClientCommands.literal("ingredients")
                                .executes(context -> executeItem(
                                        context,
                                        RecipePlanner.ShoppingMode.INGREDIENTS
                                )))
                        .then(ClientCommands.literal("raw")
                                .executes(context -> executeItem(
                                        context,
                                        RecipePlanner.ShoppingMode.RAW
                                ))))
        );
    }

    public static void shutdown() {
        shuttingDown = true;
        requestGeneration++;
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
        PLANNING_EXECUTOR.shutdownNow();
    }

    private static int executeHeld(
            CommandContext<FabricClientCommandSource> context,
            RecipePlanner.ShoppingMode mode
    ) {
        LocalPlayer player = context.getSource().getPlayer();
        ItemStack heldStack = player.getMainHandItem();
        if (heldStack.isEmpty()) {
            heldStack = player.getOffhandItem();
        }
        if (heldStack.isEmpty()) {
            context.getSource().sendError(Component.translatable(
                    "commands.lazycraft.shopping.empty_hand"
            ));
            return 0;
        }
        return start(context.getSource(), heldStack.getItem(), mode);
    }

    private static int executeItem(
            CommandContext<FabricClientCommandSource> context,
            RecipePlanner.ShoppingMode mode
    ) {
        Item item = ItemArgument.getItem(context, "item").item().value();
        return start(context.getSource(), item, mode);
    }

    private static int start(
            FabricClientCommandSource source,
            Item target,
            RecipePlanner.ShoppingMode mode
    ) {
        Optional<RecipePlanner.PlanningSession> optionalSession =
                RecipePlanner.createShoppingSession(source.getPlayer());
        if (optionalSession.isEmpty()) {
            source.sendError(Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }

        long request = ++requestGeneration;
        if (currentTask != null) {
            currentTask.cancel(false);
        }

        LocalPlayer player = source.getPlayer();
        int inventoryVersion = player.getInventory().getTimesChanged();
        Component modeName = modeName(mode);
        Component targetName = new ItemStack(target).getHoverName();
        source.sendFeedback(Component.translatable(
                "commands.lazycraft.shopping.calculating",
                modeName,
                targetName
        ));

        try {
            CompletableFuture<Optional<RecipePlanner.ShoppingList>> task =
                    CompletableFuture.supplyAsync(
                            () -> optionalSession.get().shoppingList(
                                    target,
                                    mode,
                                    () -> request != requestGeneration
                            ),
                            PLANNING_EXECUTOR
                    );
            currentTask = task;
            task.whenCompleteAsync(
                    (shoppingList, failure) -> complete(
                            source,
                            player,
                            inventoryVersion,
                            target,
                            mode,
                            request,
                            shoppingList,
                            failure
                    ),
                    source.getClient()
            );
            return 1;
        } catch (RejectedExecutionException exception) {
            currentTask = null;
            source.sendError(Component.translatable("commands.lazycraft.shopping.failed"));
            if (!shuttingDown) {
                LazyCraft.LOGGER.warn("Could not start shopping-list planning", exception);
            }
            return 0;
        }
    }

    private static void complete(
            FabricClientCommandSource source,
            LocalPlayer player,
            int inventoryVersion,
            Item target,
            RecipePlanner.ShoppingMode mode,
            long request,
            Optional<RecipePlanner.ShoppingList> shoppingList,
            Throwable failure
    ) {
        if (request != requestGeneration) {
            return;
        }
        currentTask = null;
        if (source.getClient().player != player) {
            return;
        }

        Throwable cause = unwrapCompletionException(failure);
        if (cause instanceof CancellationException) {
            return;
        }
        if (cause != null) {
            LazyCraft.LOGGER.warn("Could not calculate shopping list for {}", target, cause);
            source.sendError(Component.translatable("commands.lazycraft.shopping.failed"));
            return;
        }
        if (player.getInventory().getTimesChanged() != inventoryVersion) {
            source.sendError(Component.translatable("commands.lazycraft.shopping.inventory_changed"));
            return;
        }
        if (shoppingList == null || shoppingList.isEmpty()) {
            source.sendError(Component.translatable(
                    "commands.lazycraft.shopping.no_recipe",
                    new ItemStack(target).getHoverName()
            ));
            return;
        }

        RecipePlanner.ShoppingList result = shoppingList.get();
        if (result.missingItems().isEmpty()) {
            source.sendFeedback(Component.translatable(
                    "commands.lazycraft.shopping.complete",
                    new ItemStack(target).getHoverName()
            ));
            return;
        }

        source.sendFeedback(Component.translatable(
                "commands.lazycraft.shopping.title",
                modeName(mode),
                new ItemStack(target).getHoverName()
        ));
        for (RecipePlanner.MissingItem missingItem : result.missingItems()) {
            source.sendFeedback(Component.translatable(
                    "commands.lazycraft.shopping.entry",
                    missingItem.count(),
                    new ItemStack(missingItem.item()).getHoverName()
            ));
        }
    }

    private static Throwable unwrapCompletionException(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Component modeName(RecipePlanner.ShoppingMode mode) {
        return Component.translatable(switch (mode) {
            case INGREDIENTS -> "commands.lazycraft.shopping.mode.ingredients";
            case RAW -> "commands.lazycraft.shopping.mode.raw";
        });
    }
}
