package klumpler.lazycraft.client.recipebook;

import klumpler.lazycraft.client.planner.RecipeIndex;
import net.minecraft.client.ClientRecipeBook;

public final class RecipeBookEvents {
    private RecipeBookEvents() {
    }

    public static void inventoryChanged() {
        VisibleRecipeCraftability.inventoryChanged();
    }

    public static void recipesChanged(ClientRecipeBook recipeBook) {
        RecipeIndex.rebuildLookup(recipeBook.getCollections());
        VisibleRecipeCraftability.clear();
    }
}
