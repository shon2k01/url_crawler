package crawler;

// Lightweight failure detail for failures.csv.
public record FailureRecord(int depth, String url, String type, String message) { }
