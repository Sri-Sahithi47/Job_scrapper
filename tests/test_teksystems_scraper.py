import unittest
from unittest.mock import MagicMock, patch

import requests

from teksystems_applying_script import teksystems_scraper as tek


def page(ids, total, query='java'):
    response = MagicMock()
    response.json.return_value = {'refineSearch': {
        'status': 200, 'totalHits': total, 'hits': len(ids), 'eid': {'query': query},
        'data': {'jobs': [{'jobId': str(i), 'title': 'Java Developer', 'type': 'Contractor'} for i in ids],
                 'locationData': {'aboveMaxRadius': True}}}}
    return response


class TEKsystemsSearchTests(unittest.TestCase):
    def test_payload_uses_keyword_and_unrestricted_distance(self):
        payload = tek.search_payload('java', 20)
        self.assertEqual(payload['keywords'], 'java')
        self.assertEqual(payload['from'], 20)
        self.assertEqual(payload['ddoKey'], 'refineSearch')
        self.assertEqual(payload['selected_fields'], {})
        self.assertTrue(payload['locationData']['aboveMaxRadius'])
        for field in ('latitude', 'longitude', 'place_id', 'placeVal'):
            self.assertEqual(payload['locationData'][field], '')

    def test_all_six_pages_match_screenshot_result_count(self):
        session = MagicMock()
        session.post.side_effect = [page(list(range(i, min(i + 10, 52))), 52) for i in range(0, 52, 10)]
        rows = tek.fetch_search_rows(session, 'java', 5, 0)
        self.assertEqual(len(rows), 52)
        self.assertEqual([call.kwargs['json']['from'] for call in session.post.call_args_list], [0, 10, 20, 30, 40, 50])
        session.get.assert_not_called()

    def test_server_page_size_cap_does_not_end_search(self):
        session = MagicMock()
        session.post.side_effect = [page([1, 2], 3), page([3], 3)]
        self.assertEqual(len(tek.fetch_search_rows(session, 'java', 5, 0)), 3)
        self.assertEqual(session.post.call_args.kwargs['json']['from'], 2)

    def test_repeated_page_fails(self):
        session = MagicMock()
        session.post.return_value = page([1], 3)
        with self.assertRaisesRegex(ValueError, 'repeated page'):
            tek.fetch_search_rows(session, 'java', 5, 0)

    def test_ignored_keyword_is_detected(self):
        session = MagicMock()
        session.post.return_value = page([1], 1, query='')
        with self.assertRaisesRegex(ValueError, 'instead of'):
            tek.fetch_search_rows(session, 'java', 5, 0)

    def test_unexpected_radius_is_detected(self):
        session = MagicMock()
        session.post.return_value = page([1], 1)
        session.post.return_value.json.return_value['refineSearch']['data']['locationData']['aboveMaxRadius'] = False
        with self.assertRaisesRegex(ValueError, 'distance limit'):
            tek.fetch_search_rows(session, 'java', 5, 0)

    def test_missing_search_payload_is_not_zero_jobs(self):
        session = MagicMock()
        session.post.return_value.json.return_value = {}
        with self.assertRaises(ValueError):
            tek.fetch_search_rows(session, 'java', 5, 0)

    def test_http_error_does_not_write_an_empty_success(self):
        session = MagicMock()
        session.post.return_value.raise_for_status.side_effect = requests.HTTPError('503')
        with patch.object(tek, 'make_session', return_value=session):
            with self.assertRaises(requests.HTTPError):
                tek.scrape_teksystems(['java'], 365, True, 5, 0)

    def test_valid_zero_results(self):
        session = MagicMock()
        session.post.return_value = page([], 0)
        self.assertEqual(tek.fetch_search_rows(session, 'java', 5, 0), [])

    @patch.object(tek, 'fetch_search_rows')
    @patch.object(tek, 'make_session')
    def test_filters_and_dedup_are_applied_after_retrieval(self, factory, fetch):
        fetch.return_value = [
            {'jobId': 'contract', 'title': 'Software Engineer', 'type': 'Contractor'},
            {'jobId': 'fulltime', 'title': 'Python/Java Developer', 'type': 'Full-time'},
        ]
        kept = tek.scrape_teksystems(['java', 'python'], 365, True, 5, 0)
        self.assertEqual([j.job_id for j in kept], ['contract'])
        kept = tek.scrape_teksystems(['java', 'python'], 365, False, 5, 0)
        self.assertEqual(len(kept), 2)


if __name__ == '__main__':
    unittest.main()
