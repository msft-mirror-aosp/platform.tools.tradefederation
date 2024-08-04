/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.util;

import com.android.tradefed.auth.ICredentialFactory;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.gcs.GoogleApiClientUtilBase;

import com.google.api.client.auth.oauth2.Credential;
import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Utils for create Google API client. */
public class GoogleApiClientUtil extends GoogleApiClientUtilBase {

    private static GoogleApiClientUtil sInstance = null;

    private static GoogleApiClientUtil getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleApiClientUtil();
        }
        return sInstance;
    }



    /**
     * Try to create credential with different key files or from local host.
     *
     * <p>1. If primaryKeyFile is set, try to use it to create credential. 2. Try to get
     * corresponding key files from {@link HostOptions}. 3. Try to use backup key files. 4. Use
     * local default credential.
     *
     * @param scopes scopes for the credential.
     * @param primaryKeyFile the primary json key file; it can be null.
     * @param hostOptionKeyFileName {@link HostOptions}'service-account-json-key-file option's key;
     *     it can be null.
     * @param backupKeyFiles backup key files.
     * @return a {@link Credential}
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static Credentials createCredential(
            Collection<String> scopes,
            File primaryKeyFile,
            String hostOptionKeyFileName,
            File... backupKeyFiles)
            throws IOException, GeneralSecurityException {
        return getInstance()
                .doCreateCredential(scopes, primaryKeyFile, hostOptionKeyFileName, backupKeyFiles);
    }

    /**
     * Try to create credential with different key files or from local host.
     *
     * <p>1. Use {@link ICredentialFactory} if useCredentialFactory is true and a {@link
     * ICredentialFactory} is configured. If primaryKeyFile is set, try to use it to create
     * credential. 2. Try to get corresponding key files from {@link HostOptions}. 3. Try to use
     * backup key files. 4. Use local default credential.
     *
     * @param scopes scopes for the credential.
     * @param useCredentialFactory use credential factory if it's configured.
     * @param primaryKeyFile the primary json key file; it can be null.
     * @param hostOptionKeyFileName {@link HostOptions}'service-account-json-key-file option's key;
     *     it can be null.
     * @param backupKeyFiles backup key files.
     * @return a {@link Credential}
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static Credentials createCredential(
            Collection<String> scopes,
            boolean useCredentialFactory,
            File primaryKeyFile,
            String hostOptionKeyFileName,
            File... backupKeyFiles)
            throws IOException, GeneralSecurityException {
        Credentials credential = null;
        if (useCredentialFactory) {
            credential = getInstance().doCreateCredentialFromCredentialFactory(scopes);
            // TODO(b/186766552): Throw exception once all hosts configured CredentialFactory.
            if (credential != null) {
                return credential;
            }
            CLog.i("No CredentialFactory configured, fallback to key files.");
        }
        return getInstance()
                .doCreateCredential(scopes, primaryKeyFile, hostOptionKeyFileName, backupKeyFiles);
    }

    @VisibleForTesting
    Credentials doCreateCredential(
            Collection<String> scopes,
            File primaryKeyFile,
            String hostOptionKeyFileName,
            File... backupKeyFiles)
            throws IOException, GeneralSecurityException {

        List<File> keyFiles = new ArrayList<File>();
        if (primaryKeyFile != null) {
            keyFiles.add(primaryKeyFile);
        }
        File hostOptionKeyFile = null;
        if (hostOptionKeyFileName != null) {
            try {
                hostOptionKeyFile =
                        GlobalConfiguration.getInstance()
                                .getHostOptions()
                                .getServiceAccountJsonKeyFiles()
                                .get(hostOptionKeyFileName);
                if (hostOptionKeyFile != null) {
                    keyFiles.add(hostOptionKeyFile);
                }
            } catch (IllegalStateException e) {
                CLog.d("Global configuration haven't been initialized.");
            }
        }
        keyFiles.addAll(Arrays.asList(backupKeyFiles));
        for (File keyFile : keyFiles) {
            if (keyFile != null) {
                if (keyFile.exists() && keyFile.canRead()) {
                    CLog.d("Using %s.", keyFile.getAbsolutePath());
                    return doCreateCredentialFromJsonKeyFile(keyFile, scopes);
                } else {
                    CLog.i("No access to %s.", keyFile.getAbsolutePath());
                }
            }
        }
        return doCreateDefaultCredential(scopes);
    }


    @VisibleForTesting
    Credentials doCreateCredentialFromCredentialFactory(Collection<String> scopes)
            throws IOException {
        try {
            if (GlobalConfiguration.getInstance().getCredentialFactory() != null) {
                return GlobalConfiguration.getInstance()
                        .getCredentialFactory()
                        .createCredential(scopes);
            }
            CLog.w("No CredentialFactory configured.");
        } catch (IllegalStateException e) {
            System.out.println(
                    "GlobalConfiguration is not initialized yet,"
                            + "can not get CredentialFactory.");
        }
        return null;
    }


}
