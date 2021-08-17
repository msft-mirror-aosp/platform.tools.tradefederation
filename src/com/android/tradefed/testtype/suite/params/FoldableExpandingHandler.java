/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tradefed.testtype.suite.params;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceFoldableState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A {@link IModuleParameterHandler} expanding into more for each non-primary foldable
 * configuration.
 */
public class FoldableExpandingHandler implements IModuleParameterHandler {

    @Override
    public String getParameterIdentifier() {
        throw new UnsupportedOperationException("Should never be called");
    }

    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        throw new UnsupportedOperationException("Should never be called");
    }

    public List<IModuleParameterHandler> expandHandler(Set<DeviceFoldableState> states) {
        List<DeviceFoldableState> foldableList = new ArrayList<>(states);
        Collections.sort(foldableList);

        List<IModuleParameterHandler> foldableStateToRun = new ArrayList<>();
        // If there is no or only one foldable state, there is no need to parameterize.
        if (foldableList.isEmpty() || foldableList.size() == 1) {
            return foldableStateToRun;
        }
        // Consider the lowest identifier the 'primary state'
        for (int i = 1; i < foldableList.size(); i++) {
            foldableStateToRun.add(new FoldableHandler(
                    foldableList.get(i).toString(), foldableList.get(i).getIdentifier()));
        }
        return foldableStateToRun;
    }
}
