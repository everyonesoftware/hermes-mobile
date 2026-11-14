#!/usr/bin/env python3
"""Check that release versions follow the full Git ancestry count."""
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("release-version.sh")


class ReleaseVersionTest(unittest.TestCase):
    def test_version_increases_with_commits(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                subprocess.run(("git", *args), cwd=directory, check=True,
                               stdout=subprocess.DEVNULL)

            git("init", "-q")
            git("config", "user.name", "Release Test")
            git("config", "user.email", "release@example.invalid")
            for count in (1, 2):
                pathlib.Path(directory, "source.txt").write_text(str(count))
                git("add", "source.txt")
                git("commit", "-qm", f"Commit {count}")
                result = subprocess.check_output(("bash", str(SCRIPT)), cwd=directory, text=True)
                self.assertEqual(result.strip(), f"0.1.{count} {count}")

    def test_refuses_missing_history(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(("git", "init", "-q"), cwd=directory, check=True)
            result = subprocess.run(("bash", str(SCRIPT)), cwd=directory,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
