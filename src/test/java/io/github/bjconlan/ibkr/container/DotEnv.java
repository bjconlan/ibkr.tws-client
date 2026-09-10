package io.github.bjconlan.ibkr.container;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a dotenv-style file ({@code KEY=VALUE} per line, {@code #} comments, optionally
 * quoted values). Real environment variables take precedence over the file so CI can inject
 * credentials as secrets.
 *
 * <p>The file is expected at {@code ${basedir}/.env}. It is git-ignored; commit
 * {@code .env.example} instead.
 */
final class DotEnv {

    private DotEnv() {
    }

    /** Credentials from the environment, falling back to {@code .env}. */
    static Map<String, String> credentials() {
        Map<String, String> merged = new HashMap<>(load(projectFile()));
        System.getenv().forEach((key, value) -> {
            if (key.equals("TWS_USERID") || key.equals("TWS_PASSWORD")) {
                merged.put(key, value);
            }
        });
        return Map.copyOf(merged);
    }

    static Path projectFile() {
        return Path.of(System.getProperty("basedir", "."), ".env");
    }

    static Map<String, String> load(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(path);
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).strip();
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).strip();
                String value = line.substring(equals + 1).strip();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
        return Map.copyOf(values);
    }
}
