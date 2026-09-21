#!/usr/bin/env python3
"""Standalone Mitchell Martin scraper using the WordPress job API."""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shared_search_page_scraper import DEFAULT_SEARCH_TERMS
from shared_vendor_filters import VendorJob, clean_text, extract_contact_info, filter_and_sort_jobs, load_phrases, score_title, write_outputs


from shared_vendor_http import search_session

API_URL = "https://www.mitchellmartin.com/wp-json/wp/v2/job"


def rendered(value: Any) -> str:
    return clean_text(value.get("rendered") if isinstance(value, dict) else value)


def field(text: str, label: str) -> str:
    # A few source postings split the first letter into a separate HTML span.
    text = re.sub(r"\bL\s+ocation", "Location", text, flags=re.I)
    text = re.sub(r"\bE\s+mployment", "Employment", text, flags=re.I)
    match = re.search(rf"\b{re.escape(label)}\b(?=\s|:)\s*:?\s*(.*?)(?=\s+(?:Title|Locations?|Employment Type|Position Type|Compensation|Pay Range|Job Summary|Description|Key Responsibilities|Qualifications|Core Technologies|Contact Information|Contact Authorization|Benefits|EEO Statement)\b|$)", text, re.I)
    return clean_text(match.group(1)) if match else ""


def embedded_terms(item: dict[str, Any], taxonomy: str) -> list[str]:
    names = []
    for group in ((item.get("_embedded") or {}).get("wp:term") or []):
        for term in group or []:
            if isinstance(term, dict) and term.get("taxonomy") == taxonomy:
                names.append(clean_text(term.get("name")))
    return [name for name in names if name]


def location_for(item: dict[str, Any], raw_text: str) -> str:
    content_location = field(raw_text, "Locations") or field(raw_text, "Location")
    if content_location:
        return content_location
    terms = embedded_terms(item, "location")
    if len(terms) >= 2:
        return f"{terms[0]} | {terms[1]}"
    return " | ".join(terms)


def employment_for(item: dict[str, Any], raw_text: str) -> str:
    return field(raw_text, "Employment Type") or field(raw_text, "Position Type") or " / ".join(embedded_terms(item, "position-type"))


def is_usa_location(location: str) -> bool:
    text = clean_text(location).lower()
    if not text:
        return False
    if re.search(r"\b(?:india|canada|brazil|philippines)\b|(?<!new )\bmexico\b", text):
        return False
    if "remote" == text or "remote" in text:
        return True
    state_names = (
        "alabama", "alaska", "arizona", "arkansas", "california", "colorado", "connecticut", "delaware",
        "florida", "georgia", "hawaii", "idaho", "illinois", "indiana", "iowa", "kansas", "kentucky",
        "louisiana", "maine", "maryland", "massachusetts", "michigan", "minnesota", "mississippi",
        "missouri", "montana", "nebraska", "nevada", "new hampshire", "new jersey", "new mexico",
        "new york", "north carolina", "north dakota", "ohio", "oklahoma", "oregon", "pennsylvania",
        "rhode island", "south carolina", "south dakota", "tennessee", "texas", "utah", "vermont",
        "virginia", "washington", "west virginia", "wisconsin", "wyoming",
    )
    state_pattern = r"\b(?:AL|AK|AZ|AR|CA|CO|CT|DC|DE|FL|GA|HI|IA|ID|IL|IN|KS|KY|LA|MA|MD|ME|MI|MN|MO|MS|MT|NC|ND|NE|NH|NJ|NM|NV|NY|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VA|VT|WA|WI|WV|WY)\b"
    return re.search(r"\b(?:united states|usa|us)\b", text) is not None or any(state in text for state in state_names) or re.search(state_pattern, location) is not None


def is_allowed_employment_type(employment_type: str) -> bool:
    text = clean_text(employment_type).lower()
    if not text:
        return False
    if "contract" in text:
        return True
    if any(marker in text for marker in ("direct placement", "permanent", "perm", "full-time", "full time")):
        return False
    return "contract" in text


