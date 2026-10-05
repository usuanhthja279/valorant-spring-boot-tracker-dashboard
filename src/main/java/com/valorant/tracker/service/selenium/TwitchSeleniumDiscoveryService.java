package com.valorant.tracker.service.selenium;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TwitchSeleniumDiscoveryService {

  private static final Logger log =
          LoggerFactory.getLogger(TwitchSeleniumDiscoveryService.class);

  private static final Pattern VIEWERS =
          Pattern.compile("([\\d,.]+)\\s*([KMB]?)\\s+viewers?", Pattern.CASE_INSENSITIVE);

  private final String defaultPageUrl;
  private final boolean headless;

  public TwitchSeleniumDiscoveryService(
          @Value("${tracker.twitch.selenium.live-url:https://www.twitch.tv/directory/category/valorant?sort=VIEWER_COUNT}")
          String defaultPageUrl,
          @Value("${tracker.twitch.selenium.headless:true}")
          boolean headless) {

    this.defaultPageUrl = defaultPageUrl;
    this.headless = headless;
  }

  /**
   * Convenience method used by SeleniumTrackerService.
   */
  public List<LiveStream> fetch() {

    Map<String, Object> result = discover(100, defaultPageUrl, false);

    Object value = result.get("streams");

    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(
              "Twitch Selenium discovery returned no stream list");
    }

    List<LiveStream> streams = new ArrayList<>();

    for (Object item : list) {
      if (item instanceof LiveStream stream) {
        streams.add(stream);
      }
    }

    if (!Boolean.TRUE.equals(result.get("ok"))) {
      throw new IllegalStateException(
              String.valueOf(
                      result.getOrDefault(
                              "error",
                              "unknown Twitch Selenium error")));
    }

    return List.copyOf(streams);
  }

  public Map<String, Object> discover(
          int requestedMax,
          String requestedUrl, boolean validateWithApi) {

    int max = Math.max(1, Math.min(requestedMax, 100));

    String pageUrl =
            requestedUrl == null || requestedUrl.isBlank()
                    ? defaultPageUrl
                    : requestedUrl.trim();

    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> diagnostics = new LinkedHashMap<>();

    List<LiveStream> streams = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    Instant started = Instant.now();

    WebDriver driver = null;

    int scrolls = 0;
    int cardsSeen = 0;

    try {

      if (!pageUrl.startsWith("https://www.twitch.tv/")) {
        throw new IllegalArgumentException(
                "Twitch Selenium URL must start with https://www.twitch.tv/");
      }

      ChromeOptions options = new ChromeOptions();

      if (headless) {
        options.addArguments("--headless=new");
      }

      options.addArguments(
              "--disable-gpu",
              "--no-sandbox",
              "--disable-dev-shm-usage",
              "--window-size=1440,1200",
              "--lang=en-US",
              "--disable-notifications");

      driver = new org.openqa.selenium.chrome.ChromeDriver(options);

      driver.manage()
              .timeouts()
              .pageLoadTimeout(Duration.ofSeconds(30));

      driver.get(pageUrl);

      new WebDriverWait(driver, Duration.ofSeconds(15))
              .until(
                      d ->
                              !d.findElements(By.cssSelector("body"))
                                      .isEmpty());

      new WebDriverWait(driver, Duration.ofSeconds(15))
              .until(
                      d ->
                              !findCardAnchors(d).isEmpty());

      Set<String> seenChannels =
              new LinkedHashSet<>();

      int unchangedRounds = 0;
      int previousCount = 0;

      /*
       * Find Twitch's actual scroll container.
       *
       * We don't assume window scrolling is enough because Twitch can
       * render the directory inside an overflow-y container.
       */
      WebElement scrollContainer =
              findBestScrollContainer(driver);

      diagnostics.put(
              "scrollContainerFound",
              scrollContainer != null);

      diagnostics.put(
              "scrollMode",
              scrollContainer != null
                      ? "element"
                      : "window");

      while (
              seenChannels.size() < max
                      && scrolls < 40
                      && unchangedRounds < 5) {

        List<WebElement> anchors =
                findCardAnchors(driver);

        cardsSeen =
                Math.max(cardsSeen, anchors.size());

        /*
         * Parse all currently rendered cards.
         */
        for (WebElement anchor : anchors) {

          if (seenChannels.size() >= max) {
            break;
          }

          String href =
                  safeAttribute(anchor, "href");

          String slug =
                  extractChannelSlug(href);

          if (slug.isBlank()) {
            continue;
          }

          String normalized =
                  slug.toLowerCase(Locale.ROOT);

          if (!seenChannels.add(normalized)) {
            continue;
          }

          WebElement card =
                  nearestArticle(anchor);

          String cardText =
                  safeText(card);

          String title =
                  firstNonBlank(
                          safeAttribute(anchor, "aria-label"),
                          safeAttribute(anchor, "title"),
                          safeText(
                                  findFirst(
                                          card,
                                          "a[data-a-target='preview-card-title']")),
                          safeText(anchor));

          long viewers =
                  parseViewers(cardText);

          streams.add(
                  new LiveStream(
                          "Twitch",
                          slug,
                          "",
                          slug,
                          title,
                          viewers,
                          "https://www.twitch.tv/" + slug));
        }

        /*
         * Check whether this scroll produced new channels.
         */
        if (seenChannels.size() == previousCount) {
          unchangedRounds++;
        } else {
          unchangedRounds = 0;
        }

        previousCount =
                seenChannels.size();

        if (seenChannels.size() >= max) {
          break;
        }

        /*
         * Scroll the actual Twitch container.
         */
        boolean moved =
                scrollTwitchContainer(
                        driver,
                        scrollContainer);

        scrolls++;

        /*
         * Allow Twitch time to render the next batch.
         */
        Thread.sleep(900L);

        /*
         * Re-detect the container occasionally because Twitch can
         * replace DOM nodes while virtualizing the list.
         */
        if (scrolls % 3 == 0) {
          scrollContainer =
                  findBestScrollContainer(driver);
        }

        /*
         * If we could not move the container, try window scrolling
         * once as a fallback.
         */
        if (!moved) {

          ((JavascriptExecutor) driver)
                  .executeScript(
                          "window.scrollBy(0, Math.max(700, " +
                                  "Math.floor(window.innerHeight * 0.8)));");

          Thread.sleep(900L);
        }
      }

      streams.sort(
              Comparator
                      .comparingLong(LiveStream::viewers)
                      .reversed());

      if (streams.isEmpty()) {
        warnings.add(
                "No Twitch cards were parsed. Check the Twitch category URL, "
                        + "consent screen, login/interstitial state, or Twitch DOM changes.");
      }

      diagnostics.put(
              "pageUrl",
              pageUrl);

      diagnostics.put(
              "cardsSeen",
              cardsSeen);

      diagnostics.put(
              "uniqueChannels",
              seenChannels.size());

      diagnostics.put(
              "scrollsPerformed",
              scrolls);

      diagnostics.put(
              "unchangedRounds",
              unchangedRounds);

      diagnostics.put(
              "sortStrategy",
              "Twitch category URL sort=VIEWER_COUNT");

      diagnostics.put(
              "viewerSource",
              "DOM card text");

      out.put("ok", true);
      out.put("isolatedTestOnly", true);
      out.put("requestedMaxStreams", max);

      out.put(
              "streamsFound",
              streams.size());

      out.put(
              "elapsedMs",
              Duration
                      .between(
                              started,
                              Instant.now())
                      .toMillis());

      out.put(
              "diagnostics",
              diagnostics);

      out.put(
              "warnings",
              warnings);

      out.put(
              "streams",
              streams);

    } catch (Exception e) {

      log.warn(
              "Twitch Selenium discovery failed: {}",
              e.toString());

      out.put("ok", false);
      out.put("isolatedTestOnly", true);
      out.put("requestedMaxStreams", max);

      out.put(
              "streamsFound",
              streams.size());

      out.put(
              "elapsedMs",
              Duration
                      .between(
                              started,
                              Instant.now())
                      .toMillis());

      diagnostics.put(
              "pageUrl",
              pageUrl);

      diagnostics.put(
              "cardsSeen",
              cardsSeen);

      diagnostics.put(
              "scrollsPerformed",
              scrolls);

      out.put(
              "diagnostics",
              diagnostics);

      out.put(
              "error",
              e.getMessage() == null
                      ? e.getClass().getSimpleName()
                      : e.getMessage());

      out.put(
              "warnings",
              warnings);

      out.put(
              "streams",
              streams);

    } finally {

      if (driver != null) {
        try {
          driver.quit();
        } catch (Exception ignored) {
        }
      }
    }

    return out;
  }

  /**
   * Find the most likely Twitch directory scroll container.
   *
   * We intentionally inspect overflow-y containers instead of assuming
   * that window/document scrolling controls the Twitch card list.
   */
  private WebElement findBestScrollContainer(
          WebDriver driver) {

    try {

      Object result =
              ((JavascriptExecutor) driver)
                      .executeScript(
                              """
                              const elements = Array.from(document.querySelectorAll('*'));
            
                              const candidates = elements
                                .map((el, index) => {
                                  const style = window.getComputedStyle(el);
            
                                  const overflowY =
                                    style.overflowY === 'auto' ||
                                    style.overflowY === 'scroll';
            
                                  const canScroll =
                                    el.scrollHeight > el.clientHeight + 100;
            
                                  if (!overflowY || !canScroll) {
                                    return null;
                                  }
            
                                  const rect = el.getBoundingClientRect();
            
                                  return {
                                    index: index,
                                    scrollHeight: el.scrollHeight,
                                    clientHeight: el.clientHeight,
                                    area: rect.width * rect.height
                                  };
                                })
                                .filter(Boolean)
                                .sort((a, b) => {
                                  if (b.scrollHeight !== a.scrollHeight) {
                                    return b.scrollHeight - a.scrollHeight;
                                  }
            
                                  return b.area - a.area;
                                });
            
                              return candidates.length > 0
                                ? candidates[0].index
                                : -1;
                              """);

      if (!(result instanceof Number number)) {
        return null;
      }

      int index = number.intValue();

      if (index < 0) {
        return null;
      }

      List<WebElement> elements =
              driver.findElements(
                      By.cssSelector("*"));

      if (index >= elements.size()) {
        return null;
      }

      WebElement candidate =
              elements.get(index);

      if (candidate.isDisplayed()) {
        return candidate;
      }

    } catch (Exception e) {

      log.debug(
              "Unable to identify Twitch scroll container: {}",
              e.getMessage());
    }

    return null;
  }

  /**
   * Scroll the Twitch container by approximately 80% of its visible height.
   */
  private boolean scrollTwitchContainer(
          WebDriver driver,
          WebElement container) {

    try {

      if (container == null) {

        Object result =
                ((JavascriptExecutor) driver)
                        .executeScript(
                                """
                                const before = window.scrollY;
            
                                window.scrollBy(
                                  0,
                                  Math.max(
                                    700,
                                    Math.floor(window.innerHeight * 0.8)
                                  )
                                );
            
                                return window.scrollY !== before;
                                """);

        return Boolean.TRUE.equals(result);
      }

      Object result =
              ((JavascriptExecutor) driver)
                      .executeScript(
                              """
                              const el = arguments[0];
            
                              const before = el.scrollTop;
            
                              const amount =
                                Math.max(
                                  500,
                                  Math.floor(el.clientHeight * 0.8)
                                );
            
                              el.scrollTop += amount;
            
                              return {
                                moved: el.scrollTop !== before,
                                before: before,
                                after: el.scrollTop,
                                scrollHeight: el.scrollHeight,
                                clientHeight: el.clientHeight
                              };
                              """,
                              container);

      if (result instanceof Map<?, ?> map) {

        Object moved =
                map.get("moved");

        return Boolean.TRUE.equals(moved);
      }

    } catch (Exception e) {

      log.debug(
              "Twitch container scroll failed: {}",
              e.getMessage());
    }

    return false;
  }

  private List<WebElement> findCardAnchors(
          WebDriver driver) {

    List<WebElement> anchors =
            driver.findElements(
                    By.cssSelector(
                            "article a[href^='https://www.twitch.tv/'], "
                                    + "article a[href^='/'], "
                                    + "main a[href^='https://www.twitch.tv/'], "
                                    + "main a[href^='/']"));

    LinkedHashMap<String, WebElement> unique =
            new LinkedHashMap<>();

    for (WebElement anchor : anchors) {

      String slug =
              extractChannelSlug(
                      safeAttribute(anchor, "href"));

      if (!slug.isBlank()) {

        unique.putIfAbsent(
                slug.toLowerCase(Locale.ROOT),
                anchor);
      }
    }

    return new ArrayList<>(
            unique.values());
  }

  private String extractChannelSlug(
          String href) {

    if (href == null || href.isBlank()) {
      return "";
    }

    String value =
            href.trim();

    if (value.startsWith(
            "https://www.twitch.tv/")) {

      value =
              value.substring(
                      "https://www.twitch.tv/"
                              .length());

    } else if (value.startsWith("/")) {

      value =
              value.substring(1);

    } else {

      return "";
    }

    int query =
            value.indexOf('?');

    if (query >= 0) {
      value =
              value.substring(0, query);
    }

    int hash =
            value.indexOf('#');

    if (hash >= 0) {
      value =
              value.substring(0, hash);
    }

    int slash =
            value.indexOf('/');

    if (slash >= 0) {
      value =
              value.substring(0, slash);
    }

    if (value.isBlank()
            || value.equalsIgnoreCase("directory")
            || value.equalsIgnoreCase("search")
            || value.equalsIgnoreCase("downloads")
            || value.equalsIgnoreCase("settings")
            || value.equalsIgnoreCase("subscriptions")
            || value.equalsIgnoreCase("following")
            || value.equalsIgnoreCase("videos")
            || value.equalsIgnoreCase("categories")) {

      return "";
    }

    if (!value.matches(
            "[A-Za-z0-9_]{2,50}")) {

      return "";
    }

    return value;
  }

  private WebElement nearestArticle(
          WebElement anchor) {

    try {

      return anchor.findElement(
              By.xpath("./ancestor::article[1]"));

    } catch (Exception ignored) {

      return anchor;
    }
  }

  private WebElement findFirst(
          WebElement parent,
          String css) {

    if (parent == null) {
      return null;
    }

    try {

      List<WebElement> found =
              parent.findElements(
                      By.cssSelector(css));

      return found.isEmpty()
              ? null
              : found.get(0);

    } catch (Exception ignored) {

      return null;
    }
  }

  private long parseViewers(
          String text) {

    if (text == null || text.isBlank()) {
      return 0;
    }

    Matcher matcher =
            VIEWERS.matcher(text);

    if (!matcher.find()) {
      return 0;
    }

    try {

      double value =
              Double.parseDouble(
                      matcher
                              .group(1)
                              .replace(",", ""));

      String suffix =
              matcher
                      .group(2)
                      .toUpperCase(Locale.ROOT);

      double multiplier =
              switch (suffix) {
                case "K" -> 1_000d;
                case "M" -> 1_000_000d;
                case "B" -> 1_000_000_000d;
                default -> 1d;
              };

      return Math.max(
              0,
              Math.round(
                      value * multiplier));

    } catch (NumberFormatException e) {

      return 0;
    }
  }

  private String safeText(
          WebElement element) {

    if (element == null) {
      return "";
    }

    try {

      String text =
              element.getText();

      return text == null
              ? ""
              : text.trim();

    } catch (Exception ignored) {

      return "";
    }
  }

  private String safeAttribute(
          WebElement element,
          String name) {

    if (element == null) {
      return "";
    }

    try {

      String value =
              element.getAttribute(name);

      return value == null
              ? ""
              : value.trim();

    } catch (Exception ignored) {

      return "";
    }
  }

  private String firstNonBlank(
          String... values) {

    for (String value : values) {

      if (value != null
              && !value.isBlank()) {

        return value.trim();
      }
    }

    return "";
  }
}