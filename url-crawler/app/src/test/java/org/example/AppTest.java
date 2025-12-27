package org.example;

import crawler.FailureLogger;
import crawler.OutputManager;
import crawler.UrlUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AppTest {
    @Test
    void safeFilenameIsReadableByDefault() {
        String name = UrlUtil.toSafeFilename("https://example.com/a-b");

        assertTrue(name.endsWith(".html"));
        assertFalse(name.contains("__"));
    }

    @Test
    void outputManagerAvoidsFilenameCollision(@TempDir Path tempDir) throws Exception {
        OutputManager outputManager = new OutputManager(tempDir, new FailureLogger());

        outputManager.saveHtml(0, "https://example.com/a-b", "<html></html>");
        outputManager.saveHtml(0, "https://example.com/a_b", "<html></html>");

        Path depthDir = tempDir.resolve("0");
        try (Stream<Path> paths = Files.list(depthDir)) {
            assertEquals(2L, paths.count());
        }
    }
}
