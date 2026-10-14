package com.valorant.tracker.controller.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginPageController {
  @GetMapping("/login")
  public String loginPage(Authentication authentication) {
    // An existing signed-in session should go straight to the index, never reopen the login modal.
    if (authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
      return "redirect:/";
    }
    // Keep the login experience on the index page, using its existing auth modal.
    return "redirect:/?auth=login";
  }
}
