package klumpler.lazycraft.client.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;

public final class LazyCraftConfigScreen {
    private static final String OPTION_KEY_PREFIX = "text.autoconfig.lazycraft.option.";
    private static final String SCORING_MODE_KEY_PREFIX = "text.lazycraft.scoring_mode.";

    private LazyCraftConfigScreen() {
    }

    public static Screen create(Screen parent) {
        LazyCraftConfig config = LazyCraftConfigManager.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("text.autoconfig.lazycraft.title"))
                .setSavingRunnable(LazyCraftConfigManager::save);
        ConfigCategory recipeBookCategory = builder.getOrCreateCategory(
                Component.translatable("text.autoconfig.lazycraft.category.recipe_book_crafting"));
        ConfigCategory recursiveCraftingCategory = builder.getOrCreateCategory(
                Component.translatable("text.autoconfig.lazycraft.category.recursive_crafting"));
        ConfigCategory executionCategory = builder.getOrCreateCategory(
                Component.translatable("text.autoconfig.lazycraft.category.execution"));

        recipeBookCategory.addEntry(builder.entryBuilder()
                .startBooleanToggle(
                        optionLabel("recipeBookCrafting"),
                        config.recipeBookCrafting)
                .setDefaultValue(true)
                .setTooltip(optionTooltip("recipeBookCrafting"))
                .setSaveConsumer(value -> config.recipeBookCrafting = value)
                .build());

        recipeBookCategory.addEntry(builder.entryBuilder()
                .startBooleanToggle(
                        optionLabel("recursiveRecipeBookCrafting"),
                        config.recursiveRecipeBookCrafting)
                .setDefaultValue(true)
                .setTooltip(optionTooltip("recursiveRecipeBookCrafting"))
                .setSaveConsumer(value -> config.recursiveRecipeBookCrafting = value)
                .build());

        recipeBookCategory.addEntry(builder.entryBuilder()
                .startBooleanToggle(
                        optionLabel("showRecursiveCraftability"),
                        config.showRecursiveCraftability)
                .setDefaultValue(true)
                .setTooltip(optionTooltip("showRecursiveCraftability"))
                .setSaveConsumer(value -> config.showRecursiveCraftability = value)
                .build());

        recipeBookCategory.addEntry(builder.entryBuilder()
                .startIntSlider(
                        optionLabel("backgroundRecipeCheckDelayTicks"),
                        config.backgroundRecipeCheckDelayTicks,
                        LazyCraftConfig.MIN_BACKGROUND_RECIPE_CHECK_DELAY_TICKS,
                        LazyCraftConfig.MAX_BACKGROUND_RECIPE_CHECK_DELAY_TICKS)
                .setDefaultValue(LazyCraftConfig.DEFAULT_BACKGROUND_RECIPE_CHECK_DELAY_TICKS)
                .setTooltip(optionTooltip("backgroundRecipeCheckDelayTicks"))
                .setSaveConsumer(value -> config.backgroundRecipeCheckDelayTicks = value)
                .build());

        recursiveCraftingCategory.addEntry(builder.entryBuilder()
                .startIntSlider(
                        optionLabel("recursionDepth"),
                        config.recursionDepth,
                        LazyCraftConfig.MIN_RECURSION_DEPTH,
                        LazyCraftConfig.MAX_RECURSION_DEPTH)
                .setDefaultValue(LazyCraftConfig.DEFAULT_RECURSION_DEPTH)
                .setTooltip(optionTooltip("recursionDepth"))
                .setSaveConsumer(value -> config.recursionDepth = value)
                .build());

        recursiveCraftingCategory.addEntry(builder.entryBuilder()
                .startIntSlider(
                        optionLabel("maxCandidatesPerLayer"),
                        config.maxCandidatesPerLayer,
                        LazyCraftConfig.MIN_CANDIDATES_PER_LAYER,
                        LazyCraftConfig.MAX_CANDIDATES_PER_LAYER)
                .setDefaultValue(LazyCraftConfig.DEFAULT_CANDIDATES_PER_LAYER)
                .setTooltip(optionTooltip("maxCandidatesPerLayer"))
                .setSaveConsumer(value -> config.maxCandidatesPerLayer = value)
                .build());

        recursiveCraftingCategory.addEntry(builder.entryBuilder()
                .startEnumSelector(
                        optionLabel("scoringMode"),
                        LazyCraftConfig.ScoringMode.class,
                        config.scoringMode)
                .setDefaultValue(LazyCraftConfig.ScoringMode.LEAST_TOTAL_INGREDIENTS)
                .setEnumNameProvider(LazyCraftConfigScreen::scoringModeLabel)
                .setTooltipSupplier(LazyCraftConfigScreen::scoringModeTooltip)
                .setSaveConsumer(value -> config.scoringMode = value)
                .build());

        executionCategory.addEntry(builder.entryBuilder()
                .startIntSlider(
                        optionLabel("serverUpdateTimeoutTicks"),
                        config.serverUpdateTimeoutTicks,
                        LazyCraftConfig.MIN_SERVER_UPDATE_TIMEOUT_TICKS,
                        LazyCraftConfig.MAX_SERVER_UPDATE_TIMEOUT_TICKS)
                .setDefaultValue(LazyCraftConfig.DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS)
                .setTooltip(optionTooltip("serverUpdateTimeoutTicks"))
                .setSaveConsumer(value -> config.serverUpdateTimeoutTicks = value)
                .build());

        executionCategory.addEntry(builder.entryBuilder()
                .startIntSlider(
                        optionLabel("stepDelayTicks"),
                        config.stepDelayTicks,
                        LazyCraftConfig.MIN_STEP_DELAY_TICKS,
                        LazyCraftConfig.MAX_STEP_DELAY_TICKS)
                .setDefaultValue(LazyCraftConfig.DEFAULT_STEP_DELAY_TICKS)
                .setTooltip(optionTooltip("stepDelayTicks"))
                .setSaveConsumer(value -> config.stepDelayTicks = value)
                .build());

        return builder.build();
    }

    private static Component optionLabel(String option) {
        return Component.translatable(OPTION_KEY_PREFIX + option);
    }

    private static Component optionTooltip(String option) {
        return Component.translatable(OPTION_KEY_PREFIX + option + ".@Tooltip");
    }

    private static Component scoringModeLabel(Enum<?> mode) {
        return Component.translatable(scoringModeKey(mode));
    }

    private static Optional<Component[]> scoringModeTooltip(
            LazyCraftConfig.ScoringMode mode
    ) {
        String key = scoringModeKey(mode);
        return Optional.of(new Component[]{
                Component.translatable(key + ".tooltip.primary"),
                Component.translatable(key + ".tooltip.tie_breakers")
        });
    }

    private static String scoringModeKey(Enum<?> mode) {
        return SCORING_MODE_KEY_PREFIX + mode.name().toLowerCase(Locale.ROOT);
    }
}
