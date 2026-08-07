package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.planner.CraftingGrid;
import klumpler.lazycraft.client.planner.InventorySnapshot;
import klumpler.lazycraft.client.planner.RecipeIndex;
import klumpler.lazycraft.client.planner.RecipePlanner;
import me.shedaniel.autoconfig.AutoConfig;
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
    private static final ConcurrentLinkedQueue<JobResult> COMPLETED_JOBS =
            new ConcurrentLinkedQueue<>();
    private static final State EMPTY_STATE = State.empty();

    private static long nextGeneration;
    private static long nextJobId;
    private static State activeState = EMPTY_STATE;
    private static Refresh refresh;
    private static Job inFlightJob;
    private static JobTask inFlightTask;
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

        InventorySnapshot inventory = InventorySnapshot.from(environment.player);
        RecipePlanner.PlanningSession session = RecipePlanner.createWorkerSession(
                        inventory,
                        environment.scoringMode
                )
                .filter(captured -> captured.recipeIndexGeneration()
                        == environment.recipeIndexGeneration)
                .orElse(null);
        if (session == null) {
            return;
        }

        Map<Item, Boolean> reusableResults = previousState.matches(environment)
                ? new HashMap<>(previousState.craftableByOutput)
                : new HashMap<>();
        refresh = new Refresh(
                generation,
                environment,
                session,
                reusableResults
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
            entry.resultItems(context).stream()
                    .filter(stack -> !stack.isEmpty())
                    .findFirst()
                    .map(ItemStack::getItem)
                    .ifPresent(output -> refresh.track(entry.id(), output));
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
        COMPLETED_JOBS.clear();
    }

    /**
     * Returns a cached result only; recursive planning is never performed while rendering.
     */
    public static boolean isRecursivelyCraftable(RecipeDisplayId recipe) {
        return activeState.isRecursivelyCraftable(recipe);
    }

    /**
     * Revalidates visible state and applies completed worker results on the client thread.
     */
    public static void tick(Minecraft minecraft) {
        if (shuttingDown) {
            return;
        }

        revalidateEnvironment(minecraft);
        applyCompletedJobs();
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

        InventorySnapshot inventory = InventorySnapshot.from(currentEnvironment.player);
        RecipePlanner.PlanningSession session = RecipePlanner.createWorkerSession(
                        inventory,
                        currentEnvironment.scoringMode
                )
                .filter(captured -> captured.recipeIndexGeneration()
                        == currentEnvironment.recipeIndexGeneration)
                .orElse(null);
        if (session == null) {
            invalidateCurrentWork();
            return;
        }

        long generation = ++nextGeneration;
        cancelInFlightJob();
        activeState = activeState.recalculate(
                generation,
                currentEnvironment,
                session
        );
    }

    private static void applyCompletedJobs() {
        JobResult result;
        while ((result = COMPLETED_JOBS.poll()) != null) {
            if (inFlightJob == null || result.jobId != inFlightJob.id) {
                continue;
            }

            inFlightJob = null;
            inFlightTask = null;

            if (result.status == JobStatus.SUCCESS
                    && activeState.generation == result.generation
                    && activeState.visibleOutputs.contains(result.output)) {
                activeState.craftableByOutput.put(result.output, result.craftable);
            } else if (result.status == JobStatus.FAILED
                    && activeState.generation == result.generation) {
                LazyCraft.LOGGER.warn(
                        "Could not evaluate visible recipe output {}",
                        result.output,
                        result.failure
                );
            }
        }
    }

    private static void dispatchNextJob() {
        if (shuttingDown
                || inFlightTask != null
                || activeState == EMPTY_STATE) {
            return;
        }

        Item output = activeState.nextPendingOutput();
        if (output == null) {
            return;
        }

        Job job = new Job(
                ++nextJobId,
                activeState.generation,
                output,
                activeState.session
        );
        JobTask task = new JobTask(job);
        inFlightJob = job;
        inFlightTask = task;

        try {
            PLANNING_EXECUTOR.execute(task);
        } catch (RejectedExecutionException exception) {
            inFlightJob = null;
            inFlightTask = null;
            if (!shuttingDown) {
                LazyCraft.LOGGER.warn("Could not start visible recipe planning", exception);
            }
        }
    }

    private enum JobStatus {
        SUCCESS,
        CANCELLED,
        FAILED
    }

    private record Environment(
            Player player,
            Level level,
            int inventoryVersion,
            AbstractContainerMenu menu,
            CraftingGrid craftingGrid,
            int recursionDepth,
            int maxCandidatesPerLayer,
            LazyCraftConfig.ScoringMode scoringMode,
            long recipeIndexGeneration
    ) {
        private static Environment capture(Minecraft minecraft) {
            if (minecraft.player == null || minecraft.level == null) {
                return null;
            }

            LazyCraftConfig config = AutoConfig.getConfigHolder(LazyCraftConfig.class).getConfig();
            if (!config.recipeBookCrafting || !config.recursiveRecipeBookCrafting) {
                return null;
            }

            CraftingGrid craftingGrid = CraftingGrid.current().orElse(null);
            if (craftingGrid == null) {
                return null;
            }

            return new Environment(
                    minecraft.player,
                    minecraft.level,
                    minecraft.player.getInventory().getTimesChanged(),
                    minecraft.player.containerMenu,
                    craftingGrid,
                    config.recursionDepth,
                    config.maxCandidatesPerLayer,
                    config.scoringMode,
                    RecipeIndex.generation()
            );
        }
    }

    private static final class Refresh {
        private final long generation;
        private final Environment environment;
        private final RecipePlanner.PlanningSession session;
        private final Map<RecipeDisplayId, Item> outputByRecipe = new HashMap<>();
        private final Map<Item, Boolean> craftableByOutput;
        private final Deque<Item> pendingOutputs = new ArrayDeque<>();
        private final Set<Item> visibleOutputs = new LinkedHashSet<>();
        private final Set<Item> queuedOutputs = new HashSet<>();

        private Refresh(
                long generation,
                Environment environment,
                RecipePlanner.PlanningSession session,
                Map<Item, Boolean> craftableByOutput
        ) {
            this.generation = generation;
            this.environment = environment;
            this.session = session;
            this.craftableByOutput = craftableByOutput;
        }

        private void track(RecipeDisplayId recipe, Item output) {
            outputByRecipe.put(recipe, output);
            visibleOutputs.add(output);
            if (!craftableByOutput.containsKey(output) && queuedOutputs.add(output)) {
                pendingOutputs.addLast(output);
            }
        }

        private State finish() {
            craftableByOutput.keySet().retainAll(visibleOutputs);
            if (outputByRecipe.isEmpty()) {
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

    private record State(long generation, Environment environment, RecipePlanner.PlanningSession session,
                         Map<RecipeDisplayId, Item> outputByRecipe, Set<Item> visibleOutputs,
                         Map<Item, Boolean> craftableByOutput, Deque<Item> pendingOutputs) {

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
                        && environment.craftingGrid.equals(other.craftingGrid);
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
        private final long id;
        private final long generation;
        private final Item output;
        private final RecipePlanner.PlanningSession session;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Job(
                long id,
                long generation,
                Item output,
                RecipePlanner.PlanningSession session
        ) {
            this.id = id;
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

    private static final class JobTask extends FutureTask<JobResult> {
        private final Job job;

        private JobTask(Job job) {
            super(() -> runJob(job));
            this.job = job;
        }

        private static JobResult runJob(Job job) {
            boolean craftable = job.session.plan(job.output, 1, job::isCancelled)
                    .filter(plan -> !plan.steps().isEmpty())
                    .isPresent();
            return JobResult.success(job, craftable);
        }

        @Override
        protected void done() {
            JobResult result;
            try {
                result = get();
            } catch (CancellationException exception) {
                result = JobResult.cancelled(job);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = JobResult.cancelled(job);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                result = cause instanceof CancellationException
                        ? JobResult.cancelled(job)
                        : JobResult.failed(job, cause);
            }
            COMPLETED_JOBS.add(result);
        }
    }

    private record JobResult(long jobId, long generation, Item output, JobStatus status, boolean craftable,
                             Throwable failure) {

        private static JobResult success(Job job, boolean craftable) {
                return new JobResult(
                        job.id,
                        job.generation,
                        job.output,
                        JobStatus.SUCCESS,
                        craftable,
                        null
                );
            }

            private static JobResult cancelled(Job job) {
                return new JobResult(
                        job.id,
                        job.generation,
                        job.output,
                        JobStatus.CANCELLED,
                        false,
                        null
                );
            }

            private static JobResult failed(Job job, Throwable failure) {
                return new JobResult(
                        job.id,
                        job.generation,
                        job.output,
                        JobStatus.FAILED,
                        false,
                        failure
                );
            }
        }
}
