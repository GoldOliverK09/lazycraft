package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.*;

public final class RecipeIndex {
    private static volatile Map<Item, List<RecipeDisplayEntry>> recipesByOutput = Map.of();
    private static volatile Snapshot resolvedSnapshot = Snapshot.empty();
    private static long nextGeneration;

    private RecipeIndex() {
    }

    public static void rebuildLookup(List<RecipeCollection> collections) {
        long startNanos = System.nanoTime();
        var level = Minecraft.getInstance().level;

        if (level == null) {
            recipesByOutput = Map.of();
            resolvedSnapshot = new Snapshot(++nextGeneration, Map.of());
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        Map<Item, List<RecipeDisplayEntry>> rebuiltIndex = new HashMap<>();
        Map<Item, List<ResolvedRecipe>> rebuiltResolvedIndex = new HashMap<>();
        int indexedResultCount = 0;

        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                List<ItemStack> resultItems = entry.resultItems(context);
                ResolvedRecipe resolvedRecipe = ResolvedRecipe.resolve(entry, resultItems, context);

                for (ItemStack output : resultItems) {
                    rebuiltIndex.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
                    if (!output.isEmpty()) {
                        rebuiltResolvedIndex
                                .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                                .add(resolvedRecipe);
                    }
                    indexedResultCount++;
                }
            }
        }

        recipesByOutput = rebuiltIndex;
        resolvedSnapshot = new Snapshot(++nextGeneration, freezeIndex(rebuiltResolvedIndex));
        LazyCraft.LOGGER.info(
                "Recipe lookup took {} ms for {} recipes ({} unique outputs)",
                (System.nanoTime() - startNanos) / 1_000_000.0,
                indexedResultCount,
                recipesByOutput.size()
        );
    }

    public static List<RecipeDisplayEntry> recipesProducing(Item item) {
        return recipesByOutput.getOrDefault(item, List.of());
    }

    static Snapshot snapshot() {
        return resolvedSnapshot;
    }

    public static long generation() {
        return resolvedSnapshot.generation;
    }

    private static <T> Map<Item, List<T>> freezeIndex(Map<Item, List<T>> index) {
        Map<Item, List<T>> frozen = new HashMap<>(index.size());
        index.forEach((item, recipes) -> frozen.put(item, List.copyOf(recipes)));
        return Map.copyOf(frozen);
    }

    private enum Layout {
        SHAPED,
        SHAPELESS,
        OTHER
    }

    static final class Snapshot {
        private final long generation;
        private final Map<Item, List<ResolvedRecipe>> recipesByOutput;

        private Snapshot(long generation, Map<Item, List<ResolvedRecipe>> recipesByOutput) {
            this.generation = generation;
            this.recipesByOutput = recipesByOutput;
        }

        private static Snapshot empty() {
            return new Snapshot(0, Map.of());
        }

        long generation() {
            return generation;
        }

        List<ResolvedRecipe> recipesProducing(Item item) {
            return recipesByOutput.getOrDefault(item, List.of());
        }
    }

    static final class ResolvedRecipe {
        private final RecipeDisplayEntry entry;
        private final Optional<List<List<Item>>> requirements;
        private final List<ResolvedOutput> outputs;
        private final boolean usesCraftingTable;
        private final Layout layout;
        private final int layoutWidth;
        private final int layoutHeight;

        private ResolvedRecipe(
                RecipeDisplayEntry entry,
                Optional<List<List<Item>>> requirements,
                List<ResolvedOutput> outputs,
                boolean usesCraftingTable,
                Layout layout,
                int layoutWidth,
                int layoutHeight
        ) {
            this.entry = entry;
            this.requirements = requirements;
            this.outputs = outputs;
            this.usesCraftingTable = usesCraftingTable;
            this.layout = layout;
            this.layoutWidth = layoutWidth;
            this.layoutHeight = layoutHeight;
        }

        private static ResolvedRecipe resolve(
                RecipeDisplayEntry entry,
                List<ItemStack> resultItems,
                ContextMap context
        ) {
            Optional<List<List<Item>>> requirements = entry.craftingRequirements()
                    .map(ingredients -> ingredients.stream()
                            .map(ingredient -> resolveIngredient(ingredient, context))
                            .toList());
            List<ResolvedOutput> outputs = resultItems.stream()
                    .filter(stack -> !stack.isEmpty())
                    .map(stack -> new ResolvedOutput(stack.getItem(), stack.getCount()))
                    .toList();
            boolean usesCraftingTable = entry.display().craftingStation()
                    .resolveForStacks(context)
                    .stream()
                    .anyMatch(stack -> stack.is(Items.CRAFTING_TABLE));

            Layout layout = Layout.OTHER;
            int layoutWidth = 0;
            int layoutHeight = 0;
            if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
                layout = Layout.SHAPED;
                layoutWidth = shaped.width();
                layoutHeight = shaped.height();
            } else if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
                layout = Layout.SHAPELESS;
                layoutWidth = shapeless.ingredients().size();
            }

            return new ResolvedRecipe(
                    entry,
                    requirements,
                    outputs,
                    usesCraftingTable,
                    layout,
                    layoutWidth,
                    layoutHeight
            );
        }

        private static List<Item> resolveIngredient(Ingredient ingredient, ContextMap context) {
            Set<Item> acceptedItems = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.display().resolveForStacks(context)) {
                if (!stack.isEmpty()) {
                    acceptedItems.add(stack.getItem());
                }
            }
            return List.copyOf(acceptedItems);
        }

        RecipeDisplayEntry entry() {
            return entry;
        }

        Optional<List<List<Item>>> requirements() {
            return requirements;
        }

        boolean supports(CraftingGrid craftingGrid) {
            if (craftingGrid.width() >= 3 && craftingGrid.height() >= 3) {
                return usesCraftingTable;
            }

            return switch (layout) {
                case SHAPED -> layoutWidth <= craftingGrid.width()
                        && layoutHeight <= craftingGrid.height();
                case SHAPELESS -> layoutWidth <= craftingGrid.width() * craftingGrid.height();
                case OTHER -> false;
            };
        }

        int outputCount(Item item) {
            return outputs.stream()
                    .filter(output -> output.item == item)
                    .mapToInt(ResolvedOutput::count)
                    .sum();
        }

        void addOutputs(PlanningInventory inventory, int crafts) {
            for (ResolvedOutput output : outputs) {
                inventory.add(
                        output.item,
                        Math.multiplyExact(output.count, crafts)
                );
            }
        }
    }

    private record ResolvedOutput(Item item, int count) {
    }
}
