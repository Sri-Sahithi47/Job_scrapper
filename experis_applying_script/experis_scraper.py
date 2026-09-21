#!/usr/bin/env python3
"""Standalone Experis scraper using the public ManpowerGroup jobs API."""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shared_vendor_filters import VendorJob, clean_text, extract_contact_info, filter_and_sort_jobs, load_phrases, score_title, write_outputs

BASE_URL = "https://www.experis.com"
API_URL = f"{BASE_URL}/api/services/Jobs/searchjobs"
DEFAULT_SEARCH_TERMS = ["python", "full stack", "backend", "data engineer", "data engineering", "etl", "ai engineer", "machine learning", "llm", "rag"]


RETRYABLE_EXCEPTIONS = (
    requests.exceptions.ChunkedEncodingError,
    requests.exceptions.ConnectionError,
    requests.exceptions.ReadTimeout,
    requests.exceptions.Timeout,
)


def post_with_retries(session: requests.Session, payload: dict[str, Any], timeout: int, retries: int, retry_sleep: float) -> dict[str, Any] | None:
    for attempt in range(1, retries + 2):
        try:
            response = session.post(API_URL, json=payload, timeout=timeout)
            response.raise_for_status()
            return response.json()
        except RETRYABLE_EXCEPTIONS as exc:
            if attempt > retries:
                print(f"Experis request failed after {attempt} attempts: {exc}", file=sys.stderr)
                return None
            print(f"Experis request retry {attempt}/{retries}: {exc}", file=sys.stderr)
            time.sleep(retry_sleep * attempt)
        except requests.RequestException as exc:
            print(f"Experis request skipped: {exc}", file=sys.stderr)
            return None
        except ValueError as exc:
            print(f"Experis response was not valid JSON: {exc}", file=sys.stderr)
            return None
    return None


def fetch_term(term: str, timeout: int, limit: int, max_pages: int, retries: int, retry_sleep: float) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0", "Accept": "application/json", "Content-Type": "application/json", "Origin": BASE_URL, "Referer": f"{BASE_URL}/en/search?searchKeyword={term}"})
    for page in range(max_pages):
        payload = {"filter": {"offset": page * limit, "totalCount": 0, "limit": limit, "searchkeyword": term, "haslocation": False, "language": "en"}}
        data = post_with_retries(session, payload, timeout, retries, retry_sleep)
        if data is None:
            print(f"Experis {term}: stopped at page {page + 1}; keeping {len(rows)} jobs already extracted")
            break
        batch = data.get("jobsItems") or data.get("data", {}).get("jobsItems") or []
        rows.extend(batch)
        total = int(data.get("totalCount") or data.get("filters", {}).get("totalCount") or len(rows))
        print(f"Experis {term}: extracted {len(rows)} jobs")
        if not batch or (total and len(rows) >= total):
            break
    return rows


def normalize(row: dict[str, Any], search_term: str) -> VendorJob:
    title = clean_text(row.get("jobTitle"))
    raw_text = clean_text(row.get("publicDescription") or row.get("openingParagraph") or row.get("jobAdvertisementTeaser"))
    rank, reasons = score_title(title, raw_text)
    job_url = str(row.get("jobURL") or "")
    if job_url.startswith("/"):
        job_url = BASE_URL + job_url
    return VendorJob("Experis", search_term, rank, reasons, title, clean_text(row.get("domain")), clean_text(row.get("jobLocation")), clean_text(row.get("employmentType") or row.get("jobType")), "", str(row.get("publishfromDate") or ""), str(row.get("jobID") or row.get("jobItemID") or ""), job_url, job_url, extract_contact_info(raw_text), raw_text[:900], raw_text)


def scrape(terms: Iterable[str], posted_within_days: int, exclude_disallowed_work: bool, timeout: int, limit: int, max_pages: int, retries: int, retry_sleep: float, ignore_titles: Iterable[str] = ()) -> list[VendorJob]:
    seen: set[str] = set()
    jobs: list[VendorJob] = []
    for term in terms:
        for row in fetch_term(term, timeout, limit, max_pages, retries, retry_sleep):
            job = normalize(row, term)
            key = job.job_id or job.job_url
            if key in seen:
                continue
            seen.add(key)
            jobs.append(job)
    print(f"Extracted {len(jobs)} unique Experis jobs before filtering")
    return filter_and_sort_jobs(jobs, posted_within_days, exclude_disallowed_work, ignore_titles)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape Experis jobs into CSV/JSON/Excel.")
    parser.add_argument("--term", action="append", dest="terms")
    parser.add_argument("--posted-within-days", type=int, default=4)
    parser.add_argument("--keep-w2-f2f-onsite-interview", action="store_true")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--max-pages", type=int, default=3)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--retry-sleep", type=float, default=1.0)
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "output")
    parser.add_argument("--no-excel", action="store_true")
    parser.add_argument("--ignore-titles-file", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jobs = scrape(
        args.terms or DEFAULT_SEARCH_TERMS,
        args.posted_within_days,
        not args.keep_w2_f2f_onsite_interview,
        args.timeout,
        args.limit,
        args.max_pages,
        args.retries,
        args.retry_sleep,
        load_phrases(args.ignore_titles_file),
    )
    write_outputs("experis", jobs, args.out_dir, args.posted_within_days, args.no_excel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
