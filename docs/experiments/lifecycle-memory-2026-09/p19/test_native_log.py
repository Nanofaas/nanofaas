import unittest
import native_smoke


class NativeLogTest(unittest.TestCase):
    def test_clean_stop_is_not_an_application_failure(self):
        self.assertEqual([], native_smoke.native_errors('Started application\nShutdown complete\n'))

    def test_successful_docker_stop_cannot_hide_reflection_failure(self):
        for failure in ['MissingReflectionRegistrationError: shutdown',
                        'UnsupportedFeatureError: Record components',
                        "Failed to invoke custom destroy method 'shutdown'"]:
            self.assertEqual([failure], native_smoke.native_errors(failure))
