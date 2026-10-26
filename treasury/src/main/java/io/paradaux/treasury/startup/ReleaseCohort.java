package io.paradaux.treasury.startup;

import java.util.List;
import java.util.function.Function;

/** Reject partial deployments before Treasury can migrate the shared schema. */
public final class ReleaseCohort {
    private ReleaseCohort() {}

    public static void verify(String expectedVersion, Function<String, String> installedVersion) {
        for (String name : List.of("Treasury", "Business", "ChestShop", "TreasuryAPI")) {
            String actual = installedVersion.apply(name);
            if (!expectedVersion.equals(actual)) {
                throw new IllegalStateException("Incomplete economy release: " + name + " is " + actual
                        + "; all four plugins must be " + expectedVersion);
            }
        }
    }
}
