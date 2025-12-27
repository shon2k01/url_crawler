# URL Crawler

Simple multi-threaded URL crawler that fetches pages, stores HTML, and writes
the selected child links per page. It is designed as a CLI tool and outputs a
timestamped run folder for each crawl.

Project lives under `url-crawler/`. Java sources are in
`url-crawler/app/src/main/java/crawler`.

## Quick start

From the project folder:

```powershell
cd url-crawler
.\gradlew run --args="https://www.ynetnews.com 5 2 true"
```

Arguments:
1. startUrl
2. maxUrlsPerPage
3. maxDepth
4. crossLevelUniqueness (true/false)

Depth is zero-based. For example, `maxDepth=0` fetches only the start URL, and
`maxDepth=1` fetches the start URL and its direct children.

## Output layout

Each run writes into `url-crawler/output/<runId>/`:

- `<depth>/<safe_url>.html` - saved HTML for each fetched page.
- `<depth>/<safe_url>.links.txt` - selected child URLs for that page.
- `failures.csv` - failures captured during the run.

Filenames are based on a sanitized URL. Hashes are only added when the base
filename would be too long or would collide within the same depth.

## Crawl flow

1. `Main` parses CLI args, validates input, and creates a `CrawlerConfig`.
2. `UrlCrawler` initializes output storage, the fetcher, and the thread pool.
3. The root URL (depth 0) is submitted to the executor.
4. As each fetch completes, the crawler:
   - updates status counters,
   - selects up to `maxUrlsPerPage` unique child URLs,
   - writes the `.links.txt` file,
   - submits child URLs for the next depth (unless max depth is reached).
5. On shutdown or completion, the crawler writes `failures.csv` and prints a
   summary.

Note: the crawl is not breadth-first by depth. It processes pages as soon as
their fetches finish.

## Class responsibilities

- `Main`: CLI entry point, argument parsing, run ID creation.
- `CrawlerConfig`: immutable config for a run (start URL, limits, run ID).
- `UrlCrawler`: orchestration, concurrency, URL de-duplication, summary stats.
- `PageFetcher`: HTTP fetch via Jsoup, HTML saving, link extraction.
- `OutputManager`: output directory layout, HTML saving, `.links.txt` writing,
  and failure CSV writing.
- `UrlUtil`: URL normalization, link cleanup/resolution, filename sanitizing.
- `PageResult`: fetch result (URL, extracted URLs, status).
- `FetchStatus`: enum for OK, TIMEOUT, FAILED.
- `FailureLogger` / `FailureRecord`: collect failures for `failures.csv`.

## URL handling

- Normalization removes fragments and normalizes paths.
- Only `http` and `https` links are accepted.
- Child selection is capped per page and de-duplicates within the page.
- If `crossLevelUniqueness` is `true`, URLs are globally de-duped across depths.
  If `false`, URLs can reappear at deeper levels.

## Concurrency

The crawler uses a fixed-size thread pool and an `ExecutorCompletionService` to
process pages as soon as they finish. In-flight tasks are tracked, and shutdown
stops new submissions while allowing active requests to finish.

## Failure handling

- Timeouts and IO errors are recorded in `failures.csv`.
- Failed fetches still write an HTML placeholder with a short error comment.
- The run summary prints totals for OK, TIMEOUT, and FAILED.

## Tests

```powershell
cd url-crawler
.\gradlew test
```

## Notes and limitations
- Concurrency level is fixed to `max(4, availableProcessors)`.
- URL canonicalization is basic; different query strings or trailing slashes
  may still be treated as distinct URLs.
