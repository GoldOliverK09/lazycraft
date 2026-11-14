package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.RecipeBookComponentExtension;
import klumpler.lazycraft.client.recipebook.RecipeBookCrafting;
import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.GhostRecipe;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeHolder;
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
public abstract class RecipeBookComponentMixin implements RecipeBookComponentExtension {
    @Shadow
    @Final
    protected GhostRecipe ghostRecipe;
    @Shadow
    protected RecipeBookMenu<?, ?> menu;
    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;
    @Shadow
    @Final
    private ClientRecipeBook book;

    @Unique
    private long lazycraft$filterRevision;

    @Shadow
    private void updateCollections(boolean resetPage) {
        throw new AssertionError();
    }

    @ModifyArg(
            method = "updateCollections",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;removeIf(Ljava/util/function/Predicate;)Z",
                    ordinal = 3
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
                this::lazycraft$isFiltering,
                () -> updateCollections(false)
        );
    }

    @Inject(
            method = "mouseClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookPage;mouseClicked(DDIIIII)Z",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void lazycraft$executeGhostRecipePlan(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        RecipeHolder<?> recipe = recipeBookPage.getLastClickedRecipe();
        RecipeCollection collection = recipeBookPage.getLastClickedRecipeCollection();
        if (recipe != null
                && collection != null
                && RecipeBookCrafting.tryPlaceRecipe(
                collection,
                recipe,
                ghostRecipe.getRecipe(),
                lazycraft$hasGhostRecipe(),
                Screen.hasShiftDown(),
                ghostRecipe::clear
        )) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public boolean lazycraft$tryTakeGhostResult(Slot slot, int slotId, ClickType input) {
        return RecipeBookCrafting.tryTakeGhostResult(
                slot,
                slotId,
                ghostRecipe.getRecipe(),
                recipeBookPage.getLastClickedRecipeCollection(),
                lazycraft$hasGhostRecipe(),
                input == ClickType.QUICK_MOVE,
                ghostRecipe::clear
        );
    }

    @Unique
    private boolean lazycraft$isFiltering() {
        return book.isFiltering(menu);
    }

    @Unique
    private boolean lazycraft$hasGhostRecipe() {
        return ghostRecipe.getRecipe() != null;
    }
}
