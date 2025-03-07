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
import glob
import logging
import os
import random
import re
import subprocess
import time

import cas_metrics_pb2  # type: ignore
from artifact_manager import ArtifactManager
from uploader import ArtifactConfig
from uploader import CasInfo
from uploader import Uploader


VERSION = '1.6'

CAS_UPLOADER_PREBUILT_PATH = 'tools/tradefederation/prebuilts/'
CAS_UPLOADER_PATH = 'tools/content_addressed_storage/prebuilts/'
CAS_UPLOADER_BIN = 'casuploader'

LOG_PATH = 'logs/cas_uploader.log'
CAS_METRICS_PATH = 'logs/cas_metrics.pb'
METRICS_PATH = 'logs/artifact_metrics.json'
MAX_WORKERS_LOWER_BOUND = 2
MAX_WORKERS_UPPER_BOUND = 6
MAX_WORKERS = random.randint(MAX_WORKERS_LOWER_BOUND, MAX_WORKERS_UPPER_BOUND)

# Configurations of artifacts to upload to CAS.
#
# Override the preset artifacts with the "--artifacts" flag. Examples:
# 1. To upload img files with path relative to DIST_DIR (no '/**/'), override source_path with:
#   --artifacts 'img=./*-img-*zip'
# 2. To upload img files as chunked version only:
#   --artifacts 'img=*-img-*zip' standard=False chunk=True
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
    'target_files': ArtifactConfig('*-target_files-*zip', True),
    'img': ArtifactConfig('*-img-*zip', False, chunk=True, chunk_dir=True)
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
        start = time.time()

        args = parser.parse_args()
        artifacts = ArtifactManager(ARTIFACTS).override_artifacts(args).artifacts()
        if args.list:
            for name, artifact in artifacts.items():
                print(f"{name:<30}={artifact}")

        cas_info = _init_cas_info()
        cas_metrics = cas_metrics_pb2.CasMetrics()
        Uploader(cas_info, log_file).upload(list(artifacts.values()),
                dist_dir, cas_metrics, MAX_WORKERS, args.dryrun)

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


if __name__ == '__main__':
    main()
