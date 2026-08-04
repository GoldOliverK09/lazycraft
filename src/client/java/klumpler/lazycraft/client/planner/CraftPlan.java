package klumpler.lazycraft.client.planner;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Objects;

public record CraftPlan(Item target, int quantity, List<CraftingStep> steps, long totalIngredients) {
    public CraftPlan {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        if (totalIngredients < 0) {
            throw new IllegalArgumentException("totalIngredients cannot be negative");
        }

        steps = List.copyOf(steps);
    }
}
