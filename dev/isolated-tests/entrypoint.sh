#!/usr/bin/env bash
set -euo pipefail
test -f /.dockerenv
test "${YXI_ISOLATED_TEST_RUN:-}" != ""
test "$HOME" = /sandbox/home
test "$CLAUDE_CONFIG_DIR" = /sandbox/home/.claude
test "$(ls /sys/class/net)" = lo
test "$#" = 1
test_class=$1
[[ "$test_class" =~ ^app\.yxi\.[A-Za-z0-9_.]+Test$ ]]
case "$test_class" in
    app.yxi.desktop.*) test_project=desktop;;
    app.yxi.agent.*|app.yxi.ssh.*) test_project=core;;
    *) echo 'Unsupported test package' >&2; exit 2;;
esac
mkdir -p /sandbox/tmp /sandbox/home/.claude /results
cd /workspace/android
set +e
xvfb-run -a bash gradlew -Pyxi.desktopOnly=true ":$test_project:test" --tests "$test_class" \
    --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    -Pkotlin.compiler.execution.strategy=in-process \
    -I /workspace/dev/isolated-tests/cache.init.gradle
test_result=$?
set -e
if test -d "$test_project/build/test-results/test"; then cp -R "$test_project/build/test-results/test" /results/xml; fi
if test -d "$test_project/build/reports/tests/test"; then cp -R "$test_project/build/reports/tests/test" /results/report; fi
test "$test_result" = 0
python3 - "$test_class" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
files=list(pathlib.Path('/results/xml').glob('TEST-*.xml'))
if not files:
    raise SystemExit('No test evidence produced')
tests=failures=skipped=0
for file in files:
    suite=ET.parse(file).getroot()
    tests+=int(suite.get('tests','0'))
    failures+=int(suite.get('failures','0'))+int(suite.get('errors','0'))
    skipped+=int(suite.get('skipped','0'))
if tests <= 0 or failures or skipped:
    raise SystemExit(f'tests={tests}, failures={failures}, skipped={skipped}')
print(f'{sys.argv[1]}: {tests} executed, no failures or skips')
PY
