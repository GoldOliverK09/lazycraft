package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class RecipePlanner {
    private RecipePlanner() {
    }

    public static boolean canCraft(Item target) {
        return plan(target).isPresent();
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

        return captureSession(inventory, scorer)
                .flatMap(session -> session.plan(target, quantity));
    }

    /**
     * Captures every live Minecraft/config input needed by the planner.
     * The returned session performs only detached, immutable-data reads and may be searched
     * on a worker thread.
     */
    public static Optional<PlanningSession> createWorkerSession(
            InventorySnapshot inventory,
            LazyCraftConfig.ScoringMode scoringMode
    ) {
        Objects.requireNonNull(scoringMode, "scoringMode cannot be null");
        return captureSession(inventory, scoringMode.scorer());
    }

    private static Optional<PlanningSession> captureSession(
            InventorySnapshot inventory,
            PlanScorer scorer
    ) {
        Objects.requireNonNull(inventory, "inventory cannot be null");
        Objects.requireNonNull(scorer, "scorer cannot be null");

        if (Minecraft.getInstance().level == null) {
            return Optional.empty();
        }

        LazyCraftConfig config = config();
        return Optional.of(new PlanningSession(
                PlanningInventory.from(inventory),
                scorer,
                RecipeIndex.snapshot(),
                CraftingGrid.current().orElse(CraftingGrid.CRAFTING_TABLE),
                config.recursionDepth,
                config.maxCandidatesPerLayer
        ));
    }

    public static void logPlan(CraftPlan plan, long startNanos) {
        LazyCraft.LOGGER.info(
                "Craft plan for {} x{} ({} total ingredients) (took {} ms):",
                itemName(plan.target()),
                plan.quantity(),
                plan.totalIngredients(),
                (System.nanoTime() - startNanos) / 1_000_000.0
        );

        for (int index = 0; index < plan.steps().size(); index++) {
            CraftingStep step = plan.steps().get(index);
            LazyCraft.LOGGER.info(
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

    public static final class PlanningSession {
        private final PlanningInventory inventory;
        private final PlanScorer scorer;
        private final RecipeIndex.Snapshot recipeIndex;
        private final CraftingGrid craftingGrid;
        private final int maxSearchDepth;
        private final int maxCandidatesPerLayer;

        private PlanningSession(
                PlanningInventory inventory,
                PlanScorer scorer,
                RecipeIndex.Snapshot recipeIndex,
                CraftingGrid craftingGrid,
                int maxSearchDepth,
                int maxCandidatesPerLayer
        ) {
            this.inventory = inventory;
            this.scorer = scorer;
            this.recipeIndex = recipeIndex;
            this.craftingGrid = craftingGrid;
            this.maxSearchDepth = maxSearchDepth;
            this.maxCandidatesPerLayer = maxCandidatesPerLayer;
        }

        public long recipeIndexGeneration() {
            return recipeIndex.generation();
        }

        public Optional<CraftPlan> plan(Item target) {
            return plan(target, 1);
        }

        public Optional<CraftPlan> plan(Item target, int quantity) {
            return plan(target, quantity, () -> false);
        }

        public Optional<CraftPlan> plan(
                Item target,
                int quantity,
                BooleanSupplier cancellation
        ) {
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(cancellation, "cancellation cannot be null");
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }

            SearchContext search = new SearchContext(
                    target,
                    quantity,
                    scorer,
                    recipeIndex,
                    craftingGrid,
                    maxSearchDepth,
                    maxCandidatesPerLayer,
                    cancellation
            );
            search.ensureNotCancelled();
            return search.produce(
                            target,
                            quantity,
                            inventory.copy(),
                            Set.of(),
                            0,
                            false
                    )
                    .stream()
                    .min(search.comparator())
                    .map(search::toPlan);
        }
    }

    private record SearchContext(
            Item target,
            int targetQuantity,
            PlanScorer scorer,
            RecipeIndex.Snapshot recipeIndex,
            CraftingGrid craftingGrid,
            int maxSearchDepth,
            int maxCandidatesPerLayer,
            BooleanSupplier cancellation
    ) {

        private static long ingredientCost(List<List<Item>> requirements, int crafts) {
            return Math.multiplyExact((long) requirements.size(), crafts);
        }

        private List<SearchResult> produce(
                Item item,
                int quantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth,
                boolean mayUseExistingOutput
        ) {
            ensureNotCancelled();
            int existingOutputCount = mayUseExistingOutput ? inventory.getAmount(item) : 0;
            if (mayUseExistingOutput && existingOutputCount >= quantity) {
                return List.of(SearchResult.empty(inventory));
            }

            if (depth >= maxSearchDepth || path.contains(item)) {
                return List.of();
            }

            int missing = quantity - existingOutputCount;
            Set<Item> nextPath = new HashSet<>(path);
            nextPath.add(item);
            StableTopK<SearchResult> candidates = bestCandidates();

            for (RecipeIndex.ResolvedRecipe recipe : recipeIndex.recipesProducing(item)) {
                ensureNotCancelled();
                if (!recipe.supports(craftingGrid)) {
                    continue;
                }

                Optional<List<List<Item>>> optionalRequirements = recipe.requirements();
                if (optionalRequirements.isEmpty()) {
                    continue;
                }
                List<List<Item>> requirements = optionalRequirements.get();

                int outputCount = recipe.outputCount(item);
                if (outputCount == 0) {
                    continue;
                }

                int crafts = divideRoundUp(missing, outputCount);
                for (SearchResult satisfied : satisfyRequirements(
                        requirements,
                        crafts,
                        SearchResult.empty(inventory.copy()),
                        nextPath,
                        depth
                )) {
                    ensureNotCancelled();
                    PlanningInventory craftedInventory = satisfied.inventory().copy();
                    recipe.addOutputs(craftedInventory, crafts);
                    candidates.add(satisfied.addStep(
                            new CraftingStep(recipe.entry(), item, crafts),
                            ingredientCost(requirements, crafts),
                            craftedInventory
                    ));
                }
            }

            return finishCandidates(candidates);
        }

        private List<SearchResult> satisfyRequirements(
                List<List<Item>> requirements,
                int crafts,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            List<SearchResult> candidates = List.of(start);

            for (List<Item> acceptedItems : requirements) {
                ensureNotCancelled();
                StableTopK<SearchResult> nextCandidates = bestCandidates();
                for (SearchResult candidate : candidates) {
                    ensureNotCancelled();
                    for (SearchResult satisfied : satisfyIngredient(
                            acceptedItems,
                            crafts,
                            candidate,
                            path,
                            depth + 1
                    )) {
                        nextCandidates.add(satisfied);
                    }
                }

                candidates = finishCandidates(nextCandidates);
                if (candidates.isEmpty()) {
                    return List.of();
                }
            }

            return candidates;
        }

        private List<SearchResult> satisfyIngredient(
                List<Item> acceptedItems,
                int quantity,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            ensureNotCancelled();
            if (acceptedItems.isEmpty()) {
                return List.of();
            }

            PlanningInventory directlyConsumed = start.inventory().copy();
            if (directlyConsumed.consumeItems(acceptedItems, quantity)) {
                return List.of(start.withInventory(directlyConsumed));
            }

            int missing = quantity - start.inventory().availableItems(acceptedItems);
            StableTopK<SearchResult> candidates = bestCandidates();

            for (Item output : acceptedItems) {
                ensureNotCancelled();
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
                    ensureNotCancelled();
                    PlanningInventory consumedInventory = produced.inventory().copy();
                    if (consumedInventory.consumeItems(acceptedItems, quantity)) {
                        candidates.add(start.combine(produced, consumedInventory));
                    }
                }
            }

            return finishCandidates(candidates);
        }

        private StableTopK<SearchResult> bestCandidates() {
            return new StableTopK<>(maxCandidatesPerLayer, comparator());
        }

        private Comparator<SearchResult> comparator() {
            return Comparator.comparingLong(this::score)
                    .thenComparingLong(SearchResult::totalIngredients)
                    .thenComparingInt(result -> result.steps().size());
        }

        private List<SearchResult> finishCandidates(StableTopK<SearchResult> candidates) {
            ensureNotCancelled();
            return candidates.toList();
        }

        private CraftPlan toPlan(SearchResult result) {
            return new CraftPlan(target, targetQuantity, result.steps(), result.totalIngredients());
        }

        private static int divideRoundUp(int dividend, int divisor) {
            return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
        }

        private long score(SearchResult result) {
            ensureNotCancelled();
            return scorer.score(toPlan(result));
        }

        private void ensureNotCancelled() {
            if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
                throw new CancellationException();
            }
        }
    }

    private record SearchResult(
            PlanningInventory inventory,
            List<CraftingStep> steps,
            long totalIngredients
    ) {
        private SearchResult {
            steps = List.copyOf(steps);
        }

        private static SearchResult empty(PlanningInventory inventory) {
            return new SearchResult(inventory, List.of(), 0);
        }

        private SearchResult withInventory(PlanningInventory inventory) {
            return new SearchResult(inventory, steps, totalIngredients);
        }

        private SearchResult combine(SearchResult next, PlanningInventory inventory) {
            List<CraftingStep> combinedSteps = new ArrayList<>(steps.size() + next.steps.size());
            combinedSteps.addAll(steps);
            combinedSteps.addAll(next.steps);
            return new SearchResult(
                    inventory,
                    combinedSteps,
                    Math.addExact(totalIngredients, next.totalIngredients)
            );
        }

        private SearchResult addStep(
                CraftingStep step,
                long ingredientCost,
                PlanningInventory inventory
        ) {
            List<CraftingStep> nextSteps = new ArrayList<>(steps.size() + 1);
            nextSteps.addAll(steps);
            nextSteps.add(step);
            return new SearchResult(
                    inventory,
                    nextSteps,
                    Math.addExact(totalIngredients, ingredientCost)
            );
        }
    }
}
