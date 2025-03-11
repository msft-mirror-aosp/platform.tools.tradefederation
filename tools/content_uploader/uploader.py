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
import subprocess
import tempfile
import uuid
import time
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
    log_file: str


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
    def __init__(self, cas_info: CasInfo):
        """Initialize the Uploader with CAS info."""
        self._cas_info = cas_info

    @staticmethod
    def setup_task_logger(working_dir: str) -> Tuple[logging.Logger, str]:
        """Creates a logger for an individual uploader task."""
        task_id = uuid.uuid4()
        logger = logging.getLogger(f"Uploader-{task_id}")
        logger.setLevel(logging.DEBUG)
        logger.propagate = False

        log_file = os.path.join(working_dir, f"_uploader_{task_id}.log")
        file_handler = logging.FileHandler(log_file)
        formatter = logging.Formatter('%(asctime)s %(levelname)s %(message)s')
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

        return logger, log_file

    @staticmethod
    def read_file(file_path: str) -> str:
        """Returns contents of file."""
        try:
            with open(file_path, "r", encoding="utf-8") as file:
                return file.read()
        except FileNotFoundError:
            return f"Error: File '{file_path}' not found."
        except Exception as e:
            return f"Error: {e}"

    @staticmethod
    def _run_uploader_command(cmd: str, working_dir: str) -> str:
        """"Run the uploader command using working_dir and returns the output."""
        log_file = os.path.join(working_dir, f'_casuploader_{uuid.uuid4()}.log')
        with open(log_file, 'w', encoding='utf8') as outfile:
            subprocess.run(
                cmd,
                check=True,
                text=True,
                stdout=outfile,
                stderr=subprocess.STDOUT,
                encoding='utf-8',
                timeout=UPLOADER_TIMEOUT_SECS
            )
        return Uploader.read_file(log_file)

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
        logger, log_file = Uploader.setup_task_logger(working_dir)

        # `-dump-file-details` only supports on cas uploader V1.0 or later.
        dump_file_details = self._cas_info.client_version >= (1, 0)
        if not dump_file_details:
            logger.warning('-dump-file-details is not enabled')

        # `-dump-metrics` only supports on cas uploader V1.3 or later.
        dump_metrics = self._cas_info.client_version >= (1, 3)
        if not dump_metrics:
            logger.warning('-dump-metrics is not enabled')

        with tempfile.NamedTemporaryFile(mode='w+') as digest_file, tempfile.NamedTemporaryFile(
        mode='w+') as content_details_file:
            logger.info(
                'Uploading %s to CAS instance %s', artifact.source_path, self._cas_info.cas_instance
            )

            cmd = [
                self._cas_info.client_path,
                '-cas-instance',
                self._cas_info.cas_instance,
                '-cas-addr',
                self._cas_info.cas_service,
                '-dump-digest',
                digest_file.name,
                '-use-adc',
            ]

            cmd = cmd + Uploader._path_flag_for_artifact(artifact)

            if artifact.chunk or artifact.chunk_dir:
                cmd = cmd + ['-chunk', '-avg-chunk-size', str(AVG_CHUNK_SIZE_IN_KB)]

            for exclude_filter in artifact.exclude_filters:
                cmd = cmd + ['-exclude-filters', exclude_filter]

            if dump_file_details:
                cmd = cmd + ['-dump-file-details', content_details_file.name]

            if dump_metrics:
                cmd = cmd + ['-dump-metrics', metrics_file]

            try:
                logger.info('Running command: %s', cmd)
                output = Uploader._run_uploader_command(cmd, working_dir)
                logger.info('Command output:\n %s', output)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
                logger.warning(
                    'Failed to upload %s to CAS instance %s. Skip.\nError message: %s\nLog: %s',
                    artifact.source_path, self._cas_info.cas_instance, e, e.stdout,
                )
                return None
            except subprocess.SubprocessError as e:
                logger.warning('Failed to upload %s to CAS instance %s. Skip.\n. Error %s',
                    artifact.source_path, self._cas_info.cas_instance, e)
                return None

            # Read digest of the root directory or file from dumped digest file.
            digest = digest_file.read()
            if digest:
                logger.info('Uploaded %s to CAS. Digest: %s', artifact.source_path, digest)
            else:
                logger.warning(
                    'No digest is dumped for file %s, the uploading may fail.',
                    artifact.source_path,
                )
                return None

            content_details = None
            if dump_file_details:
                try:
                    content_details = json.loads(content_details_file.read())
                except json.JSONDecodeError as e:
                    logger.warning('Failed to parse uploaded content details: %s', e)

            return UploadResult(digest, content_details, log_file)

    @staticmethod
    def _path_flag_for_artifact(artifact: ArtifactConfig) -> list[str]:
        """Returns the path flag for the artifact."""
        if artifact.standard:
            return ['-zip-path' if artifact.unzip else '-file-path', artifact.source_path]
        if artifact.chunk:
            return ['-file-path', artifact.source_path]
        if artifact.chunk_dir:
            return ['-zip-path', artifact.source_path]
        # Should neve reach here.
        return ['-file-path', artifact.source_path]

    def _output_results(
            self,
            output_dir: str,
            digests: dict[str, str],
            content_details: list[dict[str, any]],
    ):
        """Outputs digests and content details."""
        digests_output = {
            'cas_instance': self._cas_info.cas_instance,
            'cas_service': self._cas_info.cas_service,
            'client_version': '.'.join(map(str, self._cas_info.client_version)),
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
    def _glob_wrapper(args):
        """Wrapper function for multiprocessing"""
        dist_dir, artifact = args

        files = []
        if artifact.source_path.startswith("./"):
            files = glob.glob(dist_dir + artifact.source_path[1:])
        else:
            files = glob.glob(dist_dir + '/**/' + artifact.source_path, recursive=True)
        return (files, artifact)

    def create_upload_tasks(
            self, artifacts: list[ArtifactConfig], working_dir: str, dist_dir: str
    ) -> list[UploadTask]:
        """Creates upload tasks for the artifacts."""
        start = time.time()

        tasks = []
        skip_files = []
        # Glob in parallel. Note that ThreadPoolExecutor doesn't help, likely due to GIL.
        with concurrent.futures.ProcessPoolExecutor() as executor:
            results = executor.map(
                    Uploader._glob_wrapper,
                    [(dist_dir, artifact) for artifact in artifacts],
            )
            for files, artifact in results:
                for file in files:
                    if os.path.isdir(file):
                        logging.warning('Ignore artifact match (dir): %s', file)
                        continue
                    rel_path = Uploader._get_relative_path(dist_dir, file)
                    for task_artifact in Uploader._artifact_variations(rel_path, artifact):
                        path = Uploader._artifact_path(rel_path, task_artifact)
                        if path in skip_files:
                            continue
                        skip_files.append(path)
                        task_artifact.source_path = file
                        _, task_metrics_file = tempfile.mkstemp(dir=working_dir)
                        task = UploadTask(task_artifact, path, working_dir, task_metrics_file)
                        tasks.append(task)

        logging.info(
                'Time of file globbing for all artifact configs: %d seconds',
                time.time() - start,
        )
        return tasks

    @staticmethod
    def _print_tasks(tasks: list[UploadTask]):
        """Outputs info for upload tasks."""
        for task in tasks:
            unzip = '+' if task.artifact.unzip else '-'
            print(f"{task.path:<40} {unzip} {task.artifact.source_path}")
        print(f"Total: {len(tasks)} files.")

    def upload(self, artifacts: list[ArtifactConfig], dist_dir: str,
               max_works: int, dryrun: bool = False) -> cas_metrics_pb2.CasMetrics:
        """Uploads artifacts to CAS remote"""
        file_digests = {}
        content_details = []

        cas_metrics = cas_metrics_pb2.CasMetrics()
        with tempfile.TemporaryDirectory() as working_dir:
            logging.info('The working dir is %s', working_dir)

            tasks = self.create_upload_tasks(artifacts, working_dir, dist_dir)
            logging.info('Uploading %d files, max workers = %d', len(tasks), max_works)
            if dryrun:
                Uploader._print_tasks(tasks)
                return cas_metrics

            # Upload artifacts in parallel
            logging.info('==== Start uploading %d artifact(s) in parallel ====\n', len(tasks))
            with concurrent.futures.ThreadPoolExecutor(max_workers=max_works) as executor:
                futures = [executor.submit(self._upload_wrapper, task) for task in tasks]

                index = 1
                for future in concurrent.futures.as_completed(futures):
                    result, task = future.result()
                    if result:
                        output = Uploader.read_file(result.log_file) if result else ''
                        logging.info('---- %s: %s ----\n\n%s', index, task.path, output)
                    else:
                        logging.info('---- %s: %s ----\n\n', index, task.path)
                    index += 1
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
            logging.info('==== Uploading of artifacts completed ====')

        self._output_results(
            dist_dir,
            file_digests,
            content_details,
        )
        return cas_metrics

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
