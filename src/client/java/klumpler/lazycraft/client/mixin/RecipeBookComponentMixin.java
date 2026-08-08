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
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow
    @Final
    private GhostSlots ghostSlots;

    @Unique
    private RecipeDisplayId lazycraft$armedGhostRecipe;

    @Unique
    private RecipeCollection lazycraft$armedGhostCollection;

    @Invoker("fillGhostRecipe")
    protected abstract void lazycraft$restoreGhostRecipe(RecipeDisplay recipe);

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
            if (lazycraft$armRecipeIfNeeded(recipeCollection, recipe)) {
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
        if (lazycraft$armRecipeIfNeeded(recipeCollection, recipe)) {
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
                || lazycraft$armedGhostRecipe == null
                || lazycraft$armedGhostCollection == null
                || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)
                || slot != menu.getResultSlot()
                || !slot.getItem().isEmpty()) {
            return;
        }

        if (lazycraft$executePlan(lazycraft$armedGhostCollection, lazycraft$armedGhostRecipe, true)) {
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
        return entry.resultItems(context).stream()
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(ItemStack::getItem)
                .flatMap(RecipePlanner::plan)
                .filter(plan -> !plan.steps().isEmpty())
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

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        RecipeDisplayEntry entry = lazycraft$findRecipeEntry(recipeCollection, recipe);
        if (entry == null) {
            return false;
        }

        return entry.resultItems(context).stream()
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(stack -> new CraftingStep(entry, stack.getItem(), 1))
                .map(step -> {
                    Runnable restoreRecipe = () -> lazycraft$restoreVanillaRecipe(recipe);
                    if (!useMaxItems && CraftingExecutor.executePlaced(step, restoreRecipe)) {
                        return true;
                    }
                    return CraftingExecutor.execute(step, useMaxItems, restoreRecipe);
                })
                .orElse(false);
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
    private boolean lazycraft$armRecipeIfNeeded(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        if (recipe.equals(lazycraft$armedGhostRecipe)) {
            return false;
        }

        lazycraft$armedGhostRecipe = recipe;
        lazycraft$armedGhostCollection = recipeCollection;
        return true;
    }

    @Unique
    private RecipeDisplayEntry lazycraft$findRecipeEntry(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        return recipeCollection.getRecipes().stream()
                .filter(entry -> entry.id().equals(recipe))
                .findFirst()
                .orElse(null);
    }
}
