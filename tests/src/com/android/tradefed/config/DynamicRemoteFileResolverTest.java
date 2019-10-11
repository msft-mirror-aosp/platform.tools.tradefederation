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
package com.android.tradefed.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.config.remote.GcsRemoteFileResolver;
import com.android.tradefed.config.remote.IRemoteFileResolver;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

/** Unit tests for {@link DynamicRemoteFileResolver}. */
@RunWith(JUnit4.class)
public class DynamicRemoteFileResolverTest {

    @OptionClass(alias = "alias-remote-file")
    private static class RemoteFileOption {
        @Option(name = "remote-file")
        public File remoteFile = null;

        @Option(name = "remote-file-list")
        public Collection<File> remoteFileList = new ArrayList<>();

        @Option(name = "remote-map")
        public Map<File, File> remoteMap = new HashMap<>();

        @Option(name = "remote-multi-map")
        public MultiMap<File, File> remoteMultiMap = new MultiMap<>();
    }

    @OptionClass(alias = "option-class-alias", global_namespace = false)
    private static class RemoteFileOptionWithOptionClass {
        @Option(name = "remote-file")
        public File remoteFile = null;
    }

    private DynamicRemoteFileResolver mResolver;
    private IRemoteFileResolver mMockResolver;

    @Before
    public void setUp() {
        mMockResolver = EasyMock.createMock(IRemoteFileResolver.class);
        mResolver =
                new DynamicRemoteFileResolver() {
                    @Override
                    protected IRemoteFileResolver getResolver(String protocol) {
                        if (GcsRemoteFileResolver.PROTOCOL.equals(protocol)) {
                            return mMockResolver;
                        }
                        return null;
                    }

                    @Override
                    protected boolean updateProtocols() {
                        // Do not set the static variable
                        return false;
                    }
                };
    }

    @Test
    public void testResolve() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-file", "gs://fake/path");
        assertEquals("gs:/fake/path", object.remoteFile.getPath());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(1, downloadedFile.size());
            File downloaded = downloadedFile.iterator().next();
            // The file has been replaced by the downloaded one.
            assertEquals(downloaded.getAbsolutePath(), object.remoteFile.getAbsolutePath());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolveWithQuery() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-file", "gs://fake/path?key=value");
        assertEquals("gs:/fake/path?key=value", object.remoteFile.getPath());

