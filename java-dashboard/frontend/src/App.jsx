import { useEffect, useMemo, useRef, useState } from "react";

const API = "http://127.0.0.1:8766";

export default function App() {
  const [config, setConfig] = useState({});
  const [vendors, setVendors] = useState([]);
  const [selected, setSelected] = useState([]);
  const [runs, setRuns] = useState([]);
  const [scrapeStopSupported, setScrapeStopSupported] = useState(false);
  const [allDaysSupported, setAllDaysSupported] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("Ready.");
  const [error, setError] = useState("");
  const [keywordsText, setKeywordsText] = useState("");
  const [ignoreTitlesText, setIgnoreTitlesText] = useState("");
  const [jobsPanel, setJobsPanel] = useState({ open: false, vendor: "", slug: "", jobs: [], loading: false, error: "" });
  const [expandedJobId, setExpandedJobId] = useState(null);
  const [selectedJobIds, setSelectedJobIds] = useState([]);
  const [aiCleaning, setAiCleaning] = useState(false);
  const [aiAllStatus, setAiAllStatus] = useState("");
  useEffect(() => {
    if (!aiAllStatus || aiCleaning) return;
    const timeout = setTimeout(() => setAiAllStatus(""), 8000);
    return () => clearTimeout(timeout);
  }, [aiAllStatus, aiCleaning]);

  const defaultsApplied = useRef(false);
  const keywordsInitialized = useRef(false);
  const ignoreTitlesInitialized = useRef(false);

  const latestRun = useMemo(() => runs[runs.length - 1], [runs]);
  const activeScrape = runs.find((run) => ["running", "stopping"].includes(run.status));
  const allChecked = vendors.length > 0 && vendors.every((v) => selected.includes(v.slug));

  function stripQuotes(s) {
    while (s.length >= 2 && ((s[0] === '"' && s[s.length - 1] === '"') || (s[0] === "'" && s[s.length - 1] === "'"))) {
      s = s.slice(1, -1).trim();
    }
    return s;
  }

  function parseKeywords(text) {
    return text.split(/\n+/).map((x) => stripQuotes(x.trim())).filter(Boolean);
  }

  async function api(path, options = {}) {
    const r = await fetch(`${API}${path}`, { headers: { "Content-Type": "application/json" }, ...options });
    const data = await r.json().catch(() => ({}));
    if (!r.ok || data.ok === false) throw new Error(data.error || r.statusText);
    return data;
  }

  async function refresh() {
    try {
      const c = await api("/api/config");
      setConfig(c.config || {});
      setVendors(c.vendors || []);
      setError("");
    } catch (e) {
      setError(e.message);
    }
  }

  async function refreshStatus() {
    try {
      const s = await api("/api/status");
      setRuns(s.runs || []);
      setScrapeStopSupported(s.scrape_stop_supported === true);
      setAllDaysSupported(s.teksystems_all_days_supported === true);
      setVendors(s.vendors || []);
      setError("");
    } catch (e) {
      setError(e.message);
    }
  }

  async function saveConfig() {
    const next = { ...config, keywords: parseKeywords(keywordsText), ignore_titles: parseKeywords(ignoreTitlesText) };
    const r = await api("/api/config", { method: "POST", body: JSON.stringify(next) });
    const saved = r.config || next;
    setConfig(saved);
    setKeywordsText((saved.keywords || []).join("\n"));
    setIgnoreTitlesText((saved.ignore_titles || []).join("\n"));
    setDirty(false);
    await refreshStatus();
    if (jobsPanel.open && jobsPanel.slug) await openJobsPanel(jobsPanel.slug, jobsPanel.vendor);
  }

  async function scrape(mode, selected = []) {
    setBusy(true);
    try {
      setError("");
      await saveConfig();
      await api("/api/scrape", { method: "POST", body: JSON.stringify({ mode, vendors: selected }) });
      setSelected([]);
      setMessage("Fresh scrape started. Old counts remain visible until new output finishes.");
      await refreshStatus();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function stopScrape() {
    if (!activeScrape) return;
    setBusy(true);
    try {
      await api("/api/scrape/stop", { method: "POST", body: JSON.stringify({ run_id: activeScrape.id }) });
      setMessage("Stopping scrape. Completed results are kept.");
      await refreshStatus();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function stopOpenVendor(slug) {
    setBusy(true);
    try {
      setError("");
      await api("/api/open/stop", { method: "POST", body: JSON.stringify({ vendor: slug }) });
      setMessage(`Stopped opening ${slug} jobs.`);
      await refreshStatus();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function openJobsPanel(slug, label) {
    setExpandedJobId(null);
    setSelectedJobIds([]);
    setJobsPanel({ open: true, vendor: label, slug, jobs: [], loading: true, error: "" });
    try {
      const r = await api(`/api/jobs?vendor=${encodeURIComponent(slug)}`);
      setJobsPanel({ open: true, vendor: label, slug, jobs: r.jobs || [], loading: false, error: "", hiddenCount: r.hidden_count || 0 });
    } catch (e) {
      setJobsPanel({ open: true, vendor: label, slug, jobs: [], loading: false, error: e.message });
    }
  }

  function closeJobsPanel() {
    setJobsPanel((prev) => ({ ...prev, open: false }));
    setExpandedJobId(null);
    setSelectedJobIds([]);
  }

  function toggleExpandedJob(jobId) {
    setExpandedJobId((prev) => (prev === jobId ? null : jobId));
  }

  function jobIdFor(job, i) {
    return job.job_id || job.job_url || String(i);
  }

  function toggleJobSelected(jobId, checked) {
    setSelectedJobIds((prev) => {
      if (checked) return prev.includes(jobId) ? prev : [...prev, jobId];
      return prev.filter((x) => x !== jobId);
    });
  }

  function toggleSelectAllJobs(checked) {
    if (!checked) {
      setSelectedJobIds([]);
      return;
    }
    setSelectedJobIds(jobsPanel.jobs.map((job, i) => jobIdFor(job, i)));
  }

  async function aiCleanAllPortals() {
    if (aiCleaning) return;
    setAiCleaning(true);
    setBusy(true);
    const failures = [];
    let reviewed = 0;
    let removed = 0;
    let completed = 0;
    try {
      await saveConfig();
      for (const [index, vendor] of vendors.entries()) {
        setAiAllStatus(`AI check ${index + 1}/${vendors.length}: ${vendor.label}…`);
        try {
          const result = await api("/api/jobs/ai-clean", {
            method: "POST", body: JSON.stringify({ vendor: vendor.slug })
          });
          reviewed += result.reviewed_count || 0;
          removed += result.removed_count || 0;
          completed++;
          setJobsPanel((previous) => previous.slug === vendor.slug
            ? { ...previous, jobs: result.jobs || [], hiddenCount: result.hidden_count || 0,
                aiError: "", aiNote: "AI check completed." }
            : previous);
        } catch (e) {
          failures.push(`${vendor.label}: ${e.message}`);
        }
      }
      setSelectedJobIds([]);
      await refreshStatus();
      setAiAllStatus(`AI check finished: ${completed}/${vendors.length} portals, ${reviewed} jobs reviewed, ${removed} hidden.`
        + (failures.length ? ` Failed: ${failures.join("; ")}` : ""));
    } catch (e) {
      setAiAllStatus(`AI check could not start: ${e.message}`);
    } finally {
      setAiCleaning(false);
      setBusy(false);
    }
  }

  async function aiCleanJobsPanel() {
    if (!jobsPanel.slug) return;
    setAiCleaning(true);
    setJobsPanel((prev) => ({ ...prev, aiError: "", aiNote: "" }));
    try {
      setError("");
      const r = await api("/api/jobs/ai-clean", { method: "POST", body: JSON.stringify({ vendor: jobsPanel.slug }) });
      const keptIds = new Set((r.jobs || []).map((job, i) => jobIdFor(job, i)));
      const cost = r.cost_usd ? ` (~$${r.cost_usd.toFixed(2)})` : "";
      const note = r.removed_count
        ? `AI reviewed ${r.reviewed_count ?? "?"} titles and removed ${r.removed_count}.${cost}`
        : `AI reviewed ${r.reviewed_count ?? "?"} titles — none were clearly irrelevant, so nothing was removed.${cost}`;
      setJobsPanel((prev) => ({ ...prev, jobs: r.jobs || [], aiError: "", aiNote: note, hiddenCount: r.hidden_count || 0 }));
      setSelectedJobIds((prev) => prev.filter((id) => keptIds.has(id)));
      setMessage(note);
      await refreshStatus();
    } catch (e) {
      console.error("AI cleanup failed:", e);
      setError(e.message);
      setJobsPanel((prev) => ({ ...prev, aiError: e.message }));
    } finally {
      setAiCleaning(false);
    }
  }

  async function undoAiCleanup() {
    if (!jobsPanel.slug) return;
    setAiCleaning(true);
    try {
      setError("");
      const r = await api("/api/jobs/ai-reset", { method: "POST", body: JSON.stringify({ vendor: jobsPanel.slug }) });
      setJobsPanel((prev) => ({ ...prev, jobs: r.jobs || [], aiError: "", aiNote: "AI cleanup undone — all postings restored.", hiddenCount: 0 }));
      setMessage("AI cleanup undone — all postings restored.");
      await refreshStatus();
    } catch (e) {
      console.error("AI undo failed:", e);
      setError(e.message);
      setJobsPanel((prev) => ({ ...prev, aiError: e.message }));
    } finally {
      setAiCleaning(false);
    }
  }

  async function openSelectedJobs() {
    const jobsById = new Map(jobsPanel.jobs.map((job, i) => [jobIdFor(job, i), job]));
    const urls = selectedJobIds
      .map((id) => jobsById.get(id))
      .map((job) => job?.job_url || job?.apply_url)
      .filter(Boolean);
    if (!urls.length) return;
    try {
      setError("");
      const r = await api("/api/jobs/open-urls", { method: "POST", body: JSON.stringify({ urls }) });
      setMessage(`Opened ${r.opened} job${r.opened === 1 ? "" : "s"} in your browser.`);
    } catch (e) {
      console.error("Open selected failed:", e);
      setError(e.message);
      setJobsPanel((prev) => ({ ...prev, aiError: e.message }));
    }
  }

  useEffect(() => {
    refresh();
    refreshStatus();
    const id = setInterval(() => refreshStatus(), 5000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (!vendors.length) return;
    if (!defaultsApplied.current) {
      setSelected(vendors.filter((v) => v.active_today).map((v) => v.slug));
      defaultsApplied.current = true;
    }
  }, [vendors]);

  useEffect(() => {
    if (!keywordsInitialized.current && config.keywords) {
      setKeywordsText((config.keywords || []).join("\n"));
      keywordsInitialized.current = true;
    }
  }, [config.keywords]);

  useEffect(() => {
    if (!ignoreTitlesInitialized.current && config.ignore_titles) {
      setIgnoreTitlesText((config.ignore_titles || []).join("\n"));
      ignoreTitlesInitialized.current = true;
    }
  }, [config.ignore_titles]);

  function toggleSelected(slug, checked) {
    if (checked) {
      if (!selected.includes(slug)) setSelected([...selected, slug]);
      return;
    }
    setSelected(selected.filter((x) => x !== slug));
  }

  function setConfigValue(key, value) {
    setConfig({ ...config, [key]: value });
    setDirty(true);
  }

  function renderRunLog() {
    if (error) return `Error: ${error}`;
    if (!latestRun) return message;
    const lines = [`Run ${latestRun.id} - ${latestRun.kind} - ${latestRun.status}`, "Fresh scrape run"];
    for (const step of latestRun.steps || []) {
      const countText = step.status === "running" ? "scraping..." : step.status === "queued" ? "waiting for a worker..." : `${step.count ?? 0} jobs`;
      lines.push(`${String(step.status || "").padEnd(7)} ${step.vendor}: ${countText}`);
      if (step.status === "failed" && step.output) lines.push(step.output);
    }
    return lines.join("\n");
  }

  return (
    <div className="page">
      <header>
        <div>
          <h1>Job Portal Control</h1>
          <div className="sub">Daily scrape all portals. Actively open two weekday portals from the rotation.</div>
        </div>
        <div className="buttons">
          <button className="primary" disabled={busy || Boolean(activeScrape)} onClick={() => scrape("all")}>Scrape All 15</button>
          <button className="warn" disabled={busy || !scrapeStopSupported || !activeScrape || activeScrape.status === "stopping"} onClick={stopScrape}>
            {activeScrape?.status === "stopping" ? "Stopping…" : "Stop Scrape"}
          </button>
          <button disabled={busy || aiCleaning || Boolean(activeScrape) || !vendors.length} onClick={aiCleanAllPortals}>Check All Portals with AI</button>
          <button disabled={busy} onClick={refreshStatus}>Refresh</button>
        </div>
      </header>
      {aiAllStatus && <div className="notice ai-status" role="status">
        <span>{aiAllStatus}</span>
        {!aiCleaning && <button aria-label="Dismiss AI check notification" onClick={() => setAiAllStatus("")}>×</button>}
      </div>}
      <main>
        <aside>
          <div className="block">
            <h2>Filters</h2>
            <div className="sub">These values are passed into each scraper that supports them.</div>
            <div className="form-grid">
              <label>Posted within days<input type="number" min="0" step="1" value={config.posted_within_days ?? 4} onChange={(e) => setConfigValue("posted_within_days", Number(e.target.value || 0))} /></label>
              <label>Open limit<input type="number" min="0" step="1" value={config.open_limit ?? 8} onChange={(e) => setConfigValue("open_limit", Number(e.target.value || 0))} /></label>
              <label>Start at<input type="number" min="1" step="1" value={config.start_at ?? 1} onChange={(e) => setConfigValue("start_at", Number(e.target.value || 1))} /></label>
              <label>Keep open minutes<input type="number" min="1" step="1" value={config.keep_open_minutes ?? 60} onChange={(e) => setConfigValue("keep_open_minutes", Number(e.target.value || 60))} /></label>
            </div>
          </div>

          <div className="block">
            <label>Keywords<textarea
              spellCheck="false"
              value={keywordsText}
              onChange={(e) => { setKeywordsText(e.target.value); setDirty(true); }}
            /></label>
          </div>

          <div className="block">
            <label>Ignore Titles<textarea
              spellCheck="false"
              placeholder="One phrase per line, e.g. junior, project manager"
              value={ignoreTitlesText}
              onChange={(e) => { setIgnoreTitlesText(e.target.value); setDirty(true); }}
            /></label>
            <div className="sub">Job titles containing any of these phrases are skipped.</div>
            <div className="buttons">
              <button className="primary" disabled={!dirty || busy} onClick={() => saveConfig()}>Save Controls</button>
            </div>
          </div>
        </aside>

        <section>
          <div className="toolbar">
            <h2>Portals</h2>
            <div className="buttons"><button disabled={busy || Boolean(activeScrape) || !selected.length} onClick={() => scrape("selected", selected)}>Scrape Checked</button></div>
          </div>
          <div className="table-wrap">
            <table>
              <colgroup>
                <col className="pick-col" />
                <col className="portal-col" />
                <col className="today-col" />
                <col className="count-col" />
                <col className="open-col" />
              </colgroup>
              <thead><tr><th><input type="checkbox" checked={allChecked} onChange={(e) => setSelected(e.target.checked ? vendors.map((v) => v.slug) : [])} /></th><th>Portal</th><th title="Matching jobs with a posting date of today">Posted Today</th><th>Latest Jobs</th><th>Controls</th></tr></thead>
              <tbody>{vendors.map((v) => (
                <tr key={v.slug} className={v.active_today ? "active" : ""}>
                  <td><input className="pick" type="checkbox" checked={selected.includes(v.slug)} onChange={(e) => toggleSelected(v.slug, e.target.checked)} /></td>
                  <td><strong>{v.label}</strong></td>
                  <td title="Matching jobs posted today; unknown posting dates are not counted">{v.today_count ?? "—"}{v.active_today && <> <span className="pill active-pill">active</span></>}</td>
                  <td className={v.latest_count ? "" : "zero"}>{v.latest_count}</td>
                  <td className="controls-cell">
                    {v.open_running && (
                      <button className="warn" disabled={busy} onClick={() => stopOpenVendor(v.slug)}>Stop</button>
                    )}
                    <button disabled={!v.latest_count} onClick={() => openJobsPanel(v.slug, v.label)}>Jobs</button>
                    {v.slug === "teksystems" && <button
                      disabled={busy || aiCleaning || Boolean(activeScrape) || !allDaysSupported}
                      title="Scrape TEKsystems across all posting dates, keeping your keywords and ignored titles"
                      onClick={() => scrape("teksystems_all_days")}>All Days</button>}
                  </td>
                </tr>
              ))}</tbody>
            </table>
          </div>
          <div className="log">{renderRunLog()}</div>
        </section>
      </main>

      {jobsPanel.open && <div className="jobs-backdrop" onClick={closeJobsPanel} />}
      <aside className={`jobs-panel ${jobsPanel.open ? "open" : ""}`}>
        <div className="jobs-panel-header">
          <h2>{jobsPanel.vendor || "Jobs"}</h2>
          <button className="jobs-panel-close" onClick={closeJobsPanel} aria-label="Close jobs panel">×</button>
        </div>
        <div className="jobs-panel-sub">{jobsPanel.loading ? "Loading..." : jobsPanel.error ? `Error: ${jobsPanel.error}` : `${jobsPanel.jobs.length} job${jobsPanel.jobs.length === 1 ? "" : "s"}`}</div>
        {!jobsPanel.loading && !jobsPanel.error && jobsPanel.jobs.length > 0 && (
          <div className="jobs-panel-actions">
            <label className="jobs-select-all">
              <input
                type="checkbox"
                checked={selectedJobIds.length > 0 && selectedJobIds.length === jobsPanel.jobs.length}
                onChange={(e) => toggleSelectAllJobs(e.target.checked)}
              />
              Select all
            </label>
            <button disabled={aiCleaning} onClick={aiCleanJobsPanel} title="Uses the local Claude Code Sonnet model to remove clearly irrelevant postings; keeps anything even plausibly relevant.">
              {aiCleaning ? "Reviewing with AI..." : "Clean Up with AI"}
            </button>
            {jobsPanel.hiddenCount > 0 && (
              <button disabled={aiCleaning} onClick={undoAiCleanup} title="Restore every posting the AI cleanup hid for this portal.">
                Undo ({jobsPanel.hiddenCount})
              </button>
            )}
            <button className="primary" disabled={!selectedJobIds.length} onClick={openSelectedJobs}>
              Open Selected ({selectedJobIds.length})
            </button>
          </div>
        )}
        {jobsPanel.aiError && <div className="jobs-panel-ai-error">AI cleanup failed: {jobsPanel.aiError}</div>}
        {!jobsPanel.aiError && jobsPanel.aiNote && <div className="jobs-panel-ai-note">{jobsPanel.aiNote}</div>}
        <div className="jobs-panel-list">
          {jobsPanel.jobs.map((job, i) => {
            const jobId = jobIdFor(job, i);
            const isExpanded = expandedJobId === jobId;
            const isSelected = selectedJobIds.includes(jobId);
            return (
              <div className={`job-item ${isExpanded ? "expanded" : ""}`} key={jobId}>
                <div className="job-title-row">
                  <input
                    type="checkbox"
                    className="job-select-checkbox"
                    checked={isSelected}
                    onClick={(e) => e.stopPropagation()}
                    onChange={(e) => toggleJobSelected(jobId, e.target.checked)}
                    aria-label={`Select ${job.title || "job"}`}
                  />
                  <button className="job-title-btn" onClick={() => toggleExpandedJob(jobId)}>
                    <span className="job-title">{job.title || "(untitled)"}</span>
                    <span className="job-chevron">{isExpanded ? "−" : "+"}</span>
                  </button>
                </div>
                {isExpanded && (
                  <div className="job-detail">
                    <div className="job-meta">
                      {job.location && <span>{job.location}</span>}
                      {job.employment_type && <span>{job.employment_type}</span>}
                      {job.salary && <span>{job.salary}</span>}
                      {job.posted_date && <span>{job.posted_date}</span>}
                    </div>
                    <p className="job-description">{job.description_snippet || "No description available."}</p>
                    {job.job_url && (
                      <a className="job-link" href={job.job_url} target="_blank" rel="noreferrer">Open posting</a>
                    )}
                  </div>
                )}
              </div>
            );
          })}
          {!jobsPanel.loading && !jobsPanel.error && jobsPanel.jobs.length === 0 && (
            <div className="jobs-panel-empty">No jobs found for this portal yet.</div>
          )}
        </div>
      </aside>
    </div>
  );
}
