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

/**
 * Evaluates only recipes represented by the buttons on the current recipe-book page.
 * Minecraft-dependent inputs are captured on the client thread; the detached planning
 * search runs on one cancellable daemon worker.
 */
public final class VisibleRecipeCraftability {
    private static final ExecutorService PLANNING_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "LazyCraft visible-recipe planner");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
    private static final State EMPTY_STATE = State.empty();

    private static long nextGeneration;
    private static State activeState = EMPTY_STATE;
    private static Refresh refresh;
    private static Job inFlightJob;
    private static boolean shuttingDown;

    private VisibleRecipeCraftability() {
    }

    /**
     * Starts collecting the recipes that vanilla is about to bind to visible buttons.
     */
    public static void beginRefresh() {
        State previousState = activeState;
        long generation = invalidateCurrentWork();
        Environment environment = Environment.capture(Minecraft.getInstance());
        if (environment == null) {
            return;
        }

        boolean canReuseState = previousState.matches(environment);
        refresh = new Refresh(
                generation,
                environment,
                canReuseState ? previousState.session : null,
                canReuseState
                        ? previousState.craftableByOutput
                        : new HashMap<>()
        );
    }

    /**
     * Records the uncraftable recipes represented by one visible recipe-book button.
     */
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

    /**
     * Publishes the recipes collected during the current page refresh.
     */
    public static void finishRefresh() {
        if (refresh == null) {
            activeState = EMPTY_STATE;
            return;
        }

        activeState = refresh.finish();
        refresh = null;
        dispatchNextJob();
    }

    public static void clear() {
        invalidateCurrentWork();
    }

    /**
     * Stops the owned worker without waiting for an in-progress search.
     */
    public static void shutdown() {
        shuttingDown = true;
        invalidateCurrentWork();
        PLANNING_EXECUTOR.shutdownNow();
    }

    /**
     * Returns a cached result only; recursive planning is never performed while rendering.
     */
    public static boolean isRecursivelyCraftable(RecipeDisplayId recipe) {
        return activeState.isRecursivelyCraftable(recipe);
    }

    /**
     * Revalidates visible state and schedules pending work on the client thread.
     */
    public static void tick(Minecraft minecraft) {
        if (shuttingDown) {
            return;
        }

        revalidateEnvironment(minecraft);
        dispatchNextJob();
    }

    private static long invalidateCurrentWork() {
        long generation = ++nextGeneration;
        refresh = null;
        activeState = EMPTY_STATE;
        cancelInFlightJob();
        return generation;
    }

    private static void cancelInFlightJob() {
        if (inFlightJob != null) {
            inFlightJob.cancel();
        }
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
            invalidateCurrentWork();
            return;
        }

        if (activeState.matches(currentEnvironment)) {
            return;
        }

        RecipePlanner.PlanningSession session = captureSession(currentEnvironment);
        if (session == null) {
            invalidateCurrentWork();
            return;
        }

        long generation = ++nextGeneration;
        cancelInFlightJob();
        activeState = activeState.recalculate(generation, currentEnvironment, session);
    }

    private static void dispatchNextJob() {
        if (shuttingDown || inFlightJob != null || activeState == EMPTY_STATE) {
            return;
        }

        Item output = activeState.nextPendingOutput();
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
                LazyCraft.LOGGER.warn("Could not start visible recipe planning", exception);
            }
        }
    }

    private static void completeJob(Job job, Boolean craftable, Throwable failure) {
        if (inFlightJob != job) {
            return;
        }

        inFlightJob = null;

        Throwable cause = unwrapCompletionException(failure);
        if (cause == null
                && activeState.generation == job.generation
                && activeState.visibleOutputs.contains(job.output)) {
            activeState.craftableByOutput.put(job.output, craftable);
        } else if (cause != null
                && !(cause instanceof CancellationException)
                && activeState.generation == job.generation) {
            LazyCraft.LOGGER.warn(
                    "Could not evaluate visible recipe output {}",
                    job.output,
                    cause
            );
        }
    }

    private static Throwable unwrapCompletionException(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private record Environment(
            Player player,
            Level level,
            int inventoryVersion,
            AbstractContainerMenu menu,
            RecipePlanner.Settings settings,
            long recipeIndexGeneration
    ) {
        private static Environment capture(Minecraft minecraft) {
            if (minecraft.player == null || minecraft.level == null) {
                return null;
            }

            LazyCraftConfig config = LazyCraftConfigManager.get();
            if (!config.recipeBookCrafting || !config.recursiveRecipeBookCrafting) {
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
                    minecraft.player.getInventory().getTimesChanged(),
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
        private final Deque<Item> pendingOutputs = new ArrayDeque<>();
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
            if (visibleOutputs.add(output) && !craftableByOutput.containsKey(output)) {
                pendingOutputs.addLast(output);
            }
        }

        private State finish() {
            if (outputByRecipe.isEmpty()) {
                return EMPTY_STATE;
            }

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
                    pendingOutputs
            );
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
            return output != null && Boolean.TRUE.equals(craftableByOutput.get(output));
        }

        private Item nextPendingOutput() {
            Item output;
            while ((output = pendingOutputs.pollFirst()) != null) {
                if (!craftableByOutput.containsKey(output)) {
                    return output;
                }
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
                    new ArrayDeque<>(visibleOutputs)
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
