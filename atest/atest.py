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

"""
Command line utility for running Android tests through TradeFederation.

atest helps automate the flow of building test modules across the Android
code base and executing the tests via the TradeFederation test harness.

atest is designed to support any test types that can be ran by TradeFederation.
"""

import logging
import os
import subprocess
import sys
import tempfile
import time

import atest_utils
import cli_translator

EXPECTED_VARS = frozenset([
    atest_utils.ANDROID_BUILD_TOP,
    'ANDROID_TARGET_OUT_TESTCASES',
    'OUT'])
EXIT_CODE_ENV_NOT_SETUP = 1
EXIT_CODE_BUILD_FAILURE = 2
BUILD_STEP = 'build'
TEST_STEP = 'test'
TEST_RUN_DIR_PREFIX = 'atest_run_%s_'
HELP_DESC = '''Build and run Android tests locally.

The -b and -t options allow you to specify which steps
you want to run. If none of those options are given, then
all steps are run. If any of these options are provided
then only the listed steps are run.
'''

HELP_TESTS = '''Tests to run.

Ways to identify a test:
MODULE NAME       Examples: CtsJankDeviceTestCases
CLASS NAME        Examples: CtsDeviceJankUi, android.jank.cts.ui.CtsDeviceJankUi
MODULE:CLASS      Examples: CtsJankDeviceTestCases:CtsDeviceJankUi, CtsJankDeviceTestCase:android.jank.cts.ui.CtsDeviceJankUi
INTEGRATION NAME  Examples: example/reboot, native-benchmark
FILE PATH         Examples: ., <rel_or_abs_path>/jank, <rel_or_abs_path>/CtsDeviceJankUi.java

METHODS are specified by appending to class with #.

Method Examples:
    CtsDeviceJankUi#Method1,Method2
    android.jank.cts.ui.CtsDeviceJankUi#Method
    CtsJankDeviceTestCases:CtsDeviceJankUi#Method
    path/to/CtsDeviceJankUi.java#Method
'''

def _parse_args(argv):
    """Parse command line arguments.

    Args:
        argv: A list of arguments.

    Returns:
        An argspace.Namespace class instance holding parsed args.
    """
    import argparse
    parser = argparse.ArgumentParser(
        description=HELP_DESC,
        formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument('tests', nargs='+', help=HELP_TESTS)
    parser.add_argument('-b', '--build', action='append_const', dest='steps',
                        const=BUILD_STEP, help='Run a build.')
    parser.add_argument('-t', '--test', action='append_const', dest='steps',
                        const=TEST_STEP, help='Run the tests.')
    parser.add_argument('-w', '--wait-for-debugger', action='store_true',
                        help='Only for instrumentation tests. Waits for '
                             'debugger prior to execution.')
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Display DEBUG level logging.')
    return parser.parse_args(argv)


def _configure_logging(verbose):
    """Configure the logger.

    Args:
        verbose: A boolean. If true display DEBUG level logs.
    """
    if verbose:
        logging.basicConfig(level=logging.DEBUG)
    else:
        logging.basicConfig(level=logging.INFO)


def _missing_environment_variables():
    """Verify the local environment has been set up to run atest.

    Returns:
        List of strings of any missing environment variables.
    """
    missing = filter(None, [x for x in EXPECTED_VARS if not os.environ.get(x)])
    if missing:
        logging.error('Local environment doesn\'t appear to have been '
                      'initialized. Did you remember to run lunch? Expected '
                      'Environment Variables: %s.', missing)
    return missing


def _is_missing_adb(root_dir=''):
    """Check if system built adb is available.

    TF requires adb and we want to make sure we use the latest built adb (vs.
    system adb that might be too old).

    Args:
        root_dir: A String. Path to the root dir that adb should live in.

    Returns:
        True if adb is missing, False otherwise.
    """
    try:
        output = subprocess.check_output(['which', 'adb'])
    except subprocess.CalledProcessError:
        return True
    # TODO: Check if there is a clever way to determine if system adb is good
    # enough.
    return os.path.commonprefix([output, root_dir]) != root_dir


def make_test_run_dir():
    """Make the test run dir in tmp.

    Returns:
        A string of the dir path.
    """
    utc_epoch_time = int(time.time())
    prefix = TEST_RUN_DIR_PREFIX % utc_epoch_time
    return tempfile.mkdtemp(prefix=prefix)


def run_tests(run_commands):
    """Shell out and execute tradefed run commands.

    Args:
        run_commands: A list of strings of Tradefed run commands.
    """
    logging.info('Running tests')
    # TODO: Build result parser for run command. Until then display raw stdout.
    for run_command in run_commands:
        logging.debug('Executing command: %s', run_command)
        subprocess.check_call(run_command, shell=True, stderr=subprocess.STDOUT)


def main(argv):
    """Entry point of atest script.

    Args:
        argv: A list of arguments.
    """
    args = _parse_args(argv)
    _configure_logging(args.verbose)
    if _missing_environment_variables():
        return EXIT_CODE_ENV_NOT_SETUP
    repo_root = os.environ.get(atest_utils.ANDROID_BUILD_TOP)
    results_dir = make_test_run_dir()
    translator = cli_translator.CLITranslator(results_dir=results_dir,
                                              root_dir=repo_root)
    build_targets, run_commands = translator.translate(args.tests)
    if args.wait_for_debugger:
        run_commands = [cmd + ' --wait-for-debugger' for cmd in run_commands]
    if _is_missing_adb(root_dir=repo_root):
        build_targets.add('adb')
    if not args.steps or BUILD_STEP in args.steps:
        success = atest_utils.build(build_targets, args.verbose)
        if not success:
            return EXIT_CODE_BUILD_FAILURE
    if not args.steps or TEST_STEP in args.steps:
        run_tests(run_commands)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
