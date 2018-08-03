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

include $(CLEAR_VARS)

# makefile rules to copy jars to HOST_OUT/tradefed
# so tradefed.sh can automatically add to classpath
deps := $(call copy-many-files,\
  $(call intermediates-dir-for,JAVA_LIBRARIES,tradefed,HOST)/javalib.jar:$(HOST_OUT)/tradefed/tradefed.jar \
  $(HOST_OUT_JAVA_LIBRARIES)/tools-common-prebuilt.jar:$(HOST_OUT)/tradefed/tools-common-prebuilt.jar)

# this dependency ensures the above rule will be executed if jar is installed
$(HOST_OUT_JAVA_LIBRARIES)/tradefed.jar : $(deps)
# The copy rule for loganalysis is in tools/loganalysis/Android.mk
$(HOST_OUT_JAVA_LIBRARIES)/tradefed.jar : $(HOST_OUT)/tradefed/loganalysis.jar

#######################################################
include $(CLEAR_VARS)

# Create a simple alias to build all the TF-related targets
# Note that this is incompatible with `make dist`.  If you want to make
# the distribution, you must run `tapas` with the individual target names.
.PHONY: tradefed-all
tradefed-all: tradefed tradefed-tests tf-prod-tests tf-prod-metatests tradefed_win script_help verify tradefed-contrib

# ====================================
include $(CLEAR_VARS)
# copy tradefed.sh script to host dir

LOCAL_MODULE_TAGS := optional

LOCAL_PREBUILT_EXECUTABLES := tradefed.sh tradefed_win.bat script_help.sh verify.sh run_tf_cmd.sh
include $(BUILD_HOST_PREBUILT)

# Build all sub-directories
include $(call all-makefiles-under,$(LOCAL_PATH))

########################################################
# Zip up the built files and dist it as tradefed.zip
ifneq (,$(filter tradefed tradefed-all, $(TARGET_BUILD_APPS)))

tradefed_dist_host_jars := tradefed tradefed-tests tf-prod-tests emmalib jack-jacoco-reporter loganalysis loganalysis-tests tf-remote-client tradefed-contrib
tradefed_dist_host_jar_files := $(foreach m, $(tradefed_dist_host_jars), $(HOST_OUT_JAVA_LIBRARIES)/$(m).jar)

tradefed_dist_host_exes := tradefed.sh tradefed_win.bat script_help.sh verify.sh run_tf_cmd.sh
tradefed_dist_host_exe_files := $(foreach m, $(tradefed_dist_host_exes), $(BUILD_OUT_EXECUTABLES)/$(m))

tradefed_dist_test_apks := TradeFedUiTestApp TradeFedTestApp DeviceSetupUtil
tradefed_dist_test_apk_files := $(foreach m, $(tradefed_dist_test_apks), $(TARGET_OUT_DATA_APPS)/$(m)/$(m).apk)

tradefed_dist_files := \
    $(tradefed_dist_host_jar_files) \
    $(tradefed_dist_test_apk_files) \
    $(tradefed_dist_host_exe_files)

tradefed_dist_intermediates := $(call intermediates-dir-for,PACKAGING,tradefed_dist,HOST,COMMON)
tradefed_dist_zip := $(tradefed_dist_intermediates)/tradefed.zip
$(tradefed_dist_zip) : $(tradefed_dist_files)
	@echo "Package: $@"
	$(hide) rm -rf $(dir $@) && mkdir -p $(dir $@)
	$(hide) cp -f $^ $(dir $@)
	$(hide) cd $(dir $@) && zip -q $(notdir $@) $(notdir $^)

$(call dist-for-goals, apps_only, $(tradefed_dist_zip))

endif  # tradefed in $(TARGET_BUILD_APPS)
