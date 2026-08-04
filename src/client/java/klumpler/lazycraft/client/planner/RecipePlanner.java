package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import me.shedaniel.autoconfig.AutoConfig;
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

import java.util.*;

public final class RecipePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("lazycraft");
    private static final Item DEFAULT_STATION = Items.CRAFTING_TABLE;
    private static final int MAX_CANDIDATES_PER_LAYER = 64;

    private RecipePlanner() {
    }

    public static Optional<CraftPlan> plan(Item target) {
        return plan(target, 1, config().scoringMode.scorer());
    }

    public static Optional<CraftPlan> plan(Item target, int quantity) {
        return plan(target, quantity, config().scoringMode.scorer());
    }

    public static Optional<CraftPlan> plan(Item target, int quantity, PlanScorer scorer) {
        return InventorySnapshot.fromCurrentPlayer()
                .flatMap(inventory -> plan(target, quantity, inventory, scorer));
    }

    public static Optional<CraftPlan> plan(
            Item target,
            int quantity,
            InventorySnapshot inventory,
            PlanScorer scorer
    ) {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(inventory, "inventory cannot be null");
        Objects.requireNonNull(scorer, "scorer cannot be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        var level = Minecraft.getInstance().level;
        if (level == null) {
            return Optional.empty();
        }

        SearchContext search = new SearchContext(
                target,
                quantity,
                scorer,
                SlotDisplayContext.fromLevel(level),
                config().recursionDepth
        );
        return search.produce(target, quantity, inventory.copy(), Set.of(), 0, false).stream()
                .min(search.comparator())
                .map(search::toPlan);
    }

    public static void logPlan(CraftPlan plan, long time) {
        LOGGER.info(
                "Craft plan for {} x{} ({} total ingredients) (took {} ms):",
                itemName(plan.target()),
                plan.quantity(),
                plan.totalIngredients(),
                (System.nanoTime() - time) / 1_000_000.0
        );

        for (int index = 0; index < plan.steps().size(); index++) {
            CraftingStep step = plan.steps().get(index);
            LOGGER.info(
                    "  {}. Craft {} ({} recipe executions)",
                    index + 1,
                    itemName(step.output()),
                    step.crafts()
            );
        }
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static LazyCraftConfig config() {
        return AutoConfig.getConfigHolder(LazyCraftConfig.class).getConfig();
    }

    private record SearchContext(
            Item target,
            int targetQuantity,
            PlanScorer scorer,
            ContextMap context,
            int maxSearchDepth
    ) {

        private List<SearchResult> produce(
                Item item,
                int quantity,
                InventorySnapshot inventory,
                Set<Item> path,
                int depth,
                boolean mayUseExistingOutput
        ) {
            if (mayUseExistingOutput && inventory.has(item, quantity)) {
                return List.of(SearchResult.empty(inventory));
            }

            if (depth >= maxSearchDepth || path.contains(item)) {
                return List.of();
            }

            int missing = mayUseExistingOutput ? quantity - inventory.getAmount(item) : quantity;
            Set<Item> nextPath = new HashSet<>(path);
            nextPath.add(item);
            List<SearchResult> candidates = new ArrayList<>();

            for (RecipeDisplayEntry recipe : RecipeIndex.recipesProducing(item)) {
                if (!usesStation(recipe, DEFAULT_STATION)) {
                    continue;
                }

                Optional<List<Ingredient>> requirements = recipe.craftingRequirements();
                if (requirements.isEmpty()) {
                    continue;
                }

                int outputCount = outputCount(recipe, item);
                if (outputCount == 0) {
                    continue;
                }

                int crafts = divideRoundUp(missing, outputCount);
                for (SearchResult satisfied : satisfyRequirements(
                        requirements.get(),
                        crafts,
                        SearchResult.empty(inventory.copy()),
                        nextPath,
                        depth
                )) {
                    InventorySnapshot craftedInventory = satisfied.inventory().copy();
                    addOutputs(craftedInventory, recipe, crafts);
                    candidates.add(satisfied.addStep(
                            new CraftingStep(recipe, item, crafts),
                            ingredientCost(requirements.get(), crafts),
                            craftedInventory
                    ));
                }
            }

            return retainBest(candidates);
        }

        private List<SearchResult> satisfyRequirements(
                List<Ingredient> requirements,
                int crafts,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            List<SearchResult> candidates = List.of(start);

            for (Ingredient ingredient : requirements) {
                List<SearchResult> nextCandidates = new ArrayList<>();
                for (SearchResult candidate : candidates) {
                    nextCandidates.addAll(satisfyIngredient(
                            ingredient,
                            crafts,
                            candidate,
                            path,
                            depth + 1
                    ));
                }

                candidates = retainBest(nextCandidates);
                if (candidates.isEmpty()) {
                    return List.of();
                }
            }

            return candidates;
        }

        private List<SearchResult> satisfyIngredient(
                Ingredient ingredient,
                int quantity,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            InventorySnapshot directlyConsumed = start.inventory().copy();
            if (directlyConsumed.consume(ingredient, quantity)) {
                return List.of(start.withInventory(directlyConsumed));
            }

            int missing = quantity - start.inventory().available(ingredient);
            List<SearchResult> candidates = new ArrayList<>();

            for (Item output : matchingItems(ingredient)) {
                int requiredOutputAmount = Math.addExact(
                        start.inventory().getAmount(output),
                        missing
                );

                for (SearchResult produced : produce(
                        output,
                        requiredOutputAmount,
                        start.inventory().copy(),
                        path,
                        depth,
                        true
                )) {
                    InventorySnapshot consumedInventory = produced.inventory().copy();
                    if (consumedInventory.consume(ingredient, quantity)) {
                        candidates.add(produced.withInventory(consumedInventory));
                    }
                }
            }

            return retainBest(candidates);
        }

        private Set<Item> matchingItems(Ingredient ingredient) {
            Set<Item> items = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.display().resolveForStacks(context)) {
                if (!stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
            return items;
        }

        private boolean usesStation(RecipeDisplayEntry recipe, Item station) {
            return recipe.display().craftingStation().resolveForStacks(context).stream()
                    .anyMatch(stack -> stack.is(station));
        }

        private int outputCount(RecipeDisplayEntry recipe, Item item) {
            return recipe.resultItems(context).stream()
                    .filter(output -> output.is(item))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }

        private void addOutputs(InventorySnapshot inventory, RecipeDisplayEntry recipe, int crafts) {
            for (ItemStack output : recipe.resultItems(context)) {
                if (output.isEmpty()) {
                    continue;
                }

                ItemStack craftedOutput = output.copy();
                craftedOutput.setCount(Math.multiplyExact(craftedOutput.getCount(), crafts));
                inventory.add(craftedOutput);
            }
        }

        private List<SearchResult> retainBest(List<SearchResult> candidates) {
            if (candidates.isEmpty()) {
                return List.of();
            }

            candidates.sort(comparator());
            int end = Math.min(candidates.size(), MAX_CANDIDATES_PER_LAYER);
            return List.copyOf(candidates.subList(0, end));
        }

        private Comparator<SearchResult> comparator() {
            return Comparator.comparingLong(this::score)
                    .thenComparingLong(SearchResult::totalIngredients)
                    .thenComparingInt(result -> result.steps().size());
        }

        private long score(SearchResult result) {
            return scorer.score(toPlan(result));
        }

        private CraftPlan toPlan(SearchResult result) {
            return new CraftPlan(target, targetQuantity, result.steps(), result.totalIngredients());
        }

        private static int divideRoundUp(int dividend, int divisor) {
            return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
        }

        private static long ingredientCost(List<Ingredient> requirements, int crafts) {
            return Math.multiplyExact((long) requirements.size(), crafts);
        }
    }

    private record SearchResult(
            InventorySnapshot inventory,
            List<CraftingStep> steps,
            long totalIngredients
    ) {
        private SearchResult {
            steps = List.copyOf(steps);
        }

        private static SearchResult empty(InventorySnapshot inventory) {
            return new SearchResult(inventory, List.of(), 0);
        }

        private SearchResult withInventory(InventorySnapshot inventory) {
            return new SearchResult(inventory, steps, totalIngredients);
        }

        private SearchResult addStep(
                CraftingStep step,
                long ingredientCost,
                InventorySnapshot inventory
        ) {
            List<CraftingStep> nextSteps = new ArrayList<>(steps);
            nextSteps.add(step);
            return new SearchResult(
                    inventory,
                    nextSteps,
                    Math.addExact(totalIngredients, ingredientCost)
            );
        }
    }
}
