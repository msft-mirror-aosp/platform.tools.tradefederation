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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.ExecutionFiles;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.AltDirBehavior;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** A utility class that can be used to search for test artifacts. */
public class SearchArtifactUtil {
    // The singleton is used for mocking the non-static methods during testing..
    @VisibleForTesting public static SearchArtifactUtil singleton = new SearchArtifactUtil();
    private static final String MODULE_NAME = "module-name";
    private static final String MODULE_ABI = "module-abi";

    /**
     * Searches for a test artifact/dependency file from the test directory.
     *
     * @param fileName The name of the file to look for.
     * @param targetFirst Whether we are favoring target-side files vs. host-side files for the
     *     search.
     * @return The found artifact file or null if none.
     */
    public static File searchFile(String fileName, boolean targetFirst) {
        return searchFile(fileName, targetFirst, null, null, null, null);
    }

    /**
     * Searches for a test artifact/dependency file from the test directory.
     *
     * @param fileName The name of the file to look for.
     * @param targetFirst Whether we are favoring target-side files vs. host-side files for the
     *     search.
     * @param testInfo The {@link TestInformation} of the current test when available.
     * @return The found artifact file or null if none.
     */
    public static File searchFile(String fileName, boolean targetFirst, TestInformation testInfo) {
        return searchFile(fileName, targetFirst, null, null, null, testInfo);
    }

    /**
     * Searches for a test artifact/dependency file from the test directory.
     *
     * @param fileName The name of the file to look for.
     * @param targetFirst Whether we are favoring target-side files vs. host-side files for the
     *     search.
     * @param abi The {@link IAbi} to match the file.
     * @return The found artifact file or null if none.
     */
    public static File searchFile(String fileName, boolean targetFirst, IAbi abi) {
        return searchFile(fileName, targetFirst, abi, null, null, null);
    }

    /**
     * Searches for a test artifact/dependency file from the test directory.
     *
     * @param fileName The name of the file to look for.
     * @param targetFirst Whether we are favoring target-side files vs. host-side files for the
     *     search.
     * @param altDirs Alternative search paths, in addition to the default search paths.
     * @param altDirBehavior how alternative search paths should be used against default paths: as
     *     fallback, or as override; if unspecified, fallback will be used
     * @return The found artifact file or null if none.
     */
    public static File searchFile(
            String fileName,
            boolean targetFirst,
            List<File> altDirs,
            AltDirBehavior altDirBehavior) {
        return searchFile(fileName, targetFirst, null, altDirs, altDirBehavior, null);
    }

