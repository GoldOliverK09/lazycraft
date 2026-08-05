package klumpler.lazycraft.client.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Executes one crafting-table recipe at a time through Minecraft's normal client packet methods.
 * The server remains authoritative for every placement and result-slot click.
 */
public final class CraftingExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("lazycraft");
    private static final int UPDATE_TIMEOUT_TICKS = 40;
    private static final Deque<QueuedCraft> queuedSteps = new ArrayDeque<>();
    private static ActiveCraft activeCraft;

    private CraftingExecutor() {
    }

    /**
     * Starts one execution of the first recipe compatible with the current crafting grid.
     * Planner code should prefer {@link #execute(CraftingStep)} so it retains its chosen recipe.
     */
    public static boolean execute(Item target) {
        Objects.requireNonNull(target, "target cannot be null");

        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }

        Optional<CraftingGrid> craftingGrid = CraftingGrid.current();
        if (craftingGrid.isEmpty()) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(level);
        Optional<RecipeDisplayEntry> recipe = RecipeIndex.recipesProducing(target).stream()
                .filter(entry -> craftingGrid.get().supports(entry, context))
                .findFirst();
        return recipe.map(entry -> execute(new CraftingStep(entry, target, 1))).orElse(false);
    }

    /**
     * Starts a server-synchronised execution of the supplied planner step.
     */
    public static boolean execute(CraftingStep step) {
        Objects.requireNonNull(step, "step cannot be null");
        return executeSteps(List.of(step));
    }

    /**
     * Executes every step in a planner result, including its final requested recipe.
     */
    public static boolean execute(CraftPlan plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        return executeSteps(plan.steps());
    }

    /**
     * Executes a plan and leaves its final crafted result on the player's cursor.
     * Dependency results still use quick-move so later recipes can use them.
     */
    public static boolean executeToCursor(CraftPlan plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        if (plan.steps().isEmpty()) {
            return false;
        }

        List<QueuedCraft> queuedCrafts = new ArrayList<>(plan.steps().size());
        for (int index = 0; index < plan.steps().size(); index++) {
            queuedCrafts.add(new QueuedCraft(
                    plan.steps().get(index),
                    index == plan.steps().size() - 1
            ));
        }
        return executeQueuedSteps(queuedCrafts);
    }

    private static boolean executeSteps(List<CraftingStep> steps) {
        return executeQueuedSteps(steps.stream()
                .map(step -> new QueuedCraft(step, false))
                .toList());
    }

    private static boolean executeQueuedSteps(List<QueuedCraft> steps) {
        if (activeCraft != null || !queuedSteps.isEmpty() || steps.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return false;
        }

        queuedSteps.addAll(steps);
        startNextStep(minecraft, menu);
        return true;
    }

    /**
     * Must run once per client tick. It advances only after the server updates the crafting menu.
     */
    public static void tick(Minecraft minecraft) {
        if (activeCraft == null) {
            return;
        }

        if (minecraft.player == null || minecraft.gameMode == null || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || menu.containerId != activeCraft.containerId()) {
            stop("the crafting grid was closed");
            return;
        }

        activeCraft.ticksWaiting(activeCraft.ticksWaiting() + 1);
        if (activeCraft.ticksWaiting() > UPDATE_TIMEOUT_TICKS) {
            stop("the server did not update the crafting table in time");
            return;
        }

        if (menu.getStateId() == activeCraft.expectedStateId()) {
            return;
        }

        switch (activeCraft.phase()) {
            case WAITING_FOR_PLACEMENT -> takeResult(minecraft, activeCraft, menu);
            case WAITING_FOR_CRAFT -> {
                activeCraft.remainingCrafts(activeCraft.remainingCrafts() - 1);
                if (activeCraft.remainingCrafts() == 0) {
                    LOGGER.info("Finished crafting {}", itemName(activeCraft.step().output()));
                    activeCraft = null;
                    if (!queuedSteps.isEmpty()) {
                        startNextStep(minecraft, menu);
                    }
                } else {
                    placeRecipe(minecraft, activeCraft, menu);
                }
            }
        }
    }

    public static boolean isExecuting() {
        return activeCraft != null || !queuedSteps.isEmpty();
    }

    private static void startNextStep(Minecraft minecraft, AbstractCraftingMenu menu) {
        QueuedCraft queuedCraft = queuedSteps.removeFirst();
        CraftingStep step = queuedCraft.step();
        activeCraft = new ActiveCraft(step, menu.containerId, step.crafts(), queuedCraft.takeResultToCursor());
        placeRecipe(minecraft, activeCraft, menu);
    }

    private static void placeRecipe(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        active.phase(Phase.WAITING_FOR_PLACEMENT);
        active.expectedStateId(menu.getStateId());
        active.ticksWaiting(0);
        minecraft.gameMode.handlePlaceRecipe(menu.containerId, active.step().recipe().id(), false);
    }

    private static void takeResult(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        if (!menu.getResultSlot().getItem().is(active.step().output())) {
            stop("the server could not place " + itemName(active.step().output()));
            return;
        }

        active.phase(Phase.WAITING_FOR_CRAFT);
        active.expectedStateId(menu.getStateId());
        active.ticksWaiting(0);
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                menu.getResultSlot().index,
                0,
                active.takeResultToCursor() ? ContainerInput.PICKUP : ContainerInput.QUICK_MOVE,
                minecraft.player
        );
    }

    private static void stop(String reason) {
        LOGGER.warn("Stopped crafting executor: {}", reason);
        activeCraft = null;
        queuedSteps.clear();
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private enum Phase {
        WAITING_FOR_PLACEMENT,
        WAITING_FOR_CRAFT
    }

    private record QueuedCraft(CraftingStep step, boolean takeResultToCursor) {
    }

    private static final class ActiveCraft {
        private final CraftingStep step;
        private final int containerId;
        private final boolean takeResultToCursor;
        private int remainingCrafts;
        private int expectedStateId;
        private int ticksWaiting;
        private Phase phase = Phase.WAITING_FOR_PLACEMENT;

        private ActiveCraft(CraftingStep step, int containerId, int remainingCrafts, boolean takeResultToCursor) {
            this.step = step;
            this.containerId = containerId;
            this.remainingCrafts = remainingCrafts;
            this.takeResultToCursor = takeResultToCursor;
        }

        private CraftingStep step() {
            return step;
        }

        private int containerId() {
            return containerId;
        }

        private boolean takeResultToCursor() {
            return takeResultToCursor;
        }

        private int remainingCrafts() {
            return remainingCrafts;
        }

        private void remainingCrafts(int remainingCrafts) {
            this.remainingCrafts = remainingCrafts;
        }

        private int expectedStateId() {
            return expectedStateId;
        }

        private void expectedStateId(int expectedStateId) {
            this.expectedStateId = expectedStateId;
        }

        private int ticksWaiting() {
            return ticksWaiting;
        }

        private void ticksWaiting(int ticksWaiting) {
            this.ticksWaiting = ticksWaiting;
        }

        private Phase phase() {
            return phase;
        }

        private void phase(Phase phase) {
            this.phase = phase;
        }
    }
}
