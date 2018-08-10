#!/usr/bin/env python
#
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

"""Unittests for atest."""

import sys
import unittest
import mock

import atest
import constants
import module_info
from test_finders import test_info

if sys.version_info[0] == 2:
    from StringIO import StringIO
else:
    from io import StringIO

#pylint: disable=protected-access
class AtestUnittests(unittest.TestCase):
    """Unit tests for atest.py"""

    @mock.patch('os.environ.get', return_value=None)
    def test_missing_environment_variables_uninitialized(self, _):
        """Test _has_environment_variables when no env vars."""
        self.assertTrue(atest._missing_environment_variables())

    @mock.patch('os.environ.get', return_value='out/testcases/')
    def test_missing_environment_variables_initialized(self, _):
        """Test _has_environment_variables when env vars."""
        self.assertFalse(atest._missing_environment_variables())

    def test_parse_args(self):
        """Test _parse_args parses command line args."""
        test_one = 'test_name_one'
        test_two = 'test_name_two'
        custom_arg = '--custom_arg'
        custom_arg_val = 'custom_arg_val'
        pos_custom_arg = 'pos_custom_arg'

        # Test out test and custom args are properly retrieved.
        args = [test_one, test_two, '--', custom_arg, custom_arg_val]
        parsed_args = atest._parse_args(args)
        self.assertEqual(parsed_args.tests, [test_one, test_two])
        self.assertEqual(parsed_args.custom_args, [custom_arg, custom_arg_val])

        # Test out custom positional args with no test args.
        args = ['--', pos_custom_arg, custom_arg_val]
        parsed_args = atest._parse_args(args)
        self.assertEqual(parsed_args.tests, [])
        self.assertEqual(parsed_args.custom_args, [pos_custom_arg,
                                                   custom_arg_val])

    def test_has_valid_test_mapping_args(self):
        """Test _has_valid_test_mapping_args mehod."""
        # Test test mapping related args are not mixed with incompatible args.
        options_no_tm_support = [
            ('--generate-baseline', '5'),
            ('--detect-regression', 'path'),
            ('--generate-new-metrics', '5')
        ]
        tm_options = [
            '--test-mapping',
            '--include-subdirs'
        ]

        for tm_option in tm_options:
            for no_tm_option, no_tm_option_value in options_no_tm_support:
                args = [tm_option, no_tm_option]
                if no_tm_option_value != None:
                    args.append(no_tm_option_value)
                parsed_args = atest._parse_args(args)
                self.assertFalse(
                    atest._has_valid_test_mapping_args(parsed_args),
                    'Failed to validate: %s' % args)

    @mock.patch('json.load', return_value={})
    @mock.patch('__builtin__.open', new_callable=mock.mock_open)
    @mock.patch('os.path.isfile', return_value=True)
    @mock.patch('atest_utils._has_colors', return_value=True)
    @mock.patch.object(module_info.ModuleInfo, 'get_module_info',)
    def test_print_module_info_from_module_name(self, mock_get_module_info,
                                                _mock_has_colors, _isfile,
                                                _open, _json):
        """Test _print_module_info_from_module_name mehod."""
        mod_one_name = 'mod1'
        mod_one_path = ['src/path/mod1']
        mod_one_installed = ['installed/path/mod1']
        mod_one_suites = ['device_test_mod1', 'native_test_mod1']
        mod_one = {constants.MODULE_NAME: mod_one_name,
                   constants.MODULE_PATH: mod_one_path,
                   constants.MODULE_INSTALLED: mod_one_installed,
                   constants.MODULE_COMPATIBILITY_SUITES: mod_one_suites}

        # Case 1: The testing_module('mod_one') can be found in module_info.
        mock_get_module_info.return_value = mod_one
        capture_output = StringIO()
        sys.stdout = capture_output
        mod_info = module_info.ModuleInfo()
        # Check return value = True, since 'mod_one' can be found.
        self.assertTrue(
            atest._print_module_info_from_module_name(mod_info, mod_one_name))
        # Assign sys.stdout back to default.
        sys.stdout = sys.__stdout__
        correct_output = ('\x1b[1;42mmod1\x1b[0m\n'
                          '\x1b[1;44m\tCompatibility suite\x1b[0m\n'
                          '\x1b[1;34m\t\tdevice_test_mod1\x1b[0m\n'
                          '\x1b[1;34m\t\tnative_test_mod1\x1b[0m\n'
                          '\x1b[1;44m\tSource code path\x1b[0m\n'
                          '\x1b[1;34m\t\tsrc/path/mod1\x1b[0m\n'
                          '\x1b[1;44m\tInstalled path\x1b[0m\n'
                          '\x1b[1;34m\t\tinstalled/path/mod1\x1b[0m\n')
        # Check the function correctly printed module_info in color to stdout
        self.assertEqual(capture_output.getvalue(), correct_output)

        # Case 2: The testing_module('mod_one') can NOT be found in module_info.
        mock_get_module_info.return_value = None
        capture_output = StringIO()
        sys.stdout = capture_output
        # Check return value = False, since 'mod_one' can NOT be found.
        self.assertFalse(
            atest._print_module_info_from_module_name(mod_info, mod_one_name))
        # Assign sys.stdout back to default.
        sys.stdout = sys.__stdout__
        null_output = ''
        # Check if no module_info, then nothing printed to screen.
        self.assertEqual(capture_output.getvalue(), null_output)

    @mock.patch('json.load', return_value={})
    @mock.patch('__builtin__.open', new_callable=mock.mock_open)
    @mock.patch('os.path.isfile', return_value=True)
    @mock.patch('atest_utils._has_colors', return_value=True)
    @mock.patch.object(module_info.ModuleInfo, 'get_module_info',)
    def test_print_test_info(self, mock_get_module_info, _mock_has_colors,
                             _isfile, _open, _json):
        """Test _print_test_info mehod."""
        mod_one_name = 'mod1'
        mod_one = {constants.MODULE_NAME: mod_one_name,
                   constants.MODULE_PATH: ['path/mod1'],
                   constants.MODULE_INSTALLED: ['installed/mod1'],
                   constants.MODULE_COMPATIBILITY_SUITES: ['suite_mod1']}
        mod_two_name = 'mod2'
        mod_two = {constants.MODULE_NAME: mod_two_name,
                   constants.MODULE_PATH: ['path/mod2'],
                   constants.MODULE_INSTALLED: ['installed/mod2'],
                   constants.MODULE_COMPATIBILITY_SUITES: ['suite_mod2']}
        mod_three_name = 'mod3'
        mod_three = {constants.MODULE_NAME: mod_two_name,
                     constants.MODULE_PATH: ['path/mod3'],
                     constants.MODULE_INSTALLED: ['installed/mod3'],
                     constants.MODULE_COMPATIBILITY_SUITES: ['suite_mod3']}
        test_name = mod_one_name
        build_targets = set([mod_one_name, mod_two_name, mod_three_name])
        t_info = test_info.TestInfo(test_name, 'mock_runner', build_targets)
        test_infos = set([t_info])

        # The _print_test_info() will print the module_info of the test_info's
        # test_name first. Then, print its related build targets. If the build
        # target be printed before(e.g. build_target == test_info's test_name),
        # it will skip it and print the next build_target.
        # Since the build_targets of test_info are mod_one, mod_two, and
        # mod_three, it will print mod_one first, then mod_two, and mod_three.
        #
        # _print_test_info() calls _print_module_info_from_module_name() to
        # print the module_info. And _print_module_info_from_module_name()
        # calls get_module_info() to get the module_info. So we can mock
        # get_module_info() to achieve that.
        mock_get_module_info.side_effect = [mod_one, mod_two, mod_three]

        capture_output = StringIO()
        sys.stdout = capture_output
        mod_info = module_info.ModuleInfo()
        atest._print_test_info(mod_info, test_infos)
        # Assign sys.stdout back to default.
        sys.stdout = sys.__stdout__
        correct_output = ("\x1b[1;42mmod1\x1b[0m\n"
                          "\x1b[1;44m\tCompatibility suite\x1b[0m\n"
                          "\x1b[1;34m\t\tsuite_mod1\x1b[0m\n"
                          "\x1b[1;44m\tSource code path\x1b[0m\n"
                          "\x1b[1;34m\t\tpath/mod1\x1b[0m\n"
                          "\x1b[1;44m\tInstalled path\x1b[0m\n"
                          "\x1b[1;34m\t\tinstalled/mod1\x1b[0m\n"
                          "\x1b[1;45m\tRelated build targets\x1b[0m\n"
                          "\x1b[1;33m\t\tset(['mod1', 'mod2', 'mod3'])\x1b[0m\n"
                          "\x1b[1;42mmod2\x1b[0m\n"
                          "\x1b[1;44m\tCompatibility suite\x1b[0m\n"
                          "\x1b[1;34m\t\tsuite_mod2\x1b[0m\n"
                          "\x1b[1;44m\tSource code path\x1b[0m\n"
                          "\x1b[1;34m\t\tpath/mod2\x1b[0m\n"
                          "\x1b[1;44m\tInstalled path\x1b[0m\n"
                          "\x1b[1;34m\t\tinstalled/mod2\x1b[0m\n"
                          "\x1b[1;42mmod3\x1b[0m\n"
                          "\x1b[1;44m\tCompatibility suite\x1b[0m\n"
                          "\x1b[1;34m\t\tsuite_mod3\x1b[0m\n"
                          "\x1b[1;44m\tSource code path\x1b[0m\n"
                          "\x1b[1;34m\t\tpath/mod3\x1b[0m\n"
                          "\x1b[1;44m\tInstalled path\x1b[0m\n"
                          "\x1b[1;34m\t\tinstalled/mod3\x1b[0m\n"
                          "\x1b[1;37m\x1b[0m\n")
        self.assertEqual(capture_output.getvalue(), correct_output)

if __name__ == '__main__':
    unittest.main()
