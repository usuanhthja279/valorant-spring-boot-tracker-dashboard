package com.valorant.tracker.observability;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RequestLoggingFilterTest {
  @RestController
  static class ProbeController {
    @GetMapping("/probe") String probe() { return "ok"; }
  }

  @Test
  void assignsRequestIdAndReturnsItInResponse() throws Exception {
    RequestLoggingFilter filter = new RequestLoggingFilter();
    ReflectionTestUtils.setField(filter, "slowRequestMs", 1000L);
    MockMvcBuilders.standaloneSetup(new ProbeController()).addFilters(filter).build()
        .perform(get("/probe"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(result -> {
          String id = result.getResponse().getHeader("X-Request-Id");
          assertNotNull(id);
          assertTrue(id.matches("[0-9a-f-]{36}"));
        });
  }

  @Test
  void acceptsSafeCallerProvidedRequestId() throws Exception {
    RequestLoggingFilter filter = new RequestLoggingFilter();
    ReflectionTestUtils.setField(filter, "slowRequestMs", 1000L);
    MockMvcBuilders.standaloneSetup(new ProbeController()).addFilters(filter).build()
        .perform(get("/probe").header("X-Request-Id", "browser-request-42"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "browser-request-42"));
  }
}
