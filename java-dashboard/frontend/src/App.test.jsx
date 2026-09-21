import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, beforeEach, vi } from "vitest";
import App from "./App.jsx";

const CONFIG = {
  posted_within_days: 4,
  open_limit: 8,
  start_at: 1,
  keep_open_minutes: 60,
  delay: 0.5,
  keywords: ["java developer", "spring boot developer"],
  ignore_titles: ["junior"]
};

const VENDORS = [
  { slug: "teksystems", label: "TEKsystems", active_today: false, latest_count: 0, latest_file: "", latest_modified: "", open_running: false },
  { slug: "judgegroup", label: "Judge Group", active_today: true, latest_count: 7, latest_file: "judgegroup.json", latest_modified: "2026-09-12 02:38", open_running: false },
  { slug: "beaconhill", label: "Beacon Hill", active_today: true, latest_count: 3, latest_file: "beaconhill.json", latest_modified: "2026-09-12 02:38", open_running: false }
];

const ROTATION = [
  { date: "2026-09-11", weekday: "Friday", vendors: ["Judge Group", "Beacon Hill"], slugs: ["judgegroup", "beaconhill"] },
  { date: "2026-09-14", weekday: "Monday", vendors: ["Akkodis", "Randstad"], slugs: ["akkodis", "randstad"] }
];

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({
    ok,
    status,
    statusText: ok ? "OK" : "Error",
    json: () => Promise.resolve(structuredClone(body))
  });
}

const JOBS = [
  {
    job_id: "j1",
    title: "Senior Full Stack Engineer, Java, AWS, React",
    location: "Whippany, NJ",
    employment_type: "Contract",
    salary: "$60 - $70/hr",
    posted_date: "2026-09-11",
    job_url: "https://example.com/jobs/j1",
    description_snippet: "We are looking for a senior full stack engineer with Java and AWS experience."
  },
  {
    job_id: "j2",
    title: "Backend Java Developer",
    location: "Remote",
    employment_type: "W2",
    salary: "",
    posted_date: "2026-09-10",
    job_url: "https://example.com/jobs/j2",
    description_snippet: "Backend role building microservices."
  }
];

function installFetchMock({ scrapeOk = true, openOk = true, jobsOk = true, stopOk = true, runs = [] } = {}) {
  const calls = [];
  const vendorState = VENDORS.map((v) => ({ ...v }));
  const fetchMock = vi.fn((url, options = {}) => {
    calls.push({ url, options });
    const method = options.method || "GET";
    if (url.endsWith("/api/config") && method === "GET") {
      return jsonResponse({ config: CONFIG, vendors: vendorState, rotation: ROTATION });
    }
    if (url.endsWith("/api/config") && method === "POST") {
      const next = JSON.parse(options.body);
      return jsonResponse({ ok: true, config: { ...CONFIG, ...next } });
    }
    if (url.endsWith("/api/status")) {
      return jsonResponse({ runs, scrape_stop_supported: true, teksystems_all_days_supported: true, vendors: vendorState, rotation: ROTATION });
    }
    if (url.endsWith("/api/scrape/stop")) {
      const run = runs.find((r) => r.id === JSON.parse(options.body).run_id);
      if (run) run.status = "stopped";
      return jsonResponse({ ok: true, status: "stopped" });
    }
    if (url.endsWith("/api/scrape")) {
      return scrapeOk
        ? jsonResponse({ ok: true, run_id: "abc123" })
        : jsonResponse({ ok: false, error: "A scrape is already running." }, false, 409);
    }
    if (url.endsWith("/api/jobs/open-urls")) {
      const { urls } = JSON.parse(options.body);
      return jsonResponse({ ok: true, opened: urls.length });
    }
    if (url.endsWith("/api/open/stop")) {
      const { vendor } = JSON.parse(options.body);
      const entry = vendorState.find((v) => v.slug === vendor);
      if (stopOk && entry) entry.open_running = false;
      return stopOk
        ? jsonResponse({ ok: true, status: "stopped", vendor: entry?.label || vendor })
        : jsonResponse({ ok: false, error: "No active browser session for this vendor." }, false, 400);
    }
    if (url.endsWith("/api/open")) {
      const { vendor } = JSON.parse(options.body);
      const entry = vendorState.find((v) => v.slug === vendor);
      if (openOk && entry) entry.open_running = true;
      return openOk
        ? jsonResponse({ ok: true, status: "started", vendor: entry?.label || vendor })
        : jsonResponse({ ok: false, error: "Unknown vendor." }, false, 400);
    }
    if (url.includes("/api/jobs")) {
      return jobsOk
        ? jsonResponse({ ok: true, vendor: "Judge Group", jobs: JOBS })
        : jsonResponse({ ok: false, error: "Unknown vendor." }, false, 400);
    }
    return jsonResponse({});
  });
  global.fetch = fetchMock;
  return { fetchMock, calls, vendorState };
}

