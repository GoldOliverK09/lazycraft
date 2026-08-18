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

        CraftingGrid craftingGrid = CraftingGrid.current().orElse(CraftingGrid.CRAFTING_TABLE);
        return captureSession(PlanningInventory.from(player), currentSettings(craftingGrid))
                .flatMap(session -> session.plan(target, 1, () -> false));
    }

    public static Optional<PlanningSession> createShoppingSession(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return captureSession(
                PlanningInventory.from(player),
                currentSettings(CraftingGrid.CRAFTING_TABLE)
        );
    }

    public static Optional<PlanningSession> createWorkerSession(
            Player player,
            Settings settings
    ) {
        Objects.requireNonNull(player, "player cannot be null");
        return captureSession(PlanningInventory.from(player), settings);
    }

    /**
     * Returns the item totals available to crafting, independent of where they are stored.
     */
    public static Map<Item, Integer> availableItemCounts(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return PlanningInventory.from(player).itemCounts();
    }

    private static Settings currentSettings(CraftingGrid craftingGrid) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        return new Settings(
                craftingGrid,
                config.recursionDepth,
                config.maxCandidatesPerLayer,
                config.scoringMode
        );
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

    public enum ShoppingMode {
        INGREDIENTS,
        RAW
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

    public record MissingItem(Item item, int count) {
        public MissingItem {
            Objects.requireNonNull(item, "item cannot be null");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    public record ShoppingList(List<MissingItem> missingItems) {
        public ShoppingList {
            Objects.requireNonNull(missingItems, "missingItems cannot be null");
            missingItems = List.copyOf(missingItems);
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

        public boolean canPlan(
                Item target,
                int quantity,
                BooleanSupplier cancellation
        ) {
            return !search(target, quantity, cancellation, false).isEmpty();
        }

        public Optional<ShoppingList> shoppingList(
                Item target,
                ShoppingMode mode,
                BooleanSupplier cancellation
        ) {
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(mode, "mode cannot be null");
            Objects.requireNonNull(cancellation, "cancellation cannot be null");

            SearchContext search = SearchContext.create(
                    settings,
                    recipeIndex,
                    cancellation,
                    false,
                    true
            );
            search.ensureNotCancelled();
            List<SearchResult> results = switch (mode) {
                case INGREDIENTS -> search.produceIngredientList(
                        target,
                        1,
                        inventory,
                        new HashSet<>(),
                        0
                );
                case RAW -> search.produceRawList(
                        target,
                        1,
                        inventory,
                        new HashSet<>(),
                        0,
                        false
                );
            };
            if (results.isEmpty()) {
                return Optional.empty();
            }

            List<MissingItem> missingItems = results.getFirst().missingItems().entrySet()
                    .stream()
                    .map(entry -> new MissingItem(entry.getKey(), entry.getValue()))
                    .toList();
            return Optional.of(new ShoppingList(missingItems));
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
                    collectSteps,
                    false
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
                boolean collectSteps,
                boolean prioritizeMissingItems
        ) {
            Comparator<SearchResult> configuredComparator = comparator(settings.scoringMode());
            Comparator<SearchResult> effectiveComparator = prioritizeMissingItems
                    ? Comparator.comparingLong(SearchResult::missingItemCount)
                    .thenComparing(configuredComparator)
                    : configuredComparator;
            return new SearchContext(
                    recipeIndex,
                    settings.craftingGrid(),
                    settings.maxSearchDepth(),
                    settings.maxCandidatesPerLayer(),
                    cancellation,
                    effectiveComparator,
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
                case FASTEST_EXECUTION -> Comparator
                        .comparingLong(SearchResult::recipeExecutions)
                        .thenComparingInt(SearchResult::stepCount)
                        .thenComparingLong(SearchResult::totalIngredients);
                case SHALLOWEST_CHAIN -> Comparator
                        .comparingInt(SearchResult::maximumDependencyDepth)
                        .thenComparingLong(SearchResult::recipeExecutions)
                        .thenComparingLong(SearchResult::totalIngredients)
                        .thenComparingInt(SearchResult::stepCount);
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

        private static int divideRoundUp(int dividend, int divisor) {
            return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
        }

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
                return produceRecipeCandidates(
                        item,
                        quantity,
                        inventory,
                        path,
                        depth,
                        RequirementMode.CRAFTABLE,
                        RawRecipeFilter.ALL
                );
            } finally {
                path.remove(item);
            }
        }

        private List<SearchResult> produceIngredientList(
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
                return produceRecipeCandidates(
                        item,
                        quantity,
                        inventory,
                        path,
                        depth,
                        RequirementMode.INGREDIENT_LIST,
                        RawRecipeFilter.ALL
                );
            } finally {
                path.remove(item);
            }
        }

        private List<SearchResult> produceRecipeCandidates(
                Item item,
                int quantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth,
                RequirementMode requirementMode,
                RawRecipeFilter rawRecipeFilter
        ) {
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
                if (rawRecipeFilter != RawRecipeFilter.ALL) {
                    boolean decompression = isReversibleDecompression(
                            item,
                            outputCount,
                            requirements
                    );
                    if (decompression != (rawRecipeFilter == RawRecipeFilter.DECOMPRESSION)) {
                        continue;
                    }
                }

                int crafts = divideRoundUp(quantity, outputCount);
                long producedCount = Math.multiplyExact((long) outputCount, crafts);
                long overproduction = producedCount - quantity;
                List<SearchResult> satisfiedRequirements = satisfyRequirements(
                        requirements,
                        crafts,
                        SearchResult.empty(inventory),
                        path,
                        depth,
                        requirementMode
                );
                for (SearchResult satisfied : satisfiedRequirements) {
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
        }

        private List<SearchResult> satisfyRequirements(
                List<Set<Item>> requirements,
                int crafts,
                SearchResult start,
                Set<Item> path,
                int depth,
                RequirementMode mode
        ) {
            List<SearchResult> candidates = List.of(start);
            for (Set<Item> acceptedItems : requirements) {
                ensureNotCancelled();
                StableTopK<SearchResult> nextCandidates = bestCandidates();
                for (SearchResult candidate : candidates) {
                    for (SearchResult satisfied : satisfyRequirement(
                            acceptedItems,
                            crafts,
                            candidate,
                            path,
                            depth + 1,
                            mode
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

        private List<SearchResult> satisfyRequirement(
                Set<Item> acceptedItems,
                int quantity,
                SearchResult start,
                Set<Item> path,
                int depth,
                RequirementMode mode
        ) {
            ensureNotCancelled();
            if (acceptedItems.isEmpty()) {
                return List.of();
            }

            int initiallyAvailable = start.inventory().availableItems(acceptedItems);
            if (initiallyAvailable >= quantity) {
                PlanningInventory consumedInventory = start.inventory().copy();
                return consumedInventory.consumeItems(acceptedItems, quantity)
                        ? List.of(start.withConsumedInventory(consumedInventory))
                        : List.of();
            }

            int initiallyMissing = quantity - initiallyAvailable;
            return switch (mode) {
                case CRAFTABLE, RAW_LIST -> satisfyRecursiveShortage(
                        acceptedItems,
                        quantity,
                        initiallyMissing,
                        start,
                        path,
                        depth,
                        mode
                );
                case INGREDIENT_LIST -> satisfyIngredientListShortage(
                        acceptedItems,
                        quantity,
                        initiallyMissing,
                        start,
                        path,
                        depth
                );
            };
        }

        private List<SearchResult> satisfyIngredientListShortage(
                Set<Item> acceptedItems,
                int quantity,
                int initiallyMissing,
                SearchResult start,
                Set<Item> path,
                int depth
        ) {
            int minimumShortage = initiallyMissing;
            List<PartialIngredient> bestPartialIngredients = new ArrayList<>();
            for (Item output : acceptedItems) {
                ensureNotCancelled();
                List<SearchResult> producedResults = maximumProducible(
                        output,
                        initiallyMissing,
                        start.inventory(),
                        path,
                        depth
                );
                if (producedResults.isEmpty()) {
                    producedResults = List.of(SearchResult.empty(start.inventory()));
                }

                for (SearchResult produced : producedResults) {
                    int availableAfterProduction = produced.inventory()
                            .availableItems(acceptedItems);
                    int shortage = Math.max(0, quantity - availableAfterProduction);
                    if (shortage < minimumShortage) {
                        minimumShortage = shortage;
                        bestPartialIngredients.clear();
                    }
                    if (shortage == minimumShortage) {
                        bestPartialIngredients.add(new PartialIngredient(
                                output,
                                produced,
                                shortage
                        ));
                    }
                }
            }

            StableTopK<SearchResult> candidates = bestCandidates();
            for (PartialIngredient partial : bestPartialIngredients) {
                SearchResult completed = partial.shortage == 0
                        ? partial.produced
                        : partial.produced.withMissingItem(partial.item, partial.shortage);
                PlanningInventory consumedInventory = completed.inventory().copy();
                if (consumedInventory.consumeItems(acceptedItems, quantity)) {
                    candidates.add(start.combine(
                            completed,
                            consumedInventory,
                            collectSteps
                    ));
                }
            }
            return finishCandidates(candidates);
        }

        private List<SearchResult> maximumProducible(
                Item item,
                int maximumQuantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth
        ) {
            if (!recipeIndex.hasRecipes(item)) {
                return List.of();
            }

            int lowerBound = 1;
            int upperBound = maximumQuantity;
            List<SearchResult> bestResults = List.of();
            while (lowerBound <= upperBound) {
                ensureNotCancelled();
                int quantity = lowerBound + (upperBound - lowerBound) / 2;
                List<SearchResult> results = produce(item, quantity, inventory, path, depth);
                if (results.isEmpty()) {
                    upperBound = quantity - 1;
                } else {
                    bestResults = results;
                    lowerBound = quantity + 1;
                }
            }
            return bestResults;
        }

        private List<SearchResult> produceRawList(
                Item item,
                int quantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth,
                boolean mayMaterialize
        ) {
            ensureNotCancelled();
            if (depth >= maxSearchDepth) {
                return mayMaterialize
                        ? List.of(SearchResult.empty(inventory).withMissingItem(item, quantity))
                        : List.of();
            }
            if (!path.add(item)) {
                return List.of();
            }

            try {
                List<SearchResult> results = produceRawRecipeCandidates(
                        item,
                        quantity,
                        inventory,
                        path,
                        depth,
                        false
                );
                if (!results.isEmpty()) {
                    return results;
                }
                if (!mayMaterialize) {
                    return produceRawRecipeCandidates(
                            item,
                            quantity,
                            inventory,
                            path,
                            depth,
                            true
                    );
                }
                return List.of(SearchResult.empty(inventory).withMissingItem(item, quantity));
            } finally {
                path.remove(item);
            }
        }

        private List<SearchResult> produceRawRecipeCandidates(
                Item item,
                int quantity,
                PlanningInventory inventory,
                Set<Item> path,
                int depth,
                boolean decompressionRecipes
        ) {
            return produceRecipeCandidates(
                    item,
                    quantity,
                    inventory,
                    path,
                    depth,
                    RequirementMode.RAW_LIST,
                    decompressionRecipes
                            ? RawRecipeFilter.DECOMPRESSION
                            : RawRecipeFilter.NON_DECOMPRESSION
            );
        }

        private boolean isReversibleDecompression(
                Item output,
                int outputCount,
                List<Set<Item>> requirements
        ) {
            if (outputCount <= requirements.size()) {
                return false;
            }

            for (Set<Item> acceptedItems : requirements) {
                for (Item ingredient : acceptedItems) {
                    for (RecipeIndex.ResolvedRecipe reverse
                            : recipeIndex.recipesProducing(ingredient)) {
                        if (!reverse.supports(craftingGrid)
                                || reverse.outputCount(ingredient) == 0) {
                            continue;
                        }

                        Optional<List<Set<Item>>> reverseRequirements = reverse.requirements();
                        if (reverseRequirements.isPresent()
                                && !reverseRequirements.get().isEmpty()
                                && reverseRequirements.get().stream()
                                .allMatch(accepted -> accepted.contains(output))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private List<SearchResult> satisfyRecursiveShortage(
                Set<Item> acceptedItems,
                int quantity,
                int missing,
                SearchResult start,
                Set<Item> path,
                int depth,
                RequirementMode mode
        ) {
            StableTopK<SearchResult> candidates = bestCandidates();
            for (Item output : acceptedItems) {
                ensureNotCancelled();
                if (mode == RequirementMode.CRAFTABLE && !recipeIndex.hasRecipes(output)) {
                    continue;
                }

                List<SearchResult> producedResults = mode == RequirementMode.RAW_LIST
                        ? produceRawList(
                        output,
                        missing,
                        start.inventory(),
                        path,
                        depth,
                        true
                )
                        : produce(
                        output,
                        missing,
                        start.inventory(),
                        path,
                        depth
                );
                for (SearchResult produced : producedResults) {
                    ensureNotCancelled();
                    PlanningInventory consumedInventory = produced.inventory().copy();
                    if (consumedInventory.consumeItems(acceptedItems, quantity)) {
                        candidates.add(start.combine(
                                produced,
                                consumedInventory,
                                collectSteps
                        ));
                    }
                }
            }
            return finishCandidates(candidates);
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

        private enum RequirementMode {
            CRAFTABLE,
            INGREDIENT_LIST,
            RAW_LIST
        }

        private enum RawRecipeFilter {
            ALL,
            NON_DECOMPRESSION,
            DECOMPRESSION
        }

        private record PartialIngredient(
                Item item,
                SearchResult produced,
                int shortage
        ) {
        }
    }

    private record SearchResult(
            PlanningInventory inventory,
            List<CraftingStep> steps,
            int stepCount,
            long totalIngredients,
            long recipeExecutions,
            long overproduction,
            int maximumDependencyDepth,
            long missingItemCount,
            Map<Item, Integer> missingItems
    ) {
        private static SearchResult empty(PlanningInventory inventory) {
            return new SearchResult(
                    inventory,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Map.of()
            );
        }

        private static Map<Item, Integer> mergeMissingItems(
                Map<Item, Integer> first,
                Map<Item, Integer> second
        ) {
            if (first.isEmpty()) {
                return second;
            }
            if (second.isEmpty()) {
                return first;
            }

            Map<Item, Integer> merged = new LinkedHashMap<>(first);
            second.forEach((item, count) -> merged.merge(item, count, Math::addExact));
            return Collections.unmodifiableMap(merged);
        }

        private SearchResult withConsumedInventory(PlanningInventory inventory) {
            return new SearchResult(
                    inventory,
                    steps,
                    stepCount,
                    totalIngredients,
                    recipeExecutions,
                    overproduction,
                    maximumDependencyDepth,
                    missingItemCount,
                    missingItems
            );
        }

        private SearchResult withMissingItem(Item item, int count) {
            PlanningInventory expandedInventory = inventory.copy();
            expandedInventory.add(item, count);
            Map<Item, Integer> expandedMissingItems = new LinkedHashMap<>(missingItems);
            expandedMissingItems.merge(item, count, Math::addExact);
            return new SearchResult(
                    expandedInventory,
                    steps,
                    stepCount,
                    totalIngredients,
                    recipeExecutions,
                    overproduction,
                    maximumDependencyDepth,
                    Math.addExact(missingItemCount, count),
                    Collections.unmodifiableMap(expandedMissingItems)
            );
        }

        private SearchResult combine(
                SearchResult next,
                PlanningInventory inventory,
                boolean collectSteps
        ) {
            return new SearchResult(
                    inventory,
                    combineSteps(next, collectSteps),
                    Math.addExact(stepCount, next.stepCount),
                    Math.addExact(totalIngredients, next.totalIngredients),
                    Math.addExact(recipeExecutions, next.recipeExecutions),
                    Math.addExact(overproduction, next.overproduction),
                    Math.max(maximumDependencyDepth, next.maximumDependencyDepth),
                    Math.addExact(missingItemCount, next.missingItemCount),
                    mergeMissingItems(missingItems, next.missingItems)
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
                    Math.addExact(overproduction, extraOutput),
                    Math.max(maximumDependencyDepth, dependencyDepth),
                    missingItemCount,
                    missingItems
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
