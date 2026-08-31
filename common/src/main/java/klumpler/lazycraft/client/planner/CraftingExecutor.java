package klumpler.lazycraft.client.planner;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.*;

import java.util.*;

public final class CraftingExecutor {
    private static final Deque<QueuedCraft> queuedCrafts = new ArrayDeque<>();
    private static ActiveCraft activeCraft;
    private static DirectCraft directCraft;
    private static Runnable completionCallback;
    private static int executionUpdateTimeoutTicks;
    private static int executionStepDelayTicks;

    private CraftingExecutor() {
    }

    public static boolean execute(CraftPlan plan, Runnable onComplete) {
        return executePlan(plan, onComplete, false);
    }

    public static boolean executeToCursor(CraftPlan plan, Runnable onComplete) {
        return executePlan(plan, onComplete, true);
    }

    public static boolean takePlacedResultsToInventory(
            ItemStack expectedResult,
            Runnable onComplete
    ) {
        Objects.requireNonNull(expectedResult, "expectedResult cannot be null");
        Objects.requireNonNull(onComplete, "onComplete cannot be null");
        if (isExecuting()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return false;
        }

        ItemStack result = menu.getResultSlot().getItem();
        if (result.isEmpty()
                || !ItemStack.isSameItemSameComponents(result, expectedResult)
                || result.getCount() != expectedResult.getCount()) {
            return false;
        }

        ResultDestination destination;
        if (availableInventoryCapacity(minecraft, result) >= result.getCount()) {
            destination = ResultDestination.INVENTORY;
        } else if (canCursorAccept(menu.getCarried(), result)) {
            destination = ResultDestination.CURSOR;
        } else {
            return false;
        }

        executionUpdateTimeoutTicks = LazyCraftConfigManager.get().serverUpdateTimeoutTicks;
        directCraft = new DirectCraft(
                menu.containerId,
                result.copy(),
                destination,
                countInventoryStack(minecraft, result),
                menu.getCarried().copy(),
                menu.getStateId(),
                onComplete
        );
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                menu.getResultSlot().index,
                0,
                destination == ResultDestination.INVENTORY
                        ? ContainerInput.QUICK_MOVE
                        : ContainerInput.PICKUP,
                minecraft.player
        );
        return true;
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
                    takeFinalResultToCursor && index == lastStepIndex
            ));
        }
        return executeQueuedCrafts(craftsToQueue, onComplete);
    }

    private static boolean executeQueuedCrafts(List<QueuedCraft> craftsToQueue, Runnable onComplete) {
        if (directCraft != null
                || activeCraft != null
                || !queuedCrafts.isEmpty()
                || craftsToQueue.isEmpty()) {
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
        queuedCrafts.addAll(mergeAdjacentCrafts(craftsToQueue));
        completionCallback = onComplete;
        startNextCraft(minecraft, menu);
        return true;
    }

    private static List<QueuedCraft> mergeAdjacentCrafts(List<QueuedCraft> crafts) {
        if (crafts.size() < 2) {
            return crafts;
        }

        List<QueuedCraft> merged = new ArrayList<>(crafts.size());
        for (QueuedCraft craft : crafts) {
            if (!merged.isEmpty()) {
                int previousIndex = merged.size() - 1;
                QueuedCraft combined = merged.get(previousIndex).mergeWithOrNull(craft);
                if (combined != null) {
                    merged.set(previousIndex, combined);
                    continue;
                }
            }
            merged.add(craft);
        }
        return merged;
    }

    public static void tick(Minecraft minecraft) {
        if (directCraft != null) {
            tickDirectCraft(minecraft);
            return;
        }

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
            case WAITING_FOR_PLACEMENT -> handleInitialPlacement(minecraft, activeCraft, menu);
            case WAITING_FOR_BATCH_PLACEMENT -> handleBatchPlacement(minecraft, activeCraft, menu);
            case WAITING_FOR_CRAFT -> {
                if (hasStoredCraftResult(minecraft, activeCraft, menu)) {
                    finishCrafts(minecraft, activeCraft, menu, 1);
                }
            }
            case WAITING_FOR_BATCH_CRAFT -> finishBatchCraft(minecraft, activeCraft, menu);
        }
    }

    public static boolean isExecuting() {
        return directCraft != null || activeCraft != null || !queuedCrafts.isEmpty();
    }

    private static void tickDirectCraft(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || menu.containerId != directCraft.containerId) {
            stop("the crafting grid was closed");
            return;
        }

        directCraft.ticksWaiting++;
        if (directCraft.ticksWaiting > executionUpdateTimeoutTicks) {
            stop("the server did not update the direct craft in time");
            return;
        }
        if (menu.getStateId() == directCraft.expectedStateId) {
            return;
        }

        if (directCraft.destination == ResultDestination.INVENTORY) {
            long storedItems = countInventoryStack(minecraft, directCraft.result);
            if (storedItems < directCraft.inventoryItemsBefore + directCraft.result.getCount()) {
                return;
            }
            finishDirectCraft("inventory");
            return;
        }

        ItemStack carried = menu.getCarried();
        if (!ItemStack.isSameItemSameComponents(carried, directCraft.result)
                || carried.getCount() != directCraft.carriedBefore.getCount()
                + directCraft.result.getCount()) {
            return;
        }
        finishDirectCraft("cursor");
    }

    private static void finishDirectCraft(String destination) {
        Runnable callback = directCraft.onComplete;
        LazyCraft.LOGGER.debug(
                "Finished direct crafting {} to {}",
                itemName(directCraft.result.getItem()),
                destination
        );
        directCraft = null;
        callback.run();
    }

    private static void startNextCraft(Minecraft minecraft, AbstractCraftingMenu menu) {
        QueuedCraft nextCraft = queuedCrafts.removeFirst();
        CraftingStep step = nextCraft.step();
        activeCraft = new ActiveCraft(
                step,
                menu.containerId,
                step.crafts(),
                nextCraft.takeResultToCursor()
        );
        placeRecipe(minecraft, activeCraft, menu);
    }

    private static void placeRecipe(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        active.expectedStagedCrafts = 1;
        active.batchTargetCrafts = 1;
        active.pendingBatchCrafts = 0;
        sendPlacement(minecraft, active, menu, Phase.WAITING_FOR_PLACEMENT);
    }

    private static void handleInitialPlacement(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        if (!hasExpectedResult(active, menu)) {
            return;
        }

        if (!active.batchEnabled) {
            takeResult(minecraft, active, menu);
            return;
        }

        int stagedCrafts = uniformStagedCrafts(menu);
        if (stagedCrafts < 1) {
            return;
        }
        if (stagedCrafts > 1) {
            stop("the server placed an unexpected initial recipe amount");
            return;
        }

        if (hasCraftingRemainder(menu)) {
            active.batchEnabled = false;
            takeResult(minecraft, active, menu);
            return;
        }

        int targetCrafts = calculateBatchTarget(minecraft, active, menu);
        if (targetCrafts <= 1) {
            active.batchEnabled = false;
            takeResult(minecraft, active, menu);
            return;
        }

        active.batchTargetCrafts = targetCrafts;
        active.expectedStagedCrafts = 2;
        sendPlacement(minecraft, active, menu, Phase.WAITING_FOR_BATCH_PLACEMENT);
    }

    private static void handleBatchPlacement(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        if (!hasExpectedResult(active, menu)) {
            return;
        }

        int stagedCrafts = uniformStagedCrafts(menu);
        if (stagedCrafts < active.expectedStagedCrafts) {
            return;
        }
        if (stagedCrafts > active.expectedStagedCrafts) {
            stop("the server staged an unexpected recipe amount");
            return;
        }
        if (hasCraftingRemainder(menu)) {
            stop("the staged recipe changed to an ingredient with a crafting remainder");
            return;
        }

        active.batchTargetCrafts = Math.min(
                active.batchTargetCrafts,
                exactIngredientLimit(minecraft, menu)
        );
        if (active.expectedStagedCrafts < active.batchTargetCrafts) {
            active.expectedStagedCrafts++;
            sendPlacement(minecraft, active, menu, Phase.WAITING_FOR_BATCH_PLACEMENT);
            return;
        }

        takeBatchResult(minecraft, active, menu);
    }

    private static void sendPlacement(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu,
            Phase phase
    ) {
        waitForMenuUpdate(active, phase, menu);
        assert minecraft.gameMode != null;
        minecraft.gameMode.handlePlaceRecipe(
                menu.containerId,
                active.step.recipe().id(),
                false
        );
    }

    private static void takeResult(Minecraft minecraft, ActiveCraft active, AbstractCraftingMenu menu) {
        if (!hasExpectedResult(active, menu)) {
            stop("the server could not place " + itemName(active.step.output()));
            return;
        }

        ItemStack result = menu.getResultSlot().getItem().copy();
        ResultDestination destination = chooseResultDestination(
                minecraft,
                menu,
                result,
                active.takeResultToCursor
        );
        if (destination == null) {
            stop("there was no cursor or inventory space for " + itemName(active.step.output()));
            return;
        }

        active.resultDestination = destination;
        active.singleCraftOutput = result;
        active.carriedBeforeCraft = menu.getCarried().copy();
        active.outputItemsBeforeCraft = countInventoryStack(minecraft, result);
        waitForMenuUpdate(active, Phase.WAITING_FOR_CRAFT, menu);
        assert minecraft.gameMode != null;
        assert minecraft.player != null;
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                menu.getResultSlot().index,
                0,
                destination == ResultDestination.CURSOR
                        ? ContainerInput.PICKUP
                        : ContainerInput.QUICK_MOVE,
                minecraft.player
        );
    }

    private static ResultDestination chooseResultDestination(
            Minecraft minecraft,
            AbstractCraftingMenu menu,
            ItemStack result,
            boolean preferCursor
    ) {
        boolean cursorAvailable = canCursorAccept(menu.getCarried(), result);
        boolean inventoryAvailable = availableInventoryCapacity(minecraft, result)
                >= result.getCount();

        if (preferCursor) {
            if (cursorAvailable) {
                return ResultDestination.CURSOR;
            }
            if (inventoryAvailable) {
                return ResultDestination.INVENTORY;
            }
        } else {
            if (inventoryAvailable) {
                return ResultDestination.INVENTORY;
            }
            if (cursorAvailable) {
                return ResultDestination.CURSOR;
            }
        }
        return null;
    }

    private static boolean canCursorAccept(ItemStack carried, ItemStack result) {
        if (carried.isEmpty()) {
            return true;
        }
        return ItemStack.isSameItemSameComponents(carried, result)
                && result.getCount() <= carried.getMaxStackSize() - carried.getCount();
    }

    private static boolean hasStoredCraftResult(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        if (active.resultDestination == ResultDestination.INVENTORY) {
            return countInventoryStack(minecraft, active.singleCraftOutput)
                    >= active.outputItemsBeforeCraft + active.singleCraftOutput.getCount();
        }
        if (active.resultDestination != ResultDestination.CURSOR) {
            return false;
        }

        ItemStack carried = menu.getCarried();
        return ItemStack.isSameItemSameComponents(carried, active.singleCraftOutput)
                && carried.getCount() == active.carriedBeforeCraft.getCount()
                + active.singleCraftOutput.getCount();
    }

    private static void takeBatchResult(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        ItemStack result = menu.getResultSlot().getItem();
        if (result.isEmpty() || !result.is(active.step.output())) {
            stop("the server could not finish staging " + itemName(active.step.output()));
            return;
        }

        active.pendingBatchCrafts = active.expectedStagedCrafts;
        active.batchOutput = result.copy();
        active.outputItemsBeforeBatch = countInventoryStack(minecraft, active.batchOutput);
        active.outputItemsPerCraft = result.getCount();
        waitForMenuUpdate(active, Phase.WAITING_FOR_BATCH_CRAFT, menu);
        assert minecraft.gameMode != null;
        assert minecraft.player != null;
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                menu.getResultSlot().index,
                0,
                ContainerInput.QUICK_MOVE,
                minecraft.player
        );
    }

    private static void finishBatchCraft(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        long outputIncrease = countInventoryStack(minecraft, active.batchOutput)
                - active.outputItemsBeforeBatch;
        long expectedIncrease = Math.multiplyExact(
                (long) active.pendingBatchCrafts,
                active.outputItemsPerCraft
        );
        if (outputIncrease < expectedIncrease) {
            return;
        }
        if (outputIncrease > expectedIncrease) {
            stop("the server completed an unexpected number of staged crafts");
            return;
        }

        finishCrafts(minecraft, active, menu, active.pendingBatchCrafts);
    }

    private static void finishCrafts(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu,
            int completedCrafts
    ) {
        active.remainingCrafts -= completedCrafts;
        if (active.remainingCrafts > 0) {
            placeRecipe(minecraft, active, menu);
            return;
        }

        LazyCraft.LOGGER.debug("Finished crafting {}", itemName(active.step.output()));
        if (!queuedCrafts.isEmpty()) {
            if (executionStepDelayTicks == 0) {
                startNextCraft(minecraft, menu);
            } else {
                active.phase = Phase.WAITING_TO_START_NEXT_STEP;
                active.ticksWaiting = 0;
            }
        } else {
            activeCraft = null;
            runCompletionCallback();
        }
    }

    private static boolean hasExpectedResult(
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        return menu.getResultSlot().getItem().is(active.step.output());
    }

    private static int uniformStagedCrafts(AbstractCraftingMenu menu) {
        int stagedCrafts = 0;
        for (Slot slot : menu.getInputGridSlots()) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (stagedCrafts == 0) {
                stagedCrafts = stack.getCount();
            } else if (stack.getCount() != stagedCrafts) {
                return -1;
            }
        }
        return stagedCrafts;
    }

    private static int calculateBatchTarget(
            Minecraft minecraft,
            ActiveCraft active,
            AbstractCraftingMenu menu
    ) {
        ItemStack result = menu.getResultSlot().getItem();
        long outputCapacity = availableInventoryCapacity(minecraft, result);
        long craftsThatFit = outputCapacity / result.getCount();
        return (int) Math.min(
                Math.min((long) active.remainingCrafts, exactIngredientLimit(minecraft, menu)),
                craftsThatFit
        );
    }

    private static int exactIngredientLimit(Minecraft minecraft, AbstractCraftingMenu menu) {
        List<Slot> inputSlots = menu.getInputGridSlots();
        int limit = inputStackLimit(menu);
        for (int index = 0; index < inputSlots.size(); index++) {
            ItemStack ingredient = inputSlots.get(index).getItem();
            if (ingredient.isEmpty() || appearedEarlier(inputSlots, index, ingredient)) {
                continue;
            }

            int ingredientsPerCraft = 0;
            long available = 0;
            for (Slot slot : inputSlots) {
                ItemStack stack = slot.getItem();
                if (ItemStack.isSameItemSameComponents(stack, ingredient)) {
                    ingredientsPerCraft++;
                    available += stack.getCount();
                }
            }
            assert minecraft.player != null;
            for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
                if (ItemStack.isSameItemSameComponents(stack, ingredient)) {
                    available += stack.getCount();
                }
            }
            limit = Math.min(limit, (int) Math.min(Integer.MAX_VALUE, available / ingredientsPerCraft));
        }
        return limit;
    }

    private static boolean appearedEarlier(List<Slot> slots, int endIndex, ItemStack ingredient) {
        for (int index = 0; index < endIndex; index++) {
            if (ItemStack.isSameItemSameComponents(slots.get(index).getItem(), ingredient)) {
                return true;
            }
        }
        return false;
    }

    private static int inputStackLimit(AbstractCraftingMenu menu) {
        int limit = Integer.MAX_VALUE;
        for (Slot slot : menu.getInputGridSlots()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                limit = Math.min(limit, stack.getMaxStackSize());
            }
        }
        return limit == Integer.MAX_VALUE ? 1 : limit;
    }

    private static boolean hasCraftingRemainder(AbstractCraftingMenu menu) {
        CraftingInput input = CraftingInput.of(
                menu.getGridWidth(),
                menu.getGridHeight(),
                menu.getInputGridSlots().stream().map(Slot::getItem).toList()
        );
        return CraftingRecipe.defaultCraftingReminder(input).stream()
                .anyMatch(stack -> !stack.isEmpty());
    }

    private static boolean recipeMayReturnIngredients(RecipeDisplayEntry recipe) {
        if (recipeDisplayHasRemainder(recipe.display())) {
            return true;
        }

        Optional<List<Ingredient>> requirements = recipe.craftingRequirements();
        if (requirements.isEmpty()) {
            return true;
        }
        for (Ingredient ingredient : requirements.get()) {
            if (slotDisplayHasRemainder(ingredient.display())) {
                return true;
            }
        }
        return false;
    }

    private static boolean recipeDisplayHasRemainder(RecipeDisplay display) {
        List<SlotDisplay> ingredients;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            ingredients = shaped.ingredients();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            ingredients = shapeless.ingredients();
        } else {
            return true;
        }

        for (SlotDisplay ingredient : ingredients) {
            if (slotDisplayHasRemainder(ingredient)) {
                return true;
            }
        }
        return false;
    }

    private static boolean slotDisplayHasRemainder(SlotDisplay display) {
        if (display instanceof SlotDisplay.WithRemainder) {
            return true;
        }
        if (display instanceof SlotDisplay.Composite(List<SlotDisplay> contents)) {
            for (SlotDisplay choice : contents) {
                if (slotDisplayHasRemainder(choice)) {
                    return true;
                }
            }
            return false;
        }
        if (display instanceof SlotDisplay.OnlyWithComponent filtered) {
            return slotDisplayHasRemainder(filtered.source());
        }
        if (display instanceof SlotDisplay.WithAnyPotion(SlotDisplay display1)) {
            return slotDisplayHasRemainder(display1);
        }
        return !(display instanceof SlotDisplay.Empty
                || display instanceof SlotDisplay.ItemSlotDisplay
                || display instanceof SlotDisplay.ItemStackSlotDisplay
                || display instanceof SlotDisplay.TagSlotDisplay
                || display instanceof SlotDisplay.AnyFuel);
    }

    private static long availableInventoryCapacity(Minecraft minecraft, ItemStack result) {
        long capacity = 0;
        assert minecraft.player != null;
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                capacity += result.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, result)) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    private static long countInventoryStack(Minecraft minecraft, ItemStack expected) {
        long count = 0;
        assert minecraft.player != null;
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (ItemStack.isSameItemSameComponents(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void waitForMenuUpdate(ActiveCraft active, Phase phase, AbstractCraftingMenu menu) {
        active.phase = phase;
        active.expectedStateId = menu.getStateId();
        active.ticksWaiting = 0;
    }

    private static void stop(String reason) {
        LazyCraft.LOGGER.warn("Stopped crafting executor: {}", reason);
        if (reason.contains("did not update")) {
            LazyCraft.LOGGER.warn("Maybe try increasing the crafting execution delay or update timeout?");
        }
        Runnable callback = directCraft != null
                ? directCraft.onComplete
                : completionCallback;
        directCraft = null;
        activeCraft = null;
        queuedCrafts.clear();
        completionCallback = null;
        if (callback != null) {
            callback.run();
        }
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
        WAITING_FOR_BATCH_PLACEMENT,
        WAITING_FOR_CRAFT,
        WAITING_FOR_BATCH_CRAFT,
        WAITING_TO_START_NEXT_STEP
    }

    private enum ResultDestination {
        CURSOR,
        INVENTORY
    }

    private static final class DirectCraft {
        private final int containerId;
        private final ItemStack result;
        private final ResultDestination destination;
        private final long inventoryItemsBefore;
        private final ItemStack carriedBefore;
        private final int expectedStateId;
        private final Runnable onComplete;
        private int ticksWaiting;

        private DirectCraft(
                int containerId,
                ItemStack result,
                ResultDestination destination,
                long inventoryItemsBefore,
                ItemStack carriedBefore,
                int expectedStateId,
                Runnable onComplete
        ) {
            this.containerId = containerId;
            this.result = result;
            this.destination = destination;
            this.inventoryItemsBefore = inventoryItemsBefore;
            this.carriedBefore = carriedBefore;
            this.expectedStateId = expectedStateId;
            this.onComplete = onComplete;
        }
    }

    private record QueuedCraft(
            CraftingStep step,
            boolean takeResultToCursor
    ) {
        private QueuedCraft mergeWithOrNull(QueuedCraft next) {
            if (takeResultToCursor
                    || next.takeResultToCursor
                    || step.output() != next.step.output()
                    || !step.recipe().id().equals(next.step.recipe().id())
                    || step.crafts() > Integer.MAX_VALUE - next.step.crafts()) {
                return null;
            }

            return new QueuedCraft(
                    new CraftingStep(
                            step.recipe(),
                            step.output(),
                            step.crafts() + next.step.crafts()
                    ),
                    false
            );
        }
    }

    private static final class ActiveCraft {
        private final CraftingStep step;
        private final int containerId;
        private final boolean takeResultToCursor;
        private boolean batchEnabled;
        private int remainingCrafts;
        private int expectedStagedCrafts = 1;
        private int batchTargetCrafts = 1;
        private int pendingBatchCrafts;
        private ItemStack batchOutput = ItemStack.EMPTY;
        private long outputItemsBeforeBatch;
        private int outputItemsPerCraft;
        private ResultDestination resultDestination;
        private ItemStack singleCraftOutput = ItemStack.EMPTY;
        private ItemStack carriedBeforeCraft = ItemStack.EMPTY;
        private long outputItemsBeforeCraft;
        private int expectedStateId;
        private int ticksWaiting;
        private Phase phase = Phase.WAITING_FOR_PLACEMENT;

        private ActiveCraft(
                CraftingStep step,
                int containerId,
                int remainingCrafts,
                boolean takeResultToCursor
        ) {
            this.step = step;
            this.containerId = containerId;
            this.remainingCrafts = remainingCrafts;
            this.takeResultToCursor = takeResultToCursor;
            this.batchEnabled = remainingCrafts > 1
                    && !takeResultToCursor
                    && !recipeMayReturnIngredients(step.recipe());
        }
    }
}
