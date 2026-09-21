package com.jobportal.dashboard;

import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;

/** Applies user settings to job content, never to the search query that retrieved it. */
final class JobFilter {
  private JobFilter() {}

  static String normalized(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("<[^>]*>", " ")
        .replaceAll("[^\\p{L}\\p{N}+#]+", " ").trim();
  }

  static boolean phrase(String text, String phrase) {
    String needle = normalized(phrase);
    if (needle.isEmpty()) return false;
    String expression = Arrays.stream(needle.split(" +"))
        .map(Pattern::quote).reduce((a, b) -> a + " *" + b).orElse("");
    return Pattern.compile("(?<![\\p{L}\\p{N}+#])" + expression + "(?![\\p{L}\\p{N}+#])")
        .matcher(normalized(text)).find();
  }

  static boolean matches(Map<String, Object> job, List<String> keywords, List<String> ignored, int days) {
    String title = Objects.toString(job.get("title"), "");
    if (ignored.stream().anyMatch(term -> phrase(title, term))) return false;
    LocalDate posted = date(job.get("posted_date"));
    if (days > 0 && posted != null && posted.isBefore(LocalDate.now().minusDays(days))) return false;
    if (keywords.isEmpty()) return true;
    String description = Objects.toString(job.get("raw_text"), "") + " "
        + Objects.toString(job.get("description_snippet"), "") + " "
        + Objects.toString(job.get("description"), "");
    String content = title + " " + description;
    // Each keyword line is an alternative. Token order may vary in real job titles.
    if (keywords.stream().anyMatch(term -> Arrays.stream(normalized(term).split(" +"))
        .filter(t -> !t.isBlank()).allMatch(t -> phrase(content, t)))) return true;
    // Missing descriptions cannot establish that a generic title is irrelevant.
    return description.isBlank();
  }

  static LocalDate date(Object value) {
    String text = Objects.toString(value, "").trim();
    try {
      if (text.matches("\\d{10,13}")) {
        long timestamp = Long.parseLong(text);
        return Instant.ofEpochMilli(text.length() == 10 ? timestamp * 1000 : timestamp)
            .atZone(ZoneId.systemDefault()).toLocalDate();
      }
      if (text.length() >= 10 && text.charAt(4) == '-') return LocalDate.parse(text.substring(0, 10));
      for (String format : List.of("M/d/uuuu", "MMM d, uuuu", "MMMM d, uuuu")) {
        try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(format, Locale.US)); }
        catch (DateTimeParseException ignored) {}
      }
    } catch (RuntimeException ignored) {}
    return null; // Retain unknown dates rather than silently losing possible matches.
  }
}
