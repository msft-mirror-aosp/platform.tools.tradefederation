#
# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import adb_handler

class AndroidTestDevice(object):
    """Class representing an android device.

    Each instance represents a different device connected to adb.
    """

    def __init__(self, serial=None):
        # TODO: Implement and flesh out the device interface
        self.serial = serial
        self.adb = adb_handler.AdbHandler(serial)

    def executeShellCommand(self, cmd):
        """Convenience method to call the adb wrapper to execute a shell command.

        Args:
            cmd: The command to be executed in 'adb shell'

        Returns:
            The stdout of the command if succeed. Or raise AdbError if failed.
        """
        return self.adb.exec_shell_command(cmd)