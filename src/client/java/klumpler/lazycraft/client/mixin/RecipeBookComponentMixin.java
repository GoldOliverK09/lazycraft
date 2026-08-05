package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.planner.CraftingExecutor;
import klumpler.lazycraft.client.planner.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void lazycraft$executeGhostRecipePlan(
            RecipeCollection collection,
            RecipeDisplayId recipeId,
            boolean shiftDown,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (collection.isCraftable(recipeId) || CraftingExecutor.isExecuting()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        RecipeDisplayEntry entry = collection.getRecipes().stream()
                .filter(recipe -> recipe.id().equals(recipeId))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        entry.resultItems(context).stream()
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(stack -> stack.getItem())
                .flatMap(RecipePlanner::plan)
                .filter(plan -> !plan.steps().isEmpty())
                .filter(CraftingExecutor::execute)
                .ifPresent(ignored -> cir.setReturnValue(true));
    }
}
