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

import argparse
import copy
import dataclasses
import glob
import json
import logging
import os
import random
import re
import shutil
import subprocess
import tempfile
import time
from typing import Tuple

import cas_metrics_pb2  # type: ignore
import concurrent.futures
from google.protobuf import json_format

VERSION = '1.2'

@dataclasses.dataclass
class ArtifactConfig:
    """Configuration of an artifact to be uploaded to CAS.

    Attributes:
        source_path: path to the artifact that relative to the root of source code.
        unzip: true if the artifact should be unzipped and uploaded as a directory.
        chunk: true if the artifact should be uploaded with chunking.
        chunk_fallback: true if a regular version (no chunking) of the artifact should be uploaded.
        exclude_filters: a list of regular expressions for files that are excluded from uploading.
    """
    source_path: str
    unzip: bool
    chunk: bool = False
    chunk_fallback: bool = False
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
    content_details: list[dict[str,any]]


@dataclasses.dataclass
class UploadTask:
    """Task of uploading a single artifact with CAS client."""
    artifact: ArtifactConfig
    path: str
    working_dir: str
    metrics_file: str


CAS_UPLOADER_PREBUILT_PATH = 'tools/tradefederation/prebuilts/'
CAS_UPLOADER_PATH = 'tools/content_addressed_storage/prebuilts/'
CAS_UPLOADER_BIN = 'casuploader'

UPLOADER_TIMEOUT_SECS = 600 # 10 minutes
AVG_CHUNK_SIZE_IN_KB = 128

DIGESTS_PATH = 'cas_digests.json'
LOG_PATH = 'logs/cas_uploader.log'
CAS_METRICS_PATH = 'logs/cas_metrics.pb'
METRICS_PATH = 'logs/artifact_metrics.json'
CONTENT_DETAILS_PATH = 'logs/cas_content_details.json'
CHUNKED_ARTIFACT_NAME_PREFIX = "_chunked_"
CHUNKED_DIR_ARTIFACT_NAME_PREFIX = "_chunked_dir_"
MAX_WORKERS_LOWER_BOUND = 2
MAX_WORKERS_UPPER_BOUND = 6
MAX_WORKERS = random.randint(MAX_WORKERS_LOWER_BOUND, MAX_WORKERS_UPPER_BOUND)

