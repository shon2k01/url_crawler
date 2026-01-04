package crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class OutputManager {

    // Base directory for this run: output/<runId>
    private final Path runOutputDir;

    // Keep failures in memory and write them once at the end (less spammy logs)
    private final FailureLogger failureLogger;

    private final ConcurrentMap<Integer, ConcurrentMap<String, String>> filenameByDepth = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Set<String>> usedNamesByDepth = new ConcurrentHashMap<>();

    // Handles output files for a single run.
    public OutputManager(Path runOutputDir, FailureLogger failureLogger) {
        this.runOutputDir = runOutputDir;
        this.failureLogger = failureLogger;
    }

    // Save a page's HTML under <runId>/<depth>/.
    public void saveHtml(int depth, String url, String html) {
        try {
            // Write into output/<runId>/<depth>/
            Path dir = runOutputDir.resolve(String.valueOf(depth));
            Files.createDirectories(dir);

            // Convert URL to a safe filename (which ends with .html)
            String fileName = filenameFor(depth, url);
            Path out = dir.resolve(fileName);

            // Overwrite file if it exists (same URL in same depth)
            Files.writeString(out, html == null ? "" : html, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // If saving fails, store it as a failure too
            failureLogger.add(new FailureRecord(depth, url, "SAVE_FAILED", e.getMessage()));
        }
    }

    // Save the selected child URLs for a parent into a file.
    public void writeChildrenFile(int depth, String parentUrl, List<String> children) {
        if (parentUrl == null || children == null) return;
        Path dir = runOutputDir.resolve(String.valueOf(depth));
        String fileName = linksFilenameFor(depth, parentUrl);
        Path out = dir.resolve(fileName);
        try {
            Files.write(out, children, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not write children file for " + parentUrl + " (" + e.getMessage() + ")");
        }
    }

    // Dump collected failures to failures.csv at the end of the run.
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

    // Return a stable filename for a URL at a specific depth (memoized).
    private String filenameFor(int depth, String url) {
        // One map per depth so filename collisions are tracked independently by level.
        ConcurrentMap<String, String> byUrl = filenameByDepth.computeIfAbsent(depth, d -> new ConcurrentHashMap<>());
        // Reuse the same filename for the same URL at that depth.
        return byUrl.computeIfAbsent(url, u -> reserveFilename(depth, u));
    }

    // Derive a .links.txt filename that matches the HTML filename for the same URL.
    private String linksFilenameFor(int depth, String url) {
        // Match the HTML filename so the two files are easy to correlate.
        String htmlName = filenameFor(depth, url);
        if (htmlName.endsWith(".html")) {
            return htmlName.substring(0, htmlName.length() - ".html".length()) + ".links.txt";
        }
        return htmlName + ".links.txt";
    }

    // Reserve a unique filename within a depth to avoid sanitized collisions.
    private String reserveFilename(int depth, String url) {
        // Track used names per depth to avoid two different URLs mapping to the same filename.
        Set<String> used = usedNamesByDepth.computeIfAbsent(depth, d -> ConcurrentHashMap.newKeySet());
        String base = UrlUtil.toSafeFilename(url);
        if (used.add(base)) return base;

        // Fall back to a hashed variant if the base is already taken.
        String hashed = UrlUtil.toSafeFilenameWithHash(url);
        if (used.add(hashed)) return hashed;

        // If still taken (extremely unlikely), append a counter until unique.
        String withoutExt = hashed.endsWith(".html")
                ? hashed.substring(0, hashed.length() - ".html".length())
                : hashed;
        int counter = 2;
        String candidate;
        do {
            candidate = withoutExt + "_" + counter + ".html";
            counter++;
        } while (!used.add(candidate));
        return candidate;
    }

}
