package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class RecipeBookCrafting {
    private RecipeBookCrafting() {
    }

    public static boolean tryPlaceRecipe(
            RecipeCollection recipeCollection,
            RecipeHolder<?> recipe,
            RecipeHolder<?> ghostRecipe,
            boolean hasGhostRecipe,
            boolean craftMaximum,
            Runnable clearGhostRecipe
    ) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        if (!config.recipeBookCrafting || CraftingExecutor.isExecuting()) {
            return false;
        }

        if (recipeCollection.isCraftable(recipe)) {
            return !hasGhostRecipe
                    && takePlacedResultsToInventory(recipeCollection, recipe, craftMaximum);
        }

        if (!config.recursiveRecipeBookCrafting
                || !hasGhostRecipe
                || !sameRecipe(recipe, ghostRecipe)
                || !executePlan(recipeCollection, recipe, false, craftMaximum)) {
            return false;
        }

        clearGhostRecipe.run();
        return true;
    }

    public static boolean tryTakeGhostResult(
            Slot slot,
            RecipeHolder<?> lastRecipe,
            RecipeCollection lastRecipeCollection,
            boolean hasGhostRecipe,
            boolean craftMaximum,
            Runnable clearGhostRecipe
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        LazyCraftConfig config = LazyCraftConfigManager.get();
        RecipeBookMenu<?, ?> menu = craftingMenu(minecraft.player);
        if (!config.recipeBookCrafting
                || !config.recursiveRecipeBookCrafting
                || CraftingExecutor.isExecuting()
                || !hasGhostRecipe
                || lastRecipe == null
                || lastRecipeCollection == null
                || minecraft.player == null
                || menu == null
                || slot != resultSlot(menu)
                || !slot.getItem().isEmpty()) {
            return false;
        }

        if (executePlan(lastRecipeCollection, lastRecipe, true, craftMaximum)) {
            clearGhostRecipe.run();
        }
        return true;
    }

    public static boolean executePlan(
            RecipeCollection recipeCollection,
            RecipeHolder<?> recipe,
            boolean takeResultToCursor,
            boolean craftMaximum
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        ItemStack result = recipe.value().getResultItem(recipeCollection.registryAccess());
        if (result.isEmpty()) {
            return false;
        }

        Runnable restoreRecipe = () -> placeRecipe(minecraft, recipe, craftMaximum);
        return (craftMaximum
                ? RecipePlanner.planMaximum(
                result.getItem(),
                recipe,
                result.getCount(),
                maximumOutputItems(result, takeResultToCursor)
        )
                : RecipePlanner.plan(result.getItem(), recipe))
                .map(plan -> takeResultToCursor
                        ? CraftingExecutor.executeToCursor(plan, restoreRecipe)
                        : CraftingExecutor.execute(plan, restoreRecipe))
                .orElse(false);
    }

    private static int maximumOutputItems(ItemStack result, boolean takeResultToCursor) {
        if (!takeResultToCursor) {
            return result.getMaxStackSize();
        }

        Minecraft minecraft = Minecraft.getInstance();
        RecipeBookMenu<?, ?> menu = craftingMenu(minecraft.player);
        if (menu == null) {
            return result.getMaxStackSize();
        }

        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return result.getMaxStackSize();
        }
        if (ItemStack.isSameItemSameComponents(carried, result)) {
            int cursorCapacity = carried.getMaxStackSize() - carried.getCount();
            if (cursorCapacity >= result.getCount()) {
                return cursorCapacity;
            }
        }
        return result.getMaxStackSize();
    }

    public static boolean takePlacedResultsToInventory(
            RecipeCollection recipeCollection,
            RecipeHolder<?> recipe,
            boolean craftMaximum
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        RecipeBookMenu<?, ?> menu = craftingMenu(minecraft.player);
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.gameMode == null
                || menu == null) {
            return false;
        }

        ItemStack result = resultSlot(menu).getItem();
        ItemStack expectedResult = recipe.value().getResultItem(recipeCollection.registryAccess());
        if (result.isEmpty()
                || !ItemStack.isSameItemSameComponents(result, expectedResult)
                || result.getCount() != expectedResult.getCount()
                || !menu.getCarried().isEmpty()) {
            return false;
        }

        Runnable restoreRecipe = () -> placeRecipe(minecraft, recipe, craftMaximum);
        return CraftingExecutor.takePlacedResultsToInventory(
                result,
                restoreRecipe,
                craftMaximum,
                recipe
        );
    }

    private static void placeRecipe(
            Minecraft minecraft,
            RecipeHolder<?> recipe,
            boolean craftMaximum
    ) {
        if (minecraft.player == null || minecraft.gameMode == null) {
            return;
        }
        RecipeBookMenu<?, ?> menu = craftingMenu(minecraft.player);
        if (menu == null) {
            return;
        }

        minecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe, craftMaximum);
    }

    private static RecipeBookMenu<?, ?> craftingMenu(Player player) {
        if (player == null) {
            return null;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return (RecipeBookMenu<?, ?>) menu;
        }
        return null;
    }

    private static Slot resultSlot(RecipeBookMenu<?, ?> menu) {
        return menu.slots.get(menu.getResultSlotIndex());
    }

    private static boolean sameRecipe(RecipeHolder<?> first, RecipeHolder<?> second) {
        return first != null && second != null && first.id().equals(second.id());
    }
}