# Configurations of artifacts to upload to CAS.
#
# Override the preset artifacts with the "--artifacts" flag. Examples:
# 1. To upload img files with path relative to DIST_DIR (no '/**/'), override source_path with:
#   --artifacts 'img=./*-img-*zip'
# 2. To upload img files as chunked version only:
#   --artifacts 'img=*-img-*zip' chunk_fallback=False
# 3. To also upload json files in logs folder:
#   --artifacts 'new=./log/*.json'
# 4. To not update img files:
#   --artifacts 'img='
#
ARTIFACTS = {
    # test_suite targets
    'android-catbox': ArtifactConfig('android-catbox.zip', True, exclude_filters=['android-catbox/jdk/.*']),
    'android-csuite': ArtifactConfig('android-csuite.zip', True, exclude_filters=['android-csuite/jdk/.*']),
    'android-cts': ArtifactConfig('android-cts.zip', True, exclude_filters=['android-cts/jdk/.*']),
    'android-gcatbox': ArtifactConfig('android-gcatbox.zip', True, exclude_filters=['android-gcatbox/jdk/.*']),
    'android-gts': ArtifactConfig('android-gts.zip', True, exclude_filters=['android-gts/jdk/.*']),
    'android-mcts': ArtifactConfig('android-mcts.zip', True),
    'android-mts': ArtifactConfig('android-mts.zip', True, exclude_filters=['android-mts/jdk/.*']),
    'android-pts': ArtifactConfig('android-pts.zip', True, exclude_filters=['android-pts/jdk/.*']),
    'android-sts': ArtifactConfig('android-sts.zip', True),
    'android-tvts': ArtifactConfig('android-tvts.zip', True, exclude_filters=['android-tvts/jdk/.*']),
    'android-vts': ArtifactConfig('android-vts.zip', True),
    'android-wts': ArtifactConfig('android-wts.zip', True, exclude_filters=['android-wts/jdk/.*']),
    'art-host-tests': ArtifactConfig('art-host-tests.zip', True),
    'bazel-test-suite': ArtifactConfig('bazel-test-suite.zip', True),
    'host-unit-tests': ArtifactConfig('host-unit-tests.zip', True),
    'general-tests': ArtifactConfig('general-tests.zip', True),
    'general-tests_configs': ArtifactConfig('general-tests_configs.zip', True),
    'general-tests_host-shared-libs': ArtifactConfig('general-tests_host-shared-libs.zip', True),
    'tradefed': ArtifactConfig('tradefed.zip', True),
    'google-tradefed': ArtifactConfig('google-tradefed.zip', True),
    'robolectric-tests': ArtifactConfig('robolectric-tests.zip', True),
    'ravenwood-tests': ArtifactConfig('ravenwood-tests.zip', True),
    'test_mappings': ArtifactConfig('test_mappings.zip', True),

    # Mainline artifacts
    'apex': ArtifactConfig('*.apex', False),
    'apk': ArtifactConfig('*.apk', False),

    # Device target artifacts
    'androidTest': ArtifactConfig('androidTest.zip', True),
    'device-tests': ArtifactConfig('device-tests.zip', True),
    'device-tests_configs': ArtifactConfig('device-tests_configs.zip', True),
    'device-tests_host-shared-libs': ArtifactConfig('device-tests_host-shared-libs.zip', True),
    'performance-tests': ArtifactConfig('performance-tests.zip', True),
    'device-platinum-tests': ArtifactConfig('device-platinum-tests.zip', True),
    'device-platinum-tests_configs': ArtifactConfig('device-platinum-tests_configs.zip', True),
    'device-platinum-tests_host-shared-libs': ArtifactConfig('device-platinum-tests_host-shared-libs.zip', True),
    'camera-hal-tests': ArtifactConfig('camera-hal-tests.zip', True),
    'camera-hal-tests_configs': ArtifactConfig('camera-hal-tests_configs.zip', True),
    'camera-hal-tests_host-shared-libs': ArtifactConfig('camera-hal-tests_host-shared-libs.zip', True),
    'device-pixel-tests': ArtifactConfig('device-pixel-tests.zip', True),
    'device-pixel-tests_configs': ArtifactConfig('device-pixel-tests_configs.zip', True),
    'device-pixel-tests_host-shared-libs': ArtifactConfig('device-pixel-tests_host-shared-libs.zip', True),
    'automotive-tests': ArtifactConfig('automotive-tests.zip', True),
    'automotive-general-tests': ArtifactConfig('automotive-general-tests', True),
    'automotive-sdv-tests': ArtifactConfig('automotive-sdv-tests', True),
    'automotive-sdv-tests_configs': ArtifactConfig('automotive-sdv-tests_configs', True),
    'tests': ArtifactConfig('*-tests-*zip', True),
    'continuous_instrumentation_tests': ArtifactConfig('*-continuous_instrumentation_tests-*zip', True),
    'continuous_instrumentation_metric_tests': ArtifactConfig('*-continuous_instrumentation_metric_tests-*zip', True),
    'continuous_native_tests': ArtifactConfig('*-continuous_native_tests-', True),
    'cvd-host_package': ArtifactConfig('cvd-host_package.tar.gz', False),
    'bootloader': ArtifactConfig('bootloader.img', False),
    'radio': ArtifactConfig('radio.img', False),
    'target_files': ArtifactConfig('*-target_files-*', True),
    'img': ArtifactConfig('*-img-*zip', True, True, True)
}

# Artifacts will be uploaded if the config name is set in arguments `--experiment_artifacts`.
# These configs are usually used to upload artifacts in partial branches/targets for experiment
# purpose.
# A sample entry:
#   "device_image_target_files": ArtifactConfig('*-target_files-*.zip', True)
EXPERIMENT_ARTIFACT_CONFIGS = {
    "device_image_proguard_dict": ArtifactConfig('*-proguard-dict-*.zip', False, True, True),
}

def _init_cas_info() -> CasInfo:
    client_path = _get_client()
    return CasInfo(
        _get_env_var('RBE_instance', check=True),
        _get_env_var('RBE_service', check=True),
        client_path,
        _get_client_version(client_path)
    )


def _get_client() -> str:
    if CAS_UPLOADER_PREBUILT_PATH in os.path.abspath(__file__):
        return _get_prebuilt_client()
    bin_path = os.path.join(CAS_UPLOADER_PATH, CAS_UPLOADER_BIN)
    if os.path.isfile(bin_path):
        logging.info('Using client at %s', bin_path)
        return bin_path
    return _get_prebuilt_client()


def _get_prebuilt_client() -> str:
    client = glob.glob(CAS_UPLOADER_PREBUILT_PATH + '**/' + CAS_UPLOADER_BIN, recursive=True)
    if not client:
        raise ValueError('Could not find casuploader binary')
    logging.info('Using client at %s', client[0])
    return client[0]


