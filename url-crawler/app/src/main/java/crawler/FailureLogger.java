package crawler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FailureLogger {

    // Keep failures in memory and write them once at the end.
    private final Queue<FailureRecord> failures = new ConcurrentLinkedQueue<>();

    public void add(FailureRecord record) {
        if (record != null) failures.add(record);
    }

    public boolean isEmpty() {
        return failures.isEmpty();
    }

    public Collection<FailureRecord> snapshot() {
        return new ArrayList<>(failures);
    }
}
