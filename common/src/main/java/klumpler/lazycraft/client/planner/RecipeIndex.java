package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

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

        Map<Item, List<ResolvedRecipe>> rebuiltResolvedIndex = new HashMap<>();
        Map<RecipeHolder<?>, Item> rebuiltPrimaryOutputs = new HashMap<>();
        Set<Item> rebuiltOutputOrder = new LinkedHashSet<>();
        int indexedResultCount = 0;

        for (RecipeCollection collection : collections) {
            for (RecipeHolder<?> entry : collection.getRecipes()) {
                if (!(entry.value() instanceof CraftingRecipe)) {
                    continue;
                }

                ItemStack output = entry.value().getResultItem(collection.registryAccess());
                if (output.isEmpty()) {
                    continue;
                }

                ResolvedRecipe resolvedRecipe = ResolvedRecipe.resolve(entry, output);
                if (rebuiltPrimaryOutputs.putIfAbsent(entry, output.getItem()) == null) {
                    rebuiltOutputOrder.add(output.getItem());
                }
                rebuiltResolvedIndex
                        .computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                        .add(resolvedRecipe);
                indexedResultCount++;
            }
        }

        resolvedSnapshot = new Snapshot(
                ++nextGeneration,
                freezeIndex(rebuiltResolvedIndex),
                Collections.unmodifiableMap(rebuiltPrimaryOutputs),
                List.copyOf(rebuiltOutputOrder)
        );
        VisibleRecipeCraftability.recipeIndexChanged();
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

    public static Item primaryOutputOrNull(RecipeHolder<?> recipe) {
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

    static final class Snapshot {
        private final long generation;
        private final Map<Item, List<ResolvedRecipe>> recipesByOutput;
        private final Map<RecipeHolder<?>, Item> primaryOutputs;
        private final List<Item> primaryOutputItems;

        private Snapshot(
                long generation,
                Map<Item, List<ResolvedRecipe>> recipesByOutput,
                Map<RecipeHolder<?>, Item> primaryOutputs,
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
        private final RecipeHolder<?> entry;
        private final Optional<List<Set<Item>>> requirements;
        private final List<ResolvedOutput> outputs;

        private ResolvedRecipe(
                RecipeHolder<?> entry,
                Optional<List<Set<Item>>> requirements,
                List<ResolvedOutput> outputs
        ) {
            this.entry = entry;
            this.requirements = requirements;
            this.outputs = outputs;
        }

        private static ResolvedRecipe resolve(
                RecipeHolder<?> entry,
                ItemStack result
        ) {
            Optional<List<Set<Item>>> requirements = resolveRequirements(entry);
            return new ResolvedRecipe(
                    entry,
                    requirements,
                    List.of(new ResolvedOutput(result.getItem(), result.getCount()))
            );
        }

        private static Optional<List<Set<Item>>> resolveRequirements(RecipeHolder<?> entry) {
            List<Ingredient> ingredients = entry.value().getIngredients();
            if (ingredients.isEmpty()) {
                return Optional.empty();
            }

            List<Set<Item>> requirements = new ArrayList<>(ingredients.size());
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                requirements.add(resolveIngredient(ingredient));
            }
            return requirements.isEmpty()
                    ? Optional.empty()
                    : Optional.of(List.copyOf(requirements));
        }

        private static Set<Item> resolveIngredient(Ingredient ingredient) {
            Set<Item> acceptedItems = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.getItems()) {
                Item item = stack.getItem();
                if (item != Items.AIR) {
                    acceptedItems.add(item);
                }
            }
            return Collections.unmodifiableSet(acceptedItems);
        }

        RecipeHolder<?> entry() {
            return entry;
        }

        Optional<List<Set<Item>>> requirements() {
            return requirements;
        }

        boolean supports(CraftingGrid craftingGrid) {
            return entry.value().canCraftInDimensions(craftingGrid.width(), craftingGrid.height());
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
