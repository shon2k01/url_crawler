package crawler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// CLI entry point that parses args and launches the crawl.
public class Main {
    // Parse CLI args and launch the crawler.
    public static void main(String[] args) {
        // Validate CLI args count
        if (args.length != 4) {
            System.err.println("""
                    Usage: <startUrl> <maxUrlsPerPage> <depth> <crossLevelUniqueness>
                    Example: https://www.ynetnews.com 5 2 true
                    """);
            System.exit(1);
        }

        // Parse args
        String startUrl = args[0];
        int maxUrlsPerPage = parseInt(args[1], "maxUrlsPerPage");
        int depth = parseInt(args[2], "depth");

        // Boolean.parseBoolean returns false for anything not "true" (case-insensitive),
        // so we validate manually to catch "maybe" etc.
        String uniqRaw = args[3].trim().toLowerCase();
        if (!uniqRaw.equals("true") && !uniqRaw.equals("false")) {
            System.err.println("Invalid boolean for crossLevelUniqueness: " + args[3] + " (use true/false)");
            System.exit(1);
        }
        boolean crossLevelUniqueness = Boolean.parseBoolean(uniqRaw);

        // Validate depth and max urls 
        if (maxUrlsPerPage < 0 || depth < 0) {
            System.err.println("maxUrlsPerPage and depth must be >= 0");
            System.exit(1);
        }

        // Create a unique run id so each run writes to its own folder.
        // Example: 2025-12-25_15-30-12
        String runId = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .format(LocalDateTime.now());

        // Pass runId into config so crawler can write to output/<runId>/...
        CrawlerConfig config = new CrawlerConfig(startUrl, maxUrlsPerPage, depth, crossLevelUniqueness, runId);
        UrlCrawler crawler = new UrlCrawler(config);
        crawler.run();
    }

    //to parse CLI integer args
    // Strict integer parsing with a clean error message.
    private static int parseInt(String s, String name) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            System.err.println("Invalid integer for " + name + ": " + s);
            System.exit(1);
            return -1; // unreachable, but required by compiler
        }
    }
}
