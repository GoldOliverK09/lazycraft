package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.CraftingStep;
import klumpler.lazycraft.client.planner.RecipePlanner;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Unique
    private RecipeDisplayId lazycraft$armedGhostRecipe;

    @Unique
    private RecipeCollection lazycraft$armedGhostCollection;

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostRecipePlan(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean useMaxItems,
            CallbackInfoReturnable<Boolean> cir
    ) {
        LazyCraftConfig config = AutoConfig.getConfigHolder(LazyCraftConfig.class).getConfig();
        if (!config.recipeBookCrafting) {
            return;
        }

        if (CraftingExecutor.isExecuting()) {
            return;
        }

        if (recipeCollection.isCraftable(recipe)) {
            // The first click is handled by vanilla and places the recipe.
            // Further clicks on it use LazyCraft's direct crafting shortcut.
            if (!recipe.equals(lazycraft$armedGhostRecipe)) {
                lazycraft$armedGhostRecipe = recipe;
                lazycraft$armedGhostCollection = recipeCollection;
                return;
            }

            if (lazycraft$executeVanillaRecipe(recipeCollection, recipe)) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (!config.recursiveRecipeBookCrafting) {
            return;
        }

        // The first click is handled by vanilla and displays the ghost recipe.
        // Further clicks on it confirm LazyCraft's recursive crafting action.
        if (!recipe.equals(lazycraft$armedGhostRecipe)) {
            lazycraft$armedGhostRecipe = recipe;
            lazycraft$armedGhostCollection = recipeCollection;
            return;
        }

        if (lazycraft$executePlan(recipeCollection, recipe, false)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostOutput(Slot slot, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        LazyCraftConfig config = AutoConfig.getConfigHolder(LazyCraftConfig.class).getConfig();
        if (!config.recipeBookCrafting
                || !config.recursiveRecipeBookCrafting
                || CraftingExecutor.isExecuting()
                || lazycraft$armedGhostRecipe == null
                || lazycraft$armedGhostCollection == null
                || !(minecraft.player != null && minecraft.player.containerMenu instanceof CraftingMenu menu)
                || slot != menu.getResultSlot()
                || !slot.getItem().isEmpty()) {
            return;
        }

        if (lazycraft$executePlan(lazycraft$armedGhostCollection, lazycraft$armedGhostRecipe, true)) {
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

        RecipeDisplayEntry entry = recipeCollection.getRecipes().stream()
                .filter(recipeEntry -> recipeEntry.id().equals(recipe))
                .findFirst()
                .orElse(null);
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
                .filter(plan -> takeResultToCursor
                        ? CraftingExecutor.executeToCursor(plan)
                        : CraftingExecutor.execute(plan))
                .isPresent();
    }

    @Unique
    private boolean lazycraft$executeVanillaRecipe(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        return recipeCollection.getRecipes().stream()
                .filter(recipeEntry -> recipeEntry.id().equals(recipe))
                .findFirst()
                .flatMap(recipeEntry -> recipeEntry.resultItems(context).stream()
                        .filter(stack -> !stack.isEmpty())
                        .findFirst()
                        .map(stack -> new CraftingStep(recipeEntry, stack.getItem(), 1)))
                .map(CraftingExecutor::execute)
                .orElse(false);
    }
}
