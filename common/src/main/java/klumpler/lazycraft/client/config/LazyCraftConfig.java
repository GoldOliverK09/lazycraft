package klumpler.lazycraft.client.config;

public final class LazyCraftConfig {
    static final int MIN_RECURSION_DEPTH = 1;
    static final int MAX_RECURSION_DEPTH = 16;
    static final int DEFAULT_RECURSION_DEPTH = 6;
    static final int MIN_CANDIDATES_PER_LAYER = 1;
    static final int MAX_CANDIDATES_PER_LAYER = 128;
    static final int DEFAULT_CANDIDATES_PER_LAYER = 64;
    static final int MIN_BACKGROUND_RECIPE_CHECK_DELAY_TICKS = 0;
    static final int MAX_BACKGROUND_RECIPE_CHECK_DELAY_TICKS = 20;
    static final int DEFAULT_BACKGROUND_RECIPE_CHECK_DELAY_TICKS = 1;
    static final int MIN_SERVER_UPDATE_TIMEOUT_TICKS = 1;
    static final int MAX_SERVER_UPDATE_TIMEOUT_TICKS = 300;
    static final int DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS = 20;
    static final int MIN_STEP_DELAY_TICKS = 0;
    static final int MAX_STEP_DELAY_TICKS = 20;
    static final int DEFAULT_STEP_DELAY_TICKS = 1;

    public boolean recipeBookCrafting = true;
    public boolean recursiveRecipeBookCrafting = true;
    public boolean showRecursiveCraftability = true;
    public int backgroundRecipeCheckDelayTicks = DEFAULT_BACKGROUND_RECIPE_CHECK_DELAY_TICKS;
    public int recursionDepth = DEFAULT_RECURSION_DEPTH;
    public int maxCandidatesPerLayer = DEFAULT_CANDIDATES_PER_LAYER;
    public ScoringMode scoringMode = ScoringMode.LEAST_TOTAL_INGREDIENTS;
    public int serverUpdateTimeoutTicks = DEFAULT_SERVER_UPDATE_TIMEOUT_TICKS;
    public int stepDelayTicks = DEFAULT_STEP_DELAY_TICKS;

    public void validate() {
        recursionDepth = Math.clamp(recursionDepth, MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH);
        maxCandidatesPerLayer = Math.clamp(
                maxCandidatesPerLayer,
                MIN_CANDIDATES_PER_LAYER,
                MAX_CANDIDATES_PER_LAYER
        );
        backgroundRecipeCheckDelayTicks = Math.clamp(
                backgroundRecipeCheckDelayTicks,
                MIN_BACKGROUND_RECIPE_CHECK_DELAY_TICKS,
                MAX_BACKGROUND_RECIPE_CHECK_DELAY_TICKS
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
        FASTEST_EXECUTION,
        SHALLOWEST_CHAIN,
        LEAST_OVERPRODUCTION
    }
}
