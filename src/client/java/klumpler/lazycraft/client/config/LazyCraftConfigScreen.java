package klumpler.lazycraft.client.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class LazyCraftConfigScreen {
    private LazyCraftConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigHolder<LazyCraftConfig> holder = AutoConfig.getConfigHolder(LazyCraftConfig.class);
        LazyCraftConfig config = holder.getConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("text.autoconfig.lazycraft.title"))
                .setSavingRunnable(holder::save);
        ConfigCategory recipeBookCrafting = builder.getOrCreateCategory(
                Component.translatable("text.autoconfig.lazycraft.category.recipe_book_crafting"));
        ConfigCategory recursiveCrafting = builder.getOrCreateCategory(
                Component.translatable("text.autoconfig.lazycraft.category.recursive_crafting"));

        recipeBookCrafting.addEntry(builder.entryBuilder()
                .startBooleanToggle(
                        Component.translatable("text.autoconfig.lazycraft.option.recipeBookCrafting"),
                        config.recipeBookCrafting)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.recipeBookCrafting = value)
                .build());

        recipeBookCrafting.addEntry(builder.entryBuilder()
                .startBooleanToggle(
                        Component.translatable("text.autoconfig.lazycraft.option.recursiveRecipeBookCrafting"),
                        config.recursiveRecipeBookCrafting)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.recursiveRecipeBookCrafting = value)
                .build());

        recursiveCrafting.addEntry(builder.entryBuilder()
                .startIntSlider(
                        Component.translatable("text.autoconfig.lazycraft.option.recursionDepth"),
                        config.recursionDepth,
                        1,
                        32)
                .setDefaultValue(6)
                .setSaveConsumer(value -> config.recursionDepth = value)
                .build());

        recursiveCrafting.addEntry(builder.entryBuilder()
                .startEnumSelector(
                        Component.translatable("text.autoconfig.lazycraft.option.scoringMode"),
                        LazyCraftConfig.ScoringMode.class,
                        config.scoringMode)
                .setDefaultValue(LazyCraftConfig.ScoringMode.LEAST_TOTAL_INGREDIENTS)
                .setEnumNameProvider(mode -> Component.translatable(
                        "text.lazycraft.scoring_mode." + mode.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> config.scoringMode = value)
                .build());

        return builder.build();
    }
}
