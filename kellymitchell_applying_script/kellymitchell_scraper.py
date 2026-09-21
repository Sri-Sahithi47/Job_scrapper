#!/usr/bin/env python3
"""Standalone KellyMitchell scraper using the SourceFlow jobs API."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Iterable

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shared_vendor_filters import VendorJob, clean_text, extract_contact_info, filter_and_sort_jobs, load_phrases, parse_posted_date, score_title, write_outputs


BASE_URL = "https://www.careers.kellymitchell.com"
API_URL = f"{BASE_URL}/_sf/api/v1/jobs/search.json"
DEFAULT_SEARCH_TERMS = ["python", "full stack", "backend", "data engineer", "data engineering", "etl", "ai engineer", "machine learning", "llm", "rag"]


def fetch_term(term: str, timeout: int, jobs_per_page: int) -> list[dict[str, Any]]:
    offset = 0
    rows: list[dict[str, Any]] = []
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0", "Accept": "application/json", "Content-Type": "application/json", "Referer": f"{BASE_URL}/jobs"})
    while True:
        payload = {"job_search": {"query": term, "location": {"address": "", "radius": 50, "region": "US", "radius_units": "miles"}, "filters": {}, "commute_filter": {}, "offset": offset, "jobs_per_page": jobs_per_page}}
        response = session.post(API_URL, json=payload, timeout=timeout)
        response.raise_for_status()
        data = response.json()
        batch = data.get("results") or []
        rows.extend(batch)
        total = int(data.get("total_size") or len(rows))
        print(f"KellyMitchell {term}: extracted {len(rows)}/{total}")
        if not batch or len(rows) >= total:
            break
        offset += jobs_per_page
    return rows


def normalize(row: dict[str, Any], search_term: str) -> VendorJob:
    job = row.get("job") if isinstance(row.get("job"), dict) else row
    title = clean_text(job.get("title"))
    raw_text = clean_text(job.get("description"))
    rank, reasons = score_title(title, raw_text)
    categories = job.get("categories") if isinstance(job.get("categories"), list) else []
    category = ", ".join(clean_text((item.get("name") if isinstance(item, dict) else item)) for item in categories)
    employment = clean_text(job.get("employment_type") or job.get("type") or job.get("contract_type"))
    location = clean_text(job.get("location") or job.get("address") or job.get("location_name"))
    slug = clean_text(job.get("url_slug") or job.get("slug") or job.get("id"))
    job_url = f"{BASE_URL}/jobs/{slug}" if slug and not slug.startswith("http") else slug
    salary = clean_text(job.get("salary") or job.get("pay_rate") or "")
    posted_value = job.get("published_at") or job.get("created_at") or job.get("updated_at") or ""
    posted = parse_posted_date(posted_value)
    posted_date = posted.isoformat() if posted else str(posted_value)
    return VendorJob("KellyMitchell", search_term, rank, reasons, title, category, location, employment, salary, posted_date, str(job.get("id") or ""), job_url, job_url, extract_contact_info(raw_text), raw_text[:900], raw_text)


def scrape(terms: Iterable[str], posted_within_days: int, exclude_disallowed_work: bool, timeout: int, jobs_per_page: int, ignore_titles: Iterable[str] = ()) -> list[VendorJob]:
    seen: set[str] = set()
    jobs: list[VendorJob] = []
    for term in terms:
        for row in fetch_term(term, timeout, jobs_per_page):
            job = normalize(row, term)
            key = job.job_id or job.job_url
            if key in seen:
                continue
            seen.add(key)
            jobs.append(job)
    print(f"Extracted {len(jobs)} unique KellyMitchell jobs before filtering")
    return filter_and_sort_jobs(jobs, posted_within_days, exclude_disallowed_work, ignore_titles)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape KellyMitchell jobs into CSV/JSON/Excel.")
    parser.add_argument("--term", action="append", dest="terms")
    parser.add_argument("--posted-within-days", type=int, default=4)
    parser.add_argument("--keep-w2-f2f-onsite-interview", action="store_true")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--jobs-per-page", type=int, default=50)
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "output")
    parser.add_argument("--no-excel", action="store_true")
    parser.add_argument("--ignore-titles-file", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jobs = scrape(args.terms or DEFAULT_SEARCH_TERMS, args.posted_within_days, not args.keep_w2_f2f_onsite_interview, args.timeout, args.jobs_per_page, load_phrases(args.ignore_titles_file))
    write_outputs("kellymitchell", jobs, args.out_dir, args.posted_within_days, args.no_excel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
