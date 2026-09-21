package com.jobportal.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashboardService resolves its file-system root relative to the JVM working directory.
 * Tests reflectively repoint {@code root}/{@code configPath} at a JUnit temp directory so
 * they never read or write the real repository's job_portal_dashboard_config.json.
 */
class DashboardServiceTest {

  @TempDir
  Path tempDir;

  DashboardService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new DashboardService();
    setField("root", tempDir);
    setField("configPath", tempDir.resolve("job_portal_dashboard_config.json"));
  }

  @AfterEach
  void shutdownRunner() throws Exception {
    String id = getField("activeRunId");
    if (!id.isEmpty()) service.stopScrape(id);
    ExecutorService runner = getField("runner");
    runner.shutdown();
    runner.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  void stopTerminatesProcessTreeSkipsRemainingVendorsAndAllowsAnotherRun() throws Exception {
    Path folder = tempDir.resolve("mitchellmartin_applying_script");
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("mitchellmartin_scraper.py"), """
        import subprocess, sys, time
        from pathlib import Path
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        Path('started').write_text(str(child.pid))
        time.sleep(60)
        """);
    Path next = tempDir.resolve("insightglobal_applying_script");
    Files.createDirectories(next);
    Files.writeString(next.resolve("insightglobal_scraper.py"), "from pathlib import Path; Path('next-started').touch()");
    for (String name : List.of("teksystems", "brooksource")) {
      Path other = tempDir.resolve(name + "_applying_script");
      Files.createDirectories(other);
      Files.writeString(other.resolve(name + "_scraper.py"), "from pathlib import Path; import time; Path('" + name + "-started').touch(); time.sleep(60)");
    }
    Map<String, Object> start = service.scrape(Map.of("vendors", List.of("mitchellmartin", "teksystems", "brooksource", "insightglobal")));
    String id = (String) start.get("run_id");
    await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(tempDir.resolve("started")));
    await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(tempDir.resolve("teksystems-started")) && Files.exists(tempDir.resolve("brooksource-started")));
    Map<String, Process> active = getField("activeScrapers");
    List<Process> processes = List.copyOf(active.values());
    assertThat(processes).hasSize(3);
    long childId = Long.parseLong(Files.readString(tempDir.resolve("started")));
    assertThat(service.stopScrape("stale-run").get("ok")).isEqualTo(false);
    assertThat(service.stopScrape(id).get("ok")).isEqualTo(true);
    AtomicBoolean running = getField("running");
    await().atMost(Duration.ofSeconds(10)).until(() -> !running.get());
    await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(childId).map(p -> !p.isAlive()).orElse(true));
    Map<String, Map<String, Object>> runs = getField("runs");
    assertThat(runs.get(id).get("status")).isEqualTo("stopped");
    assertThat(processes).allMatch(p -> !p.isAlive());
    assertThat(Files.exists(tempDir.resolve("next-started"))).isFalse();
    assertThat(service.scrape(Map.of("vendors", List.of("insightglobal"))).get("ok")).isEqualTo(true);
    await().atMost(Duration.ofSeconds(10)).until(() -> !running.get());
    assertThat(Files.exists(tempDir.resolve("next-started"))).isTrue();
  }

  @Test
  void stopWhenIdleDoesNotAffectOtherProcesses() {
    assertThat(service.stopScrape("old-run").get("ok")).isEqualTo(false);
  }

  private void setField(String name, Object value) throws Exception {
    Field f = DashboardService.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(service, value);
  }

  @SuppressWarnings("unchecked")
  private <T> T getField(String name) throws Exception {
    Field f = DashboardService.class.getDeclaredField(name);
    f.setAccessible(true);
    return (T) f.get(service);
  }

  @Test
  void configPayloadReturnsDefaultsWhenNoConfigFileExists() {
    Map<String, Object> payload = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) payload.get("config");

    assertThat(config.get("posted_within_days")).isEqualTo(4);
    assertThat(config.get("open_limit")).isEqualTo(8);
    assertThat(config.get("start_at")).isEqualTo(1);
    assertThat(config.get("keep_open_minutes")).isEqualTo(60);
    @SuppressWarnings("unchecked")
    List<String> keywords = (List<String>) config.get("keywords");
    assertThat(keywords).contains("java developer");
  }

  @Test
  void saveConfigPersistsAndIsReloaded() {
    service.saveConfig(Map.of("posted_within_days", 10));

    Map<String, Object> reloaded = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) reloaded.get("config");
    assertThat(config.get("posted_within_days")).isEqualTo(10);
    assertThat(config.get("open_limit")).isEqualTo(8);
  }

  @Test
  void saveConfigNormalizesNewlineSeparatedKeywordString() {
    service.saveConfig(Map.of("keywords", "java developer\n java developer \n\nspring boot \n"));

    Map<String, Object> reloaded = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) reloaded.get("config");
    @SuppressWarnings("unchecked")
    List<String> keywords = (List<String>) config.get("keywords");

    assertThat(keywords).containsExactly("java developer", "spring boot");
  }

  @Test
  void rotationPreviewHasTenWeekdayOnlyEntries() {
    Map<String, Object> payload = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rotation = (List<Map<String, Object>>) payload.get("rotation");

    assertThat(rotation).hasSize(10);
    for (Map<String, Object> row : rotation) {
      LocalDate date = LocalDate.parse((String) row.get("date"));
      assertThat(date.getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
      @SuppressWarnings("unchecked")
      List<String> vendorNames = (List<String>) row.get("vendors");
      assertThat(vendorNames).hasSize(2);
    }
  }

  @Test
  void activeTodayIsConsistentWithRotationPreviewAcrossWeekendsAndWeekdays() {
    Map<String, Object> payload = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> vendors = (List<Map<String, Object>>) payload.get("vendors");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rotation = (List<Map<String, Object>>) payload.get("rotation");

    String todayStr = LocalDate.now().toString();
    Map<String, Object> firstRow = rotation.get(0);
    @SuppressWarnings("unchecked")
    List<String> expectedActive = todayStr.equals(firstRow.get("date")) ? (List<String>) firstRow.get("slugs") : List.of();

    List<String> actualActive = vendors.stream()
        .filter(v -> Boolean.TRUE.equals(v.get("active_today")))
        .map(v -> (String) v.get("slug"))
        .toList();

    assertThat(actualActive).containsExactlyInAnyOrderElementsOf(expectedActive);
  }

  @Test
  void vendorStatusIsZeroWhenNoOutputDirectoryExists() {
    Map<String, Object> payload = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> vendors = (List<Map<String, Object>>) payload.get("vendors");

    Map<String, Object> teksystems = vendors.stream()
        .filter(v -> "teksystems".equals(v.get("slug")))
        .findFirst().orElseThrow();
    assertThat(teksystems.get("latest_count")).isEqualTo(0);
    assertThat(teksystems.get("latest_file")).isEqualTo("");
  }

  @Test
  void vendorStatusReadsCountFromLatestOutputFile() throws Exception {
    Path outputDir = tempDir.resolve("cbts_applying_script").resolve("output");
    Files.createDirectories(outputDir);
    Files.writeString(outputDir.resolve("cbts_jobs_20260101_000000.json"), "[{\"a\":1},{\"a\":2},{\"a\":3}]");

    Map<String, Object> payload = service.getConfigPayload();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> vendors = (List<Map<String, Object>>) payload.get("vendors");
    Map<String, Object> cbts = vendors.stream()
        .filter(v -> "cbts".equals(v.get("slug")))
        .findFirst().orElseThrow();

    assertThat(cbts.get("latest_count")).isEqualTo(3);
    assertThat((String) cbts.get("latest_file")).contains("cbts_jobs_20260101_000000.json");
  }

  @Test
  void todayCountUsesPostingDateAndCurrentFilters() throws Exception {
    service.saveConfig(Map.of("keywords", List.of("java"), "ignore_titles", List.of("architect"), "posted_within_days", 4));
    Path output = tempDir.resolve("cbts_applying_script/output");
    Files.createDirectories(output);
    String today = LocalDate.now().toString();
    String yesterday = LocalDate.now().minusDays(1).toString();
    Files.writeString(output.resolve("cbts_jobs_test.json"), "["
        + "{\"title\":\"Java Developer\",\"posted_date\":\"" + today + "\"},"
        + "{\"title\":\"Java Developer\",\"posted_date\":\"" + yesterday + "\"},"
        + "{\"title\":\"Java Developer\"},"
        + "{\"title\":\"Java Architect\",\"posted_date\":\"" + today + "\"}]");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> vendors = (List<Map<String, Object>>) service.getStatusPayload().get("vendors");
    Map<String, Object> cbts = vendors.stream().filter(v -> "cbts".equals(v.get("slug"))).findFirst().orElseThrow();
    assertThat(cbts.get("today_count")).isEqualTo(1);
    assertThat(cbts.get("latest_count")).isEqualTo(3);
  }

  @Test
  void teksystemsAllDaysResultsBypassOnlyDateFilter() throws Exception {
    service.saveConfig(Map.of("keywords", List.of("java"), "ignore_titles", List.of("architect"), "posted_within_days", 4));
    Path output = tempDir.resolve("teksystems_applying_script/output");
    Files.createDirectories(output);
    Files.writeString(output.resolve("teksystems_jobs_test.json"), "["
        + "{\"title\":\"Java Developer\",\"posted_date\":\"2020-01-01\",\"dashboard_all_days\":true},"
        + "{\"title\":\"Java Architect\",\"posted_date\":\"2020-01-01\",\"dashboard_all_days\":true},"
        + "{\"title\":\"Java Developer\",\"posted_date\":\"2020-01-01\"}]");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> jobs = (List<Map<String, Object>>) service.getJobs("teksystems").get("jobs");
    assertThat(jobs).hasSize(1);
    assertThat(((Map<?, ?>) service.getConfigPayload().get("config")).get("posted_within_days")).isEqualTo(4);
  }

  @Test
  void scrapeWithEmptySelectionReturnsError() {
    Map<String, Object> result = service.scrape(Map.of("mode", "selected", "vendors", List.of()));
    assertThat(result.get("ok")).isEqualTo(false);
    assertThat(result.get("error")).isEqualTo("No vendors selected.");
  }

  @Test
  void scrapeFiltersOutUnknownVendorsAndReturnsErrorWhenNoneRemain() {
    Map<String, Object> result = service.scrape(Map.of("mode", "selected", "vendors", List.of("not-a-real-vendor")));
    assertThat(result.get("ok")).isEqualTo(false);
    assertThat(result.get("error")).isEqualTo("No vendors selected.");
  }

  @Test
  void scrapeRefusesConcurrentRuns() throws Exception {
    AtomicBoolean running = getField("running");
    running.set(true);
    try {
      Map<String, Object> result = service.scrape(Map.of("mode", "all"));
      assertThat(result.get("ok")).isEqualTo(false);
      assertThat(result.get("error")).isEqualTo("A scrape is already running.");
    } finally {
      running.set(false);
    }
  }

  @Test
  void openWithUnknownVendorReturnsError() throws Exception {
    Map<String, Object> result = service.open(Map.of("vendor", "not-a-real-vendor"));
    assertThat(result.get("ok")).isEqualTo(false);
    assertThat(result.get("error")).isEqualTo("Unknown vendor.");
  }
}
