"""Exercise the workflow's source-selection boundary against real Git history."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).parents[1] / 'workflows' / 'build-mod.yml'


def selection_script():
    workflow = WORKFLOW.read_text()
    step = re.search(
        r'^      - name: Select verified build commit\n(.*?)(?=^      - name:|\Z)',
        workflow, re.M | re.S)
    if step is None:
        raise AssertionError('Build job must verify source before executing checked-out code')
    return textwrap.dedent(step.group(1).split('        run: |\n', 1)[1])


class CheckoutTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_CONFIG_NOSYSTEM='1',
                        GIT_AUTHOR_NAME='takoyakifan', GIT_COMMITTER_NAME='takoyakifan',
                        GIT_AUTHOR_EMAIL='342837632+takoyakifan@users.noreply.github.com',
                        GIT_COMMITTER_EMAIL='342837632+takoyakifan@users.noreply.github.com')
        self.git('init', '-q')
        tree = self.git('mktree', input='')
        self.base = self.git('commit-tree', tree, '-m', 'Base')
        self.accepted = self.git('commit-tree', tree, '-p', self.base, '-m', 'Accepted')
        self.tip = self.git('commit-tree', tree, '-p', self.accepted, '-m', 'Tip')
        self.foreign = self.git('commit-tree', tree, '-p', self.base, '-m', 'Foreign')
        for branch in ('main', '26.2', '26.1.2', '1.21.11'):
            self.git('update-ref', f'refs/remotes/origin/{branch}', self.tip)
        self.git('checkout', '--detach', self.tip)

    def git(self, *args, input=None):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env,
                              input=input, text=True, capture_output=True,
                              check=True).stdout.strip()

    def select(self, sha=None, branch='main', event='workflow_dispatch'):
        env = dict(self.env, BUILD_SHA=sha or self.accepted, BUILD_BRANCH=branch,
                   BUILD_EVENT=event)
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', selection_script()],
                              cwd=self.root, env=env, text=True, capture_output=True)

    def assert_rejected(self, **kwargs):
        result = self.select(**kwargs)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.tip)

    def test_exact_ancestor_and_tip_on_every_maintained_branch(self):
        for branch in ('main', '26.2', '26.1.2', '1.21.11'):
            for sha in (self.accepted, self.tip):
                with self.subTest(branch=branch, sha=sha):
                    result = self.select(sha=sha, branch=branch)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(self.git('rev-parse', 'HEAD'), sha)

    def test_foreign_commit_is_rejected(self):
        self.assert_rejected(sha=self.foreign)

    def test_tag_cannot_impersonate_remote_branch(self):
        self.git('tag', 'main', self.foreign)
        self.assert_rejected(sha=self.foreign)

    def test_malformed_or_symbolic_commits_are_rejected(self):
        for sha in ('HEAD', self.accepted[:12], '-' + 'a' * 39,
                    'a' * 40 + '\n', 'a' * 40 + '; true', 'f' * 40):
            with self.subTest(sha=sha):
                self.assert_rejected(sha=sha)

    def test_unsupported_and_option_like_branches_are_rejected(self):
        for branch in ('feature', '--help', 'main\n', 'refs/heads/main',
                       'main; touch injected', '../main'):
            with self.subTest(branch=branch):
                self.assert_rejected(branch=branch)

    def test_pr_preserves_github_merge_checkout(self):
        result = self.select(sha=self.foreign, event='pull_request')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.tip)

    def test_unexpected_event_is_rejected(self):
        self.assert_rejected(event='repository_dispatch')

    def test_checkout_precedes_guard_without_dispatch_selected_ref(self):
        workflow = WORKFLOW.read_text().split('\n  build:\n', 1)[1]
        checkout = workflow.split('      - name: Select verified build commit', 1)[0]
        self.assertIn('fetch-depth: 0', checkout)
        # An implicit dispatch checkout can rewind origin/main to the event SHA
        # after fetching newer tips. A literal branch avoids that fallback.
        self.assertIn('ref: refs/heads/main', checkout)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", checkout)
        self.assertIn("if: github.event_name == 'pull_request'", checkout)
        self.assertNotIn('ref: ${{ matrix.sha }}', checkout)
        self.assertIn('persist-credentials: false', checkout)
        self.assertIn('BUILD_EVENT: ${{ github.event_name }}', workflow)
        self.assertIn('BUILD_BRANCH: ${{ matrix.branch }}', workflow)
        self.assertIn('BUILD_SHA: ${{ matrix.sha }}', workflow)


if __name__ == '__main__':
    unittest.main()
