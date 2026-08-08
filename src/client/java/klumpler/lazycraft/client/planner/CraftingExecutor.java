package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

import java.util.*;

/**
 * Executes one crafting-table recipe at a time through Minecraft's normal client packet methods.
 * The server remains authoritative for every placement and result-slot click.
 */
public final class CraftingExecutor {
    private static final Deque<QueuedCraft> queuedCrafts = new ArrayDeque<>();
    private static ActiveCraft activeCraft;
    private static Runnable completionCallback;
    private static int executionUpdateTimeoutTicks;
    private static int executionStepDelayTicks;

    private CraftingExecutor() {
    }

    /**
     * Executes one selected recipe, optionally asking vanilla to fill the grid
     * with the maximum number of ingredient sets available.
     */
    public static boolean execute(CraftingStep step, boolean useMaxItems) {
        return execute(step, useMaxItems, null);
    }

    /**
     * Executes one selected recipe and runs {@code onComplete} after it succeeds.
     */
    public static boolean execute(CraftingStep step, boolean useMaxItems, Runnable onComplete) {
        Objects.requireNonNull(step, "step cannot be null");
        return executeQueuedCrafts(
                List.of(new QueuedCraft(step, false, useMaxItems, false)),
                onComplete
        );
    }

    /**
     * Takes a recipe result that vanilla has already placed in the crafting grid.
     */
    public static boolean executePlaced(CraftingStep step) {
        return executePlaced(step, null);
    }

    /**
     * Takes an already-placed recipe result and runs {@code onComplete} after it succeeds.
     */
    public static boolean executePlaced(CraftingStep step, Runnable onComplete) {
        Objects.requireNonNull(step, "step cannot be null");

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || !menu.getResultSlot().getItem().is(step.output())) {
            return false;
        }

