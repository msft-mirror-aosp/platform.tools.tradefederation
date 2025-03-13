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

package com.android.tradefed.testtype.mobly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Mobly yaml result 'Test Name List' element handler. */
public class MoblyYamlResultTestNameListHandler implements IMoblyYamlResultHandler {

    private static final String REQUESTED_TESTS = "Requested Tests";

    @Override
    public TestNameList handle(Map<String, Object> docMap) {
        TestNameList.Builder builder = TestNameList.builder();
        builder.addTests((List<String>) docMap.get(REQUESTED_TESTS));
        return builder.build();
    }

    public static class TestNameList implements ITestResult {

        private List<String> testList = new ArrayList<>();

        private TestNameList(List<String> testList) {
            this.testList.addAll(testList);
        }

        @Override
        public MoblyYamlResultHandlerFactory.Type getType() {
            return MoblyYamlResultHandlerFactory.Type.TEST_NAME_LIST;
        }

        public static Builder builder() {
            return new Builder();
        }

        public List<String> getTestList() {
            return testList;
        }

        public static class Builder {

            private List<String> testList = new ArrayList<>();

            public TestNameList build() {
                return new TestNameList(testList);
            }

            public void addTests(List<String> tests) {
                if (tests != null) {
                    testList.addAll(tests);
                }
            }
        }
    }
}
