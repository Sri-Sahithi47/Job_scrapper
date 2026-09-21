#!/usr/bin/env python3
"""Standalone Insight Global scraper using the public consultant job-board API."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Iterable


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shared_vendor_filters import VendorJob, clean_text, extract_contact_info, filter_and_sort_jobs, load_phrases, is_within_posted_days, score_title, write_outputs


from shared_vendor_http import search_session

BASE_URL = "https://insightglobal.com"
API_URL = f"{BASE_URL}/all/jobs"
DEFAULT_SEARCH_TERMS = ["python", "full stack", "backend", "data engineer", "data engineering", "etl", "ai engineer", "machine learning", "llm", "rag"]


def fetch_term(term: str, timeout: int) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    page = 1
    with search_session() as session:
        while True:
            response = session.get(API_URL, params={"keyword": term, "page": page, "size": 50,
                "sort": "postedDate,desc", "filter.status": "active"}, timeout=timeout)
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or not isinstance(data.get("jobs"), list):
                raise ValueError(f"Insight Global: '{term}' page {page}: missing jobs list")
            metadata = data.get("pageMetadata") or {}
            total_pages = int(metadata["totalPages"])
            if int(metadata["number"]) != page:
                raise ValueError(f"Insight Global: '{term}' returned the wrong page")
            batch = data["jobs"]
            if not batch:
                if page <= total_pages and int(metadata.get("totalElements", 0)):
                    raise ValueError(f"Insight Global: '{term}' page {page} unexpectedly empty")
                break
            ids = {str(row["requisitionId"]) for row in batch}
            if not ids - seen:
                raise ValueError(f"Insight Global: '{term}' page {page} repeated; scrape is incomplete")
            for row in batch:
                if str(row["requisitionId"]) not in seen:
                    rows.append(row)
            seen.update(ids)
            if page >= total_pages:
                break
            page += 1
    print(f"Insight Global {term}: extracted {len(rows)} jobs across {page} pages")
    return rows


def normalize(row: dict[str, Any], search_term: str) -> VendorJob:
    title = clean_text(row.get("jobTitle"))
    raw_text = clean_text(row.get("description") or row.get("descriptionHtml"))
    rank, reasons = score_title(title, raw_text)
    address = row.get("workAddress") or {}
    location = ", ".join(clean_text(address.get(k)) for k in ("locality", "administrativeArea", "country") if address.get(k))
    if row.get("workRemote"):
        location = f"{location} (Remote)" if location else "Remote"
    pay = row.get("payRate") or {}
    salary = " - ".join(str(pay[k]) for k in ("min", "max") if pay.get(k) is not None)
    if salary:
        salary = " ".join(x for x in (salary, clean_text(pay.get("currency")), clean_text(pay.get("type"))) if x)
    job_id = clean_text(row.get("requisitionId"))
    if not job_id or not title:
        raise ValueError("Insight Global: job is missing its ID or title")
    url = f"{BASE_URL}/jobs/{job_id}"
    return VendorJob("Insight Global", search_term, rank, reasons, title, "", location,
        clean_text(row.get("jobType")), salary, clean_text(row.get("postedDate")), job_id,
        url, url, extract_contact_info(raw_text), raw_text[:900], raw_text)


def scrape(terms: Iterable[str], posted_within_days: int, exclude_disallowed_work: bool, timeout: int, ignore_titles: Iterable[str] = ()) -> list[VendorJob]:
    seen: set[str] = set()
    jobs: list[VendorJob] = []
    with search_session() as session:
        for term in terms:
            for row in fetch_term(term, timeout):
                key = clean_text(row.get("requisitionId"))
                if key in seen:
                    continue
                seen.add(key)
                # Search summaries lack descriptions; fetch details for every job in
                # the requested date window before applying content-based filters.
                if not is_within_posted_days(row.get("postedDate"), posted_within_days):
                    continue
                response = session.get(f"{API_URL}/{key}/details", timeout=timeout)
                response.raise_for_status()
                detail = response.json()
                if not isinstance(detail, dict) or not isinstance(detail.get("description"), str):
                    raise ValueError(f"Insight Global: missing description for {key}")
                jobs.append(normalize({**row, "description": detail["description"]}, term))
    print(f"Extracted {len(jobs)} unique Insight Global jobs before filtering")
    return filter_and_sort_jobs(jobs, posted_within_days, exclude_disallowed_work, ignore_titles)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape Insight Global jobs into CSV/JSON/Excel.")
    parser.add_argument("--term", action="append", dest="terms")
    parser.add_argument("--posted-within-days", type=int, default=4)
    parser.add_argument("--keep-w2-f2f-onsite-interview", action="store_true")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "output")
    parser.add_argument("--no-excel", action="store_true")
    parser.add_argument("--ignore-titles-file", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jobs = scrape(args.terms or DEFAULT_SEARCH_TERMS, args.posted_within_days, not args.keep_w2_f2f_onsite_interview, args.timeout, load_phrases(args.ignore_titles_file))
    write_outputs("insightglobal", jobs, args.out_dir, args.posted_within_days, args.no_excel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
