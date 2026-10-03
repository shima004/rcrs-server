package kernel;

import rescuecore2.config.Config;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;
import rescuecore2.worldmodel.ChangeSet;

/**
   Implementations of this interface are responsible for determining what entities/properties each agent can see.
 */
public interface Perception {
    /**
       Initialise this perception object.
       @param config The kernel configuration.
       @param world The world model.
    */
    void initialise(Config config, WorldModel<? extends Entity> world);

    /**
       Determine what Entities are visible to a particular agent. The returned Entities should be copies of Entities in the ground-truth WorldModel. Only visible properties should have defined values.
       @param agent The agent that is perceiving the world.
       @return A collection of entities that the agent can perceive.
     */
    ChangeSet getVisibleEntities(AgentProxy agent);

    /**
       Notify this perception object of the current time.
       @param timestep The current timestep.
    */
    void setTime(int timestep);

    /** Log implementation-specific totals after all agents have been processed.
     * @param totalNanos Time spent in getVisibleEntities calls in this timestep.
     */
    default void logTimingBreakdown(long totalNanos) {
        // Optional for perception implementations.
    }
}
