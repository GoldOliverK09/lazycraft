package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.RecipeBookComponentExtension;
import klumpler.lazycraft.client.recipebook.RecipeBookCrafting;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.screens.recipebook.GhostSlots;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
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
public class RecipeBookComponentMixin implements RecipeBookComponentExtension {
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
    private void updateCollections(boolean resetPage, boolean isFiltering) {
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
        return VisibleRecipeCraftability.includeRecursiveCollections(vanillaFilter);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lazycraft$refreshRecursiveFilter(CallbackInfo ci) {
        lazycraft$filterRevision = VisibleRecipeCraftability.refreshFilter(
                lazycraft$filterRevision,
                this::isFiltering,
                () -> updateCollections(false, true)
        );
    }

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostRecipePlan(
            RecipeCollection recipeCollection,
            RecipeDisplayId recipe,
            boolean useMaxItems,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (RecipeBookCrafting.tryPlaceRecipe(
                recipeCollection,
                recipe,
                lastPlacedRecipe,
                lazycraft$hasGhostRecipe(),
                useMaxItems,
                this::lazycraft$restoreGhostRecipe,
                ghostSlots::clear
        )) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public boolean lazycraft$tryTakeGhostResult(Slot slot, ContainerInput input) {
        return RecipeBookCrafting.tryTakeGhostResult(
                slot,
                lastPlacedRecipe,
                lastRecipeCollection,
                lazycraft$hasGhostRecipe(),
                input == ContainerInput.QUICK_MOVE,
                this::lazycraft$restoreGhostRecipe,
                ghostSlots::clear
        );
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
}
