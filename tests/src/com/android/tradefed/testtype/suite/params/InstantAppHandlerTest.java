/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.testtype.suite.params;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.targetprep.suite.SuiteApkInstaller;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Set;

/** Unit tests for {@link InstantAppHandler}. */
@RunWith(JUnit4.class)
public class InstantAppHandlerTest {

    private InstantAppHandler mHandler;
    private IConfiguration mModuleConfig;

    @Before
    public void setUp() {
        mHandler = new InstantAppHandler();
        mModuleConfig = new Configuration("test", "test");
    }

    private class TestFilterable implements IRemoteTest, ITestAnnotationFilterReceiver {

        public String mReceivedFiltered;

        @Override
        public void addIncludeAnnotation(String annotation) {
            // ignore
        }

        @Override
        public void addExcludeAnnotation(String notAnnotation) {
            mReceivedFiltered = notAnnotation;
        }

        @Override
        public void addAllIncludeAnnotation(Set<String> annotations) {
            // ignore
        }

        @Override
        public void addAllExcludeAnnotation(Set<String> notAnnotations) {
            // ignore
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
            // ignore
        }
    }

    /** Test that when a module configuration go through the handler it gets tuned properly. */
    @Test
    public void testApplySetup() {
        SuiteApkInstaller installer = new SuiteApkInstaller();
        assertFalse(installer.isInstantMode());
        TestFilterable test = new TestFilterable();
        assertNull(test.mReceivedFiltered);
        mModuleConfig.setTest(test);
        mModuleConfig.setTargetPreparer(installer);
        mHandler.applySetup(mModuleConfig);

        // Instant mode gets turned on.
        assertTrue(installer.isInstantMode());
        // Full mode is filtered out.
        assertEquals("android.platform.test.annotations.AppModeFull", test.mReceivedFiltered);
    }
}
