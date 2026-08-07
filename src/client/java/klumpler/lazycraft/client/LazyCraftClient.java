package klumpler.lazycraft.client;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigScreen;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.RecipePlanner;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class LazyCraftClient implements ClientModInitializer {
    private static int planSelectedItem(FabricClientCommandSource source) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            source.sendFeedback(Component.literal("LazyCraft needs an active player to create a plan."));
            return 0;
        }

        ItemStack selectedStack = player.getMainHandItem();
        if (selectedStack.isEmpty()) {
            source.sendFeedback(Component.literal("Hold an item in your selected hotbar slot first."));
            return 0;
        }

        long startNanos = System.nanoTime();
        var plan = RecipePlanner.plan(selectedStack.getItem());
        if (plan.isPresent()) {
            var selectedPlan = plan.get();
            RecipePlanner.logPlan(selectedPlan, startNanos);
            source.sendFeedback(Component.literal(
                    "LazyCraft plan written to the log (" + selectedPlan.steps().size() + " steps)."
            ));
            return 1;
        }

        source.sendFeedback(Component.literal(
                "LazyCraft could not find a crafting-table plan for the selected item."
        ));
        source.sendFeedback(Component.literal(
                "Took " + ((System.nanoTime() - startNanos) / 1_000_000.0) + " ms"
        ));
        return 0;
    }

    private static int openConfigScreen() {
        Minecraft.getInstance().setScreenAndShow(LazyCraftConfigScreen.create(null));
        return 1;
    }

    private static int craftSelectedItem(FabricClientCommandSource source) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.getMainHandItem().isEmpty()) {
            source.sendFeedback(Component.literal("Hold the item you want to craft in your main hand."));
            return 0;
        }

        if (!CraftingExecutor.execute(player.getMainHandItem().getItem())) {
            source.sendFeedback(Component.literal(
                    "Open a crafting table and make sure LazyCraft is not already crafting."
            ));
            return 0;
        }

        source.sendFeedback(Component.literal("LazyCraft started the selected recipe."));
        return 1;
    }

    @Override
    public void onInitializeClient() {
        AutoConfig.register(LazyCraftConfig.class, GsonConfigSerializer::new);
        ClientTickEvents.END_CLIENT_TICK.register(CraftingExecutor::tick);
        ClientTickEvents.END_CLIENT_TICK.register(VisibleRecipeCraftability::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored ->
                VisibleRecipeCraftability.shutdown());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess) ->
                dispatcher.register(literal("lazycraft")
                        .executes(context -> planSelectedItem(context.getSource()))
                        .then(literal("config").executes(context -> openConfigScreen()))
                        .then(literal("craft").executes(context -> craftSelectedItem(context.getSource()))))
        );
    }
}