        Map<String, String> testMap = new HashMap<>();
        testMap.put("key", "value");
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.eq(testMap)))
                .andReturn(fake);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(1, downloadedFile.size());
            File downloaded = downloadedFile.iterator().next();
            // The file has been replaced by the downloaded one.
            assertEquals(downloaded.getAbsolutePath(), object.remoteFile.getAbsolutePath());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    /** Test to make sure that a dynamic download marked as "optional" does not throw */
    @Test
    public void testResolveOptional() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        setter.setOptionValue("remote-file", "gs://fake/path?optional=true");
        assertEquals("gs:/fake/path?optional=true", object.remoteFile.getPath());

        Map<String, String> testMap = new HashMap<>();
        testMap.put("optional", "true");
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.eq(testMap)))
                .andThrow(new ConfigurationException("Failed to download"));
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(0, downloadedFile.size());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolve_remoteFileList() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-file-list", "gs://fake/path");
        setter.setOptionValue("remote-file-list", "fake/file");
        assertEquals(2, object.remoteFileList.size());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(1, downloadedFile.size());
            File downloaded = downloadedFile.iterator().next();
            // The file has been replaced by the downloaded one.
            assertEquals(2, object.remoteFileList.size());
            Iterator<File> ite = object.remoteFileList.iterator();
            File notGsFile = ite.next();
            assertEquals("fake/file", notGsFile.getPath());
            File gsFile = ite.next();
            assertEquals(downloaded.getAbsolutePath(), gsFile.getAbsolutePath());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolve_remoteFileList_downloadError() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };
        setter.setOptionValue("remote-file-list", "fake/file");
        setter.setOptionValue("remote-file-list", "gs://success/fake/path");
        setter.setOptionValue("remote-file-list", "gs://success/fake/path2");
        setter.setOptionValue("remote-file-list", "gs://failure/test");
        assertEquals(4, object.remoteFileList.size());

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs://success/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs://success/fake/path2")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs://failure/test")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andThrow(new ConfigurationException("retrieval error"));
        EasyMock.replay(mMockResolver);
        try {
            setter.validateRemoteFilePath();
            fail("Should have thrown an exception");
        } catch (ConfigurationException expected) {
            // Only when we reach failure/test it fails
            assertTrue(expected.getMessage().contains("retrieval error"));
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolve_remoteMap() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");
        File fake2 = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-map", "gs://fake/path", "value");
        setter.setOptionValue("remote-map", "fake/file", "gs://fake/path2");
        setter.setOptionValue("remote-map", "key", "val");
        assertEquals(3, object.remoteMap.size());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path2")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake2);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(2, downloadedFile.size());
            // The file has been replaced by the downloaded one.
            assertEquals(3, object.remoteMap.size());
            assertEquals(new File("value"), object.remoteMap.get(fake));
            assertEquals(fake2, object.remoteMap.get(new File("fake/file")));
            assertEquals(new File("val"), object.remoteMap.get(new File("key")));
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolve_remoteMultiMap() throws Exception {
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");
        File fake2 = FileUtil.createTempFile("gs-option-setter-test", "txt");
        File fake3 = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-multi-map", "gs://fake/path", "value");
        setter.setOptionValue("remote-multi-map", "fake/file", "gs://fake/path2");
        setter.setOptionValue("remote-multi-map", "fake/file", "gs://fake/path3");
        setter.setOptionValue("remote-multi-map", "key", "val");
        assertEquals(3, object.remoteMultiMap.size());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path2")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake2);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path3")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake3);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(3, downloadedFile.size());
            // The file has been replaced by the downloaded one.
            assertEquals(3, object.remoteMultiMap.size());
            assertEquals(new File("value"), object.remoteMultiMap.get(fake).get(0));
            assertEquals(fake2, object.remoteMultiMap.get(new File("fake/file")).get(0));
            assertEquals(fake3, object.remoteMultiMap.get(new File("fake/file")).get(1));
            assertEquals(new File("val"), object.remoteMultiMap.get(new File("key")).get(0));
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolve_withNoGlobalNameSpace() throws Exception {
        RemoteFileOptionWithOptionClass object = new RemoteFileOptionWithOptionClass();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("option-class-alias:remote-file", "gs://fake/path");
        assertEquals("gs:/fake/path", object.remoteFile.getPath());

        // File is downloaded the first time, then is ignored since it doesn't have the protocol
        // anymore
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(1, downloadedFile.size());
            File downloaded = downloadedFile.iterator().next();
            // The file has been replaced by the downloaded one.
            assertEquals(downloaded.getAbsolutePath(), object.remoteFile.getAbsolutePath());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    /** Allow extra resolver to be provided via global configuration. */
    @Test
    public void testResolve_addition() throws Exception {
        mResolver =
                new DynamicRemoteFileResolver() {
                    @Override
                    protected boolean updateProtocols() {
                        // Do not set the static variable
                        return true;
                    }

                    @Override
                    IGlobalConfiguration getGlobalConfig() {
                        IGlobalConfiguration config;
                        try {
                            config =
                                    ConfigurationFactory.getInstance()
                                            .createGlobalConfigurationFromArgs(
                                                    new String[] {"empty"}, new ArrayList<>());
                            // Add a custom object to the resolving map
                            config.setConfigurationObject(
                                    DynamicRemoteFileResolver.DYNAMIC_RESOLVER,
                                    new IRemoteFileResolver() {

                                        @Override
                                        public @Nonnull File resolveRemoteFiles(
                                                File consideredFile, Option option)
                                                throws ConfigurationException {
                                            return mMockResolver.resolveRemoteFiles(
                                                    consideredFile, option);
                                        }

                                        @Override
                                        public @Nonnull String getSupportedProtocol() {
                                            return "fakeprotocol";
                                        }
                                    });

                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }
                        return config;
                    }
                };
        RemoteFileOption object = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");

        setter.setOptionValue("remote-file", "fakeprotocol://fake/path");
        assertEquals("fakeprotocol:/fake/path", object.remoteFile.getPath());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("fakeprotocol:/fake/path")),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(1, downloadedFile.size());
            File downloaded = downloadedFile.iterator().next();
            // The file has been replaced by the downloaded one.
            assertEquals(downloaded.getAbsolutePath(), object.remoteFile.getAbsolutePath());
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    @Test
    public void testResolvePartialDownloadZip() throws Exception {
        List<String> includeFilters = Arrays.asList("test1", "test2");
        List<String> excludeFilters = Arrays.asList("[.]config");

        Map<String, String> queryArgs = new HashMap<>();
        queryArgs.put("partial_download_dir", "/tmp");
        queryArgs.put("include_filters", "test1;test2");
        queryArgs.put("exclude_filters", "[.]config");
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.eq(null),
                                EasyMock.eq(queryArgs)))
                .andReturn(null);
        EasyMock.replay(mMockResolver);

        mResolver.resolvePartialDownloadZip(
                new File("/tmp"), "gs:/fake/path", includeFilters, excludeFilters);
        EasyMock.verify(mMockResolver);
    }

    /** Ignore any error if the download request is optional. */
    @Test
    public void testResolvePartialDownloadZip_optional() throws Exception {
        List<String> includeFilters = Arrays.asList("test1", "test2");
        List<String> excludeFilters = Arrays.asList("[.]config");

        Map<String, String> queryArgs = new HashMap<>();
        queryArgs.put("partial_download_dir", "/tmp");
        queryArgs.put("include_filters", "test1;test2");
        queryArgs.put("exclude_filters", "[.]config");
        queryArgs.put("optional", "true");
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path?optional=true")),
                                EasyMock.eq(null),
                                EasyMock.eq(queryArgs)))
                .andThrow(new ConfigurationException("should not throw this exception."));
        EasyMock.replay(mMockResolver);

        mResolver.resolvePartialDownloadZip(
                new File("/tmp"), "gs:/fake/path?optional=true", includeFilters, excludeFilters);
        EasyMock.verify(mMockResolver);
    }

    /**
     * Ensure that the same field on two different objects can be set with different remote values.
     */
    @Test
    public void testResolveTwoObjects() throws Exception {
        RemoteFileOption object1 = new RemoteFileOption();
        RemoteFileOption object2 = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object1, object2) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");
        setter.setOptionValue("alias-remote-file:1:remote-file", "gs://fake/path");
        assertEquals("gs:/fake/path", object1.remoteFile.getPath());

        File fake2 = FileUtil.createTempFile("gs-option-setter-test", "txt");
        setter.setOptionValue("alias-remote-file:2:remote-file", "gs://fake2/path2");
        assertEquals("gs:/fake2/path2", object2.remoteFile.getPath());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake2/path2")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake2);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(2, downloadedFile.size());
            assertTrue(downloadedFile.contains(object1.remoteFile));
            assertTrue(downloadedFile.contains(object2.remoteFile));

            assertFalse(object1.remoteFile.equals(object2.remoteFile));
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }

    /** Ensure that if the same value is set on two different objects we still resolve both. */
    @Test
    public void testResolveTwoObjects_sameValue() throws Exception {
        RemoteFileOption object1 = new RemoteFileOption();
        RemoteFileOption object2 = new RemoteFileOption();
        OptionSetter setter =
                new OptionSetter(object1, object2) {
                    @Override
                    protected DynamicRemoteFileResolver createResolver() {
                        return mResolver;
                    }
                };

        File fake = FileUtil.createTempFile("gs-option-setter-test", "txt");
        setter.setOptionValue("alias-remote-file:1:remote-file", "gs://fake/path");
        assertEquals("gs:/fake/path", object1.remoteFile.getPath());

        File fake2 = FileUtil.createTempFile("gs-option-setter-test", "txt");
        setter.setOptionValue("alias-remote-file:2:remote-file", "gs://fake/path");
        assertEquals("gs:/fake/path", object2.remoteFile.getPath());

        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake);
        EasyMock.expect(
                        mMockResolver.resolveRemoteFiles(
                                EasyMock.eq(new File("gs:/fake/path")),
                                EasyMock.anyObject(),
                                EasyMock.anyObject()))
                .andReturn(fake2);
        EasyMock.replay(mMockResolver);

        Set<File> downloadedFile = setter.validateRemoteFilePath();
        try {
            assertEquals(2, downloadedFile.size());
            assertTrue(downloadedFile.contains(object1.remoteFile));
            assertTrue(downloadedFile.contains(object2.remoteFile));

            assertFalse(object1.remoteFile.equals(object2.remoteFile));
        } finally {
            for (File f : downloadedFile) {
                FileUtil.recursiveDelete(f);
            }
        }
        EasyMock.verify(mMockResolver);
    }
}
