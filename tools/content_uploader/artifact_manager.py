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

"""Artifact manager that supports artifact overriding."""

import logging
from typing import Dict, List, Self, Any
from uploader import ArtifactConfig

class ArtifactManager:
    """Manager for artifact overriding."""

    def __init__(self, artifacts: Dict[str, ArtifactConfig]):
        """
        Initialize the ArtifactManager with a preset dictionary of artifacts.

        Args:
            artifacts: Dictionary of artifact configurations.
        """
        self._artifacts = artifacts.copy()

    def artifacts(self) -> Dict[str, ArtifactConfig]:
        """
        Returns a copy of the stored artifacts.

        Returns:
            A dictionary containing artifact configurations.
        """
        return self._artifacts.copy()

    def override_artifacts(self, args: Any) -> Self:
        """
        Override the preset artifacts based on the given arguments.

        Args:
            args: An object containing artifact override arguments.
        Returns:
            self
        """
        if not hasattr(args, 'artifacts') or not args.artifacts:
            return self

        for override in args.artifacts:
            self._process_artifact_override(override)
        return self

    def _process_artifact_override(self, override: str):
        """
        Processes a single artifact override string.

        Args:
            override: The override string in the format 'name=source_path attributes'.
        """
        tokens = override.split('=', maxsplit=1)
        if len(tokens) != 2:
            logging.warning('Artifact override - ignored (invalid format): %s', override)
            return

        name, source_path_str = tokens
        if not source_path_str:
            self._delete_artifact(name, override)
            return

        self._update_or_create_artifact(name, source_path_str)

    def _delete_artifact(self, name: str, override: str):
        """
        Deletes an artifact if it exists.

        Args:
            name: The name of the artifact to delete.
            override: The original override string for logging purposes.
        """
        if name in self._artifacts:
            logging.info('Artifact delete: %s', override)
            del self._artifacts[name]
        else:
            logging.warning('Artifact delete - ignored (name not found): %s', override)

    def _update_or_create_artifact(self, name: str, source_path_str: str):
        """
        Updates an existing artifact or creates a new one.

        Args:
            name: The name of the artifact.
            source_path_str: The source path and optional attributes string.
        """
        tokens = source_path_str.split()
        source_path = tokens[0]
        action = "update" if name in self._artifacts else "add"
        artifact = self._artifacts.get(name, ArtifactConfig(source_path, unzip=False))
        artifact.source_path = source_path

        if len(tokens) > 1:
            artifact.unzip = self._get_artifact_attribute(
                tokens[1:], 'unzip', artifact.unzip
            )
            artifact.standard = self._get_artifact_attribute(
                tokens[1:], 'standard', artifact.standard
            )
            artifact.chunk = self._get_artifact_attribute(
                tokens[1:], 'chunk', artifact.chunk
            )
            artifact.chunk_dir = self._get_artifact_attribute(
                tokens[1:], 'chunk_dir', artifact.chunk_dir
            )

        logging.info('Artifact %s: %s', action, artifact)
        self._artifacts[name] = artifact

    def _get_artifact_attribute(
        self, tokens: List[str], attribute_name: str, default: bool
    ) -> bool:
        """
        Retrieves a boolean attribute from the given tokens. Treat 'ATTRIBUTE_NAME'
        same as 'ATTRIBUTE_NAME=True'.

        Args:
            tokens: A list of tokens containing attribute assignments.
            attribute_name: The name of the attribute to retrieve.
            default: The default value if the attribute is not found.

        Returns:
            The boolean value of the attribute.
        """
        for token in tokens:
            if token.startswith(attribute_name + '='):
                attr_token = token.split('=', 1)[1].lower()
                if attr_token in {'true', 't', '1'}:
                    return True
                elif attr_token in {'false', 'f', '0'}:
                    return False
                else:
                    logging.warning(
                        'Invalid boolean value for %s: %s' % (attribute_name, attr_token)
                    )
                    return default
            if token == attribute_name:
                return True
        return default
