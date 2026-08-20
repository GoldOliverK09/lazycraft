package klumpler.lazycraft;

import klumpler.lazycraft.client.command.ShoppingListCommand;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.integration.LazyCraftNeoForgeConfigIntegration;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppedEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = LazyCraft.MOD_ID, dist = Dist.CLIENT)
public final class LazyCraftNeoForge {
    public LazyCraftNeoForge(ModContainer container) {
        LazyCraft.LOGGER.info("LazyCraft loaded!");
        LazyCraftConfigManager.initialize(
                FMLPaths.CONFIGDIR.get().resolve(LazyCraft.MOD_ID + ".json")
        );
        LazyCraftConfigManager.load();
        LazyCraftNeoForgeConfigIntegration.register(container);

        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onClientStopped);
    }

    private void onClientTick(ClientTickEvent.Post ignored) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        CraftingExecutor.tick(minecraft);
        VisibleRecipeCraftability.tick(minecraft);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ShoppingListCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    private void onClientStopped(ClientStoppedEvent ignored) {
        ShoppingListCommand.shutdown();
        VisibleRecipeCraftability.shutdown();
    }
}
