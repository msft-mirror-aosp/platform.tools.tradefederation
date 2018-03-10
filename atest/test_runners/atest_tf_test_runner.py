# Copyright 2017, The Android Open Source Project
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
Atest Tradefed test runner class.
"""

import json
import logging
import os
import subprocess

# pylint: disable=import-error
import atest_utils
import constants
from test_finders import test_info
import test_runner_base


class AtestTradefedTestRunner(test_runner_base.TestRunnerBase):
    """TradeFed Test Runner class."""
    NAME = 'AtestTradefedTestRunner'
    EXECUTABLE = 'atest_tradefed.sh'
    _TF_TEMPLATE = 'template/local_min'
    _RUN_CMD = ('{exe} run commandAndExit {template} --template:map '
                'test=atest {args}')
    _BUILD_REQ = {'tradefed-core'}

    def __init__(self, results_dir, module_info=None, **kwargs):
        """Init stuff for base class."""
        super(AtestTradefedTestRunner, self).__init__(results_dir)
        self.module_info = module_info
        self.run_cmd_dict = {'exe': self.EXECUTABLE,
                             'template': self._TF_TEMPLATE,
                             'args': ''}

    def run_tests(self, test_infos, extra_args):
        """Run the list of test_infos.

        Args:
            test_infos: List of TestInfo.
            extra_args: Dict of extra args to add to test run.
        """
        iterations = 1
        metrics_folder = ''
        if extra_args.get(constants.PRE_PATCH_ITERATIONS):
            iterations = extra_args.pop(constants.PRE_PATCH_ITERATIONS)
            metrics_folder = os.path.join(self.results_dir, 'baseline-metrics')
        elif extra_args.get(constants.POST_PATCH_ITERATIONS):
            iterations = extra_args.pop(constants.POST_PATCH_ITERATIONS)
            metrics_folder = os.path.join(self.results_dir, 'new-metrics')
        filepath = self._create_test_info_file(test_infos)
        run_cmd = self._generate_run_commands(filepath, extra_args, metrics_folder)
        for _ in xrange(iterations):
            super(AtestTradefedTestRunner, self).run(run_cmd)
        if metrics_folder:
            logging.info('Saved metrics in: %s', metrics_folder)

    def host_env_check(self):
        """Check that host env has everything we need.

        We actually can assume the host env is fine because we have the same
        requirements that atest has. Update this to check for android env vars
        if that changes.
        """
        pass

    @staticmethod
    def _is_missing_adb():
        """Check if system built adb is available.

        TF requires adb and we want to make sure we use the latest built adb
        (vs. system adb that might be too old).

        Returns:
            True if adb is missing, False otherwise.
        """
        try:
            output = subprocess.check_output(['which', 'adb'])
        except subprocess.CalledProcessError:
            return True
        # TODO: Check if there is a clever way to determine if system adb is
        # good enough.
        root_dir = os.environ.get(constants.ANDROID_BUILD_TOP)
        return os.path.commonprefix([output, root_dir]) != root_dir

    def get_test_runner_build_reqs(self):
        """Return the build requirements.

        Returns:
            Set of build targets.
        """
        build_req = self._BUILD_REQ
        # Use different base build requirements if google-tf is around.
        if self.module_info.is_module(constants.GTF_MODULE):
            build_req = {constants.GTF_TARGET}
        # Add adb if we can't find it.
        if self._is_missing_adb():
            build_req.add('adb')
        return build_req

    @staticmethod
    def _parse_extra_args(extra_args):
        """Convert the extra args into something tf can understand.

        Args:
            extra_args: Dict of args

        Returns:
            Tuple of args to append and args not supported.
        """
        args_to_append = []
        args_not_supported = []
        for arg in extra_args:
            if constants.WAIT_FOR_DEBUGGER == arg:
                args_to_append.append('--wait-for-debugger')
                continue
            if constants.DISABLE_INSTALL == arg:
                args_to_append.append('--disable-target-preparers')
                continue
            if constants.DISABLE_TEARDOWN == arg:
                args_to_append.append('--disable-teardown')
                continue
            if constants.CUSTOM_ARGS == arg:
                # We might need to sanitize it prior to appending but for now
                # let's just treat it like a simple arg to pass on through.
                args_to_append.extend(extra_args[arg])
                continue
            args_not_supported.append(arg)
        return args_to_append, args_not_supported

    def _generate_run_commands(self, filepath, extra_args, metrics_folder):
        """Generate a list of run commands from TestInfos.

        Args:
            filepath: A string of the filepath to the test_info file.
            extra_Args: A Dict of extra args to append.
            metrics_folder: A string of the filepath to put metrics.

        Returns:
            A string that contains the atest tradefed run command.
        """
        args = ['--test-info-file', filepath]
        if metrics_folder:
            args.extend(['--metrics-folder', metrics_folder])
        if logging.getLogger().isEnabledFor(logging.DEBUG):
            log_level = 'VERBOSE'
        else:
            log_level = 'WARN'
        args.extend(['--log-level', log_level])

        args_to_add, args_not_supported = self._parse_extra_args(extra_args)
        args.extend(args_to_add)
        if args_not_supported:
            logging.info('%s does not support the following args %s',
                         self.EXECUTABLE, args_not_supported)

        args.extend(atest_utils.get_result_server_args())
        self.run_cmd_dict['args'] = ' '.join(args)
        return self._RUN_CMD.format(**self.run_cmd_dict)

    @staticmethod
    def _testinfo_to_tf_dict(t_info):
        """Return dict representation of TestInfo suitable to be saved
        to test_info.json file and loaded by TradeFed's AtestRunner."""
        filters = set()
        for test_filter in t_info.data.get(constants.TI_FILTER, []):
            filters.update(test_filter.to_set_of_tf_strings())
        return {'test': t_info.test_name, 'filters': list(filters)}

    def _flatten_test_infos(self, test_infos):
        """Sort and group test_infos by module_name and sort and group filters
        by class name.

            Example of three test_infos in a set:
                Module1, {(classA, {})}
                Module1, {(classB, {Method1})}
                Module1, {(classB, {Method2}}
            Becomes a set with one element:
                Module1, {(ClassA, {}), (ClassB, {Method1, Method2})}
            Where:
                  Each line is a test_info namedtuple
                  {} = Frozenset
                  () = TestFilter namedtuple

        Args:
            test_infos: A set of TestInfo namedtuples.

        Returns:
            A set of TestInfos flattened.
        """
        results = set()
        key = lambda x: x.test_name
        for module, group in atest_utils.sort_and_group(test_infos, key):
            # module is a string, group is a generator of grouped TestInfos.
            # Module Test, so flatten test_infos:
            no_filters = False
            filters = set()
            test_runner = None
            build_targets = set()
            data = {}
            for test_info_i in group:
                # We can overwrite existing data since it'll mostly just
                # comprise of filters and relative configs.
                data.update(test_info_i.data)
                test_runner = test_info_i.test_runner
                build_targets |= test_info_i.build_targets
                test_filters = test_info_i.data.get(constants.TI_FILTER)
                if not test_filters or no_filters:
                    # test_info wants whole module run, so hardcode no filters.
                    no_filters = True
                    filters = set()
                    continue
                filters |= test_filters
            data[constants.TI_FILTER] = self._flatten_test_filters(filters)
            results.add(
                test_info.TestInfo(test_name=module,
                                   test_runner=test_runner,
                                   build_targets=build_targets,
                                   data=data))
        return results

    @staticmethod
    def _flatten_test_filters(filters):
        """Sort and group test_filters by class_name.

            Example of three test_filters in a frozenset:
                classA, {}
                classB, {Method1}
                classB, {Method2}
            Becomes a frozenset with these elements:
                classA, {}
                classB, {Method1, Method2}
            Where:
                Each line is a TestFilter namedtuple
                {} = Frozenset

        Args:
            filters: A frozenset of test_filters.

        Returns:
            A frozenset of test_filters flattened.
        """
        results = set()
        key = lambda x: x.class_name
        for class_name, group in atest_utils.sort_and_group(filters, key):
            # class_name is a string, group is a generator of TestFilters
            assert class_name is not None
            methods = set()
            for test_filter in group:
                if not test_filter.methods:
                    # Whole class should be run
                    methods = set()
                    break
                methods |= test_filter.methods
            results.add(test_info.TestFilter(class_name, frozenset(methods)))
        return frozenset(results)

    def _create_test_info_file(self, test_infos):
        """

        Args:
            test_infos: A set of TestInfo instances.

        Returns: A string of the filepath.
        """
        test_infos = self._flatten_test_infos(test_infos)
        filepath = os.path.join(self.results_dir, 'test_info.json')
        infos = [self._testinfo_to_tf_dict(t_info) for t_info in test_infos]
        logging.debug('Test info: %s', infos)
        logging.info('Writing test info to: %s', filepath)
        with open(filepath, 'w') as test_info_file:
            json.dump(infos, test_info_file)
        return filepath
