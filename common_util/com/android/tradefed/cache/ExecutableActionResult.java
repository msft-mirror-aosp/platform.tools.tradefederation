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

import com.google.auto.value.AutoValue;
import java.io.File;
import javax.annotation.Nullable;

/** A value class representing a result of a {@link ExecutableAction}. */
@AutoValue
public abstract class ExecutableActionResult {
    public static ExecutableActionResult create(int exitCode, File stdOut, File stdErr) {
        return new AutoValue_ExecutableActionResult(exitCode, stdOut, stdErr);
    }

    public abstract int exitCode();

    @Nullable
    public abstract File stdOut();

    @Nullable
    public abstract File stdErr();
}
