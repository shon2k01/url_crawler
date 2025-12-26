package crawler;

//convert parameters from user to a class for easy access

public record CrawlerConfig(
        String startUrl,
        int maxUrlsPerPage,
        int maxDepth,
        boolean crossLevelUniqueness,
        String runId  //uniqe id for each run, used later for output files
) {}
