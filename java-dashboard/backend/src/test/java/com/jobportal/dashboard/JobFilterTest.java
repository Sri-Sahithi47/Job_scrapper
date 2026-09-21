package com.jobportal.dashboard;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JobFilterTest {
  boolean matches(String title, String description, List<String> keywords) {
    return JobFilter.matches(Map.of("title", title, "raw_text", description), keywords, List.of(), 4);
  }
  @Test void usesDescriptionAndAlternativeKeywords() {
    assertTrue(matches("Software Engineer", "Build services using Java", List.of("react", "java")));
    assertTrue(matches("Developer, Java", "Develop services", List.of("java developer")));
    assertFalse(matches("Payroll Clerk", "Process payroll", List.of("java")));
    assertFalse(matches("Frontend Developer", "JavaScript only", List.of("java")));
    assertTrue(matches("Software Engineer", "", List.of("java")));
  }
  @Test void ignorePhrasesHaveBoundariesAndPunctuationTolerance() {
    assertFalse(JobFilter.phrase("Design Engineer", "GIS"));
    assertTrue(JobFilter.phrase("Senior GIS Engineer", "GIS"));
    assertTrue(JobFilter.phrase("Oracle PLSQL Developer", "Oracle PL/SQL"));
    assertTrue(JobFilter.phrase("Full-stack Java Developer", "full stack"));
  }
  @Test void currentSettingsApplyToSavedJobs() {
    var job = Map.<String,Object>of("title", "Java Developer", "posted_date", LocalDate.now().minusDays(10).toString());
    assertFalse(JobFilter.matches(job, List.of("java"), List.of(), 4));
    assertTrue(JobFilter.matches(job, List.of("java"), List.of(), 30));
    assertFalse(JobFilter.matches(job, List.of("java"), List.of("developer"), 30));
  }
  @Test void queryMetadataDoesNotQualifyUnrelatedContent() {
    assertFalse(JobFilter.matches(Map.of("title", "Accountant", "raw_text", "Prepare tax reports", "search_term", "java"), List.of("java"), List.of(), 4));
  }
}
