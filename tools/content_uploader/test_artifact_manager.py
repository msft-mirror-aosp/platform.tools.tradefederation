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

"""Unit tests for artifact_manager."""

import argparse
import sys
import tempfile
import unittest
import logging
from unittest.mock import patch

from artifact_manager import ArtifactManager
from uploader import ArtifactConfig


class ArtifactManagerTest(unittest.TestCase):
    """A unit test class for artifact_manager."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.test_dir = self.temp_dir.name
        self.artifacts = {
            'apex': ArtifactConfig('*.apex', False),
            'img': ArtifactConfig('*-img-*zip', False, chunk=True, chunk_dir=True)
        }
        self.artifact_manager = ArtifactManager(self.artifacts)
        self.parser = argparse.ArgumentParser()
        self.parser.add_argument(
            '--artifacts',
            required=False,
            action='append',
            default=[],
            help='Override artifacts',
        )

    def tearDown(self):
        self.temp_dir.cleanup()
        return super().tearDown()

    def test_override_artifacts_delete_artifact(self):
        """Flag --artifact 'ARTIFACT_NAME=' deletes the artifact."""
        test_args = ['content_uploader', '--artifacts', 'img=']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            self.artifact_manager.override_artifacts(args)
            artifacts = self.artifact_manager.artifacts()
            expected = {k: v for k, v in self.artifacts.items() if k != 'img'}
            self.assertDictEqual(artifacts, expected)

    def test_override_artifacts_update_artifact(self):
        """Flag --artifact 'ARTIFACT_NAME=NEW_PATH' updates artifact path."""
        test_args = ['content_uploader', '--artifacts', 'apex=new_path']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            self.artifact_manager.override_artifacts(args)
            artifacts = self.artifact_manager.artifacts()
            expected = self.artifacts.copy()
            self.artifacts['apex'].source_path = 'new_path'
            self.assertDictEqual(artifacts, expected)

    def test_override_artifacts_add_artifact(self):
        """Flag --artifact 'NEW_ARTIFACT=PATH' adds a new artifact."""
        test_args = ['content_uploader', '--artifacts', 'new_artifact=path/to/new']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            self.artifact_manager.override_artifacts(args)
            artifacts = self.artifact_manager.artifacts()
            expected = self.artifacts.copy()
            expected['new_artifact'] = ArtifactConfig('path/to/new', False)
            self.assertDictEqual(artifacts, expected)

    def test_override_artifacts_update_attributes(self):
        """Flag --artifact 'ARTIFACT_NAME=PATH standard chunk=True' updates attributes."""
        test_args = ['content_uploader', '--artifacts', 'apex=new_path standard chunk=True']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            self.artifact_manager.override_artifacts(args)
            artifacts = self.artifact_manager.artifacts()
            self.assertEqual(artifacts['apex'].source_path, 'new_path')
            self.assertFalse(artifacts['apex'].unzip)   # Default
            self.assertTrue(artifacts['apex'].standard) # 'standard'
            self.assertTrue(artifacts['apex'].chunk)    # 'chunk=True'
            self.assertFalse(artifacts['apex'].chunk_dir)

    def test_override_artifacts_update_attributes_to_false(self):
        """Flag --artifact 'ARTIFACT_NAME=PATH unzip standard=False chunk=F' updates attributes."""
        test_args = ['content_uploader', '--artifacts', 'img=new_path standard=False chunk=F']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            self.artifact_manager.override_artifacts(args)
            artifacts = self.artifact_manager.artifacts()
            self.assertEqual(artifacts['img'].source_path, 'new_path')
            self.assertFalse(artifacts['img'].unzip)    # Default
            self.assertFalse(artifacts['img'].standard) # 'standard=False'
            self.assertFalse(artifacts['img'].chunk)    # 'chunk=F'
            self.assertTrue(artifacts['img'].chunk_dir)

    def test_override_artifacts_invalid_format(self):
        """Flag --artifact 'INVALID_FORMAT' logs a warning."""
        test_args = ['content_uploader', '--artifacts', 'invalid_format']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            with self.assertLogs(level=logging.WARNING) as cm:
                self.artifact_manager.override_artifacts(args)
            self.assertIn("Artifact override - ignored (invalid format):", cm.output[0])

    def test_override_artifacts_invalid_boolean_attribute(self):
        """Flag --artifact logs a warning for invalid boolean value."""
        test_args = ['content_uploader', '--artifacts', 'apex=new_path unzip=invalid']
        with patch.object(sys, 'argv', test_args):
            args = self.parser.parse_args()
            with self.assertLogs(level=logging.WARNING) as cm:
                self.artifact_manager.override_artifacts(args)
            self.assertIn("Invalid boolean value for unzip: invalid", cm.output[0])


if __name__ == '__main__':
    unittest.main()
