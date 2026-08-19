package klumpler.lazycraft;

import klumpler.lazycraft.client.command.ShoppingListCommand;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric-specific client bootstrap.
 */
public final class LazyCraftFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LazyCraft.LOGGER.info("LazyCraft loaded!");
        LazyCraftConfigManager.initialize(
                FabricLoader.getInstance().getConfigDir().resolve(LazyCraft.MOD_ID + ".json")
        );
        LazyCraftConfigManager.load();
        ClientCommandRegistrationCallback.EVENT.register(ShoppingListCommand::register);
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            CraftingExecutor.tick(minecraft);
            VisibleRecipeCraftability.tick(minecraft);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> {
            ShoppingListCommand.shutdown();
            VisibleRecipeCraftability.shutdown();
        });
    }
}
