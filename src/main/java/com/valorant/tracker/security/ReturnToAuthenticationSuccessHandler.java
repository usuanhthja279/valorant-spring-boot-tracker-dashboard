package com.valorant.tracker.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
public class ReturnToAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
  @Override public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                 Authentication authentication) throws IOException, ServletException {
    String destination = "/";
    Cookie[] cookies = request.getCookies();
    if (cookies != null) for (Cookie cookie : cookies) {
      if ("stremng_return_to".equals(cookie.getName())) {
        String candidate = cookie.getValue();
        try { candidate = java.net.URLDecoder.decode(candidate, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) {}
        if (candidate != null && candidate.startsWith("/") && !candidate.startsWith("//") && !candidate.startsWith("/api/") && !candidate.startsWith("/login") && !candidate.startsWith("/oauth2/") && !candidate.contains("auth=login")) destination = candidate;
      }
    }
    Cookie clear = new Cookie("stremng_return_to", ""); clear.setPath("/"); clear.setMaxAge(0); clear.setHttpOnly(false); response.addCookie(clear);
    response.sendRedirect(response.encodeRedirectURL(destination));
  }
}
