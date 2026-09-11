import unittest
import sdk_profiles


class ProfileAccountingTest(unittest.TestCase):
    def test_census_does_not_parse_html_as_prometheus(self):
        self.assertEqual({},sdk_profiles.census([{'metrics':'<title> - Error</title>\n'}]))

    def test_census_reports_observed_peak_drain_and_absence_without_zero(self):
        samples=[{'owners':{'active':2},'metrics':'runtime_pending_callbacks 1\n'},
                 {'owners':{'active':0},'metrics':'runtime_pending_callbacks 0\n'}]
        self.assertEqual({'owners.active':{'peak':2,'drain':0},
                          'metrics.runtime_pending_callbacks':{'peak':1.0,'drain':0.0}},sdk_profiles.census(samples))

    def test_refusal_is_not_admitted_and_callback_ack_is_not_terminal_success(self):
        rows=[{'status':200,'body':{'id':'ok'},'callback':False},
              {'status':500,'body':{'error':{'code':'HANDLER_TIMEOUT'}},'callback':False},
              {'status':503,'body':{'error':{'code':'RUNTIME_HANDLER_SATURATED'}},'callback':False},
              {'status':202,'body':{},'callback':True}]
        self.assertEqual({'offered':4,'admitted':3,'refused':1,'terminal_successes':1,'terminal_errors':1,
                          'callback_acknowledgements':1},sdk_profiles.account(rows))
