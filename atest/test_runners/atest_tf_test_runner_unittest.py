#!/usr/bin/env python
#
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

"""Unittests for atest_tf_test_runner."""

import os
import unittest
import json
import socket
import mock

# pylint: disable=import-error
import constants
import unittest_constants as uc
import unittest_utils
import atest_tf_test_runner as atf_tr
from test_finders import test_info
from test_runners import test_runner_base

#pylint: disable=protected-access
#pylint: disable=invalid-name
TEST_INFO_DIR = '/tmp/atest_run_1510085893_pi_Nbi'
METRICS_DIR = '%s/baseline-metrics' % TEST_INFO_DIR
METRICS_DIR_ARG = '--metrics-folder %s ' % METRICS_DIR
RUN_CMD_ARGS = '{metrics}--log-level WARN'
RUN_CMD = atf_tr.AtestTradefedTestRunner._RUN_CMD.format(
    exe=atf_tr.AtestTradefedTestRunner.EXECUTABLE,
    template=atf_tr.AtestTradefedTestRunner._TF_TEMPLATE,
    args=RUN_CMD_ARGS)
FULL_CLASS2_NAME = 'android.jank.cts.ui.SomeOtherClass'
CLASS2_FILTER = test_info.TestFilter(FULL_CLASS2_NAME, frozenset())
METHOD2_FILTER = test_info.TestFilter(uc.FULL_CLASS_NAME, frozenset([uc.METHOD2_NAME]))
CLASS2_METHOD_FILTER = test_info.TestFilter(FULL_CLASS2_NAME,
                                            frozenset([uc.METHOD_NAME, uc.METHOD2_NAME]))
MODULE2_INFO = test_info.TestInfo(uc.MODULE2_NAME,
                                  atf_tr.AtestTradefedTestRunner.NAME,
                                  set(),
                                  data={constants.TI_REL_CONFIG: uc.CONFIG2_FILE,
                                        constants.TI_FILTER: frozenset()})
CLASS1_BUILD_TARGETS = {'class_1_build_target'}
CLASS1_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS1_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset([uc.CLASS_FILTER])})
CLASS2_BUILD_TARGETS = {'class_2_build_target'}
CLASS2_INFO = test_info.TestInfo(uc.MODULE_NAME,
                                 atf_tr.AtestTradefedTestRunner.NAME,
                                 CLASS2_BUILD_TARGETS,
                                 data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
                                       constants.TI_FILTER: frozenset([CLASS2_FILTER])})
CLASS1_CLASS2_MODULE_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS | CLASS1_BUILD_TARGETS | CLASS2_BUILD_TARGETS,
    uc.MODULE_DATA)
FLAT_CLASS_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    CLASS1_BUILD_TARGETS | CLASS2_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.CLASS_FILTER, CLASS2_FILTER])})
GTF_INT_CONFIG = os.path.join(uc.GTF_INT_DIR, uc.GTF_INT_NAME + '.xml')
CLASS2_METHOD_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    set(),
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER:
              frozenset([test_info.TestFilter(
                  FULL_CLASS2_NAME, frozenset([uc.METHOD_NAME, uc.METHOD2_NAME]))])})
METHOD_AND_CLASS2_METHOD = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.METHOD_FILTER, CLASS2_METHOD_FILTER])})
METHOD_METHOD2_AND_CLASS2_METHOD = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    uc.MODULE_BUILD_TARGETS,
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([uc.FLAT_METHOD_FILTER, CLASS2_METHOD_FILTER])})
METHOD2_INFO = test_info.TestInfo(
    uc.MODULE_NAME,
    atf_tr.AtestTradefedTestRunner.NAME,
    set(),
    data={constants.TI_REL_CONFIG: uc.CONFIG_FILE,
          constants.TI_FILTER: frozenset([METHOD2_FILTER])})

EVENTS_NORMAL = [
    ('TEST_MODULE_STARTED', {
        'moduleContextFileName':'serial-util11462169742772610436.ser',
        'moduleName':'someTestModule'}),
    ('TEST_RUN_STARTED', {'testCount': 2}),
    ('TEST_STARTED', {'className':'someClassName', 'testName':'someTestName'}),
    ('TEST_ENDED', {'className':'someClassName', 'testName':'someTestName'}),
    ('TEST_STARTED', {'className':'someClassName2',
                      'testName':'someTestName2'}),
    ('TEST_FAILED', {'className':'someClassName2', 'testName':'someTestName2',
                     'trace': 'someTrace'}),
    ('TEST_ENDED', {'className':'someClassName2', 'testName':'someTestName2'}),
    ('TEST_RUN_ENDED', {}),
    ('TEST_MODULE_ENDED', {}),
]

EVENTS_RUN_FAILURE = [
    ('TEST_MODULE_STARTED', {
        'moduleContextFileName': 'serial-util11462169742772610436.ser',
        'moduleName': 'someTestModule'}),
    ('TEST_RUN_STARTED', {'testCount': 2}),
    ('TEST_STARTED', {'className': 'someClassName', 'testName':'someTestName'}),
    ('TEST_RUN_FAILED', {'reason': 'someRunFailureReason'})
]

