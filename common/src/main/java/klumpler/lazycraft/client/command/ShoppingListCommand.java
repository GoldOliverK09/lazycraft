package klumpler.lazycraft.client.command;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public abstract class ShoppingListCommand<S> {
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

    private static Component itemName(Item item) {
        return new ItemStack(item).getHoverName();
    }

    protected final int executeHeld(S source, RecipePlanner.ShoppingMode mode) {
        LocalPlayer player = player(source);
        if (player == null) {
            sendError(source, Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }

        ItemStack heldStack = player.getMainHandItem();
        if (heldStack.isEmpty()) {
            heldStack = player.getOffhandItem();
        }
        if (heldStack.isEmpty()) {
            sendError(source, Component.translatable("commands.lazycraft.shopping.empty_hand"));
            return 0;
        }
        return start(source, player, heldStack.getItem(), mode);
    }

    protected final int executeItem(
            S source,
            Item target,
            RecipePlanner.ShoppingMode mode
    ) {
        LocalPlayer player = player(source);
        if (player == null) {
            sendError(source, Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }
        return start(source, player, target, mode);
    }

    protected final void shutdownCommand() {
        shuttingDown = true;
        requestGeneration++;
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
        PLANNING_EXECUTOR.shutdownNow();
    }

    private int start(
            S source,
            LocalPlayer player,
            Item target,
            RecipePlanner.ShoppingMode mode
    ) {
        Optional<RecipePlanner.PlanningSession> optionalSession =
                RecipePlanner.createShoppingSession(player);
        if (optionalSession.isEmpty()) {
            sendError(source, Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }

        long request = ++requestGeneration;
        if (currentTask != null) {
            currentTask.cancel(false);
        }

        Map<Item, Integer> availableItemCounts = RecipePlanner.availableItemCounts(player);
        sendFeedback(source, Component.translatable(
                "commands.lazycraft.shopping.calculating",
                modeName(mode),
                itemName(target)
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
                            availableItemCounts,
                            target,
                            mode,
                            request,
                            shoppingList,
                            failure
                    ),
                    client(source)
            );
            return 1;
        } catch (RejectedExecutionException exception) {
            currentTask = null;
            sendError(source, Component.translatable("commands.lazycraft.shopping.failed"));
            if (!shuttingDown) {
                LazyCraft.LOGGER.warn("Could not start shopping-list planning", exception);
            }
            return 0;
        }
    }

    private void complete(
            S source,
            LocalPlayer player,
            Map<Item, Integer> availableItemCounts,
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
        if (client(source).player != player) {
            return;
        }

        Throwable cause = unwrapCompletionException(failure);
        if (cause instanceof CancellationException) {
            return;
        }
        if (cause != null) {
            LazyCraft.LOGGER.warn("Could not calculate shopping list for {}", target, cause);
            sendError(source, Component.translatable("commands.lazycraft.shopping.failed"));
            return;
        }
        if (!RecipePlanner.availableItemCounts(player).equals(availableItemCounts)) {
            sendError(source, Component.translatable(
                    "commands.lazycraft.shopping.inventory_changed"
            ));
            return;
        }
        if (shoppingList.isEmpty()) {
            sendError(source, Component.translatable(
                    "commands.lazycraft.shopping.no_recipe",
                    itemName(target)
            ));
            return;
        }

        RecipePlanner.ShoppingList result = shoppingList.get();
        if (result.missingItems().isEmpty()) {
            sendFeedback(source, Component.translatable(
                    "commands.lazycraft.shopping.complete",
                    itemName(target)
            ));
            return;
        }

        sendFeedback(source, Component.translatable(
                "commands.lazycraft.shopping.title",
                modeName(mode),
                itemName(target)
        ));
        for (RecipePlanner.MissingItem missingItem : result.missingItems()) {
            sendFeedback(source, Component.translatable(
                    "commands.lazycraft.shopping.entry",
                    missingItem.count(),
                    itemName(missingItem.item())
            ));
        }
    }

    protected abstract Minecraft client(S source);

    protected abstract LocalPlayer player(S source);

    protected abstract void sendFeedback(S source, Component message);

    protected abstract void sendError(S source, Component message);
}
