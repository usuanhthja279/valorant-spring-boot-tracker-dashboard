package com.valorant.tracker.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
  private final CustomOAuth2UserService oauth2UserService;
  private final CustomOidcUserService oidcUserService;
  private final ReturnToAuthenticationSuccessHandler successHandler;
  private final ReturnToAuthenticationFailureHandler failureHandler;

  public SecurityConfig(CustomOAuth2UserService oauth2UserService,
                        CustomOidcUserService oidcUserService,
                        ReturnToAuthenticationSuccessHandler successHandler,
                        ReturnToAuthenticationFailureHandler failureHandler) {
    this.oauth2UserService = oauth2UserService;
    this.oidcUserService = oidcUserService;
    this.successHandler = successHandler;
    this.failureHandler = failureHandler;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**", "/login"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/oauth/complete").permitAll()
            // Historical data is member-only. Live/current endpoints remain public.
            .requestMatchers(
                "/api/snapshots",
                "/api/snapshots/**",
                "/api/analytics",
                "/api/analytics/**",
                "/api/esports/overview/**",
                "/api/channels/history",
                "/api/channels/comparison"
            ).authenticated()
            .anyRequest().permitAll())
        .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
            (request, response, exception) -> {
              response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
              response.setContentType(MediaType.APPLICATION_JSON_VALUE);
              response.setCharacterEncoding("UTF-8");
              response.getWriter().write("{\"error\":\"authentication_required\",\"message\":\"Sign in to access historical data.\"}");
            },
            new AntPathRequestMatcher("/api/**")))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .formLogin(form -> form
            .loginPage("/login.html")
            .successHandler(successHandler)
            .failureHandler(failureHandler)
            .permitAll())
        .oauth2Login(oauth -> oauth
                .loginPage("/login")
            .userInfoEndpoint(userInfo -> userInfo
                .userService(oauth2UserService)
                .oidcUserService(oidcUserService))
            .successHandler(successHandler)
            .permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/").permitAll());
    return http.build();
  }
}
