package klumpler.lazycraft.client.planner;

import java.util.List;
import java.util.Objects;

public record CraftPlan(List<CraftingStep> steps) {
    public CraftPlan {
        Objects.requireNonNull(steps, "steps cannot be null");
        steps = List.copyOf(steps);
    }
}
