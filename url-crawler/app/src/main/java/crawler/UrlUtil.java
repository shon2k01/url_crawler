package crawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class UrlUtil {

    // Create a filesystem-safe filename for a URL.
    // We keep a short readable prefix + add a hash to avoid Windows path/length issues.
    public static String toSafeFilename(String url) {
        // Replace anything not [a-zA-Z0-9] with underscore, so it works as a filename.
        String safe = url.replaceAll("[^a-zA-Z0-9]+", "_");

        // avoid ridiculously long filenames on URLs with long querystrings
        String hash = shortHash(url);
        int maxPrefix = 80; // keep it short-ish; hash guarantees uniqueness
        if (safe.length() > maxPrefix) safe = safe.substring(0, maxPrefix);

        return safe + "__" + hash + ".html";
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            // 12 hex chars is plenty for collisions to be extremely unlikely here
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            // fallback if crypto is unavailable (rare)
            return Integer.toHexString(s.hashCode());
        }
    }

    // Normalize URL a bit (remove fragments, normalize path).
    public static String normalize(String url) {
        try {
            URI uri = new URI(url).normalize();
            // remove fragment (#...)
            uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
            return uri.toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    public static boolean isHttpLike(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    // Clean up common malformed hrefs before resolving against base URL.
    public static String cleanHref(String href) {
        if (href == null) return null;
        String s = href.trim();
        if (s.isEmpty()) return null;

        // Strip surrounding quotes if present
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // If there are extra tokens (like target="_blank"), keep only the first token
        int ws = s.indexOf(' ');
        if (ws > 0) s = s.substring(0, ws).trim();

        // Drop trailing quote/angle bracket artifacts
        while (s.endsWith("\"") || s.endsWith("'") || s.endsWith(">")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        return s.isEmpty() ? null : s;
    }

    // (assuming you already added this earlier)
    public static String resolveAgainst(String baseUrl, String href) {
        // Resolves relative links like "/wiki/X" into absolute "https://en.wikipedia.org/wiki/X"
        try {
            URI base = new URI(baseUrl);
            URI rel = new URI(href);
            URI resolved = base.resolve(rel);
            return resolved.toString();
        } catch (Exception e) {
            // Bad hrefs exist in the wild; just skip them
            return null;
        }
    }

    public static String escapeForHtmlComment(String s) {
        if (s == null) return "";
        return s.replace("--", "__");
    }
}
