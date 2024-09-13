/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tradefed.util;

import static org.mockito.Mockito.when;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.invoker.ExecutionFiles;
import com.android.tradefed.targetprep.AltDirBehavior;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/** Unit tests for {@link SearchArtifactUtil} */
@RunWith(JUnit4.class)
public class SearchArtifactUtilTest {
    /**
     * When file is present in multiple modules, including top level folder, and module name is
     * present, it should return the file from the correct module directory.
     */
    @Test
    public void testSearchFile_multipleMatchWithModuleName() throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");
            File correctModuleFile = new File(searchDirectory, "correctModule/testfile.txt");
            correctModuleFile.getParentFile().mkdirs();
            correctModuleFile.createNewFile();

            File wrongModuleFile = new File(searchDirectory, "wrongModule/testfile.txt");
            wrongModuleFile.getParentFile().mkdirs();
            wrongModuleFile.createNewFile();

            File topLevelFile = new File(searchDirectory, "testfile.txt");
            topLevelFile.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");

            File f = SearchArtifactUtil.searchFile("testfile.txt", false);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(correctModuleFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * When file is present in multiple modules, including top level folder, but module name is not
     * present, it should return the file from the top level directory.
     */
    @Test
    public void testSearchFile_multipleMatchWithoutModuleName() throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");
            File module1File = new File(searchDirectory, "moduleName1/testfile.txt");
            module1File.getParentFile().mkdirs();
            module1File.createNewFile();

            File module2File = new File(searchDirectory, "moduleName2/testfile.txt");
            module2File.getParentFile().mkdirs();
            module2File.createNewFile();

            File topLevelFile = new File(searchDirectory, "testfile.txt");
            topLevelFile.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn(null);

            File f = SearchArtifactUtil.searchFile("testfile.txt", false);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(topLevelFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * When file is present in multiple modules, including top level folder, and both module name
     * and abi is present, it should return the file from the correct module directory with the
     * correct abi.
     */
    @Test
    public void testSearchFile_multipleMatchWithModuleNameAndAbi() throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");
            File armFile = new File(searchDirectory, "correctModule/arm/testfile.txt");
            armFile.getParentFile().mkdirs();
            armFile.createNewFile();

            File arm64File = new File(searchDirectory, "correctModule/arm64/testfile.txt");
            arm64File.getParentFile().mkdirs();
            arm64File.createNewFile();

            File armFile2 = new File(searchDirectory, "wrongModule/arm/testfile.txt");
            armFile2.getParentFile().mkdirs();
            armFile2.createNewFile();

            File arm64File2 = new File(searchDirectory, "wrongModule/arm64/testfile.txt");
            arm64File2.getParentFile().mkdirs();
            arm64File2.createNewFile();

            File topLevelFile = new File(searchDirectory, "testfile.txt");
            topLevelFile.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");

            IAbi arm64abi = new Abi("arm64-v8a", "64");
            File f = SearchArtifactUtil.searchFile("testfile.txt", false, arm64abi);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(arm64File.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * When file is present in multiple modules, including in the top level folder, And abi is given
     * but module is not present, it should ignore the abi and return the top level file in order to
     * avoid selecting a file from the wrong module.
     */
    @Test
    public void testSearchFile_multipleMatchWithAbiButNoModuleName() throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");

            File armFile = new File(searchDirectory, "wrongModule/arm/testfile.txt");
            armFile.getParentFile().mkdirs();
            armFile.createNewFile();

            File arm64File = new File(searchDirectory, "wrongModule/arm64/testfile.txt");
            arm64File.getParentFile().mkdirs();
            arm64File.createNewFile();

            File topLevelFile = new File(searchDirectory, "testfile.txt");
            topLevelFile.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn(null);

            IAbi arm64abi = new Abi("arm64-v8a", "64");
            File f = SearchArtifactUtil.searchFile("testfile.txt", false, arm64abi);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(topLevelFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * When file is present in multiple places, but not in the top level folder, And abi is given
     * but module is not present, it should return the file that matches the abi.
     */
    @Test
    public void testSearchFile_multipleMatchNoTopLevelMatchWithAbiButNoModuleName()
            throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");

            File armFile = new File(searchDirectory, "wrongModule/arm/testfile.txt");
            armFile.getParentFile().mkdirs();
            armFile.createNewFile();

            File arm64File = new File(searchDirectory, "wrongModule/arm64/testfile.txt");
            arm64File.getParentFile().mkdirs();
            arm64File.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn(null);

            IAbi arm64abi = new Abi("arm64-v8a", "64");
            File f = SearchArtifactUtil.searchFile("testfile.txt", false, arm64abi);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(arm64File.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * When only one file is present, it should ignore module name and abi even if they are present.
     */
    @Test
    public void testSearchFile_singleNonAbiNonModuleMatchWithAbiAndModuleNamePresent()
            throws IOException {
        File searchDirectory = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");

            File armFile = new File(searchDirectory, "wrongModule/arm/testfile.txt");
            armFile.getParentFile().mkdirs();
            armFile.createNewFile();

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");

            IAbi arm64abi = new Abi("arm64-v8a", "64");
            File f = SearchArtifactUtil.searchFile("testfile.txt", false, arm64abi);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(armFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
        }
    }

    /**
     * when no matching file is present in the search directories, but is present in the
     * ExecutionFiles, it should be returned.
     */
    @Test
    public void testSearchFile_matchInExecutionFile() throws IOException {
        File searchDirectory = null;
        File correctFile = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");
            File wrongModuleFile = new File(searchDirectory, "wrongModule/wrongTestFile.txt");
            wrongModuleFile.getParentFile().mkdirs();
            wrongModuleFile.createNewFile();

            File wrongTopLevelFile = new File(searchDirectory, "wrongTestFile.txt");
            wrongTopLevelFile.createNewFile();

            correctFile = FileUtil.createTempFile("correctFile", ".txt");
            ExecutionFiles executionFiles = Mockito.mock(ExecutionFiles.class);
            when(executionFiles.get(correctFile.getName())).thenReturn(correctFile);

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");
            when(SearchArtifactUtil.singleton.getExecutionFiles(null)).thenReturn(executionFiles);

            File f = SearchArtifactUtil.searchFile(correctFile.getName(), false);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(correctFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
            FileUtil.deleteFile(correctFile);
        }
    }

    /**
     * when no matching file is present in the search directories, but is present in the IBuildInfo,
     * it should be returned.
     */
    @Test
    public void testSearchFile_matchInIBuildInfo() throws IOException {
        File searchDirectory = null;
        File correctFile = null;
        try {
            searchDirectory = FileUtil.createTempDir("test-dir");
            File wrongModuleFile = new File(searchDirectory, "wrongModule/wrongTestFile.txt");
            wrongModuleFile.getParentFile().mkdirs();
            wrongModuleFile.createNewFile();

            File wrongTopLevelFile = new File(searchDirectory, "wrongTestFile.txt");
            wrongTopLevelFile.createNewFile();

            correctFile = FileUtil.createTempFile("correctFile", ".txt");
            IBuildInfo buildInfo = Mockito.mock(IBuildInfo.class);
            when(buildInfo.getFile(correctFile.getName())).thenReturn(correctFile);

            List<File> searchDirectories = new LinkedList<>();
            searchDirectories.add(searchDirectory);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenReturn(searchDirectories);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");
            when(SearchArtifactUtil.singleton.getBuildInfo()).thenReturn(buildInfo);

            File f = SearchArtifactUtil.searchFile(correctFile.getName(), false);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(correctFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(searchDirectory);
            FileUtil.deleteFile(correctFile);
        }
    }

    /**
     * When alt-dir is provided with OVERRIDE behavior, it should return the file from the alt-dir
     * even if it is present in the other search directories.
     */
    @Test
    public void testSearchFile_withAltDirOverRide() throws IOException {
        File testDir = null;
        File altDir = null;
        try {
            testDir = FileUtil.createTempDir("test-dir");
            File correctModuleFile = new File(testDir, "correctModule/testfile.txt");
            correctModuleFile.getParentFile().mkdirs();
            correctModuleFile.createNewFile();

            File wrongModuleFile = new File(testDir, "wrongModule/testfile.txt");
            wrongModuleFile.getParentFile().mkdirs();
            wrongModuleFile.createNewFile();

            File topLevelFile = new File(testDir, "testfile.txt");
            topLevelFile.createNewFile();

            altDir = FileUtil.createTempDir("altDir");
            File altFile = new File(altDir, "testfile.txt");
            altFile.createNewFile();
            List<File> altDirs = new LinkedList<>();
            altDirs.add(altDir);

            ExecutionFiles executionFiles = Mockito.mock(ExecutionFiles.class);
            when(executionFiles.get(ExecutionFiles.FilesKey.TESTS_DIRECTORY)).thenReturn(testDir);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.findModuleName()).thenReturn("correctModule");
            when(SearchArtifactUtil.singleton.getExecutionFiles(null)).thenReturn(executionFiles);
            when(SearchArtifactUtil.singleton.getSearchDirectories(
                            false, altDirs, AltDirBehavior.OVERRIDE, null))
                    .thenCallRealMethod();

            File f =
                    SearchArtifactUtil.searchFile(
                            "testfile.txt", false, altDirs, AltDirBehavior.OVERRIDE);
            Truth.assertThat(f).isNotNull();
            Truth.assertThat(f.getAbsolutePath()).isEqualTo(altFile.getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(testDir);
            FileUtil.recursiveDelete(altDir);
        }
    }

    /**
     * Should return the correct module directory from the target/testcases parent directory when
     * target first is true.
     */
    @Test
    public void testFindModuleDir_whenTargetFirst() throws IOException {
        File testDir = null;
        try {
            testDir = FileUtil.createTempDir("test-dir");
            File hostDir = FileUtil.createNamedTempDir(testDir, "host/testcases");
            File targetDir = FileUtil.createNamedTempDir(testDir, "target/testcases");

            File hostCorretModuleFile = new File(hostDir, "correctModule/testfile.txt");
            hostCorretModuleFile.getParentFile().mkdirs();
            hostCorretModuleFile.createNewFile();
            File hostWrongModuleFile = new File(hostDir, "wrongModule/testfile.txt");
            hostWrongModuleFile.getParentFile().mkdirs();
            hostWrongModuleFile.createNewFile();

            File targetCorrectModuleFile = new File(targetDir, "correctModule/testfile.txt");
            targetCorrectModuleFile.getParentFile().mkdirs();
            targetCorrectModuleFile.createNewFile();
            File targetWrongModuleFile = new File(targetDir, "wrongModule/testfile.txt");
            targetWrongModuleFile.getParentFile().mkdirs();
            targetWrongModuleFile.createNewFile();

            ExecutionFiles executionFiles = Mockito.mock(ExecutionFiles.class);
            when(executionFiles.get(ExecutionFiles.FilesKey.TESTS_DIRECTORY)).thenReturn(testDir);
            when(executionFiles.get(ExecutionFiles.FilesKey.HOST_TESTS_DIRECTORY))
                    .thenReturn(hostDir);
            when(executionFiles.get(ExecutionFiles.FilesKey.TARGET_TESTS_DIRECTORY))
                    .thenReturn(targetDir);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getExecutionFiles(null)).thenReturn(executionFiles);
            when(SearchArtifactUtil.singleton.getSearchDirectories(true, null, null, null))
                    .thenCallRealMethod();

            File dir = SearchArtifactUtil.findModuleDir("correctModule", true);
            Truth.assertThat(dir).isNotNull();
            Truth.assertThat(dir.getAbsolutePath())
                    .isEqualTo(targetCorrectModuleFile.getParentFile().getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(testDir);
        }
    }

    /**
     * Should return the correct module directory from the host/testcases parent directory when
     * target first is false.
     */
    @Test
    public void testFindModuleDir_whenNotTargetFirst() throws IOException {
        File testDir = null;
        try {
            testDir = FileUtil.createTempDir("test-dir");
            File hostDir = FileUtil.createNamedTempDir(testDir, "host/testcases");
            File targetDir = FileUtil.createNamedTempDir(testDir, "target/testcases");

            File hostCorretModuleFile = new File(hostDir, "correctModule/testfile.txt");
            hostCorretModuleFile.getParentFile().mkdirs();
            hostCorretModuleFile.createNewFile();
            File hostWrongModuleFile = new File(hostDir, "wrongModule/testfile.txt");
            hostWrongModuleFile.getParentFile().mkdirs();
            hostWrongModuleFile.createNewFile();

            File targetCorrectModuleFile = new File(targetDir, "correctModule/testfile.txt");
            targetCorrectModuleFile.getParentFile().mkdirs();
            targetCorrectModuleFile.createNewFile();
            File targetWrongModuleFile = new File(targetDir, "wrongModule/testfile.txt");
            targetWrongModuleFile.getParentFile().mkdirs();
            targetWrongModuleFile.createNewFile();

            ExecutionFiles executionFiles = Mockito.mock(ExecutionFiles.class);
            when(executionFiles.get(ExecutionFiles.FilesKey.TESTS_DIRECTORY)).thenReturn(testDir);
            when(executionFiles.get(ExecutionFiles.FilesKey.HOST_TESTS_DIRECTORY))
                    .thenReturn(hostDir);
            when(executionFiles.get(ExecutionFiles.FilesKey.TARGET_TESTS_DIRECTORY))
                    .thenReturn(targetDir);

            SearchArtifactUtil.singleton = Mockito.mock(SearchArtifactUtil.class);
            when(SearchArtifactUtil.singleton.getExecutionFiles(null)).thenReturn(executionFiles);
            when(SearchArtifactUtil.singleton.getSearchDirectories(false, null, null, null))
                    .thenCallRealMethod();

            File dir = SearchArtifactUtil.findModuleDir("correctModule", false);
            Truth.assertThat(dir).isNotNull();
            Truth.assertThat(dir.getAbsolutePath())
                    .isEqualTo(hostCorretModuleFile.getParentFile().getAbsolutePath());
        } finally {
            FileUtil.recursiveDelete(testDir);
        }
    }
}
