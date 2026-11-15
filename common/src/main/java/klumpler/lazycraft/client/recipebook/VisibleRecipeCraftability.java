package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingGrid;
import klumpler.lazycraft.client.planner.RecipeIndex;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class VisibleRecipeCraftability {
    private static final ExecutorService PLANNING_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "LazyCraft recipe-book planner");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });

    private static final State EMPTY_STATE = State.empty();

    private static final Map<RecipeHolder<?>, Item> REUSABLE_OUTPUT_BY_RECIPE = new HashMap<>(64);
    private static State activeState = EMPTY_STATE;
    private static Refresh refresh;
    private static Job inFlightJob;
    private static long filterRevision;
    private static int backgroundCooldownTicks;
    private static boolean shuttingDown;
    private static final Set<Item> REUSABLE_VISIBLE_OUTPUTS = new LinkedHashSet<>(64);
    private static final Deque<Item> REUSABLE_PENDING_OUTPUTS = new ArrayDeque<>(64);
    private static long nextGeneration;
    private static boolean environmentDirty = false;
    private static boolean visibleRecipesDirty = false;
    private static boolean recipeIndexDirty = false;
    private static boolean configurationRefreshRequired;
    private static long lastRecipeIndexGeneration = -1L;
    private static int lastPlayerContainerId = -1;
    private static long lastPlayerInventoryVersion = -1L;

    private VisibleRecipeCraftability() {
    }

    private static int lastSelectedCategory = -1;

    public static void beginRefresh() {
        State previousState = activeState;
        refresh = null;
        environmentDirty = false;
        visibleRecipesDirty = false;

        Environment environment = Environment.capture(Minecraft.getInstance());
        if (environment == null) {
            nextGeneration++;
            backgroundCooldownTicks = 0;
            cancelInFlightJob();
            if (previousState.hasRecursivelyCraftableResults()) {
                filterRevision++;
            }
            return;
        }

        boolean canReuseState = previousState.matches(environment);
        long generation = previousState.generation;
        if (!canReuseState) {
            generation = ++nextGeneration;
            backgroundCooldownTicks = 0;
            cancelInFlightJob();
            if (previousState.hasRecursivelyCraftableResults()) {
                filterRevision++;
            }
        }

        REUSABLE_OUTPUT_BY_RECIPE.clear();
        REUSABLE_VISIBLE_OUTPUTS.clear();

        refresh = new Refresh(
                generation,
                environment,
                canReuseState ? previousState.session : null,
                canReuseState ? previousState.craftableByOutput : new HashMap<>()
        );
    }

    public static void track(RecipeCollection collection) {
        if (refresh == null) {
            return;
        }

        int category = collection.hashCode();
        if (category != lastSelectedCategory) {
            lastSelectedCategory = category;
            visibleRecipesDirty = true;
        }

        for (RecipeHolder<?> entry : collection.getDisplayRecipes(false)) {
            Item indexedOutput = RecipeIndex.primaryOutputOrNull(entry);
            if (indexedOutput != null) {
                refresh.track(entry, indexedOutput);
                continue;
            }

            ItemStack result = entry.value().getResultItem(collection.registryAccess());
            if (!result.isEmpty()) {
                refresh.track(entry, result.getItem());
            }
        }
    }

    public static void finishRefresh() {
        if (refresh == null) {
            activeState = EMPTY_STATE;
            return;
        }

        State finishedState = refresh.finish();
        if (finishedState == EMPTY_STATE && refresh.hasRecursivelyCraftableResults()) {
            filterRevision++;
        }
        activeState = finishedState;
        refresh = null;
        visibleRecipesDirty = false;
        prioritizeVisibleWork();
        dispatchNextJob();
    }

    public static void clear() {
        if (activeState.hasRecursivelyCraftableResults()) {
            filterRevision++;
        }
        invalidateCurrentWork();
    }

    public static void shutdown() {
        shuttingDown = true;
        invalidateCurrentWork();
        PLANNING_EXECUTOR.shutdownNow();
    }

    public static boolean isRecursivelyCraftable(RecipeHolder<?> recipe) {
        return activeState.isRecursivelyCraftable(recipe);
    }

    public static Predicate<RecipeCollection> includeRecursiveCollections(Predicate<RecipeCollection> vanillaFilter) {
        return collection -> vanillaFilter.test(collection) && !hasRecursivelyCraftable(collection);
    }

    public static long refreshFilter(long previousRevision, BooleanSupplier filtering, Runnable updateCollections) {
        long currentRevision = filterRevision;
        if (currentRevision != previousRevision
                && (filtering.getAsBoolean() || configurationRefreshRequired)) {
            updateCollections.run();
            configurationRefreshRequired = false;
        }
        return currentRevision;
    }

    public static void configurationChanged() {
        environmentDirty = true;
        configurationRefreshRequired = true;
        filterRevision++;
    }

    public static boolean isCraftable(RecipeCollection collection, RecipeHolder<?> recipe) {
        return collection.isCraftable(recipe) || isRecursivelyCraftable(recipe);
    }

    public static List<RecipeHolder<?>> includeRecursivelyCraftableEntries(
            RecipeCollection collection,
            boolean craftableOnly,
            List<RecipeHolder<?>> vanillaEntries
    ) {
        if (!craftableOnly) {
            return vanillaEntries;
        }

        List<RecipeHolder<?>> expanded = null;
        Set<net.minecraft.resources.ResourceLocation> alreadyPresent = new HashSet<>();
        for (RecipeHolder<?> entry : vanillaEntries) {
            alreadyPresent.add(entry.id());
        }
        for (RecipeHolder<?> entry : collection.getDisplayRecipes(false)) {
            if (!isRecursivelyCraftable(entry) || !alreadyPresent.add(entry.id())) {
                continue;
            }

            if (expanded == null) {
                expanded = new ArrayList<>(vanillaEntries);
            }
            expanded.add(entry);
        }
        return expanded != null ? expanded : vanillaEntries;
    }

    public static boolean hasRecursivelyCraftable(RecipeCollection collection) {
        for (RecipeHolder<?> entry : collection.getDisplayRecipes(false)) {
            if (isRecursivelyCraftable(entry)) {
                return true;
            }
        }
        return false;
    }

    public static long filterRevision() {
        return filterRevision;
    }

    // Called when player inventory changes.
    public static void inventoryChanged() {
        if (activeState != EMPTY_STATE) {
            environmentDirty = true;
        }
    }

    // Called when recipe-book category or page changes.
    public static void recipeBookCategoryChanged() {
        visibleRecipesDirty = true;
        environmentDirty = true;
    }

    // Called when the recipe index changes.
    public static void recipeIndexChanged() {
        recipeIndexDirty = true;
        environmentDirty = true;
    }

    public static void tick(Minecraft minecraft) {
        if (shuttingDown) {
            return;
        }

        checkCheapEnvironmentChanges(minecraft);

        if (!environmentDirty && !visibleRecipesDirty && !recipeIndexDirty && inFlightJob == null) {
            if (backgroundCooldownTicks > 0) {
                backgroundCooldownTicks--;
            }
            return;
        }

        if (environmentDirty) {
            environmentDirty = false;
            revalidateEnvironment(minecraft);
        }
        if (visibleRecipesDirty) {
            visibleRecipesDirty = false;
            prioritizeVisibleWork();
        }
        if (recipeIndexDirty) {
            recipeIndexDirty = false;
            if (activeState != EMPTY_STATE) {
                long generation = ++nextGeneration;
                cancelInFlightJob();
                backgroundCooldownTicks = 0;
                if (activeState.hasRecursivelyCraftableResults()) {
                    filterRevision++;
                }
                activeState = activeState.recalculate(generation, activeState.environment, activeState.session);
            }
        }

        dispatchNextJob();

        if (inFlightJob == null && backgroundCooldownTicks > 0) {
            backgroundCooldownTicks--;
        }
    }

    private static void checkCheapEnvironmentChanges(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            if (activeState != EMPTY_STATE) {
                environmentDirty = true;
            }
            return;
        }

        long invVersion = computeInventoryVersion(minecraft.player);
        if (invVersion != lastPlayerInventoryVersion) {
            lastPlayerInventoryVersion = invVersion;
            environmentDirty = true;
        }

        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int containerId = menu.containerId;
        if (containerId != lastPlayerContainerId) {
            lastPlayerContainerId = containerId;
            environmentDirty = true;
        }

        long recipeGen = RecipeIndex.generation();
        if (recipeGen != lastRecipeIndexGeneration) {
            lastRecipeIndexGeneration = recipeGen;
            recipeIndexDirty = true;
        }
    }

    private static long computeInventoryVersion(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        long hash = menu.getStateId();
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            hash ^= carried.getItem().hashCode() * 31L + carried.getCount();
        }
        return hash;
    }

    private static long invalidateCurrentWork() {
        long generation = ++nextGeneration;
        refresh = null;
        activeState = EMPTY_STATE;
        backgroundCooldownTicks = 0;
        environmentDirty = false;
        visibleRecipesDirty = false;
        recipeIndexDirty = false;
        cancelInFlightJob();
        return generation;
    }

    private static void cancelInFlightJob() {
        if (inFlightJob != null) {
            inFlightJob.cancel();
        }
    }

    private static void prioritizeVisibleWork() {
        if (inFlightJob == null
                || activeState == EMPTY_STATE
                || inFlightJob.generation != activeState.generation
                || activeState.visibleOutputs.contains(inFlightJob.output)
                || !activeState.hasPendingVisibleOutput()) {
            return;
        }
        inFlightJob.cancel();
    }

    private static RecipePlanner.PlanningSession captureSession(Environment environment) {
        return RecipePlanner.createWorkerSession(environment.player, environment.settings)
                .filter(session -> session.recipeIndexGeneration() == environment.recipeIndexGeneration)
                .orElse(null);
    }

    private static void revalidateEnvironment(Minecraft minecraft) {
        if (activeState == EMPTY_STATE) {
            return;
        }

        Environment currentEnvironment = Environment.capture(minecraft);
        if (currentEnvironment == null || !activeState.representsSamePage(currentEnvironment)) {
            clear();
            return;
        }

        if (activeState.matches(currentEnvironment)) {
            return;
        }

        RecipePlanner.PlanningSession session = captureSession(currentEnvironment);
        if (session == null) {
            clear();
            return;
        }

        long generation = ++nextGeneration;
        cancelInFlightJob();
        backgroundCooldownTicks = 0;
        if (activeState.hasRecursivelyCraftableResults()) {
            filterRevision++;
        }
        activeState = activeState.recalculate(generation, currentEnvironment, session);
    }

    private static void dispatchNextJob() {
        if (shuttingDown || inFlightJob != null || activeState == EMPTY_STATE) {
            return;
        }

        Item output = activeState.nextPendingOutput(backgroundCooldownTicks == 0);
        if (output == null) {
            return;
        }

        Job job = new Job(activeState.generation, output, activeState.session);
        inFlightJob = job;

        try {
            CompletableFuture<Boolean> task = CompletableFuture.supplyAsync(
                    () -> job.session.canPlan(job.output, 1, job::isCancelled),
                    PLANNING_EXECUTOR
            );
            task.whenCompleteAsync(
                    (craftable, failure) -> completeJob(job, craftable, failure),
                    Minecraft.getInstance()
            );
        } catch (RejectedExecutionException exception) {
            inFlightJob = null;
            if (!shuttingDown) {
                LazyCraft.LOGGER.warn("Could not start recipe-book planning", exception);
            }
        }
    }

    private static void completeJob(Job job, Boolean craftable, Throwable failure) {
        if (inFlightJob != job) {
            return;
        }
        inFlightJob = null;

        Throwable cause = unwrapCompletionException(failure);
        boolean currentGeneration = activeState.generation == job.generation;
        if (cause == null && currentGeneration) {
            Boolean previous = activeState.craftableByOutput.put(job.output, craftable);
            if (!Objects.equals(previous, craftable)
                    && (Boolean.TRUE.equals(previous) || Boolean.TRUE.equals(craftable))) {
                filterRevision++;
            }
        } else if (cause != null && !(cause instanceof CancellationException) && currentGeneration) {
            LazyCraft.LOGGER.warn("Could not evaluate recipe-book output {}", job.output, cause);
        }

        if (currentGeneration
                && !activeState.visibleOutputs.contains(job.output)
                && !(cause instanceof CancellationException)) {
            backgroundCooldownTicks = LazyCraftConfigManager.get().backgroundRecipeCheckDelayTicks;
        }
        dispatchNextJob();
    }

    private static Throwable unwrapCompletionException(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Deque<Item> createPendingOutputs(Set<Item> visibleOutputs, Map<Item, Boolean> craftableByOutput) {
        REUSABLE_PENDING_OUTPUTS.clear();

        for (Item output : visibleOutputs) {
            if (!craftableByOutput.containsKey(output)) {
                REUSABLE_PENDING_OUTPUTS.addLast(output);
            }
        }
        for (Item output : RecipeIndex.primaryOutputs()) {
            if (!visibleOutputs.contains(output) && !craftableByOutput.containsKey(output)) {
                REUSABLE_PENDING_OUTPUTS.addLast(output);
            }
        }
        return REUSABLE_PENDING_OUTPUTS;
    }

    private record Environment(
            Player player,
            Level level,
            Map<Item, Integer> availableItemCounts,
            AbstractContainerMenu menu,
            RecipePlanner.Settings settings,
            long recipeIndexGeneration
    ) {
        private static Environment capture(Minecraft minecraft) {
            if (minecraft.player == null || minecraft.level == null) {
                return null;
            }

            LazyCraftConfig config = LazyCraftConfigManager.get();
            if (!config.recipeBookCrafting || !config.recursiveRecipeBookCrafting || !config.showRecursiveCraftability) {
                return null;
            }

            CraftingGrid craftingGrid = CraftingGrid.current().orElse(null);
            if (craftingGrid == null) {
                return null;
            }

            RecipePlanner.Settings settings = new RecipePlanner.Settings(
                    craftingGrid,
                    config.recursionDepth,
                    config.maxCandidatesPerLayer,
                    config.scoringMode
            );

            return new Environment(
                    minecraft.player,
                    minecraft.level,
                    RecipePlanner.availableItemCounts(minecraft.player),
                    minecraft.player.containerMenu,
                    settings,
                    RecipeIndex.generation()
            );
        }
    }

    private static final class Refresh {
        private final long generation;
        private final Environment environment;
        private final RecipePlanner.PlanningSession reusableSession;
        private final Map<Item, Boolean> craftableByOutput;
        private final Map<RecipeHolder<?>, Item> outputByRecipe = REUSABLE_OUTPUT_BY_RECIPE;
        private final Set<Item> visibleOutputs = REUSABLE_VISIBLE_OUTPUTS;

        private Refresh(long generation, Environment environment, RecipePlanner.PlanningSession reusableSession, Map<Item, Boolean> craftableByOutput) {
            this.generation = generation;
            this.environment = environment;
            this.reusableSession = reusableSession;
            this.craftableByOutput = craftableByOutput;
        }

        private void track(RecipeHolder<?> recipe, Item output) {
            outputByRecipe.put(recipe, output);
            visibleOutputs.add(output);
        }

        private State finish() {
            RecipePlanner.PlanningSession session = reusableSession != null ? reusableSession : captureSession(environment);
            if (session == null) {
                return EMPTY_STATE;
            }

            Map<RecipeHolder<?>, Item> outputSnapshot = Map.copyOf(outputByRecipe);
            Set<Item> visibleSnapshot = Collections.unmodifiableSet(new LinkedHashSet<>(visibleOutputs));
            Deque<Item> pendingSnapshot = new ArrayDeque<>(createPendingOutputs(visibleSnapshot, craftableByOutput));

            outputByRecipe.clear();
            visibleOutputs.clear();

            return new State(
                    generation,
                    environment,
                    session,
                    outputSnapshot,
                    visibleSnapshot,
                    craftableByOutput,
                    pendingSnapshot
            );
        }

        private boolean hasRecursivelyCraftableResults() {
            return craftableByOutput.containsValue(Boolean.TRUE);
        }
    }

    private record State(
            long generation,
            Environment environment,
            RecipePlanner.PlanningSession session,
            Map<RecipeHolder<?>, Item> outputByRecipe,
            Set<Item> visibleOutputs,
            Map<Item, Boolean> craftableByOutput,
            Deque<Item> pendingOutputs
    ) {
        private static State empty() {
            return new State(0, null, null, Map.of(), Set.of(), new HashMap<>(), new ArrayDeque<>());
        }

        private boolean matches(Environment other) {
            return environment != null && environment.equals(other);
        }

        private boolean representsSamePage(Environment other) {
            return environment != null
                    && environment.player == other.player
                    && environment.level == other.level
                    && environment.menu == other.menu
                    && environment.settings.craftingGrid().equals(other.settings.craftingGrid());
        }

        private boolean isRecursivelyCraftable(RecipeHolder<?> recipe) {
            Item output = outputByRecipe.get(recipe);
            if (output == null) {
                output = RecipeIndex.primaryOutputOrNull(recipe);
            }
            return output != null && Boolean.TRUE.equals(craftableByOutput.get(output));
        }

        private boolean hasRecursivelyCraftableResults() {
            return craftableByOutput.containsValue(Boolean.TRUE);
        }

        private boolean hasPendingVisibleOutput() {
            for (Item output : visibleOutputs) {
                if (!craftableByOutput.containsKey(output)) {
                    return true;
                }
            }
            return false;
        }

        private Item nextPendingOutput(boolean allowBackground) {
            while (!pendingOutputs.isEmpty()) {
                Item output = pendingOutputs.peekFirst();
                if (craftableByOutput.containsKey(output)) {
                    pendingOutputs.removeFirst();
                    continue;
                }
                if (!allowBackground && !visibleOutputs.contains(output)) {
                    return null;
                }
                return pendingOutputs.removeFirst();
            }
            return null;
        }

        private State recalculate(long generation, Environment currentEnvironment, RecipePlanner.PlanningSession session) {
            Deque<Item> newPending = new ArrayDeque<>(createPendingOutputs(visibleOutputs, Map.of()));
            return new State(
                    generation,
                    currentEnvironment,
                    session,
                    outputByRecipe,
                    visibleOutputs,
                    new HashMap<>(),
                    newPending
            );
        }
    }

    private static final class Job {
        private final long generation;
        private final Item output;
        private final RecipePlanner.PlanningSession session;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Job(long generation, Item output, RecipePlanner.PlanningSession session) {
            this.generation = generation;
            this.output = output;
            this.session = session;
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean isCancelled() { return cancelled.get(); }
    }
}
