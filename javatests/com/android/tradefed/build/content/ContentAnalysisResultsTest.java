/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.build.content;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

/** Unit tests for {@link ContentAnalysisResults}. */
@RunWith(JUnit4.class)
public class ContentAnalysisResultsTest {

    @Test
    public void testNoModification() {
        ContentAnalysisResults results =
                new ContentAnalysisResults().addUnchangedFile().addUnchangedModule("module1");

        assertFalse(results.hasAnyTestsChange());
    }

    @Test
    public void testModifiedFiles() {
        ContentAnalysisResults results = new ContentAnalysisResults().addModifiedFile();

        assertTrue(results.hasAnyTestsChange());
    }

    @Test
    public void testModifiedModules() {
        ContentAnalysisResults results = new ContentAnalysisResults().addModifiedModule("module1");

        assertTrue(results.hasAnyTestsChange());
    }

    @Test
    public void testModifiedModulesMerge() {
        ContentAnalysisResults result1 =
                new ContentAnalysisResults()
                        .addUnchangedModule("module1")
                        .addUnchangedModule("module2");
        ContentAnalysisResults result2 = new ContentAnalysisResults().addModifiedModule("module1");

        ContentAnalysisResults merge =
                ContentAnalysisResults.mergeResults(Arrays.asList(result1, result2));
        assertTrue(merge.hasAnyTestsChange());
        Truth.assertThat(merge.getUnchangedModules()).containsExactly("module2");
    }
}
