package klumpler.lazycraft.client.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import klumpler.lazycraft.client.config.LazyCraftConfigScreen;

public final class LazyCraftModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LazyCraftConfigScreen::create;
    }
}
