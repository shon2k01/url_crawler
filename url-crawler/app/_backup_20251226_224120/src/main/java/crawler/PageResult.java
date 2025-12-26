package crawler;

import java.util.List;

public record PageResult(String url, List<String> extractedUrls, FetchStatus status) { }