def _get_client_version(client_path: str) -> int:
    """Get the version of CAS client in turple format."""
    version_output = ''
    try:
        version_output = subprocess.check_output([client_path, '-version']).decode('utf-8').strip()
        matched = re.findall(r'version: (\d+\.\d+)', version_output)
        if not matched:
            logging.warning('Failed to parse CAS client version. Output: %s', version_output)
            return (0, 0)
        version = tuple(map(int, matched[0].split('.')))
        logging.info('CAS client version is %s', version)
        return version
    # pylint: disable=broad-exception-caught
    except Exception as e:
    # pylint: enable=broad-exception-caught
        logging.warning('Failed to get CAS client version. Output: %s. Error %s', version_output, e)
        return (0, 0)


def _get_env_var(key: str, default=None, check=False):
    value = os.environ.get(key, default)
    if check and not value:
        raise ValueError(f'Error: the environment variable {key} is not set')
    return value


def _get_artifact_property(values: list[str], property:str, default:bool) -> bool:
    for value in values:
        # Example:
        #   --artifacts 'img=*-img-*zip chunk=True'
        #   --artifacts 'img=*-img-*zip chunk chunk_fallback=False'
        if value.startswith(property):
            tokens=value.split('=', maxsplit=1)
            if tokens[0] != property:
                continue
            if len(tokens) == 1 or tokens[1] in {'T', 't', 'True', 'true'}:
                return True
            return False
    return default


def _override_artifacts(args):
    for override in args.artifacts:
        # Example:
        #   --artifacts 'img=./*-img-*zip unzip chunk=False'
        tokens=override.split('=', maxsplit=1)
        if len(tokens) == 1:
            logging.warning("Artifact override - ignored (invalid): %s", override)
            continue
        name = tokens[0]
        artifact = ARTIFACTS[name] if name in ARTIFACTS else None
        if not tokens[1]:  # Delete an artifact ('name=')
            if artifact:
                logging.info("Artifact delete: %s", override)
                del ARTIFACTS[name]
            else:
                logging.warning("Artifact delete - ignored (name not found): %s", override)
            return
        values = tokens[1].split()
        source_path = values[0]
        if artifact:
            logging.info("Artifact override: %s", override)
            artifact.source_path = source_path
        else:
            logging.info("Artifact add new: %s", override)
            artifact = ArtifactConfig(source_path, False)
        if len(values) > 1:
            artifact.unzip = _get_artifact_property(values[1:], "unzip", artifact.unzip)
            artifact.chunk = _get_artifact_property(values[1:], "chunk", artifact.chunk)
            artifact.chunk_fallback = _get_artifact_property(values[1:], "chunk_fallback", artifact.chunk_fallback)
        logging.info("Artifact: %s", artifact)
        ARTIFACTS[name] = artifact


def _parse_additional_artifacts(args) -> list[ArtifactConfig]:
    additional_artifacts = []
    for config in args.experiment_artifacts:
        if config not in EXPERIMENT_ARTIFACT_CONFIGS:
            logging.warning('Ignore invalid experiment_artifacts: %s', config)
        else:
            additional_artifacts.append(EXPERIMENT_ARTIFACT_CONFIGS[config])
            logging.info(
                'Added experiment artifact from arguments %s',
                EXPERIMENT_ARTIFACT_CONFIGS[config].source_path,
            )
    return additional_artifacts


