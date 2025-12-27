package crawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class UrlCrawler {

    private final CrawlerConfig config;

    // Base directory for this run: output/<runId>
    private final Path runOutputDir;

    // Thread pool for parallel fetches
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
    private OutputManager outputManager;

    // Responsible for fetching + extracting links
    private PageFetcher pageFetcher;

    // Bundle task metadata with its fetch result.
    private record TaskResult(int depth, PageResult result) { }

    // Wire dependencies and thread pool for a single crawl run.
    public UrlCrawler(CrawlerConfig config) {
        this.config = config;

        // Each run gets its own folder so reruns never mix files
        this.runOutputDir = Paths.get("output", config.runId());

        int threadCount = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.pool = Executors.newFixedThreadPool(threadCount);

        this.failureLogger = new FailureLogger();

        // If user hits Ctrl+C, mark shutdown and stop workers
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            pool.shutdown();
        }));
    }

    // Entry point for the crawl: schedules work and drains results until done.
    public void run() {
        // Make sure base output folder exists early
        try {
            Files.createDirectories(runOutputDir);
        } catch (IOException e) {
            System.err.println("Could not create output dir: " + runOutputDir + " (" + e.getMessage() + ")");
            return;
        }

        this.outputManager = new OutputManager(runOutputDir, failureLogger);
        this.pageFetcher = new PageFetcher(outputManager, failureLogger);

        // Concurrent crawl: as soon as a page finishes, enqueue its children.
        Set<String> globalSeen = ConcurrentHashMap.newKeySet();
        Map<Integer, Set<String>> depthSeen = new HashMap<>();
        String rootUrl = UrlUtil.normalize(config.startUrl());

        // When cross-level uniqueness is enabled, remember everything across all depths
        if (config.crossLevelUniqueness()) {
            globalSeen.add(rootUrl);
        }

        // Completion service lets us process tasks as they finish
        ExecutorCompletionService<TaskResult> completion = new ExecutorCompletionService<>(pool);
        //number of currently running or queued in the thread pool
        AtomicInteger inFlight = new AtomicInteger(0);

        submitTask(completion, inFlight, rootUrl, 0);

        try {
            while (inFlight.get() > 0) {
                Future<TaskResult> future;
                try {
                    future = completion.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    shuttingDown = true;
                    break;
                }

                inFlight.decrementAndGet();
                TaskResult taskResult;
                try {
                    taskResult = future.get();
                } catch (ExecutionException e) {
                    failureLogger.add(new FailureRecord(-1, "<unknown>", "CRASH", String.valueOf(e.getCause())));
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    shuttingDown = true;
                    break;
                }

                PageResult r = taskResult.result();
                if (r == null) continue;

                // Update counters per completed task.
                if (r.status() == FetchStatus.OK) pagesFetchedOk.incrementAndGet();
                else if (r.status() == FetchStatus.TIMEOUT) pagesTimedOut.incrementAndGet();
                else if (r.status() == FetchStatus.FAILED) pagesFailed.incrementAndGet();

                int depth = taskResult.depth();
                if (depth >= config.maxDepth()) continue;

                // Pick up to maxUrlsPerPage unique children for this parent.
                List<String> children = selectChildren(r, depth, globalSeen, depthSeen);
                outputManager.writeChildrenFile(depth, r.url(), children);

                if (shuttingDown) continue;

                int childDepth = depth + 1;
                for (String child : children) {
                    submitTask(completion, inFlight, child, childDepth);
                }
            }
        } finally {
            shutdownGracefully();
            outputManager.writeFailuresFile();   // write output/<runId>/failures.csv
            printFinalSummary();
        }
    }

    // Stop workers with a small grace period.
    private void shutdownGracefully() {
        // Stop accepting new tasks
        pool.shutdown();
        try {
            // Wait a bit for tasks to finish
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                // Force stop if taking too long (only when not shutting down gracefully)
                if (!shuttingDown) {
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!shuttingDown) {
                pool.shutdownNow();
            }
        }
    }

    // Print a minimal end-of-run summary.
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

    // Enqueue a single URL fetch at a given depth.
    private void submitTask(ExecutorCompletionService<TaskResult> completion,
                            AtomicInteger inFlight,
                            String url,
                            int depth) {
        if (shuttingDown) return;
        try {
            inFlight.incrementAndGet();
            completion.submit(() -> new TaskResult(depth, pageFetcher.fetchAndSave(url, depth)));
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            failureLogger.add(new FailureRecord(depth, url, "REJECTED", e.getMessage()));
        }
    }

    // Pick up to maxUrlsPerPage unique children for one parent.
    private List<String> selectChildren(PageResult result,
                                        int depth,
                                        Set<String> globalSeen,
                                        Map<Integer, Set<String>> depthSeen) {
        List<String> children = new ArrayList<>();
        Set<String> childrenSeen = new HashSet<>();
        int added = 0;

        List<String> extracted = result.extractedUrls();
        if (extracted == null) return children;

        int childDepth = depth + 1;
        Set<String> depthSet = depthSeen.computeIfAbsent(childDepth, k -> new LinkedHashSet<>());

        for (String raw : extracted) {
            if (added >= config.maxUrlsPerPage()) break;
            if (raw == null) continue;

            String normalized = UrlUtil.normalize(raw);
            if (!UrlUtil.isHttpLike(normalized)) continue;
            if (!childrenSeen.add(normalized)) continue;

            if (config.crossLevelUniqueness()) {
                if (globalSeen.contains(normalized)) continue;
                globalSeen.add(normalized);
            } else {
                if (depthSet.contains(normalized)) continue;
                depthSet.add(normalized);
            }

            children.add(normalized);
            added++;
        }

        return children;
    }
}
