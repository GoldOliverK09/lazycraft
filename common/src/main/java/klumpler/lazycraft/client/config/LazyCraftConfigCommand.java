package klumpler.lazycraft.client.config;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class LazyCraftConfigCommand {
    private static final List<String> SETTINGS = List.of(
            "recipeBookCrafting",
            "recursiveRecipeBookCrafting",
            "showRecursiveCraftability",
            "backgroundRecipeCheckDelayTicks",
            "recursionDepth",
            "maxCandidatesPerLayer",
            "scoringMode",
            "massCraftingPolicy",
            "serverUpdateTimeoutTicks",
            "stepDelayTicks"
    );

    private LazyCraftConfigCommand() {
    }

    public static Result query(String setting) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        String value = switch (setting) {
            case "recipeBookCrafting" -> Boolean.toString(config.recipeBookCrafting);
            case "recursiveRecipeBookCrafting" -> Boolean.toString(config.recursiveRecipeBookCrafting);
            case "showRecursiveCraftability" -> Boolean.toString(config.showRecursiveCraftability);
            case "backgroundRecipeCheckDelayTicks" -> Integer.toString(config.backgroundRecipeCheckDelayTicks);
            case "recursionDepth" -> Integer.toString(config.recursionDepth);
            case "maxCandidatesPerLayer" -> Integer.toString(config.maxCandidatesPerLayer);
            case "scoringMode" -> config.scoringMode.name();
            case "massCraftingPolicy" -> config.massCraftingPolicy.name();
            case "serverUpdateTimeoutTicks" -> Integer.toString(config.serverUpdateTimeoutTicks);
            case "stepDelayTicks" -> Integer.toString(config.stepDelayTicks);
            default -> null;
        };
        if (value == null) {
            return unknownSetting(setting);
        }
        return success("current", setting, value);
    }

    public static Result set(String setting, String value) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        Result result = switch (setting) {
            case "recipeBookCrafting" -> setBoolean(
                    setting, value, newValue -> config.recipeBookCrafting = newValue);
            case "recursiveRecipeBookCrafting" -> setBoolean(
                    setting, value, newValue -> config.recursiveRecipeBookCrafting = newValue);
            case "showRecursiveCraftability" -> setBoolean(
                    setting, value, newValue -> config.showRecursiveCraftability = newValue);
            case "backgroundRecipeCheckDelayTicks" -> setInteger(
                    setting, value,
                    LazyCraftConfig.MIN_BACKGROUND_RECIPE_CHECK_DELAY_TICKS,
                    LazyCraftConfig.MAX_BACKGROUND_RECIPE_CHECK_DELAY_TICKS,
                    newValue -> config.backgroundRecipeCheckDelayTicks = newValue);
            case "recursionDepth" -> setInteger(
                    setting, value,
                    LazyCraftConfig.MIN_RECURSION_DEPTH,
                    LazyCraftConfig.MAX_RECURSION_DEPTH,
                    newValue -> config.recursionDepth = newValue);
            case "maxCandidatesPerLayer" -> setInteger(
                    setting, value,
                    LazyCraftConfig.MIN_CANDIDATES_PER_LAYER,
                    LazyCraftConfig.MAX_CANDIDATES_PER_LAYER,
                    newValue -> config.maxCandidatesPerLayer = newValue);
            case "scoringMode" -> setEnum(
                    setting, value, LazyCraftConfig.ScoringMode.values(),
                    newValue -> config.scoringMode = newValue);
            case "massCraftingPolicy" -> setEnum(
                    setting, value, LazyCraftConfig.MassCraftingPolicy.values(),
                    newValue -> config.massCraftingPolicy = newValue);
            case "serverUpdateTimeoutTicks" -> setInteger(
                    setting, value,
                    LazyCraftConfig.MIN_SERVER_UPDATE_TIMEOUT_TICKS,
                    LazyCraftConfig.MAX_SERVER_UPDATE_TIMEOUT_TICKS,
                    newValue -> config.serverUpdateTimeoutTicks = newValue);
            case "stepDelayTicks" -> setInteger(
                    setting, value,
                    LazyCraftConfig.MIN_STEP_DELAY_TICKS,
                    LazyCraftConfig.MAX_STEP_DELAY_TICKS,
                    newValue -> config.stepDelayTicks = newValue);
            default -> unknownSetting(setting);
        };

        if (result.success()) {
            LazyCraftConfigManager.save();
            VisibleRecipeCraftability.configurationChanged();
        }
        return result;
    }

    public static CompletableFuture<Suggestions> suggestSettings(SuggestionsBuilder builder) {
        SETTINGS.forEach(builder::suggest);
        return builder.buildFuture();
    }

    public static CompletableFuture<Suggestions> suggestValues(String setting, SuggestionsBuilder builder) {
        switch (setting) {
            case "recipeBookCrafting", "recursiveRecipeBookCrafting", "showRecursiveCraftability" -> {
                builder.suggest("true");
                builder.suggest("false");
            }
            case "scoringMode" -> Arrays.stream(LazyCraftConfig.ScoringMode.values())
                    .map(Enum::name)
                    .forEach(builder::suggest);
            case "massCraftingPolicy" -> Arrays.stream(LazyCraftConfig.MassCraftingPolicy.values())
                    .map(Enum::name)
                    .forEach(builder::suggest);
            case "backgroundRecipeCheckDelayTicks" -> suggestBounds(
                    builder,
                    LazyCraftConfig.MIN_BACKGROUND_RECIPE_CHECK_DELAY_TICKS,
                    LazyCraftConfig.MAX_BACKGROUND_RECIPE_CHECK_DELAY_TICKS);
            case "recursionDepth" -> suggestBounds(
                    builder,
                    LazyCraftConfig.MIN_RECURSION_DEPTH,
                    LazyCraftConfig.MAX_RECURSION_DEPTH);
            case "maxCandidatesPerLayer" -> suggestBounds(
                    builder,
                    LazyCraftConfig.MIN_CANDIDATES_PER_LAYER,
                    LazyCraftConfig.MAX_CANDIDATES_PER_LAYER);
            case "serverUpdateTimeoutTicks" -> suggestBounds(
                    builder,
                    LazyCraftConfig.MIN_SERVER_UPDATE_TIMEOUT_TICKS,
                    LazyCraftConfig.MAX_SERVER_UPDATE_TIMEOUT_TICKS);
            case "stepDelayTicks" -> suggestBounds(
                    builder,
                    LazyCraftConfig.MIN_STEP_DELAY_TICKS,
                    LazyCraftConfig.MAX_STEP_DELAY_TICKS);
            default -> {
            }
        }
        return builder.buildFuture();
    }

    private static void suggestBounds(SuggestionsBuilder builder, int minimum, int maximum) {
        builder.suggest(minimum);
        builder.suggest(maximum);
    }

    private static Result setBoolean(String setting, String value, Consumer<Boolean> setter) {
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            return failure("invalid_boolean", setting);
        }
        setter.accept(Boolean.parseBoolean(value));
        return updated(setting, value.toLowerCase(Locale.ROOT));
    }

    private static Result setInteger(
            String setting,
            String value,
            int minimum,
            int maximum,
            IntConsumer setter
    ) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return failure("invalid_integer", setting);
        }
        if (parsed < minimum || parsed > maximum) {
            return new Result(false, Component.translatable(
                    "commands.lazycraft.config.out_of_range",
                    setting,
                    minimum,
                    maximum
            ));
        }
        setter.accept(parsed);
        return updated(setting, Integer.toString(parsed));
    }

    private static <E extends Enum<E>> Result setEnum(
            String setting,
            String value,
            E[] choices,
            Consumer<E> setter
    ) {
        String normalized = value.replace('-', '_').toUpperCase(Locale.ROOT);
        for (E choice : choices) {
            if (choice.name().equals(normalized)) {
                setter.accept(choice);
                return updated(setting, choice.name());
            }
        }
        String allowedValues = Arrays.stream(choices)
                .map(Enum::name)
                .reduce((first, next) -> first + ", " + next)
                .orElse("");
        return failure("invalid_choice", setting, allowedValues);
    }

    private static Result unknownSetting(String setting) {
        return failure("unknown_setting", setting, String.join(", ", SETTINGS));
    }

    private static Result updated(String setting, String value) {
        return success("updated", setting, value);
    }

    private static Result success(String key, String setting, String value) {
        return new Result(true, Component.translatable(
                "commands.lazycraft.config." + key,
                setting,
                value
        ));
    }

    private static Result failure(String key, Object... arguments) {
        return new Result(false, Component.translatable(
                "commands.lazycraft.config." + key,
                arguments
        ));
    }

    public record Result(boolean success, Component message) {
    }
}
