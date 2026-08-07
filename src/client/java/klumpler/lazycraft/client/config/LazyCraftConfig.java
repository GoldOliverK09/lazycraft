package klumpler.lazycraft.client.config;

import klumpler.lazycraft.LazyCraft;
import klumpler.lazycraft.client.planner.PlanScorer;
import klumpler.lazycraft.client.planner.PlanScorers;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = LazyCraft.MOD_ID)
public class LazyCraftConfig implements ConfigData {
    static final int MIN_RECURSION_DEPTH = 1;
    static final int MAX_RECURSION_DEPTH = 32;
    static final int DEFAULT_RECURSION_DEPTH = 6;

    /**
     * Enables LazyCraft's additional recipe-book click actions.
     */
    public boolean recipeBookCrafting = true;

    /**
     * Allows ghost recipes to craft their missing crafting-table dependencies.
     */
    public boolean recursiveRecipeBookCrafting = true;

    @ConfigEntry.BoundedDiscrete(min = MIN_RECURSION_DEPTH, max = MAX_RECURSION_DEPTH)
    public int recursionDepth = DEFAULT_RECURSION_DEPTH;

    public ScoringMode scoringMode = ScoringMode.LEAST_TOTAL_INGREDIENTS;

    @Override
    public void validatePostLoad() {
        recursionDepth = Math.clamp(recursionDepth, MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH);
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
