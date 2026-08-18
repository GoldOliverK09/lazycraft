package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.config.LazyCraftConfig;
import klumpler.lazycraft.client.config.LazyCraftConfigManager;
import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.RecipePlanner;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(RecipeBookComponent.class)
public class RecipeBookComponentMixin {
    @Shadow
    @Final
    private GhostSlots ghostSlots;

    @Shadow
    private RecipeDisplayId lastPlacedRecipe;

    @Shadow
    private RecipeCollection lastRecipeCollection;

    @Unique
    private long lazycraft$filterRevision;

    @Shadow
    private boolean isFiltering() {
        throw new AssertionError();
    }

    @Shadow
    private void updateCollections(boolean resetPage, boolean filtering) {
        throw new AssertionError();
    }

    @ModifyArg(
            method = "updateCollections",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;removeIf(Ljava/util/function/Predicate;)Z",
                    ordinal = 2
            ),
            index = 0
    )
    private Predicate<RecipeCollection> lazycraft$includeRecursiveCollections(
            Predicate<RecipeCollection> vanillaFilter
    ) {
        return collection -> vanillaFilter.test(collection)
                && !VisibleRecipeCraftability.hasRecursivelyCraftable(collection);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lazycraft$refreshRecursiveFilter(CallbackInfo ci) {
        long currentRevision = VisibleRecipeCraftability.filterRevision();
        if (currentRevision == lazycraft$filterRevision) {
            return;
        }

        lazycraft$filterRevision = currentRevision;
        if (isFiltering()) {
            updateCollections(false, true);
        }
    }

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
            if (lazycraft$craftPlacedVanillaRecipe(recipeCollection, recipe)) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (!config.recursiveRecipeBookCrafting) {
            return;
        }

        // The first click is handled by vanilla and displays the ghost recipe.
        // Further clicks on it confirm LazyCraft's recursive crafting action.
        if (!recipe.equals(lastPlacedRecipe) || !lazycraft$hasGhostRecipe()) {
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
                || !lazycraft$hasGhostRecipe()
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
    private boolean lazycraft$craftPlacedVanillaRecipe(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe
    ) {
        if (!recipe.equals(lastPlacedRecipe) || lazycraft$hasGhostRecipe()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof AbstractCraftingMenu menu)) {
            return false;
        }

        ItemStack result = menu.getResultSlot().getItem();
        RecipeDisplayEntry entry = lazycraft$findRecipeEntry(recipeCollection, recipe);
        if (result.isEmpty() || entry == null) {
            return false;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        ItemStack expectedResult = lazycraft$firstResult(entry, context);
        if (!ItemStack.isSameItemSameComponents(result, expectedResult)
                || result.getCount() != expectedResult.getCount()) {
            return false;
        }

        if (!menu.getCarried().isEmpty()) {
            return false;
        }

        return CraftingExecutor.takePlacedResultToInventory(result);
    }

    @Unique
    private void lazycraft$restoreGhostRecipe(RecipeDisplay recipe) {
        ((RecipeBookComponent<?>) (Object) this).fillGhostRecipe(recipe);
    }

    @Unique
    private boolean lazycraft$hasGhostRecipe() {
        return !((GhostSlotsAccessor) ghostSlots)
                .lazycraft$getIngredients()
                .isEmpty();
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
