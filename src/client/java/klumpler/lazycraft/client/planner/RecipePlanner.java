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
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return Optional.empty();
        }

        LazyCraftConfig config = config();
        return captureSession(PlanningInventory.from(player), config.scoringMode)
                .flatMap(session -> session.plan(target, 1, () -> false));
    }

    /**
     * Captures every live Minecraft/config input needed by the planner.
     * The returned session performs only detached, immutable-data reads and may be searched
     * on a worker thread.
     */
    public static Optional<PlanningSession> createWorkerSession(
            Player player,
            LazyCraftConfig.ScoringMode scoringMode
    ) {
        Objects.requireNonNull(player, "player cannot be null");
        return captureSession(PlanningInventory.from(player), scoringMode);
    }

    private static Optional<PlanningSession> captureSession(
            PlanningInventory inventory,
            LazyCraftConfig.ScoringMode scoringMode
    ) {
        Objects.requireNonNull(inventory, "inventory cannot be null");
        Objects.requireNonNull(scoringMode, "scoringMode cannot be null");

        if (Minecraft.getInstance().level == null) {
            return Optional.empty();
        }

        LazyCraftConfig config = config();
        return Optional.of(new PlanningSession(
                inventory,
                scoringMode,
                RecipeIndex.snapshot(),
                CraftingGrid.current().orElse(CraftingGrid.CRAFTING_TABLE),
                config.recursionDepth,
                config.maxCandidatesPerLayer
        ));
    }

    private static LazyCraftConfig config() {
        return LazyCraftConfigManager.get();
    }

    public static final class PlanningSession {
        private final PlanningInventory inventory;
        private final LazyCraftConfig.ScoringMode scoringMode;
        private final RecipeIndex.Snapshot recipeIndex;
        private final CraftingGrid craftingGrid;
        private final int maxSearchDepth;
        private final int maxCandidatesPerLayer;

        private PlanningSession(
                PlanningInventory inventory,
                LazyCraftConfig.ScoringMode scoringMode,
                RecipeIndex.Snapshot recipeIndex,
                CraftingGrid craftingGrid,
                int maxSearchDepth,
                int maxCandidatesPerLayer
        ) {
            this.inventory = inventory;
            this.scoringMode = scoringMode;
            this.recipeIndex = recipeIndex;
            this.craftingGrid = craftingGrid;
            this.maxSearchDepth = maxSearchDepth;
            this.maxCandidatesPerLayer = maxCandidatesPerLayer;
        }

        public long recipeIndexGeneration() {
            return recipeIndex.generation();
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

            SearchContext search = SearchContext.create(
                    scoringMode,
                    recipeIndex,
                    craftingGrid,
                    maxSearchDepth,
                    maxCandidatesPerLayer,
                    cancellation
            );
            search.ensureNotCancelled();
            List<SearchResult> results = search.produce(
                    target,
                    quantity,
                    inventory.copy(),
                    Set.of(),
                    0,
                    false
            );
            return results.isEmpty()
                    ? Optional.empty()
                    : Optional.of(search.toPlan(results.getFirst()));
        }
    }

    private record SearchContext(
            RecipeIndex.Snapshot recipeIndex,
            CraftingGrid craftingGrid,
            int maxSearchDepth,
            int maxCandidatesPerLayer,
            BooleanSupplier cancellation,
            Comparator<SearchResult> resultComparator
    ) {
        private static SearchContext create(
                LazyCraftConfig.ScoringMode scoringMode,
                RecipeIndex.Snapshot recipeIndex,
                CraftingGrid craftingGrid,
                int maxSearchDepth,
                int maxCandidatesPerLayer,
            BooleanSupplier cancellation
        ) {
            Comparator<SearchResult> resultComparator = Comparator
                    .comparingLong((SearchResult result) ->
                            score(result, scoringMode, cancellation))
                    .thenComparingLong(SearchResult::totalIngredients)
                    .thenComparingInt(result -> result.steps().size());
            return new SearchContext(
                    recipeIndex,
                    craftingGrid,
                    maxSearchDepth,
                    maxCandidatesPerLayer,
                    cancellation,
                    resultComparator
            );
        }

        private static long ingredientCost(List<Set<Item>> requirements, int crafts) {
            return Math.multiplyExact((long) requirements.size(), crafts);
        }

        private static long score(
                SearchResult result,
                LazyCraftConfig.ScoringMode scoringMode,
                BooleanSupplier cancellation
        ) {
            ensureNotCancelled(cancellation);
            return switch (scoringMode) {
                case LEAST_TOTAL_INGREDIENTS -> result.totalIngredients();
                case FEWEST_STEPS -> result.steps().size();
                case FEWEST_RECIPE_EXECUTIONS -> result.recipeExecutions();
            };
        }

        private static void ensureNotCancelled(BooleanSupplier cancellation) {
            if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
                throw new CancellationException();
            }
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

                Optional<List<Set<Item>>> optionalRequirements = recipe.requirements();
                if (optionalRequirements.isEmpty()) {
                    continue;
                }
                List<Set<Item>> requirements = optionalRequirements.get();

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

        private List<SearchResult> finishCandidates(StableTopK<SearchResult> candidates) {
            ensureNotCancelled();
            return candidates.toList();
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
                directlyConsumed.consumeItems(acceptedItems, quantity);
                return List.of(start.withInventory(directlyConsumed));
            }

            int missing = quantity - available;
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

        private static int divideRoundUp(int dividend, int divisor) {
            return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
        }

        private StableTopK<SearchResult> bestCandidates() {
            return new StableTopK<>(maxCandidatesPerLayer, resultComparator);
        }

        private CraftPlan toPlan(SearchResult result) {
            return new CraftPlan(result.steps());
        }

        private void ensureNotCancelled() {
            ensureNotCancelled(cancellation);
        }
    }

    private record SearchResult(
            PlanningInventory inventory,
            List<CraftingStep> steps,
            long totalIngredients,
            long recipeExecutions
    ) {
        private SearchResult {
            steps = List.copyOf(steps);
        }

        private static SearchResult empty(PlanningInventory inventory) {
            return new SearchResult(inventory, List.of(), 0, 0);
        }

        private SearchResult withInventory(PlanningInventory inventory) {
            return new SearchResult(inventory, steps, totalIngredients, recipeExecutions);
        }

        private SearchResult combine(SearchResult next, PlanningInventory inventory) {
            List<CraftingStep> combinedSteps = new ArrayList<>(steps.size() + next.steps.size());
            combinedSteps.addAll(steps);
            combinedSteps.addAll(next.steps);
            return new SearchResult(
                    inventory,
                    combinedSteps,
                    Math.addExact(totalIngredients, next.totalIngredients),
                    Math.addExact(recipeExecutions, next.recipeExecutions)
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
                    Math.addExact(totalIngredients, ingredientCost),
                    Math.addExact(recipeExecutions, step.crafts())
            );
        }
    }
}
