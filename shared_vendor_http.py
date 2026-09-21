"""HTTP sessions for the public Robert Half, Mitchell Martin and IG searches."""

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


def search_session() -> requests.Session:
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
    # POST is used only for Robert Half's read-only search servlet.
    retry = Retry(total=3, backoff_factor=0.5, status_forcelist=(429, 500, 502, 503, 504),
                  allowed_methods=frozenset({"GET", "POST"}))
    session.mount("https://", HTTPAdapter(max_retries=retry))
    return session
