package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingGrid;
import klumpler.lazycraft.client.planner.RecipeIndex;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VisibleRecipeCraftability {
    private static final ExecutorService PLANNING_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "LazyCraft recipe-book planner");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
    private static final State EMPTY_STATE = State.empty();

    private static long nextGeneration;
    private static State activeState = EMPTY_STATE;
    private static Refresh refresh;
    private static Job inFlightJob;
    private static long filterRevision;
    private static int backgroundCooldownTicks;
    private static boolean shuttingDown;

    private VisibleRecipeCraftability() {
    }

    public static void beginRefresh() {
        State previousState = activeState;
        refresh = null;
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
        refresh = new Refresh(
                generation,
                environment,
                canReuseState ? previousState.session : null,
                canReuseState
                        ? previousState.craftableByOutput
                        : new HashMap<>()
        );
    }

    public static void track(RecipeCollection collection, ContextMap context) {
        if (refresh == null || collection.hasCraftable()) {
            return;
        }

        for (RecipeDisplayEntry entry
                : collection.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY)) {
            Item indexedOutput = RecipeIndex.primaryOutputOrNull(entry.id());
            if (indexedOutput != null) {
                refresh.track(entry.id(), indexedOutput);
                continue;
            }

            for (ItemStack result : entry.resultItems(context)) {
                if (!result.isEmpty()) {
                    refresh.track(entry.id(), result.getItem());
                    break;
                }
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

    public static boolean isRecursivelyCraftable(RecipeDisplayId recipe) {
        return activeState.isRecursivelyCraftable(recipe);
    }

    public static List<RecipeDisplayEntry> includeRecursivelyCraftableEntries(
            RecipeCollection collection,
            List<RecipeDisplayEntry> entries,
            Set<RecipeDisplayId> selected,
            List<RecipeDisplayEntry> vanillaEntries
    ) {
        List<RecipeDisplayEntry> expanded = null;
        for (RecipeDisplayEntry entry : entries) {
            RecipeDisplayId recipe = entry.id();
            if (!selected.contains(recipe)
                    || collection.isCraftable(recipe)
                    || !isRecursivelyCraftable(recipe)) {
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
        for (RecipeDisplayEntry entry
                : collection.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY)) {
            if (isRecursivelyCraftable(entry.id())) {
                return true;
            }
        }
        return false;
    }

    public static long filterRevision() {
        return filterRevision;
    }

    public static void tick(Minecraft minecraft) {
        if (shuttingDown) {
            return;
        }

        revalidateEnvironment(minecraft);
        dispatchNextJob();
        if (inFlightJob == null && backgroundCooldownTicks > 0) {
            backgroundCooldownTicks--;
        }
    }

    private static long invalidateCurrentWork() {
        long generation = ++nextGeneration;
        refresh = null;
        activeState = EMPTY_STATE;
        backgroundCooldownTicks = 0;
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
                .filter(session -> session.recipeIndexGeneration()
                        == environment.recipeIndexGeneration)
                .orElse(null);
    }

    private static void revalidateEnvironment(Minecraft minecraft) {
        if (activeState == EMPTY_STATE) {
            return;
        }

        Environment currentEnvironment = Environment.capture(minecraft);
        if (currentEnvironment == null
                || !activeState.representsSamePage(currentEnvironment)) {
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

        Job job = new Job(
                activeState.generation,
                output,
                activeState.session
        );
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
        } else if (cause != null
                && !(cause instanceof CancellationException)
                && currentGeneration) {
            LazyCraft.LOGGER.warn(
                    "Could not evaluate recipe-book output {}",
                    job.output,
                    cause
            );
        }

        if (currentGeneration
                && !activeState.visibleOutputs.contains(job.output)
                && !(cause instanceof CancellationException)) {
            backgroundCooldownTicks = LazyCraftConfigManager.get()
                    .backgroundRecipeCheckDelayTicks;
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

    private static Deque<Item> createPendingOutputs(
            Set<Item> visibleOutputs,
            Map<Item, Boolean> craftableByOutput
    ) {
        Deque<Item> pendingOutputs = new ArrayDeque<>();
        for (Item output : visibleOutputs) {
            if (!craftableByOutput.containsKey(output)) {
                pendingOutputs.addLast(output);
            }
        }
        for (Item output : RecipeIndex.primaryOutputs()) {
            if (!visibleOutputs.contains(output)
                    && !craftableByOutput.containsKey(output)) {
                pendingOutputs.addLast(output);
            }
        }
        return pendingOutputs;
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
            if (!config.recipeBookCrafting
                    || !config.recursiveRecipeBookCrafting
                    || !config.showRecursiveCraftability) {
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
        private final Map<RecipeDisplayId, Item> outputByRecipe = new HashMap<>();
        private final Map<Item, Boolean> craftableByOutput;
        private final Set<Item> visibleOutputs = new LinkedHashSet<>();

        private Refresh(
                long generation,
                Environment environment,
                RecipePlanner.PlanningSession reusableSession,
                Map<Item, Boolean> craftableByOutput
        ) {
            this.generation = generation;
            this.environment = environment;
            this.reusableSession = reusableSession;
            this.craftableByOutput = craftableByOutput;
        }

        private void track(RecipeDisplayId recipe, Item output) {
            outputByRecipe.put(recipe, output);
            visibleOutputs.add(output);
        }

        private State finish() {
            RecipePlanner.PlanningSession session = reusableSession != null
                    ? reusableSession
                    : captureSession(environment);
            if (session == null) {
                return EMPTY_STATE;
            }

            return new State(
                    generation,
                    environment,
                    session,
                    Map.copyOf(outputByRecipe),
                    Collections.unmodifiableSet(new LinkedHashSet<>(visibleOutputs)),
                    craftableByOutput,
                    createPendingOutputs(visibleOutputs, craftableByOutput)
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
            Map<RecipeDisplayId, Item> outputByRecipe,
            Set<Item> visibleOutputs,
            Map<Item, Boolean> craftableByOutput,
            Deque<Item> pendingOutputs
    ) {
        private static State empty() {
            return new State(
                    0,
                    null,
                    null,
                    Map.of(),
                    Set.of(),
                    new HashMap<>(),
                    new ArrayDeque<>()
            );
        }

        private boolean matches(Environment other) {
            return environment != null && environment.equals(other);
        }

        private boolean representsSamePage(Environment other) {
            return environment != null
                    && environment.player == other.player
                    && environment.level == other.level
                    && environment.menu == other.menu
                    && environment.settings.craftingGrid()
                    .equals(other.settings.craftingGrid());
        }

        private boolean isRecursivelyCraftable(RecipeDisplayId recipe) {
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

        private State recalculate(
                long generation,
                Environment currentEnvironment,
                RecipePlanner.PlanningSession session
        ) {
            return new State(
                    generation,
                    currentEnvironment,
                    session,
                    outputByRecipe,
                    visibleOutputs,
                    new HashMap<>(),
                    createPendingOutputs(visibleOutputs, Map.of())
            );
        }
    }

    private static final class Job {
        private final long generation;
        private final Item output;
        private final RecipePlanner.PlanningSession session;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Job(
                long generation,
                Item output,
                RecipePlanner.PlanningSession session
        ) {
            this.generation = generation;
            this.output = output;
            this.session = session;
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
