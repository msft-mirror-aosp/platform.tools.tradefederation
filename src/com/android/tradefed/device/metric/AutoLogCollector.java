/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.device.metric;

import java.lang.reflect.InvocationTargetException;

/** Enumeration describing which collector can automatically be handled by the harness. */
public enum AutoLogCollector {
    BUGREPORTZ_ON_FAILURE(BugreportzOnFailureCollector.class),
    BUGREPORTZ_ON_TESTCASE_FAILURE(BugreportzOnTestCaseFailureCollector.class),
    CLANG_COVERAGE(ClangCodeCoverageCollector.class),
    // TODO: Remove this temporary value after the new collector is verified to work as expected.
    CODE_COVERAGE(CodeCoverageCollector.class),
    GCOV_COVERAGE(GcovCodeCoverageCollector.class),
    GCOV_KERNEL_COVERAGE(GcovKernelCodeCoverageCollector.class),
    HOSTLOG_ON_FAILURE(DebugHostLogOnFailureCollector.class),
    JAVA_COVERAGE(JavaCodeCoverageCollector.class),
    LOGCAT_ON_FAILURE(LogcatOnFailureCollector.class),
    SCREENSHOT_ON_FAILURE(ScreenshotOnFailureCollector.class),
    MODULE_LOGCAT(ModuleLogcatCollector.class),
    DEVICE_TRACE(DeviceTraceCollector.class);

    private Class<?> mClass;

    private AutoLogCollector(Class<? extends BaseDeviceMetricCollector> className) {
        mClass = className;
    }

    /** Returns the instance of collector associated with the {@link AutoLogCollector} value. */
    public BaseDeviceMetricCollector getInstanceForValue() {
        try {
            Object o = mClass.getDeclaredConstructor().newInstance();
            return (BaseDeviceMetricCollector) o;
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
