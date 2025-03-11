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

from artifact_manager import ArtifactManager
from artifacts import ARTIFACTS
import cas_metrics_pb2  # type: ignore
from uploader import CasInfo
from uploader import Uploader


VERSION = '1.8'

CAS_UPLOADER_PREBUILT_PATH = 'tools/tradefederation/prebuilts/'
CAS_UPLOADER_PATH = 'tools/content_addressed_storage/prebuilts/'
CAS_UPLOADER_BIN = 'casuploader'

LOG_PATH = 'logs/cas_uploader.log'
CAS_METRICS_PATH = 'logs/cas_metrics.pb'
METRICS_PATH = 'logs/artifact_metrics.json'
MAX_WORKERS_LOWER_BOUND = 5
MAX_WORKERS_UPPER_BOUND = 7
MAX_WORKERS = random.randint(MAX_WORKERS_LOWER_BOUND, MAX_WORKERS_UPPER_BOUND)


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
        cas_metrics = Uploader(cas_info).upload(list(artifacts.values()),
                dist_dir, MAX_WORKERS, args.dryrun)

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