def _upload(
        cas_info: CasInfo,
        artifact: ArtifactConfig,
        working_dir: str,
        log_file: str,
        metrics_file: str,
) -> UploadResult:
    """Upload the artifact to CAS by casuploader binary.

    Args:
      cas_info: the basic CAS server information.
      artifact: the artifact to be uploaded to CAS.
      working_dir: the directory for intermediate files.
      log_file: the file where to add the upload logs.
      metrics_file: the metrics_file for the artifact.

    Returns: the digest of the uploaded artifact, formatted as "<hash>/<size>".
      returns None if artifact upload fails.
    """
    # `-dump-file-details` only supports on cas uploader V1.0 or later.
    dump_file_details = cas_info.client_version >= (1, 0)
    if not dump_file_details:
        logging.warning('-dump-file-details is not enabled')

    # `-dump-metrics` only supports on cas uploader V1.3 or later.
    dump_metrics = cas_info.client_version >= (1, 3)
    if not dump_metrics:
        logging.warning('-dump-metrics is not enabled')

    with tempfile.NamedTemporaryFile(mode='w+') as digest_file, tempfile.NamedTemporaryFile(
      mode='w+') as content_details_file:
        logging.info(
            'Uploading %s to CAS instance %s', artifact.source_path, cas_info.cas_instance
        )

        cmd = [
            cas_info.client_path,
            '-cas-instance',
            cas_info.cas_instance,
            '-cas-addr',
            cas_info.cas_service,
            '-dump-digest',
            digest_file.name,
            '-use-adc',
        ]

        cmd = cmd + _path_for_artifact(artifact, working_dir)

        if artifact.chunk:
            cmd = cmd + ['-chunk', '-avg-chunk-size', str(AVG_CHUNK_SIZE_IN_KB)]

        for exclude_filter in artifact.exclude_filters:
            cmd = cmd + ['-exclude-filters', exclude_filter]

        if dump_file_details:
            cmd = cmd + ['-dump-file-details', content_details_file.name]

        if dump_metrics:
            cmd = cmd + ['-dump-metrics', metrics_file]

        try:
            logging.info('Running command: %s', cmd)
            with open(log_file, 'a', encoding='utf8') as outfile:
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
                artifact.source_path, cas_info.cas_instance, e, e.stdout,
            )
            return None
        except subprocess.SubprocessError as e:
            logging.warning('Failed to upload %s to CAS instance %s. Skip.\n. Error %s',
                artifact.source_path, cas_info.cas_instance, e)
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


def _path_for_artifact(artifact: ArtifactConfig, working_dir: str) -> [str]:
    if artifact.unzip:
        return ['-zip-path', artifact.source_path]
    if artifact.chunk:
        return ['-file-path', artifact.source_path]
    # TODO(b/250643926) This is a workaround to handle non-directory files.
    tmp_dir = tempfile.mkdtemp(dir=working_dir)
    target_path = os.path.join(tmp_dir, os.path.basename(artifact.source_path))
    shutil.copy(artifact.source_path, target_path)
    return ['-dir-path', tmp_dir]


