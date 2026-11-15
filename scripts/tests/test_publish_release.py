import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("publish", Path(__file__).parents[1] / "publish-release.py")
publish = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publish)


class LocalReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / "input/app.apk"
        self.apk.parent.mkdir()
        self.apk.write_bytes(b"signed-apk-fixture")
        self.digest = publish.gitee.sha256(self.apk.read_bytes())
        self.head = "a" * 40
        self.args = argparse.Namespace(apk=self.apk, source_commit=None, notes_file=None,
                                       offline=False, gitee_token_file=None)

    def prepare(self):
        def run(command, **kwargs):
            if command[:2] == ["git", "show"]:
                return 'versionName = "1.3.29"\nversionCode = 33\n'
            if command[:2] == ["git", "merge-base"]:
                return subprocess.CompletedProcess(command, 0)
            if command[:2] == ["git", "log"]:
                return "Release notes"
            raise AssertionError(command)

        with patch.object(publish, "ROOT", self.root), patch.object(publish, "run", side_effect=run):
            with patch.object(publish, "verify_apk", return_value=("1.3.29", 33)):
                return publish.prepare(self.args, {}, self.head)

    def test_existing_local_apk_is_preserved_with_its_source_and_digest(self):
        metadata, apk, digest = self.prepare()
        self.assertEqual(self.apk.read_bytes(), apk.read_bytes())
        self.assertEqual(self.head, metadata["sourceCommit"])
        self.assertEqual(self.digest, digest)
        self.assertEqual(metadata, json.loads((apk.parent / "release.json").read_text()))

    def test_retry_uses_the_original_notes_and_never_runs_gradle(self):
        self.args.notes_file = self.root / "notes.txt"
        self.args.notes_file.write_text("Original release notes")
        metadata, apk, digest = self.prepare()
        self.args.apk = apk
        self.args.notes_file = None
        retried, same_apk, same_digest = self.prepare()
        self.assertEqual((metadata, apk, digest), (retried, same_apk, same_digest))

    def test_changed_apk_does_not_replace_a_prepared_release(self):
        self.prepare()
        self.apk.write_bytes(b"different-signed-apk")
        with self.assertRaisesRegex(publish.PublishError, "已有不同"):
            self.prepare()

    def test_apk_tampering_is_detected_when_resuming(self):
        _, apk, _ = self.prepare()
        apk.write_bytes(b"x" * apk.stat().st_size)
        self.args.apk = apk
        with self.assertRaisesRegex(publish.gitee.SyncError, "SHA-256"):
            self.prepare()

    def test_token_defaults_to_the_ignored_repository_file(self):
        token = self.root / "gitee_key.md"
        token.write_text("test-token\n")
        token.chmod(0o600)
        with patch.object(publish, "ROOT", self.root):
            self.assertEqual("test-token", publish.read_gitee_token(self.args, {}))
        self.assertEqual("override", publish.read_gitee_token(self.args, {"GITEE_TOKEN": "override"}))

    def test_explicit_missing_token_file_does_not_fall_back_to_another_account(self):
        self.args.gitee_token_file = self.root / "missing"
        with self.assertRaisesRegex(publish.PublishError, "不存在"):
            publish.read_gitee_token(self.args, {})

    def test_git_authentication_uses_temporary_askpass_instead_of_a_token_in_the_url(self):
        with publish.gitee_git_environment(dict(os.environ, GITEE_REPOSITORY="owner/repo"), "secret") as env:
            askpass = Path(env["GIT_ASKPASS"])
            self.assertNotIn("secret", askpass.read_text())
            for prompt, expected in [("Username for https://gitee.com", "owner\n"),
                                     ("Password for https://gitee.com", "secret\n")]:
                result = subprocess.check_output([askpass, prompt], env=env, text=True)
                self.assertEqual(expected, result)
        self.assertFalse(askpass.exists())

    def test_conflicting_release_tag_is_rejected(self):
        tag = "refs/tags/v1.3.29"
        with patch.object(publish, "run", return_value="b" * 40 + "\t" + tag):
            with self.assertRaisesRegex(publish.PublishError, "已指向其他提交"):
                publish.check_tag({"tagName": "v1.3.29", "sourceCommit": self.head})

    def test_annotated_tag_uses_its_commit_instead_of_the_tag_object(self):
        tag = "refs/tags/v1.3.29"
        refs = "b" * 40 + "\t" + tag + "\n" + self.head + "\t" + tag + "^{}"
        with patch.object(publish, "run", return_value=refs):
            publish.check_tag({"tagName": "v1.3.29", "sourceCommit": self.head})

    def test_gitee_divergence_is_detected_before_pushing_to_either_host(self):
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            if "ls-remote" in command:
                return "b" * 40 + "\trefs/heads/main"
            return subprocess.CompletedProcess(command, int(command[:3] == ["git", "merge-base", "--is-ancestor"]
                                                          and command[3] != "origin/main"))

        with patch.object(publish.gitee, "GiteeApi"), patch.object(publish, "run", side_effect=run):
            with self.assertRaisesRegex(publish.PublishError, "分叉"):
                publish.sync_source({"GITEE_REPOSITORY": "owner/repo"}, "secret", self.head)
        self.assertFalse(any("push" in command for command in commands))

    def test_source_sync_pushes_and_verifies_the_same_commit_on_both_hosts(self):
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            if "ls-remote" in command:
                return self.head + "\trefs/heads/main"
            return subprocess.CompletedProcess(command, 0)

        with patch.object(publish.gitee, "GiteeApi") as api, patch.object(publish, "run", side_effect=run):
            publish.sync_source({"GITEE_REPOSITORY": "owner/repo"}, "secret", self.head)
        pushes = [command for command in commands if "push" in command]
        self.assertEqual(2, len(pushes))
        self.assertTrue(all(command[-1] == self.head + ":refs/heads/main" for command in pushes))
        self.assertIn("origin", pushes[0])
        self.assertIn("https://gitee.com/owner/repo.git", pushes[1])
        self.assertFalse(any("--force" in command for command in commands))
        api.return_value.prepare_source_repository.assert_called_once_with(
            api.return_value.check_repository.return_value, self.head)

    def test_republishing_identical_github_apk_skips_the_upload(self):
        release = {"tagName": "v1.3.29", "assets": [
            {"name": self.apk.name, "size": self.apk.stat().st_size, "digest": "sha256:" + self.digest}
        ]}
        with patch.object(publish, "github_release", return_value=release), patch.object(publish, "run") as run:
            publish.upload_github("owner/repo", release, self.apk, self.digest)
        run.assert_not_called()

    def test_github_hash_mismatch_is_rejected_without_uploading(self):
        release = {"assets": [{"name": self.apk.name, "size": self.apk.stat().st_size,
                               "digest": "sha256:" + "0" * 64}]}
        with self.assertRaisesRegex(publish.PublishError, "哈希不同"):
            publish.github_asset_matches(release, self.apk, self.digest, "owner/repo")
