package klumpler.lazycraft.client.mixin;

import klumpler.lazycraft.client.recipebook.VisibleRecipeCraftability;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

@Mixin(RecipeCollection.class)
public class RecipeCollectionMixin {
    @Shadow
    @Final
    private List<RecipeDisplayEntry> entries;

    @Shadow
    @Final
    private Set<RecipeDisplayId> selected;

    @Inject(method = "getSelectedRecipes", at = @At("RETURN"), cancellable = true)
    private void lazycraft$includeRecursiveEntries(
            RecipeCollection.CraftableStatus status,
            CallbackInfoReturnable<List<RecipeDisplayEntry>> cir
    ) {
        List<RecipeDisplayEntry> vanillaEntries = cir.getReturnValue();
        List<RecipeDisplayEntry> expanded = VisibleRecipeCraftability.includeRecursivelyCraftableEntries(
                (RecipeCollection) (Object) this,
                status,
                entries,
                selected,
                vanillaEntries
        );
        if (expanded != vanillaEntries) {
            cir.setReturnValue(expanded);
        }
    }
}
