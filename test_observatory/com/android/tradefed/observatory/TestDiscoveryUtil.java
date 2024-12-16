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

package com.android.tradefed.observatory;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.util.keystore.DryRunKeyStore;

import java.util.Set;

/** A utility class for test discovery. */
public class TestDiscoveryUtil {
    public IConfigurationFactory getConfigurationFactory() {
        return ConfigurationFactory.getInstance();
    }

    public boolean hasOutputResultFile() {
        return System.getenv(TestDiscoveryInvoker.OUTPUT_FILE) != null;
    }

    protected String getEnvironment(String var) {
        return System.getenv(var);
    }

    /**
     * Retrieve configuration base on command line args.
     *
     * @param args the command line args of the test.
     * @return A {@link IConfiguration} which constructed based on command line args.
     */
    public IConfiguration getConfiguration(String[] args) throws ConfigurationException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("create_configuration")) {
            IConfigurationFactory configurationFactory = getConfigurationFactory();
            return configurationFactory.createPartialConfigurationFromArgs(
                    args,
                    new DryRunKeyStore(),
                    Set.of(
                            Configuration.BUILD_PROVIDER_TYPE_NAME,
                            Configuration.TEST_TYPE_NAME,
                            Configuration.TARGET_PREPARER_TYPE_NAME),
                    null);
        }
    }

    /** Returns true if the command is a Tradefed command */
    public boolean isTradefedConfiguration(String[] args) {
        if (args[0].equals("testing/mobileharness/gateway")) {
            return false;
        }
        if (args[0].equals("unused")) {
            return false;
        }
        return true;
    }
}
