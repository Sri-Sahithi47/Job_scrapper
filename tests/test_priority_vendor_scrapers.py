"""Regression tests for complete public vendor searches (no network required)."""
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import requests

from insightglobal_applying_script import insightglobal_scraper as ig
from mitchellmartin_applying_script import mitchellmartin_scraper as mm
from roberthalf_applying_script import roberthalf_scraper as rh
from shared_vendor_http import search_session
from shared_vendor_filters import parse_posted_date


def response(payload, headers=None):
    result = MagicMock()
    result.json.return_value = payload
    result.headers = headers or {}
    return result


def rh_page(ids, total):
    return response({'request_status': 'SUCCESS', 'found': str(total),
                     'jobs': [{'unique_job_number': str(i)} for i in ids]})


def ig_row(job_id, **fields):
    return {'requisitionId': job_id, 'jobTitle': 'Python Developer',
            'jobType': 'Contract', **fields}


def ig_page(ids, page, pages):
    return response({'jobs': [ig_row(i) for i in ids],
                     'pageMetadata': {'number': page, 'totalPages': pages,
                                      'totalElements': pages * 2, 'size': 2}})


class RobertHalfTests(unittest.TestCase):
    @patch.object(rh, 'search_session')
    def test_all_pages_even_when_server_returns_less_than_requested(self, factory):
        session = factory.return_value
        session.post.side_effect = [rh_page([1, 2], 7), rh_page([3, 4], 7),
                                    rh_page([5, 6], 7), rh_page([7], 7)]
        jobs = rh.fetch_jobs(['python'], 0, 25, 5)
        self.assertEqual(len(jobs), 7)
        self.assertEqual([c.kwargs['json']['pagenumber'] for c in session.post.call_args_list], [1, 2, 3, 4])

    @patch.object(rh, 'search_session')
    def test_cross_term_duplicates_do_not_stop_pagination(self, factory):
        factory.return_value.post.side_effect = [rh_page([1, 2], 2), rh_page([1, 2], 3), rh_page([3], 3)]
        self.assertEqual(len(rh.fetch_jobs(['python', 'backend'], 0, 2, 5)), 3)

    @patch.object(rh, 'search_session')
    def test_repeated_page_fails_instead_of_looping(self, factory):
        factory.return_value.post.side_effect = [rh_page([1], 3), rh_page([1], 3)]
        with self.assertRaisesRegex(ValueError, 'repeated'):
            rh.fetch_jobs(['python'], 0, 1, 5)

    @patch.object(rh, 'search_session')
    def test_api_error_is_not_reported_as_zero_jobs(self, factory):
        factory.return_value.post.return_value = response({'request_status': 'ERROR'})
        with self.assertRaises(ValueError):
            rh.fetch_jobs(['python'], 0, 25, 5)

    @patch.object(rh, 'search_session')
    def test_explicit_limit_warns(self, factory):
        factory.return_value.post.return_value = rh_page([1], 2)
        with patch('sys.stderr') as stderr:
            self.assertEqual(len(rh.fetch_jobs(['python'], 1, 1, 5)), 1)
            self.assertTrue(any('truncated' in str(c) for c in stderr.write.call_args_list))

    def test_numeric_salary_and_relative_url(self):
        job = rh.normalize({'jobtitle': 'Python Developer', 'payrate_min': 0,
                            'payrate_max': 75.5, 'job_detail_url': '/us/en/job/test'})
        self.assertEqual(job.salary, '0 - 75.5')
        self.assertEqual(job.job_url, 'https://www.roberthalf.com/us/en/job/test')

    def test_cli_defaults_to_all_pages(self):
        with patch('sys.argv', ['scraper']):
            self.assertEqual(rh.parse_args().max_pages, 0)


class MitchellMartinTests(unittest.TestCase):
    def test_wordpress_total_pages_overrides_short_response(self):
        session = MagicMock()
        session.get.side_effect = [response([{'id': 1}], {'X-WP-TotalPages': '2'}),
                                   response([{'id': 2}], {'X-WP-TotalPages': '2'})]
        self.assertEqual(len(mm.fetch_postings(session, 'python', 40, 5, 0)), 2)
        self.assertEqual(session.get.call_args.kwargs['params']['page'], 2)

    def test_repeated_page_is_failure(self):
        session = MagicMock()
        session.get.return_value = response([{'id': 1}], {'X-WP-TotalPages': '3'})
        with self.assertRaisesRegex(ValueError, 'repeated'):
            mm.fetch_postings(session, 'python', 40, 5, 0)

    def test_plural_locations_and_contract_to_perm(self):
        text = mm.rendered({'rendered': '<p>Title: Developer</p><p>Locations: New York, NY / Plano, TX</p><p>Employment Type: Contract to Perm</p><p>Pay Range: $70</p>'})
        self.assertEqual(mm.location_for({}, text), 'New York, NY / Plano, TX')
        self.assertEqual(mm.employment_for({}, text), 'Contract to Perm')
        self.assertTrue(mm.is_allowed_employment_type(mm.employment_for({}, text)))
        self.assertFalse(mm.is_allowed_employment_type('Direct Placement'))

    def test_us_states_are_not_mistaken_for_foreign_countries(self):
        for location in ['Indianapolis, Indiana', 'Albuquerque, New Mexico']:
            self.assertTrue(mm.is_usa_location(location), location)
        for location in ['Remote, India', 'Toronto, Canada', 'Mexico City, Mexico']:
            self.assertFalse(mm.is_usa_location(location), location)

    def test_taxonomy_location_fallback(self):
        item = {'_embedded': {'wp:term': [[{'taxonomy': 'location', 'name': 'New York'}]]}}
        self.assertEqual(mm.location_for(item, ''), 'New York')

    def test_split_labels_do_not_capture_location_in_description(self):
        text = ('Title: Cloud Engineer L ocation: Jersey City NJ E mployment Type: Contract '
                'Description Engage in a regional location, focusing on cloud environments.')
        self.assertEqual(mm.location_for({}, text), 'Jersey City NJ')
        self.assertEqual(mm.employment_for({}, text), 'Contract')

    def test_position_type_header_and_us_location(self):
        text = 'Location: in US. Position Type: Contract Compensation: $80/hr'
        self.assertEqual(mm.location_for({}, text), 'in US.')
        self.assertTrue(mm.is_usa_location(mm.location_for({}, text)))
        self.assertEqual(mm.employment_for({}, text), 'Contract')


