"""Rendered chart invariants, including the default disabled deployment."""
import pathlib
import subprocess
import unittest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]

class LoggingDeploymentTest(unittest.TestCase):
    def render(self, enabled):
        args = ['helm', 'template', 'logging-test', str(ROOT / 'deploy/helm/nanofaas')]
        if enabled:
            args += ['--set', 'observability.logs.enabled=true', '--set',
                     'observability.logs.loki.url=https://logs.example:8443/loki/api/v1/push']
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
