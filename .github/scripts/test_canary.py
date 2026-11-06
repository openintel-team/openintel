import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('canary', Path(__file__).with_name('canary.py'))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


def entry(branch='main', sha='a' * 40, title='Fix map markers', patch=None):
    return dict(branch=branch, sha=sha, title=title, files=['mod/Map.java'],
                patch=patch or '+ keep position\n- old position', patch_id=None)


class MatchingTests(unittest.TestCase):
    def test_versions_and_backport_prefix(self):
        a = entry(title='Fix map markers on 26.3')
        b = entry('26.2', 'b' * 40, 'Backport Fix map markers to 26.2')
        self.assertTrue(c.related(a, b))

    def test_unrelated_or_same_branch_separate(self):
        a = entry()
        self.assertFalse(c.related(a, entry(sha='b' * 40)))
        self.assertFalse(c.related(a, entry('26.2', title='Fix relay login')))
        b = entry('26.2')
        b['files'] = ['relay/server.js']
        self.assertFalse(c.related(a, b))

    def test_generic_titles_do_not_override_different_patches(self):
        self.assertFalse(c.related(entry(title='Fix bug', patch='+ apples'),
                                   entry('26.2', title='Fix bug', patch='+ oranges')))

    def test_identical_patch_id_and_different_titles(self):
        a, b = entry(), entry('26.2', title='Version-specific adaptation')
        a['patch_id'] = b['patch_id'] = '1' * 40
        self.assertTrue(c.related(a, b))

    def test_empty_patch_does_not_match(self):
        a, b = entry(), entry('26.2')
        a['patch'] = b['patch'] = ''
        self.assertFalse(c.related(a, b))

    def test_group_requires_every_pair_and_distinct_branches(self):
        from unittest.mock import patch
        a, b, d = entry(), entry('26.2', 'b'*40), entry('26.1.2', 'c'*40)
        with patch.object(c, 'related', side_effect=lambda x,y: {x['branch'],y['branch']} != {'main','26.1.2'}):
            groups = c.group_entries([a,b,d])
        self.assertEqual([len(g['entries']) for g in groups], [2,1])
        self.assertEqual(sum(len(g['entries']) for g in groups), 3)

    def test_batch_id_order_independent_and_changes_with_sha(self):
        a, b = entry(), entry('26.2', 'b'*40)
        self.assertEqual(c.batch_id([a,b]), c.batch_id([b,a]))
        self.assertNotEqual(c.batch_id([a]), c.batch_id([b]))

    def test_manifest_validation(self):
        a = entry()
        self.assertEqual(c.validate_entries([a])[0]['sha'], a['sha'])
        for value in [[], [entry(sha='main')], [entry(branch='evil')], [a,a]]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                c.validate_entries(value)

    def test_title_sanitized(self):
        self.assertEqual(c.safe_title('hello\n::error::oops'), 'hello ::error::oops')
        self.assertLessEqual(len(c.safe_title('x'*300)), 100)


class ApiTests(unittest.TestCase):
    def test_pagination(self):
        class Api(c.GitHub):
            def request(self, path, data=None, method=None):
                return {'workflow_runs': [1]*100 if 'page=1&' in path else [2]}
        self.assertEqual(len(list(Api('o/r', 'token').pages('x', 'workflow_runs'))), 101)

    def test_receipt_requires_matching_branch_sha_and_hash(self):
        group = c.make_group([entry()])
        receipt = {'external_id':group['batch'], 'output':{'text':json.dumps(group)}}
        self.assertEqual(c.receipt_group(receipt, 'main', 'a'*40), group)
        self.assertIsNone(c.receipt_group(receipt, '26.2', 'a'*40))
        receipt['external_id'] = 'bad'
        self.assertIsNone(c.receipt_group(receipt, 'main', 'a'*40))

    def test_duplicate_run_owner_is_lowest_id(self):
        runs=[dict(id=20,display_title='Update [batch:abc]'),
              dict(id=10,display_title='Update [batch:abc]'),
              dict(id=1,display_title='Other [batch:def]')]
        self.assertEqual(c.find_run(runs, 'abc')['id'], 10)

    def test_dispatch_failure_preserves_receipts_for_retry(self):
        class Api:
            def __init__(self): self.calls=[]
            def request(self,path,data=None,method=None):
                self.calls.append((path,data))
                if path.endswith('/dispatches'): raise RuntimeError('unavailable')
                return {'id':len(self.calls)}
        api=Api()
        group=c.make_group([entry()])
        with self.assertRaises(RuntimeError): c.schedule(api,group,[],new=True)
        self.assertEqual(api.calls[0][0], 'check-runs')
        self.assertEqual(api.calls[-1][0], 'actions/workflows/build-mod.yml/dispatches')

    def test_existing_dispatch_is_reconciled_without_dispatch(self):
        class Api:
            def __init__(self): self.calls=[]
            def request(self,path,data=None,method=None):
                self.calls.append((path,data))
                return {'id':7}
        api=Api()
        group=c.make_group([entry()])
        run={'id':1,'display_title':c.run_title(group),'html_url':'https://example.invalid/run'}
        c.schedule(api,group,[run],new=False,receipts=[{'id':7}])
        self.assertFalse(any(p.endswith('/dispatches') for p,d in api.calls))
        self.assertEqual(api.calls[-1][1]['status'],'completed')

