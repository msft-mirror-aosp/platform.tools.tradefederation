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

import com.android.tradefed.invoker.tracing.CloseableTraceScope;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Duration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A value class representing an action which can be executed.
 *
 * <p>Terminology note: "action" is used here in the remote execution protocol sense.
 */
@AutoValue
public abstract class ExecutableAction {

    // A silo key used to invalid stale cache.
    private static final String SILO_CACHE_KEY = "0.0";

    /** Builds an {@link ExecutableAction}. */
    public static ExecutableAction create(
            File input, Iterable<String> args, Map<String, String> envVariables, long timeout)
            throws IOException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("create_executable_action")) {
            Command command =
                    Command.newBuilder()
                            .addAllArguments(args)
                            .setPlatform(
                                    Platform.newBuilder()
                                            .addProperties(
                                                    Property.newBuilder()
                                                            .setName(
                                                                    String.format(
                                                                            "%s(%s)",
                                                                            System.getProperty(
                                                                                    "os.name"),
                                                                            System.getProperty(
                                                                                    "os.version")))
                                                            .build())
                                            .addProperties(
                                                    Property.newBuilder()
                                                            .setName("cache-silo-key")
                                                            .setValue(SILO_CACHE_KEY)
                                                            .build())
                                            .build())
                            .addAllEnvironmentVariables(
                                    envVariables.entrySet().stream()
                                            .map(
                                                    entry ->
                                                            EnvironmentVariable.newBuilder()
                                                                    .setName(entry.getKey())
                                                                    .setValue(entry.getValue())
                                                                    .build())
                                            .collect(Collectors.toList()))
                            .build();

            MerkleTree inputMerkleTree = MerkleTree.buildFromDir(input);
            Action.Builder actionBuilder =
                    Action.newBuilder()
                            .setInputRootDigest(inputMerkleTree.rootDigest())
                            .setCommandDigest(DigestCalculator.compute(command));
            if (timeout > 0L) {
                actionBuilder.setTimeout(Duration.newBuilder().setSeconds(timeout).build());
            }

            Action action = actionBuilder.build();
            return new AutoValue_ExecutableAction(
                    action,
                    DigestCalculator.compute(action),
                    command,
                    DigestCalculator.compute(command),
                    inputMerkleTree);
        }
    }

    public abstract Action action();

    public abstract Digest actionDigest();

    public abstract Command command();

    public abstract Digest commandDigest();

    public abstract MerkleTree input();
}
