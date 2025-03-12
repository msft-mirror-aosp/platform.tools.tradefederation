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

"""Unit tests for artifacts."""

import unittest

from artifacts import ARTIFACTS
from uploader import ArtifactConfig


class ArtifactsTest(unittest.TestCase):
    """A unit test class for preset artifacts."""

    def test_img_has_expected_attributes(self):
        """Tests the attributes of the 'img' artifact."""
        self.assertTrue('img' in ARTIFACTS, "The 'img' artifact should exist.")
        expected = ArtifactConfig('*-img-*zip', False, chunk=True, chunk_dir=True)
        self.assertEqual(
            ARTIFACTS['img'], expected, "The 'img' artifact attributes do not match."
        )

    def test_unzip_artifacts_have_path_end_with_zip(self):
        """Tests that artifacts with unzip=True have source paths ending with 'zip'."""
        for name, artifact in ARTIFACTS.items():
            if artifact.unzip:
                self.assertTrue(
                    artifact.source_path.endswith('zip'),
                    f"Artifact '{name}' (unzip=True) should have a source path ending with 'zip'.",
                )

    def test_all_preset_artifacts_are_valid(self):
        """Tests that no artrifact has all standard, chunk, and chunk_dir false."""
        for name, artifact in ARTIFACTS.items():
            self.assertTrue(
                artifact.standard or artifact.chunk or artifact.chunk_dir,
                (
                    f"Artifact '{name}' is invalid. "
                    "One or more of 'standard', 'chunk' or 'chunk_dir' must be true."
                )
            )


if __name__ == '__main__':
    unittest.main()
