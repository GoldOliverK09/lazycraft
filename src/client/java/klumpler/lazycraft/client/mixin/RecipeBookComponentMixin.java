package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.CraftingStep;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.GhostSlots;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public class RecipeBookComponentMixin {
    @Shadow
    @Final
    private GhostSlots ghostSlots;

    @Shadow
    private RecipeDisplayId lastPlacedRecipe;

    @Shadow
    private RecipeCollection lastRecipeCollection;

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostRecipePlan(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean useMaxItems,
            CallbackInfoReturnable<Boolean> cir
    ) {
        LazyCraftConfig config = LazyCraftConfigManager.get();
        if (!config.recipeBookCrafting || CraftingExecutor.isExecuting()) {
            return;
        }

        if (recipeCollection.isCraftable(recipe)) {
            // The first click is handled by vanilla and places the recipe.
            // Further clicks on it use LazyCraft's direct crafting shortcut.
            if (!recipe.equals(lastPlacedRecipe)) {
                return;
            }

            if (lazycraft$executeVanillaRecipe(recipeCollection, recipe, useMaxItems)) {
                ghostSlots.clear();
                cir.setReturnValue(true);
            }
            return;
        }

        if (!config.recursiveRecipeBookCrafting) {
            return;
        }

        // The first click is handled by vanilla and displays the ghost recipe.
        // Further clicks on it confirm LazyCraft's recursive crafting action.
        if (!recipe.equals(lastPlacedRecipe)) {
            return;
        }

        if (lazycraft$executePlan(recipeCollection, recipe, false)) {
            ghostSlots.clear();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostOutput(Slot slot, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        LazyCraftConfig config = LazyCraftConfigManager.get();
        if (!config.recipeBookCrafting
                || !config.recursiveRecipeBookCrafting
                || CraftingExecutor.isExecuting()
                || lastPlacedRecipe == null
                || lastRecipeCollection == null
                || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || slot != menu.getResultSlot()
                || !slot.getItem().isEmpty()) {
            return;
        }

        if (lazycraft$executePlan(lastRecipeCollection, lastPlacedRecipe, true)) {
            ghostSlots.clear();
            ci.cancel();
        }
    }

    @Unique
    private boolean lazycraft$executePlan(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean takeResultToCursor
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        RecipeDisplayEntry entry = lazycraft$findRecipeEntry(recipeCollection, recipe);
        if (entry == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        ItemStack result = lazycraft$firstResult(entry, context);
        if (result.isEmpty()) {
            return false;
        }

        return RecipePlanner.plan(result.getItem())
                .map(plan -> takeResultToCursor
                        ? CraftingExecutor.executeToCursor(plan, () -> lazycraft$restoreGhostRecipe(entry.display()))
                        : CraftingExecutor.execute(plan, () -> lazycraft$restoreGhostRecipe(entry.display())))
                .orElse(false);
    }

    @Unique
    private boolean lazycraft$executeVanillaRecipe(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean useMaxItems
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        RecipeDisplayEntry entry = lazycraft$findRecipeEntry(recipeCollection, recipe);
        if (entry == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        ItemStack result = lazycraft$firstResult(entry, context);
        if (result.isEmpty()) {
            return false;
        }

        CraftingStep step = new CraftingStep(entry, result.getItem(), 1);
        Runnable restoreRecipe = () -> lazycraft$restoreVanillaRecipe(recipe);
        if (!useMaxItems && CraftingExecutor.executePlaced(step, restoreRecipe)) {
            return true;
        }
        return CraftingExecutor.execute(step, useMaxItems, restoreRecipe);
    }

    @Unique
    private void lazycraft$restoreVanillaRecipe(RecipeDisplayId recipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return;
        }

        // The server replaces this with a ghost recipe when another set cannot be placed.
        minecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
    }

    @Unique
    private void lazycraft$restoreGhostRecipe(RecipeDisplay recipe) {
        ((RecipeBookComponent) (Object) this).fillGhostRecipe(recipe);
    }

    @Unique
    private RecipeDisplayEntry lazycraft$findRecipeEntry(
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

    @Unique
    private ItemStack lazycraft$firstResult(RecipeDisplayEntry entry, ContextMap context) {
        for (ItemStack result : entry.resultItems(context)) {
            if (!result.isEmpty()) {
                return result;
            }
        }
        return ItemStack.EMPTY;
    }
}
