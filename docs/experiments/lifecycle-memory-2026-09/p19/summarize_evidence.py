"""Extract structured supporting evidence from native test report formats."""
import gzip
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
from http_runner import write_json

work = pathlib.Path(sys.argv[1])
records = []
for label in ['configured-T1', 'configured-T2', 'catalog-cost']:
    for path in (work / 'verification' / label).glob('TEST-*.xml.gz'):
        suite = ET.fromstring(gzip.decompress(path.read_bytes()))
        output = suite.findtext('system-out') or ''
        for line in output.splitlines():
            if 'P07-HTTP-' in line:
                fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
                for key, value in list(fields.items()):
                    try:
                        fields[key] = float(value) if '.' in value else int(value)
                    except ValueError:
                        pass
                records.append(dict(kind='configured-LOCAL-profile', source=str(path.relative_to(work)), raw=line, values=fields,
                                    limitation='Existing P07 p50/p95 and shared-test-JVM allocation are not P19 performance acceptance; admission/refusal and drain are fresh correctness evidence'))
            elif label == 'catalog-cost' and line.strip():
                records.append(dict(kind='catalog-mutation-cost', source=str(path.relative_to(work)), raw=line))
write_json(work / 'supporting-profile-records.json', records)

groups = []
for path in (work / 'verification').glob('G*/result.json'):
    result = json.loads(path.read_text())
    groups.append(result)
assert sum(g['counts']['passed'] for g in groups) == 752
assert all(g['exit_code'] == 0 and g['counts']['failed'] == 0 and g['counts']['skipped'] == 0 for g in groups)
regressions = [case for group in groups for case in group['cases'] if re.search(r'\.R[1-8][A-Z]', case['classname'])]
assert set(re.search(r'\.(R[1-8])[A-Z]', c['classname'])[1] for c in regressions) == {f'R{i}' for i in range(1, 9)}
assert all(c['state'] == 'passed' for c in regressions)
write_json(work / 'regression-map.json', dict(groups=18, passed=752, failed=0, skipped=0, R1_R8=regressions))
print('G1-G18:', 752, 'passed; R1-R8:', len(regressions), 'fresh methods')
