#!/usr/bin/env python3
"""Free, conservative grouping and recoverable scheduling of push Canary builds."""
import datetime as dt
import difflib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

BRANCHES = ('main', '26.2', '26.1.2', '1.21.11')
SHA = re.compile(r'[0-9a-f]{40}')
BATCH = re.compile(r'[0-9a-f]{20}')
MAX_CANDIDATES = 32


def now():
    return dt.datetime.now(dt.timezone.utc)


def timestamp(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def safe_title(value):
    value = re.sub(r'[^A-Za-z0-9 .,:()/+_-]', ' ', str(value))
    return re.sub(r'\s+', ' ', value).strip()[:100] or 'Branch update'


def normal_title(value):
    value = re.sub(r'^backport\s+', '', value.lower())
    value = re.sub(r'\b(?:to|for|on|mc|minecraft)?\s*(?:26\.[123]|1\.21\.11)\b', '', value)
    return re.sub(r'[^a-z0-9]+', ' ', value).strip()


def validate_entries(entries):
    if not isinstance(entries, list) or not 1 <= len(entries) <= len(BRANCHES):
        raise ValueError('A build needs one to four branch commits')
    result, seen = [], set()
    for e in entries:
        if not isinstance(e, dict):
            raise ValueError('Invalid branch commit')
        branch, sha = e.get('branch'), e.get('sha')
        if branch not in BRANCHES or branch in seen or not isinstance(sha, str) or not SHA.fullmatch(sha):
            raise ValueError('Invalid or repeated branch, or non-exact commit')
        seen.add(branch)
        result.append({'branch': branch, 'sha': sha})
    return sorted(result, key=lambda e: BRANCHES.index(e['branch']))


def batch_id(entries):
    payload = json.dumps(validate_entries(entries), sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(payload.encode()).hexdigest()[:20]


def make_group(entries):
    return {'batch': batch_id(entries), 'entries': validate_entries(entries),
            'title': safe_title(entries[0].get('title', 'Branch update')),
            'created_at': now().isoformat()}


def related(a, b):
    if a['branch'] == b['branch']:
        return False
    if a.get('created_at') and b.get('created_at'):
        if abs((timestamp(a['created_at']) - timestamp(b['created_at'])).total_seconds()) > 600:
            return False
    if not a.get('patch') or not b.get('patch'):
        return False
    if a.get('patch_id') and a['patch_id'] == b.get('patch_id'):
        return True
    title = normal_title(a['title'])
    if not title or title != normal_title(b['title']):
        return False
    af, bf = set(a['files']), set(b['files'])
    if not af or len(af & bf) / len(af | bf) < 0.8:
        return False
    return difflib.SequenceMatcher(None, a['patch'].splitlines(), b['patch'].splitlines(), autojunk=True).ratio() >= 0.9


def group_entries(entries):
    groups = []
    for entry in entries:
        for group in groups:
            if all(related(entry, other) for other in group):
                group.append(entry)
                break
        else:
            groups.append([entry])
    return [make_group(group) for group in groups]


class GitHub:
    def __init__(self, repository, token):
        self.repository, self.token = repository, token

    def request(self, path, data=None, method=None):
        url = f'https://api.github.com/repos/{self.repository}/{path}'
        req = urllib.request.Request(url, method=method or ('POST' if data is not None else 'GET'),
                                     data=json.dumps(data).encode() if data is not None else None,
                                     headers={'Authorization': f'Bearer {self.token}',
                                              'Accept': 'application/vnd.github+json',
                                              'X-GitHub-Api-Version': '2022-11-28',
                                              'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f'GitHub API {method or "request"} {path.split("?")[0]}: HTTP {exc.code}') from None

    def pages(self, path, key=None):
        page = 1
        while True:
            sep = '&' if '?' in path else '?'
            result = self.request(f'{path}{sep}page={page}&per_page=100')
            items = result[key] if key else result
            yield from items
            if len(items) < 100:
                break
            page += 1

    def checkpoint(self):
        runs = self.request('actions/workflows/canary-coordinator.yml/runs?status=success&per_page=1')['workflow_runs']
        if not runs:
            return {}
        artifacts = list(self.pages(f'actions/runs/{runs[0]["id"]}/artifacts', 'artifacts'))
        artifact = next((a for a in artifacts if a['name'] == 'canary-coordinator-state' and not a['expired']), None)
        if not artifact:
            return {}

        # Artifact downloads redirect to signed storage URLs. Never forward the
        # repository token to that different host, or print the signed URL.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        url = f'https://api.github.com/repos/{self.repository}/actions/artifacts/{artifact["id"]}/zip'
        req = urllib.request.Request(url, headers={'Authorization': f'Bearer {self.token}'})
        try:
            urllib.request.build_opener(NoRedirect).open(req, timeout=45)
            raise RuntimeError('Artifact download did not redirect')
        except urllib.error.HTTPError as exc:
            if exc.code != 302:
                raise RuntimeError(f'Checkpoint download: HTTP {exc.code}') from None
            location = exc.headers['Location']
        if urllib.parse.urlparse(location).scheme != 'https':
            raise RuntimeError('Checkpoint download requires HTTPS')
        with urllib.request.urlopen(location, timeout=45) as response:
            blob = response.read(2_000_001)
        if len(blob) > 2_000_000:
            raise ValueError('Checkpoint too large')
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            state = json.loads(archive.read('state.json'))
        timestamp(state['watermark'])
        for entry in state['backlog']:
            validate_entries([entry])
            timestamp(entry['created_at'])
        return state


def run_title(group):
    return f'Canary: {safe_title(group["title"])} [batch:{group["batch"]}]'


def find_run(runs, batch):
    matches = [r for r in runs if r.get('display_title', '').endswith(f'[batch:{batch}]')]
    return min(matches, key=lambda r: r['id']) if matches else None


def receipt_group(receipt, branch, sha):
    try:
        group = json.loads(receipt['output']['text'])
        if (group['batch'] != receipt['external_id'] or group['batch'] != batch_id(group['entries'])
                or {'branch': branch, 'sha': sha} not in validate_entries(group['entries'])):
            return None
        timestamp(group['created_at'])
        return group
    except (KeyError, ValueError, TypeError, AttributeError):
        return None


def receipt_name(branch):
    return f'Canary scheduling / {branch}'


def schedule(api, group, runs, *, new, receipts=None):
    receipts = list(receipts or [])
    run = find_run(runs, group['batch'])
    if not run and not new and (now() - timestamp(group['created_at'])).total_seconds() < 300:
        return  # Allow a successful dispatch to become visible before retrying it.
    if new:
        for entry in group['entries']:
            receipts.append(api.request('check-runs', {
                'name': receipt_name(entry['branch']), 'head_sha': entry['sha'],
                'external_id': group['batch'], 'status': 'queued',
                'output': {'title': 'Waiting for grouped Canary scheduling',
                           'summary': 'This receipt tracks scheduling, not build success.',
                           'text': json.dumps(group, separators=(',', ':'))}}))
    if not run:
        api.request('actions/workflows/build-mod.yml/dispatches', {
            'ref': 'main', 'inputs': {'target': 'all', 'title': group['title'],
                                     'batch': group['batch'], 'commits': json.dumps(group['entries'])}})
        # Leave receipts queued until reconciliation observes the actual run.
        return
    for receipt in receipts:
        api.request(f'check-runs/{receipt["id"]}', {
            'status': 'completed', 'conclusion': 'success', 'details_url': run['html_url'],
            'output': {'title': 'Canary build scheduled',
                       'summary': 'Scheduling succeeded. Follow Details for the actual build result.',
                       'text': json.dumps(group, separators=(',', ':'))}}, method='PATCH')


def describe(api, entry):
    """Read data only; commit messages and patches are never executed."""
    result = dict(entry)
    commit = api.request(f'commits/{entry["sha"]}')
    result.update(title=(commit['commit']['message'].splitlines() or ['Branch update'])[0], files=[], patch='', patch_id=None)
    files = commit.get('files', [])
    # Merge diffs, binary patches, and truncated diffs cannot prove equivalence.
    if len(commit.get('parents', [])) != 1 or not files or len(files) >= 300:
        return result
    if any('patch' not in f for f in files):
        return result
    diff, changed = [], []
    for f in files:
        patch = f['patch']
        lines = patch.splitlines()
        changes = [s for s in lines if s.startswith(('+', '-'))]
        if len(changes) != f['changes']:
            return result
        name = f['filename']
        result['files'].append(name)
        previous = f.get('previous_filename', name)
        diff.append(f'diff --git a/{previous} b/{name}\n--- a/{previous}\n+++ b/{name}\n{patch}\n')
        changed.extend(re.sub(r'\s+', '', s) for s in changes)
    result['patch'] = '\n'.join(changed)
    if len(result['patch']) > 250_000 or len(changed) > 10_000:
        result['patch'] = ''
        return result
    patch_id = subprocess.run(['git', 'patch-id', '--stable'], input=''.join(diff),
                              text=True, capture_output=True, check=True).stdout.split()
    result['patch_id'] = patch_id[0] if patch_id else None
    return result


def collect(api, state=None):
    state = state if state is not None else {}
    watermark = state.get('watermark', '1970-01-01T00:00:00Z')
    scan_started = now().replace(microsecond=0).isoformat()
    pending, recovery, cache = [], {}, {}
    # Enumerate the event workflow rather than coordinator runs: GitHub may replace
    # a pending coordinator when a burst of pushes arrives. Its event still exists.
    # Unfiltered history avoids GitHub's 1,000-result cap on filtered run lists.
    # Runs are newest first; overlap the boundary second to include late arrivals.
    fresh = []
    for event in api.pages('actions/workflows/canary-push.yml/runs', 'workflow_runs'):
        if timestamp(event['created_at']) < timestamp(watermark):
            break
        if event.get('event', 'push') != 'push' or event.get('conclusion') == 'skipped':
            continue
        fresh.append(event)
    events = fresh + [dict(head_branch=e['branch'], head_sha=e['sha'], created_at=e['created_at'])
                      for e in state.get('backlog', [])]
    backlog = []
    for event in events:
        branch, sha = event['head_branch'], event['head_sha']
        key = (branch, sha)
        if branch not in BRANCHES or key in cache:
            continue
        cache[key] = True
        validate_entries([{'branch': branch, 'sha': sha}])
        checks = api.pages(f'commits/{sha}/check-runs?filter=latest', 'check_runs')
        valid = [(check, receipt_group(check, branch, sha)) for check in checks
                 if check['name'] == receipt_name(branch) and check.get('app', {}).get('slug') == 'github-actions']
        valid = [(check, group) for check, group in valid if group]
        if valid:
            unresolved = False
            for check, group in valid:
                if check['status'] != 'completed':
                    unresolved = True
                    item = recovery.setdefault(group['batch'], (group, []))
                    item[1].append(check)
            if unresolved:
                backlog.append({'branch': branch, 'sha': sha, 'created_at': event['created_at']})
            continue
        entry = {'branch': branch, 'sha': sha, 'created_at': event['created_at']}
        pending.append(entry)
        backlog.append(entry)
    # Oldest first avoids starving an earlier push if many updates arrive together.
    pending.sort(key=lambda e: (e['created_at'], BRANCHES.index(e['branch'])))
    state.update(watermark=scan_started, backlog=backlog)
    return pending[:MAX_CANDIDATES], list(recovery.values())


def coordinate(api):
    state = api.checkpoint()
    pending, recovery = collect(api, state)
    if not pending and not recovery:
        print('All recorded pushes have Canary scheduling receipts.')
        Path(os.environ['CANARY_STATE_FILE']).write_text(json.dumps(state))
        return
    runs = list(api.pages('actions/workflows/build-mod.yml/runs', 'workflow_runs'))
    for group, receipts in recovery:
        # A runner may have died halfway through creating receipts. Recreate only
        # missing members, preserving the original complete manifest.
        for entry in group['entries']:
            checks = list(api.pages(f'commits/{entry["sha"]}/check-runs?filter=latest', 'check_runs'))
            found = [r for r in checks if r['name'] == receipt_name(entry['branch'])
                     and r.get('app', {}).get('slug') == 'github-actions'
                     and receipt_group(r, entry['branch'], entry['sha']) == group]
            if not found:
                receipt = api.request('check-runs', {
                    'name': receipt_name(entry['branch']), 'head_sha': entry['sha'],
                    'external_id': group['batch'], 'status': 'queued',
                    'output': {'title': 'Recovering Canary scheduling',
                               'summary': 'Scheduling receipt recovered after interruption.',
                               'text': json.dumps(group, separators=(',', ':'))}})
                receipts.append(receipt)
            else:
                receipts.extend(r for r in found if r['id'] not in {x['id'] for x in receipts})
        schedule(api, group, runs, new=False, receipts=receipts)
    # Recovered manifests reserve every member, even a missing receipt discovered
    # as a fresh candidate earlier in this same collection pass.
    reserved = {(e['branch'], e['sha']) for group, _ in recovery for e in group['entries']}
    entries = []
    for entry in pending:
        if (entry['branch'], entry['sha']) in reserved:
            continue
        try:
            entries.append(describe(api, entry))
        except (RuntimeError, subprocess.SubprocessError):
            # Matching is optional; a deleted/unavailable diff still gets its own
            # exact-SHA build attempt and a visible result.
            entries.append(dict(entry, title='Branch update', files=[], patch='', patch_id=None))
    groups = group_entries(entries)
    for group in groups:
        schedule(api, group, runs, new=True)
    Path(os.environ['CANARY_STATE_FILE']).write_text(json.dumps(state))
    print(f'Scheduled {len(groups)} new groups; reconciled {len(recovery)} pending groups.')


def plan(api):
    event = os.environ['GITHUB_EVENT_NAME']
    batch = os.environ.get('BATCH', '')
    commits = os.environ.get('COMMITS', '')
    skip = False
    if event == 'pull_request':
        entries = [{'branch': os.environ['PR_BRANCH'], 'sha': os.environ['GITHUB_SHA']}]
    elif commits or batch:
        if not BATCH.fullmatch(batch):
            raise ValueError('Invalid batch ID')
        entries = validate_entries(json.loads(commits))
        if batch_id(entries) != batch:
            raise ValueError('Batch ID does not match exact commits')
        for entry in entries:
            branch = urllib.parse.quote(entry['branch'], safe='')
            compare = api.request(f'compare/{entry["sha"]}...{branch}')
            if compare['status'] not in ('ahead', 'identical'):
                raise ValueError('Commit is not in the selected maintained branch')
        runs = list(api.pages('actions/workflows/build-mod.yml/runs', 'workflow_runs'))
        owner = find_run(runs, batch)
        if owner is None:
            raise ValueError('Dispatched batch run is not yet visible; rerun this job')
        skip = str(owner['id']) != os.environ['GITHUB_RUN_ID']
    else:
        target = os.environ['TARGET']
        if target != 'all' and target not in BRANCHES:
            raise ValueError('Unsupported build target')
        selected = BRANCHES if target == 'all' else [target]
        entries = [{'branch': b, 'sha': api.request(f'git/ref/heads/{b}')["object"]["sha"]} for b in selected]
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        print('matrix=' + json.dumps({'include': entries}), file=output)
        print('skip=' + str(skip).lower(), file=output)


def main():
    api = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN'])
    command = sys.argv[1]
    if command == 'coordinate':
        coordinate(api)
    elif command == 'plan':
        plan(api)
    else:
        raise ValueError('Unknown Canary command')


if __name__ == '__main__':
    main()