class InsightGlobalTests(unittest.TestCase):
    def test_fractional_posting_dates_are_parsed(self):
        for fraction in ('1', '12', '123', '1234', '1234567'):
            date = parse_posted_date(f'2020-01-01T12:30:00.{fraction}Z')
            self.assertIsNotNone(date)
            self.assertEqual(date.year, 2020)
            self.assertEqual(date.microsecond, int((fraction + '000000')[:6]))

    @patch.object(ig, 'search_session')
    def test_consultant_board_and_pagination(self, factory):
        session = factory.return_value.__enter__.return_value
        session.get.side_effect = [ig_page(['a', 'b'], 1, 2), ig_page(['b', 'c'], 2, 2)]
        jobs = ig.fetch_term('python', 5)
        self.assertEqual([j['requisitionId'] for j in jobs], ['a', 'b', 'c'])
        self.assertEqual(session.get.call_args.args[0], 'https://insightglobal.com/all/jobs')
        params = session.get.call_args.kwargs['params']
        self.assertEqual(params['page'], 2)
        self.assertEqual(params['keyword'], 'python')
        self.assertEqual(params['sort'], 'postedDate,desc')

    @patch.object(ig, 'search_session')
    def test_http_404_is_failure_not_no_results(self, factory):
        session = factory.return_value.__enter__.return_value
        session.get.return_value.raise_for_status.side_effect = requests.HTTPError('404')
        with self.assertRaises(requests.HTTPError):
            ig.fetch_term('python', 5)

    @patch.object(ig, 'search_session')
    def test_empty_search_is_valid(self, factory):
        factory.return_value.__enter__.return_value.get.return_value = response({
            'jobs': [], 'pageMetadata': {'number': 1, 'totalPages': 0, 'totalElements': 0}})
        self.assertEqual(ig.fetch_term('nonexistent', 5), [])

    @patch.object(ig, 'search_session')
    def test_repeated_page_fails(self, factory):
        session = factory.return_value.__enter__.return_value
        session.get.side_effect = [ig_page(['a'], 1, 3), ig_page(['a'], 2, 3)]
        with self.assertRaisesRegex(ValueError, 'repeated'):
            ig.fetch_term('python', 5)

    @patch.object(ig, 'fetch_term')
    @patch.object(ig, 'search_session')
    def test_details_are_loaded_once_before_content_filters(self, factory, fetch):
        old = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
        fetch.return_value = [ig_row('a'), ig_row('b', postedDate=old)]
        session = factory.return_value.__enter__.return_value
        session.get.return_value = response({'description': 'Python developer. W2 only.'})
        self.assertEqual(ig.scrape(['python', 'backend'], 4, True, 5), [])
        session.get.assert_called_once_with('https://insightglobal.com/all/jobs/a/details', timeout=5)

    def test_normalizes_consultant_fields(self):
        job = ig.normalize(ig_row('abc', workAddress={'locality': 'Boston', 'administrativeArea': 'MA'},
                                  workRemote=True, payRate={'min': 65, 'currency': 'USD', 'type': 'Hourly'},
                                  description='<p>Python APIs</p>', postedDate='2026-09-18T12:00:00Z'), 'python')
        self.assertEqual(job.job_url, 'https://insightglobal.com/jobs/abc')
        self.assertEqual(job.location, 'Boston, MA (Remote)')
        self.assertEqual(job.salary, '65 USD Hourly')
        self.assertEqual(job.raw_text, 'Python APIs')


class HttpTests(unittest.TestCase):
    def test_search_requests_retry_transient_errors(self):
        with search_session() as session:
            retries = session.get_adapter('https://').max_retries
            self.assertIn(429, retries.status_forcelist)
            self.assertIn('POST', retries.allowed_methods)
            self.assertGreater(retries.total, 0)


if __name__ == '__main__':
    unittest.main()
