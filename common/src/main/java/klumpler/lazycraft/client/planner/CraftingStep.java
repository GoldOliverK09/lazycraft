package klumpler.lazycraft.client.planner;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Objects;

public record CraftingStep(RecipeHolder<?> recipe, Item output, int crafts) {
    public CraftingStep {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        Objects.requireNonNull(output, "output cannot be null");

        if (crafts <= 0) {
            throw new IllegalArgumentException("crafts must be positive");
        }
    }
}
