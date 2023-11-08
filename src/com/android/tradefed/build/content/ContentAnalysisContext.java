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

/** Provide the context surrounding a content to analyze it properly. */
public class ContentAnalysisContext {

    /** This describes what to expect from the content structure for proper analysis. */
    public enum AnalysisMethod {
        FILE,
        MODULE_XTS,
        SANDBOX_WORKDIR
    }

    private final String contentEntry;
    private final ContentInformation information;
    private final AnalysisMethod analysisMethod;

    public ContentAnalysisContext(
            String contentEntry, ContentInformation information, AnalysisMethod method) {
        this.contentEntry = contentEntry;
        this.information = information;
        this.analysisMethod = method;
    }

    public String contentEntry() {
        return contentEntry;
    }

    public ContentInformation contentInformation() {
        return information;
    }

    public AnalysisMethod analysisMethod() {
        return analysisMethod;
    }
}
