package klumpler.lazycraft.client.config;

import klumpler.lazycraft.client.planner.PlanScorer;
import klumpler.lazycraft.client.planner.PlanScorers;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "lazycraft")
public class LazyCraftConfig implements ConfigData {
    /**
     * Enables LazyCraft's additional recipe-book click actions.
     */
    public boolean recipeBookCrafting = true;

    /**
     * Allows ghost recipes to craft their missing crafting-table dependencies.
     */
    public boolean recursiveRecipeBookCrafting = true;

    @ConfigEntry.BoundedDiscrete(min = 1, max = 32)
    public int recursionDepth = 6;

    public ScoringMode scoringMode = ScoringMode.LEAST_TOTAL_INGREDIENTS;

    @Override
    public void validatePostLoad() {
        recursionDepth = Math.clamp(recursionDepth, 1, 32);
        if (scoringMode == null) {
            scoringMode = ScoringMode.LEAST_TOTAL_INGREDIENTS;
        }
    }

    public enum ScoringMode {
        LEAST_TOTAL_INGREDIENTS,
        FEWEST_STEPS,
        FEWEST_RECIPE_EXECUTIONS;

        public PlanScorer scorer() {
            return switch (this) {
                case LEAST_TOTAL_INGREDIENTS -> PlanScorers.LEAST_TOTAL_INGREDIENTS;
                case FEWEST_STEPS -> PlanScorers.FEWEST_STEPS;
                case FEWEST_RECIPE_EXECUTIONS -> PlanScorers.FEWEST_RECIPE_EXECUTIONS;
            };
        }
    }
}
