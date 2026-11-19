package sereneseasons.config;

import java.util.List;

/**
 * Stand-in: Serene Seasons' dimension whitelist. In the probe mode bridge-nonseasonal the overworld is not on it,
 * which is what a Serene Seasons configured for other dimensions looks like (it then returns a frozen season, no failure).
 */
public class SeasonsConfig {
    public List<String> whitelistedDimensions =
            "bridge-nonseasonal".equals(mode()) ? List.of("minecraft:the_nether") : List.of("minecraft:overworld");

    private static String mode() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("probe-mode.txt"), java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (final Exception unreadable) {
            return "";
        }
    }
}
