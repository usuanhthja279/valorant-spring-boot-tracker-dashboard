package com.valorant.tracker.service.selenium;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Isolated Selenium proof of concept.
 *
 * This service does not write to TrackerService or the production feed.
 *
 * Important:
 * YouTube's Live page initially loads "My region" content and then
 * asynchronously replaces the grid after Worldwide is selected.
 *
 * Therefore we explicitly wait for the video grid to change before
 * collecting any Worldwide streams.
 */
@Service
public class YouTubeSeleniumWorldwideDiscoveryService {

  private static final Logger log = LoggerFactory.getLogger(YouTubeSeleniumWorldwideDiscoveryService.class);

  private static final Pattern VIDEO_ID = Pattern.compile("(?:watch\\?v=|/live/|youtu\\.be/)([A-Za-z0-9_-]{11})");
  private static final Pattern VIEWERS = Pattern.compile("([\\d,.]+)\\s*([KMB]?)\\s+watching", Pattern.CASE_INSENSITIVE);

  private final ObjectMapper mapper;
  private final WebClient http;
  private final String defaultPageUrl;
  private final String apiKey;
  private final boolean headless;

  public YouTubeSeleniumWorldwideDiscoveryService(
          ObjectMapper mapper,
          WebClient.Builder builder,
          @Value("${tracker.youtube.selenium.live-url:https://www.youtube.com/channel/UCiMRGE8Sc6oxIGuu_JxFoHg/live}")
          String defaultPageUrl,
          @Value("${tracker.youtube.api-key:}") String apiKey,
          @Value("${tracker.youtube.selenium.headless:true}") boolean headless) {

    this.mapper = mapper;
    this.http = builder.clone().build();
    this.defaultPageUrl = defaultPageUrl;
    this.apiKey = apiKey;
    this.headless = headless;
  }

  public Map<String, Object> discover(int requestedMax, String requestedUrl, String region, boolean validateWithApi) {

    int max = Math.clamp(requestedMax, 1, 1000);
    String pageUrl = requestedUrl == null || requestedUrl.isBlank() ? defaultPageUrl : requestedUrl.trim();

    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> diagnostics = new LinkedHashMap<>();
    List<LiveStream> streams = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Instant started = Instant.now();

    log.info("YouTube Selenium scraping started | requestedMax={} | url={}", max, pageUrl);

    WebDriver driver = null;
    int scrolls = 0;
    int cardsSeen = 0;
    boolean worldwideClicked = false;
    try {
      validateYoutubeUrl(pageUrl);
      ChromeOptions options = new ChromeOptions();
      if (headless) {
        options.addArguments("--headless=new");
      }
      // Capture Chrome network events so we can parse YouTube's internal
      // /youtubei/v1/browse response directly after Worldwide is selected.
      options.setCapability("goog:loggingPrefs", Map.of("performance", "ALL"));

      options.addArguments(
              "--disable-gpu",
              "--no-sandbox",
              "--disable-dev-shm-usage",
              "--window-size=1440,1200",
              "--lang=en-US",
              "--user-agent=Mozilla/5.0 "
                      + "(Windows NT 10.0; Win64; x64) "
                      + "AppleWebKit/537.36 "
                      + "(KHTML, like Gecko) "
                      + "Chrome/131.0.0.0 "
                      + "Safari/537.36");

      driver = new org.openqa.selenium.chrome.ChromeDriver(options);
      enableNetworkCapture(driver);
      driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
      driver.get(pageUrl);

      new WebDriverWait(driver,
              Duration.ofSeconds(20))
              .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("ytd-app, body")));

      /*
       * Give YouTube time to populate the initial
       * "My region" grid.
       */
      Thread.sleep(1800L);

      /*
       * ---------------------------------------------------------
       * STEP 1
       * Capture the initial My-region dataset.
       *
       * We do NOT add these streams to the result.
       * We only use their IDs to detect when YouTube has
       * replaced the grid after Worldwide is selected.
       * ---------------------------------------------------------
       */
      Set<String> initialRegionVideoIds = captureVisibleVideoIds(driver);
      diagnostics.put("initialRegionLabel", currentDropdownLabel(driver));
      diagnostics.put("initialRegionVideoIds", initialRegionVideoIds.size());

