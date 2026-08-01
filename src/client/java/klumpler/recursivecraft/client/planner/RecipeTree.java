package klumpler.recursivecraft.client.planner;

import net.minecraft.client.Minecraft;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record RecipeTree(Item item, List<RecipeOption> recipes, Stop stop) {
    private static final Logger LOGGER = LoggerFactory.getLogger("recursivecraft");
    // Kept at three because this was the current project setting before this split.
    private static final int DEFAULT_MAX_DEPTH = 3;

    public static RecipeTree build(Item target) {
        return build(target, DEFAULT_MAX_DEPTH);
    }

    public static RecipeTree build(Item target, int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth cannot be negative");
        }

        var level = Minecraft.getInstance().level;
        if (level == null) {
            return new RecipeTree(target, List.of(), Stop.NO_LEVEL);
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        return build(target, maxDepth, context, Set.of());
    }

    public static void log(Item target) {
        log(target, DEFAULT_MAX_DEPTH);
    }

    public static void log(Item target, int maxDepth) {
        log(build(target, maxDepth), 0);
    }

    private static RecipeTree build(
            Item item,
            int remainingDepth,
            ContextMap context,
            Set<Item> path
    ) {
        if (path.contains(item)) {
            return new RecipeTree(item, List.of(), Stop.CYCLE);
        }

        List<RecipeDisplayEntry> producers = RecipeIndex.recipesProducing(item);
        if (producers.isEmpty()) {
            return new RecipeTree(item, List.of(), Stop.RAW_MATERIAL);
        }

        List<RecipeDisplayEntry> craftingTableRecipes = producers.stream()
                .filter(recipe -> usesCraftingTable(recipe, context))
                .toList();
        if (craftingTableRecipes.isEmpty()) {
            return new RecipeTree(item, List.of(), Stop.UNAVAILABLE_AT_CRAFTING_TABLE);
        }

        if (remainingDepth == 0) {
            return new RecipeTree(item, List.of(), Stop.DEPTH_LIMIT);
        }

        Set<Item> nextPath = new HashSet<>(path);
        nextPath.add(item);
        List<RecipeOption> recipes = new ArrayList<>();

        for (RecipeDisplayEntry recipe : craftingTableRecipes) {
            Optional<List<Ingredient>> requirements = recipe.craftingRequirements();
            if (requirements.isEmpty()) {
                continue;
            }

            List<IngredientOption> ingredients = new ArrayList<>();
            for (Ingredient ingredient : requirements.get()) {
                List<RecipeTree> choices = new ArrayList<>();
                for (ItemStack acceptedStack : ingredient.display().resolveForStacks(context)) {
                    if (!acceptedStack.isEmpty()) {
                        choices.add(build(
                                acceptedStack.getItem(),
                                remainingDepth - 1,
                                context,
                                new HashSet<>(nextPath)
                        ));
                    }
                }

                ingredients.add(new IngredientOption(ingredient, List.copyOf(choices)));
            }

            recipes.add(new RecipeOption(recipe, List.copyOf(ingredients)));
        }

        Stop stop = recipes.isEmpty() ? Stop.NO_CRAFTING_REQUIREMENTS : Stop.NONE;
        return new RecipeTree(item, List.copyOf(recipes), stop);
    }

    private static boolean usesCraftingTable(RecipeDisplayEntry recipe, ContextMap context) {
        return recipe.display().craftingStation().resolveForStacks(context).stream()
                .anyMatch(stack -> stack.is(Items.CRAFTING_TABLE));
    }

    private static void log(RecipeTree node, int indentation) {
        LOGGER.info("{}{}{}", " ".repeat(indentation), itemName(node.item()), stopSuffix(node.stop()));

        for (RecipeOption recipe : node.recipes()) {
            for (IngredientOption ingredient : recipe.ingredients()) {
                for (RecipeTree choice : ingredient.choices()) {
                    log(choice, indentation + 1);
                }
            }
        }
    }

    private static String stopSuffix(Stop stop) {
        return switch (stop) {
            case NONE, RAW_MATERIAL -> "";
            case DEPTH_LIMIT -> " [depth limit]";
            case CYCLE -> " [cycle]";
            case NO_LEVEL -> " [no level]";
            case UNAVAILABLE_AT_CRAFTING_TABLE -> " [not craftable at crafting table]";
            case NO_CRAFTING_REQUIREMENTS -> " [no crafting requirements]";
        };
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public record RecipeOption(RecipeDisplayEntry entry, List<IngredientOption> ingredients) {
    }

    public record IngredientOption(Ingredient ingredient, List<RecipeTree> choices) {
    }

    public enum Stop {
        NONE,
        RAW_MATERIAL,
        DEPTH_LIMIT,
        CYCLE,
        NO_LEVEL,
        UNAVAILABLE_AT_CRAFTING_TABLE,
        NO_CRAFTING_REQUIREMENTS
    }
}
