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
package com.android.tradefed.cluster;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.LogFileSaver;
import com.android.tradefed.util.FileUtil;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A {@link ILogSaver} class to upload test outputs to TFC. */
@OptionClass(alias = "cluster", global_namespace = false)
public class ClusterLogSaver implements ILogSaver {

    /** A name of a text file containing all test output file names. */
    public static final String FILE_NAMES_FILE_NAME = "FILES";

    /** A name of a subdirectory containing all files generated by host process. */
    public static final String TOOL_LOG_PATH = "tool-logs";

    /** File picking strategies. */
    public static enum FilePickingStrategy {
        PICK_LAST,
        PICK_FIRST
    }

    @Option(name = "root-dir", description = "A root directory", mandatory = true)
    private File mRootDir;

    @Option(name = "request-id", description = "A request ID", mandatory = true)
    private String mRequestId;

    @Option(name = "command-id", description = "A command ID", mandatory = true)
    private String mCommandId;

    @Option(name = "attempt-id", description = "A command attempt ID", mandatory = true)
    private String mAttemptId;

    @Option(
            name = "output-file-upload-url",
            description = "URL to upload output files to",
            mandatory = true)
    private String mOutputFileUploadUrl;

    @Option(name = "output-file-pattern", description = "Output file patterns")
    private List<String> mOutputFilePatterns = new ArrayList<>();

    @Option(
            name = "context-file-pattern",
            description =
                    "A regex pattern for test context file(s). A test context file is a file to be"
                            + " used in successive invocations to pass context information.")
    private String mContextFilePattern = null;

    @Option(name = "file-picking-strategy", description = "A picking strategy for file(s)")
    private FilePickingStrategy mFilePickingStrategy = FilePickingStrategy.PICK_LAST;

    @Option(
            name = "extra-context-file",
            description =
                    "Additional files to include in the context file. "
                            + "Context file must be a ZIP archive.")
    private List<String> mExtraContextFiles = new ArrayList<>();

    @Option(
            name = "retry-command-line",
            description =
                    "A command line to store in test context. This will replace the original"
                            + " command line in a retry invocation.")
    private String mRetryCommandLine = null;

    private File mLogDir;
    private LogFileSaver mLogFileSaver = null;
    private IClusterClient mClusterClient = null;

    @Override
    public void invocationStarted(IInvocationContext context) {
        mLogDir = new File(mRootDir, "logs");
        mLogFileSaver = new LogFileSaver(mLogDir);
    }

    private Path getRelativePath(Path p) {
        return mRootDir.toPath().relativize(p);
    }

    /** Returns a Path stream for all files under a directory matching a given pattern. */
    @SuppressWarnings("StreamResourceLeak")
    private Stream<Path> getPathStream(final File dir, final Pattern pattern) throws IOException {
        return Files.find(
                dir.toPath(),
                Integer.MAX_VALUE,
                (path, attr) ->
                        attr.isRegularFile()
                                && pattern.matcher(getRelativePath(path).toString()).matches(),
                FileVisitOption.FOLLOW_LINKS);
    }

    private Set<File> findFilesRecursively(final File dir, final String regex) {
        final Pattern pattern = Pattern.compile(regex);
        try (final Stream<Path> stream = getPathStream(dir, pattern)) {
            return stream.map(Path::toFile).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed to collect output files", e);
        }
    }