      /*
       * ---------------------------------------------------------
       * STEP 2
       * Select Worldwide.
       * ---------------------------------------------------------
       */
      Map<String, Object> worldwideDiagnostics = new LinkedHashMap<>();
      // Clear pre-existing network events BEFORE the Worldwide click so the
      // browse response triggered by that click is retained for capture.
      drainPerformanceLogs(driver);
      worldwideClicked = clickWorldwideIfPresent(driver, worldwideDiagnostics);
      out.put("worldwideSelectionDiagnostics", worldwideDiagnostics);

      /*
       * ---------------------------------------------------------
       * STEP 3
       * IMPORTANT FIX:
       *
       * Do not start scraping immediately after the label
       * becomes "Worldwide".
       *
       * YouTube changes the label first and refreshes the
       * actual video grid afterwards.
       * ---------------------------------------------------------
       */

      boolean networkDataFound = false;
      List<LiveStream> networkStreams = List.of();

      if (worldwideClicked) {
        // The network response is the source of truth. It contains the
        // structured gridVideoRenderer objects that YouTube itself uses
        // to populate the Worldwide grid.
        NetworkCaptureResult networkResult = waitForWorldwideNetworkResponse(driver, max, 20, diagnostics);
        networkDataFound = networkResult.found();
        networkStreams = networkResult.streams();
        diagnostics.put("worldwideNetworkResponseFound", networkDataFound);
      }

      diagnostics.put("postSelectionRegionLabel", currentDropdownLabel(driver));

      if (!worldwideClicked) {
        warnings.add("Could not select Worldwide. No Worldwide data was collected.");

        /*
         * Do not silently return My-region data.
         */
        out.put("ok", false);
        out.put("isolatedTestOnly", true);
        out.put("requestedMaxStreams", max);
        out.put("streamsFound", 0);
        out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
        out.put("diagnostics", diagnostics);
        out.put("warnings", warnings);
        out.put("streams", List.of());
        return out;
      }

      if (!networkDataFound) {
        warnings.add("Worldwide was selected, but no Worldwide /youtubei/v1/browse response containing live grid data was captured within the expected time.");

        out.put("ok", false);
        out.put("isolatedTestOnly", true);
        out.put("requestedMaxStreams", max);
        out.put("streamsFound", 0);
        out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
        out.put("diagnostics", diagnostics);
        out.put("warnings", warnings);
        out.put("streams", List.of());
        return out;
      }

      /*
       * ---------------------------------------------------------
       * STEP 4
       * NOW we begin collection.
       *
       * There is deliberately no "seen" set containing the
       * initial My-region cards.
       * ---------------------------------------------------------
       */

      // Parse the structured Worldwide network response instead of scraping
      // the rendered DOM. This avoids waiting for the visual grid to stabilize.
      Map<String, LiveStream> streamById = new LinkedHashMap<>();
      for (LiveStream stream : networkStreams) {
        if (streamById.size() >= max) break;
        streamById.putIfAbsent(stream.id(), stream);
      }
      cardsSeen = networkStreams.size();

      streams = new ArrayList<>(streamById.values());

      /*
       * ---------------------------------------------------------
       * STEP 5
       * Sort ONLY after all Worldwide cards have been collected.
       *
       * This prevents DOM/network discovery order from becoming
       * the final ranking.
       * ---------------------------------------------------------
       */
      streams.sort(Comparator.comparingLong(
                      LiveStream::viewers)
              .reversed());

      /*
       * Enforce max after sorting as well.
       */
      if (streams.size() > max) {
        streams = new ArrayList<>(streams.subList(0, max));
      }

      /*
       * ---------------------------------------------------------
       * STEP 6
       * Optional YouTube Data API validation.
       * ---------------------------------------------------------
       */
      boolean apiValidationAttempted = validateWithApi && apiKey != null && !apiKey.isBlank();

      if (validateWithApi && !apiValidationAttempted) {
        warnings.add("API validation requested but tracker.youtube.api-key is empty.");
      }

