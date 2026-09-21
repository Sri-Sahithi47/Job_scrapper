package com.jobportal.dashboard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@CrossOrigin(origins = {"http://127.0.0.1:5173", "http://localhost:5173"})
public class DashboardController {
  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  @GetMapping("/api/config")
  public Map<String, Object> config() {
    return service.getConfigPayload();
  }

  @GetMapping("/api/status")
  public Map<String, Object> status() {
    return service.getStatusPayload();
  }

  @GetMapping("/api/jobs")
  public ResponseEntity<Map<String, Object>> jobs(@RequestParam("vendor") String vendor) {
    Map<String, Object> r = service.getJobs(vendor);
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/config")
  public Map<String, Object> saveConfig(@RequestBody Map<String, Object> payload) {
    return service.saveConfig(payload);
  }

  @PostMapping("/api/scrape")
  public ResponseEntity<Map<String, Object>> scrape(@RequestBody Map<String, Object> payload) {
    Map<String, Object> r = service.scrape(payload);
    if (Boolean.FALSE.equals(r.get("ok"))) {
      String error = String.valueOf(r.get("error"));
      HttpStatus status = "A scrape is already running.".equals(error) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
      return ResponseEntity.status(status).body(r);
    }
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/scrape/stop")
  public ResponseEntity<Map<String, Object>> stopScrape(@RequestBody Map<String, Object> payload) {
    Map<String, Object> result = service.stopScrape(String.valueOf(payload.getOrDefault("run_id", "")));
    return Boolean.FALSE.equals(result.get("ok"))
        ? ResponseEntity.status(HttpStatus.CONFLICT).body(result) : ResponseEntity.ok(result);
  }

  @PostMapping("/api/open")
  public ResponseEntity<Map<String, Object>> open(@RequestBody Map<String, Object> payload) throws IOException {
    Map<String, Object> r = service.open(payload);
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/jobs/ai-clean")
  public ResponseEntity<Map<String, Object>> aiCleanJobs(@RequestBody Map<String, Object> payload) {
    Map<String, Object> r = service.aiCleanJobs(String.valueOf(payload.getOrDefault("vendor", "")));
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/jobs/open-urls")
  public ResponseEntity<Map<String, Object>> openUrls(@RequestBody Map<String, Object> payload) {
    Object urls = payload.get("urls");
    Map<String, Object> r = service.openUrls(urls instanceof java.util.List<?> l ? l : java.util.List.of());
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/jobs/ai-reset")
  public ResponseEntity<Map<String, Object>> resetAiHidden(@RequestBody Map<String, Object> payload) {
    Map<String, Object> r = service.resetAiHidden(String.valueOf(payload.getOrDefault("vendor", "")));
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }

  @PostMapping("/api/open/stop")
  public ResponseEntity<Map<String, Object>> stopOpen(@RequestBody Map<String, Object> payload) {
    Map<String, Object> r = service.stopOpen(String.valueOf(payload.getOrDefault("vendor", "")));
    if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
    return ResponseEntity.ok(r);
  }
}
