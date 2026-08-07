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
    static final int MIN_CANDIDATES_PER_LAYER = 1;
    static final int MAX_CANDIDATES_PER_LAYER = 256;
    static final int DEFAULT_CANDIDATES_PER_LAYER = 64;
    static final int MIN_SERVER_UPDATE_TIMEOUT_TICKS = 1;
    static final int MAX_SERVER_UPDATE_TIMEOUT_TICKS = 600;
    static final int DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS = 40;
    static final int MIN_STEP_DELAY_TICKS = 1;
    static final int MAX_STEP_DELAY_TICKS = 20;
    static final int DEFAULT_STEP_DELAY_TICKS = 1;

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

    /**
     * Limits the number of candidate plans retained at each recursive search layer.
     */
    @ConfigEntry.BoundedDiscrete(min = MIN_CANDIDATES_PER_LAYER, max = MAX_CANDIDATES_PER_LAYER)
    public int maxCandidatesPerLayer = DEFAULT_CANDIDATES_PER_LAYER;

    public ScoringMode scoringMode = ScoringMode.LEAST_TOTAL_INGREDIENTS;

    /**
     * Number of ticks to wait for a server menu update before cancelling execution.
     */
    @ConfigEntry.BoundedDiscrete(
            min = MIN_SERVER_UPDATE_TIMEOUT_TICKS,
            max = MAX_SERVER_UPDATE_TIMEOUT_TICKS
    )
    public int serverUpdateTimeoutTicks = DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS;

    /**
     * Number of ticks to wait between completed steps in a multi-step plan.
     */
    @ConfigEntry.BoundedDiscrete(min = MIN_STEP_DELAY_TICKS, max = MAX_STEP_DELAY_TICKS)
    public int stepDelayTicks = DEFAULT_STEP_DELAY_TICKS;

    @Override
    public void validatePostLoad() {
        recursionDepth = Math.clamp(recursionDepth, MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH);
        maxCandidatesPerLayer = Math.clamp(
                maxCandidatesPerLayer,
                MIN_CANDIDATES_PER_LAYER,
                MAX_CANDIDATES_PER_LAYER
        );
        serverUpdateTimeoutTicks = Math.clamp(
                serverUpdateTimeoutTicks,
                MIN_SERVER_UPDATE_TIMEOUT_TICKS,
                MAX_SERVER_UPDATE_TIMEOUT_TICKS
        );
        stepDelayTicks = Math.clamp(stepDelayTicks, MIN_STEP_DELAY_TICKS, MAX_STEP_DELAY_TICKS);
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
