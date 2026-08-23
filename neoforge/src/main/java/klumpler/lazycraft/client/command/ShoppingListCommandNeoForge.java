package klumpler.lazycraft.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public final class ShoppingListCommandNeoForge {
    private static final ExecutorService PLANNING_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "LazyCraft shopping-list planner");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private static volatile long requestGeneration;
    private static CompletableFuture<?> currentTask;
    private static boolean shuttingDown;

    private ShoppingListCommandNeoForge() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("lazycraft")
                .then(Commands.literal("hand")
                        .then(Commands.literal("ingredients").executes(context -> executeHeld(context, RecipePlanner.ShoppingMode.INGREDIENTS)))
                        .then(Commands.literal("raw").executes(context -> executeHeld(context, RecipePlanner.ShoppingMode.RAW))))
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .then(Commands.literal("ingredients").executes(context -> executeItem(context, RecipePlanner.ShoppingMode.INGREDIENTS)))
                        .then(Commands.literal("raw").executes(context -> executeItem(context, RecipePlanner.ShoppingMode.RAW)))));
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

    private static int executeHeld(CommandContext<CommandSourceStack> context, RecipePlanner.ShoppingMode mode) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            context.getSource().sendFailure(Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }
        ItemStack heldStack = player.getMainHandItem();
        if (heldStack.isEmpty()) heldStack = player.getOffhandItem();
        if (heldStack.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("commands.lazycraft.shopping.empty_hand"));
            return 0;
        }
        return start(context.getSource(), player, heldStack.getItem(), mode);
    }

    private static int executeItem(CommandContext<CommandSourceStack> context, RecipePlanner.ShoppingMode mode) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            context.getSource().sendFailure(Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }
        Item item = ItemArgument.getItem(context, "item").item().value();
        return start(context.getSource(), player, item, mode);
    }

    private static int start(CommandSourceStack source, LocalPlayer player, Item target, RecipePlanner.ShoppingMode mode) {
        Optional<RecipePlanner.PlanningSession> optionalSession = RecipePlanner.createShoppingSession(player);
        if (optionalSession.isEmpty()) {
            source.sendFailure(Component.translatable("commands.lazycraft.shopping.unavailable"));
            return 0;
        }

        long request = ++requestGeneration;
        if (currentTask != null) currentTask.cancel(false);

        Map<Item, Integer> availableItemCounts = RecipePlanner.availableItemCounts(player);
        Component modeName = modeName(mode);
        Component targetName = new ItemStack(target).getHoverName();
        source.sendSuccess(() -> Component.translatable("commands.lazycraft.shopping.calculating", modeName, targetName), false);

        try {
            CompletableFuture<Optional<RecipePlanner.ShoppingList>> task = CompletableFuture.supplyAsync(
                    () -> optionalSession.get().shoppingList(target, mode, () -> request != requestGeneration), PLANNING_EXECUTOR);
            currentTask = task;
            task.whenCompleteAsync((shoppingList, failure) -> complete(
                    source, player, availableItemCounts, target, mode, request, shoppingList, failure), Minecraft.getInstance());
            return 1;
        } catch (RejectedExecutionException exception) {
            currentTask = null;
            source.sendFailure(Component.translatable("commands.lazycraft.shopping.failed"));
            if (!shuttingDown) LazyCraft.LOGGER.warn("Could not start shopping-list planning", exception);
            return 0;
        }
    }

    private static void complete(CommandSourceStack source, LocalPlayer player, Map<Item, Integer> availableItemCounts,
                                 Item target, RecipePlanner.ShoppingMode mode, long request,
                                 Optional<RecipePlanner.ShoppingList> shoppingList, Throwable failure) {
        if (request != requestGeneration) return;
        currentTask = null;
        if (Minecraft.getInstance().player != player) return;
        Throwable cause = unwrapCompletionException(failure);
        if (cause instanceof CancellationException) return;
        if (cause != null) {
            LazyCraft.LOGGER.warn("Could not calculate shopping list for {}", target, cause);
            source.sendFailure(Component.translatable("commands.lazycraft.shopping.failed"));
            return;
        }
        if (!RecipePlanner.availableItemCounts(player).equals(availableItemCounts)) {
            source.sendFailure(Component.translatable("commands.lazycraft.shopping.inventory_changed"));
            return;
        }
        if (shoppingList.isEmpty()) {
            source.sendFailure(Component.translatable("commands.lazycraft.shopping.no_recipe", new ItemStack(target).getHoverName()));
            return;
        }
        RecipePlanner.ShoppingList result = shoppingList.get();
        if (result.missingItems().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.lazycraft.shopping.complete", new ItemStack(target).getHoverName()), false);
            return;
        }
        source.sendSuccess(() -> Component.translatable("commands.lazycraft.shopping.title", modeName(mode), new ItemStack(target).getHoverName()), false);
        for (RecipePlanner.MissingItem missingItem : result.missingItems()) {
            source.sendSuccess(() -> Component.translatable("commands.lazycraft.shopping.entry", missingItem.count(), new ItemStack(missingItem.item()).getHoverName()), false);
        }
    }

    private static Throwable unwrapCompletionException(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static Component modeName(RecipePlanner.ShoppingMode mode) {
        return Component.translatable(switch (mode) {
            case INGREDIENTS -> "commands.lazycraft.shopping.mode.ingredients";
            case RAW -> "commands.lazycraft.shopping.mode.raw";
        });
    }
}
