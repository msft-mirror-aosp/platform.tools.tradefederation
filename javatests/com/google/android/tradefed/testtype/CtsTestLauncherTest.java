/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.tradefed.testtype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.FolderBuildInfo;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.config.proxy.AutomatedReporters;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NullDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.retry.BaseRetryDecision;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Unit tests for {@link CtsTestLauncher}. */
@RunWith(JUnit4.class)
public class CtsTestLauncherTest {

    private CtsTestLauncher mCtsTestLauncher;
    private FolderBuildInfo mBuildInfo;
    private ITestInvocationListener mMockListener;
    private IRunUtil mMockRunUtil;
    private ITestDevice mMockTestDevice;
    private IConfiguration mMockConfig;
    private IInvocationContext mFakeContext;
    private CoverageOptions mCoverageOptions;
    private TestInformation mTestInfo;

    private File mCtsRoot;
    private File mTfPath;
    private File mCtsTestcasesPath;

    private static final String FAKE_SERIAL = "FAKESERIAL";
    protected static final String[] TF_JAR = {"google-tradefed.jar", "google-tf-prod-tests.jar"};

    private List<String> mListJarNames = new ArrayList<String>();

    @Before
    public void setUp() throws Exception {
        mCtsRoot = FileUtil.createTempDir("cts-launcher-root");
        mTfPath = FileUtil.createTempDir("tf-path");
        mFakeContext = new InvocationContext();
        mFakeContext.setTestTag("TEST_TAG");
        mMockListener = Mockito.mock(ITestInvocationListener.class);
        mMockRunUtil = Mockito.mock(IRunUtil.class);
        mMockTestDevice = Mockito.mock(ITestDevice.class);
        mMockConfig = Mockito.mock(IConfiguration.class);
        mCoverageOptions = new CoverageOptions();

        mBuildInfo = new FolderBuildInfo("buildId", "buildName");
        mBuildInfo.setRootDir(mCtsRoot);
        mBuildInfo.setDeviceSerial(FAKE_SERIAL);
        mFakeContext.addDeviceBuildInfo("default", mBuildInfo);
        when(mMockTestDevice.getSerialNumber()).thenReturn(FAKE_SERIAL);
        when(mMockTestDevice.getOptions()).thenReturn(new TestDeviceOptions());
        when(mMockTestDevice.checkApiLevelAgainstNextRelease(29)).thenReturn(false);
        when(mMockTestDevice.getIDevice()).thenReturn(Mockito.mock(IDevice.class));

        mCtsTestLauncher =
                new CtsTestLauncher() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    void createSubprocessTmpDir(List<String> args) {
                        // ignore
                    }

                    @Override
                    void createHeapDumpTmpDir(List<String> args) {
                        // ignore
                    }

                    @Override
                    protected String getSystemJava() {
                        return "jdk/java";
                    }
                };
        mCtsTestLauncher.setBuild(mBuildInfo);
        mCtsTestLauncher.setDevice(mMockTestDevice);
        mCtsTestLauncher.setConfigName("cts");
        mCtsTestLauncher.setConfiguration(mMockConfig);
        mCtsTestLauncher.setInvocationContext(mFakeContext);
        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("disable-compress", "false");

        mListJarNames.add("hosttestlib.jar");
        mListJarNames.add("tradefed.jar");
        mListJarNames.add("cts-tradefed.jar");

        for (String tfJar : TF_JAR) {
            File tempFile = new File(mTfPath, tfJar);
            tempFile.createNewFile();
            mBuildInfo.setFile(tfJar, tempFile, null);
        }
        File ctsPath = new File(new File(mCtsRoot, "android-cts"), "tools");
        ctsPath.mkdirs();
        for (String ctsJar : mListJarNames) {
            File tempFile = new File(ctsPath, ctsJar);
            tempFile.createNewFile();
        }

        when(mMockConfig.getCommandOptions()).thenReturn(new CommandOptions());
        when(mMockConfig.getRetryDecision()).thenReturn(new BaseRetryDecision());
        when(mMockConfig.getCoverageOptions()).thenReturn(mCoverageOptions);
        when(mMockConfig.getConfigurationDescription()).thenReturn(new ConfigurationDescriptor());

        mCtsTestcasesPath = new File(new File(mCtsRoot, "android-cts"), "testcases");
        mCtsTestcasesPath.mkdirs();
        File jarFile = new File(mCtsTestcasesPath, "test1.jar");
        jarFile.createNewFile();
        ZipUtil.createZip(mCtsTestcasesPath, jarFile);

