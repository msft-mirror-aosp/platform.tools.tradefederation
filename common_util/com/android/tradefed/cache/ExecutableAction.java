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

package com.android.tradefed.cache;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.Command;
import com.google.auto.value.AutoValue;

/**
 * A value class representing an action which can be executed.
 *
 * <p>Terminology note: "action" is used here in the remote execution protocol sense.
 */
@AutoValue
public abstract class ExecutableAction {
    public static ExecutableAction create(Action action, Command command) {
        return new AutoValue_ExecutableAction(action, command);
    }

    public abstract Action action();

    public abstract Command command();
}
