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
package com.android.tradefed.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ResourceUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link ModifiedFilesParser} */
@RunWith(JUnit4.class)
public class ModifiedFilesParserTest {

    private static final String DEVICE_IMAGE_FILE = "modified_files1.json";
    private static final String DEVICE_IMAGE_FILE_CHANGED = "modified_files2.json";

    @Test
    public void testDeviceImageParsing() throws Exception {
        File modifiedFile = getTestResource(DEVICE_IMAGE_FILE);
        try {
            ModifiedFilesParser parser = new ModifiedFilesParser(modifiedFile, true);
            parser.parse();
            assertFalse(parser.hasImageChanged());
        } finally {
            FileUtil.deleteFile(modifiedFile);
        }
    }

    @Test
    public void testDeviceImageParsing_imageChanged() throws Exception {
        File modifiedFile = getTestResource(DEVICE_IMAGE_FILE_CHANGED);
        try {
            ModifiedFilesParser parser = new ModifiedFilesParser(modifiedFile, true);
            parser.parse();
            assertTrue(parser.hasImageChanged());
        } finally {
            FileUtil.deleteFile(modifiedFile);
        }
    }

    private File getTestResource(String resource) throws Exception {
        File output = FileUtil.createTempFile("modified_files", ".json");
        boolean res = ResourceUtil.extractResourceAsFile("/testdata/" + resource, output);
        if (!res) {
            FileUtil.deleteFile(output);
            fail(String.format("Failed to extract %s from resources", resource));
        }
        return output;
    }
}
