package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.*;

import java.util.*;

public final class RecipeIndex {
    private static volatile Snapshot resolvedSnapshot = Snapshot.empty();
    private static long nextGeneration;

    private RecipeIndex() {
    }

    public static void rebuildLookup(List<RecipeCollection> collections) {
        long startNanos = System.nanoTime();
        var level = Minecraft.getInstance().level;

        if (level == null) {
            resolvedSnapshot = new Snapshot(++nextGeneration, Map.of(), Map.of(), List.of());
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        Map<Item, List<ResolvedRecipe>> rebuiltResolvedIndex = new HashMap<>();
        Map<RecipeDisplayId, Item> rebuiltPrimaryOutputs = new HashMap<>();
        Set<Item> rebuiltOutputOrder = new LinkedHashSet<>();
        int indexedResultCount = 0;

        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                List<ItemStack> resultItems = entry.resultItems(context);
                ResolvedRecipe resolvedRecipe = ResolvedRecipe.resolve(entry, resultItems, context);

                for (ItemStack output : resultItems) {
                    if (output.isEmpty()) {
                        continue;
                    }

                    if (rebuiltPrimaryOutputs.putIfAbsent(entry.id(), output.getItem()) == null) {
                        rebuiltOutputOrder.add(output.getItem());
                    }
                    rebuiltResolvedIndex
                            .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                            .add(resolvedRecipe);
                    indexedResultCount++;
                }
            }
        }

        resolvedSnapshot = new Snapshot(
                ++nextGeneration,
                freezeIndex(rebuiltResolvedIndex),
                Collections.unmodifiableMap(rebuiltPrimaryOutputs),
                List.copyOf(rebuiltOutputOrder)
        );
        LazyCraft.LOGGER.info(
                "Recipe lookup took {} ms for {} recipes ({} unique outputs)",
                (System.nanoTime() - startNanos) / 1_000_000.0,
                indexedResultCount,
                rebuiltResolvedIndex.size()
        );
    }

    static Snapshot snapshot() {
        return resolvedSnapshot;
    }

    public static long generation() {
        return resolvedSnapshot.generation;
    }

    public static Item primaryOutputOrNull(RecipeDisplayId recipe) {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        return resolvedSnapshot.primaryOutputs.get(recipe);
    }

    public static List<Item> primaryOutputs() {
        return resolvedSnapshot.primaryOutputItems;
    }

    private static <T> Map<Item, List<T>> freezeIndex(Map<Item, List<T>> index) {
        index.replaceAll((item, recipes) -> List.copyOf(recipes));
        return Collections.unmodifiableMap(index);
    }

    private enum Layout {
        SHAPED,
        SHAPELESS,
        OTHER
    }

    static final class Snapshot {
        private final long generation;
        private final Map<Item, List<ResolvedRecipe>> recipesByOutput;
        private final Map<RecipeDisplayId, Item> primaryOutputs;
        private final List<Item> primaryOutputItems;

        private Snapshot(
                long generation,
                Map<Item, List<ResolvedRecipe>> recipesByOutput,
                Map<RecipeDisplayId, Item> primaryOutputs,
                List<Item> primaryOutputItems
        ) {
            this.generation = generation;
            this.recipesByOutput = recipesByOutput;
            this.primaryOutputs = primaryOutputs;
            this.primaryOutputItems = primaryOutputItems;
        }

        private static Snapshot empty() {
            return new Snapshot(0, Map.of(), Map.of(), List.of());
        }

        long generation() {
            return generation;
        }

        List<ResolvedRecipe> recipesProducing(Item item) {
            return recipesByOutput.getOrDefault(item, List.of());
        }

        boolean hasRecipes(Item item) {
            return recipesByOutput.containsKey(item);
        }
    }

    static final class ResolvedRecipe {
        private final RecipeDisplayEntry entry;
        private final Optional<List<Set<Item>>> requirements;
        private final List<ResolvedOutput> outputs;
        private final boolean usesCraftingTable;
        private final Layout layout;
        private final int layoutWidth;
        private final int layoutHeight;

        private ResolvedRecipe(
                RecipeDisplayEntry entry,
                Optional<List<Set<Item>>> requirements,
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
            Optional<List<Set<Item>>> requirements = resolveRequirements(entry, context);
            List<ResolvedOutput> outputs = new ArrayList<>(resultItems.size());
            for (ItemStack stack : resultItems) {
                if (!stack.isEmpty()) {
                    outputs.add(new ResolvedOutput(stack.getItem(), stack.getCount()));
                }
            }

            boolean usesCraftingTable = false;
            for (ItemStack station : entry.display().craftingStation().resolveForStacks(context)) {
                if (station.is(Items.CRAFTING_TABLE)) {
                    usesCraftingTable = true;
                    break;
                }
            }

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
                    List.copyOf(outputs),
                    usesCraftingTable,
                    layout,
                    layoutWidth,
                    layoutHeight
            );
        }

        private static Optional<List<Set<Item>>> resolveRequirements(
                RecipeDisplayEntry entry,
                ContextMap context
        ) {
            Optional<List<Ingredient>> ingredients = entry.craftingRequirements();
            if (ingredients.isEmpty()) {
                return Optional.empty();
            }

            List<Ingredient> recipeIngredients = ingredients.get();
            List<Set<Item>> requirements = new ArrayList<>(recipeIngredients.size());
            for (Ingredient ingredient : recipeIngredients) {
                requirements.add(resolveIngredient(ingredient, context));
            }
            return Optional.of(List.copyOf(requirements));
        }

        private static Set<Item> resolveIngredient(
                Ingredient ingredient,
                ContextMap context
        ) {
            Set<Item> acceptedItems = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.display().resolveForStacks(context)) {
                Item item = stack.getItem();
                if (item != Items.AIR) {
                    acceptedItems.add(item);
                }
            }
            return Collections.unmodifiableSet(acceptedItems);
        }

        RecipeDisplayEntry entry() {
            return entry;
        }

        Optional<List<Set<Item>>> requirements() {
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
            int count = 0;
            for (ResolvedOutput output : outputs) {
                if (output.item == item) {
                    count += output.count;
                }
            }
            return count;
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
