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
Utility functions for atest.
"""

import logging
import os
import subprocess
import sys

ANDROID_BUILD_TOP = 'ANDROID_BUILD_TOP'
BUILD_CMD = ['make', '-j', '-C', os.environ.get(ANDROID_BUILD_TOP)]
BASH_RESET_CODE = '\033[0m\n'
# Arbitrary number to limit stdout for failed runs in _run_limited_output.
# Reason for its use is that the make command itself has its own carriage
# return output mechanism that when collected line by line causes the streaming
# full_output list to be extremely large.
FAILED_OUTPUT_LINE_LIMIT = 100


def _run_limited_output(cmd):
    """Runs a given command and streams the output on a single line in stdout.

    Args:
        cmd: A list of strings representing the command to run.

    Raises:
        subprocess.CalledProcessError: When the command exits with a non-0
            exitcode.
    """
    # Send stderr to stdout so we only have to deal with a single pipe.
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    sys.stdout.write('\n')
    # Determine the width of the terminal. We'll need to clear this many
    # characters when carriage returning.
    _, term_width = os.popen('stty size', 'r').read().split()
    term_width = int(term_width)
    white_space = " " * int(term_width)
    full_output = []
    while proc.poll() is None:
        line = proc.stdout.readline()
        # Readline will often return empty strings.
        if not line:
            continue
        full_output.append(line)
        # Trim the line to the width of the terminal.
        # Note: Does not handle terminal resizing, which is probably not worth
        #       checking the width every loop.
        if len(line) >= term_width:
            line = line[:term_width - 1]
        # Clear the last line we outputted.
        sys.stdout.write('\r%s\r' % white_space)
        sys.stdout.write('%s' % line.strip())
        sys.stdout.flush()
    # Reset stdout (on bash) to remove any custom formatting and newline.
    sys.stdout.write(BASH_RESET_CODE)
    sys.stdout.flush()
    # Wait for the Popen to finish completely before checking the returncode.
    proc.wait()
    if proc.returncode != 0:
        output = full_output
        if len(output) >= FAILED_OUTPUT_LINE_LIMIT:
            output = output[-FAILED_OUTPUT_LINE_LIMIT:]
        logging.error('Output (may be trimmed):\n%s', ''.join(output))
        raise subprocess.CalledProcessError(proc.returncode, cmd, output)


def build(build_targets, verbose=False):
    """Shell out and make build_targets.

    Args:
        build_targets: A set of strings of build targets to make.
        verbose: Optional arg. If True output is streamed to the console.
                 If False, only the last line of the build output is outputted.

    Returns:
        Boolean of whether build command was successful.
    """
    logging.info('Building targets: %s', ' '.join(build_targets))
    cmd = BUILD_CMD + list(build_targets)
    logging.debug('Executing command: %s', cmd)
    try:
        if verbose:
            subprocess.check_call(cmd, stderr=subprocess.STDOUT)
        else:
            # TODO: Save output to a log file.
            _run_limited_output(cmd)
        logging.info('Build successful')
        return True
    except subprocess.CalledProcessError as err:
        logging.error('Error building: %s', build_targets)
        if err.output:
            logging.error(err.output)
        return False
