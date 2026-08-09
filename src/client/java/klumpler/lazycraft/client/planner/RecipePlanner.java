package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class RecipePlanner {
    private RecipePlanner() {
    }

    public static Optional<CraftPlan> plan(Item target) {
        Objects.requireNonNull(target, "target cannot be null");
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return Optional.empty();
        }

        LazyCraftConfig config = LazyCraftConfigManager.get();
        CraftingGrid craftingGrid = CraftingGrid.current().orElse(CraftingGrid.CRAFTING_TABLE);
        Settings settings = new Settings(
                craftingGrid,
                config.recursionDepth,
                config.maxCandidatesPerLayer,
                config.scoringMode
        );
        return captureSession(PlanningInventory.from(player), settings)
                .flatMap(session -> session.plan(target, 1, () -> false));
    }

    /**
     * Captures every live Minecraft/config input needed by the planner.
     * The returned session performs only detached, immutable-data reads and may be searched
     * on a worker thread.
     */
    public static Optional<PlanningSession> createWorkerSession(
            Player player,
            Settings settings
    ) {
        Objects.requireNonNull(player, "player cannot be null");
        return captureSession(PlanningInventory.from(player), settings);
    }

    private static Optional<PlanningSession> captureSession(
            PlanningInventory inventory,
            Settings settings
    ) {
        Objects.requireNonNull(inventory, "inventory cannot be null");
        Objects.requireNonNull(settings, "settings cannot be null");
        if (Minecraft.getInstance().level == null) {
            return Optional.empty();
        }

        return Optional.of(new PlanningSession(inventory, settings, RecipeIndex.snapshot()));
    }

    public record Settings(
            CraftingGrid craftingGrid,
            int maxSearchDepth,
            int maxCandidatesPerLayer,
            LazyCraftConfig.ScoringMode scoringMode
    ) {
        public Settings {
            Objects.requireNonNull(craftingGrid, "craftingGrid cannot be null");
            Objects.requireNonNull(scoringMode, "scoringMode cannot be null");
            if (maxSearchDepth <= 0) {
                throw new IllegalArgumentException("maxSearchDepth must be positive");
            }
            if (maxCandidatesPerLayer <= 0) {
                throw new IllegalArgumentException("maxCandidatesPerLayer must be positive");
            }
        }
    }

    public static final class PlanningSession {
        private final PlanningInventory inventory;
        private final Settings settings;
        private final RecipeIndex.Snapshot recipeIndex;

        private PlanningSession(
                PlanningInventory inventory,
                Settings settings,
                RecipeIndex.Snapshot recipeIndex
        ) {
            this.inventory = inventory;
            this.settings = settings;
            this.recipeIndex = recipeIndex;
        }

        public long recipeIndexGeneration() {
            return recipeIndex.generation();
        }

        public Optional<CraftPlan> plan(
                Item target,
                int quantity,
                BooleanSupplier cancellation
        ) {
            List<SearchResult> results = search(target, quantity, cancellation, true);
            return results.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new CraftPlan(results.getFirst().steps()));
        }

        /**
         * Runs the exact same bounded search as {@link #plan(Item, int, BooleanSupplier)}
         * without allocating crafting-step traces that visible highlighting never consumes.
         */
        public boolean canPlan(
                Item target,
                int quantity,
                BooleanSupplier cancellation
        ) {
            return !search(target, quantity, cancellation, false).isEmpty();
        }

        private List<SearchResult> search(
                Item target,
                int quantity,
                BooleanSupplier cancellation,
                boolean collectSteps
        ) {
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(cancellation, "cancellation cannot be null");
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }

            SearchContext search = SearchContext.create(
                    settings,
                    recipeIndex,
                    cancellation,
                    collectSteps
            );
            search.ensureNotCancelled();
            return search.produce(target, quantity, inventory, new HashSet<>(), 0);
        }
    }

    private record SearchContext(
            RecipeIndex.Snapshot recipeIndex,
            CraftingGrid craftingGrid,
            int maxSearchDepth,
            int maxCandidatesPerLayer,
            BooleanSupplier cancellation,
            Comparator<SearchResult> resultComparator,
            boolean collectSteps
    ) {
        private static SearchContext create(
                Settings settings,
                RecipeIndex.Snapshot recipeIndex,
                BooleanSupplier cancellation,
                boolean collectSteps
        ) {
            return new SearchContext(
                    recipeIndex,
                    settings.craftingGrid(),
                    settings.maxSearchDepth(),
                    settings.maxCandidatesPerLayer(),
                    cancellation,
                    comparator(settings.scoringMode()),
                    collectSteps
            );
        }

        private static Comparator<SearchResult> comparator(
                LazyCraftConfig.ScoringMode scoringMode
        ) {
            return switch (scoringMode) {
                case LEAST_TOTAL_INGREDIENTS -> Comparator
                        .comparingLong(SearchResult::totalIngredients)
                        .thenComparingInt(SearchResult::stepCount);
                case FEWEST_STEPS -> Comparator
                        .comparingInt(SearchResult::stepCount)
                        .thenComparingLong(SearchResult::totalIngredients);
                case FEWEST_RECIPE_EXECUTIONS -> Comparator
                        .comparingLong(SearchResult::recipeExecutions)
                        .thenComparingLong(SearchResult::totalIngredients)
                        .thenComparingInt(SearchResult::stepCount);
                case FASTEST_EXECUTION -> Comparator
                        .comparingLong(SearchResult::recipeExecutions)
                        .thenComparingInt(SearchResult::stepCount)
                        .thenComparingLong(SearchResult::totalIngredients);
                case INVENTORY_FIRST -> Comparator
                        .comparingLong(SearchResult::recursivelySuppliedIngredients)
                        .thenComparingLong(SearchResult::recipeExecutions)
                        .thenComparingLong(SearchResult::totalIngredients)
                        .thenComparingInt(SearchResult::stepCount);
                case SHALLOWEST_CHAIN -> Comparator
                        .comparingInt(SearchResult::maximumDependencyDepth)
                        .thenComparingLong(SearchResult::recipeExecutions)
                        .thenComparingLong(SearchResult::totalIngredients)
                        .thenComparingInt(SearchResult::stepCount);
                case LEAST_BASE_INPUTS -> Comparator
                        .comparingLong(SearchResult::baseInputsConsumed)
                        .thenComparingLong(SearchResult::overproduction)
                        .thenComparingLong(SearchResult::recipeExecutions)
                        .thenComparingInt(SearchResult::stepCount)
                        .thenComparingLong(SearchResult::totalIngredients);
                case LEAST_OVERPRODUCTION -> Comparator
                        .comparingLong(SearchResult::overproduction)
                        .thenComparingLong(SearchResult::totalIngredients)
                        .thenComparingLong(SearchResult::recipeExecutions)
                        .thenComparingInt(SearchResult::stepCount);
            };
        }

        private static long ingredientCost(List<Set<Item>> requirements, int crafts) {
            return Math.multiplyExact((long) requirements.size(), crafts);
        }

        private static void ensureNotCancelled(BooleanSupplier cancellation) {
            if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
                throw new CancellationException();
            }
        }

        /**
         * Produces {@code quantity} additional units. The input inventory is read-only;
         * every branch copies it before consuming ingredients or appending outputs.
         */
        private List<SearchResult> produce(
                Item item,
                int quantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth
        ) {
            ensureNotCancelled();
            if (depth >= maxSearchDepth || !path.add(item)) {
                return List.of();
            }

            try {
                StableTopK<SearchResult> candidates = bestCandidates();
                for (RecipeIndex.ResolvedRecipe recipe : recipeIndex.recipesProducing(item)) {
                    ensureNotCancelled();
                    if (!recipe.supports(craftingGrid)) {
                        continue;
                    }

                    Optional<List<Set<Item>>> optionalRequirements = recipe.requirements();
                    if (optionalRequirements.isEmpty()) {
                        continue;
                    }
                    List<Set<Item>> requirements = optionalRequirements.get();

                    int outputCount = recipe.outputCount(item);
                    if (outputCount == 0) {
                        continue;
                    }

                    int crafts = divideRoundUp(quantity, outputCount);
                    long producedCount = Math.multiplyExact((long) outputCount, crafts);
                    long overproduction = producedCount - quantity;
                    for (SearchResult satisfied : satisfyRequirements(
                            requirements,
                            crafts,
                            SearchResult.empty(inventory),
                            path,
                            depth
                    )) {
                        ensureNotCancelled();
                        PlanningInventory craftedInventory = satisfied.inventory().copy();
                        recipe.addOutputs(craftedInventory, crafts);
                        candidates.add(satisfied.addRecipe(
                                recipe,
                                item,
                                crafts,
                                ingredientCost(requirements, crafts),
                                overproduction,
                                depth + 1,
                                craftedInventory,
                                collectSteps
                        ));
                    }
                }

                return finishCandidates(candidates);
            } finally {
                path.remove(item);
            }
        }

        private List<SearchResult> satisfyRequirements(
                List<Set<Item>> requirements,
                int crafts,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            List<SearchResult> candidates = List.of(start);

            for (Set<Item> acceptedItems : requirements) {
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
                Set<Item> acceptedItems,
                int quantity,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            ensureNotCancelled();
            if (acceptedItems.isEmpty()) {
                return List.of();
            }

            int available = start.inventory().availableItems(acceptedItems);
            if (available >= quantity) {
                PlanningInventory directlyConsumed = start.inventory().copy();
                PlanningInventory.Consumption consumption =
                        directlyConsumed.consumeItems(acceptedItems, quantity);
                return consumption.successful()
                        ? List.of(start.withConsumedInventory(directlyConsumed, consumption))
                        : List.of();
            }

            int missing = quantity - available;
            StableTopK<SearchResult> candidates = bestCandidates();
            for (Item output : acceptedItems) {
                ensureNotCancelled();
                if (!recipeIndex.hasRecipes(output)) {
                    continue;
                }

                for (SearchResult produced : produce(
                        output,
                        missing,
                        start.inventory(),
                        path,
                        depth
                )) {
                    ensureNotCancelled();
                    PlanningInventory consumedInventory = produced.inventory().copy();
                    PlanningInventory.Consumption consumption =
                            consumedInventory.consumeItems(acceptedItems, quantity);
                    if (consumption.successful()) {
                        candidates.add(start.combine(
                                produced,
                                consumedInventory,
                                consumption,
                                missing,
                                collectSteps
                        ));
                    }
                }
            }

            return finishCandidates(candidates);
        }

        private static int divideRoundUp(int dividend, int divisor) {
            return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
        }

        private StableTopK<SearchResult> bestCandidates() {
            return new StableTopK<>(maxCandidatesPerLayer, resultComparator);
        }

        private List<SearchResult> finishCandidates(StableTopK<SearchResult> candidates) {
            ensureNotCancelled();
            return candidates.takeValues();
        }

        private void ensureNotCancelled() {
            ensureNotCancelled(cancellation);
        }
    }

    private record SearchResult(
            PlanningInventory inventory,
            List<CraftingStep> steps,
            int stepCount,
            long totalIngredients,
            long recipeExecutions,
            long recursivelySuppliedIngredients,
            long baseInputsConsumed,
            long overproduction,
            int maximumDependencyDepth
    ) {
        private static SearchResult empty(PlanningInventory inventory) {
            return new SearchResult(inventory, List.of(), 0, 0, 0, 0, 0, 0, 0);
        }

        private SearchResult withConsumedInventory(
                PlanningInventory inventory,
                PlanningInventory.Consumption consumption
        ) {
            return new SearchResult(
                    inventory,
                    steps,
                    stepCount,
                    totalIngredients,
                    recipeExecutions,
                    recursivelySuppliedIngredients,
                    Math.addExact(baseInputsConsumed, consumption.originalItemsConsumed()),
                    overproduction,
                    maximumDependencyDepth
            );
        }

        private SearchResult combine(
                SearchResult next,
                PlanningInventory inventory,
                PlanningInventory.Consumption consumption,
                int recursivelySupplied,
                boolean collectSteps
        ) {
            return new SearchResult(
                    inventory,
                    combineSteps(next, collectSteps),
                    Math.addExact(stepCount, next.stepCount),
                    Math.addExact(totalIngredients, next.totalIngredients),
                    Math.addExact(recipeExecutions, next.recipeExecutions),
                    Math.addExact(
                            Math.addExact(
                                    recursivelySuppliedIngredients,
                                    next.recursivelySuppliedIngredients
                            ),
                            recursivelySupplied
                    ),
                    Math.addExact(
                            Math.addExact(baseInputsConsumed, next.baseInputsConsumed),
                            consumption.originalItemsConsumed()
                    ),
                    Math.addExact(overproduction, next.overproduction),
                    Math.max(maximumDependencyDepth, next.maximumDependencyDepth)
            );
        }

        private SearchResult addRecipe(
                RecipeIndex.ResolvedRecipe recipe,
                Item output,
                int crafts,
                long ingredientCost,
                long extraOutput,
                int dependencyDepth,
                PlanningInventory inventory,
                boolean collectSteps
        ) {
            List<CraftingStep> nextSteps = List.of();
            if (collectSteps) {
                nextSteps = new ArrayList<>(Math.addExact(stepCount, 1));
                nextSteps.addAll(steps);
                nextSteps.add(new CraftingStep(recipe.entry(), output, crafts));
            }

            return new SearchResult(
                    inventory,
                    nextSteps,
                    Math.addExact(stepCount, 1),
                    Math.addExact(totalIngredients, ingredientCost),
                    Math.addExact(recipeExecutions, crafts),
                    recursivelySuppliedIngredients,
                    baseInputsConsumed,
                    Math.addExact(overproduction, extraOutput),
                    Math.max(maximumDependencyDepth, dependencyDepth)
            );
        }

        private List<CraftingStep> combineSteps(
                SearchResult next,
                boolean collectSteps
        ) {
            if (!collectSteps) {
                return List.of();
            }
            if (steps.isEmpty()) {
                return next.steps;
            }
            if (next.steps.isEmpty()) {
                return steps;
            }

            List<CraftingStep> combined = new ArrayList<>(
                    Math.addExact(stepCount, next.stepCount)
            );
            combined.addAll(steps);
            combined.addAll(next.steps);
            return combined;
        }
    }
}
