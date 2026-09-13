import app.yxi.desktop.HostConfigFile;
import app.yxi.desktop.WindowsCredentialProtector;
import java.nio.file.*;
import kotlin.Unit;
import org.json.JSONArray;

/** Uses only a caller-provided fresh fixture directory and synthetic host data. */
public class HostProtectionNativeCheck {
    static HostConfigFile store(Path file, String purpose) {
        return new HostConfigFile(file.toFile(), new WindowsCredentialProtector(purpose), raw -> { new JSONArray(raw); return Unit.INSTANCE; });
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]).toAbsolutePath();
        Path file = root.resolve("hosts.json");
        String raw = "[{\"id\":\"native-fixture\",\"password\":\"synthetic-host-password\"}]";
        String purpose = "Yxi/host-credentials/v1";
        if (args[0].equals("init")) {
            Files.createDirectory(root);
            Files.writeString(file, raw);
            Files.writeString(root.resolve("hosts.json.bak"), "[{\"password\":\"old-fixture-password\"}]");
            if (!raw.equals(store(file, purpose).read())) throw new AssertionError("Migration changed data");
            if (!Files.readString(file).equals("[]") || !Files.readString(root.resolve("hosts.json.bak")).equals("[]")) throw new AssertionError("Plaintext not cleared");
            if (!Files.exists(root.resolve("hosts.json.bak.migration-copy.protected"))) throw new AssertionError("Encrypted old backup missing");
            Path roaming = root.resolve("roaming"); Files.createDirectory(roaming);
            Files.writeString(roaming.resolve("hosts.json"), raw);
            Files.writeString(roaming.resolve("hosts.json.bak"), "[{\"password\":\"old-roaming-fixture\"}]");
            store(file, purpose).importLegacy(roaming.resolve("hosts.json").toFile());
            try (var paths = Files.list(roaming)) {
                for (Path path : paths.toList()) if (!Files.readString(path).equals("[]")) throw new AssertionError("Roaming plaintext not cleared");
            }
            try (var paths = Files.list(root)) {
                if (paths.filter(path -> path.getFileName().toString().contains(".import-")).count() != 2) throw new AssertionError("Local encrypted roaming copies missing");
            }
            if (!raw.equals(store(file, purpose).read())) throw new AssertionError("Roaming import changed local hosts");
            System.out.println("host DPAPI migration passed");
        } else if (args[0].equals("reopen")) {
            if (!raw.equals(store(file, purpose).read())) throw new AssertionError("Cross-process read failed");
            boolean rejected = false;
            try { store(file, "Yxi/account-credentials/v1").read(); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Incorrect protection purpose accepted");
            store(file, purpose).write("[]");
            if (!"[]".equals(store(file, purpose).read())) throw new AssertionError("Protected save failed");
            System.out.println("host DPAPI reopen purpose isolation and save passed");
        } else throw new IllegalArgumentException("Unknown check mode");
    }
}
