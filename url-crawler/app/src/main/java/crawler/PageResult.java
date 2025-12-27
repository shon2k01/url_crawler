package crawler;

import java.util.List;

// Result of a single fetch: URL, extracted links, and status.
public record PageResult(String url, List<String> extractedUrls, FetchStatus status) { }
