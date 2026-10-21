package net.democracycraft.inttests.support;

import io.paradaux.mockclient.MockPlayer;

/** Run before acceptance, even when --tests selects only a subset of test classes. */
public final class CapabilityPreflight {
    public static void main(String[] args) {
        String runtime = RconClient.send("diag runtime").trim();
        require(runtime.equals("minecraft=1.21.11 online-mode=false"),
                "Mock client requires offline Paper 1.21.11; observed " + runtime
                        + ". Paper 26.3 staging needs a matching protocol client/profile (ADT-193).");
        System.out.println("Runtime: " + runtime);
        for (String name : new String[]{"Treasury", "Business", "TreasuryAPI", "ChestShop",
                "LuckPerms", "DcDiag", "WorldEdit", "WorldGuard", "GriefPrevention", "Nexo"}) {
            String capability = RconClient.send("diag plugin " + name).trim();
            require(capability.startsWith("enabled=true version="), name + ": " + capability);
            System.out.println(name + ": " + capability);
        }
        if (Boolean.getBoolean("chestshop.integrations")) {
            for (String field : new String[]{"worldguardIntegration", "griefpreventionIntegration"}) {
                String config = RconClient.send("diag configget " + field).trim();
                require(config.startsWith("ChestShopConfiguration." + field + " = true "),
                        "Protection profile requires startup-enabled " + field + ": " + config);
            }
        }
        // A real login proves protocol negotiation, not merely an open Minecraft port.
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Probe"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), java.time.Duration.ofSeconds(10));
            Barrier.settle(player);
        }
        require(RestApi.postRotateNoAuth().status() == 401,
                "REST API must be ready and reject unauthenticated token rotation");
        System.out.println("Capabilities ready. Nexo is a test stub; Realty, CarbonChat, Bedrock and Explorer are not certified by this profile.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Acceptance preflight failed: " + message);
    }

    private CapabilityPreflight() {}
}
