package klumpler.lazycraft.client.planner;

public final class PlanScorers {
    public static final PlanScorer LEAST_TOTAL_INGREDIENTS = CraftPlan::totalIngredients;

    private PlanScorers() {
    }
}
