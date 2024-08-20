# Copyright (C) 2010 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)
COMPATIBILITY.tradefed_tests_dir := \
  $(COMPATIBILITY.tradefed_tests_dir) $(LOCAL_PATH)/res/config $(LOCAL_PATH)/javatests/res/config

include $(CLEAR_VARS)

# makefile rules to copy jars to HOST_OUT/tradefed
# so tradefed.sh can automatically add to classpath

$(HOST_OUT_JAVA_LIBRARIES)/tradefed.jar : $(HOST_OUT)/tradefed/loganalysis.jar

#######################################################

# Create a simple alias to build all the TF-related targets
# Note that this is incompatible with `make dist`.  If you want to make
# the distribution, you must run `tapas` with the individual target names.
.PHONY: tradefed-core
tradefed-core: tradefed tradefed-test-framework atest_tradefed.sh tradefed-contrib script_help.sh tradefed.sh

.PHONY: tradefed-all
tradefed-all: tradefed-core tradefed-tests tradefed_win compatibility-host-util compatibility-tradefed casuploader tradefed-avd-util-tests
