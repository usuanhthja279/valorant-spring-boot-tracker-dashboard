package com.valorant.tracker.service.selenium;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.valorant.tracker.service.tracker.DataSourceCatalog;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/** Isolated Kick Selenium discovery test; does not alter the production tracker feed. */
@Service
public class KickSeleniumDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(KickSeleniumDiscoveryService.class);
    private static final String STREAMS_MARKER = "\\\"livestreams\\\":[";
    private final String defaultPageUrl;
    private final boolean headless;

    public KickSeleniumDiscoveryService(
            DataSourceCatalog sources,
            @Value("${tracker.kick.selenium.live-url:https://kick.com/category/valorant?sort=viewers_high_to_low}") String configuredUrl,
            @Value("${tracker.kick.selenium.headless:true}") boolean headless) {
        String sourceUrl = sources.kickScraperUrl();
        this.defaultPageUrl = configuredUrl == null || configuredUrl.isBlank()
                ? (sourceUrl == null || sourceUrl.isBlank() ? "https://kick.com/category/valorant" : sourceUrl)
                : configuredUrl.trim();
        this.headless = headless;
    }

    public Map<String, Object> discover(int requestedMax, String requestedUrl, boolean validateWithApi) {
        int max = Math.max(1, Math.min(requestedMax, 100));
        String pageUrl = requestedUrl == null || requestedUrl.isBlank() ? defaultPageUrl : requestedUrl.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<LiveStream> streams = new ArrayList<>();
        Instant started = Instant.now();
        log.info("Kick Selenium scraping started | requestedMax={} | url={}", max, pageUrl);
        WebDriver driver = null;
        int scrolls = 0;
        List<Map<String, String>> capturedNetworkRequests = new ArrayList<>();
        Map<String, String> viewerCountSources = new LinkedHashMap<>();
        try {
            if (!pageUrl.startsWith("https://kick.com/") && !pageUrl.startsWith("https://www.kick.com/")) {
                throw new IllegalArgumentException("pageUrl must be a Kick URL");
            }
            ChromeOptions options = new ChromeOptions();
            LoggingPreferences loggingPreferences = new LoggingPreferences();
            loggingPreferences.enable(LogType.PERFORMANCE, Level.ALL);
            options.setCapability("goog:loggingPrefs", loggingPreferences);
            if (headless) options.addArguments("--headless=new");
            options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage",
                    "--window-size=1440,1200", "--lang=en-US",
                    "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            driver = new org.openqa.selenium.chrome.ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            driver.get(pageUrl);
            new WebDriverWait(driver, Duration.ofSeconds(25)).until(d ->
                    !d.findElements(By.cssSelector("body")).isEmpty());
            Thread.sleep(2500L);
            captureNetworkRequests(driver, capturedNetworkRequests);

            @SuppressWarnings("unchecked")
            Map<String, Object> domDebug = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                    const all = [...document.querySelectorAll('a[href]')];
                    const visible = e => {
                        const r = e.getBoundingClientRect();
                        const s = getComputedStyle(e);
                        return r.width > 0 && r.height > 0 &&
                               s.display !== 'none' && s.visibility !== 'hidden';
                    };
                    const main = document.querySelector('main') ||
                                 document.querySelector('[role="main"]');
                    return {
                        title: document.title,
                        readyState: document.readyState,
                        bodyText: (document.body.innerText || '').slice(0, 2500),
                        mainFound: !!main,
                        totalLinks: all.length,
                        visibleLinks: all.filter(visible).length,
                        mainLinks: main
                            ? [...main.querySelectorAll('a[href]')].slice(0, 30).map(a => ({
                                text: (a.innerText || '').trim().slice(0, 120),
                                href: a.href,
                                parentText: (a.parentElement?.parentElement?.innerText || '')
                                    .trim().slice(0, 250)
                            }))
                            : [],
                        streamLikeLinks: all
                            .filter(a => /kick\\.com\\/[a-zA-Z0-9_-]+/.test(a.href))
                            .slice(0, 30)
                            .map(a => ({
                                text: (a.innerText || '').trim().slice(0, 120),
                                href: a.href,
                                visible: visible(a)
                            }))
                    };
                    """);
            diagnostics.put("domDebug", domDebug);

            diagnostics.put("sortByViewersHighToLowSelected", pageUrl.contains("sort=viewers_high_to_low"));
            diagnostics.put("sortSelectorStrategy", "URL query parameter");
            diagnostics.put("extractionScope", "Livestreams section only; recommended sidebar excluded");
            diagnostics.put("sortOrder", "viewers_descending");

            Set<String> seen = new LinkedHashSet<>();
            int unchangedRounds = 0;
            int previousCount = 0;
            while (seen.size() < max && scrolls < 10 && unchangedRounds < 3) {
                List<WebElement> anchors = findLivestreamAnchors(driver);
                diagnostics.put("livestreamAnchorsFound", anchors.size());
                for (WebElement anchor : anchors) {
                    if (seen.size() >= max) break;
                    String href = safeAttribute(anchor, "href");
                    String slug = extractSlug(href);
                    if (slug.isBlank() || !seen.add(slug.toLowerCase(Locale.ROOT))) continue;
                    WebElement card = nearestCard(anchor, driver);
                    String text = safeText(card);
                    long viewers = parseViewers(text);
                    String anchorText = safeText(anchor);
                    String title = firstNonBlank(
                            safeAttribute(anchor, "data-title"),
                            safeAttribute(anchor, "title"),
                            safeAttribute(anchor, "aria-label"));
                    if (title.isBlank() && isUsefulStreamTitle(anchorText, slug)) title = anchorText;
                    if (title.isBlank()) title = extractStreamTitle(text, slug);
                    if (title.isBlank()) title = slug;
                    streams.add(new LiveStream("Kick", slug, "", slug, title, viewers, "https://kick.com/" + slug));
                    viewerCountSources.put(slug.toLowerCase(Locale.ROOT), viewers > 0 ? "DOM" : "UNAVAILABLE");
                }
                if (seen.size() == previousCount) unchangedRounds++; else unchangedRounds = 0;
                previousCount = seen.size();
                // Kick lazy-loads the Livestreams section inside its own scrollable
                // container (typically #main-container). Scrolling window/document is
                // insufficient and can leave discovery stuck at the first 24 cards.
                @SuppressWarnings("unchecked")
                Map<String, Object> scrollDebug = (Map<String, Object>) ((JavascriptExecutor) driver)
                        .executeScript("""
                                const isVisible = e => {
                                    const r = e.getBoundingClientRect();
                                    const s = getComputedStyle(e);
                                    return r.width > 0 && r.height > 0 &&
                                           s.display !== 'none' && s.visibility !== 'hidden';
                                };
                                const heading = [...document.querySelectorAll('h1,h2,h3,h4,[role=\"heading\"],a,button,div,span')]
                                    .find(e => (e.innerText || '').trim() === 'Livestreams' && isVisible(e));
                                let node = heading;
                                let target = null;
                                for (let i = 0; node && i < 12; i++, node = node.parentElement) {
                                    const style = getComputedStyle(node);
                                    const scrollable = /(auto|scroll)/.test(style.overflowY) &&
                                                       node.scrollHeight > node.clientHeight + 20;
                                    if (scrollable) { target = node; break; }
                                }
                                if (!target) {
                                    const candidates = [...document.querySelectorAll('*')].filter(e => {
                                        if (!isVisible(e)) return false;
                                        const style = getComputedStyle(e);
                                        return /(auto|scroll)/.test(style.overflowY) &&
                                               e.scrollHeight > e.clientHeight + 20;
                                    });
                                    target = candidates.sort((a,b) => b.scrollHeight - a.scrollHeight)[0] || null;
                                }
                                const before = target ? target.scrollTop : window.scrollY;
                                const maxScroll = target
                                    ? Math.max(0, target.scrollHeight - target.clientHeight)
                                    : Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
                                const delta = Math.max(650, Math.floor((target ? target.clientHeight : window.innerHeight) * 0.75));
                                if (target) target.scrollTop = Math.min(maxScroll, before + delta);
                                else window.scrollBy(0, delta);
                                return {
                                    targetFound: !!target,
                                    targetTag: target ? target.tagName : 'WINDOW',
                                    targetId: target ? (target.id || '') : '',
                                    targetClass: target ? (target.className || '').toString().slice(0, 200) : '',
                                    clientHeight: target ? target.clientHeight : window.innerHeight,
                                    scrollHeight: target ? target.scrollHeight : document.documentElement.scrollHeight,
                                    scrollTopBefore: before,
                                    scrollTopAfter: target ? target.scrollTop : window.scrollY,
                                    atBottom: target
                                        ? target.scrollTop + target.clientHeight >= target.scrollHeight - 5
                                        : window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 5
                                };
                                """);
                diagnostics.put("lastScroll", scrollDebug);
                Thread.sleep(1100L);
                captureNetworkRequests(driver, capturedNetworkRequests);
                scrolls++;
            }
            // Use Kick's embedded structured livestream data to correct DOM-derived titles/counts.
            // Keep the Selenium-discovered channel set as the allowlist, so recommended sidebar
            // channels cannot be added by the structured-data pass.
            enrichFromEmbeddedJson(streams, pageUrl, diagnostics, warnings, viewerCountSources);

            // Do not make another request for streams that already have a viewer count.
            // Only the streams still marked UNAVAILABLE are fetched individually.
            enrichMissingViewerData(streams, diagnostics, warnings, viewerCountSources);

            // Network replay is intentionally not used as a broad enrichment pass here.
            // Selenium + category embedded JSON already cover the vast majority of streams;
            // the individual fallback above is reserved for the genuinely missing counts.
            // Preserve viewer-descending order after all values have been applied.
            streams.sort(Comparator.comparingLong(LiveStream::viewers).reversed());
            diagnostics.put("pageUrl", pageUrl);
            diagnostics.put("uniqueChannels", seen.size());
            diagnostics.put("scrollsPerformed", scrolls);
            diagnostics.put("sortOrder", "viewers_descending");
            diagnostics.put("capturedStructuredNetworkRequests", capturedNetworkRequests.size());
            diagnostics.put("cardsParsed", streams.size());
            diagnostics.put("viewerCountUnavailable", streams.stream()
                    .filter(s -> "UNAVAILABLE".equals(viewerCountSources.get(s.channelTitle().toLowerCase(Locale.ROOT))))
                    .count());
            diagnostics.put("viewerCountSources", viewerCountSources);
            diagnostics.put("viewerCountUnavailableChannels", streams.stream()
                    .filter(s -> "UNAVAILABLE".equals(viewerCountSources.get(s.channelTitle().toLowerCase(Locale.ROOT))))
                    .map(LiveStream::channelTitle)
                    .toList());
            out.put("ok", true);
            out.put("isolatedTestOnly", true);
            out.put("requestedMaxStreams", max);
            out.put("streamsFound", streams.size());
            out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
            out.put("diagnostics", diagnostics);
            out.put("warnings", warnings);
            out.put("streams", streams);
        } catch (Exception e) {
            log.warn("Kick Selenium discovery failed: {}", e.toString());
            diagnostics.put("pageUrl", pageUrl);
            diagnostics.put("scrollsPerformed", scrolls);
            out.put("ok", false);
            out.put("isolatedTestOnly", true);
            out.put("requestedMaxStreams", max);
            out.put("streamsFound", streams.size());
            out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
            out.put("diagnostics", diagnostics);
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            out.put("warnings", warnings);
            out.put("streams", streams);
        } finally {
            if (driver != null) try { driver.quit(); } catch (Exception ignored) {}

            long elapsedMs = Duration.between(started, Instant.now()).toMillis();
            Object resultStreams = out.get("streams");
            int resultCount = resultStreams instanceof List<?> list ? list.size() : 0;
            long totalViewers = 0L;
            if (resultStreams instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof LiveStream stream) {
                        totalViewers += stream.viewers();
                    }
                }
            }

            log.info(
                    "Kick Selenium scraping completed | ok={} | streams={} | viewers={} | elapsedMs={}",
                    Boolean.TRUE.equals(out.get("ok")),
                    resultCount,
                    totalViewers,
                    elapsedMs);

            if (resultStreams instanceof List<?> list) {
                int limit = Math.min(20, list.size());
                log.info("Kick Selenium Top 20:");
                for (int index = 0; index < limit; index++) {
                    Object item = list.get(index);
                    if (item instanceof LiveStream stream) {
                        log.info(
                                "{}. {} -> {} viewers | {}",
                                index + 1,
                                stream.channelTitle(),
                                stream.viewers(),
                                stream.title());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Return only stream links inside the main Livestreams section. Kick's page also
     * contains recommended-channel links in the sidebar, so a document-wide query
     * is intentionally avoided.
     */
    @SuppressWarnings("unchecked")
    private List<WebElement> findLivestreamAnchors(WebDriver driver) {
        Object result = ((JavascriptExecutor) driver).executeScript("""
                const isVisible = e => {
                    const r = e.getBoundingClientRect();
                    const s = getComputedStyle(e);
                    return r.width > 0 && r.height > 0 &&
                           s.display !== 'none' && s.visibility !== 'hidden';
                };
                const channelPath = a => {
                    try {
                        const u = new URL(a.href, location.origin);
                        if (u.origin !== location.origin) return false;
                        const parts = u.pathname.split('/').filter(Boolean);
                        if (parts.length !== 1) return false;
                        return !/^(category|video|dashboard|search|settings|login|signup|browse|following)$/i.test(parts[0]);
                    } catch (_) {
                        return false;
                    }
                };

                // Anchor the search to the actual "Livestreams" heading, not the
                // whole page's <main> and not a guessed horizontal offset.
                const heading = [...document.querySelectorAll('h1,h2,h3,h4,[role="heading"],a,button,div,span')]
                    .find(e => (e.innerText || '').trim() === 'Livestreams' && isVisible(e));

                if (!heading) return [];

                let section = null;
                let node = heading;
                for (let level = 0; node && level < 9; level++, node = node.parentElement) {
                    const links = [...node.querySelectorAll('a[href]')].filter(a => isVisible(a) && channelPath(a));
                    const unique = new Set(links.map(a => new URL(a.href).pathname.toLowerCase()));
                    const text = node.innerText || '';
                    if (unique.size > 0 && /Sort by:|Viewers \\(High to Low\\)|Filter by:/i.test(text)) {
                        section = node;
                        break;
                    }
                }

                if (!section) {
                    // Fallback: find the smallest visible ancestor containing the
                    // heading and at least one channel card with the VALORANT label.
                    node = heading.parentElement;
                    for (let level = 0; node && level < 9; level++, node = node.parentElement) {
                        const links = [...node.querySelectorAll('a[href]')].filter(a => isVisible(a) && channelPath(a));
                        if (links.some(a => {
                            let card = a;
                            for (let i = 0; i < 5 && card.parentElement; i++) {
                                card = card.parentElement;
                                const t = card.innerText || '';
                                if (/\\bVALORANT\\b/i.test(t) && /\\b\\d[\\d,.]*\\s*[KMB]?\\b/i.test(t)) return true;
                            }
                            return false;
                        })) {
                            section = node;
                            break;
                        }
                    }
                }
                if (!section) return [];

                // Group all links by channel. Kick may expose separate links for
                // viewer count, avatar, channel name and stream title; prefer title text.
                const found = new Map();
                for (const a of [...section.querySelectorAll('a[href]')]) {
                    if (!isVisible(a) || !channelPath(a)) continue;
                    let card = a;
                    let cardText = '';
                    for (let i = 0; i < 8 && card.parentElement; i++) {
                        card = card.parentElement;
                        cardText = (card.innerText || '').trim();
                        if (/\\bVALORANT\\b/i.test(cardText) &&
                            /(?:^|\\n)\\s*(?:\\d[\\d,.]*\\s*[KMB]?|LIVE)\\s*(?:\\n|$)/im.test(cardText)) break;
                    }
                    if (!/\\bVALORANT\\b/i.test(cardText)) continue;
                    if (!/(?:^|\\n)\\s*(?:\\d[\\d,.]*\\s*[KMB]?|LIVE)\\s*(?:\\n|$)/im.test(cardText)) continue;
                    const key = new URL(a.href).pathname.toLowerCase();
                    const label = (a.innerText || '').trim();
                    const slug = key.split('/').filter(Boolean)[0] || '';
                    const isCount = /^\\d[\\d,.]*\\s*[KMB]?$/i.test(label) || /^LIVE$/i.test(label);
                    const isName = label.toLowerCase() === slug.toLowerCase();
                    const score = label && !isCount && !isName ? 3 : (isName ? 2 : 1);
                    const previous = found.get(key);
                    if (!previous || score > previous.score) found.set(key, {a, score});
                }
                return [...found.values()].map(x => x.a);
                """);

        List<WebElement> anchors = new ArrayList<>();
        if (result instanceof List<?>) {
            for (Object item : (List<?>) result) {
                if (item instanceof WebElement) {
                    anchors.add((WebElement) item);
                }
            }
        }
        return anchors;
    }

    private boolean selectViewerSort(WebDriver driver, Map<String, Object> diagnostics) {
        diagnostics.put("sortSelectorStrategy", "open Sort control and select Viewers (High to Low)");
        try {
            WebElement sort = findVisibleText(driver, "Sort");
            if (sort == null) { diagnostics.put("sortFailureReason", "Sort control not found"); return false; }
            click(driver, sort);
            diagnostics.put("sortControlClicked", true);
            Thread.sleep(400L);
            WebElement option = findVisibleText(driver, "Viewers (High to Low)");
            if (option == null) option = findVisibleText(driver, "Viewers");
            if (option == null) { diagnostics.put("sortFailureReason", "Viewers (High to Low) option not found after opening Sort"); return false; }
            click(driver, option);
            diagnostics.put("viewerSortOptionClicked", true);
            Thread.sleep(1200L);
            diagnostics.put("sortOptionText", safeText(option));
            return true;
        } catch (Exception e) {
            diagnostics.put("sortError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private WebElement findVisibleText(WebDriver driver, String exactText) {
        for (WebElement e : driver.findElements(By.xpath("//*[normalize-space(.)=" + xpathLiteral(exactText) + "]"))) {
            try { if (e.isDisplayed() && e.getSize().getHeight() > 0) return e; } catch (Exception ignored) {}
        }
        return null;
    }

    private void click(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
        try { element.click(); }
        catch (Exception e) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element); }
    }

    private WebElement nearestCard(WebElement anchor, WebDriver driver) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript("""
                    let node = arguments[0];
                    for (let i = 0; node && i < 9; i++, node = node.parentElement) {
                        const text = (node.innerText || '').trim();
                        const hasGame = /\\bVALORANT\\b/i.test(text);
                        const hasLiveOrCount = /(?:^|\\n)\\s*(?:\\d[\\d,.]*\\s*[KMB]?|LIVE)\\s*(?:\\n|$)/im.test(text);
                        if (hasGame && hasLiveOrCount) return node;
                    }
                    return arguments[0].parentElement || arguments[0];
                    """, anchor);
            if (result instanceof WebElement) return (WebElement) result;
        } catch (Exception ignored) {}
        return anchor;
    }

    private String extractSlug(String href) {
        if (href == null || href.isBlank()) return "";
        try {
            String path = java.net.URI.create(href).getPath();
            if (path == null || path.isBlank() || path.equals("/")) return "";
            String slug = path.substring(1).split("/")[0];
            if (slug.equalsIgnoreCase("category") || slug.equalsIgnoreCase("video") || slug.equalsIgnoreCase("dashboard") || slug.equalsIgnoreCase("search")) return "";
            return slug.matches("[A-Za-z0-9_-]{2,30}") ? slug : "";
        } catch (Exception ignored) { return ""; }
    }

    private long parseViewers(String text) {
        if (text == null || text.isBlank()) return 0;
        // Only accept a standalone count line. Never treat numbers from titles,
        // languages, or other card metadata as the viewer count.
        Pattern countLine = Pattern.compile("^\\s*([\\d,.]+)\\s*([KMB]?)\\s*$", Pattern.CASE_INSENSITIVE);
        for (String line : text.split("\\R")) {
            Matcher matcher = countLine.matcher(line);
            if (!matcher.matches()) continue;
            try {
                double value = Double.parseDouble(matcher.group(1).replace(",", ""));
                String suffix = matcher.group(2).toUpperCase(Locale.ROOT);
                if (suffix.equals("K")) value *= 1_000;
                else if (suffix.equals("M")) value *= 1_000_000;
                else if (suffix.equals("B")) value *= 1_000_000_000;
                if (value >= 0 && value < Long.MAX_VALUE) return Math.round(value);
            } catch (NumberFormatException ignored) {}
        }
        // Kick sometimes renders only a LIVE badge, without a numeric count.
        // Zero here means "count not exposed by the card", not "offline".
        return 0;
    }

    private boolean isUsefulStreamTitle(String value, String slug) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        if (v.equalsIgnoreCase(slug) || v.equalsIgnoreCase("LIVE")) return false;
        if (v.matches("(?i)\\d[\\d,.]*\\s*[KMB]?")) return false;
        if (v.equalsIgnoreCase("VALORANT")) return false;
        return true;
    }

    private String extractStreamTitle(String cardText, String slug) {
        if (cardText == null || cardText.isBlank()) return "";
        for (String raw : cardText.split("\\R")) {
            String line = raw.trim();
            if (line.isBlank() || line.equalsIgnoreCase(slug) ||
                    line.equalsIgnoreCase("VALORANT") || line.equalsIgnoreCase("LIVE") ||
                    line.matches("(?i)\\d[\\d,.]*\\s*[KMB]?") ||
                    line.matches("(?i)\\d+\\s*(watching|followers)") ||
                    line.matches("(?i)18\\+")) continue;
            // Language tags are short known labels, not stream titles.
            if (line.matches("(?i)(English|English \\(India\\)|Thai|Turkish|Arabic|Portuguese|Spanish|Hebrew|Japanese|Russian|French|German|Korean|Chinese)")) continue;
            return line;
        }
        return "";
    }

    private void captureNetworkRequests(WebDriver driver, List<Map<String, String>> requests) {
        try {
            for (org.openqa.selenium.logging.LogEntry entry : driver.manage().logs().get(LogType.PERFORMANCE)) {
                try {
                    JsonNode envelope = new ObjectMapper().readTree(entry.getMessage());
                    JsonNode message = envelope.path("message");
                    if (!"Network.requestWillBeSent".equals(message.path("method").asText())) continue;
                    JsonNode request = message.path("params").path("request");
                    String url = request.path("url").asText("");
                    String method = request.path("method").asText("GET");
                    if (!isLikelyKickStructuredUrl(url)) continue;
                    Map<String, String> candidate = new LinkedHashMap<>();
                    candidate.put("method", method);
                    candidate.put("url", url);
                    candidate.put("postData", request.path("postData").asText(""));
                    String key = method + "\\n" + url + "\\n" + candidate.get("postData");
                    boolean alreadySeen = requests.stream().anyMatch(r ->
                            key.equals(r.get("method") + "\\n" + r.get("url") + "\\n" + r.get("postData")));
                    if (!alreadySeen) requests.add(candidate);
                } catch (Exception ignored) {
                    // Performance-log entries are best-effort diagnostics for this isolated test.
                }
            }
        } catch (Exception ignored) {
            // Browser/logging support can vary by driver; Selenium DOM discovery still works.
        }
    }

    private boolean isLikelyKickStructuredUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://kick.com/") || lower.startsWith("https://api.kick.com/"))) return false;
        return lower.contains("livestream") || lower.contains("stream") || lower.contains("category")
                || lower.contains("graphql") || lower.contains("channel");
    }

    /**
     * Fetch only streams whose viewer count is still unavailable after DOM + category JSON enrichment.
     *
     * This is deliberately a per-channel fallback. On the current Kick page, most DOM-only streams
     * already expose a usable viewer count, so fetching every DOM-only stream would be unnecessary.
     */
    private void enrichMissingViewerData(List<LiveStream> streams,
                                         Map<String, Object> diagnostics,
                                         List<String> warnings,
                                         Map<String, String> viewerCountSources) {
        List<LiveStream> missing = streams.stream()
                .filter(stream -> "UNAVAILABLE".equals(
                        viewerCountSources.get(stream.channelTitle().toLowerCase(Locale.ROOT))))
                .toList();

        diagnostics.put("missingViewerFallbackCandidates", missing.size());
        if (missing.isEmpty()) {
            diagnostics.put("missingViewerFallbackCalls", 0);
            diagnostics.put("missingViewerFallbackResolved", 0);
            return;
        }

        WebClient webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
        int calls = 0;
        int resolved = 0;
        List<LiveStream> corrected = new ArrayList<>(streams);

        for (LiveStream stream : missing) {
            calls++;
            try {
                String html = webClient.get()
                        .uri(stream.url())
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(10));

                Long viewerCount = extractViewerCountFromChannelPage(html);
                if (viewerCount == null) continue;

                for (int i = 0; i < corrected.size(); i++) {
                    LiveStream current = corrected.get(i);
                    if (!current.channelTitle().equalsIgnoreCase(stream.channelTitle())) continue;
                    corrected.set(i, new LiveStream(
                            current.platform(),
                            current.id(),
                            current.channelId(),
                            current.channelTitle(),
                            current.title(),
                            viewerCount,
                            current.url()));
                    viewerCountSources.put(current.channelTitle().toLowerCase(Locale.ROOT), "CHANNEL_PAGE");
                    resolved++;
                    break;
                }
            } catch (Exception e) {
                warnings.add("Viewer fallback failed for " + stream.channelTitle() + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        streams.clear();
        streams.addAll(corrected);
        diagnostics.put("missingViewerFallbackCalls", calls);
        diagnostics.put("missingViewerFallbackResolved", resolved);
        diagnostics.put("missingViewerFallbackSource", "CHANNEL_PAGE");
    }

    /**
     * Kick channel pages may expose viewer_count either as normal JSON or as escaped serialized data.
     * Keep this parser intentionally narrow: we only need a numeric viewer_count for the fallback.
     */
    private Long extractViewerCountFromChannelPage(String html) {
        if (html == null || html.isBlank()) return null;

        Pattern[] patterns = new Pattern[] {
                Pattern.compile("\\\"viewer_count\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\\\\\\"viewer_count\\\\\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("viewer_count(?:\\\\\\\"|\\\")?\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // Try the next representation.
                }
            }
        }
        return null;
    }

    private void enrichFromCapturedNetworkJson(WebDriver driver, List<LiveStream> streams,
                                               List<Map<String, String>> requests,
                                               Map<String, Object> diagnostics, List<String> warnings,
                                               Map<String, String> viewerCountSources) {
        if (streams.isEmpty() || requests.isEmpty()) return;
        Map<String, JsonNode> structuredBySlug = new LinkedHashMap<>();
        int jsonResponses = 0;
        int streamObjects = 0;
        int matchedRequests = 0;
        try {
            for (Map<String, String> request : requests) {
                Map<String, Object> response = fetchCapturedRequest(driver, request);
                if (!Boolean.TRUE.equals(response.get("ok"))) continue;
                String contentType = String.valueOf(response.getOrDefault("contentType", ""));
                String body = String.valueOf(response.getOrDefault("body", ""));
                if (body.isBlank() || (!contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).contains("json"))) continue;
                JsonNode root;
                try {
                    root = new ObjectMapper().readTree(body);
                } catch (Exception ignored) {
                    continue;
                }
                jsonResponses++;
                int found = collectStructuredStreams(root, structuredBySlug);
                if (found > 0) {
                    streamObjects += found;
                    matchedRequests++;
                }
            }

            if (!structuredBySlug.isEmpty()) {
                List<LiveStream> corrected = new ArrayList<>();
                int matchedStreams = 0;
                for (LiveStream dom : streams) {
                    JsonNode item = structuredBySlug.get(dom.channelTitle().toLowerCase(Locale.ROOT));
                    if (item == null) {
                        corrected.add(dom);
                        continue;
                    }
                    corrected.add(toLiveStream(item, dom));
                    viewerCountSources.put(dom.channelTitle().toLowerCase(Locale.ROOT), "NETWORK_JSON");
                    matchedStreams++;
                }
                streams.clear();
                streams.addAll(corrected);
                diagnostics.put("networkStructuredJsonMatchedStreams", matchedStreams);
                diagnostics.put("networkStructuredJsonResponses", jsonResponses);
                diagnostics.put("networkStructuredStreamObjects", streamObjects);
                diagnostics.put("networkStructuredRequestsWithStreams", matchedRequests);
                diagnostics.put("networkViewerCountSource", "NETWORK_JSON");
            }
        } catch (Exception e) {
            warnings.add("Could not enrich Selenium results from captured Kick network JSON; existing values retained: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            diagnostics.put("networkStructuredJsonEnrichment", "failed");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCapturedRequest(WebDriver driver, Map<String, String> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String script = """
                    const done = arguments[arguments.length - 1];
                    const url = arguments[0];
                    const method = arguments[1];
                    const postData = arguments[2];
                    const headers = {};
                    if (postData) {
                        headers['Content-Type'] = postData.trim().startsWith('{') || postData.trim().startsWith('[')
                            ? 'application/json' : 'application/x-www-form-urlencoded;charset=UTF-8';
                    }
                    fetch(url, {
                        method: method,
                        body: postData || undefined,
                        credentials: 'include',
                        headers: headers
                    }).then(async response => {
                        const body = await response.text();
                        done({ok: response.ok, status: response.status,
                              contentType: response.headers.get('content-type') || '', body: body});
                    }).catch(error => done({ok: false, error: String(error)}));
                    """;
            Object raw = ((JavascriptExecutor) driver).executeAsyncScript(
                    script, request.get("url"), request.getOrDefault("method", "GET"), request.getOrDefault("postData", ""));
            if (raw instanceof Map<?, ?>) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return result;
    }

    private int collectStructuredStreams(JsonNode node, Map<String, JsonNode> bySlug) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        int found = 0;
        if (node.isObject()) {
            JsonNode channel = node.path("channel");
            String slug = channel.path("slug").asText("");
            if (!slug.isBlank() && node.has("viewer_count")) {
                bySlug.put(slug.toLowerCase(Locale.ROOT), node);
                found++;
            }
            var fields = node.fields();
            while (fields.hasNext()) found += collectStructuredStreams(fields.next().getValue(), bySlug);
        } else if (node.isArray()) {
            for (JsonNode child : node) found += collectStructuredStreams(child, bySlug);
        }
        return found;
    }

    private LiveStream toLiveStream(JsonNode item, LiveStream fallback) {
        JsonNode channel = item.path("channel");
        String slug = channel.path("slug").asText(fallback.channelTitle());
        String username = channel.path("username").asText(slug);
        return new LiveStream(
                "Kick",
                item.path("id").asText(fallback.id()),
                channel.path("id").asText(fallback.channelId()),
                username,
                item.path("title").asText(fallback.title()),
                item.path("viewer_count").asLong(fallback.viewers()),
                "https://kick.com/" + slug);
    }

    private void enrichFromEmbeddedJson(List<LiveStream> streams, String pageUrl,
                                        Map<String, Object> diagnostics, List<String> warnings,
                                        Map<String, String> viewerCountSources) {
        try {
            WebClient webClient = WebClient.builder()
                    .codecs(configurer -> configurer.defaultCodecs()
                            .maxInMemorySize(10 * 1024 * 1024))
                    .build();

            String html = webClient.get().uri(pageUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (html == null || html.isBlank()) throw new IllegalStateException("empty category HTML");
            String encoded = extractStreamsArray(html);
            String json = new ObjectMapper().readTree("\"" + encoded + "\"").asText();
            JsonNode root = new ObjectMapper().readTree(json);
            Map<String, JsonNode> bySlug = new LinkedHashMap<>();
            for (JsonNode item : root) {
                String slug = item.path("channel").path("slug").asText("");
                if (!slug.isBlank()) bySlug.put(slug.toLowerCase(Locale.ROOT), item);
            }
            int matched = 0;
            List<LiveStream> corrected = new ArrayList<>();
            for (LiveStream dom : streams) {
                JsonNode item = bySlug.get(dom.channelTitle().toLowerCase(Locale.ROOT));
                if (item == null) {
                    corrected.add(dom);
                    continue;
                }
                JsonNode channel = item.path("channel");
                String slug = channel.path("slug").asText(dom.channelTitle());
                String username = channel.path("username").asText(slug);
                corrected.add(new LiveStream(
                        "Kick",
                        item.path("id").asText(""),
                        channel.path("id").asText(""), username,
                        item.path("title").asText(dom.title()),
                        item.path("viewer_count").asLong(dom.viewers()),
                        "https://kick.com/" + slug));
                viewerCountSources.put(dom.channelTitle().toLowerCase(Locale.ROOT), "EMBEDDED_JSON");
                matched++;
            }
            streams.clear();
            streams.addAll(corrected);
            diagnostics.put("structuredJsonMatchedStreams", matched);
            diagnostics.put("viewerCountSource", "EMBEDDED_JSON when matched; DOM otherwise; UNAVAILABLE when neither exposes a count");
        } catch (Exception e) {
            warnings.add("Could not enrich Selenium results from embedded Kick JSON; DOM values retained: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            diagnostics.put("structuredJsonEnrichment", "failed");
        }
    }

    private String extractStreamsArray(String html) {
        int markerIndex = html.indexOf(STREAMS_MARKER);
        if (markerIndex < 0) throw new IllegalStateException("Kick category page did not contain livestream data");
        int start = markerIndex + STREAMS_MARKER.length() - 1;
        boolean inString = false;
        int depth = 0;
        for (int index = start; index < html.length(); index++) {
            char current = html.charAt(index);
            if (current == '"') {
                int backslashes = 0;
                for (int previous = index - 1; previous >= start && html.charAt(previous) == '\\'; previous--) backslashes++;
                if (backslashes == 1) inString = !inString;
                else if (backslashes == 0 && !inString) inString = true;
                continue;
            }
            if (inString) continue;
            if (current == '[') depth++;
            else if (current == ']' && --depth == 0) return html.substring(start, index + 1);
        }
        throw new IllegalStateException("Kick category livestream data was incomplete");
    }

    private String safeText(WebElement e) { try { return e.getText() == null ? "" : e.getText().trim(); } catch (Exception ex) { return ""; } }
    private String safeAttribute(WebElement e, String name) { try { return e.getAttribute(name) == null ? "" : e.getAttribute(name).trim(); } catch (Exception ex) { return ""; } }
    private String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return ""; }
    private String xpathLiteral(String value) { return "'" + value + "'"; }
    /** Convenience method for the Selenium aggregate tracker. */
    public List<LiveStream> fetch() {
        return fetch(100);
    }

    /** Fetch up to the requested number of Kick live streams for the aggregate tracker. */
    public List<LiveStream> fetch(int maxStreams) {
        Map<String, Object> result = discover(maxStreams, defaultPageUrl, true);
        Object value = result.get("streams");
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Kick Selenium discovery returned no stream list");
        }
        List<LiveStream> streams = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof LiveStream stream) {
                streams.add(stream);
            }
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            throw new IllegalStateException(
                    String.valueOf(result.getOrDefault("error", "unknown Kick Selenium error")));
        }
        return List.copyOf(streams);
    }

}
