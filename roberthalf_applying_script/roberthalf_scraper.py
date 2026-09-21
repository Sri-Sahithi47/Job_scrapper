#!/usr/bin/env python3
"""Standalone Robert Half scraper using the public job search servlet."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urljoin


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shared_vendor_filters import VendorJob, clean_text, extract_contact_info, filter_and_sort_jobs, load_phrases, score_title, write_outputs

from shared_vendor_http import search_session

BASE_URL = "https://www.roberthalf.com"
SEARCH_URL = f"{BASE_URL}/bin/jobSearchServlet"

DEFAULT_TERMS = [
    "python developer",
    "python engineer",
    "full stack developer",
    "full stack engineer",
    "backend developer",
    "software engineer python",
    "data engineer",
    "ai engineer",
    "machine learning engineer",
]


def load_terms(path: Path | None) -> list[str]:
    if path and path.exists():
        terms = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        if terms:
            return terms
    return DEFAULT_TERMS


def search_payload(keywords: str, page: int, page_size: int) -> dict[str, Any]:
    return {
        "country": "us",
        "keywords": keywords,
        "location": "",
        "distance": "50",
        "remote": "Any",
        "remoteText": "",
        "languagecodes": [],
        "source": ["Salesforce"],
        "city": "",
        "emptype": "",
        "lobid": "",
        "jobtype": "",
        "postedwithin": "0",
        "timetype": "",
        "pagesize": page_size,
        "pagenumber": page,
        "mode": "",
        "payratemin": 0,
        "payrateperiod": "",
        "includedoe": "",
    }


def fetch_jobs(terms: list[str], max_pages: int, page_size: int, timeout: int) -> list[dict[str, Any]]:
    if max_pages < 0 or page_size < 1:
        raise ValueError("max_pages must be nonnegative and page_size must be positive")
    session = search_session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Origin": BASE_URL,
        "Referer": f"{BASE_URL}/us/en/jobs",
    })
    seen_ids: set[str] = set()
    all_jobs: list[dict[str, Any]] = []
    for term in terms:
        term_count = 0
        page = 1
        term_seen: set[str] = set()
        received = 0
        while not max_pages or page <= max_pages:
            response = session.post(SEARCH_URL, json=search_payload(term, page, page_size), timeout=timeout)
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or data.get("request_status") != "SUCCESS":
                raise ValueError(f"Robert Half: '{term}' page {page}: unsuccessful search response")
            jobs = data.get("jobs")
            if not isinstance(jobs, list):
                raise ValueError(f"Robert Half: '{term}' page {page}: missing jobs list")
            total = int(data["found"]) if data.get("found") is not None else None
            if not jobs:
                if total is not None and received < total:
                    raise ValueError(f"Robert Half: '{term}' ended before all {total} jobs were retrieved")
                break
            page_ids = {str(job.get("unique_job_number") or job.get("sf_jo_number") or job.get("job_detail_url") or "") for job in jobs}
            if not page_ids - term_seen:
                raise ValueError(f"Robert Half: '{term}' page {page} repeated; scrape is incomplete")
            term_seen.update(page_ids)
            received += len(jobs)
            for job in jobs:
                job_id = str(job.get("unique_job_number") or job.get("sf_jo_number") or job.get("job_detail_url") or "")
                if job_id and job_id in seen_ids:
                    continue
                if job_id:
                    seen_ids.add(job_id)
                all_jobs.append(job)
                term_count += 1
            if total is not None and received >= total:
                break
            if total is None and len(jobs) < page_size:
                break
            if max_pages and page == max_pages:
                print(f"Robert Half: WARNING '{term}' truncated by --max-pages={max_pages}", file=sys.stderr)
            page += 1
        print(f"Robert Half: '{term}' -> {term_count} jobs")
    print(f"Robert Half: {len(all_jobs)} unique jobs before filtering")
    return all_jobs


def normalize(row: dict[str, Any]) -> VendorJob:
    title = clean_text(row.get("jobtitle"))
    raw_text = clean_text(" ".join(str(row.get(k) or "") for k in ("description", "skills")))
    rank, reasons = score_title(title, raw_text)
    location = clean_text(", ".join(x for x in [row.get("city"), row.get("stateprovince")] if x))
    salary = clean_text(" - ".join(str(x) for x in [row.get("payrate_min"), row.get("payrate_max")] if x is not None and x != ""))
    return VendorJob(
        "Robert Half",
        "jobSearchServlet",
        rank,
        reasons,
        title,
        clean_text(row.get("functional_role")),
        location,
        clean_text(row.get("emptype")),
        salary,
        str(row.get("date_posted") or ""),
        str(row.get("unique_job_number") or row.get("sf_jo_number") or ""),
        urljoin(BASE_URL, str(row.get("job_detail_url") or "")),
        urljoin(BASE_URL, str(row.get("job_detail_url") or "")),
        extract_contact_info(raw_text),
        raw_text[:900],
        raw_text,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape Robert Half jobs into CSV/JSON/Excel.")
    parser.add_argument("--posted-within-days", type=int, default=4)
    parser.add_argument("--keep-w2-f2f-onsite-interview", action="store_true")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "output")
    parser.add_argument("--no-excel", action="store_true")
    parser.add_argument("--terms-file", type=Path, default=None)
    parser.add_argument("--max-pages", type=int, default=0, help="Pages per term; 0 fetches all pages (default).")
    parser.add_argument("--page-size", type=int, default=25)
    parser.add_argument("--ignore-titles-file", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    terms = load_terms(args.terms_file)
    raw_jobs = fetch_jobs(terms, args.max_pages, args.page_size, args.timeout)
    jobs = [normalize(row) for row in raw_jobs]
    filtered = filter_and_sort_jobs(jobs, args.posted_within_days, not args.keep_w2_f2f_onsite_interview, load_phrases(args.ignore_titles_file))
    write_outputs("roberthalf", filtered, args.out_dir, args.posted_within_days, args.no_excel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
