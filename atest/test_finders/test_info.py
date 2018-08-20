# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
TestInfo class.
"""

from collections import namedtuple

# pylint: disable=import-error
import constants


TestFilterBase = namedtuple('TestFilter', ['class_name', 'methods'])


class TestInfo(object):
    """Information needed to identify and run a test."""

    # pylint: disable=too-many-arguments
    def __init__(self, test_name, test_runner, build_targets, data=None,
                 suite=None, module_class=None, install_locations=None):
        """Init for TestInfo.

        Args:
            test_name: String of test name.
            test_runner: String of test runner.
            build_targets: Set of build targets.
            data: Dict of data for test runners to use.
            suite: Suite for test runners to use.
            module_class: A list of test classes. It's a snippet of class
                        in module_info. e.g. ["EXECUTABLES",  "NATIVE_TESTS"]
            install_locations: Set of install locations.
                        e.g. set(['host', 'device'])
        """
        self.test_name = test_name
        self.test_runner = test_runner
        self.build_targets = build_targets
        self.data = data if data else {}
        self.suite = suite
        self.module_class = module_class if module_class else []
        self.install_locations = (install_locations if install_locations
                                  else set())

    def __str__(self):
        return ('test_name: %s - test_runner:%s - build_targets:%s - data:%s - '
                'suite:%s - module_class: %s - install_locations:%s' % (
                    self.test_name, self.test_runner, self.build_targets,
                    self.data, self.suite, self.module_class,
                    self.install_locations))

    def get_supported_exec_mode(self):
        """Get the supported execution mode of the test.

        Determine the test supports which execution mode by strategy:
        Robolectric test --> 'both'
        JAVA_LIBRARIES test installed in both target and host --> 'both',
            otherwise --> 'device'.
        Not native tests or installed only in out/target --> 'device'
        Installed only in out/host --> 'host'
        Installed under host and target --> 'both'

        Return:
            String of execution mode.
        """
        if not self.module_class:
            return constants.DEVICE_TEST
        # Let Robolectric test support both.
        if constants.MODULE_CLASS_ROBOLECTRIC in self.module_class:
            return constants.BOTH_TEST
        # JAVA_LIBRARIES : if build for both side, support both. Otherwise,
        # device-only.
        if constants.MODULE_CLASS_JAVA_LIBRARIES in self.module_class:
            if len(self.install_locations) == 2:
                return constants.BOTH_TEST
            return constants.DEVICE_TEST
        if not self.install_locations:
            return constants.DEVICE_TEST
        # Non-Native test runs on device-only.
        if constants.MODULE_CLASS_NATIVE_TESTS not in self.module_class:
            return constants.DEVICE_TEST
        # Native test returns its install path locations.
        if len(self.install_locations) == 1:
            return list(self.install_locations)[0]
        return constants.BOTH_TEST


class TestFilter(TestFilterBase):
    """Information needed to filter a test in Tradefed"""

    def to_set_of_tf_strings(self):
        """Return TestFilter as set of strings in TradeFed filter format."""
        if self.methods:
            return {'%s#%s' % (self.class_name, m) for m in self.methods}
        return {self.class_name}
