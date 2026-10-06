"""Read-only FM OAuth probe. Never writes tokens, account IDs or favourite items."""
import argparse
import datetime
import json
from pathlib import Path
import urllib.error
import urllib.request

FM = 'https://radio.api.music.yandex.ru'
MUSIC = 'https://api.music.yandex.net'
CLIENT = 'YandexMusicWebRadio/1.0.0'
ROOT = Path(__file__).resolve().parents[1]
INVALID_TOKEN = 'YMPlayer2-intentionally-invalid-probe-token'
PATHS = ('/account/about', '/radio/v1/collection/stations/slugs',
         '/radio/v1/collection/tracks/ids')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Credentials must never reach a redirected origin or login page.
        return None


def fetch(origin, path, token=None):
    assert origin in (FM, MUSIC) and path.startswith('/')
    headers = {'Accept': 'application/json', 'User-Agent': 'YMPlayer2-RadioProbe/1',
               'X-Yandex-Music-Client': CLIENT}
    if token:
        headers['Authorization'] = 'OAuth ' + token
    request = urllib.request.Request(origin + path, headers=headers, method='GET')
    opener = urllib.request.build_opener(NoRedirect())  # no browser cookies
    try:
        response = opener.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return {'status': None, 'networkError': type(error).__name__}, None
    with response:
        body = response.read(2 * 1024 * 1024 + 1)
        info = {'status': response.code}
    if len(body) > 2 * 1024 * 1024:
        info['bodyTooLarge'] = True
        return info, None
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        info['json'] = False
        return info, None
    info['json'] = True
    info['shape'] = sorted(data.keys()) if isinstance(data, dict) else type(data).__name__
    return info, data


def identity(data):
    """Only explicit UID fields count; display names/Plus/HTTP 200 do not."""
    if not isinstance(data, dict):
        return None
    for key in ('uid', 'puid', 'passportUid', 'passport_uid'):
        value = data.get(key)
        if isinstance(value, (str, int)) and not isinstance(value, bool) and str(value).isdigit():
            return str(value)
    for key in ('result', 'account', 'user', 'data'):
        value = identity(data.get(key))
        if value:
            return value
    return None


def collection_count(data):
    if isinstance(data, list):
        return len(data)
    if isinstance(data, dict):
        for key in ('items', 'slugs', 'ids', 'stationSlugs', 'trackIds'):
            if isinstance(data.get(key), list):
                return len(data[key])
        for key in ('result', 'data'):
            if key in data:
                return collection_count(data[key])
    return None


def probe(token=None):
    report = {'dateUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'readOnly': True, 'browserCookiesUsed': False, 'newTokenRequested': False,
              'personalOAuthChecked': token is not None, 'controls': {}}
    raw = {}
    for label, credential in [('anonymous', None), ('invalidOAuth', INVALID_TOKEN)] + (
            [('existingMusicOAuth', token)] if token else []):
        rows = {}
        for path in PATHS:
            info, data = fetch(FM, path, credential)
            count = collection_count(data) if '/collection/' in path else None
            if count is not None and info['status'] == 200:
                info['itemCount'] = count
            rows[path] = info
            if label == 'existingMusicOAuth':
                raw[path] = data
        report['controls'][label] = rows
    if token:
        status, data = fetch(MUSIC, '/account/status', token)
        music_uid = identity(data)
        fm_uid = identity(raw.get('/account/about'))
        report['musicAccount'] = status | {'uidPresent': music_uid is not None}
        report['fmAccountUidPresent'] = fm_uid is not None
        report['accountUidMatches'] = (music_uid == fm_uid) if music_uid and fm_uid else None
        success = report['controls']['existingMusicOAuth']
        stations = '/radio/v1/collection/stations/slugs'
        protected = all(report['controls'][label][stations]['status'] in (400, 401, 403)
                        for label in ('anonymous', 'invalidOAuth'))
        read_ok = success[stations]['status'] == 200 and 'itemCount' in success[stations]
        report['favouriteStationsReadableWithOAuth'] = bool(protected and read_ok)
        report['existingTokenVerifiedForFmCollection'] = bool(
            status['status'] == 200 and music_uid and protected and read_ok)
        report['personalAccountIdentityVerified'] = bool(
            report['existingTokenVerifiedForFmCollection'] and report['accountUidMatches'])
    else:
        report['existingTokenVerifiedForFmCollection'] = False
        report['personalAccountIdentityVerified'] = False
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--token-file', type=Path, help='Private file containing existing access token')
    group.add_argument('--anonymous-only', action='store_true', help='Only anonymous and invalid-token controls')
    parser.add_argument('--output', type=Path, default=ROOT / '.local-build/radio-research/auth-probe.json')
    args = parser.parse_args()
    token = None
    if args.token_file:
        try:
            token = args.token_file.read_text(encoding='utf-8-sig').strip()
        except OSError:
            parser.error('Cannot read private token file')
        if not token or len(token) > 4096 or any(not 33 <= ord(c) <= 126 for c in token):
            parser.error('Token must be one nonempty printable ASCII value without whitespace')
    report = probe(token)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if args.anonymous_only or report['personalAccountIdentityVerified'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
