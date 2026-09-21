# TEKsystems Standalone Scraper

Scrapes TEKsystems jobs from `careers.teksystems.com` and writes filtered CSV, JSON, Excel, and daily grouped outputs. Defaults to jobs posted in the last 4 days.

Searches use the site's public `refineSearch` API with the supplied keywords,
follow every result page, and explicitly use unrestricted distance (the site's
“100+ miles” setting). No city, coordinates, or country facet is selected. The
initial HTML data is not used: it can contain unrelated local results with an
empty keyword and a default 50-mile radius.

Temporary HTTP failures are retried. Invalid responses or repeated pages fail
the run instead of saving an incomplete result as successful. The console shows
retrieval progress and the reasons for title/employment exclusions.

## Run

```bash
python3 teksystems_scraper.py
```

Useful options:

```bash
python3 teksystems_scraper.py --posted-within-days 4
python3 teksystems_scraper.py --term "data engineer" --term "python developer"
python3 teksystems_scraper.py --no-excel
```

## Open Jobs

```bash
python3 teksystems_open_jobs.py --limit 10
python3 teksystems_open_jobs.py --apply --limit 5
```

## Filter Intent

Keeps Python, full stack, backend/API, AI/ML, data engineering, ETL, cloud, and data science roles.

Title ranking is informational; `--ignore-titles-file` supplies title exclusions.
The default employment filter rejects W2-only, no-C2C/no-corp-to-corp, full-time,
permanent, direct hire, face-to-face interview, onsite interview, and local-only
signals. Thus an API result such as a full-time Python/Java Developer can be
retrieved successfully and still be excluded from the final output. The existing
`--keep-w2-f2f-onsite-interview` option disables these employment restrictions.

Contract-to-hire/C2H is allowed.
