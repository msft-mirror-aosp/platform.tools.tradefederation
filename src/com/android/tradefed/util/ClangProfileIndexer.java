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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A utility class that indexes Clang code coverage measurements. */
public final class ClangProfileIndexer {
    // Maximum number of profile files before writing the list to a file. Beyond this value,
    // llvm-profdata will use the -f option to read the list from a file to prevent exceeding
    // the command line length limit.
    private static final int MAX_PROFILE_FILES = 100;

    private File mProfileTool;
    private IRunUtil mRunUtil;

    public ClangProfileIndexer(File profileTool) {
        this(profileTool, RunUtil.getDefault());
    }

    public ClangProfileIndexer(File profileTool, IRunUtil runUtil) {
        mProfileTool = profileTool;
        mRunUtil = runUtil;
    }

    /**
     * Indexes raw LLVM profile files and writes the coverage data to the output file.
     *
     * @param rawProfileFiles list of .profraw files to index
     * @param outputFile file to write the results to
     * @throws IOException on tool failure
     */
    public void index(Collection<String> rawProfileFiles, File outputFile) throws IOException {
        Path profileBin = mProfileTool.toPath().resolve("bin/llvm-profdata");
        profileBin.toFile().setExecutable(true);

        List<String> command = new ArrayList<>();
        command.add(profileBin.toString());
        command.add("merge");
        command.add("-sparse");

        File fileList = null;
        try {
            if (rawProfileFiles.size() > MAX_PROFILE_FILES) {
                // Write the measurement file list to a temporary file. This allows large numbers of
                // measurements to not exceed the command line length limit.
                fileList = FileUtil.createTempFile("clang_measurements", ".txt");
                Files.write(fileList.toPath(), rawProfileFiles, Charset.defaultCharset());

                // Add the file containing the list of .profraw files.
                command.add("-f");
                command.add(fileList.getAbsolutePath());
            } else {
                command.addAll(rawProfileFiles);
            }

            command.add("-o");
            command.add(outputFile.getAbsolutePath());

            CommandResult result = mRunUtil.runTimedCmd(0, command.toArray(new String[0]));
            if (result.getStatus() != CommandStatus.SUCCESS) {
                // Retry with -failure-mode=all to still be able to report some coverage.
                command.add("-failure-mode=all");
                result = mRunUtil.runTimedCmd(0, command.toArray(new String[0]));

                if (result.getStatus() != CommandStatus.SUCCESS) {
                    throw new IOException(
                            "Failed to merge Clang profile data:\n" + result.toString());
                }
            }
        } finally {
            FileUtil.deleteFile(fileList);
        }
    }
}
