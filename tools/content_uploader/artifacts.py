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

"""A preset dictionary of artifacts for all branches."""

import uploader

ARTIFACTS = {
    # test_suite targets
    'android-catbox': uploader.ArtifactConfig(
        'android-catbox.zip', True, exclude_filters=['android-catbox/jdk/.*']
    ),
    'android-csuite': uploader.ArtifactConfig(
        'android-csuite.zip', True, exclude_filters=['android-csuite/jdk/.*']
    ),
    'android-cts': uploader.ArtifactConfig(
        'android-cts.zip', True, exclude_filters=['android-cts/jdk/.*']
    ),
    'android-gcatbox': uploader.ArtifactConfig(
        'android-gcatbox.zip', True, exclude_filters=['android-gcatbox/jdk/.*']
    ),
    'android-gts': uploader.ArtifactConfig(
        'android-gts.zip', True, exclude_filters=['android-gts/jdk/.*']
    ),
    'android-mcts': uploader.ArtifactConfig('android-mcts.zip', True),
    'android-mts': uploader.ArtifactConfig(
        'android-mts.zip', True, exclude_filters=['android-mts/jdk/.*']
    ),
    'android-pts': uploader.ArtifactConfig(
        'android-pts.zip', True, exclude_filters=['android-pts/jdk/.*']
    ),
    'android-sts': uploader.ArtifactConfig('android-sts.zip', True),
    'android-tvts': uploader.ArtifactConfig(
        'android-tvts.zip', True, exclude_filters=['android-tvts/jdk/.*']
    ),
    'android-vts': uploader.ArtifactConfig('android-vts.zip', True),
    'android-wts': uploader.ArtifactConfig(
        'android-wts.zip', True, exclude_filters=['android-wts/jdk/.*']
    ),
    'art-host-tests': uploader.ArtifactConfig('art-host-tests.zip', True),
    'bazel-test-suite': uploader.ArtifactConfig('bazel-test-suite.zip', True),
    'host-unit-tests': uploader.ArtifactConfig('host-unit-tests.zip', True),
    'general-tests': uploader.ArtifactConfig('general-tests.zip', True),
    'general-tests_configs': uploader.ArtifactConfig(
        'general-tests_configs.zip', True
    ),
    'general-tests_host-shared-libs': uploader.ArtifactConfig(
        'general-tests_host-shared-libs.zip', True
    ),
    'tradefed': uploader.ArtifactConfig('tradefed.zip', True),
    'google-tradefed': uploader.ArtifactConfig('google-tradefed.zip', True),
    'robolectric-tests': uploader.ArtifactConfig('robolectric-tests.zip', True),
    'ravenwood-tests': uploader.ArtifactConfig('ravenwood-tests.zip', True),
    'test_mappings': uploader.ArtifactConfig('test_mappings.zip', True),

    # Mainline artifacts
    'apex': uploader.ArtifactConfig('*.apex', False),
    'apk': uploader.ArtifactConfig('*.apk', False),

    # Device target artifacts
    'androidTest': uploader.ArtifactConfig('androidTest.zip', True),
    'device-tests': uploader.ArtifactConfig('device-tests.zip', True),
    'device-tests_configs': uploader.ArtifactConfig(
        'device-tests_configs.zip', True
    ),
    'device-tests_host-shared-libs': uploader.ArtifactConfig(
        'device-tests_host-shared-libs.zip', True
    ),
    'performance-tests': uploader.ArtifactConfig('performance-tests.zip', True),
    'device-platinum-tests': uploader.ArtifactConfig(
        'device-platinum-tests.zip', True
    ),
    'device-platinum-tests_configs': uploader.ArtifactConfig(
        'device-platinum-tests_configs.zip', True
    ),
    'device-platinum-tests_host-shared-libs': uploader.ArtifactConfig(
        'device-platinum-tests_host-shared-libs.zip', True
    ),
    'camera-hal-tests': uploader.ArtifactConfig('camera-hal-tests.zip', True),
    'camera-hal-tests_configs': uploader.ArtifactConfig(
        'camera-hal-tests_configs.zip', True
    ),
    'camera-hal-tests_host-shared-libs': uploader.ArtifactConfig(
        'camera-hal-tests_host-shared-libs.zip', True
    ),
    'device-pixel-tests': uploader.ArtifactConfig(
        'device-pixel-tests.zip', True
    ),
    'device-pixel-tests_configs': uploader.ArtifactConfig(
        'device-pixel-tests_configs.zip', True
    ),
    'device-pixel-tests_host-shared-libs': uploader.ArtifactConfig(
        'device-pixel-tests_host-shared-libs.zip', True
    ),
    'automotive-tests': uploader.ArtifactConfig('automotive-tests.zip', True),
    'automotive-general-tests': uploader.ArtifactConfig(
        'automotive-general-tests.zip', True
    ),
    'automotive-sdv-tests': uploader.ArtifactConfig(
        'automotive-sdv-tests.zip', True
    ),
    'automotive-sdv-tests_configs': uploader.ArtifactConfig(
        'automotive-sdv-tests_configs.zip', True
    ),
    'tests': uploader.ArtifactConfig('*-tests-*zip', True),
    'continuous_instrumentation_tests': uploader.ArtifactConfig(
        '*-continuous_instrumentation_tests-*zip', True
    ),
    'continuous_instrumentation_metric_tests': uploader.ArtifactConfig(
        '*-continuous_instrumentation_metric_tests-*zip', True
    ),
    'continuous_native_tests': uploader.ArtifactConfig(
        '*-continuous_native_tests-*zip', True
    ),
    'cvd-host_package': uploader.ArtifactConfig(
        'cvd-host_package.tar.gz', False
    ),
    'bootloader': uploader.ArtifactConfig('bootloader.img', False),
    'radio': uploader.ArtifactConfig('radio.img', False),
    'target_files': uploader.ArtifactConfig('*-target_files-*.zip', True),
    'img': uploader.ArtifactConfig(
        '*-img-*zip', False, chunk=True, chunk_dir=True
    ),
}