class PlanTests(unittest.TestCase):
    def plan(self, env, api):
        import os
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'output'
            with patch.dict(os.environ, dict(GITHUB_OUTPUT=str(output), **env), clear=True):
                c.plan(api)
            return dict(line.split('=',1) for line in output.read_text().splitlines())

    def test_pr_uses_synthetic_merge_without_api_calls(self):
        output = self.plan(dict(GITHUB_EVENT_NAME='pull_request',PR_BRANCH='26.2',GITHUB_SHA='c'*40), None)
        self.assertEqual(json.loads(output['matrix'])['include'], [{'branch':'26.2','sha':'c'*40}])

    def test_manual_all_snapshots_all_four_tips(self):
        class Api:
            def request(self,path): return {'object':{'sha':'a'*40}}
        output = self.plan(dict(GITHUB_EVENT_NAME='workflow_dispatch',TARGET='all'), Api())
        self.assertEqual(len(json.loads(output['matrix'])['include']),4)

    def test_wrong_fingerprint_cannot_build(self):
        with self.assertRaises(ValueError):
            self.plan(dict(GITHUB_EVENT_NAME='workflow_dispatch',BATCH='b'*20,
                           COMMITS=json.dumps([entry()])),None)

    def test_foreign_commit_cannot_build(self):
        class Api:
            def request(self,path): return {'status':'diverged'}
        members=[entry()]
        with self.assertRaises(ValueError):
            self.plan(dict(GITHUB_EVENT_NAME='workflow_dispatch',BATCH=c.batch_id(members),
                           COMMITS=json.dumps(members)),Api())

    def test_duplicate_dispatch_skips_builds_but_first_owner_builds(self):
        group=c.make_group([entry()])
        class Api:
            def request(self,path): return {'status':'identical'}
            def pages(self,path,key): return [dict(id=10,display_title=c.run_title(group))]
        env=dict(GITHUB_EVENT_NAME='workflow_dispatch',BATCH=group['batch'],
                 COMMITS=json.dumps(group['entries']),GITHUB_RUN_ID='20')
        self.assertEqual(self.plan(env,Api())['skip'],'true')
        env['GITHUB_RUN_ID']='10'
        self.assertEqual(self.plan(env,Api())['skip'],'false')


class CollectionTests(unittest.TestCase):
    def test_replaced_pending_coordinator_does_not_lose_pushes(self):
        class Api:
            def pages(self,path,key):
                if path.startswith('actions/'):
                    return [dict(head_branch='main',head_sha='b'*40,created_at='2026-11-05T01:01:00Z'),
                            dict(head_branch='main',head_sha='a'*40,created_at='2026-11-05T01:00:00Z')]
                return []
        pending,recovery=c.collect(Api())
        self.assertEqual([e['sha'] for e in pending],['a'*40,'b'*40])
        self.assertEqual(recovery,[])

    def test_completed_receipts_prevent_rescheduling(self):
        group=c.make_group([entry()])
        class Api:
            def pages(self,path,key):
                if path.startswith('actions/'):
                    return [dict(head_branch='main',head_sha='a'*40,created_at='2026-11-05T01:00:00Z')]
                return [dict(name=c.receipt_name('main'), app={'slug':'github-actions'},
                             status='completed',external_id=group['batch'],output={'text':json.dumps(group)})]
        self.assertEqual(c.collect(Api()),([],[]))

    def test_delayed_backports_build_separately(self):
        a,b=entry(),entry('26.2','b'*40)
        a['created_at']='2026-11-05T01:00:00Z'
        b['created_at']='2026-11-05T01:11:00Z'
        self.assertFalse(c.related(a,b))


class RegressionTests(unittest.TestCase):
    def test_renamed_text_file_can_still_match_backports(self):
        class Api:
            def request(self,path):
                return dict(commit={'message':'Move map marker'},parents=[{}], files=[dict(
                    filename='mod/map/Marker.java', previous_filename='mod/Marker.java',
                    status='renamed',changes=2,patch='@@ -1 +1 @@\n-old\n+new')])
        self.assertTrue(c.describe(Api(),entry())['patch'])

    def test_checkpoint_avoids_rechecking_completed_history(self):
        class Api:
            def __init__(self): self.paths=[]
            def pages(self,path,key):
                self.paths.append(path)
                if path.startswith('actions/'):
                    return [dict(id=1,event='push',head_branch='main',head_sha='a'*40,
                                 created_at='2026-11-04T01:00:00Z')]
                raise AssertionError('Old completed commit should not be checked again')
        api=Api()
        state=dict(watermark='2026-11-05T01:00:00Z',backlog=[])
        self.assertEqual(c.collect(api,state),([],[]))
        self.assertNotIn('event=',api.paths[0])


if __name__ == '__main__':
    unittest.main()
