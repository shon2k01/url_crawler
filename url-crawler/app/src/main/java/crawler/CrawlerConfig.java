package crawler;

// Parsed CLI parameters for a crawl run.
public record CrawlerConfig(
        String startUrl,
        int maxUrlsPerPage,
        int maxDepth,
        boolean crossLevelUniqueness,
        String runId  // unique id for each run, used for output files
) {}
