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
package com.android.tradefed.testtype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.config.DynamicRemoteFileResolver.FileResolverLoader;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.config.remote.GcsRemoteFileResolver;
import com.android.tradefed.config.remote.IRemoteFileResolver;
import com.android.tradefed.config.remote.IRemoteFileResolver.RemoteFileResolverArgs;
import com.android.tradefed.config.remote.IRemoteFileResolver.ResolvedFile;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;

import com.google.common.collect.ImmutableMap;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.model.InitializationError;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link DeviceJUnit4ClassRunner}. */
@RunWith(JUnit4.class)
public class DeviceJUnit4ClassRunnerTest {

    private static final File FAKE_REMOTE_FILE_PATH = new File("gs://bucket/test/file.txt");

    /** Class that allow testing. */
    public static class TestableRunner extends DeviceJUnit4ClassRunner {

        public TestableRunner(Class<?> klass) throws InitializationError {
            super(klass);
        }

        @Override
        protected DynamicRemoteFileResolver createResolver() {
            IRemoteFileResolver mockResolver = Mockito.mock(IRemoteFileResolver.class);
            try {
                doReturn(new ResolvedFile(new File("/downloaded/somewhere")))
                        .when(mockResolver)
                        .resolveRemoteFile((RemoteFileResolverArgs) Mockito.any());
            } catch (BuildRetrievalError e) {
                CLog.e(e);
            }
            FileResolverLoader resolverLoader =
                    new FileResolverLoader() {
                        @Override
                        public IRemoteFileResolver load(String scheme, Map<String, String> config) {
                            return ImmutableMap.of(GcsRemoteFileResolver.PROTOCOL, mockResolver)
                                    .get(scheme);
                        }
                    };
            return new DynamicRemoteFileResolver(resolverLoader);
        }
    }

    @RunWith(TestableRunner.class)
    public static class Junit4TestClass {

        public Junit4TestClass() {}

        @Option(name = "dynamic-option")
        public File mOption = FAKE_REMOTE_FILE_PATH;

        @Test
        public void testPass() {
            assertNotNull(mOption);
            assertNotEquals(FAKE_REMOTE_FILE_PATH, mOption);
        }

        public static int mBeforeClassRunCount;

        @BeforeClass
        public static void beforeClass() {
            CLog.d("beforeClass");
            mBeforeClassRunCount++;
        }

        public static int mBeforeClassWithInfoRunCount;

        @BeforeClassWithInfo
        public static void beforeClassWithInfo(TestInformation testInfo) {
            CLog.d("beforeClassWithInfo");
            mBeforeClassWithInfoRunCount++;
            assertNotNull(testInfo);
        }

        public static int mAfterClassRunCount;

        @AfterClass
        public static void afterClass() {
            CLog.d("afterClass");
            mAfterClassRunCount++;
        }

        public static int mAfterClassWithInfoRunCount;

        @AfterClassWithInfo
        public static void afterClassWithInfo(TestInformation testInfo) {
            CLog.d("afterClassWithInfo");
            mAfterClassWithInfoRunCount++;
            assertNotNull(testInfo);
        }
    }

    @Mock ITestInvocationListener mListener;
    private HostTest mHostTest;
    @Mock ITestDevice mMockDevice;
    private TestInformation mTestInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHostTest = new HostTest();
        mHostTest.setBuild(new BuildInfo());
        mHostTest.setDevice(mMockDevice);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        OptionSetter setter = new OptionSetter(mHostTest);
        // Disable pretty logging for testing
        setter.setOptionValue("enable-pretty-logs", "false");
        mTestInfo = TestInformation.newBuilder().build();
    }

    @Test
    public void testDynamicDownload() throws Exception {
        mHostTest.setClassName(Junit4TestClass.class.getName());
        TestDescription test1 = new TestDescription(Junit4TestClass.class.getName(), "testPass");

        mHostTest.run(mTestInfo, mListener);

        verify(mListener).testRunStarted((String) Mockito.any(), Mockito.eq(1));
        verify(mListener).testStarted(Mockito.eq(test1));
        verify(mListener).testEnded(Mockito.eq(test1), Mockito.<HashMap<String, Metric>>any());
        verify(mListener).testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());

        assertEquals(1, Junit4TestClass.mBeforeClassRunCount);
        assertEquals(1, Junit4TestClass.mBeforeClassWithInfoRunCount);
        assertEquals(1, Junit4TestClass.mAfterClassRunCount);
        assertEquals(1, Junit4TestClass.mAfterClassWithInfoRunCount);
    }
}