    private Set<String> getGroupNames(final String regex) {
        final Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Find a test context file and collect environment vars if exists.
     *
     * <p>If there are multiple matches, it will only collect the first or last one in
     * lexicographical order according to a given file picking strategy. If a newly collected
     * environment var already exists in a given map, it will be overridden.
     *
     * @param dir a root directory.
     * @param regex a regex pattern for a context file path. A relative path is used for matching.
     * @param strategy a file picking strategy.
     * @param envVars a map for environment vars.
     * @return a {@link File} object.
     */
    @VisibleForTesting
    File findTestContextFile(
            final File dir,
            final String regex,
            final FilePickingStrategy strategy,
            final Map<String, String> envVars) {
        final Pattern pattern = Pattern.compile(regex);
        try (Stream<Path> stream = getPathStream(dir, pattern)) {
            Optional<Path> op = null;
            switch (strategy) {
                case PICK_FIRST:
                    op = stream.sorted().findFirst();
                    break;
                case PICK_LAST:
                    op = stream.sorted(Comparator.reverseOrder()).findFirst();
                    break;
            }
            if (op == null || !op.isPresent()) {
                return null;
            }
            final Path p = op.get();
            Set<String> groupNames = getGroupNames(regex);
            CLog.d("Context var names: %s", groupNames);
            Path relPath = dir.toPath().relativize(p);
            Matcher matcher = pattern.matcher(relPath.toString());
            // One needs to call matches() before calling group() method.
            matcher.matches();
            for (final String name : groupNames) {
                final String value = matcher.group(name);
                if (value == null) {
                    continue;
                }
                envVars.put(name, value);
            }
            return p.toFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to collect a context file", e);
        }
    }

    /** Determine a file's new path after applying an optional prefix. */
    private String getDestinationPath(String prefix, File file) {
        String filename = file.getName();
        return prefix == null ? filename : Paths.get(prefix, filename).toString();
    }

    /**
     * Create a text file containing a list of file names.
     *
     * @param filenames filenames to write.
     * @param destFile a {@link File} where to write names to.
     * @throws IOException if writing fails
     */
    private void writeFilenamesToFile(Set<String> filenames, File destFile) throws IOException {
        String content = filenames.stream().sorted().collect(Collectors.joining("\n"));
        FileUtil.writeToFile(content, destFile);
    }

    /**
     * Upload files to mOutputFileUploadUrl.
     *
     * @param fileMap a {@link Map} of file and destination path string pairs.
     * @return a {@link Map} of file and URL pairs.
     */
    private Map<File, String> uploadFiles(Map<File, String> fileMap, FilePickingStrategy strategy) {
        // construct a map of unique destination paths and files, to prevent duplicate uploads
        Map<String, File> destinationMap =
                fileMap.entrySet()
                        .stream()
                        .sorted(Comparator.comparing(Map.Entry::getKey)) // sort by filename
                        .collect(
                                Collectors.toMap(
                                        e -> getDestinationPath(e.getValue(), e.getKey()),
                                        Map.Entry::getKey,
                                        // use strategy if two files have the same destination
                                        (first, second) ->
                                                strategy == FilePickingStrategy.PICK_FIRST
                                                        ? first
                                                        : second));
        fileMap.keySet().retainAll(destinationMap.values());
        CLog.i("Collected %d files to upload", fileMap.size());
        fileMap.keySet().forEach(f -> CLog.i(f.getAbsolutePath()));

        // Create a file names file.
        File fileNamesFile = new File(mRootDir, FILE_NAMES_FILE_NAME);
        try {
            writeFilenamesToFile(destinationMap.keySet(), fileNamesFile);
        } catch (IOException e) {
            CLog.e("Failed to write %s", fileNamesFile.getAbsolutePath());
        }

        final TestOutputUploader uploader = getTestOutputUploader();
        try {
            uploader.setUploadUrl(mOutputFileUploadUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to set upload URL", e);
        }

        fileMap.put(fileNamesFile, null);
        final Map<File, String> fileUrls = new TreeMap<>();
        int index = 1;
        for (Map.Entry<File, String> entry : fileMap.entrySet()) {
            File file = entry.getKey();
            CLog.i("Uploading file %d of %d: %s", index, fileMap.size(), file.getAbsolutePath());
            try {
                fileUrls.put(file, uploader.uploadFile(file, entry.getValue()));
            } catch (IOException | RuntimeException e) {
                CLog.e("Failed to upload %s: %s", file, e);
            }
            index++;
        }
        return fileUrls;
    }

    /** If the context file is a zip file, will append the specified files to it. */
    @VisibleForTesting
    void appendFilesToContext(File contextFile, List<String> filesToAdd) {
        if (filesToAdd.isEmpty()) {
            return;
        }

        // create new ZIP file system which allows creating files
        URI uri = URI.create("jar:" + contextFile.toURI());
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        try (FileSystem zip = FileSystems.newFileSystem(uri, env)) {
            // copy files into the zip file, will not overwrite existing files
            for (String filename : filesToAdd) {
                Path path = Paths.get(filename);
                if (!path.isAbsolute()) {
                    path = mRootDir.toPath().resolve(path);
                }
                if (!path.toFile().exists()) {
                    CLog.w("File %s not found", path);
                    continue;
                }
                Path zipPath = zip.getPath(path.getFileName().toString());
                Files.copy(path, zipPath);
            }
        } catch (IOException | RuntimeException e) {
            CLog.w("Failed to append files to context");
            CLog.e(e);
        }
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        // Key is the file to be uploaded. Value is the destination path to upload url.
        // For example, to upload a.txt to uploadUrl/path1/, the destination path is "path1";
        // To upload a.txt to uploadUrl/, the destination path is null.
        final Map<File, String> outputFiles = new HashMap<>();
        File contextFile = null;
        Map<String, String> envVars = new TreeMap<>();

        // Get a list of log files to upload (skip host_log_*.txt to prevent duplicate upload)
        findFilesRecursively(mLogDir, "^((?!host_log_\\d+).)*$")
                .forEach(file -> outputFiles.put(file, TOOL_LOG_PATH));

        // Collect output files to upload
        if (0 < mOutputFilePatterns.size()) {
            final String regex =
                    mOutputFilePatterns
                            .stream()
                            .map((s) -> "(" + s + ")")
                            .collect(Collectors.joining("|"));
            CLog.i("Collecting output files matching regex: " + regex);
            findFilesRecursively(mRootDir, regex).forEach(file -> outputFiles.put(file, null));
        }

        // Collect a context file if exists.
        if (mContextFilePattern != null) {
            CLog.i("Collecting context file matching regex: " + mContextFilePattern);
            contextFile =
                    findTestContextFile(
                            mRootDir, mContextFilePattern, mFilePickingStrategy, envVars);
            if (contextFile != null) {
                CLog.i("Context file = %s", contextFile.getAbsolutePath());
                outputFiles.put(contextFile, null);
                appendFilesToContext(contextFile, mExtraContextFiles);
            } else {
                CLog.i("No context file found");
            }
        }

        final Map<File, String> outputFileUrls = uploadFiles(outputFiles, mFilePickingStrategy);
        if (contextFile != null && outputFileUrls.containsKey(contextFile)) {
            final IClusterClient client = getClusterClient();
            final TestContext testContext = new TestContext();
            testContext.setCommandLine(mRetryCommandLine);
            testContext.addEnvVars(envVars);
            final String name = getRelativePath(contextFile.toPath()).toString();
            testContext.addTestResource(new TestResource(name, outputFileUrls.get(contextFile)));
            try {
                CLog.i("Updating test context: %s", testContext.toString());
                client.updateTestContext(mRequestId, mCommandId, testContext);
            } catch (IOException | JSONException e) {
                throw new RuntimeException("failed to update test context", e);
            }
        }
    }

    @VisibleForTesting
    TestOutputUploader getTestOutputUploader() {
        return new TestOutputUploader();
    }

    /** Get the {@link IClusterClient} instance used to interact with the TFC backend. */
    @VisibleForTesting
    IClusterClient getClusterClient() {
        if (mClusterClient == null) {
            mClusterClient =
                    (IClusterClient)
                            GlobalConfiguration.getInstance()
                                    .getConfigurationObject(IClusterClient.TYPE_NAME);
            if (mClusterClient == null) {
                throw new IllegalStateException("cluster_client not defined in TF global config.");
            }
        }
        return mClusterClient;
    }

    @Override
    public LogFile saveLogData(String dataName, LogDataType dataType, InputStream dataStream)
            throws IOException {
        File log = mLogFileSaver.saveLogData(dataName, dataType, dataStream);
        return new LogFile(log.getAbsolutePath(), null, dataType);
    }

    @Override
    public LogFile saveLogDataRaw(String dataName, LogDataType dataType, InputStream dataStream)
            throws IOException {
        File log = mLogFileSaver.saveLogDataRaw(dataName, dataType.getFileExt(), dataStream);
        return new LogFile(log.getAbsolutePath(), null, dataType);
    }

    @Override
    public LogFile getLogReportDir() {
        return new LogFile(mLogDir.getAbsolutePath(), null, LogDataType.DIR);
    }

    @VisibleForTesting
    String getAttemptId() {
        return mAttemptId;
    }

    @VisibleForTesting
    String getOutputFileUploadUrl() {
        return mOutputFileUploadUrl;
    }

    @VisibleForTesting
    List<String> getOutputFilePatterns() {
        return mOutputFilePatterns;
    }
}