def fetch_postings(session, term: str, per_page: int, timeout: int, sleep: float) -> list[dict[str, Any]]:
    postings = []
    seen: set[str] = set()
    page = 1
    while True:
        response = session.get(API_URL, params={"search": term, "per_page": per_page,
            "_embed": "wp:term", "page": page}, timeout=timeout)
        response.raise_for_status()
        rows = response.json()
        if not isinstance(rows, list):
            raise ValueError(f"Mitchell Martin: '{term}' page {page}: expected a jobs list")
        total_pages = int(response.headers.get("X-WP-TotalPages", 0))
        if not rows:
            if page <= total_pages:
                raise ValueError(f"Mitchell Martin: '{term}' page {page} unexpectedly empty")
            break
        ids = {str(row["id"]) for row in rows}
        if not ids - seen:
            raise ValueError(f"Mitchell Martin: '{term}' page {page} repeated; scrape is incomplete")
        seen.update(ids)
        postings.extend(rows)
        if (total_pages and page >= total_pages) or (not total_pages and len(rows) < per_page):
            break
        page += 1
        time.sleep(sleep)
    print(f"Mitchell Martin {term}: found {len(postings)} API jobs across {page} pages")
    return postings


def scrape_jobs(terms: list[str], timeout: int, max_jobs: int, sleep: float) -> list[VendorJob]:
    session = search_session()
    seen: set[str] = set()
    jobs: list[VendorJob] = []
    per_page = max(1, min(max_jobs, 100))
    for term in terms:
        postings = fetch_postings(session, term, per_page, timeout, sleep)
        for item in postings:
            job_id = clean_text(item.get("id"))
            if not job_id or job_id in seen:
                continue
            seen.add(job_id)
            title = rendered(item.get("title"))
            raw_text = rendered(item.get("content"))
            location = location_for(item, raw_text)
            employment_type = employment_for(item, raw_text)
            if not is_usa_location(location):
                print(f"  Skipped non-USA location: {title} | {location}")
                continue
            if not is_allowed_employment_type(employment_type):
                print(f"  Skipped employment type ({employment_type}): {title}")
                continue
            salary = field(raw_text, "Pay Range") or field(raw_text, "Compensation")
            posted = clean_text(item.get("date_gmt") or item.get("date"))
            rank, reasons = score_title(title, raw_text)
            jobs.append(VendorJob(
                "Mitchell Martin",
                term,
                rank,
                reasons,
                title,
                "",
                location,
                employment_type,
                salary,
                posted,
                job_id,
                clean_text(item.get("link")),
                clean_text(item.get("link")),
                extract_contact_info(raw_text),
                raw_text[:900],
                raw_text,
            ))
        time.sleep(sleep)
    print(f"Extracted {len(jobs)} unique Mitchell Martin jobs before filtering")
    return jobs


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape Mitchell Martin jobs into CSV/JSON/Excel.")
    parser.add_argument("--term", action="append", dest="terms")
    parser.add_argument("--posted-within-days", type=int, default=4)
    parser.add_argument("--keep-w2-f2f-onsite-interview", action="store_true")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--sleep", type=float, default=0.2)
    parser.add_argument("--page-size", "--max-jobs", dest="max_jobs", type=int, default=100,
                        help="API page size (1-100); all pages are fetched. --max-jobs is a legacy alias.")
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).resolve().parent / "output")
    parser.add_argument("--no-excel", action="store_true")
    parser.add_argument("--ignore-titles-file", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jobs = scrape_jobs(args.terms or DEFAULT_SEARCH_TERMS, args.timeout, args.max_jobs, args.sleep)
    filtered = filter_and_sort_jobs(jobs, args.posted_within_days, not args.keep_w2_f2f_onsite_interview, load_phrases(args.ignore_titles_file))
    write_outputs("mitchellmartin", filtered, args.out_dir, args.posted_within_days, args.no_excel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
