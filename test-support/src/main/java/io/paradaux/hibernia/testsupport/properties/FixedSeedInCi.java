package io.paradaux.hibernia.testsupport.properties;

import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;

/**
 * jqwik lifecycle hook that pins the random seed on CI so a pipeline run is
 * reproducible (same inputs, same runtime) — jqwik itself has no global seed setting.
 * Locally the seed stays random, so every developer run explores new inputs; a
 * failing seed is recorded in jqwik's failure database and replayed (and shrunk)
 * first on the next run.
 *
 * <p>Applied with {@code @AddLifecycleHook(FixedSeedInCi.class)} on a property class.
 * A property that declares its own {@code @Property(seed = …)} keeps it. Set
 * {@code JQWIK_SEED} to replay a specific seed anywhere (e.g. one printed by a
 * local failure), or to rotate the CI seed deliberately.
 */
public final class FixedSeedInCi implements AroundPropertyHook {

    /** The seed CI uses unless {@code JQWIK_SEED} overrides it. */
    public static final String CI_SEED = "20261016";

    @Override
    public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
        String override = System.getenv("JQWIK_SEED");
        boolean ci = "true".equalsIgnoreCase(System.getenv("CI"));
        if (context.attributes().seed().filter(s -> !s.isBlank()).isEmpty()) {
            if (override != null && !override.isBlank()) {
                context.attributes().setSeed(override.strip());
            } else if (ci) {
                context.attributes().setSeed(CI_SEED);
            }
        }
        return property.execute();
    }

    @Override
    public PropagationMode propagateTo() {
        // Registered on the property class; must reach every property inside it,
        // including nested containers.
        return PropagationMode.ALL_DESCENDANTS;
    }

    @Override
    public int aroundPropertyProximity() {
        // Outermost: the seed must be settled before any inner hook or the executor
        // reads the attributes.
        return -100;
    }
}
