package klumpler.recursivecraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecipeIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("recursivecraft");
    private static volatile Map<Item, List<RecipeDisplayEntry>> recipesByOutput = Map.of();

    private RecipeIndex() {
    }

    public static void rebuildLookup(List<RecipeCollection> collections) {
        long start = System.nanoTime();
        var level = Minecraft.getInstance().level;

        if (level == null) {
            recipesByOutput = Map.of();
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        Map<Item, List<RecipeDisplayEntry>> next = new HashMap<>();
        int recipeCount = 0;

        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                for (ItemStack output : entry.resultItems(context)) {
                    next.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                    recipeCount++;
                }
            }
        }

        recipesByOutput = next;
        LOGGER.info(
                "Recipe lookup took {} ms for {} recipes ({} unique outputs)",
                (System.nanoTime() - start) / 1_000_000.0,
                recipeCount,
                recipesByOutput.size()
        );
    }

    public static List<RecipeDisplayEntry> recipesProducing(Item item) {
        return recipesByOutput.getOrDefault(item, List.of());
    }
}
