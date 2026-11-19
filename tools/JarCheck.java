import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a jar the way a launcher may have to: front to back with a ZipInputStream, every entry to its
 * last byte (a jar that only the central directory can open fails here). Also checks the jar holds
 * META-INF/neoforge.mods.toml and no entry twice. Run as a single-file program:
 *   java tools/JarCheck.java colony_seasons-0.1.0-beta.1.jar
 */
public class JarCheck {
    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java JarCheck.java <jar>");
            System.exit(2);
        }
        final Path jar = Path.of(args[0]);
        final Set<String> names = new HashSet<>();
        int entries = 0;
        long bytes = 0;
        boolean toml = false;
        final byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!names.add(entry.getName())) {
                    System.err.println("duplicate entry: " + entry.getName());
                    System.exit(1);
                }
                toml |= entry.getName().equals("META-INF/neoforge.mods.toml");
                entries++;
                final InputStream in = zip;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes += n;
                }
            }
        }
        if (entries == 0) {
            System.err.println("the jar has no entries");
            System.exit(1);
        }
        if (!toml) {
            System.err.println("META-INF/neoforge.mods.toml is missing");
            System.exit(1);
        }
        System.out.println("sequential read ok: " + entries + " entries, " + bytes + " bytes, " + jar.getFileName());
    }
}
