package com.valorant.tracker.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class ReturnToAuthenticationFailureHandler implements AuthenticationFailureHandler {
  @Override public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                                AuthenticationException exception) throws IOException, ServletException {
    String destination = "/?auth=login";
    Cookie[] cookies = request.getCookies();
    if (cookies != null) for (Cookie cookie : cookies) if ("stremng_return_to".equals(cookie.getName())) {
      String candidate = cookie.getValue();
      try { candidate = java.net.URLDecoder.decode(candidate, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) {}
      if (candidate != null && candidate.startsWith("/") && !candidate.startsWith("//") && !candidate.startsWith("/api/") && !candidate.startsWith("/login") && !candidate.startsWith("/oauth2/")) {
        destination = candidate.contains("auth=login") ? candidate : candidate + (candidate.contains("?") ? "&" : "?") + "auth=login";
      }
    }
    response.sendRedirect(response.encodeRedirectURL(destination));
  }
}