EVENTS_INVOCATION_FAILURE = [
    ('INVOCATION_FAILED', {'cause': 'someInvocationFailureReason'})
]


class AtestTradefedTestRunnerUnittests(unittest.TestCase):
    """Unit tests for atest_tf_test_runner.py"""

    def setUp(self):
        self.tr = atf_tr.AtestTradefedTestRunner(results_dir=TEST_INFO_DIR)

    def tearDown(self):
        mock.patch.stopall()

    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_start_socket_server')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       'run')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_exec_with_tf_polling')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_create_test_args', return_value=['some_args'])
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_generate_run_command', return_value='some_cmd')
    @mock.patch.object(atf_tr.AtestTradefedTestRunner,
                       '_process_connection', return_value=None)
    @mock.patch('os.killpg', return_value=None)
    @mock.patch('signal.signal', return_value=None)
    def test_run_tests_pretty(self, _signal, _killpg, _process, _run_cmd,
                              _test_args, mock_exec_w_poll, mock_run,
                              mock_start_socket_server):
        """Test _run_tests_pretty method."""
        mock_subproc = mock.Mock()
        mock_run.return_value = mock_subproc
        mock_server = mock.Mock()
        mock_server.getsockname.return_value = ('', '')
        mock_start_socket_server.return_value = mock_server
        mock_reporter = mock.Mock()

        # Test no early TF exit
        mock_exec_w_poll.return_value = ('some_conn', 'some_addr')
        self.tr.run_tests_pretty(['test_infos'], {}, mock_reporter)

        # Test early TF exit
        mock_exec_w_poll.side_effect = atf_tr.TradeFedExitError()
        self.assertRaises(atf_tr.TradeFedExitError, self.tr.run_tests_pretty,
                          ['test_infos'], {}, mock_reporter)

    def test_exec_with_tf_polling(self):
        """Test _exec_with_tf_polling method."""
        mock_socket_func = mock.Mock()
        mock_socket_func.side_effect = [socket.timeout, socket.timeout,
                                        socket.timeout]
        mock_tf_subproc = mock.Mock()
        exit_code = 7
        mock_tf_subproc.poll.side_effect = [None, None, exit]
        mock_tf_subproc.returncode.returns = exit_code
        # First call should raise, because TF exits before socket_func returns
        self.assertRaises(atf_tr.TradeFedExitError,
                          self.tr._exec_with_tf_polling,
                          mock_socket_func, mock_tf_subproc)
        # Second call succeeds because socket_func returns before TF exits
        mock_socket_func.side_effect = [socket.timeout, 'some_return_value']
        mock_tf_subproc.poll.side_effect = [None]
        self.tr._exec_with_tf_polling(mock_socket_func, mock_tf_subproc)

    def test_start_socket_server(self):
        """Test start_socket_server method."""
        server = self.tr._start_socket_server()
        host, port = server.getsockname()
        self.assertEquals(host, atf_tr.SOCKET_HOST)
        self.assertLessEqual(port, 65535)
        self.assertGreaterEqual(port, 1024)
        server.shutdown(socket.SHUT_RDWR)
        server.close()

    @mock.patch.object(atf_tr.AtestTradefedTestRunner, '_process_event')
    def test_process_connection(self, mock_pe):
        """Test _process_connection method."""
        mock_socket = mock.Mock()
        mock_tf_subproc = mock.Mock()
        socket_data = ['%s %s' % (name, json.dumps(data))
                       for name, data in EVENTS_NORMAL]
        socket_data.append('')
        mock_socket.recv.side_effect = socket_data
        self.tr._process_connection(mock_socket, 'fake reporter',
                                    mock_tf_subproc)
        calls = [mock.call(name, data, 'fake reporter', mock.ANY)
                 for name, data in EVENTS_NORMAL]
        mock_pe.assert_has_calls(calls)

    def test_process_event_normal_results(self):
        """Test _process_event method for normal test results."""
        mock_reporter = mock.Mock()
        state = atf_tr.CONNECTION_STATE.copy()
        for name, data in EVENTS_NORMAL:
            self.tr._process_event(name, data, mock_reporter, state)
        call1 = mock.call(test_runner_base.TestResult(
            runner_name=self.tr.NAME,
            group_name='someTestModule',
            test_name='someClassName#someTestName',
            status=test_runner_base.PASSED_STATUS,
            details=None,
            runner_total=None,
            group_total=2
        ))
        call2 = mock.call(test_runner_base.TestResult(
            runner_name=self.tr.NAME,
            group_name='someTestModule',
            test_name='someClassName2#someTestName2',
            status=test_runner_base.FAILED_STATUS,
            details='someTrace',
            runner_total=None,
            group_total=2
        ))
        mock_reporter.process_test_result.assert_has_calls([call1, call2])

    def test_process_event_run_failure(self):
        """Test _process_event method run failure."""
        mock_reporter = mock.Mock()
        state = atf_tr.CONNECTION_STATE.copy()
        for name, data in EVENTS_RUN_FAILURE:
            self.tr._process_event(name, data, mock_reporter, state)
        call = mock.call(test_runner_base.TestResult(
            runner_name=self.tr.NAME,
            group_name='someTestModule',
            test_name='someClassName#someTestName',
            status=test_runner_base.ERROR_STATUS,
            details='someRunFailureReason',
            runner_total=None,
            group_total=2
        ))
        mock_reporter.process_test_result.assert_has_calls([call])

    def test_process_event_invocation_failure(self):
        """Test _process_event method with invocation failure."""
        mock_reporter = mock.Mock()
        state = atf_tr.CONNECTION_STATE.copy()
        for name, data in EVENTS_INVOCATION_FAILURE:
            self.tr._process_event(name, data, mock_reporter, state)
        call = mock.call(test_runner_base.TestResult(
            runner_name=self.tr.NAME,
            group_name=None,
            test_name=None,
            status=test_runner_base.ERROR_STATUS,
            details='someInvocationFailureReason',
            runner_total=None,
            group_total=None
        ))
        mock_reporter.process_test_result.assert_has_calls([call])

    @mock.patch('atest_utils.get_result_server_args')
    def test_generate_run_command(self, mock_resultargs):
        """Test _generate_run_command method."""
        # Basic Run Cmd
        mock_resultargs.return_value = []
        unittest_utils.assert_strict_equal(
            self,
            self.tr._generate_run_command([], {}, ''),
            RUN_CMD.format(metrics=''))
        unittest_utils.assert_strict_equal(
            self,
            self.tr._generate_run_command([], {}, METRICS_DIR),
            RUN_CMD.format(metrics=METRICS_DIR_ARG))
        # Run cmd with result server args.
        result_arg = '--result_arg'
        mock_resultargs.return_value = [result_arg]
        unittest_utils.assert_strict_equal(
            self,
            self.tr._generate_run_command([], {}, ''),
            RUN_CMD.format(metrics='') + ' ' + result_arg)

    def test_flatten_test_filters(self):
        """Test _flatten_test_filters method."""
        # No Flattening
        filters = self.tr._flatten_test_filters({uc.CLASS_FILTER})
        unittest_utils.assert_strict_equal(self, frozenset([uc.CLASS_FILTER]),
                                           filters)
        filters = self.tr._flatten_test_filters({CLASS2_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([CLASS2_FILTER]), filters)
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([uc.METHOD_FILTER]), filters)
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 CLASS2_METHOD_FILTER})
        unittest_utils.assert_strict_equal(
            self, frozenset([uc.METHOD_FILTER, CLASS2_METHOD_FILTER]), filters)
        # Flattening
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 METHOD2_FILTER})
        unittest_utils.assert_strict_equal(
            self, filters, frozenset([uc.FLAT_METHOD_FILTER]))
        filters = self.tr._flatten_test_filters({uc.METHOD_FILTER,
                                                 METHOD2_FILTER,
                                                 CLASS2_METHOD_FILTER,})
        unittest_utils.assert_strict_equal(
            self, filters, frozenset([uc.FLAT_METHOD_FILTER,
                                      CLASS2_METHOD_FILTER]))

    def test_flatten_test_infos(self):
        """Test _flatten_test_infos method."""
        # No Flattening
        test_infos = self.tr._flatten_test_infos({uc.MODULE_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.MODULE_INFO})

        test_infos = self.tr._flatten_test_infos([uc.MODULE_INFO, MODULE2_INFO])
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {uc.MODULE_INFO, MODULE2_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {CLASS1_INFO})

        test_infos = self.tr._flatten_test_infos({uc.INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.METHOD_INFO})

        # Flattening
        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {FLAT_CLASS_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, uc.INT_INFO,
                                                  CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO,
                                                   FLAT_CLASS_INFO})

        test_infos = self.tr._flatten_test_infos({CLASS1_INFO, uc.MODULE_INFO,
                                                  CLASS2_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {CLASS1_CLASS2_MODULE_INFO})

        test_infos = self.tr._flatten_test_infos({MODULE2_INFO, uc.INT_INFO,
                                                  CLASS1_INFO, CLASS2_INFO,
                                                  uc.GTF_INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {uc.INT_INFO, uc.GTF_INT_INFO,
                                                   FLAT_CLASS_INFO,
                                                   MODULE2_INFO})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO,
                                                  CLASS2_METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(self, test_infos,
                                                  {METHOD_AND_CLASS2_METHOD})

        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO, METHOD2_INFO,
                                                  CLASS2_METHOD_INFO})
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {METHOD_METHOD2_AND_CLASS2_METHOD})
        test_infos = self.tr._flatten_test_infos({uc.METHOD_INFO, METHOD2_INFO,
                                                  CLASS2_METHOD_INFO,
                                                  MODULE2_INFO,
                                                  uc.INT_INFO})
        unittest_utils.assert_equal_testinfo_sets(
            self, test_infos, {uc.INT_INFO, MODULE2_INFO,
                               METHOD_METHOD2_AND_CLASS2_METHOD})


if __name__ == '__main__':
    unittest.main()
