package com.valorant.tracker.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs each HTTP request without recording query parameters or credentials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

  @Value("${tracker.logging.slow-request-ms:1000}")
  private long slowRequestMs;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String suppliedId = request.getHeader(REQUEST_ID_HEADER);
    String requestId = suppliedId != null && SAFE_REQUEST_ID.matcher(suppliedId).matches()
        ? suppliedId : UUID.randomUUID().toString();
    long started = System.nanoTime();
    MDC.put("requestId", requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException exception) {
      log.error("http_request_failed method={} path={} status={} durationMs={} exceptionType={}",
          request.getMethod(), request.getRequestURI(), 500, elapsedMs(started),
          exception.getClass().getSimpleName(), exception);
      throw exception;
    } finally {
      long durationMs = elapsedMs(started);
      int status = response.getStatus();
      String message = "http_request_completed method={} path={} status={} durationMs={} slow={}";
      if (status >= 500) {
        log.error(message, request.getMethod(), request.getRequestURI(), status, durationMs,
            durationMs >= slowRequestMs);
      } else if (status >= 400 || durationMs >= slowRequestMs) {
        log.warn(message, request.getMethod(), request.getRequestURI(), status, durationMs,
            durationMs >= slowRequestMs);
      } else {
        log.info(message, request.getMethod(), request.getRequestURI(), status, durationMs, false);
      }
      MDC.remove("requestId");
    }
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }
}
