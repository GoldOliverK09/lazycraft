package klumpler.lazycraft.client.planner;

public final class PlanScorers {
    public static final PlanScorer LEAST_TOTAL_INGREDIENTS = CraftPlan::totalIngredients;
    public static final PlanScorer FEWEST_STEPS = plan -> plan.steps().size();

    private PlanScorers() {
    }
}
