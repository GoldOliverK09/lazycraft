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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Overlays cached recursive craftability onto the recipe book's filtered view without
 * modifying Minecraft's own direct-craftability state.
 */
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
        if (status != RecipeCollection.CraftableStatus.CRAFTABLE) {
            return;
        }

        RecipeCollection collection = (RecipeCollection) (Object) this;
        List<RecipeDisplayEntry> expanded = null;
        for (RecipeDisplayEntry entry : entries) {
            RecipeDisplayId recipe = entry.id();
            if (!selected.contains(recipe)
                    || collection.isCraftable(recipe)
                    || !VisibleRecipeCraftability.isRecursivelyCraftable(recipe)) {
                continue;
            }

            if (expanded == null) {
                expanded = new ArrayList<>(cir.getReturnValue());
            }
            expanded.add(entry);
        }

        if (expanded != null) {
            cir.setReturnValue(expanded);
        }
    }
}
