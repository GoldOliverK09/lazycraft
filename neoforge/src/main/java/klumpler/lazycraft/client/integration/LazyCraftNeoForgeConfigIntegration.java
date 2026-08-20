package klumpler.lazycraft.client.integration;

import klumpler.lazycraft.LazyCraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class LazyCraftNeoForgeConfigIntegration {
    private static final String CLOTH_CONFIG_MOD_ID = "cloth_config";
    private static final String CLOTH_SCREEN_CLASS =
            "klumpler.lazycraft.client.config.LazyCraftConfigScreen";

    private LazyCraftNeoForgeConfigIntegration() {
    }

    public static void register(ModContainer container) {
        if (!ModList.get().isLoaded(CLOTH_CONFIG_MOD_ID)) {
            return;
        }
        container.registerExtensionPoint(IConfigScreenFactory.class,
                LazyCraftNeoForgeConfigIntegration::createConfigScreen);
    }

    private static Screen createConfigScreen(ModContainer ignored, Screen parent) {
        try {
            Class<?> screenClass = Class.forName(CLOTH_SCREEN_CLASS);
            Method factory = screenClass.getMethod("create", Screen.class);
            return (Screen) factory.invoke(null, parent);
        } catch (ClassCastException | ReflectiveOperationException | LinkageError exception) {
            Throwable cause = exception instanceof InvocationTargetException invocationException
                    ? invocationException.getCause()
                    : exception;
            LazyCraft.LOGGER.error("Could not open LazyCraft's Cloth Config screen", cause);
            return parent;
        }
    }
}