        mTestInfo = TestInformation.newBuilder().setInvocationContext(mFakeContext).build();
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mCtsRoot);
        FileUtil.recursiveDelete(mTfPath);
    }

    /** Test {@link CtsTestLauncher#buildClasspath()} with File exception because not TF jar */
    @Test
    public void testBuildClasspath_returnNotFoundNoTfJar() throws IOException {
        // Remove only TF jars
        for (String tfJar : TF_JAR) {
            File tempFile = new File(mTfPath, tfJar);
            tempFile.delete();
        }

        try {
            mCtsTestLauncher.buildClasspath();
            fail("Should have thrown an exception.");
        } catch (FileNotFoundException expected) {
            assertTrue(expected.getMessage().contains("Couldn't find the jar file"));
        }
    }

    /** Test {@link CtsTestLauncher#buildClasspath()} with File exception because no CTS jar */
    @Test
    public void testBuildClasspath_returnNotFoundNoCtsJar() throws IOException {
        // Remove CTS Jars
        for (String ctsJar : mListJarNames) {
            File tempFile = new File(new File(new File(mCtsRoot, "android-cts"), "tools"), ctsJar);
            tempFile.delete();
        }

        try {
            mCtsTestLauncher.buildClasspath();
            fail("Should have thrown an exception.");
        } catch (FileNotFoundException expected) {
            assertTrue(expected.getMessage().contains("Could not find any files under"));
        }
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with File exception because no CTS build
     * directory.
     */
    @Test
    public void testBuildClasspath_returnNotFoundNoCtsBuildDir() throws IOException {
        FolderBuildInfo wrongBuildInfo = new FolderBuildInfo("buildId", "buildName");
        wrongBuildInfo.setRootDir(new File("/wrong/not/exist"));
        wrongBuildInfo.setDeviceSerial(FAKE_SERIAL);
        mFakeContext = new InvocationContext();
        mFakeContext.addDeviceBuildInfo("default", wrongBuildInfo);

        mCtsTestLauncher.setBuild(wrongBuildInfo);
        mCtsTestLauncher.setInvocationContext(mFakeContext);

        try {
            mCtsTestLauncher.buildClasspath();
            fail("Should have thrown an exception.");
        } catch (FileNotFoundException expected) {
            assertEquals(
                    expected.getMessage(), "Couldn't find the build directory: /wrong/not/exist");
        }
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with Illegal Argument exception because
     * BuildInfo got the wrong type
     */
    @Test
    public void testBuildClasspath_returnIllegalBuildInfoType() throws Exception {
        BuildInfo wrongBuildInfo = new BuildInfo("buildId", "buildName");
        wrongBuildInfo.setDeviceSerial(FAKE_SERIAL);
        mFakeContext = new InvocationContext();
        mFakeContext.addDeviceBuildInfo("default", wrongBuildInfo);

        mCtsTestLauncher.setBuild(wrongBuildInfo);
        mCtsTestLauncher.setInvocationContext(mFakeContext);

        try {
            mCtsTestLauncher.buildClasspath();
            fail("Should have thrown an exception.");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    expected.getMessage(),
                    "Build info did not contain the suite key 'android-cts' nor is a "
                            + "IFolderBuildInfo.");
        }
    }

    /** Test {@link CtsTestLauncher#buildClasspath()} with return classpath and no exception. */
    @Test
    public void testBuildClasspath_returnClasspath() throws Exception {
        String res = mCtsTestLauncher.buildClasspath();
        assertNotNull(res);
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with return classpath excluding the given jar
     * file.
     */
    @Test
    public void testBuildClasspath_returnExcludeTheGivenFiles() throws Exception {
        String bundleToolFileName = "bundletool-2019.jar";
        List<String> excludedFiles = new ArrayList<>();
        excludedFiles.add("bundletool.*");
        mCtsTestLauncher.setExcludedFilesInClasspath(excludedFiles);
        // Create the bundletool jars.
        File tempFile = new File(mTfPath, bundleToolFileName);
        tempFile.createNewFile();
        mBuildInfo.setFile(bundleToolFileName, tempFile, null);

        assertFalse(mCtsTestLauncher.buildClasspath().contains(bundleToolFileName));
    }

    /** Test {@link CtsTestLauncher#buildClasspath()} with option top-prioprity-jar set. */
    @Test
    public void testBuildClasspath_withTopPriorityJar() throws Exception {
        String topJarFileName = "top-priority.jar";
        Collection<String> jars = new LinkedHashSet<>();
        jars.add(topJarFileName);
        mCtsTestLauncher.setTopPriorityJar(jars);
        // Create the bundletool jars.
        File tempFile = new File(mTfPath, topJarFileName);
        tempFile.createNewFile();
        mBuildInfo.setFile(topJarFileName, tempFile, null);

        assertTrue(mCtsTestLauncher.buildClasspath().startsWith(tempFile.getAbsolutePath()));
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with proper return for the basic command
     * template
     */
    @Test
    public void testBuildJavaCmd_returnCmd() throws Exception {
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    @Test
    public void testBuildJavaCmd_returnCmd_bundled() throws Exception {
        new File(mCtsRoot, "jdk/bin/").mkdirs();
        File java = new File(mCtsRoot, "jdk/bin/java");
        java.createNewFile();

        List<String> expected = new ArrayList<String>();
        expected.add(java.getAbsolutePath());
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    @Test
    public void testBuildJavaCmd_returnCmd_nullDevice() throws Exception {
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");
        Mockito.reset(mMockTestDevice, mMockConfig);
        when(mMockTestDevice.getIDevice()).thenReturn(new NullDevice("serial"));
        when(mMockTestDevice.getSerialNumber()).thenReturn(FAKE_SERIAL);
        when(mMockTestDevice.getOptions()).thenReturn(new TestDeviceOptions());
        when(mMockConfig.getCommandOptions()).thenReturn(new CommandOptions());
        when(mMockConfig.getCoverageOptions()).thenReturn(new CoverageOptions());

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with proper return for the basic command
     * template
     */
    @Test
    public void testBuildJavaCmd_returnCmd_withBuildInfo() throws Exception {
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--build-flavor");
        expected.add("buildFlavor");
        expected.add("--build-attribute");
        expected.add("build_target=buildTarget");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        mBuildInfo.setBuildFlavor("buildFlavor");
        mBuildInfo.addBuildAttribute("build_target", "buildTarget");
        mCtsTestLauncher.setConfiguration(new Configuration("test", "test"));

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} when the build info is not a folder build
     * but contains the suite package key that refers to it.
     */
    @Test
    public void testBuildJavaCmd_notRootFolder() throws Exception {
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--build-flavor");
        expected.add("buildFlavor");
        expected.add("--build-attribute");
        expected.add("build_target=buildTarget");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("tests-suite-package", "suite");
        BuildInfo build = new BuildInfo("buildId", "buildName");
        build.setBuildFlavor("buildFlavor");
        build.addBuildAttribute("build_target", "buildTarget");
        build.setFile("suite", mCtsRoot, "v2");
        mFakeContext.addDeviceBuildInfo("device", build);
        mCtsTestLauncher.setBuild(build);
        mCtsTestLauncher.setConfiguration(new Configuration("test", "test"));

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with proper return for the basic command
     * template when running without root
     */
    @Test
    public void testBuildJavaCmd_returnCmdNonRoot() throws Exception {
        List<String> expected = new ArrayList<String>();
        mCtsTestLauncher.setRunAsRoot(false);
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add("-DCTS_ROOT=" + mCtsRoot.getAbsolutePath());
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--no-enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with proper return for the basic command
     * template with V2
     */
    @Test
    public void testBuildJavaCmd_returnCmdV2() throws Exception {
        mCtsTestLauncher.setCtsVersion(2);
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add(String.format("-DCTS_ROOT=%s", mCtsRoot.getAbsolutePath()));
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    @Test
    public void testBuildJavaCmd_localSharding() throws Exception {
        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("local-sharding-mode", "true");
        mCtsTestLauncher.setCtsVersion(2);
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add(String.format("-DCTS_ROOT=%s", mCtsRoot.getAbsolutePath()));
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        // No Serial during local sharding
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} is adding invocation-data to the java cmd
     * when passed from the main invocation.
     */
    @Test
    public void testBuildJavaCmd_invocationData() throws Exception {
        IConfiguration mockConfig = Mockito.mock(IConfiguration.class);
        CommandOptions cmdOption = new CommandOptions();
        OptionSetter setter = new OptionSetter(cmdOption);
        setter.setOptionValue("invocation-data", "CL_NUMBER", "12345678");
        when(mockConfig.getCommandOptions()).thenReturn(cmdOption);
        when(mockConfig.getRetryDecision()).thenReturn(new BaseRetryDecision());
        when(mockConfig.getCoverageOptions()).thenReturn(new CoverageOptions());
        OptionSetter s = new OptionSetter(mCtsTestLauncher);
        s.setOptionValue("inject-invocation-data", "true");
        mCtsTestLauncher.setCtsVersion(2);
        List<String> expected = new ArrayList<String>();
        expected.add("jdk/java");
        expected.add("-cp");
        expected.add("FAKE_CP");
        expected.add(String.format("-DCTS_ROOT=%s", mCtsRoot.getAbsolutePath()));
        expected.add("com.android.tradefed.command.CommandRunner");
        expected.add("cts");
        expected.add("--log-level");
        expected.add("VERBOSE");
        expected.add("--log-level-display");
        expected.add("VERBOSE");
        expected.add("--serial");
        expected.add(FAKE_SERIAL);
        expected.add("--build-id");
        expected.add("buildId");
        expected.add("--enable-root");
        expected.add("--invocation-data");
        expected.add("CL_NUMBER");
        expected.add("12345678");
        expected.add("--" + CommandOptions.INVOCATION_DATA);
        expected.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
        expected.add("true");

        mCtsTestLauncher.setConfiguration(mockConfig);

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertArrayEquals(expected.toArray(), actual.toArray());
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with Clang coverage, passing the value
     * through from the {@link CoverageOptions}.
     */
    @Test
    public void testBuildJavaCmd_profileToolPassedThrough() throws Exception {
        OptionSetter setter = new OptionSetter(mCoverageOptions);
        setter.setOptionValue("coverage", "true");
        setter.setOptionValue("coverage-toolchain", "CLANG");
        setter.setOptionValue(
                "llvm-profdata-path", "/path/to/some/directory/containing/llvm-profdata");

        mCtsTestLauncher.setConfiguration(mMockConfig);

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        Assert.assertTrue(actual.contains("--llvm-profdata-path"));
        Assert.assertTrue(actual.contains("/path/to/some/directory/containing/llvm-profdata"));
    }

    /**
     * Test {@link CtsTestLauncher#buildJavaCmd(String)} with Clang coverage, downloading the tool
     * from the build.
     */
    @Test
    public void testBuildJavaCmd_profileToolFromBuild() throws Exception {
        OptionSetter setter = new OptionSetter(mCoverageOptions);
        setter.setOptionValue("coverage", "true");
        setter.setOptionValue("coverage-toolchain", "CLANG");
        mBuildInfo.setFile("llvm-profdata.zip", createProfileToolZip(), null);
        mCtsTestLauncher.setConfiguration(mMockConfig);

        List<String> actual = mCtsTestLauncher.buildJavaCmd("FAKE_CP");
        mCtsTestLauncher.cleanLlvmProfdataTool();
        Assert.assertTrue(actual.contains("--llvm-profdata-path"));
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with return classpath and no exception with V2
     */
    @Test
    public void testBuildClasspath_returnClasspathV2() throws Exception {
        mCtsTestLauncher.setCtsVersion(2);
        String res = mCtsTestLauncher.buildClasspath();
        assertNotNull(res);
        assertTrue(res.contains("android-cts/testcases/test1.jar"));
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with return classpath and no exception with V2
     * and includes jars in sub directory.
     */
    @Test
    public void testBuildClasspath_returnClasspathV2Subdir() throws Exception {
        File subdir = new File(mCtsTestcasesPath, "subdir");
        subdir.mkdirs();
        File jarFile = new File(subdir, "test2.jar");
        jarFile.createNewFile();
        ZipUtil.createZip(subdir, jarFile);

        mCtsTestLauncher.setCtsVersion(2);
        String res = mCtsTestLauncher.buildClasspath();
        assertNotNull(res);
        assertTrue(res.contains("android-cts/testcases/subdir/test2.jar"));
    }

    /**
     * Test {@link CtsTestLauncher#buildClasspath()} with File exception because no testcases folder
     */
    @Test
    public void testBuildClasspath_returnNotFoundTestcases() throws IOException {
        mCtsTestLauncher.setCtsVersion(2);
        // Remove Testcases folder
        FileUtil.recursiveDelete(mCtsTestcasesPath);
        try {
            mCtsTestLauncher.buildClasspath();
            fail("Should have thrown an exception.");
        } catch (FileNotFoundException expected) {
        }
    }

    /**
     * Test {@link CtsTestLauncher#run(TestInformation, ITestInvocationListener)} with an incorrect
     * version
     */
    @Test
    public void testRun_badVersion() throws DeviceNotAvailableException {
        mCtsTestLauncher.setCtsVersion(3);
        try {
            mCtsTestLauncher.run(mTestInfo, mMockListener);
            fail();
        } catch (RuntimeException e) {
            // expected
        }
        mCtsTestLauncher.setCtsVersion(0);
        try {
            mCtsTestLauncher.run(mTestInfo, mMockListener);
            fail();
        } catch (RuntimeException e) {
            // expected
        }
    }

    /**
     * Test {@link CtsTestLauncher#run(TestInformation, ITestInvocationListener)} with a success
     * case.
     */
    @Test
    public void testRun_success() throws Exception {
        mCtsTestLauncher =
                new CtsTestLauncher() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    public List<String> buildJavaCmd(String classpath) {
                        List<String> fake = new ArrayList<String>();
                        fake.add("jdk/java");
                        mCtsTestLauncher.getDevice().getSerialNumber();
                        return fake;
                    }
                };
        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("use-event-streaming", "false");
        setter.setOptionValue("use-proto-reporting", "false");
        mCtsTestLauncher.setBuild(mBuildInfo);
        mCtsTestLauncher.setConfigName("cts");
        mCtsTestLauncher.setDevice(mMockTestDevice);
        mCtsTestLauncher.setInvocationContext(mFakeContext);
        mCtsTestLauncher.setConfiguration(new Configuration("name", "description"));
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        result.setStdout("stdout");
        result.setStderr("stderr");
        when(mMockRunUtil.runTimedCmdWithInput(
                        Mockito.anyLong(),
                        Mockito.isNull(),
                        (File) Mockito.any(),
                        (File) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any()))
                .thenReturn(result);

        mCtsTestLauncher.run(mTestInfo, mMockListener);
        verify(mMockListener, times(3))
                .testLog(
                        (String) Mockito.any(),
                        Mockito.eq(LogDataType.TEXT),
                        (InputStreamSource) Mockito.any());
        verify(mMockRunUtil)
                .unsetEnvVariable(Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE));
        verify(mMockRunUtil)
                .unsetEnvVariable(
                        Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE));
        for (String variable : AutomatedReporters.REPORTER_MAPPING) {
            verify(mMockRunUtil).unsetEnvVariable(variable);
        }
    }

    /**
     * Test {@link CtsTestLauncher#run(TestInformation, ITestInvocationListener)} with a command
     * failure.
     */
    @Test
    public void testRun_failure() throws Exception {
        mCtsTestLauncher =
                new CtsTestLauncher() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    public List<String> buildJavaCmd(String classpath) {
                        List<String> fake = new ArrayList<String>();
                        fake.add("jdk/java");
                        mCtsTestLauncher.getDevice().getSerialNumber();
                        return fake;
                    }
                };
        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("use-event-streaming", "false");
        setter.setOptionValue("use-proto-reporting", "false");
        mCtsTestLauncher.setBuild(mBuildInfo);
        mCtsTestLauncher.setConfigName("cts");
        mCtsTestLauncher.setDevice(mMockTestDevice);
        mCtsTestLauncher.setInvocationContext(mFakeContext);
        mCtsTestLauncher.setConfiguration(new Configuration("name", "description"));
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.FAILED);
        result.setStdout("stdout");
        result.setStderr("stderr");
        when(mMockRunUtil.runTimedCmdWithInput(
                        Mockito.anyLong(),
                        Mockito.isNull(),
                        (File) Mockito.any(),
                        (File) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any()))
                .thenReturn(result);

        try {
            mCtsTestLauncher.run(mTestInfo, mMockListener);
            fail("CtsTestLauncher should have thrown an exception");
        } catch (RuntimeException e) {
            // expected
        }
        verify(mMockListener, times(3))
                .testLog(
                        (String) Mockito.any(),
                        Mockito.eq(LogDataType.TEXT),
                        (InputStreamSource) Mockito.any());
        verify(mMockRunUtil)
                .unsetEnvVariable(Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE));
        verify(mMockRunUtil)
                .unsetEnvVariable(
                        Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE));
        for (String variable : AutomatedReporters.REPORTER_MAPPING) {
            verify(mMockRunUtil).unsetEnvVariable(variable);
        }
    }

    /**
     * Test {@link CtsTestLauncher#run(TestInformation, ITestInvocationListener)} with a command
     * timeout.
     */
    @Test
    public void testRun_timeout() throws Exception {
        mCtsTestLauncher =
                new CtsTestLauncher() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    public List<String> buildJavaCmd(String classpath) {
                        List<String> fake = new ArrayList<String>();
                        fake.add("jdk/java");
                        mCtsTestLauncher.getDevice().getSerialNumber();
                        return fake;
                    }
                };
        OptionSetter setter = new OptionSetter(mCtsTestLauncher);
        setter.setOptionValue("use-event-streaming", "false");
        setter.setOptionValue("use-proto-reporting", "false");
        mCtsTestLauncher.setBuild(mBuildInfo);
        mCtsTestLauncher.setConfigName("cts");
        mCtsTestLauncher.setDevice(mMockTestDevice);
        mCtsTestLauncher.setInvocationContext(mFakeContext);
        mCtsTestLauncher.setConfiguration(new Configuration("name", "description"));
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.TIMED_OUT);
        result.setStdout("stdout");
        result.setStderr("stderr");
        when(mMockRunUtil.runTimedCmdWithInput(
                        Mockito.anyLong(),
                        Mockito.isNull(),
                        (File) Mockito.any(),
                        (File) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any(),
                        (String) Mockito.any()))
                .thenReturn(result);

        try {
            mCtsTestLauncher.run(mTestInfo, mMockListener);
            fail("CtsTestLauncher should have thrown an exception");
        } catch (RuntimeException expected) {
            // Expected
            Truth.assertThat(expected).hasMessageThat().contains("Timed out after");
        }
        verify(mMockListener, times(3))
                .testLog(
                        (String) Mockito.any(),
                        Mockito.eq(LogDataType.TEXT),
                        (InputStreamSource) Mockito.any());
        verify(mMockRunUtil)
                .unsetEnvVariable(Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE));
        verify(mMockRunUtil)
                .unsetEnvVariable(
                        Mockito.eq(GlobalConfiguration.GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE));
        for (String variable : AutomatedReporters.REPORTER_MAPPING) {
            verify(mMockRunUtil).unsetEnvVariable(variable);
        }
    }

    /** Test that when there is no heap dump available, we do not log anything and clean the dir. */
    @Test
    public void testLogAndCleanHeapDump_Empty() throws Exception {
        File heapDumpDir = FileUtil.createTempDir("heap-dump");
        try {
            mCtsTestLauncher.logAndCleanHeapDump(heapDumpDir, mMockListener);
            // ensure the dir was cleaned
            assertFalse(heapDumpDir.exists());
        } finally {
            FileUtil.recursiveDelete(heapDumpDir);
        }
    }

    /** Test that when the heap dump is available, we log it and clean the dir. */
    @Test
    public void testLogAndCleanHeapDump() throws Exception {
        File heapDumpDir = FileUtil.createTempDir("heap-dump");
        File hprof = FileUtil.createTempFile("java.999.", ".hprof", heapDumpDir);
        try {
            mCtsTestLauncher.logAndCleanHeapDump(heapDumpDir, mMockListener);
            // ensure the dir was cleaned
            assertFalse(heapDumpDir.exists());
        } finally {
            FileUtil.recursiveDelete(heapDumpDir);
        }
        verify(mMockListener)
                .testLog(Mockito.eq(hprof.getName()), Mockito.eq(LogDataType.HPROF), Mockito.any());
    }

    /** Test that when splitting the launcher all shards will be exercised. */
    @Test
    public void testShardLauncher() {
        final int numShards = 5;
        Collection<IRemoteTest> shards = mCtsTestLauncher.split(numShards);
        int i = 0;
        for (IRemoteTest test : shards) {
            // Each launcher is of the right type
            CtsTestLauncher launcher = (CtsTestLauncher) test;
            assertEquals(i, launcher.getShardIndex());
            // Cannot be resharded
            launcher.setConfiguration(mMockConfig);
            assertNull(launcher.split(5));
            i++;
        }
    }

    private File createProfileToolZip() throws IOException {
        File profileToolZip = new File(mTfPath, "llvm-profdata.zip");
        try (FileOutputStream stream = new FileOutputStream(profileToolZip);
                ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(stream))) {
            // Add bin/llvm-profdata.
            ZipEntry entry = new ZipEntry("bin/llvm-profdata");
            out.putNextEntry(entry);
            out.closeEntry();
        }
        return profileToolZip;
    }
}