      if (apiValidationAttempted) {
        streams = validateStreams(streams, warnings);

        /*
         * Re-sort after API validation because API viewer
         * counts may differ from the initial DOM values.
         */
        streams.sort(Comparator.comparingLong(
                        LiveStream::viewers)
                .reversed());

        if (streams.size() > max) {
          streams = new ArrayList<>(streams.subList(0, max));
        }
      }

      if (streams.isEmpty()) {
        warnings.add("No Worldwide video cards were parsed. "
                + "Check the configured page URL, "
                + "consent screen, or YouTube DOM changes.");
      }

      /*
       * ---------------------------------------------------------
       * Diagnostics
       * ---------------------------------------------------------
       */

      diagnostics.put("pageUrl", pageUrl);
      diagnostics.put("worldwideSelectorClicked", worldwideClicked);
      diagnostics.put("cardsSeen", cardsSeen);
      diagnostics.put("uniqueVideoIds", streams.size());
      diagnostics.put("scrollsPerformed", 0);
      diagnostics.put("unchangedRounds", 0);
      diagnostics.put("collectionSource", "YouTube /youtubei/v1/browse network response");
      diagnostics.put("apiValidationAttempted", apiValidationAttempted);
      diagnostics.put("apiValidationNote",
              apiValidationAttempted
                      ? "Only videos returned by the Data API as currently live are retained; viewer counts may be refreshed from API."
                      : "No Data API validation performed; Selenium card data is discovery-only.");
      out.put("ok", true);
      out.put("isolatedTestOnly", true);
      out.put("requestedMaxStreams", max);
      out.put("streamsFound", streams.size());
      out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
      out.put("diagnostics", diagnostics);
      out.put("warnings", warnings);
      out.put("streams", streams);
    } catch (Exception e) {
      log.warn("Selenium YouTube discovery failed: {}", e.toString());

      out.put("ok", false);
      out.put("isolatedTestOnly", true);
      out.put("requestedMaxStreams", max);
      out.put("streamsFound", streams.size());
      out.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
      diagnostics.put("pageUrl", pageUrl);
      diagnostics.put("cardsSeen", cardsSeen);
      diagnostics.put("scrollsPerformed", scrolls);
      diagnostics.put("worldwideSelectorClicked", worldwideClicked);
      out.put("diagnostics", diagnostics);
      out.put("error", e.getMessage() == null
              ? e.getClass().getSimpleName()
              : e.getMessage());
      out.put("warnings", warnings);
      out.put("streams", streams);
    } finally {

      if (driver != null) {
        try {
          driver.quit();
        } catch (Exception ignored) {
        }
      }

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
              "YouTube Selenium scraping completed | ok={} | streams={} | viewers={} | elapsedMs={} | error={} | warnings={}",
              Boolean.TRUE.equals(out.get("ok")),
              resultCount,
              totalViewers,
              elapsedMs,
              out.get("error"),
              out.get("warnings"));

      if (resultStreams instanceof List<?> list) {
        int limit = Math.min(20, list.size());
        log.info("YouTube Selenium Top 20:");
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
   * Convenience method for SeleniumTrackerService.
   */
  public List<LiveStream> fetch() {
    return fetch(100, "worldwide", defaultPageUrl);
  }

  /** Fetch up to the requested number of Worldwide live videos for the aggregate tracker. */
  public List<LiveStream> fetch(int maxStreams, String region, String pageUrl) {

    Map<String, Object> result = discover(maxStreams, pageUrl, region, true);
    Object value = result.get("streams");

    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException("YouTube Selenium discovery returned no stream list");
    }

    List<LiveStream> streams = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof LiveStream stream) {
        streams.add(stream);
      }
    }

    if (!Boolean.TRUE.equals(result.get("ok"))) {
      throw new IllegalStateException(String.valueOf(
              result.getOrDefault("error", "unknown YouTube Selenium error")));
    }

    return List.copyOf(streams);
  }

  private void enableNetworkCapture(WebDriver driver) {
    try {
      if (driver instanceof ChromiumDriver chromium) {
        chromium.executeCdpCommand("Network.enable", Map.of());
      }
    } catch (Exception e) {
      log.debug("Could not enable Chrome Network domain: {}", e.getMessage());
    }
  }

  private void drainPerformanceLogs(WebDriver driver) {
    try {
      driver.manage().logs().get(LogType.PERFORMANCE);
    } catch (Exception e) {
      log.debug("Could not drain Chrome performance logs: {}", e.getMessage());
    }
  }

  private NetworkCaptureResult waitForWorldwideNetworkResponse(
          WebDriver driver,
          int max,
          int seconds,
          Map<String, Object> diagnostics) {

    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    int browseResponses = 0;
    int browseBodiesRead = 0;
    int rendererResponses = 0;

    while (System.nanoTime() < deadline) {
      try {
        var entries = driver.manage().logs().get(LogType.PERFORMANCE);
        for (var entry : entries) {
          JsonNode message = mapper.readTree(entry.getMessage());
          JsonNode params = message.path("message").path("params");
          if (!"Network.responseReceived".equals(message.path("message").path("method").asText())) {
            continue;
          }

          JsonNode response = params.path("response");
          String url = response.path("url").asText("");
          if (!url.contains("/youtubei/v1/browse")) {
            continue;
          }

          browseResponses++;
          String requestId = params.path("requestId").asText("");
          if (requestId.isBlank()) continue;

          Map<String, Object> bodyArgs = Map.of("requestId", requestId);
          Map<String, Object> bodyResult;
          try {
            if (!(driver instanceof ChromiumDriver chromium)) continue;
            bodyResult = chromium.executeCdpCommand("Network.getResponseBody", bodyArgs);
          } catch (Exception bodyError) {
            // The response may not be complete yet; a later performance event can be retried.
            continue;
          }

          browseBodiesRead++;
          String body = String.valueOf(bodyResult.getOrDefault("body", ""));
          if (body.isBlank()) continue;

          JsonNode root;
          try {
            root = mapper.readTree(body);
          } catch (Exception parseError) {
            continue;
          }

          List<LiveStream> parsed = parseWorldwideBrowseResponse(root, max);
          if (!parsed.isEmpty()) {
            rendererResponses++;
            diagnostics.put("worldwideBrowseResponseUrl", url);
            diagnostics.put("worldwideBrowseResponsesSeen", browseResponses);
            diagnostics.put("worldwideBrowseBodiesRead", browseBodiesRead);
            diagnostics.put("worldwideGridVideoRendererResponses", rendererResponses);
            diagnostics.put("worldwideStreamsFromNetwork", parsed.size());
            return new NetworkCaptureResult(true, parsed);
          }
        }
        Thread.sleep(200L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.debug("Waiting for Worldwide YouTube network response: {}", e.getMessage());
        try {
          Thread.sleep(250L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    diagnostics.put("worldwideBrowseResponsesSeen", browseResponses);
    diagnostics.put("worldwideBrowseBodiesRead", browseBodiesRead);
    diagnostics.put("worldwideGridVideoRendererResponses", rendererResponses);
    diagnostics.put("worldwideNetworkWaitSeconds", seconds);
    return new NetworkCaptureResult(false, List.of());
  }

  private List<LiveStream> parseWorldwideBrowseResponse(JsonNode root, int max) {
    Map<String, LiveStream> byId = new LinkedHashMap<>();
    collectGridVideoRenderers(root, byId, max);
    return new ArrayList<>(byId.values());
  }

  private void collectGridVideoRenderers(
          JsonNode node,
          Map<String, LiveStream> byId,
          int max) {

    if (node == null || node.isMissingNode() || node.isNull() || byId.size() >= max) return;

    if (node.isObject()) {
      JsonNode renderer = node.get("gridVideoRenderer");
      if (renderer != null && renderer.isObject()) {
        LiveStream stream = parseGridVideoRenderer(renderer);
        if (stream != null && stream.id() != null && !stream.id().isBlank()) {
          byId.putIfAbsent(stream.id(), stream);
        }
      }

      node.fields().forEachRemaining(entry -> {
        if (byId.size() < max) collectGridVideoRenderers(entry.getValue(), byId, max);
      });
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (byId.size() >= max) break;
        collectGridVideoRenderers(child, byId, max);
      }
    }
  }

  private LiveStream parseGridVideoRenderer(JsonNode video) {
    String id = video.path("videoId").asText("");
    if (id.isBlank()) return null;

    String title = firstRunText(video.path("title"));
    String channel = firstRunText(video.path("shortBylineText"));
    String channelId = video.path("shortBylineText")
            .path("runs").path(0)
            .path("navigationEndpoint")
            .path("browseEndpoint")
            .path("browseId").asText("");

    String viewersText = firstRunText(video.path("viewCountText"));
    long viewers = parseViewers(viewersText);

    boolean live = false;
    for (JsonNode badge : video.path("badges")) {
      if ("LIVE".equalsIgnoreCase(
              badge.path("metadataBadgeRenderer").path("label").asText(""))) {
        live = true;
        break;
      }
    }
    if (!live) return null;

    return new LiveStream(
            "YouTube",
            id,
            channelId,
            channel,
            title,
            Math.max(0, viewers),
            "https://www.youtube.com/watch?v=" + id);
  }

  private String firstRunText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }

    JsonNode runs = node.path("runs");
    if (runs.isArray() && runs.size() > 0) {
      StringBuilder text = new StringBuilder();
      for (JsonNode run : runs) {
        text.append(run.path("text").asText(""));
      }
      return text.toString().trim();
    }

    return node.path("simpleText").asText("").trim();
  }

  private record NetworkCaptureResult(boolean found, List<LiveStream> streams) {}

  /**
   * Capture IDs currently present in the DOM.
   *
   * This is used ONLY for detecting the transition from
   * My region -> Worldwide.
   */
  private Set<String> captureVisibleVideoIds(WebDriver driver) {
    Set<String> ids = new LinkedHashSet<>();
    for (WebElement anchor : findVideoAnchors(driver)) {
      String href = safeAttribute(anchor, "href");
      Matcher matcher = VIDEO_ID.matcher(href);
      if (matcher.find()) {
        ids.add(matcher.group(1));
      }
    }
    return ids;
  }

  /**
   * Wait until YouTube has actually replaced the initial
   * region grid.
   *
   * Merely seeing "Worldwide" in the dropdown is NOT enough.
   */
  private boolean waitForWorldwideGridRefresh(WebDriver driver, Set<String> initialIds, int seconds) {
    long deadline =
            System.nanoTime()
                    + Duration
                    .ofSeconds(seconds)
                    .toNanos();

    int stableWorldwideRounds = 0;

    while (
            System.nanoTime() < deadline) {

      try {

        String label =
                currentDropdownLabel(driver);

        if (!"Worldwide"
                .equalsIgnoreCase(label)) {

          Thread.sleep(250L);
          continue;
        }

        Set<String> currentIds =
                captureVisibleVideoIds(driver);

        if (currentIds.isEmpty()) {

          stableWorldwideRounds = 0;

          Thread.sleep(300L);
          continue;
        }

        int overlap = 0;

        for (String id :
                currentIds) {

          if (initialIds.contains(id)) {
            overlap++;
          }
        }

        double overlapRatio =
                initialIds.isEmpty()
                        ? 0.0
                        : (double) overlap
                        / (double) Math.max(
                        1,
                        Math.min(
                                initialIds.size(),
                                currentIds.size()));

        /*
         * YouTube can retain a few old cards during the
         * transition. We don't require zero overlap.
         *
         * We require the majority of the visible dataset
         * to have changed.
         */
        boolean substantiallyChanged =
                overlapRatio <= 0.30
                        || currentIds.size() >= 6
                        && !currentIds.equals(initialIds);

        if (substantiallyChanged) {

          stableWorldwideRounds++;

        } else {

          stableWorldwideRounds = 0;
        }

        /*
         * Require two consecutive observations.
         * This prevents us from scraping the intermediate
         * transition frame.
         */
        if (stableWorldwideRounds >= 2) {

          return true;
        }

        Thread.sleep(500L);

      } catch (InterruptedException e) {

        Thread.currentThread()
                .interrupt();

        return false;

      } catch (Exception e) {

        log.debug(
                "Waiting for Worldwide grid refresh: {}",
                e.getMessage());

        try {
          Thread.sleep(300L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread()
                  .interrupt();

          return false;
        }
      }
    }

    return false;
  }

  private List<WebElement> findVideoAnchors(
          WebDriver driver) {

    return driver.findElements(
            By.cssSelector(
                    "a#thumbnail[href*='watch?v='], "
                            + "a[href*='/live/'], "
                            + "ytd-video-renderer a#video-title, "
                            + "ytd-rich-grid-media a#video-title"));
  }

  private List<LiveStream> validateStreams(List<LiveStream> found, List<String> warnings) {

    List<LiveStream> validated = new ArrayList<>();

    for (int start = 0; start < found.size(); start += 50) {
      List<LiveStream> batch =
              found.subList(start, Math.min(start + 50, found.size()));

      String ids = batch.stream().map(LiveStream::id).reduce((a, b) -> a + "," + b).orElse("");
      try {
        String body = http.get().uri(uriBuilder ->
                        uriBuilder.scheme("https")
                                .host("www.googleapis.com")
                                .path("/youtube/v3/videos")
                                .queryParam("part", "snippet,liveStreamingDetails,status")
                                .queryParam("id", ids)
                                .queryParam("key", apiKey)
                                .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(20));

        JsonNode root = mapper.readTree(body == null ? "{}" : body);
        Map<String, JsonNode> apiById = new LinkedHashMap<>();
        root.path("items")
                .forEach(item -> apiById.put(item.path("id").asText(), item));

        for (LiveStream stream : batch) {
          JsonNode item = apiById.get(stream.id());
          if (item == null) {
            continue;
          }

          String broadcastState = item.path("snippet").path("liveBroadcastContent").asText("");
          if (!"live".equalsIgnoreCase(broadcastState)) {
            continue;
          }

          long viewers =
                  item.path("liveStreamingDetails")
                          .path("concurrentViewers")
                          .asLong(stream.viewers());

          String title = item.path("snippet")
                  .path("title")
                  .asText(stream.title());

          String channel = item.path("snippet")
                  .path("channelTitle")
                  .asText(stream.channelTitle());

          String channelId = item.path("snippet")
                  .path("channelId")
                  .asText("");

          validated.add(
                  new LiveStream(
                          "YouTube",
                          stream.id(),
                          channelId,
                          channel,
                          title,
                          viewers,
                          stream.url()));
        }

      } catch (Exception e) {
        warnings.add("YouTube Data API validation failed for a batch: " + e.getMessage());

        /*
         * Fail open for discovery-only operation.
         */
        validated.addAll(batch);
      }
    }

    return validated;
  }

  /**
   * Select Worldwide from YouTube's Live-tab region dropdown.
   */
  private boolean clickWorldwideIfPresent(
          WebDriver driver,
          Map<String, Object> diag) {

    diag.put("selectorStrategy",
            "click #label-icon dropdown arrow, then click the visible Worldwide menu item by YouTube DOM XPath");

    try {
      WebElement label = findRegionDropdownLabel(driver);

      diag.put("initialRegionLabel",
              label == null
                      ? findRegionLabel(driver)
                      : safeText(label));

      /*
       * Already Worldwide.
       */
      if (label != null
              && "Worldwide".equalsIgnoreCase(
              safeText(label))) {

        diag.put(
                "alreadySelected",
                true);

        diag.put(
                "finalRegionLabel",
                "Worldwide");

        diag.put(
                "verified",
                true);

        return true;
      }

      /*
       * Click actual dropdown trigger.
       */
      WebElement trigger =
              driver.findElement(
                      By.xpath(
                              "//*[@id='label-icon']"));

      ((JavascriptExecutor) driver)
              .executeScript(
                      "arguments[0].scrollIntoView({block:'center'});",
                      trigger);

      try {
        trigger.click();
      } catch (Exception e) {

        ((JavascriptExecutor) driver)
                .executeScript(
                        "arguments[0].click();",
                        trigger);
      }

      diag.put(
              "dropdownTriggerClicked",
              true);

      /*
       * Wait briefly for menu opening.
       */
      try {

        new WebDriverWait(
                driver,
                Duration.ofSeconds(4))
                .until(
                        d -> {
                          try {

                            return "true"
                                    .equalsIgnoreCase(
                                            trigger.getAttribute(
                                                    "aria-expanded"));

                          } catch (Exception ignored) {

                            return false;
                          }
                        });

        diag.put(
                "dropdownOpened",
                true);

      } catch (Exception e) {

        diag.put(
                "dropdownOpened",
                false);

        diag.put(
                "dropdownExpandedValue",
                safeAttribute(
                        trigger,
                        "aria-expanded"));
      }

      boolean optionClicked =
              clickWorldwideOption(
                      driver,
                      diag);

      if (optionClicked) {

        /*
         * First verify the selector itself.
         */
        boolean verified =
                waitForDropdownLabel(
                        driver,
                        "Worldwide",
                        5);

        diag.put(
                "finalRegionLabel",
                currentDropdownLabel(
                        driver));

        diag.put(
                "verified",
                verified);

        if (verified) {
          return true;
        }

        diag.put(
                "failureReason",
                "Worldwide option was clicked but dropdown label did not change");

      } else {

        diag.put(
                "failureReason",
                "Dropdown opened but no visible Worldwide menu option was clickable");
      }

      diag.put(
              "finalRegionLabel",
              currentDropdownLabel(driver));

      return false;

    } catch (Exception e) {

      diag.put(
              "error",
              e.getClass().getSimpleName()
                      + ": "
                      + e.getMessage());

      diag.put(
              "finalRegionLabel",
              currentDropdownLabel(driver));

      return false;
    }
  }

  private WebElement findRegionDropdownLabel(
          WebDriver driver) {

    try {

      List<WebElement> labels =
              driver.findElements(
                      By.cssSelector(
                              "yt-sort-filter-sub-menu-renderer "
                                      + "yt-dropdown-menu "
                                      + "tp-yt-paper-button#label "
                                      + "#label-text"));

      for (WebElement label :
              labels) {

        if (label.isDisplayed()) {
          return label;
        }
      }

    } catch (Exception ignored) {
    }

    return null;
  }

  private String currentDropdownLabel(
          WebDriver driver) {

    WebElement label =
            findRegionDropdownLabel(
                    driver);

    return label == null
            ? "not found"
            : safeText(label);
  }

  private boolean clickWorldwideOption(
          WebDriver driver,
          Map<String, Object> diag) {

    List<By> selectors =
            List.of(

                    By.xpath(
                            "//*[@id='dropdown']"
                                    + "//tp-yt-paper-listbox/"
                                    + "a[2]/tp-yt-paper-item/"
                                    + "tp-yt-paper-item-body/"
                                    + "div[1]/div"),

                    By.xpath(
                            "//*[@id='dropdown']"
                                    + "//*[@id='item-with-badge']/div"),

                    By.cssSelector(
                            "tp-yt-iron-dropdown#dropdown "
                                    + "tp-yt-paper-item"),

                    By.cssSelector(
                            "tp-yt-iron-dropdown#dropdown "
                                    + "[role='option']"),

                    By.cssSelector(
                            "tp-yt-iron-dropdown#dropdown "
                                    + "[role='menuitem']"));

    for (By selector :
            selectors) {

      for (WebElement option :
              driver.findElements(
                      selector)) {

        try {

          if (!option.isDisplayed()
                  || !option.isEnabled()) {

            continue;
          }

          if (!"Worldwide"
                  .equalsIgnoreCase(
                          safeText(option))) {

            continue;
          }

          ((JavascriptExecutor) driver)
                  .executeScript(
                          "arguments[0].scrollIntoView({block:'center'});",
                          option);

          try {

            option.click();

          } catch (Exception e) {

            ((JavascriptExecutor) driver)
                    .executeScript(
                            "arguments[0].click();",
                            option);
          }

          diag.put(
                  "worldwideOptionClicked",
                  true);

          Thread.sleep(400L);

          return true;

        } catch (Exception e) {

          diag.put(
                  "optionClickError",
                  e.getClass().getSimpleName()
                          + ": "
                          + e.getMessage());
        }
      }
    }

    /*
     * Fallback for DOM variants.
     */
    for (WebElement option :
            driver.findElements(
                    By.xpath(
                            "//*[@id='dropdown']"
                                    + "//*[normalize-space(.)='Worldwide']"))) {

      try {

        if (!option.isDisplayed()
                || !option.isEnabled()) {

          continue;
        }

        ((JavascriptExecutor) driver)
                .executeScript(
                        "arguments[0].click();",
                        option);

        diag.put(
                "worldwideOptionClicked",
                true);

        Thread.sleep(400L);

        return true;

      } catch (Exception e) {

        diag.put(
                "optionClickError",
                e.getClass().getSimpleName()
                        + ": "
                        + e.getMessage());
      }
    }

    diag.put(
            "worldwideOptionClicked",
            false);

    return false;
  }

  private boolean waitForDropdownLabel(
          WebDriver driver,
          String expected,
          int seconds) {

    long deadline =
            System.nanoTime()
                    + Duration
                    .ofSeconds(seconds)
                    .toNanos();

    while (
            System.nanoTime()
                    < deadline) {

      if (expected.equalsIgnoreCase(
              currentDropdownLabel(driver))) {

        return true;
      }

      try {

        Thread.sleep(200L);

      } catch (InterruptedException e) {

        Thread.currentThread()
                .interrupt();

        return false;
      }
    }

    return false;
  }

  private String findRegionLabel(
          WebDriver driver) {

    return currentDropdownLabel(
            driver);
  }

  private WebElement nearestCard(
          WebElement anchor,
          WebDriver driver) {

    try {

      return anchor.findElement(
              By.xpath(
                      "./ancestor::ytd-rich-item-renderer[1]"
                              + " | ./ancestor::ytd-video-renderer[1]"
                              + " | ./ancestor::ytd-grid-video-renderer[1]"));

    } catch (Exception ignored) {

      return anchor;
    }
  }

  private String findChannelName(
          WebElement card) {

    for (String selector :
            List.of(
                    "#channel-name",
                    "ytd-channel-name",
                    "#text.ytd-channel-name")) {

      try {

        String value =
                safeText(
                        card.findElement(
                                By.cssSelector(
                                        selector)));

        if (!value.isBlank()) {
          return value;
        }

      } catch (Exception ignored) {
      }
    }

    return "";
  }

  private String safeAttribute(
          WebElement el,
          String name) {

    try {

      String value =
              el.getAttribute(name);

      return value == null
              ? ""
              : value.trim();

    } catch (Exception e) {

      return "";
    }
  }

  private String safeText(WebElement el) {
    try {
      String value = el.getText();
      return value == null ? "" : value.trim();
    } catch (Exception e) {
      return "";
    }
  }

  private String firstNonBlank(String... values) {

    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private long parseViewers(String text) {

    Matcher matcher = VIEWERS.matcher(text == null ? "" : text);
    if (!matcher.find()) {
      return 0;
    }

    try {
      double number = Double.parseDouble(matcher.group(1).replace(",", ""));
      double multiplier =
              switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
                case "K" -> 1_000d;
                case "M" -> 1_000_000d;
                case "B" -> 1_000_000_000d;
                default -> 1d;
              };
      return Math.round(number * multiplier);
    } catch (Exception e) {
      return 0;
    }
  }

  private void validateYoutubeUrl(String value) {
    URI uri = URI.create(value);
    String host = uri.getHost();

    if (host == null
            || !(host.equalsIgnoreCase("youtube.com")
            || host.endsWith(".youtube.com")
            || host.equalsIgnoreCase("www.youtube.com"))) {

      throw new IllegalArgumentException("pageUrl must be a youtube.com URL");
    }
  }
}