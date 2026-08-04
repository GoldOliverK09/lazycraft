package klumpler.lazycraft.client.planner;

@FunctionalInterface
public interface PlanScorer {
    long score(CraftPlan plan);
}
