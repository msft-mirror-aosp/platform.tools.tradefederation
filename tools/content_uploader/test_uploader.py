# Copyright 2025 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Unit tests for content_uploader."""

import logging
import os
import tempfile
import unittest

from uploader import Uploader
from uploader import CasInfo
from uploader import ArtifactConfig
import cas_metrics_pb2  # type: ignore

class ContentUploaderTest(unittest.TestCase):
    """A unit test class for content uploader."""

    @classmethod
    def setUpClass(cls):
        # Create a temporary directory for log files
        cls.log_dir = tempfile.TemporaryDirectory()
        cls.log_file_path = os.path.join(cls.log_dir.name, 'test.log')

        # Configure logging
        logging.basicConfig(
            filename=cls.log_file_path,
            level=logging.CRITICAL,
            format='%(asctime)s %(levelname)s %(message)s',
        )

    @classmethod
    def tearDownClass(cls):
        cls.log_dir.cleanup()


    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.test_dir = self.temp_dir.name
        self.command_file=os.path.join(self.test_dir, 'command')


    def tearDown(self):
        self.temp_dir.cleanup()
        return super().tearDown()


    def _create_fake_uploader(self, test_dir: str, text: str) -> str:
        try:
            path = os.path.join(test_dir, 'casuploader')
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            os.chmod(path, 0o777)
            return path
        except Exception as e:
            self.fail(f"Error creating casuploader: {e}")
            return None


    def _create_fake_imagefile(self, dist_dir: str, name: str):
        try:
            path = os.path.join(dist_dir, name)
            with open(path, "w", encoding="utf-8") as f:
                f.write('fake image')
        except Exception as e:
            self.fail(f"Error creating image file: {e}")


    def _read_file_content(self, file_path):
        try:
            with open(file_path, "r", encoding="utf-8") as file:
                return file.read()
        except FileNotFoundError:
            return f"Error: File '{file_path}' not found."
        except Exception as e:
            return f"Error: {e}"


    def _upload(self, artifacts, test_dir: str, dist_dir: str) -> str:
        log_dir = os.path.join(dist_dir, 'logs')
        os.makedirs(log_dir, 0o777)
        log_file = os.path.join(log_dir, 'casuploader.log')
        cas_metrics = cas_metrics_pb2.CasMetrics()
        max_workers = 1
        dryrun = False
        client_path = self._create_fake_uploader(test_dir, f"""#!/bin/bash
echo $@ >> {self.command_file}
while (("$#" > 0)); do
    case "$1" in
    -dump-digest)
        echo "DIGEST" > $2
        shift 2 ;;
    -dump-file-details)
        echo "[ {{ "digest": "DIGEST", "path": "PATH", "size": 3680794449 }} ]" > $2
        shift 2
        ;;
    -dump-metrics)
        echo "{{ "digest": "DIGEST", "time_ms": "100" }}" > $2
        shift 2 ;;
    *)
        shift ;;
    esac
done

""")
        cas_info = CasInfo('INSTANCE', 'SERVICE', client_path, (1, 4))
        uploader = Uploader(cas_info, log_file)
        uploader.upload(artifacts, dist_dir, cas_metrics, max_workers, dryrun)
        return self._read_file_content(self.command_file)


    def _verify(self, command: str, has_flags: list[str], no_flags: list[str]):
        for flag in has_flags:
            if not flag in command:
                self.fail(f'flag "{flag}" is absent from command "{command}"')
        for flag in no_flags:
            if flag in command:
                self.fail(f'flag "{flag}" is should not be present in command "{command}"')


    def test_standard_version_flags(self):
        """standard_unzip version has '-file-path', no '-chunk'."""

        standard_image = ArtifactConfig(
            '*-img-*zip',
            unzip = False,
            standard = True,
            chunk = False,
            chunk_dir = False,
        )
        with tempfile.TemporaryDirectory() as dist_dir:
            artifacts = [standard_image]
            self._create_fake_imagefile(dist_dir, 'oriole-img-123.zip')
            command = self._upload(artifacts, self.test_dir, dist_dir)
            self._verify(command, ['-file-path'], ['-zip-path', '-chunk'])


    def test_standard_unzip_version_flags(self):
        """standard_unzip version has '-zip-path', no '-chunk'."""

        standard_image = ArtifactConfig(
            '*-img-*zip',
            unzip = True,
        )
        with tempfile.TemporaryDirectory() as dist_dir:
            artifacts = [standard_image]
            self._create_fake_imagefile(dist_dir, 'oriole-img-123.zip')
            command = self._upload(artifacts, self.test_dir, dist_dir)
            self._verify(command, ['-zip-path'], ['-file-path', '-chunk'])


    def test_chunk_version_flags(self):
        """chunk version has '-file-path' and '-chunk'."""

        standard_image = ArtifactConfig(
            '*-img-*zip',
            unzip = False,
            standard = False,
            chunk = True,
            chunk_dir = False,
        )
        with tempfile.TemporaryDirectory() as dist_dir:
            artifacts = [standard_image]
            self._create_fake_imagefile(dist_dir, 'oriole-img-123.zip')
            command = self._upload(artifacts, self.test_dir, dist_dir)
            self._verify(command, ['-file-path', '-chunk'], ['-zip-path'])


    def test_chunk_dir_version_flags(self):
        """chunk_dir version has '-zip-path' and '-chunk'."""
        standard_image = ArtifactConfig(
            '*-img-*zip',
            unzip = False,
            standard = False,
            chunk = False,
            chunk_dir = True,
        )
        with tempfile.TemporaryDirectory() as dist_dir:
            artifacts = [standard_image]
            self._create_fake_imagefile(dist_dir, 'oriole-img-123.zip')
            command = self._upload(artifacts, self.test_dir, dist_dir)
            self._verify(command, ['-zip-path', '-chunk'], ['-file-path'])

if __name__ == '__main__':
    unittest.main()
