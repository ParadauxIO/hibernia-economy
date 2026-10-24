package net.democracycraft.inttests.support;

import io.paradaux.mockclient.MockPlayer;

/** Run before acceptance, even when --tests selects only a subset of test classes. */
public final class CapabilityPreflight {
    public static void main(String[] args) {
        String runtime = RconClient.send("diag runtime").trim();
        require(runtime.equals("minecraft=26.3 online-mode=false"),
                "Mock client requires offline Paper 26.3; observed " + runtime
                        + ". Use the pinned 26.3 client and isolated runtime profile.");
        System.out.println("Runtime: " + runtime);
        for (String name : new String[]{"Treasury", "Business", "TreasuryAPI", "ChestShop",
                "LuckPerms", "DcDiag", "WorldEdit", "WorldGuard", "GriefPrevention", "Nexo"}) {
            String capability = RconClient.send("diag plugin " + name).trim();
            require(capability.startsWith("enabled=true version="), name + ": " + capability);
            System.out.println(name + ": " + capability);
        }
        boolean realNexo = "true".equals(System.getenv("IT_NEXO_PROFILE"));
        if ("true".equals(System.getenv("IT_REALTY_PROFILE"))) {
            for (String name : new String[]{"Realty", "Vault"}) {
                String capability = RconClient.send("diag plugin " + name).trim();
                require(capability.startsWith("enabled=true version="), name + ": " + capability);
                System.out.println(name + ": " + capability);
            }
        }
        if ("true".equals(System.getenv("IT_PROVIDERS_PROFILE"))) {
            for (String name : new String[]{"Utilities", "Bolt", "BoltChestShop", "BreweryX"}) {
                String capability = RconClient.send("diag plugin " + name).trim();
                require(capability.startsWith("enabled=true version="), name + ": " + capability);
                System.out.println(name + ": " + capability);
            }
        }
        String nexo = RconClient.send("diag plugin Nexo");
        require(realNexo != nexo.contains("-stub"),
                "Nexo runtime does not match selected real/stub profile: " + nexo);
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
        System.out.println("Capabilities ready. Nexo is " + (realNexo ? "the supplied real plugin" : "a test stub")
                + "; only the selected profile's assertions establish acceptance. CarbonChat and Bedrock remain unverified.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Acceptance preflight failed: " + message);
    }

    private CapabilityPreflight() {}
}
