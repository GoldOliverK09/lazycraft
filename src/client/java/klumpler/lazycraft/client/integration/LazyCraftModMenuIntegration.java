package klumpler.lazycraft.client.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import klumpler.lazycraft.client.config.LazyCraftConfigScreens;
import net.fabricmc.loader.api.FabricLoader;

public final class LazyCraftModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return ModMenuApi.super.getModConfigScreenFactory();
        }

        return LazyCraftConfigScreens::create;
    }
}
