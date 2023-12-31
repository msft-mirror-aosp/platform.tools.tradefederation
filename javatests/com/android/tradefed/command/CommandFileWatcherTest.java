/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tradefed.command;

import static org.mockito.Mockito.verify;

import com.android.tradefed.command.CommandFileWatcher.ICommandFileListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Unit tests for {@link CommandFileWatcher}. Mocks all file system accesses. */
@RunWith(JUnit4.class)
public class CommandFileWatcherTest {
    private static final List<String> EMPTY_ARGS = Collections.<String>emptyList();
    private static final List<String> EMPTY_DEPENDENCIES = Collections.<String>emptyList();

    private CommandFileWatcher mWatcher = null;
    @Mock ICommandFileListener mMockListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mWatcher = new CommandFileWatcher(mMockListener);
    }

    /**
     * Make sure we get a parse attempt if the mod time changes immediately after we start running
     */
    @Test
    public void testImmediateChange() throws Exception {
        final File cmdFile = new ModFile("/a/path/too/far", 1, 2);
        mWatcher.addCmdFile(cmdFile, EMPTY_ARGS, EMPTY_DEPENDENCIES);

        mWatcher.checkForUpdates();

        verify(mMockListener).notifyFileChanged(cmdFile, EMPTY_ARGS);
    }

    /** Make sure we _don't_ get a notify call if the mod time never changes. */
    @Test
    public void testNoChange() throws Exception {
        final File cmdFile = new ModFile("/a/path/too/far", 1, 1, 1);
        mWatcher.addCmdFile(cmdFile, EMPTY_ARGS, EMPTY_DEPENDENCIES);

        mWatcher.checkForUpdates();
        mWatcher.checkForUpdates();
    }

    /**
     * Make sure that we behave properly when watching multiple primary command files. This means
     * that we should reload only the changed file
     */
    @Test
    public void testMultipleCmdFiles() throws Exception {
        final File cmdFile1 = new ModFile("/went/too/far", 1, 1);
        final File cmdFile2 = new ModFile("/knew/too/much", 1, 2);
        mWatcher.addCmdFile(cmdFile1, EMPTY_ARGS, EMPTY_DEPENDENCIES);
        mWatcher.addCmdFile(cmdFile2, EMPTY_ARGS, EMPTY_DEPENDENCIES);

        mWatcher.checkForUpdates();

        verify(mMockListener).notifyFileChanged(cmdFile2, EMPTY_ARGS);
    }

    /**
     * Make sure that we behave properly when watching a primary command file as well as its
     * dependencies. In this case, we should only reload the primary command files, even though only
     * the dependencies changed.
     */
    @Test
    public void testDependencies() throws Exception {
        final File cmdFile1 = new ModFile("/went/too/far", 1, 1);
        final File cmdFile2 = new ModFile("/knew/too/much", 1, 1);
        final File dependent = new ModFile("/those/are/my/lines", 1, 2);
        mWatcher.addCmdFile(cmdFile1, EMPTY_ARGS, Arrays.asList(dependent));
        mWatcher.addCmdFile(cmdFile2, EMPTY_ARGS, EMPTY_DEPENDENCIES);

        mWatcher.checkForUpdates();

        verify(mMockListener).notifyFileChanged(cmdFile1, EMPTY_ARGS);
    }

    /**
     * Make sure that we behave properly when watching a primary command file as well as its
     * dependencies. In this case, we should only reload the primary command files, even though only
     * the dependencies changed.
     */
    @Test
    public void testMultipleDependencies() throws Exception {
        final File cmdFile1 = new ModFile("/went/too/far", 1, 1, 1);
        final File cmdFile2 = new ModFile("/knew/too/much", 1, 1, 1);
        final File dep1 = new ModFile("/those/are/my/lines", 1, 2, 2);
        final File dep2 = new ModFile("/ceci/n'est/pas/une/line", 1, 1, 1);
        mWatcher.addCmdFile(cmdFile1, EMPTY_ARGS, Arrays.asList(dep1, dep2));
        mWatcher.addCmdFile(cmdFile2, EMPTY_ARGS, Arrays.asList(dep2));

        mWatcher.checkForUpdates();

        verify(mMockListener).notifyFileChanged(cmdFile1, EMPTY_ARGS);
    }

    @Test
    public void testReplacingWatchedFile() {
        final File cmdFile1 = new ModFile("/went/too/far", 1, 1, 1, 1);
        final File dep1 = new ModFile("/those/are/my/lines", 1, 2, 3, 4);
        final File dep2 = new ModFile("/ceci/n'est/pas/une/line", 1, 1, 1, 1);

        mWatcher.addCmdFile(cmdFile1, EMPTY_ARGS, Arrays.asList(dep1));
        mWatcher.checkForUpdates();
        mWatcher.addCmdFile(cmdFile1, EMPTY_ARGS, Arrays.asList(dep2));
        // don't expect a second notify here, because dependent file has changed from dep1 to dep2
        mWatcher.checkForUpdates();

        verify(mMockListener).notifyFileChanged(cmdFile1, EMPTY_ARGS);
    }

    /** A File extension that allows a list of modtimes to be set. */
    @SuppressWarnings("serial")
    private static class ModFile extends File {
        private long[] mModTimes = null;
        private int mCurrentIdx = 0;

        public ModFile(String path, long... modTimes) {
            super(path);
            mModTimes = modTimes;
        }

        @Override
        public long lastModified() {
            if (mCurrentIdx >= mModTimes.length) {
                throw new IllegalStateException("Unexpected call to #lastModified");
            }
            return mModTimes[mCurrentIdx++];
        }
    }
}