async function renderLoaded(options) {
  const mocks = installFetchMock(options);
  render(<App />);
  await screen.findAllByRole("button", { name: "Jobs" });
  const table = screen.getByRole("table");
  return { table, ...mocks };
}

function rowFor(table, label) {
  return within(table).getByText(label).closest("tr");
}

describe("App", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("stops the active scrape and enables starting another run", async () => {
    const { fetchMock } = await renderLoaded({ runs: [{ id: "active-123", kind: "all", status: "running", steps: [] }] });
    const user = userEvent.setup();
    const stop = screen.getByRole("button", { name: "Stop Scrape" });
    await waitFor(() => expect(stop).toBeEnabled());
    expect(screen.getByRole("button", { name: "Scrape All 15" })).toBeDisabled();
    await user.click(stop);
    const call = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/scrape/stop"));
    expect(JSON.parse(call[1].body)).toEqual({ run_id: "active-123" });
    await waitFor(() => expect(stop).toBeDisabled());
    expect(screen.getByRole("button", { name: "Scrape All 15" })).toBeEnabled();
  });

  it("disables Stop Scrape when idle", async () => {
    await renderLoaded();
    expect(screen.getByRole("button", { name: "Stop Scrape" })).toBeDisabled();
  });

  it("shows a disabled Stopping button while cancellation is pending", async () => {
    await renderLoaded({ runs: [{ id: "pending", status: "stopping", steps: [] }] });
    expect(await screen.findByRole("button", { name: "Stopping…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Scrape All 15" })).toBeDisabled();
  });

  it("renders the header and loads vendor data from the API", async () => {
    const { table } = await renderLoaded();
    expect(screen.getByText("Job Portal Control")).toBeInTheDocument();
    expect(within(table).getByText("TEKsystems")).toBeInTheDocument();
    expect(within(table).getByText("Judge Group")).toBeInTheDocument();
    expect(within(table).getByText("Beacon Hill")).toBeInTheDocument();
  });

  it("does not render a 'Scrape Today's 2' button", async () => {
    await renderLoaded();
    expect(screen.queryByRole("button", { name: /Scrape Today's 2/i })).not.toBeInTheDocument();
  });

  it("does not render a 'Latest Output' column", async () => {
    await renderLoaded();
    expect(screen.queryByText(/Latest Output/i)).not.toBeInTheDocument();
  });

  it("keeps the scrape-all and scrape-checked buttons but removes the two-only scrape shortcut", async () => {
    await renderLoaded();
    expect(screen.getByRole("button", { name: "Scrape All 15" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Scrape Checked" })).toBeInTheDocument();
  });

  it("does not render a Weekday Rotation section", async () => {
    await renderLoaded();
    expect(screen.queryByText(/Weekday Rotation/i)).not.toBeInTheDocument();
  });

  it("does not render an Open Jobs panel", async () => {
    await renderLoaded();
    expect(screen.queryByText(/Open Jobs/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Open Selected/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Open Today's 2/i })).not.toBeInTheDocument();
  });

  it("pre-selects today's active vendors for the checked-scrape action", async () => {
    const { table } = await renderLoaded();
    expect(within(rowFor(table, "Judge Group")).getByRole("checkbox")).toBeChecked();
    expect(within(rowFor(table, "Beacon Hill")).getByRole("checkbox")).toBeChecked();
    expect(within(rowFor(table, "TEKsystems")).getByRole("checkbox")).not.toBeChecked();
  });

  it("lets the user uncheck all portals without the selection snapping back", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();

    await user.click(within(rowFor(table, "Judge Group")).getByRole("checkbox"));
    await user.click(within(rowFor(table, "Beacon Hill")).getByRole("checkbox"));

    expect(within(rowFor(table, "Judge Group")).getByRole("checkbox")).not.toBeChecked();
    expect(within(rowFor(table, "Beacon Hill")).getByRole("checkbox")).not.toBeChecked();
  });

  it("checking the header checkbox selects every portal, unchecking clears all", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();

    const headerCheckbox = within(table).getAllByRole("checkbox")[0];
    await user.click(headerCheckbox);
    for (const v of VENDORS) {
      expect(within(rowFor(table, v.label)).getByRole("checkbox")).toBeChecked();
    }

    await user.click(headerCheckbox);
    for (const v of VENDORS) {
      expect(within(rowFor(table, v.label)).getByRole("checkbox")).not.toBeChecked();
    }
  });

  it("sends a scrape request with mode 'all' when 'Scrape All 15' is clicked", async () => {
    const { fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Scrape All 15" }));

    await waitFor(() => {
      const scrapeCall = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/scrape"));
      expect(scrapeCall).toBeTruthy();
      const body = JSON.parse(scrapeCall[1].body);
      expect(body.mode).toBe("all");
    });
  });

  it("sends a scrape request with the checked vendors when 'Scrape Checked' is clicked", async () => {
    const { fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Scrape Checked" }));

    await waitFor(() => {
      const scrapeCall = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/scrape"));
      expect(scrapeCall).toBeTruthy();
      const body = JSON.parse(scrapeCall[1].body);
      expect(body.mode).toBe("selected");
      expect([...body.vendors].sort()).toEqual(["beaconhill", "judgegroup"]);
    });
  });

  it("shows an error message in the log panel when the API call fails", async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error("Failed to fetch")));
    render(<App />);
    expect(await screen.findByText(/Error: Failed to fetch/i)).toBeInTheDocument();
  });

  it("surfaces a scrape conflict error without crashing", async () => {
    await renderLoaded({ scrapeOk: false });
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Scrape All 15" }));

    expect(await screen.findByText(/A scrape is already running\./i)).toBeInTheDocument();
  });

  it("lets the user type multi-word keywords and start a new line without the text snapping back", async () => {
    await renderLoaded();
    const user = userEvent.setup();

    const textarea = screen.getByLabelText("Keywords");
    await user.click(textarea);
    await user.keyboard(" senior ");
    expect(textarea).toHaveValue("java developer\nspring boot developer senior ");

    await user.keyboard("{Enter}");
    expect(textarea).toHaveValue("java developer\nspring boot developer senior \n");

    await user.keyboard("qa engineer");
    expect(textarea).toHaveValue("java developer\nspring boot developer senior \nqa engineer");
  });

  it("saves normalized keywords (trimmed, blank lines dropped) only on Save Controls", async () => {
    const { fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    const textarea = screen.getByLabelText("Keywords");
    await user.click(textarea);
    await user.keyboard("{Enter}{Enter}qa engineer  ");

    await user.click(screen.getByRole("button", { name: "Save Controls" }));

    await waitFor(() => {
      const configCall = fetchMock.mock.calls.find(
        ([url, options]) => url.endsWith("/api/config") && options?.method === "POST"
      );
      expect(configCall).toBeTruthy();
      const body = JSON.parse(configCall[1].body);
      expect(body.keywords).toEqual(["java developer", "spring boot developer", "qa engineer"]);
    });
  });

  it("renders a separate Ignore Titles box pre-filled from config", async () => {
    await renderLoaded();
    const textarea = screen.getByLabelText("Ignore Titles");
    expect(textarea).toHaveValue("junior");
  });

  it("saves normalized ignore titles alongside keywords on Save Controls", async () => {
    const { fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    const textarea = screen.getByLabelText("Ignore Titles");
    await user.click(textarea);
    await user.keyboard("{Enter}entry level  ");

    await user.click(screen.getByRole("button", { name: "Save Controls" }));

    await waitFor(() => {
      const configCall = fetchMock.mock.calls.find(
        ([url, options]) => url.endsWith("/api/config") && options?.method === "POST"
      );
      expect(configCall).toBeTruthy();
      const body = JSON.parse(configCall[1].body);
      expect(body.ignore_titles).toEqual(["junior", "entry level"]);
    });
  });

  it("does not render a row-level Open button in Controls", async () => {
    const { table } = await renderLoaded();
    const row = rowFor(table, "Judge Group");
    expect(within(row).queryByRole("button", { name: "Open" })).not.toBeInTheDocument();
  });

  it("shows a Stop button while a vendor's browser session is running and can stop it", async () => {
    const { table, fetchMock, vendorState } = await renderLoaded();
    const row = rowFor(table, "Judge Group");
    expect(within(row).queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();

    const entry = vendorState.find((v) => v.slug === "judgegroup");
    entry.open_running = true;
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    const stopBtn = await within(row).findByRole("button", { name: "Stop" });
    await user.click(stopBtn);

    await waitFor(() => {
      const stopCall = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/open/stop"));
      expect(stopCall).toBeTruthy();
      expect(JSON.parse(stopCall[1].body).vendor).toBe("judgegroup");
    });
    await waitFor(() => {
      expect(within(row).queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
    });
  });

  it("opens the jobs sidebar with titles for the selected vendor and can close it", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));

    expect(await screen.findByText("Senior Full Stack Engineer, Java, AWS, React")).toBeInTheDocument();
    expect(screen.getByText("Backend Java Developer")).toBeInTheDocument();
    expect(screen.getByText("2 jobs")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /close jobs panel/i }));
    await waitFor(() => {
      expect(document.querySelector(".jobs-panel.open")).not.toBeInTheDocument();
    });
  });

  it("expands a job title to reveal its description and collapses it again", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));

    const titleButton = await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");
    expect(screen.queryByText(/senior full stack engineer with Java and AWS experience/i)).not.toBeInTheDocument();

    await user.click(titleButton);
    expect(await screen.findByText(/senior full stack engineer with Java and AWS experience/i)).toBeInTheDocument();

    await user.click(titleButton);
    expect(screen.queryByText(/senior full stack engineer with Java and AWS experience/i)).not.toBeInTheDocument();
  });

  it("disables the Jobs button for a vendor with zero latest jobs", async () => {
    const { table } = await renderLoaded();
    const row = rowFor(table, "TEKsystems");
    expect(within(row).getByRole("button", { name: "Jobs" })).toBeDisabled();
  });

  it("opens only the manually checked jobs when Open Selected is clicked", async () => {
    const { table, fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));

    await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");
    expect(screen.getByRole("button", { name: /Open Selected/i })).toBeDisabled();

    await user.click(screen.getByRole("checkbox", { name: /select senior full stack engineer/i }));
    const openSelectedBtn = screen.getByRole("button", { name: /Open Selected/i });
    expect(openSelectedBtn).not.toBeDisabled();
    expect(openSelectedBtn).toHaveTextContent("Open Selected (1)");

    await user.click(openSelectedBtn);

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/jobs/open-urls"));
      expect(call).toBeTruthy();
      expect(JSON.parse(call[1].body).urls).toEqual(["https://example.com/jobs/j1"]);
    });
  });

  it("sends every checked job to the opener, not just the first", async () => {
    const { table, fetchMock } = await renderLoaded();
    const user = userEvent.setup();

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));
    await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");

    await user.click(screen.getByRole("checkbox", { name: /select all/i }));
    await user.click(screen.getByRole("button", { name: /Open Selected/i }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => url.endsWith("/api/jobs/open-urls"));
      expect(call).toBeTruthy();
      expect(JSON.parse(call[1].body).urls.length).toBe(JOBS.length);
    });
  });

  it("selects and deselects all jobs via the select-all checkbox", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();
    vi.spyOn(window, "open").mockImplementation(() => {});

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));
    await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");

    await user.click(screen.getByRole("checkbox", { name: /select all/i }));
    expect(screen.getByRole("button", { name: /Open Selected/i })).toHaveTextContent("Open Selected (2)");
    expect(screen.getByRole("checkbox", { name: /select senior full stack engineer/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /select backend java developer/i })).toBeChecked();

    await user.click(screen.getByRole("checkbox", { name: /select all/i }));
    expect(screen.getByRole("button", { name: /Open Selected/i })).toHaveTextContent("Open Selected (0)");
  });

  it("clears job selections when the panel is closed", async () => {
    const { table } = await renderLoaded();
    const user = userEvent.setup();

    const row = rowFor(table, "Judge Group");
    await user.click(within(row).getByRole("button", { name: "Jobs" }));
    await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");

    await user.click(screen.getByRole("checkbox", { name: /select senior full stack engineer/i }));
    await user.click(screen.getByRole("button", { name: /close jobs panel/i }));

    await user.click(within(row).getByRole("button", { name: "Jobs" }));
    await screen.findByText("Senior Full Stack Engineer, Java, AWS, React");
    expect(screen.getByRole("checkbox", { name: /select senior full stack engineer/i })).not.toBeChecked();
  });
});

