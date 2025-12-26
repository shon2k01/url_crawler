package crawler;

public record FailureRecord(int depth, String url, String type, String message) { }
