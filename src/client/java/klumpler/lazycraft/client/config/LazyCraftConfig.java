package klumpler.lazycraft.client.config;

import klumpler.lazycraft.client.planner.PlanScorer;
import klumpler.lazycraft.client.planner.PlanScorers;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "lazycraft")
public class LazyCraftConfig implements ConfigData {
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
        FEWEST_STEPS;

        public PlanScorer scorer() {
            return switch (this) {
                case LEAST_TOTAL_INGREDIENTS -> PlanScorers.LEAST_TOTAL_INGREDIENTS;
                case FEWEST_STEPS -> PlanScorers.FEWEST_STEPS;
            };
        }
    }
}
