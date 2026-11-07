#!/usr/bin/env bash

if ./gradlew :app:connectedDebugAndroidTest -PbenchmarkEmulator=true --stacktrace; then
  exit 0
fi

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

reports = list(Path('app/build/outputs/androidTest-results/connected').rglob('*.xml'))
print(f'Gerätetest-Berichte: {len(reports)}')
for report in reports:
    for case in ElementTree.parse(report).iter('testcase'):
        for failure in list(case.findall('failure')) + list(case.findall('error')):
            print(f"FEHLER {case.get('classname')}.{case.get('name')}: {failure.get('message')}")
            print((failure.text or '')[:2500])
PY
exit 1
