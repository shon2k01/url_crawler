package crawler;

// Parsed CLI parameters for a crawl run.
//record makes it immutabale! get parameters with () 
public record CrawlerConfig(
        String startUrl,
        int maxUrlsPerPage,
        int maxDepth,
        boolean crossLevelUniqueness,
        String runId  // unique id for each run, used for output files
) {}
