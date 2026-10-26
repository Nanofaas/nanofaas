"""Rendered chart invariants, including the default disabled deployment."""
import pathlib
import subprocess
import unittest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]

class LoggingDeploymentTest(unittest.TestCase):
    def render(self, enabled, **log_values):
        args = ['helm', 'template', 'logging-test', str(ROOT / 'deploy/helm/nanofaas')]
        if enabled:
            args += ['--set', 'observability.logs.enabled=true']
            log_values = {'loki.url': 'https://logs.example:8443/loki/api/v1/push', **log_values}
        for key, value in log_values.items():
            args += ['--set-string', f'observability.logs.{key}={value}']
        return list(yaml.safe_load_all(subprocess.check_output(args, text=True)))

    def test_disabled_creates_no_collector(self):
        docs = self.render(False)
        self.assertFalse(any(d and 'fluent-bit' in d['metadata']['name'] for d in docs))

    def test_enabled_bounded_persistent_agent_and_private_labels(self):
        docs = self.render(True)
        agent = next(d for d in docs if d and d['kind']=='DaemonSet' and 'fluent-bit' in d['metadata']['name'])
        pod = agent['spec']['template']['spec']
        self.assertFalse(pod['automountServiceAccountToken'])
        container = pod['containers'][0]
        self.assertEqual({'cpu':'100m', 'memory':'64Mi'}, container['resources']['limits'])
        self.assertFalse(container['securityContext']['privileged'])
        self.assertTrue(any(v.get('hostPath',{}).get('path')=='/var/lib/nanofaas/fluent-bit' for v in pod['volumes']))
        config = next(d for d in docs if d and d['kind']=='ConfigMap' and 'fluent-bit' in d['metadata']['name'])['data']['fluent-bit.conf']
        self.assertIn('storage.total_limit_size 256M',config)
        self.assertIn('Retry_Limit False',config)
        labels = next(line for line in config.splitlines() if line.strip().startswith('Labels'))
        self.assertNotIn('executionId', labels)
        self.assertNotIn('traceId', labels)
        self.assertIn('Host logs.example',config)
        self.assertIn('Port 8443',config)
        self.assertIn('tls On',config)

    def test_config_changes_roll_collectors(self):
        def pod_template(docs):
            return next(d['spec']['template'] for d in docs if d and d['kind']=='DaemonSet')

        original = pod_template(self.render(True))
        for key, value in [('loki.url', 'http://new-loki:3100/loki/api/v1/push'),
                           ('namespaces[0]', 'functions'), ('bufferSize', '128M'),
                           ('environment', 'production')]:
            with self.subTest(setting=key):
                self.assertNotEqual(original, pod_template(self.render(True, **{key: value})))

    def test_loki_endpoint_host_and_port(self):
        for url, host, port, tls in [
            ('http://logs.example/loki/api/v1/push', 'logs.example', '80', 'Off'),
            ('https://logs.example/loki/api/v1/push', 'logs.example', '443', 'On'),
            ('http://[fd00::1]/loki/api/v1/push', 'fd00::1', '80', 'Off'),
            ('https://[fd00::1]/loki/api/v1/push', 'fd00::1', '443', 'On'),
            ('https://[fd00::1]:8443/loki/api/v1/push', 'fd00::1', '8443', 'On'),
        ]:
            with self.subTest(url=url):
                docs = self.render(True, **{'loki.url': url})
                config = next(d['data']['fluent-bit.conf'] for d in docs
                              if d and d['kind']=='ConfigMap' and 'fluent-bit' in d['metadata']['name'])
                fields = dict(line.strip().split(None, 1) for line in config.splitlines()
                              if line.strip().startswith(('Host ', 'Port ', 'URI ', 'tls ')))
                self.assertEqual({'Host': host, 'Port': port, 'URI': '/loki/api/v1/push', 'tls': tls}, fields)
