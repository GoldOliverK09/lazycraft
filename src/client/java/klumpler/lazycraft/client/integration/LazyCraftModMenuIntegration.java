package klumpler.lazycraft.client.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import klumpler.lazycraft.LazyCraft;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class LazyCraftModMenuIntegration implements ModMenuApi {
    private static final String CLOTH_CONFIG_MOD_ID = "cloth-config";
    private static final String CLOTH_SCREEN_CLASS =
            "klumpler.lazycraft.client.config.LazyCraftConfigScreen";

    private static Screen createConfigScreen(Screen parent) {
        Screen clothScreen = createClothScreen(parent);
        if (clothScreen != null) {
            return clothScreen;
        }

        return new AlertScreen(
                () -> Minecraft.getInstance().setScreen(parent),
                Component.translatable("text.lazycraft.config.cloth_missing.title"),
                Component.translatable("text.lazycraft.config.cloth_missing.message"),
                CommonComponents.GUI_BACK,
                false
        );
    }

    private static Screen createClothScreen(Screen parent) {
        try {
            Class<?> screenClass = Class.forName(CLOTH_SCREEN_CLASS);
            Method factory = screenClass.getMethod("create", Screen.class);
            return (Screen) factory.invoke(null, parent);
        } catch (ClassCastException | ReflectiveOperationException | LinkageError exception) {
            Throwable cause = exception instanceof InvocationTargetException invocationException
                    ? invocationException.getCause()
                    : exception;
            LazyCraft.LOGGER.error("Could not open LazyCraft's Cloth Config screen", cause);
            return null;
        }
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded(CLOTH_CONFIG_MOD_ID)) {
            return ModMenuApi.super.getModConfigScreenFactory();
        }

        return LazyCraftModMenuIntegration::createConfigScreen;
    }
}
