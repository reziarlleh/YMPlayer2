"""Check project documentation links and current release references without building Android."""
import argparse
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
CURRENT = ['README.md', 'docs/PROJECT_STATUS.md', 'docs/ROADMAP.md',
           'docs/FEATURE_INVENTORY.md', 'docs/BUG_REPORT.md',
           'docs/USER_GUIDE.md', 'docs/USER_GUIDE_EN.md']

def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding='utf-8').strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--github', action='store_true', help='Also compare GitHub latest and README blob')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    props = dict(line.split('=', 1) for line in (ROOT/'version.properties').read_text().splitlines() if '=' in line)
    version = props['baseVersion'] + ('beta' if props['channel'] == 'beta' else '') + '-build' + props['lastIssuedBuild']
    errors = []
    feeds = {}
    for name in ['manifest', 'stable']:
        data = json.loads((ROOT/f'update/{name}.json').read_text(encoding='utf-8-sig'))
        feeds[name] = data['versionName']
        if data['versionName'] != version or data['versionCode'] != int(props['lastIssuedBuild']):
            errors.append(f'update/{name}.json disagrees with version.properties')
    for name in CURRENT:
        if version not in (ROOT/name).read_text(encoding='utf-8-sig'):
            errors.append(f'{name} does not declare current release {version}')
    tracked = run('git', 'ls-files', '*.md').splitlines()
    files = {ROOT/p for p in tracked} | set((ROOT/'docs').glob('*.md'))
    broken = []
    links = 0
    for path in sorted(files):
        content = path.read_text(encoding='utf-8-sig')
        content = re.sub(r'^```.*?^```[^\n]*$', '', content, flags=re.M|re.S)
        targets = re.findall(r'!?\[[^\]\n]*\]\(([^)\n]+)\)', content)
        targets += re.findall(r'(?:src|href)=["\']([^"\']+)["\']', content)
        for raw in targets:
            target = raw.strip().split(' "', 1)[0].strip('<>')
            if not target or target.startswith('#') or urlsplit(target).scheme:
                continue
            target = unquote(target.split('#', 1)[0].split('?', 1)[0])
            links += 1
            if not (path.parent/target).exists():
                broken.append({'document': str(path.relative_to(ROOT)).replace('\\', '/'), 'target': raw})
    if broken:
        errors.append(f'{len(broken)} missing local documentation targets')
    github = None
    if args.github:
        github = json.loads(run('gh', 'release', 'view', '--json', 'tagName,isPrerelease,url'))
        if github['tagName'] != 'v'+version or github['isPrerelease'] != (props['channel']=='beta'):
            errors.append('GitHub latest disagrees with version.properties')
        github['readmeBlob'] = run('gh', 'api', 'repos/reziarlleh/YMPlayer2/contents/README.md', '--jq', '.sha')
        github['localReadmeBlob'] = run('git', 'hash-object', 'README.md')
        if github['readmeBlob'] != github['localReadmeBlob']:
            errors.append('GitHub README differs from the working copy (push first)')
    report = {'version': version, 'localFeeds': feeds, 'documents': len(files),
              'localLinks': links, 'brokenLinks': broken, 'github': github, 'errors': errors}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(bool(errors))

if __name__ == '__main__':
    main()
