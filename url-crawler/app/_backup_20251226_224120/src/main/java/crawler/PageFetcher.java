package crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PageFetcher {

    private final OutputManager outputManager;
    private final FailureLogger failureLogger;

    public PageFetcher(OutputManager outputManager, FailureLogger failureLogger) {
        this.outputManager = outputManager;
        this.failureLogger = failureLogger;
    }

    public PageResult fetchAndSave(String url, int depth, boolean shuttingDown) {
        if (shuttingDown) return new PageResult(url, List.of(), FetchStatus.FAILED);

        try {
            // Fetch with Jsoup
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; UrlCrawler/1.0)")
                    .timeout((int) Duration.ofSeconds(15).toMillis())
                    .followRedirects(true)
                    .get();

            // Save HTML to output/<runId>/<depth>/<safe_url>.html
            outputManager.saveHtml(depth, url, doc.outerHtml());

            // Extract URLs (absolute URLs via absUrl)
            List<String> extracted = new ArrayList<>();
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("href");                        // raw href (often relative in Wikipedia)
                String resolved;
                try {
                    resolved = UrlUtil.resolveAgainst(url, href);    // make it absolute
                } catch (Exception ex) {
                    resolved = null;
                }

                if (resolved != null && UrlUtil.isHttpLike(resolved)) {
                    extracted.add(resolved);
                }
            }

            return new PageResult(url, extracted, FetchStatus.OK);

        } catch (SocketTimeoutException e) {
            // store in failures.csv
            failureLogger.add(new FailureRecord(depth, url, "TIMEOUT", e.getMessage()));

            outputManager.saveHtml(depth, url, "<!-- timeout -->");
            return new PageResult(url, List.of(), FetchStatus.TIMEOUT);

        } catch (IOException e) {
            // store in failures.csv
            failureLogger.add(new FailureRecord(depth, url, "FAILED", e.getMessage()));

            outputManager.saveHtml(depth, url, "<!-- failed: " + UrlUtil.escapeForHtmlComment(e.getMessage()) + " -->");
            return new PageResult(url, List.of(), FetchStatus.FAILED);
        }
    }
}
