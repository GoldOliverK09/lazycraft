package klumpler.lazycraft.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import klumpler.lazycraft.LazyCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Owns LazyCraft's configuration independently of any optional screen library.
 */
public final class LazyCraftConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(LazyCraft.MOD_ID + ".json");

    private static LazyCraftConfig config = new LazyCraftConfig();
    private static boolean loaded;

    private LazyCraftConfigManager() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        boolean createDefaultFile = !Files.exists(CONFIG_PATH);
        LazyCraftConfig loadedConfig = createDefaultFile
                ? new LazyCraftConfig()
                : readConfig();
        loadedConfig.validate();
        config = loadedConfig;
        loaded = true;

        if (createDefaultFile) {
            save();
        }
    }

    public static synchronized LazyCraftConfig get() {
        load();
        return config;
    }

    public static synchronized void save() {
        load();
        config.validate();

        Path temporaryPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(temporaryPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }

            moveIntoPlace(temporaryPath);
        } catch (IOException exception) {
            LazyCraft.LOGGER.error("Could not save LazyCraft config to {}", CONFIG_PATH, exception);
        }
    }

    private static LazyCraftConfig readConfig() {
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            LazyCraftConfig loadedConfig = GSON.fromJson(reader, LazyCraftConfig.class);
            if (loadedConfig != null) {
                return loadedConfig;
            }
            LazyCraft.LOGGER.warn("LazyCraft config was empty; using defaults");
        } catch (IOException | JsonParseException exception) {
            LazyCraft.LOGGER.error("Could not load LazyCraft config from {}; using defaults", CONFIG_PATH, exception);
        }
        return new LazyCraftConfig();
    }

    private static void moveIntoPlace(Path temporaryPath) throws IOException {
        try {
            Files.move(
                    temporaryPath,
                    CONFIG_PATH,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
