package klumpler.lazycraft;

import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LazyCraft implements ClientModInitializer {
    public static final String MOD_ID = "lazycraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("LazyCraft loaded!");
        LazyCraftConfigManager.load();
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            CraftingExecutor.tick(minecraft);
            VisibleRecipeCraftability.tick(minecraft);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored ->
                VisibleRecipeCraftability.shutdown());
    }
}
