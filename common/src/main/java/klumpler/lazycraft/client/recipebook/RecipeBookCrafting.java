package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.function.Consumer;

public final class RecipeBookCrafting {
    private RecipeBookCrafting() {
    }

    public static boolean tryPlaceRecipe(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            RecipeDisplayId lastPlacedRecipe,
            boolean hasGhostRecipe,
            Consumer<RecipeDisplay> restoreGhostRecipe,
            Runnable clearGhostRecipe
    ) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        if (!config.recipeBookCrafting || CraftingExecutor.isExecuting()) {
            return false;
        }

        if (recipeCollection.isCraftable(recipe)) {
            return recipe.equals(lastPlacedRecipe)
                    && !hasGhostRecipe
                    && takePlacedResultToInventory(recipeCollection, recipe);
        }
        if (!config.recursiveRecipeBookCrafting
                || !recipe.equals(lastPlacedRecipe)
                || !hasGhostRecipe
                || !executePlan(recipeCollection, recipe, false, restoreGhostRecipe)) {
            return false;
        }

        clearGhostRecipe.run();
        return true;
    }

    public static boolean tryTakeGhostResult(
            Slot slot,
            RecipeDisplayId lastPlacedRecipe,
            RecipeCollection lastRecipeCollection,
            boolean hasGhostRecipe,
            Consumer<RecipeDisplay> restoreGhostRecipe,
            Runnable clearGhostRecipe
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        LazyCraftConfig config = LazyCraftConfigManager.get();
        if (!config.recipeBookCrafting
                || !config.recursiveRecipeBookCrafting
                || CraftingExecutor.isExecuting()
                || !hasGhostRecipe
                || lastPlacedRecipe == null
                || lastRecipeCollection == null
                || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || slot != menu.getResultSlot()
                || !slot.getItem().isEmpty()
                || !executePlan(
                lastRecipeCollection,
                lastPlacedRecipe,
                true,
                restoreGhostRecipe
        )) {
            return false;
        }

        clearGhostRecipe.run();
        return true;
    }

    public static boolean executePlan(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean takeResultToCursor,
            Consumer<RecipeDisplay> restoreGhostRecipe
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        RecipeDisplayEntry entry = findRecipeEntry(recipeCollection, recipe);
        if (entry == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        ItemStack result = firstResult(entry, context);
        if (result.isEmpty()) {
            return false;
        }

        Runnable restoreRecipe = () -> restoreGhostRecipe.accept(entry.display());
        return RecipePlanner.plan(result.getItem())
                .map(plan -> takeResultToCursor
                        ? CraftingExecutor.executeToCursor(plan, restoreRecipe)
                        : CraftingExecutor.execute(plan, restoreRecipe))
                .orElse(false);
    }

    public static boolean takePlacedResultToInventory(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return false;
        }

        ItemStack result = menu.getResultSlot().getItem();
        RecipeDisplayEntry entry = findRecipeEntry(recipeCollection, recipe);
        if (result.isEmpty() || entry == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        ItemStack expectedResult = firstResult(entry, context);
        if (!ItemStack.isSameItemSameComponents(result, expectedResult)
                || result.getCount() != expectedResult.getCount()
                || !menu.getCarried().isEmpty()) {
            return false;
        }

        return CraftingExecutor.takePlacedResultToInventory(result);
    }

    private static RecipeDisplayEntry findRecipeEntry(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        for (RecipeDisplayEntry entry : recipeCollection.getRecipes()) {
            if (entry.id().equals(recipe)) {
                return entry;
            }
        }
        return null;
    }

    private static ItemStack firstResult(RecipeDisplayEntry entry, ContextMap context) {
        for (ItemStack result : entry.resultItems(context)) {
            if (!result.isEmpty()) {
                return result;
            }
        }
        return ItemStack.EMPTY;
    }
}
