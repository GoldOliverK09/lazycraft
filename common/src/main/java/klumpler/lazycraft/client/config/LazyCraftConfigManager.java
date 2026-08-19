package klumpler.lazycraft.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import klumpler.lazycraft.LazyCraft;

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
    private static Path configPath;

    private static LazyCraftConfig config = new LazyCraftConfig();
    private static boolean loaded;

    private LazyCraftConfigManager() {
    }

    /**
     * Must be called by the active loader before the configuration is accessed.
     */
    public static synchronized void initialize(Path path) {
        if (configPath != null && !configPath.equals(path)) {
            throw new IllegalStateException("LazyCraft configuration path was already initialized");
        }
        configPath = path;
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        Path path = configPath();
        boolean createDefaultFile = !Files.exists(path);
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

        Path path = configPath();
        Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporaryPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }

            moveIntoPlace(temporaryPath);
        } catch (IOException exception) {
            LazyCraft.LOGGER.error("Could not save LazyCraft config to {}", path, exception);
        }
    }

    private static LazyCraftConfig readConfig() {
        Path path = configPath();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            LazyCraftConfig loadedConfig = GSON.fromJson(reader, LazyCraftConfig.class);
            if (loadedConfig != null) {
                return loadedConfig;
            }
            LazyCraft.LOGGER.warn("LazyCraft config was empty; using defaults");
        } catch (IOException | JsonParseException exception) {
            LazyCraft.LOGGER.error("Could not load LazyCraft config from {}; using defaults", path, exception);
        }
        return new LazyCraftConfig();
    }

    private static void moveIntoPlace(Path temporaryPath) throws IOException {
        try {
            Files.move(
                    temporaryPath,
                    configPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, configPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path configPath() {
        if (configPath == null) {
            throw new IllegalStateException("LazyCraft configuration has not been initialized by a loader");
        }
        return configPath;
    }
}
