package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.*;

public record RecipeTree(Item item, List<RecipeOption> recipes, Stop stop) {
    private static final int DEFAULT_MAX_DEPTH = 5;

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
        return build(
                target,
                maxDepth,
                context,
                CraftingGrid.current().orElse(CraftingGrid.CRAFTING_TABLE),
                Set.of()
        );
    }

    public static void log(Item target) {
        long startNanos = System.nanoTime();
        log(target, DEFAULT_MAX_DEPTH);
        LazyCraft.LOGGER.info("Tree build took {} ms", (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    public static void log(Item target, int maxDepth) {
        log(build(target, maxDepth), 0, 1);
    }

    private static RecipeTree build(
            Item item,
            int remainingDepth,
            ContextMap context,
            CraftingGrid craftingGrid,
            Set<Item> path
    ) {
        if (path.contains(item)) {
            return new RecipeTree(item, List.of(), Stop.CYCLE);
        }

        List<RecipeDisplayEntry> supportedRecipes = RecipeIndex.recipesProducing(item).stream()
                .filter(recipe -> craftingGrid.supports(recipe, context))
                .toList();
        if (supportedRecipes.isEmpty()) {
            return new RecipeTree(item, List.of(), Stop.RAW_MATERIAL);
        }

        if (remainingDepth == 0) {
            return new RecipeTree(item, List.of(), Stop.DEPTH_LIMIT);
        }

        Set<Item> nextPath = new HashSet<>(path);
        nextPath.add(item);
        List<RecipeOption> recipeOptions = new ArrayList<>();

        for (RecipeDisplayEntry recipe : supportedRecipes) {
            Optional<List<Ingredient>> optionalRequirements = recipe.craftingRequirements();
            if (optionalRequirements.isEmpty()) {
                continue;
            }
            List<Ingredient> requirements = optionalRequirements.get();

            List<IngredientOption> ingredientOptions = new ArrayList<>();
            for (Ingredient ingredient : requirements) {
                if (ingredient.display() instanceof SlotDisplay.TagSlotDisplay(TagKey<Item> tag)) {
                    ingredientOptions.add(new IngredientOption(
                            ingredient,
                            Optional.of(tag),
                            List.of()
                    ));
                    continue;
                }

                List<RecipeTree> choices = new ArrayList<>();
                for (ItemStack acceptedStack : ingredient.display().resolveForStacks(context)) {
                    if (!acceptedStack.isEmpty()) {
                        choices.add(build(
                                acceptedStack.getItem(),
                                remainingDepth - 1,
                                context,
                                craftingGrid,
                                nextPath
                        ));
                    }
                }

                ingredientOptions.add(new IngredientOption(ingredient, Optional.empty(), List.copyOf(choices)));
            }

            int outputCount = recipe.resultItems(context).stream()
                    .filter(output -> output.is(item))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (outputCount > 0) {
                recipeOptions.add(new RecipeOption(recipe, outputCount, List.copyOf(ingredientOptions)));
            }
        }

        Stop stop = recipeOptions.isEmpty() ? Stop.NO_CRAFTING_REQUIREMENTS : Stop.NONE;
        return new RecipeTree(item, List.copyOf(recipeOptions), stop);
    }

    private static void log(RecipeTree node, int indentation, long requiredCount) {
        logNode(node, indentation, requiredCount);
        logChildren(node, indentation, requiredCount);
    }

    private static void logChildren(RecipeTree node, int indentation, long requiredCount) {
        for (RecipeOption recipe : node.recipes()) {
            Map<Item, RecipeTree> choicesByItem = new LinkedHashMap<>();
            Map<Item, Long> quantityByItem = new LinkedHashMap<>();
            Map<TagKey<Item>, Long> quantityByTag = new LinkedHashMap<>();
            long craftsRequired = divideRoundUp(requiredCount, recipe.outputCount());

            for (IngredientOption ingredient : recipe.ingredients()) {
                if (ingredient.tag().isPresent()) {
                    quantityByTag.merge(ingredient.tag().get(), craftsRequired, Long::sum);
                    continue;
                }

                for (RecipeTree choice : ingredient.choices()) {
                    choicesByItem.putIfAbsent(choice.item(), choice);
                    quantityByItem.merge(choice.item(), craftsRequired, Long::sum);
                }
            }

            for (Map.Entry<Item, RecipeTree> entry : choicesByItem.entrySet()) {
                log(entry.getValue(), indentation + 1, quantityByItem.get(entry.getKey()));
            }

            for (Map.Entry<TagKey<Item>, Long> entry : quantityByTag.entrySet()) {
                logTag(entry.getKey(), indentation + 1, entry.getValue());
            }
        }
    }

    private static void logNode(RecipeTree node, int indentation, long quantity) {
        LazyCraft.LOGGER.info(
                "{}{}{}{}",
                "  ".repeat(indentation),
                itemName(node.item()),
                quantitySuffix(quantity),
                stopSuffix(node.stop())
        );
    }

    private static long divideRoundUp(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private static void logTag(TagKey<Item> tag, int indentation, long quantity) {
        LazyCraft.LOGGER.info("{}#{}{}", "  ".repeat(indentation), tag.location(), quantitySuffix(quantity));
    }

    private static String quantitySuffix(long quantity) {
        return quantity > 1 ? " x" + quantity : "";
    }

    private static String stopSuffix(Stop stop) {
        return switch (stop) {
            case NONE, RAW_MATERIAL -> "";
            case DEPTH_LIMIT -> " [depth limit]";
            case CYCLE -> " [cycle]";
            case NO_LEVEL -> " [no level]";
            case NO_CRAFTING_REQUIREMENTS -> " [no crafting requirements]";
        };
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public record RecipeOption(
            RecipeDisplayEntry entry,
            int outputCount,
            List<IngredientOption> ingredients
    ) {
    }

    public record IngredientOption(
            Ingredient ingredient,
            Optional<TagKey<Item>> tag,
            List<RecipeTree> choices
    ) {
    }

    public enum Stop {
        NONE,
        RAW_MATERIAL,
        DEPTH_LIMIT,
        CYCLE,
        NO_LEVEL,
        NO_CRAFTING_REQUIREMENTS
    }
}