it("checks every portal with AI and continues after a portal fails", async () => {
  const { calls } = installFetchMock();
  const original = global.fetch;
  global.fetch = vi.fn((url, options = {}) => {
    if (url.endsWith('/api/jobs/ai-clean')) {
      calls.push({ url, options });
      const { vendor } = JSON.parse(options.body);
      return vendor === 'judgegroup'
        ? jsonResponse({ ok: false, error: 'Review unavailable' }, false, 500)
        : jsonResponse({ ok: true, jobs: [], reviewed_count: 2, removed_count: 1 });
    }
    return original(url, options);
  });
  render(<App />);
  const button = await screen.findByRole('button', { name: 'Check All Portals with AI' });
  await waitFor(() => expect(button).toBeEnabled());
  await userEvent.click(button);
  await screen.findByText(/AI check finished: 2\/3 portals, 4 jobs reviewed, 2 hidden/);
  expect(calls.filter(c => c.url.endsWith('/api/jobs/ai-clean')).map(c => JSON.parse(c.options.body).vendor))
    .toEqual(['teksystems', 'judgegroup', 'beaconhill']);
  expect(button).toBeEnabled();
});

it("offers All Days only for TEKsystems and submits its dedicated scrape mode", async () => {
  const { calls } = installFetchMock();
  render(<App />);
  const button = await screen.findByRole('button', { name: 'All Days' });
  expect(screen.getAllByRole('button', { name: 'All Days' })).toHaveLength(1);
  expect(button.closest('tr')).toHaveTextContent('TEKsystems');
  await userEvent.click(button);
  await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/scrape'))).toBe(true));
  const request = calls.find(c => c.url.endsWith('/api/scrape'));
  expect(JSON.parse(request.options.body).mode).toBe('teksystems_all_days');
});
