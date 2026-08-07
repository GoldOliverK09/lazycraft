package klumpler.lazycraft.client.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class LazyCraftConfigScreen {
    private static final String OPTION_KEY_PREFIX = "text.autoconfig.lazycraft.option.";

    private LazyCraftConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigHolder<LazyCraftConfig> holder = AutoConfig.getConfigHolder(LazyCraftConfig.class);
        LazyCraftConfig config = holder.getConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("text.autoconfig.lazycraft.title"))
                .setSavingRunnable(holder::save);
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
                .startIntField(
                        optionLabel("maxCandidatesPerLayer"),
                        config.maxCandidatesPerLayer)
                .setDefaultValue(LazyCraftConfig.DEFAULT_CANDIDATES_PER_LAYER)
                .setMin(LazyCraftConfig.MIN_CANDIDATES_PER_LAYER)
                .setMax(LazyCraftConfig.MAX_CANDIDATES_PER_LAYER)
                .setTooltip(optionTooltip("maxCandidatesPerLayer"))
                .setSaveConsumer(value -> config.maxCandidatesPerLayer = value)
                .build());

        recursiveCraftingCategory.addEntry(builder.entryBuilder()
                .startEnumSelector(
                        optionLabel("scoringMode"),
                        LazyCraftConfig.ScoringMode.class,
                        config.scoringMode)
                .setDefaultValue(LazyCraftConfig.ScoringMode.LEAST_TOTAL_INGREDIENTS)
                .setEnumNameProvider(mode -> Component.translatable(
                        "text.lazycraft.scoring_mode." + mode.name().toLowerCase(Locale.ROOT)))
                .setTooltip(optionTooltip("scoringMode"))
                .setSaveConsumer(value -> config.scoringMode = value)
                .build());

        executionCategory.addEntry(builder.entryBuilder()
                .startIntField(
                        optionLabel("serverUpdateTimeoutTicks"),
                        config.serverUpdateTimeoutTicks)
                .setDefaultValue(LazyCraftConfig.DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS)
                .setMin(LazyCraftConfig.MIN_SERVER_UPDATE_TIMEOUT_TICKS)
                .setMax(LazyCraftConfig.MAX_SERVER_UPDATE_TIMEOUT_TICKS)
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
}