    /**
     * Searches for a test artifact/dependency file from the test directory.
     *
     * @param fileName The name of the file to look for.
     * @param targetFirst Whether we are favoring target-side files vs. host-side files for the
     *     search.
     * @param abi The {@link IAbi} to match the file.
     * @param altDirs Alternative search paths, in addition to the default search paths.
     * @param altDirBehavior how alternative search paths should be used against default paths: as
     *     fallback, or as override; if unspecified, fallback will be used
     * @return The found artifact file or null if none.
     */
    public static File searchFile(
            String fileName,
            boolean targetFirst,
            IAbi abi,
            List<File> altDirs,
            AltDirBehavior altDirBehavior,
            TestInformation testInfo) {
        List<File> searchDirectories =
                singleton.getSearchDirectories(targetFirst, altDirs, altDirBehavior, testInfo);

        // Search in the test directories
        for (File dir : searchDirectories) {
            File file = findFile(fileName, abi, dir);
            if (fileExists(file)) {
                return file;
            }
        }
        // Search in the execution files directly
        ExecutionFiles executionFiles = singleton.getExecutionFiles(testInfo);
        if (executionFiles != null) {
            File file = executionFiles.get(fileName);
            if (fileExists(file)) {
                return file;
            }
        }

        // Search in the build info or stage remote file as fallback
        IBuildInfo buildInfo = singleton.getBuildInfo();
        if (buildInfo != null) {
            File file = buildInfo.getFile(fileName);
            if (fileExists(file)) {
                return file;
            } else {
                // fallback to staging from remote zip files.
                File stagingDir = getModuleDirFromConfig();
                if (stagingDir == null) {
                    stagingDir = getWorkFolder(testInfo);
                }
                if (fileExists(stagingDir)) {
                    buildInfo.stageRemoteFile(fileName, stagingDir);
                    // multiple matching files can be staged. So do a search with module name and
                    // abi in consideration.
                    file = findFile(fileName, abi, stagingDir);
                    if (fileExists(file)) {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.STAGE_UNDEFINED_DEPENDENCY, fileName);
                        return file;
                    }
                }
            }
        }
        CLog.e("Could not find an artifact file associated with %s.", fileName);
        return null;
    }

    /** Returns the list of search locations in correct order. */
    @VisibleForTesting
    List<File> getSearchDirectories(
            boolean targetFirst,
            List<File> altDirs,
            AltDirBehavior altDirBehavior,
            TestInformation testInfo) {
        List<File> dirs = new LinkedList<>();
        // Prioritize the module directory retrieved from the config obj, as this is the ideal place
        // for all test artifacts.
        File moduleDir = getModuleDirFromConfig();
        if (moduleDir != null) {
            dirs.add(moduleDir);
        }

        ExecutionFiles executionFiles = singleton.getExecutionFiles(testInfo);
        if (executionFiles != null) {
            // Add host/testcases or target/testcases directory first
            FilesKey hostOrTarget = FilesKey.HOST_TESTS_DIRECTORY;
            if (targetFirst) {
                hostOrTarget = FilesKey.TARGET_TESTS_DIRECTORY;
            }
            File testcasesDir = executionFiles.get(hostOrTarget);
            if (fileExists(testcasesDir)) {
                dirs.add(testcasesDir);
            }

            // Add root test directory
            File rootTestDir = executionFiles.get(FilesKey.TESTS_DIRECTORY);
            if (fileExists(rootTestDir)) {
                dirs.add(rootTestDir);
            }
        } else {
            // try getting the search directories from the build info.
            IBuildInfo buildInfo = singleton.getBuildInfo();
            if (buildInfo != null) {

                // Add host/testcases or target/testcases directory first
                BuildInfoFileKey hostOrTarget = BuildInfoFileKey.HOST_LINKED_DIR;
                if (targetFirst) {
                    hostOrTarget = BuildInfoFileKey.TARGET_LINKED_DIR;
                }
                File testcasesDir = buildInfo.getFile(hostOrTarget);
                if (fileExists(testcasesDir)) {
                    dirs.add(testcasesDir);
                }

                // Add root test directory
                File rootTestDir = null;
                if (buildInfo instanceof IDeviceBuildInfo) {
                    rootTestDir = ((IDeviceBuildInfo) buildInfo).getTestsDir();
                }
                if (!fileExists(rootTestDir)) {
                    rootTestDir = buildInfo.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
                }
                if (!fileExists(rootTestDir)) {
                    rootTestDir = buildInfo.getFile(BuildInfoFileKey.ROOT_DIRECTORY);
                }
                if (fileExists(rootTestDir)) {
                    dirs.add(rootTestDir);
                }
            }
        }

        // Add alternative directories based on the alt dir behavior
        if (altDirs != null) {
            // reverse the order so ones provided via command line last can be searched first
            Collections.reverse(altDirs);
            if (altDirBehavior == null || AltDirBehavior.FALLBACK.equals(altDirBehavior)) {
                dirs.addAll(altDirs);
            } else {
                altDirs.addAll(dirs);
                dirs = altDirs;
            }
        }

        // Add working directory at the end as a last resort
        File workDir = getWorkFolder(testInfo);
        if (fileExists(workDir)) {
            dirs.add(workDir);
        }
        return dirs;
    }

    /** Searches for the file in the given search directory and possibly matching the abi. */
    private static File findFile(String filename, IAbi abi, File searchDirectory) {
        if (filename == null || searchDirectory == null || !searchDirectory.exists()) {
            return null;
        }
        // Try looking for abi if not provided.
        if (abi == null) {
            abi = findModuleAbi();
        }
        File retFile;
        String moduleName = singleton.findModuleName();
        // Check under module subdirectory first if it is present.
        if (!Strings.isNullOrEmpty(moduleName)) {
            try {
                File moduleDir = FileUtil.findDirectory(moduleName, searchDirectory);
                if (moduleDir != null) {
                    CLog.d("Searching the module dir: %s", moduleDir);
                    // search with abi filtering on first
                    retFile = FileUtil.findFile(filename, abi, moduleDir);
                    if (fileExists(retFile)) {
                        return retFile;
                    }
                    // search without the abi filter
                    retFile = FileUtil.findFile(filename, null, moduleDir);
                    if (fileExists(retFile)) {
                        return retFile;
                    }
                } else {
                    CLog.w(
                            "we have a module name: %s but no directory found in %s.",
                            moduleName, searchDirectory);
                }
            } catch (IOException e) {
                CLog.w(
                        "Something went wrong while searching for the module '%s' directory.",
                        moduleName);
                CLog.e(e);
            }
        }

        // if module subdirectory not present or file not found, search under the entire directory
        try {
            Set<File> allMatch = FileUtil.findFilesObject(searchDirectory, filename, false);
            if (allMatch.size() == 1) {
                // if only one file found, return this one since we can not filter anymore.
                return allMatch.iterator().next();
            } else if (allMatch.size() > 1) {
                // prioritize the top level file to avoid selecting from a wrong module directory.
                for (File f : allMatch) {
                    if (searchDirectory.getAbsolutePath().equals(f.getParent())) {
                        return f;
                    }
                }
                // if top level not found, prioritize the one with the correct abi
                if (abi != null) {
                    for (File f : allMatch) {
                        if (f.getParentFile()
                                .getName()
                                .equals(AbiUtils.getArchForAbi(abi.getName()))) {
                            return f;
                        }
                    }
                }
                // if abi not matches, return any of them
                return allMatch.iterator().next();
            }
        } catch (IOException e) {
            CLog.w(
                    "Something went wrong while searching for file %s under the directory '%s'.",
                    filename, moduleName);
            CLog.e(e);
        }
        CLog.w("Failed to find test file %s from directory %s.", filename, searchDirectory);
        return null;
    }

    public static File getModuleDirFromConfig(IInvocationContext moduleContext) {
        if (moduleContext != null) {
            List<String> moduleDirPath =
                    moduleContext
                            .getConfigurationDescriptor()
                            .getMetaData(ConfigurationDescriptor.MODULE_DIR_PATH_KEY);
            if (moduleDirPath != null && !moduleDirPath.isEmpty()) {
                File moduleDir = new File(moduleDirPath.get(0));
                if (moduleDir.exists()) {
                    return moduleDir;
                }
            }
        }
        return null;
    }

    /** Returns the module directory if present, when called inside a module scope. */
    public static File getModuleDirFromConfig() {
        IInvocationContext moduleContext = CurrentInvocation.getModuleContext();
        return getModuleDirFromConfig(moduleContext);
    }

    /**
     * Finds the module directory that matches the given module name
     *
     * @param moduleName The name of the module.
     * @param targetFirst Whether we are favoring target-side vs. host-side for the search.
     * @return the module directory. Can be null.
     */
    public static File findModuleDir(String moduleName, boolean targetFirst) {
        try (CloseableTraceScope ignored = new CloseableTraceScope("findModuleDir")) {
            List<File> searchDirectories =
                    singleton.getSearchDirectories(targetFirst, null, null, null);
            for (File searchDirectory : searchDirectories) {
                try {
                    File moduleDir = FileUtil.findDirectory(moduleName, searchDirectory);
                    if (moduleDir != null && moduleDir.exists()) {
                        return moduleDir;
                    }
                } catch (IOException e) {
                    CLog.w(
                            "Something went wrong while searching for the module '%s' directory in"
                                    + " %s.",
                            moduleName, searchDirectory);
                    CLog.e(e);
                }
            }
            return null;
        }
    }

    /** returns the module name for the current test invocation if present. */
    @VisibleForTesting
    String findModuleName() {
        IInvocationContext moduleContext = CurrentInvocation.getModuleContext();
        if (moduleContext != null && moduleContext.getAttributes().get(MODULE_NAME) != null) {
            return moduleContext.getAttributes().get(MODULE_NAME).get(0);
        } else if (moduleContext != null
                && moduleContext.getConfigurationDescriptor().getModuleName() != null) {
            return moduleContext.getConfigurationDescriptor().getModuleName();
        }
        return null;
    }

    /** returns the abi for the current module if present. */
    private static IAbi findModuleAbi() {
        IInvocationContext moduleContext = CurrentInvocation.getModuleContext();
        if (moduleContext != null && moduleContext.getAttributes().get(MODULE_ABI) != null) {
            String abiName = moduleContext.getAttributes().get(MODULE_ABI).get(0);
            return new Abi(abiName, AbiUtils.getBitness(abiName));
        }
        return null;
    }

    /** returns the primary build info for the current invocation. */
    @VisibleForTesting
    IBuildInfo getBuildInfo() {
        IInvocationContext context = CurrentInvocation.getInvocationContext();
        if (context != null
                && context.getBuildInfos() != null
                && !context.getBuildInfos().isEmpty()) {
            return context.getBuildInfos().get(0);
        }
        return null;
    }

    @VisibleForTesting
    ExecutionFiles getExecutionFiles(TestInformation testInfo) {
        if (testInfo != null && testInfo.executionFiles() != null) {
            return testInfo.executionFiles();
        }
        return CurrentInvocation.getInvocationFiles();
    }

    private static File getWorkFolder(TestInformation testInfo) {
        if (testInfo != null && testInfo.dependenciesFolder() != null) {
            return testInfo.dependenciesFolder();
        }
        return CurrentInvocation.getInfo(InvocationInfo.WORK_FOLDER);
    }

    private static boolean fileExists(File file) {
        return file != null && file.exists();
    }
}
