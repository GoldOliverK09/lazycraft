package klumpler.lazycraft.client.planner;

public final class PlanScorers {
    public static final PlanScorer LEAST_TOTAL_INGREDIENTS = CraftPlan::totalIngredients;
    public static final PlanScorer FEWEST_STEPS = plan -> plan.steps().size();
    public static final PlanScorer FEWEST_RECIPE_EXECUTIONS = plan -> plan.steps().stream()
            .mapToLong(CraftingStep::crafts)
            .sum();

    private PlanScorers() {
    }
}
