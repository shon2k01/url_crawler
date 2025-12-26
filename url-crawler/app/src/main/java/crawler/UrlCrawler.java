package crawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class UrlCrawler {

    private final CrawlerConfig config;

    // Base directory for this run: output/<runId>
    private final Path runOutputDir;

    // Thread pool for parallel fetches inside each depth level
    private final ExecutorService pool;

    // For graceful shutdown
    private volatile boolean shuttingDown = false;

    // Keep failures in memory and write them once at the end (less spammy logs)
    private final FailureLogger failureLogger;

    // Counters for a clean summary at the end
    private final AtomicInteger pagesFetchedOk = new AtomicInteger(0);
    private final AtomicInteger pagesFailed = new AtomicInteger(0);
    private final AtomicInteger pagesTimedOut = new AtomicInteger(0);

    // Responsible for writing html files + failures.csv + optional url map
    private final OutputManager outputManager;

    // Responsible for fetching + extracting links
    private final PageFetcher pageFetcher;

    public UrlCrawler(CrawlerConfig config) {
        this.config = config;

        // Each run gets its own folder so reruns never mix files
        this.runOutputDir = Paths.get("output", config.runId());

        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.pool = Executors.newFixedThreadPool(threads);

        this.failureLogger = new FailureLogger();

        this.outputManager = new OutputManager(runOutputDir, failureLogger);
        this.pageFetcher = new PageFetcher(outputManager, failureLogger);

        // If user hits Ctrl+C, mark shutdown and stop workers
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            pool.shutdownNow();
        }));
    }

    public void run() {
        // BFS by depth: level 0 has startUrl, level 1 has extracted urls, etc.
        Set<String> globalSeen = ConcurrentHashMap.newKeySet();
        List<String> currentLevel = List.of(UrlUtil.normalize(config.startUrl()));

        // When cross-level uniqueness is enabled, remember everything across all depths
        if (config.crossLevelUniqueness()) {
            globalSeen.addAll(currentLevel);
        }

        // Make sure base output folder exists early
        try {
            Files.createDirectories(runOutputDir);
        } catch (IOException e) {
            System.err.println("Could not create output dir: " + runOutputDir + " (" + e.getMessage() + ")");
            return;
        }

        for (int depth = 0; depth <= config.maxDepth(); depth++) {
            if (shuttingDown) break;
            if (currentLevel.isEmpty()) break;

            int d = depth;

            // Fetch all URLs in current level in parallel
            List<Future<PageResult>> futures = currentLevel.stream()
                    .map(url -> pool.submit(() -> pageFetcher.fetchAndSave(url, d, shuttingDown)))
                    .toList();

            List<PageResult> results = new ArrayList<>();
            for (Future<PageResult> f : futures) {
                try {
                    PageResult r = f.get();
                    results.add(r);

                    // update counters (keeps UrlCrawler as the owner of run summary)
                    if (r.status() == FetchStatus.OK) pagesFetchedOk.incrementAndGet();
                    else if (r.status() == FetchStatus.TIMEOUT) pagesTimedOut.incrementAndGet();
                    else if (r.status() == FetchStatus.FAILED) pagesFailed.incrementAndGet();

                } catch (InterruptedException e) {
                    // If interrupted, stop quickly and exit
                    Thread.currentThread().interrupt();
                    shuttingDown = true;
                    break;
                } catch (ExecutionException e) {
                    // One task crashed; continue others
                    failureLogger.add(new FailureRecord(d, "<unknown>", "CRASH", String.valueOf(e.getCause())));
                }
            }

            // Depth summary (keeps console readable)
            System.out.println("Depth " + depth + " done. Pages: " + currentLevel.size());

            // If this is the last depth, we stop after saving
            if (depth == config.maxDepth()) break;

            // Extract next level URLs
            List<String> next = new ArrayList<>();
            for (PageResult r : results) {
                if (r == null || r.extractedUrls() == null) continue;

                // Take up to maxUrlsPerPage per page
                List<String> extracted = r.extractedUrls().stream()
                        .filter(UrlUtil::isHttpLike)
                        .map(UrlUtil::normalize)
                        .distinct()
                        .limit(config.maxUrlsPerPage())
                        .toList();

                next.addAll(extracted);
            }

            // Remove duplicates in the next level itself
            // If crossLevelUniqueness = true, also remove anything seen in previous levels
            List<String> nextLevel;
            if (config.crossLevelUniqueness()) {
                nextLevel = next.stream()
                        .filter(u -> globalSeen.add(u)) // add returns false if already seen
                        .collect(Collectors.toList());
            } else {
                // uniqueness only "within this level": keep distinct here, allow repeats across depths
                nextLevel = next.stream().distinct().toList();
            }

            System.out.println("Depth " + depth + " -> next level URLs: " + nextLevel.size());
            currentLevel = nextLevel;
        }

        shutdownGracefully();
        outputManager.writeFailuresFile();   // write output/<runId>/failures.csv
        printFinalSummary();                 // final clean summary
    }

    private void shutdownGracefully() {
        // Stop accepting new tasks
        pool.shutdown();
        try {
            // Wait a bit for tasks to finish
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                // Force stop if taking too long
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private void printFinalSummary() {
        System.out.println("==== Run summary ====");
        System.out.println("Saved output under: " + runOutputDir.toString());
        System.out.println("Fetched OK: " + pagesFetchedOk.get());
        System.out.println("Timeouts : " + pagesTimedOut.get());
        System.out.println("Failed   : " + pagesFailed.get());
        if (!failureLogger.isEmpty()) {
            System.out.println("Failures details: " + runOutputDir.resolve("failures.csv"));
        }
    }
}
