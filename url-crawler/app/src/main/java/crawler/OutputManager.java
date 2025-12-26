package crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class OutputManager {

    // Base directory for this run: output/<runId>
    private final Path runOutputDir;

    // Keep failures in memory and write them once at the end (less spammy logs)
    private final FailureLogger failureLogger;

    public OutputManager(Path runOutputDir, FailureLogger failureLogger) {
        this.runOutputDir = runOutputDir;
        this.failureLogger = failureLogger;
    }

    public void saveHtml(int depth, String url, String html) {
        try {
            // Write into output/<runId>/<depth>/
            Path dir = runOutputDir.resolve(String.valueOf(depth));
            Files.createDirectories(dir);

            // Convert URL to a safe filename
            String fileName = UrlUtil.toSafeFilename(url);
            Path out = dir.resolve(fileName);

            // Overwrite file if it exists (same URL in same depth)
            Files.writeString(out, html == null ? "" : html, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException e) {
            // If saving fails, store it as a failure too
            failureLogger.add(new FailureRecord(depth, url, "SAVE_FAILED", e.getMessage()));
        }
    }

    public void writeFailuresFile() {
        if (failureLogger.isEmpty()) return;

        Path out = runOutputDir.resolve("failures.csv");
        try {
            // Very simple CSV: depth,url,type,message
            List<String> lines = new ArrayList<>();
            lines.add("depth,url,type,message");

            for (FailureRecord f : failureLogger.snapshot()) {
                lines.add(csv(f.depth()) + "," + csv(f.url()) + "," + csv(f.type()) + "," + csv(f.message()));
            }

            Files.write(out, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Wrote failures file: " + out.toString());
        } catch (IOException e) {
            System.err.println("Could not write failures file: " + e.getMessage());
        }
    }

    // Quote CSV fields safely (minimal)
    private static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        s = s.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }
}
