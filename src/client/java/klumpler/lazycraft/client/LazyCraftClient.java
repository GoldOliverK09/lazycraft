package klumpler.lazycraft.client;

import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class LazyCraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LazyCraftConfigManager.load();
        ClientTickEvents.END_CLIENT_TICK.register(CraftingExecutor::tick);
        ClientTickEvents.END_CLIENT_TICK.register(VisibleRecipeCraftability::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored ->
                VisibleRecipeCraftability.shutdown());
    }
}
