package com.jobportal.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class DashboardService {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path root = Path.of("").toAbsolutePath().resolve("..").resolve("..").normalize();
  private final String pythonBin = resolvePython();
  private final Path configPath = root.resolve("job_portal_dashboard_config.json");
  private final ExecutorService runner = Executors.newSingleThreadExecutor();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private String activeRunId = "";
  private boolean stopRequested;
  private final Map<String, Process> activeScrapers = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> runs = new ConcurrentHashMap<>();
  private final Map<String, Process> openProcesses = new ConcurrentHashMap<>();
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final List<Vendor> vendors = List.of(
      new Vendor("teksystems", "TEKsystems", "teksystems_applying_script", "teksystems_scraper.py", "teksystems_open_jobs.py", "teksystems", "file", "", 0),
      new Vendor("apexsystems", "Apex Systems", "apexsystems_applying_script", "apex_scraper.py", "apex_open_jobs.py", "apex", "file", "rows-per-search", 100),
      new Vendor("judgegroup", "Judge Group", "judgegroup_applying_script", "judgegroup_scraper.py", "judgegroup_open_jobs.py", "judgegroup", "file", "max-pages", 3),
      new Vendor("beaconhill", "Beacon Hill", "beaconhill_applying_script", "beaconhill_scraper.py", "beaconhill_open_jobs.py", "beaconhill", "file", "max-pages", 3),
      new Vendor("akkodis", "Akkodis", "akkodis_applying_script", "akkodis_scraper.py", "akkodis_open_jobs.py", "akkodis", "file", "max-detail-pages", 30),
      new Vendor("randstad", "Randstad", "randstad_applying_script", "randstad_scraper.py", "randstad_open_jobs.py", "randstad", "file", "", 0),
      new Vendor("eliassen", "Eliassen", "eliassen_applying_script", "eliassen_scraper.py", "eliassen_open_jobs.py", "eliassen", "none", "", 0),
      new Vendor("experis", "Experis", "experis_applying_script", "experis_scraper.py", "experis_open_jobs.py", "experis", "append", "max-pages", 3),
      new Vendor("brooksource", "Brooksource", "brooksource_applying_script", "brooksource_scraper.py", "brooksource_open_jobs.py", "brooksource", "none", "", 0),
      new Vendor("kellymitchell", "KellyMitchell", "kellymitchell_applying_script", "kellymitchell_scraper.py", "kellymitchell_open_jobs.py", "kellymitchell", "append", "jobs-per-page", 50),
      new Vendor("mitchellmartin", "Mitchell Martin", "mitchellmartin_applying_script", "mitchellmartin_scraper.py", "mitchellmartin_open_jobs.py", "mitchellmartin", "append", "max-jobs", 40),
      new Vendor("cbts", "CBTS", "cbts_applying_script", "cbts_scraper.py", "cbts_open_jobs.py", "cbts", "file", "", 0),
      new Vendor("roberthalf", "Robert Half", "roberthalf_applying_script", "roberthalf_scraper.py", "roberthalf_open_jobs.py", "roberthalf", "file", "", 0),
      new Vendor("kforce", "Kforce", "kforce_applying_script", "kforce_scraper.py", "kforce_open_jobs.py", "kforce", "file", "", 0),
      new Vendor("insightglobal", "Insight Global", "insightglobal_applying_script", "insightglobal_scraper.py", "insightglobal_open_jobs.py", "insightglobal", "append", "", 0)
  );

  private final List<List<String>> pairs = List.of(
      List.of("teksystems", "apexsystems"), List.of("judgegroup", "beaconhill"),
      List.of("akkodis", "randstad"), List.of("eliassen", "experis"),
      List.of("brooksource", "kellymitchell"), List.of("mitchellmartin", "cbts"),
      List.of("roberthalf", "kforce"), List.of("insightglobal", "teksystems")
  );

  public Map<String, Object> getConfigPayload() {
    return Map.of("config", loadConfig(), "vendors", vendorStatus(), "rotation", rotationPreview());
  }

  public Map<String, Object> getStatusPayload() {
    List<Map<String, Object>> sorted = runs.values().stream().sorted(Comparator.comparing(x -> String.valueOf(x.get("started_at")))).toList();
    List<Map<String, Object>> last = sorted.size() <= 10 ? sorted : sorted.subList(sorted.size() - 10, sorted.size());
    return Map.of("runs", last, "vendors", vendorStatus(), "rotation", rotationPreview(), "scrape_stop_supported", true, "teksystems_all_days_supported", true);
  }

  public Map<String, Object> saveConfig(Map<String, Object> updates) {
    Map<String, Object> c = loadConfig();
    c.putAll(updates);
    writeConfig(c);
    return Map.of("ok", true, "config", loadConfig());
  }

  public synchronized Map<String, Object> scrape(Map<String, Object> payload) {
    if (!running.compareAndSet(false, true)) return Map.of("ok", false, "error", "A scrape is already running.");
    Map<String, Object> config = loadConfig();
    String mode = String.valueOf(payload.getOrDefault("mode", "selected"));
    List<String> slugs;
    if ("teksystems_all_days".equals(mode)) {
      slugs = List.of("teksystems");
      config.put("posted_within_days", 0);
    }
    else if ("all".equals(mode)) slugs = vendors.stream().map(Vendor::slug).toList();
    else if ("today".equals(mode)) slugs = activePair(LocalDate.now());
    else slugs = ((List<?>) payload.getOrDefault("vendors", List.of())).stream().map(String::valueOf).filter(this::isKnownVendor).toList();
    if (slugs.isEmpty()) {
      running.set(false);
      return Map.of("ok", false, "error", "No vendors selected.");
    }
    String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    Map<String, Object> run = new ConcurrentHashMap<>();
    run.put("id", runId);
    run.put("kind", mode);
    run.put("status", "running");
    run.put("started_at", LocalDateTime.now().toString());
    run.put("finished_at", "");
    run.put("vendors", slugs);
    run.put("steps", new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>());
    runs.put(runId, run);
    activeRunId = runId;
    stopRequested = false;
    runner.submit(() -> runScrapers(runId, slugs, config));
    return Map.of("ok", true, "run_id", runId);
  }

  public synchronized Map<String, Object> stopScrape(String runId) {
    if (!running.get() || !activeRunId.equals(runId)) {
      return Map.of("ok", false, "error", "This scrape is no longer running.");
    }
    stopRequested = true;
    runs.get(activeRunId).put("status", "stopping");
    for (Process activeScraper : activeScrapers.values()) {
      // Capture children before terminating the parent, which can orphan them.
      List<ProcessHandle> children = activeScraper.descendants().toList();
      children.forEach(ProcessHandle::destroyForcibly);
      activeScraper.destroyForcibly();
    }
    return Map.of("ok", true, "status", "stopping", "run_id", activeRunId);
  }

  public Map<String, Object> getJobs(String slug) {
    Vendor v = findVendor(slug);
    if (v == null) return Map.of("ok", false, "error", "Unknown vendor.");
    List<Map<String, Object>> jobs;
    try {
      jobs = loadLatestJobs(v);
    } catch (Exception e) {
      return Map.of("ok", false, "error", "Failed to read jobs: " + e.getMessage());
    }
    jobs = filterConfiguredJobs(jobs, v);
    java.util.Set<String> hidden = loadAiHidden(v);
    List<Map<String, Object>> visible = filterAiHidden(jobs, hidden);
    return Map.of("ok", true, "vendor", v.label(), "jobs", visible,
        "hidden_count", jobs.size() - visible.size());
  }

  private Path aiHiddenPath(Vendor v) {
    return root.resolve(v.folder()).resolve(".dashboard_ai_hidden.json");
  }

  private java.util.Set<String> loadAiHidden(Vendor v) {
    Path p = aiHiddenPath(v);
    if (!Files.exists(p)) return java.util.Set.of();
    try {
      List<String> ids = mapper.readValue(Files.readString(p), new TypeReference<>() {});
      return new java.util.HashSet<>(ids);
    } catch (Exception e) {
      return java.util.Set.of();
    }
  }

  private void saveAiHidden(Vendor v, java.util.Set<String> ids) throws IOException {
    Files.writeString(aiHiddenPath(v), mapper.writeValueAsString(new ArrayList<>(ids)), StandardCharsets.UTF_8);
  }

  private String jobKey(Map<String, Object> job) {
    for (String field : List.of("job_id", "job_url", "apply_url", "title")) {
      Object val = job.get(field);
      if (val != null && !String.valueOf(val).isBlank()) return field + ":" + val;
    }
    return "";
  }

  private List<Map<String, Object>> filterAiHidden(List<Map<String, Object>> jobs, java.util.Set<String> hidden) {
    if (hidden.isEmpty()) return jobs;
    return jobs.stream().filter(j -> !hidden.contains(jobKey(j))).toList();
  }

  public Map<String, Object> openUrls(List<?> rawUrls) {
    List<String> urls = new ArrayList<>();
    for (Object o : rawUrls) {
      String url = String.valueOf(o).trim();
      if (url.startsWith("http://") || url.startsWith("https://")) urls.add(url);
    }
    if (urls.isEmpty()) return Map.of("ok", false, "error", "No valid URLs to open.");

    String os = System.getProperty("os.name", "").toLowerCase();
    List<String> cmd = new ArrayList<>();
    if (os.contains("mac")) cmd.add("/usr/bin/open");
    else if (os.contains("win")) cmd.addAll(List.of("rundll32", "url.dll,FileProtocolHandler"));
    else cmd.add("xdg-open");
    cmd.addAll(urls);

    try {
      new ProcessBuilder(cmd)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException e) {
      return Map.of("ok", false, "error", "Failed to open browser tabs: " + e.getMessage());
    }
    System.out.println("[open-urls] opened " + urls.size() + " tabs");
    return Map.of("ok", true, "opened", urls.size());
  }

  public Map<String, Object> resetAiHidden(String slug) {
    Vendor v = findVendor(slug);
    if (v == null) return Map.of("ok", false, "error", "Unknown vendor.");
    try {
      Files.deleteIfExists(aiHiddenPath(v));
    } catch (IOException e) {
      return Map.of("ok", false, "error", "Failed to clear AI filter: " + e.getMessage());
    }
    return getJobs(slug);
  }

  private List<Map<String, Object>> loadLatestJobs(Vendor v) throws IOException {
    Path out = root.resolve(v.folder()).resolve("output");
    Path latest = null;
    if (Files.exists(out)) {
      try (Stream<Path> stream = Files.list(out)) {
        latest = stream
            .filter(p -> p.getFileName().toString().startsWith(v.prefix() + "_jobs_") && p.toString().endsWith(".json"))
            .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
            .orElse(null);
      }
      if (latest != null) {
        return mapper.readValue(Files.readString(latest), new TypeReference<>() {});
      }
    }
    return new ArrayList<>();
  }

  private static final java.util.regex.Pattern POSITIVE_SIGNAL = java.util.regex.Pattern.compile(
      "\\b(java|spring|springboot|j2ee|jvm|kotlin|scala|python|django|fastapi|react|angular|vue|javascript|"
      + "typescript|node|nodejs|full.?stack|fullstack|back.?end|front.?end|web developer|web development|api|"
      + "apis|microservice|microservices|rest|graphql|aws|azure|gcp|cloud|devops|sre|platform|kubernetes|"
      + "openshift|ai|ml|machine learning|llm|genai|generative)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  // Roles that can never be the kind of work this dashboard searches for.
  private static final java.util.regex.Pattern ALWAYS_BLOCK = java.util.regex.Pattern.compile(
      "\\b(mechanical|civil|structural|plumbing|hvac|solar|renewable|energy storage|construction|"
      + "superintendent|facilities|facility|thermal|signal integrity|instrumentation|geotechnical|automotive|"
      + "aerospace|asic|rtl|fpga|emissions|warranty|packaging engineer|industrial|electrician|welder|machinist|"
      + "recruiter|recruiting|accountant|accounts payable|accounts receivable|collections|payroll|bookkeep|"
      + "supply chain|procurement|logistics|warehouse|picker|packer|shipping|receptionist|office admin|"
      + "executive assistant|media relations|instructional designer|campaign|marketing|sales consultant|"
      + "service desk|help desk|helpdesk|desktop support|break.?fix|pc refresh|technician|field engineer)\\b",
      java.util.regex.Pattern.CASE_INSENSITIVE);

  // Blocked unless the title also carries a signal from the target stack.
  private static final java.util.regex.Pattern BLOCK_UNLESS_POSITIVE = java.util.regex.Pattern.compile(
      "\\b(embedded|firmware|ios|android|mobile|qa|quality assurance|tester|test engineer|test analyst|"
      + "testing|sdet)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  private boolean hardBlocked(String title) {
    if (title == null || title.isBlank()) return false;
    if (ALWAYS_BLOCK.matcher(title).find()) return true;
    return BLOCK_UNLESS_POSITIVE.matcher(title).find() && !POSITIVE_SIGNAL.matcher(title).find();
  }

  public Map<String, Object> aiCleanJobs(String slug) {
    Vendor v = findVendor(slug);
    if (v == null) return Map.of("ok", false, "error", "Unknown vendor.");
    List<Map<String, Object>> jobs;
    try {
      jobs = loadLatestJobs(v);
    } catch (Exception e) {
      return Map.of("ok", false, "error", "Failed to read jobs: " + e.getMessage());
    }
    jobs = filterConfiguredJobs(jobs, v);
    jobs = filterAiHidden(jobs, loadAiHidden(v));
    if (jobs.isEmpty()) {
      return Map.of("ok", true, "vendor", v.label(), "jobs", jobs, "removed_count", 0, "reviewed_count", 0);
    }

    int reviewed = jobs.size();
    long startedAt = System.currentTimeMillis();
    java.util.Set<String> hidden = new java.util.HashSet<>(loadAiHidden(v));
    List<Map<String, Object>> kept = new ArrayList<>();
    List<Map<String, Object>> forModel = new ArrayList<>();
    int ruleRemoved = 0;
    for (Map<String, Object> job : jobs) {
      if (hardBlocked(String.valueOf(job.getOrDefault("title", "")))) {
        hidden.add(jobKey(job));
        ruleRemoved++;
      } else {
        forModel.add(job);
      }
    }

    int aiRemoved = 0;
    double[] costAcc = new double[1];
    if (!forModel.isEmpty()) {
      Path cli = resolveClaudeCli();
      if (cli == null) return Map.of("ok", false, "error", "Claude Code CLI not found on this machine.");
      List<String> keywords = normalizeKeywords(loadConfig().get("keywords"));
      System.out.println("[ai-clean] " + v.slug() + ": " + reviewed + " titles, " + ruleRemoved
          + " dropped by rules, " + forModel.size() + " sent to Sonnet");
      final int BATCH = 25;
      for (int from = 0; from < forModel.size(); from += BATCH) {
        List<Map<String, Object>> batch = forModel.subList(from, Math.min(from + BATCH, forModel.size()));
        java.util.Set<Integer> drop;
        try {
          drop = classifyBatch(cli, keywords, batch, costAcc);
        } catch (Exception e) {
          System.out.println("[ai-clean] " + v.slug() + " FAILED: " + e);
          return Map.of("ok", false, "error", "AI cleanup failed: " + e.getMessage());
        }
        for (int i = 0; i < batch.size(); i++) {
          if (drop.contains(i)) {
            hidden.add(jobKey(batch.get(i)));
            aiRemoved++;
          } else {
            kept.add(batch.get(i));
          }
        }
      }
    }

    try {
      saveAiHidden(v, hidden);
    } catch (IOException e) {
      return Map.of("ok", false, "error", "Failed to save AI filter: " + e.getMessage());
    }
    int removed = ruleRemoved + aiRemoved;
    System.out.println("[ai-clean] " + v.slug() + ": reviewed " + reviewed + ", removed " + removed
        + " (" + ruleRemoved + " by rules, " + aiRemoved + " by AI) in " + (System.currentTimeMillis() - startedAt)
        + "ms, cost $" + String.format("%.3f", costAcc[0]));
    return Map.of("ok", true, "vendor", v.label(), "jobs", kept, "removed_count", removed,
        "reviewed_count", reviewed, "hidden_count", hidden.size(), "cost_usd", costAcc[0]);
  }

  private java.util.Set<Integer> classifyBatch(Path cli, List<String> keywords, List<Map<String, Object>> batch,
      double[] costAcc) throws IOException, InterruptedException {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are screening scraped job TITLES for one specific job seeker.\n\n");
    prompt.append("THEIR TARGET STACK: ")
        .append(keywords.isEmpty() ? "Java, Spring Boot, full stack web, React/Angular, REST APIs, microservices, AWS"
            : String.join(", ", keywords))
        .append("\nThey also want closely related software work: backend/frontend/full-stack web development, Python, ")
        .append("Node/JavaScript/TypeScript, .NET/C#, cloud, DevOps/SRE, security and infrastructure engineering, ")
        .append("API and microservice work, and AI/ML engineering.\n\n");
    prompt.append("KEEP a title when the role is hands-on software engineering whose skills overlap that stack, ")
        .append("or when the title is generic enough that it might ('Software Engineer', 'Developer', ")
        .append("'Software Engineer III', 'Lead Software Engineer', 'Platform Engineer').\n\n");
    prompt.append("REMOVE a title in any of these cases:\n");
    prompt.append("1) Not a software role at all — hardware, mechanical, electrical, civil, automotive, aerospace, ")
        .append("chemical, industrial, manufacturing, construction, facilities, field service, technician, ")
        .append("warehouse/logistics, finance, accounting, HR/recruiting, sales, marketing, legal, EHS, clinical.\n");
    prompt.append("2) Not hands-on engineering — project/program/delivery manager, project engineer, product owner, ")
        .append("product manager, scrum master, coordinator, business analyst, systems analyst, technical writer, ")
        .append("designer, support/service desk, administrator (system, CRM, website, IT asset), analyst roles.\n");
    prompt.append("3) Embedded or firmware engineering, or mobile-native development (iOS, Android).\n");
    prompt.append("4) QA / testing / test-automation roles.\n");
    prompt.append("5) Software, but a specialized vendor/legacy platform with no overlap with the target stack — ")
        .append("SAP/ABAP, Salesforce, ServiceNow, Workday, UKG, PeopleSoft, Siebel, PEGA, Guidewire, Curam, TIBCO, ")
        .append("webMethods, MuleSoft, Endur, Murex, Essbase/Hyperion, Informatica, Ab Initio, Cognos, SharePoint, ")
        .append("MS Dynamics, Adobe AJO/Campaign, Slate CRM, Mainframe/COBOL/AS400, RPA/UiPath/Blue Prism, ")
        .append("Power BI/Tableau reporting, Oracle DBA or PL/SQL-only roles.\n\n");
    prompt.append("TIE-BREAKER: if a title mixes one of those platforms WITH the target stack (e.g. 'Java Developer ")
        .append("with Salesforce integration'), KEEP it. If you truly cannot tell what the role is, KEEP it. ")
        .append("Only remove when you are confident.\n\n");
    prompt.append("Return the numbers of the titles to REMOVE.\n\nTITLES:\n");
    for (int i = 0; i < batch.size(); i++) {
      prompt.append(i + 1).append(". ").append(String.valueOf(batch.get(i).getOrDefault("title", ""))).append("\n");
    }

    String schema = "{\"type\":\"object\",\"properties\":{\"irrelevant\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}},\"required\":[\"irrelevant\"]}";
    List<String> cmd = List.of(cli.toString(), "-p", prompt.toString(), "--model", "sonnet", "--effort", "low",
        "--output-format", "json", "--json-schema", schema, "--restricted",
        "--permission-prompts", "none", "--no-session-persistence");

    ProcessBuilder pb = new ProcessBuilder(cmd);
    Process p = pb.start();
    p.getOutputStream().close();
    java.util.concurrent.CompletableFuture<String> stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try (InputStream is = p.getInputStream()) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return "";
      }
    });
    java.util.concurrent.CompletableFuture<String> stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try (InputStream is = p.getErrorStream()) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return "";
      }
    });
    if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IOException("Claude CLI timed out after 120s");
    }
    String stdout;
    String stderr;
    try {
      stdout = stdoutFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
      stderr = stderrFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      stdout = stdoutFuture.getNow("");
      stderr = stderrFuture.getNow("");
    }
    if (p.exitValue() != 0) {
      String detail = stderr.isBlank() ? stdout : stderr;
      throw new IOException("Claude CLI exited with code " + p.exitValue() + ": " + detail.trim().lines().findFirst().orElse(""));
    }
    if (stdout.isBlank()) throw new IOException("Claude CLI returned no output. " + stderr.trim().lines().findFirst().orElse(""));
    Map<?, ?> outer = mapper.readValue(stdout, Map.class);
    if (Boolean.TRUE.equals(outer.get("is_error"))) {
      throw new IOException("Claude CLI reported an error: " + outer.get("result"));
    }
    Object structured = outer.get("structured_output");
    if (!(structured instanceof Map<?, ?>) || !(((Map<?, ?>) structured).get("irrelevant") instanceof List<?>)) {
      throw new IOException("AI returned no usable answer (" + outer.get("subtype") + "). Raw: " + outer.get("result"));
    }
    if (outer.get("total_cost_usd") instanceof Number n) costAcc[0] += n.doubleValue();
    java.util.Set<Integer> drop = new java.util.HashSet<>();
    for (Object o : (List<?>) ((Map<?, ?>) structured).get("irrelevant")) {
      int idx = Integer.parseInt(String.valueOf(o)) - 1;
      if (idx >= 0 && idx < batch.size()) drop.add(idx);
    }
    return drop;
  }

  private Path resolveClaudeCli() {
    String override = System.getenv("CLAUDE_CLI_PATH");
    if (override != null && Files.isExecutable(Path.of(override))) return Path.of(override);
    try {
      Process which = new ProcessBuilder("which", "claude").redirectErrorStream(true).start();
      String out = new String(which.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      which.waitFor();
      if (!out.isBlank() && Files.isExecutable(Path.of(out))) return Path.of(out);
    } catch (Exception ignored) {}
    Path extDir = Path.of(System.getProperty("user.home"), ".vscode", "extensions");
    try {
      if (Files.isDirectory(extDir)) {
        try (Stream<Path> stream = Files.list(extDir)) {
          Path best = stream
              .filter(p -> p.getFileName().toString().startsWith("anthropic.claude-code-"))
              .max(Comparator.comparing(p -> p.getFileName().toString()))
              .orElse(null);
          if (best != null) {
            Path bin = best.resolve("resources").resolve("native-binary").resolve("claude");
            if (Files.isExecutable(bin)) return bin;
          }
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  public Map<String, Object> open(Map<String, Object> payload) throws IOException {
    String slug = String.valueOf(payload.getOrDefault("vendor", ""));
    Vendor v = findVendor(slug);
    if (v == null) return Map.of("ok", false, "error", "Unknown vendor.");
    stopOpenProcess(slug);
    List<String> cmd = openCommand(v, loadConfig(), payload);
    ProcessBuilder pb = new ProcessBuilder(cmd)
        .directory(root.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD);
    Process p = pb.start();
    p.getOutputStream().close();
    openProcesses.put(slug, p);
    p.onExit().thenAccept(finished -> openProcesses.remove(slug, finished));
    return Map.of("ok", true, "status", "started", "vendor", v.label());
  }

  public Map<String, Object> stopOpen(String slug) {
    Vendor v = findVendor(slug);
    if (v == null) return Map.of("ok", false, "error", "Unknown vendor.");
    boolean stopped = stopOpenProcess(slug);
    if (!stopped) return Map.of("ok", false, "error", "No active browser session for this vendor.");
    return Map.of("ok", true, "status", "stopped", "vendor", v.label());
  }

  private boolean stopOpenProcess(String slug) {
    Process p = openProcesses.remove(slug);
    if (p == null || !p.isAlive()) return false;
    p.descendants().forEach(ProcessHandle::destroy);
    p.destroy();
    return true;
  }

  private void runScrapers(String runId, List<String> slugs, Map<String, Object> config) {
    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> steps = (List<Map<String, Object>>) runs.get(runId).get("steps");
      ExecutorService workers = Executors.newFixedThreadPool(3);
      List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
      try {
        for (String slug : slugs.stream().distinct().toList()) {
          Vendor vendor = findVendor(slug);
          if (vendor == null) continue;
          Map<String, Object> step = new ConcurrentHashMap<>();
          step.put("vendor", vendor.label());
          step.put("slug", slug);
          step.put("status", "queued");
          steps.add(step);
          tasks.add(workers.submit(() -> runVendor(runId, vendor, config, step)));
        }
        for (java.util.concurrent.Future<?> task : tasks) task.get();
      } finally {
        workers.shutdownNow();
      }
      boolean ok = steps.stream().allMatch(s -> Objects.equals("done", s.get("status")));
      synchronized (this) {
        runs.get(runId).put("status", stopRequested ? "stopped" : ok ? "done" : "failed");
      }
    } catch (Exception e) {
      runs.get(runId).put("error", String.valueOf(e.getMessage()));
    } finally {
      synchronized (this) {
        if (stopRequested) runs.get(runId).put("status", "stopped");
        else if ("running".equals(runs.get(runId).get("status"))) runs.get(runId).put("status", "failed");
        runs.get(runId).put("finished_at", LocalDateTime.now().toString());
        activeRunId = "";
        running.set(false);
      }
    }
  }

  private void runVendor(String runId, Vendor v, Map<String, Object> config, Map<String, Object> step) {
    String slug = v.slug();
    synchronized (this) {
      if (stopRequested) {
        step.put("status", "stopped");
        return;
      }
      step.put("status", "running");
    }
        try {
          List<String> cmd = scrapeCommand(v, config);
          ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
          Process p;
          synchronized (this) {
            if (stopRequested) {
              step.put("status", "stopped");
              return;
            }
            p = pb.start();
            activeScrapers.put(slug, p);
          }
          String output;
          int code;
          try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
          }
          try {
            code = p.waitFor();
          } finally {
            p.destroy();
          }
          if (code == 0 && "teksystems_all_days".equals(runs.get(runId).get("kind"))) {
            Map<String, Object> latest = vendorStatus(v);
            String file = String.valueOf(latest.get("latest_file"));
            if (!file.isBlank()) {
              Path path = root.resolve(file);
              List<Map<String, Object>> jobs = mapper.readValue(Files.readString(path), new TypeReference<>() {});
              jobs.forEach(job -> job.put("dashboard_all_days", true));
              Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));
            }
          }
          Map<String, Object> status = vendorStatus(v);
          step.put("status", code == 0 ? "done" : "failed");
          step.put("returncode", code);
          step.put("count", status.get("latest_count"));
          step.put("command", String.join(" ", cmd));
          if (!output.isBlank()) step.put("output", output.length() > 5000 ? output.substring(output.length() - 5000) : output);
        } catch (Exception e) {
          step.put("status", "failed");
          step.put("returncode", -1);
          step.put("output", String.valueOf(e.getMessage()));
        } finally {
          synchronized (this) {
            Process activeScraper = activeScrapers.remove(slug);
            if (activeScraper != null && activeScraper.isAlive()) {
              activeScraper.descendants().forEach(ProcessHandle::destroyForcibly);
              activeScraper.destroyForcibly();
            }
            if (stopRequested) step.put("status", "stopped");
          }
        }
  }

  private Map<String, Object> loadConfig() {
    Map<String, Object> defaults = defaultConfig();
    if (Files.exists(configPath)) {
      try {
        Map<String, Object> in = mapper.readValue(Files.readString(configPath), new TypeReference<>() {});
        defaults.putAll(in);
      } catch (Exception ignored) {}
    }
    defaults.put("keywords", normalizeKeywords(defaults.get("keywords")));
    defaults.put("ignore_titles", normalizeKeywords(defaults.get("ignore_titles")));
    return defaults;
  }

  private void writeConfig(Map<String, Object> config) {
    try {
      Map<String, Object> clean = defaultConfig();
      clean.putAll(config);
      clean.put("keywords", normalizeKeywords(clean.get("keywords")));
      clean.put("ignore_titles", normalizeKeywords(clean.get("ignore_titles")));
      Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clean), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Map<String, Object> defaultConfig() {
    return new HashMap<>(Map.of(
        "posted_within_days", 4, "open_limit", 8, "start_at", 1, "delay", 0.5, "keep_open_minutes", 60,
        "keywords", List.of(
            "java developer",
            "java engineer",
            "full stack java developer",
            "spring boot developer",
            "backend java developer",
            "software engineer java",
            "microservices developer",
            "rest api developer",
            "hibernate developer",
            "jpa developer",
            "aws java developer",
            "react java full stack",
            "java full stack engineer",
            "distributed systems java"
        ),
        "ignore_titles", List.of(),
        "vendor_overrides", new HashMap<>()
    ));
  }

  private List<String> normalizeKeywords(Object v) {
    List<String> source = new ArrayList<>();
    if (v instanceof List<?> l) for (Object o : l) source.add(stripQuotes(String.valueOf(o).trim()));
    if (v instanceof String s) for (String p : s.split("\\R+")) source.add(stripQuotes(p.trim()));
    return source.stream().filter(s -> !s.isBlank()).distinct().toList();
  }

  private String stripQuotes(String s) {
    while (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
        || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
      s = s.substring(1, s.length() - 1).trim();
    }
    return s;
  }

  private List<Map<String, Object>> vendorStatus() {
    return vendors.stream().map(this::vendorStatus).toList();
  }

  private List<Map<String, Object>> filterConfiguredJobs(List<Map<String, Object>> jobs, Vendor vendor) {
    Map<String, Object> config = loadConfig();
    List<String> keywords = normalizeKeywords(config.get("keywords"));
    List<String> ignored = normalizeKeywords(config.get("ignore_titles"));
    int days = Integer.parseInt(String.valueOf(config.getOrDefault("posted_within_days", 4)));
    return jobs.stream().filter(job -> JobFilter.matches(job, keywords, ignored,
        "teksystems".equals(vendor.slug()) && Boolean.TRUE.equals(job.get("dashboard_all_days")) ? 0 : days)).toList();
  }

  private Map<String, Object> vendorStatus(Vendor v) {
    Path out = root.resolve(v.folder()).resolve("output");
    Path latest = null;
    int count = 0;
    int todayCount = 0;
    String modified = "";
    try {
      if (Files.exists(out)) {
        try (Stream<Path> stream = Files.list(out)) {
          latest = stream
              .filter(p -> p.getFileName().toString().startsWith(v.prefix() + "_jobs_") && p.toString().endsWith(".json"))
              .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
              .orElse(null);
        }
        if (latest != null) {
          List<Map<String, Object>> items = mapper.readValue(Files.readString(latest), new TypeReference<>() {});
          List<Map<String, Object>> visible = filterAiHidden(filterConfiguredJobs(items, v), loadAiHidden(v));
          count = visible.size();
          todayCount = (int) visible.stream()
              .filter(job -> LocalDate.now().equals(JobFilter.date(job.get("posted_date")))).count();
          modified = TS.format(LocalDateTime.ofEpochSecond(latest.toFile().lastModified() / 1000, 0, java.time.ZoneOffset.UTC));
        }
      }
    } catch (Exception ignored) {}
    Map<String, Object> m = new HashMap<>();
    m.put("slug", v.slug());
    m.put("label", v.label());
    m.put("latest_file", latest == null ? "" : root.relativize(latest).toString());
    m.put("latest_count", count);
    m.put("today_count", todayCount);
    m.put("latest_modified", modified);
    LocalDate today = LocalDate.now();
    m.put("active_today", isWeekday(today) && activePair(today).contains(v.slug()));
    Process p = openProcesses.get(v.slug());
    m.put("open_running", p != null && p.isAlive());
    return m;
  }

  private boolean isWeekday(LocalDate day) {
    return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
  }

  private List<String> activePair(LocalDate day) {
    LocalDate anchor = LocalDate.of(2026, 5, 11);
    int step = day.isBefore(anchor) ? -1 : 1;
    LocalDate current = anchor;
    int count = 0;
    while (!current.equals(day)) {
      current = current.plusDays(step);
      if (current.getDayOfWeek().getValue() <= 5) count += step;
    }
    return pairs.get(Math.floorMod(count, pairs.size()));
  }

  private List<Map<String, Object>> rotationPreview() {
    List<Map<String, Object>> rows = new ArrayList<>();
    LocalDate d = LocalDate.now();
    while (rows.size() < 10) {
      if (isWeekday(d)) {
        List<String> slugs = activePair(d);
        List<String> names = slugs.stream().map(s -> findVendor(s).label()).toList();
        rows.add(Map.of("date", d.toString(), "weekday", d.getDayOfWeek().name().substring(0, 1) + d.getDayOfWeek().name().substring(1).toLowerCase(), "vendors", names, "slugs", slugs));
      }
      d = d.plusDays(1);
    }
    return rows;
  }

  private List<String> scrapeCommand(Vendor v, Map<String, Object> config) throws IOException {
    List<String> cmd = new ArrayList<>(List.of(pythonBin, root.resolve(v.folder()).resolve(v.scraper()).toString(), "--posted-within-days", String.valueOf(config.getOrDefault("posted_within_days", 4))));
    List<String> keywords = normalizeKeywords(config.get("keywords"));
    if ("file".equals(v.termsMode())) {
      Path terms = root.resolve(v.folder()).resolve(".dashboard_terms.txt");
      Files.writeString(terms, String.join("\n", keywords) + "\n", StandardCharsets.UTF_8);
      cmd.add("--terms-file");
      cmd.add(terms.toString());
    } else if ("append".equals(v.termsMode())) {
      for (String k : keywords) {
        cmd.add("--term");
        cmd.add(k);
      }
    }
    if (!v.pageArg().isBlank()) {
      Object pageValue = pageOverride(v, config);
      if (pageValue != null && !String.valueOf(pageValue).isBlank()) {
        cmd.add("--" + v.pageArg());
        cmd.add(String.valueOf(pageValue));
      }
    }
    List<String> ignoreTitles = normalizeKeywords(config.get("ignore_titles"));
    if (!ignoreTitles.isEmpty()) {
      Path ignoreFile = root.resolve(v.folder()).resolve(".dashboard_ignore_titles.txt");
      Files.writeString(ignoreFile, String.join("\n", ignoreTitles) + "\n", StandardCharsets.UTF_8);
      cmd.add("--ignore-titles-file");
      cmd.add(ignoreFile.toString());
    }
    return cmd;
  }

  private List<String> openCommand(Vendor v, Map<String, Object> config, Map<String, Object> payload) {
    List<String> cmd = new ArrayList<>(List.of(
        pythonBin, root.resolve(v.folder()).resolve(v.opener()).toString(),
        "--limit", String.valueOf(payload.getOrDefault("limit", config.getOrDefault("open_limit", 8))),
        "--start-at", String.valueOf(payload.getOrDefault("start_at", config.getOrDefault("start_at", 1)))
    ));
    String openerSource = readOpenerSource(v);
    if (openerSource.contains("keep-open-minutes")) {
      cmd.add("--keep-open-minutes");
      cmd.add(String.valueOf(payload.getOrDefault("keep_open_minutes", config.getOrDefault("keep_open_minutes", 60))));
    }
    if (openerSource.contains("--delay")) {
      cmd.add("--delay");
      cmd.add(String.valueOf(config.getOrDefault("delay", 0.5)));
    }
    return cmd;
  }

  @SuppressWarnings("unchecked")
  private Object pageOverride(Vendor v, Map<String, Object> config) {
    Object overridesRaw = config.get("vendor_overrides");
    if (!(overridesRaw instanceof Map<?, ?> overrides)) return null;
    Object vendorRaw = overrides.get(v.slug());
    if (!(vendorRaw instanceof Map<?, ?> vendorOverrides)) return null;
    return vendorOverrides.get(v.pageArg());
  }

  private String readOpenerSource(Vendor v) {
    try {
      return Files.readString(root.resolve(v.folder()).resolve(v.opener()));
    } catch (IOException e) {
      return "";
    }
  }

  private String resolvePython() {
    Path venvPython = root.resolve(".venv").resolve("bin").resolve("python3");
    if (Files.exists(venvPython)) return venvPython.toString();
    return "python3";
  }

  private Vendor findVendor(String slug) {
    return vendors.stream().filter(v -> v.slug().equals(slug)).findFirst().orElse(null);
  }

  private boolean isKnownVendor(String slug) {
    return findVendor(slug) != null;
  }

  record Vendor(String slug, String label, String folder, String scraper, String opener, String prefix, String termsMode, String pageArg, int pageDefault) {}
}
