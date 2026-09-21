package com.jobportal.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses a hand-written fake subclass instead of a mocking library: Mockito's inline mock
 * maker cannot instrument classes on newer JDKs in this environment, and DashboardService's
 * real methods spawn OS processes / touch the file system, which a controller-layer test
 * must not do.
 */
class DashboardControllerTest {

  static class FakeDashboardService extends DashboardService {
    Function<Map<String, Object>, Map<String, Object>> onScrape = p -> Map.of("ok", true);
    Function<Map<String, Object>, Map<String, Object>> onOpen = p -> Map.of("ok", true);
    Map<String, Object> configPayload = Map.of();
    Map<String, Object> statusPayload = Map.of();

    @Override
    public Map<String, Object> getConfigPayload() { return configPayload; }

    @Override
    public Map<String, Object> getStatusPayload() { return statusPayload; }

    @Override
    public Map<String, Object> scrape(Map<String, Object> payload) { return onScrape.apply(payload); }

    @Override
    public Map<String, Object> open(Map<String, Object> payload) { return onOpen.apply(payload); }
  }

  private final FakeDashboardService service = new FakeDashboardService();
  private final DashboardController controller = new DashboardController(service);

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173"})
  void acceptsDashboardFromBothLocalAddresses(String origin) throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    mvc.perform(get("/api/config").header("Origin", origin))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin));
  }

  @Test
  void configDelegatesToService() {
    service.configPayload = Map.of("config", Map.of("posted_within_days", 4));
    assertThat(controller.config()).isSameAs(service.configPayload);
  }

  @Test
  void statusDelegatesToService() {
    service.statusPayload = Map.of("runs", List.of());
    assertThat(controller.status()).isSameAs(service.statusPayload);
  }

  @Test
  void scrapeReturnsOkWhenServiceSucceeds() {
    service.onScrape = p -> Map.of("ok", true, "run_id", "abc");
    ResponseEntity<Map<String, Object>> response = controller.scrape(Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get("run_id")).isEqualTo("abc");
  }

  @Test
  void scrapeReturnsConflictWhenAlreadyRunning() {
    service.onScrape = p -> Map.of("ok", false, "error", "A scrape is already running.");
    ResponseEntity<Map<String, Object>> response = controller.scrape(Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void scrapeReturnsBadRequestForOtherErrors() {
    service.onScrape = p -> Map.of("ok", false, "error", "No vendors selected.");
    ResponseEntity<Map<String, Object>> response = controller.scrape(Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void openReturnsOkWhenServiceSucceeds() throws Exception {
    service.onOpen = p -> Map.of("ok", true, "status", "started");
    ResponseEntity<Map<String, Object>> response = controller.open(Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void openReturnsBadRequestWhenVendorUnknown() throws Exception {
    service.onOpen = p -> Map.of("ok", false, "error", "Unknown vendor.");
    ResponseEntity<Map<String, Object>> response = controller.open(Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
