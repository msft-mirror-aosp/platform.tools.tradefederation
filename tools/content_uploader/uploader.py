#!/usr/bin/env python3
#
#  Copyright (C) 2022 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

"""The script to upload generated artifacts from build server to CAS."""

import concurrent.futures
import dataclasses
import glob
import json
import logging
import os
import shutil
import subprocess
import tempfile
from typing import Tuple

import cas_metrics_pb2  # type: ignore
from google.protobuf import json_format

@dataclasses.dataclass
class ArtifactConfig:
    """Configuration of an artifact to be uploaded to CAS.

    Attributes:
        source_path: path to the artifact that relative to the root of source code.
        unzip: true if the artifact should be unzipped and uploaded as a directory.
        chunk: true if the artifact should be uploaded with chunking as a single file.
        chunk_dir: true if the artifact should be uploaded with chunking as a directory.
        exclude_filters: a list of regular expressions for files that are excluded from uploading.
    """
    source_path: str
    unzip: bool
    standard: bool = True
    chunk: bool = False
    chunk_dir: bool = False
    exclude_filters: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class CasInfo:
    """Basic information of CAS server and client.

    Attributes:
        cas_instance: the instance name of CAS service.
        cas_service: the address of CAS service.
        client_path: path to the CAS uploader client.
        version: version of the CAS uploader client, in turple format.
    """
    cas_instance: str
    cas_service: str
    client_path: str
    client_version: tuple


@dataclasses.dataclass
class UploadResult:
    """Result of uploading a single artifact with CAS client.

    Attributes:
        digest: root digest of the artifact.
        content_details: detail information of all uploaded files inside the uploaded artifact.
    """
    digest: str
    content_details: list[dict[str, any]]


@dataclasses.dataclass
class UploadTask:
    """Task of uploading a single artifact with CAS client."""
    artifact: ArtifactConfig
    path: str
    working_dir: str
    metrics_file: str


UPLOADER_TIMEOUT_SECS = 600  # 10 minutes
AVG_CHUNK_SIZE_IN_KB = 128
DIGESTS_PATH = 'cas_digests.json'
CONTENT_DETAILS_PATH = 'logs/cas_content_details.json'
CHUNKED_ARTIFACT_NAME_PREFIX = "_chunked_"
CHUNKED_DIR_ARTIFACT_NAME_PREFIX = "_chunked_dir_"


