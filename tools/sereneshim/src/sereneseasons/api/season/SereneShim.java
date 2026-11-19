package sereneseasons.api.season;

import net.neoforged.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The stand-in's mod entry (the jar is a minimal mod with the id sereneseasons, so ModList sees it) and its mode word. */
@Mod("sereneseasons")
public class SereneShim {
    /** The rig's probe mode word; empty when there is none. */
    static String mode() {
        try {
            return Files.readString(Path.of("probe-mode.txt"), StandardCharsets.US_ASCII).trim();
        } catch (final Exception unreadable) {
            return "";
        }
    }
}