        return executeQueuedCrafts(
                List.of(new QueuedCraft(step, false, false, true)),
                onComplete
        );
    }

    /**
     * Executes every step in a planner result, including its final requested recipe.
     */
    public static boolean execute(CraftPlan plan) {
        return execute(plan, null);
    }

    /**
     * Executes a plan and runs {@code onComplete} after its final craft succeeds.
     */
    public static boolean execute(CraftPlan plan, Runnable onComplete) {
        return executePlan(plan, onComplete, false);
    }

    /**
     * Executes a plan and leaves its final crafted result on the player's cursor.
     * Dependency results still use quick-move so later recipes can use them.
     */
    public static boolean executeToCursor(CraftPlan plan) {
        return executeToCursor(plan, null);
    }

    /**
     * Executes a plan, leaves its final result on the cursor, then runs {@code onComplete}.
     */
    public static boolean executeToCursor(CraftPlan plan, Runnable onComplete) {
        return executePlan(plan, onComplete, true);
    }

    private static boolean executePlan(CraftPlan plan, Runnable onComplete, boolean takeFinalResultToCursor) {
        Objects.requireNonNull(plan, "plan cannot be null");
        List<CraftingStep> steps = plan.steps();
        if (steps.isEmpty()) {
            return false;
        }

        int lastStepIndex = steps.size() - 1;
        List<QueuedCraft> craftsToQueue = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            craftsToQueue.add(new QueuedCraft(
                    steps.get(index),
                    takeFinalResultToCursor && index == lastStepIndex,
                    false,
                    false
            ));
        }
        return executeQueuedCrafts(craftsToQueue, onComplete);
    }

    private static boolean executeQueuedCrafts(List<QueuedCraft> craftsToQueue, Runnable onComplete) {
        if (activeCraft != null || !queuedCrafts.isEmpty() || craftsToQueue.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return false;
        }

        LazyCraftConfig config = LazyCraftConfigManager.get();
        executionUpdateTimeoutTicks = config.serverUpdateTimeoutTicks;
        executionStepDelayTicks = config.stepDelayTicks;
        queuedCrafts.addAll(craftsToQueue);
        completionCallback = onComplete;
        startNextCraft(minecraft, menu);
        return true;
    }

    /**
     * Must run once per client tick. It advances only after the server updates the crafting menu.
     */
    public static void tick(Minecraft minecraft) {
        if (activeCraft == null) {
            return;
        }

        if (minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || menu.containerId != activeCraft.containerId) {
            stop("the crafting grid was closed");
            return;
        }

        activeCraft.ticksWaiting++;

        if (activeCraft.phase == Phase.WAITING_TO_START_NEXT_STEP) {
            if (activeCraft.ticksWaiting < executionStepDelayTicks) {
                return;
            }

            activeCraft = null;
            startNextCraft(minecraft, menu);
            return;
        }

        if (activeCraft.ticksWaiting > executionUpdateTimeoutTicks) {
            stop("the server did not update the crafting table in time");
            return;
        }

        if (menu.getStateId() == activeCraft.expectedStateId) {
            return;
        }

        switch (activeCraft.phase) {
            case WAITING_FOR_PLACEMENT -> takeResult(minecraft, activeCraft, menu);
            case WAITING_FOR_CRAFT -> {
                activeCraft.remainingCrafts--;
                if (activeCraft.remainingCrafts == 0) {
                    LazyCraft.LOGGER.info("Finished crafting {}", itemName(activeCraft.step.output()));
                    if (!queuedCrafts.isEmpty()) {
                        activeCraft.phase = Phase.WAITING_TO_START_NEXT_STEP;
                        activeCraft.ticksWaiting = 0;
                    } else {
                        activeCraft = null;
                        runCompletionCallback();
                    }
                } else {
                    placeRecipe(minecraft, activeCraft, menu);
                }
            }
        }
    }

    public static boolean isExecuting() {
        return activeCraft != null || !queuedCrafts.isEmpty();
    }

    private static void startNextCraft(Minecraft minecraft, AbstractCraftingMenu menu) {
        QueuedCraft nextCraft = queuedCrafts.removeFirst();
        CraftingStep step = nextCraft.step();
        activeCraft = new ActiveCraft(
                step,
                menu.containerId,
                step.crafts(),
                nextCraft.takeResultToCursor(),
                nextCraft.useMaxItems()
        );
        if (nextCraft.recipeAlreadyPlaced()) {
            takeResult(minecraft, activeCraft, menu);
        } else {
            placeRecipe(minecraft, activeCraft, menu);
        }
    }

    private static void placeRecipe(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        waitForMenuUpdate(active, Phase.WAITING_FOR_PLACEMENT, menu);
        minecraft.gameMode.handlePlaceRecipe(
                menu.containerId,
                active.step.recipe().id(),
                active.useMaxItems
        );
    }

    private static void takeResult(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        if (!menu.getResultSlot().getItem().is(active.step.output())) {
            stop("the server could not place " + itemName(active.step.output()));
            return;
        }

        waitForMenuUpdate(active, Phase.WAITING_FOR_CRAFT, menu);
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                menu.getResultSlot().index,
                0,
                active.takeResultToCursor ? ContainerInput.PICKUP : ContainerInput.QUICK_MOVE,
                minecraft.player
        );
    }

    private static void waitForMenuUpdate(ActiveCraft active, Phase phase, AbstractCraftingMenu menu) {
        active.phase = phase;
        active.expectedStateId = menu.getStateId();
        active.ticksWaiting = 0;
    }

    private static void stop(String reason) {
        LazyCraft.LOGGER.warn("Stopped crafting executor: {}", reason);
        activeCraft = null;
        queuedCrafts.clear();
        completionCallback = null;
    }

    private static void runCompletionCallback() {
        Runnable callback = completionCallback;
        completionCallback = null;
        if (callback != null) {
            callback.run();
        }
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private enum Phase {
        WAITING_FOR_PLACEMENT,
        WAITING_FOR_CRAFT,
        WAITING_TO_START_NEXT_STEP
    }

    private record QueuedCraft(
            CraftingStep step,
            boolean takeResultToCursor,
            boolean useMaxItems,
            boolean recipeAlreadyPlaced
    ) {
    }

    private static final class ActiveCraft {
        private final CraftingStep step;
        private final int containerId;
        private final boolean takeResultToCursor;
        private final boolean useMaxItems;
        private int remainingCrafts;
        private int expectedStateId;
        private int ticksWaiting;
        private Phase phase = Phase.WAITING_FOR_PLACEMENT;

        private ActiveCraft(
                CraftingStep step,
                int containerId,
                int remainingCrafts,
                boolean takeResultToCursor,
                boolean useMaxItems
        ) {
            this.step = step;
            this.containerId = containerId;
            this.remainingCrafts = remainingCrafts;
            this.takeResultToCursor = takeResultToCursor;
            this.useMaxItems = useMaxItems;
        }
    }
}