class Uploader:
    """Uploader for uploading artifacts to CAS remote."""
    def __init__(self, cas_info: CasInfo, log_file: str):
        """Initialize the Uploader with CAS info."""
        self.cas_info = cas_info
        self.log_file = log_file

    def _upload_artifact(self,
            artifact: ArtifactConfig,
            working_dir: str,
            metrics_file: str,
    ) -> UploadResult:
        """Upload the artifact to CAS using casuploader binary.

        Args:
        artifact: the artifact to be uploaded to CAS.
        working_dir: the directory for intermediate files.
        metrics_file: the metrics_file for the artifact.

        Returns: the digest of the uploaded artifact, formatted as "<hash>/<size>".
        returns None if artifact upload fails.
        """
        # `-dump-file-details` only supports on cas uploader V1.0 or later.
        dump_file_details = self.cas_info.client_version >= (1, 0)
        if not dump_file_details:
            logging.warning('-dump-file-details is not enabled')

        # `-dump-metrics` only supports on cas uploader V1.3 or later.
        dump_metrics = self.cas_info.client_version >= (1, 3)
        if not dump_metrics:
            logging.warning('-dump-metrics is not enabled')

        with tempfile.NamedTemporaryFile(mode='w+') as digest_file, tempfile.NamedTemporaryFile(
        mode='w+') as content_details_file:
            logging.info(
                'Uploading %s to CAS instance %s', artifact.source_path, self.cas_info.cas_instance
            )

            cmd = [
                self.cas_info.client_path,
                '-cas-instance',
                self.cas_info.cas_instance,
                '-cas-addr',
                self.cas_info.cas_service,
                '-dump-digest',
                digest_file.name,
                '-use-adc',
            ]

            cmd = cmd + Uploader._path_flag_for_artifact(artifact, working_dir)

            if artifact.chunk or artifact.chunk_dir:
                cmd = cmd + ['-chunk', '-avg-chunk-size', str(AVG_CHUNK_SIZE_IN_KB)]

            for exclude_filter in artifact.exclude_filters:
                cmd = cmd + ['-exclude-filters', exclude_filter]

            if dump_file_details:
                cmd = cmd + ['-dump-file-details', content_details_file.name]

            if dump_metrics:
                cmd = cmd + ['-dump-metrics', metrics_file]

            try:
                logging.info('Running command: %s', cmd)
                with open(self.log_file, 'a', encoding='utf8') as outfile:
                    subprocess.run(
                        cmd,
                        check=True,
                        text=True,
                        stdout=outfile,
                        stderr=subprocess.STDOUT,
                        encoding='utf-8',
                        timeout=UPLOADER_TIMEOUT_SECS
                    )
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
                logging.warning(
                    'Failed to upload %s to CAS instance %s. Skip.\nError message: %s\nLog: %s',
                    artifact.source_path, self.cas_info.cas_instance, e, e.stdout,
                )
                return None
            except subprocess.SubprocessError as e:
                logging.warning('Failed to upload %s to CAS instance %s. Skip.\n. Error %s',
                    artifact.source_path, self.cas_info.cas_instance, e)
                return None

            # Read digest of the root directory or file from dumped digest file.
            digest = digest_file.read()
            if digest:
                logging.info('Uploaded %s to CAS. Digest: %s', artifact.source_path, digest)
            else:
                logging.warning(
                    'No digest is dumped for file %s, the uploading may fail.', artifact.source_path)
                return None

            content_details = None
            if dump_file_details:
                try:
                    content_details = json.loads(content_details_file.read())
                except json.JSONDecodeError as e:
                    logging.warning('Failed to parse uploaded content details: %s', e)

            return UploadResult(digest, content_details)


    @staticmethod
    def _path_flag_for_artifact(artifact: ArtifactConfig, working_dir: str) -> str:
        """Returns the path flag for the artifact."""
        if artifact.standard:
            return ['-zip-path' if artifact.unzip else '-file-path', artifact.source_path]
        if artifact.chunk:
            return ['-file-path', artifact.source_path]
        if artifact.chunk_dir:
            return ['-zip-path', artifact.source_path]
        # TODO(b/250643926) This is a workaround to handle non-directory files.
        tmp_dir = tempfile.mkdtemp(dir=working_dir)
        target_path = os.path.join(tmp_dir, os.path.basename(artifact.source_path))
        shutil.copy(artifact.source_path, target_path)
        return ['-dir-path', tmp_dir]


    def _output_results(
            self,
            output_dir: str,
            digests: dict[str, str],
            content_details: list[dict[str, any]],
    ):
        """Outputs digests and content details."""
        digests_output = {
            'cas_instance': self.cas_info.cas_instance,
            'cas_service': self.cas_info.cas_service,
            'client_version': '.'.join(map(str, self.cas_info.client_version)),
            'files': digests,
        }
        output_path = os.path.join(output_dir, DIGESTS_PATH)
        with open(output_path, 'w', encoding='utf8') as writer:
            writer.write(json.dumps(digests_output, sort_keys=True, indent=2))
        logging.info('Output digests to %s', output_path)

        output_path = os.path.join(output_dir, CONTENT_DETAILS_PATH)
        with open(output_path, 'w', encoding='utf8') as writer:
            writer.write(json.dumps(content_details, sort_keys=True, indent=2))
        logging.info('Output uploaded content details to %s', output_path)


    def _upload_wrapper(self, task: UploadTask) -> Tuple[UploadResult, UploadTask]:
        """Returns a wrapper for _upload_artifact that associates the result with the task."""
        return self._upload_artifact(
            task.artifact,
            task.working_dir,
            task.metrics_file,
        ), task


    @staticmethod
    def _glob(dist_dir: str, path: str) -> list[str]:
        """Returns glob pattern for files matching path in dist_dir."""
        if path.startswith("./"):
            return glob.glob(dist_dir + path[1:])
        return glob.glob(dist_dir + '/**/' + path, recursive=True)


    def create_upload_tasks(self, artifacts: list[ArtifactConfig], working_dir: str, dist_dir: str) -> list[UploadTask]:
        """Creates upload tasks for the artifacts."""
        tasks = []
        skip_files = []
        for artifact in artifacts:
            for f in Uploader._glob(dist_dir, artifact.source_path):
                if os.path.isdir(f):
                    logging.warning('Ignore artifact match (dir): %s', f)
                    continue
                rel_path = Uploader._get_relative_path(dist_dir, f)
                for task_artifact in Uploader._artifact_variations(rel_path, artifact):
                    path = Uploader._artifact_path(rel_path, task_artifact)

                    # Avoid redundant upload if multiple ArtifactConfigs share files.
                    if path in skip_files:
                        continue
                    skip_files.append(path)
                    task_artifact.source_path = f
                    _, task_metrics_file = tempfile.mkstemp(dir=working_dir)
                    task = UploadTask(task_artifact, path, working_dir, task_metrics_file)
                    tasks.append(task)
        return tasks


    @staticmethod
    def _print_tasks(tasks: list[UploadTask]):
        """Outputs info for upload tasks."""
        for task in tasks:
            unzip = '+' if task.artifact.unzip else '-'
            print(f"{task.path:<40} {unzip} {task.artifact.source_path}")
        print(f"Total: {len(tasks)} files.")


    def upload(self, artifacts: list[ArtifactConfig], dist_dir: str,
               cas_metrics: str, max_works: int, dryrun: bool = False):
        """Uploads artifacts to CAS remote"""
        file_digests = {}
        content_details = []

        with tempfile.TemporaryDirectory() as working_dir:
            logging.info('The working dir is %s', working_dir)

            tasks = self.create_upload_tasks(artifacts, working_dir, dist_dir)
            logging.info('Uploading %d files, max workers = %d', len(tasks), max_works)
            if dryrun:
                Uploader._print_tasks(tasks)
                return

            # Upload artifacts in parallel
            with concurrent.futures.ThreadPoolExecutor(max_workers=max_works) as executor:
                futures = [executor.submit(self._upload_wrapper, task) for task in tasks]

                for future in concurrent.futures.as_completed(futures):
                    result, task = future.result()
                    if result and result.digest:
                        file_digests[task.path] = result.digest
                    else:
                        logging.warning(
                            'Skip to save the digest of file %s, the uploading may fail',
                            task.path,
                        )
                    if result and result.content_details:
                        content_details.append({"artifact": task.path,
                                                "details": result.content_details})
                    else:
                        logging.warning('Skip to save the content details of file %s', task.path)

                    if os.path.exists(task.metrics_file):
                        Uploader._add_artifact_metrics(task.metrics_file, cas_metrics)
                        os.remove(task.metrics_file)

        self._output_results(
            dist_dir,
            file_digests,
            content_details,
        )


    @staticmethod
    def _add_artifact_metrics(metrics_file: str, cas_metrics: cas_metrics_pb2.CasMetrics):
        """Adds artifact metrics from metrics_file to cas_metrics."""
        try:
            with open(metrics_file, "r", encoding='utf8') as file:
                json_str = file.read()  # Read the file contents here
                if json_str:
                    json_metrics = json.loads(json_str)
                    cas_metrics.artifacts.append(
                        json_format.ParseDict(json_metrics, cas_metrics_pb2.ArtifactMetrics())
                    )
                else:
                    logging.exception("Empty file: %s", metrics_file)
        except FileNotFoundError:
            logging.exception("File not found: %s", metrics_file)
        except json.JSONDecodeError as e:
            logging.exception("Jason decode error: %s for json contents:\n%s", e, json_str)
        except json_format.ParseError as e:  # Catch any other unexpected errors
            logging.exception("Error converting Json to protobuf: %s", e)


    @staticmethod
    def _get_relative_path(dir: str, path: str) -> str:
        """Returns the relative path from dir, falls back to basename on error."""
        try:
            return os.path.relpath(path, dir)
        except ValueError as e:
            logging.exception("Error calculating relative path: %s", e)
            return os.path.basename(path)


    @staticmethod
    def _artifact_path(path: str, artifact: ArtifactConfig) -> str:
        """Returns unique artifact path for saving in cas_digest.json."""
        if artifact.chunk:
            return CHUNKED_ARTIFACT_NAME_PREFIX + path
        if artifact.chunk_dir:
            return CHUNKED_DIR_ARTIFACT_NAME_PREFIX + path
        return path


    @staticmethod
    def _artifact_variations(path: str, artifact: ArtifactConfig) -> list[ArtifactConfig]:
        """Returns variations of the artifact for upload based on artifact attributes."""
        variations = []
        if artifact.standard:
            variations.append(ArtifactConfig(path, artifact.unzip, True, False, False,
                                            exclude_filters=artifact.exclude_filters))
        if artifact.chunk:
            variations.append(ArtifactConfig(path, False, False, True, False,
                                            exclude_filters=artifact.exclude_filters))
        if artifact.chunk_dir:
            variations.append(ArtifactConfig(path, True, False, False, True,
                                            exclude_filters=artifact.exclude_filters))
        return variations
