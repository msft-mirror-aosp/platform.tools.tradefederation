/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.proto.BuildInformation;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SerializationUtil;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link BuildInfo}. */
@RunWith(JUnit4.class)
public class BuildInfoTest {
    private static final String VERSION = "2";
    private static final String ATTRIBUTE_KEY = "attribute";
    private static final String FILE_KEY = "file";

    private BuildInfo mBuildInfo;
    private File mFile;

    @Before
    public void setUp() throws Exception {
        mBuildInfo = new BuildInfo("1", "target");
        mBuildInfo.addBuildAttribute(ATTRIBUTE_KEY, "value");
        mFile = FileUtil.createTempFile("image", "tmp");
        FileUtil.writeToFile("filedata", mFile);
        mBuildInfo.setFile(FILE_KEY, mFile, VERSION);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mFile);
    }

    /** Test method for {@link BuildInfo#clone()}. */
    @Test
    public void testClone() throws Exception {
        mBuildInfo.setDeviceSerial("tobecloned");
        mBuildInfo.setFile(
                IBuildInfo.REMOTE_FILE_PREFIX + "general-tests.zip",
                new File("ab:/branch/suites/android-cts.zip"),
                "1");
        BuildInfo copy = (BuildInfo) mBuildInfo.clone();
        assertEquals(mBuildInfo.getBuildAttributes().get(ATTRIBUTE_KEY),
                copy.getBuildAttributes().get(ATTRIBUTE_KEY));
        try {
            // ensure a copy of mImageFile was created
            assertEquals(VERSION, copy.getVersion(FILE_KEY));
            assertTrue(!mFile.getAbsolutePath().equals(copy.getFile(FILE_KEY).getAbsolutePath()));
            assertTrue(FileUtil.compareFileContents(mFile, copy.getFile(FILE_KEY)));
            assertEquals("tobecloned", copy.getDeviceSerial());
            Truth.assertThat(copy.getRemoteFiles())
                    .containsExactly(new File("ab:/branch/suites/android-cts.zip"));
        } finally {
            FileUtil.deleteFile(copy.getFile(FILE_KEY));
        }
    }

    /** Test method for {@link BuildInfo#cleanUp()}. */
    @Test
    public void testCleanUp() {
        assertTrue(mBuildInfo.getFile(FILE_KEY).exists());
        mBuildInfo.cleanUp();
        assertNull(mBuildInfo.getFile(FILE_KEY));
        assertFalse(mFile.exists());
    }

    /** Test for {@link BuildInfo#toString()}. */
    @Test
    public void testToString() {
        mBuildInfo.setBuildFlavor("testFlavor");
        mBuildInfo.setBuildBranch("testBranch");
        mBuildInfo.setDeviceSerial("fakeSerial");
        String expected = "BuildInfo{bid=1, target=target, build_flavor=testFlavor, "
                + "branch=testBranch, serial=fakeSerial}";
        assertEquals(expected, mBuildInfo.toString());
    }

    /** Test for {@link BuildInfo#toString()} when a build alias is present. */
    @Test
    public void testToString_withBuildAlias() {
        mBuildInfo.addBuildAttribute("build_alias", "NMR12");
        mBuildInfo.setBuildFlavor("testFlavor");
        mBuildInfo.setBuildBranch("testBranch");
        mBuildInfo.setDeviceSerial("fakeSerial");
        String expected = "BuildInfo{build_alias=NMR12, bid=1, target=target, "
                + "build_flavor=testFlavor, branch=testBranch, serial=fakeSerial}";
        assertEquals(expected, mBuildInfo.toString());
    }

    /**
     * Test that all the components of {@link BuildInfo} can be serialized via the default java
     * object serialization.
     */
    @Test
    public void testSerialization() throws Exception {
        File tmpSerialized = SerializationUtil.serialize(mBuildInfo);
        Object o = SerializationUtil.deserialize(tmpSerialized, true);
        assertTrue(o instanceof BuildInfo);
        BuildInfo test = (BuildInfo) o;
        // use the custom build info equals to check similar properties
        assertTrue(mBuildInfo.equals(test));
    }

    /**
     * Test {@link BuildInfo#cleanUp(java.util.List)} to ensure it removes non-existing files and
     * lives others.
     */
    @Test
    public void testCleanUpWithExemption() throws Exception {
        File testFile = FileUtil.createTempFile("fake-versioned-file", ".txt");
        File testFile2 = FileUtil.createTempFile("fake-versioned-file2", ".txt");
        try {
            mBuildInfo.setFile("name", testFile, "version");
            mBuildInfo.setFile("name2", testFile2, "version2");
            assertNotNull(mBuildInfo.getFile("name"));
            assertNotNull(mBuildInfo.getFile("name2"));
            // Clean up with an exception on one of the file
            mBuildInfo.cleanUp(Arrays.asList(testFile2));
            assertNull(mBuildInfo.getFile("name"));
            // The second file still exists and is left untouched.
            assertNotNull(mBuildInfo.getFile("name2"));
        } finally {
            FileUtil.deleteFile(testFile);
            FileUtil.deleteFile(testFile2);
        }
    }

    /**
     * If we call {@link IBuildInfo#getVersionedFiles(BuildInfoFileKey)} on a non-list key we get an
     * exception.
     */
    @Test
    public void testGetList_error() {
        try {
            mBuildInfo.getVersionedFiles(BuildInfoFileKey.TESTDIR_IMAGE);
            fail("Should have thrown an exception.");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    /** Test that if the key supports list, we can save several files. */
    @Test
    public void testListFiles() throws Exception {
        File testFile = FileUtil.createTempFile("fake-versioned-file", ".txt");
        File testFile2 = FileUtil.createTempFile("fake-versioned-file2", ".txt");
        try {
            mBuildInfo.setFile(BuildInfoFileKey.PACKAGE_FILES, testFile, "version");
            mBuildInfo.setFile(BuildInfoFileKey.PACKAGE_FILES, testFile2, "version2");
            assertNotNull(mBuildInfo.getFile(BuildInfoFileKey.PACKAGE_FILES));
            List<VersionedFile> listFiles =
                    mBuildInfo.getVersionedFiles(BuildInfoFileKey.PACKAGE_FILES);
            assertEquals(2, listFiles.size());
        } finally {
            FileUtil.deleteFile(testFile);
            FileUtil.deleteFile(testFile2);
        }
    }

    /** Test that the build info can be described in its proto format. */
    @Test
    public void testProtoSerialization() throws Exception {
        List<String> remoteFiles = Arrays.asList("remote/file1", "remote/file2");
        for (String file : remoteFiles) {
            mBuildInfo.setFile(
                    IBuildInfo.REMOTE_FILE_PREFIX + file,
                    new File(file),
                    IBuildInfo.REMOTE_FILE_VERSION);
        }

        BuildInformation.BuildInfo proto = mBuildInfo.toProto();

        assertEquals("1", proto.getBuildId());
        assertEquals(BuildInfo.class.getCanonicalName(), proto.getBuildInfoClass());
        assertEquals("value", proto.getAttributesMap().get("attribute"));
        assertEquals(3, proto.getVersionedFileList().size());
        assertNotNull(proto.getVersionedFileList().get(0));

        IBuildInfo deserialized = BuildInfo.fromProto(proto);
        assertEquals("1", deserialized.getBuildId());
        // Build flavor was not set, so it's null
        assertNull(deserialized.getBuildFlavor());
        assertNotNull(deserialized.getVersionedFile(FILE_KEY));

        // Check the remote files are restored.
        for (String file : remoteFiles) {
            assertTrue(deserialized.getRemoteFiles().contains(new File(file)));
        }
    }
}