def _output_results(
        cas_info: CasInfo,
        output_dir: str,
        digests: dict[str, str],
        content_details: list[dict[str, any]],
):
    digests_output = {
        'cas_instance': cas_info.cas_instance,
        'cas_service': cas_info.cas_service,
        'client_version': '.'.join(map(str, cas_info.client_version)),
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


def _upload_wrapper(
    cas_info: CasInfo, log_file: str, task: UploadTask
) -> Tuple[UploadResult, UploadTask]:
    return _upload(
        cas_info,
        task.artifact,
        task.working_dir,
        log_file,
        task.metrics_file,
    ), task

def _glob(dist_dir: str, path: str) -> list[str]:
    if path.startswith("./"):
        return glob.glob(dist_dir + path[1:])
    return glob.glob(dist_dir + '/**/' + path, recursive=True)


def _upload_all_artifacts(cas_info: CasInfo, all_artifacts: list[ArtifactConfig],
    dist_dir: str, working_dir: str, log_file:str, cas_metrics: str, dryrun: bool):
    file_digests = {}
    content_details = []
    skip_files = []
    _add_fallback_artifacts(all_artifacts)

    # Populate upload tasks
    tasks = []
    for artifact in all_artifacts:
        for f in _glob(dist_dir, artifact.source_path):
            if os.path.isdir(f):
                logging.warning('Ignore artifact match (dir): %s', f)
                continue
            rel_path = _get_relative_path(dist_dir, f)
            path = _artifact_path(rel_path, artifact.chunk, artifact.unzip)

            # Avoid redundant upload if multiple ArtifactConfigs share files.
            if path in skip_files:
                continue
            skip_files.append(path)
            if artifact.chunk and (not artifact.chunk_fallback or artifact.unzip):
                # Skip the regular version even it matches other configs.
                skip_files.append(rel_path)

            task_artifact = copy.copy(artifact)
            task_artifact.source_path = f
            _, task_metrics_file = tempfile.mkstemp(dir=working_dir)
            task = UploadTask(task_artifact, path, working_dir, task_metrics_file)
            tasks.append(task)

    # Upload artifacts in parallel
    logging.info('Uploading %d files, max workers = %d', len(tasks), MAX_WORKERS)
    if dryrun:
        for task in tasks:
            print("%-40s=%s" % (task.path, task.artifact.source_path))
        print("Total: %d files." % len(tasks))
        return

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = [executor.submit(_upload_wrapper, cas_info, log_file, task) for task in tasks]

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
                content_details.append({"artifact": task.path, "details": result.content_details})
            else:
                logging.warning('Skip to save the content details of file %s', task.path)

            # Add artifact metrics to cas_metrics
            if os.path.exists(task.metrics_file):
                _add_artifact_metrics(task.metrics_file, cas_metrics)
                os.remove(task.metrics_file)

    _output_results(
        cas_info,
        dist_dir,
        file_digests,
        content_details,
    )


def _add_artifact_metrics(metrics_file: str, cas_metrics: cas_metrics_pb2.CasMetrics):
    try:
        with open(metrics_file, "r", encoding='utf8') as file:
            json_metrics = json.load(file)
            cas_metrics.artifacts.append(
                json_format.ParseDict(json_metrics, cas_metrics_pb2.ArtifactMetrics())
            )
    except FileNotFoundError:
        logging.exception("File not found: %s", metrics_file)
    except json.JSONDecodeError as e:
        logging.exception("Jason decode error: %s for json contents:\n%s", e, file.read())
    except json_format.ParseError as e:  # Catch any other unexpected errors
        logging.exception("Error converting Json to protobuf: %s", e)


def _add_fallback_artifacts(artifacts: list[ArtifactConfig]):
    """Add a fallback artifact if chunking is enabled for an artifact.

    For unzip artifacts, the fallback is the zipped chunked version.
    For the rest, the fallback is the standard version (not chunked).
    """
    for artifact in artifacts:
        if artifact.chunk and artifact.chunk_fallback:
            fallback_artifact = copy.copy(artifact)
            if artifact.unzip:
                fallback_artifact.unzip = False
            else:
                fallback_artifact.chunk = False
            artifacts.append(fallback_artifact)


def _get_relative_path(dir: str, file: str) -> str:
    try:
        return os.path.relpath(file, dir)
    except ValueError as e:
        logging.exception("Error calculating relative path: %s", e)
        return os.path.basename(file)


def _artifact_path(path: str, chunk: bool, unzip: bool) -> str:
    if not chunk:
        return path
    if unzip:
        return CHUNKED_DIR_ARTIFACT_NAME_PREFIX + path
    return CHUNKED_ARTIFACT_NAME_PREFIX + path


def main():
    """Uploads the specified artifacts to CAS."""
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--experiment_artifacts',
        required=False,
        action='append',
        default=[],
        help='Name of configuration which artifact to upload',
    )
    parser.add_argument(
        '--artifacts',
        required=False,
        action='append',
        default=[],
        help='Override preset artifacts, e.g. "--artifacts \'img=./*-img-*zip unzip chunk=False\'"',
    )
    parser.add_argument(
        '--list',
        action='store_true',
        help='List preset artifacts and exit',
    )
    parser.add_argument(
        '--dryrun',
        action='store_true',
        help='List files to upload and exit',
    )
    args = parser.parse_args()

    dist_dir = _get_env_var('DIST_DIR', check=True)
    log_file = os.path.join(dist_dir, LOG_PATH)
    print('content_uploader.py will export logs to:', log_file)
    logging.basicConfig(
        level=logging.DEBUG,
        format='%(asctime)s %(levelname)s %(message)s',
        filename=log_file,
    )
    logging.info('Content uploader version: %s', VERSION)
    logging.info('Environment variables of running server: %s', os.environ)

    try:
        _override_artifacts(args)
        additional_artifacts = _parse_additional_artifacts(args)
        if args.list:
            for name, artifact in ARTIFACTS.items():
                print("%-30s=%s" % (name, artifact))
            return 0

        cas_info = _init_cas_info()

        with tempfile.TemporaryDirectory() as working_dir:
            logging.info('The working dir is %s', working_dir)
            start = time.time()
            cas_metrics = cas_metrics_pb2.CasMetrics()
            _upload_all_artifacts(cas_info, list(ARTIFACTS.values()) + additional_artifacts,
                dist_dir, working_dir, log_file, cas_metrics, args.dryrun)
            elapsed = time.time() - start
            logging.info('Total time of uploading build artifacts to CAS: %d seconds',
                        elapsed)
            cas_metrics.time_ms = int(elapsed * 1000)
            cas_metrics.client_version = '.'.join([str(num) for num in cas_info.client_version])
            cas_metrics.uploader_version = VERSION
            cas_metrics.max_workers = MAX_WORKERS
            serialized_metrics = cas_metrics.SerializeToString()
            if serialized_metrics:
                cas_metrics_file = os.path.join(dist_dir, CAS_METRICS_PATH)
                with open(cas_metrics_file, "wb") as file:
                    file.write(serialized_metrics)
                logging.info('Output cas metrics to: %s', cas_metrics_file)
    except ValueError as e:
        logging.exception("Unexpected error: %s", e)
        return 1


if __name__ == '__main__':
    main()
