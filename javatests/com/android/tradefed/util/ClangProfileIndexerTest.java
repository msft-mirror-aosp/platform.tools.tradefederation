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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/** Unit tests for {@link ClangProfileIndexer}. */
@RunWith(JUnit4.class)
public class ClangProfileIndexerTest {
    private static final int MAX_PROFILE_FILES = 100;

    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Spy CommandArgumentCaptor mCommandArgumentCaptor;

    /** Object under test. */
    ClangProfileIndexer mIndexer;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mIndexer = new ClangProfileIndexer(new File("/path/to/llvm-tools"), mCommandArgumentCaptor);
    }

    @Test
    public void testProfileFiles_includesFiles() throws Exception {
        mCommandArgumentCaptor.setResult(CommandStatus.SUCCESS);
        List<String> profileFiles =
                ImmutableList.of("/path/to/clang-1.profraw", "/path/to/clang-2.profraw");
        File outputFile = folder.newFile();

        mIndexer.index(profileFiles, outputFile);

        List<String> command = mCommandArgumentCaptor.getCommand();

        // Check contents of the command line contain what we expect.
        assertThat(command)
                .containsAtLeast(
                        "/path/to/llvm-tools/bin/llvm-profdata",
                        "merge",
                        outputFile.getAbsolutePath());
        assertThat(command).containsAtLeastElementsIn(profileFiles);
    }

    @Test
    public void testLargeProfileCount_usesFile() throws Exception {
        mCommandArgumentCaptor.setResult(CommandStatus.SUCCESS);
        List<String> profileFiles = new ArrayList<>(MAX_PROFILE_FILES + 5);
        for (int i = 0; i < MAX_PROFILE_FILES + 5; i++) {
            profileFiles.add(String.format("path/to/clang-%d.profraw", i));
        }

        mIndexer.index(profileFiles, folder.newFile());
        List<String> command = mCommandArgumentCaptor.getCommand();

        // Contains the `-f` argument that points to a file containing the list of profile files.
        assertThat(command.size()).isLessThan(MAX_PROFILE_FILES);
        assertThat(command).contains("-f");
        checkListDoesNotContainSuffix(command, ".profraw");
    }

    @Test
    public void testSingleFailure_usesFailureMode() throws Exception {
        mCommandArgumentCaptor.setResult(CommandStatus.FAILED).setResult(CommandStatus.SUCCESS);
        List<String> profileFiles =
                ImmutableList.of("/path/to/clang-1.profraw", "/path/to/clang-2.profraw");

        mIndexer.index(profileFiles, folder.newFile());

        List<String> command = mCommandArgumentCaptor.getCommand();
        assertThat(command).contains("-failure-mode=all");
    }

    @Test
    public void testMultipleFailures_throwsException() throws Exception {
        mCommandArgumentCaptor.setResult(CommandStatus.FAILED).setResult(CommandStatus.FAILED);
        List<String> profileFiles = ImmutableList.of("/path/to/clang-1.profraw");

        try {
            mIndexer.index(profileFiles, folder.newFile());
            fail("should have thrown an exception");
        } catch (IOException e) {
            // Expected
        }
    }

    abstract static class CommandArgumentCaptor implements IRunUtil {
        private List<String> mCommand = new ArrayList<>();
        private Queue<CommandStatus> mResults = new ArrayDeque<>();

        /** Stores the command for retrieval later. */
        @Override
        public CommandResult runTimedCmd(long timeout, String... cmd) {
            mCommand = Arrays.asList(cmd);
            return new CommandResult(mResults.remove());
        }

        CommandArgumentCaptor setResult(CommandStatus status) {
            mResults.add(status);
            return this;
        }

        List<String> getCommand() {
            return mCommand;
        }

        /** Ignores sleep calls. */
        @Override
        public void sleep(long ms) {}
    }

    /** Utility function to verify that certain suffixes are contained in the List. */
    void checkListContainsSuffixes(List<String> list, List<String> suffixes) {
        for (String suffix : suffixes) {
            boolean found = false;
            for (String item : list) {
                if (item.endsWith(suffix)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                fail("List " + list.toString() + " does not contain suffix '" + suffix + "'");
            }
        }
    }

    void checkListDoesNotContainSuffix(List<String> list, String suffix) {
        for (String item : list) {
            if (item.endsWith(suffix)) {
                fail("List " + list.toString() + " should not contain suffix '" + suffix + "'");
            }
        }
    }
}
