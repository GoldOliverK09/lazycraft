package klumpler.recursivecraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RecipeIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("recursivecraft");
    private static volatile Map<Item, List<RecipeDisplayEntry>> recipesByOutput = Map.of();

    public static void rebuildLookup(List<RecipeCollection> collections) {
        long start = System.nanoTime(); // So my debugging can look fancy
        var level = Minecraft.getInstance().level;

        if (level == null) {    // Is null when there is no world loaded yet
            recipesByOutput = Map.of();
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        Map<Item, List<RecipeDisplayEntry>> next = new HashMap<>(); // Temporary dictionary basically

        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                for (ItemStack output : entry.resultItems(context)) {
                    // Get the list of recipes that make this item.
                    // If no list exists yet, create an empty one.
                    // Then add this recipe to the list.
                    // Added for sanity when I come back to this after I have forgotten Java
                    next.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                }
            }
        }

        recipesByOutput = next;
        LOGGER.info("Recipe lookup took {} ms", (System.nanoTime() - start) / 1_000_000.0);
        LOGGER.info(firstRecipeBranch(Items.NETHERITE_SCRAP));
    }

    /**
     * Returns all known recipes that produce the specified item
     * @param item Target item
     * @return List of recipes to craft the target item
     */
    public static List<RecipeDisplayEntry> recipesProducing(Item item) {
        return recipesByOutput.getOrDefault(item, List.of());
    }

    /**
     * Follows one deliberately simple crafting branch: the first known recipe for
     * an item and the first valid item for that recipe's first ingredient.
     *
     * For example: sticks = planks > oak_log
     */
    public static String firstRecipeBranch(Item target) {
        List<String> branch = new ArrayList<>();
        Set<Item> visited = new HashSet<>();
        Item current = target;
        boolean foundCycle = false;

        while (true) {
            if (!visited.add(current)) {
                foundCycle = true;
                break;
            }

            List<RecipeDisplayEntry> producers = recipesProducing(current);

            if (producers.isEmpty()) {
                break;
            }

            Optional<List<Ingredient>> requirements = producers.getFirst().craftingRequirements();

            if (requirements.isEmpty() || requirements.get().isEmpty()) {
                break;
            }

            Optional<Holder<Item>> firstAcceptedItem = requirements.get().getFirst().items().findFirst();

            if (firstAcceptedItem.isEmpty()) {
                break;
            }

            current = firstAcceptedItem.get().value();
            branch.add(itemName(current));
        }

        if (foundCycle) {
            branch.add("cycle: " + itemName(current));
        }

        if (branch.isEmpty()) {
            return itemName(target) + " = raw material";
        }

        return itemName(target) + " = " + String.join(" > ", branch);
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
