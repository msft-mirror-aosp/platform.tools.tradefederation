/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.google.android.tradefed.util;

import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** A helper class for methods related to launch test. */
public class ClasspathLauncherUtil {

    private static final Set<String> EXCLUDED_CLASSES =
            ImmutableSet.of(
                    "android/app/Application.class", "io/grpc/okhttp/OkHttpClientTransport.class");

    /**
     * List all the jars to be included in the classpath
     *
     * @param workingDir Directory to search for jars
     * @param excludedPatterns Pattern of jars to exclude from classpath
     * @return A list of all files to be included in the classpath
     * @throws IOException
     */
    public static List<File> getJars(File workingDir, List<String> excludedPatterns)
            throws IOException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("build_classpath")) {
            Set<File> jarFiles = FileUtil.findFilesObject(workingDir, ".*.jar");
            // Device jars should not be loaded since they can mess up the host jar loading.
            List<File> filteredList = filterJars(jarFiles, excludedPatterns);

            // Check if all jars are in the same directory.
            if (filteredList.size() > 1) {
                boolean sameDir = true;
                String dir = new File(filteredList.get(0).getAbsolutePath()).getParent();
                for (File jar : filteredList) {
                    if (!dir.equals(jar.getParent())) {
                        sameDir = false;
                        break;
                    }
                }
                if (sameDir) {
                    filteredList.clear();
                    filteredList.add(new File(dir, "*"));
                }
            }

            // Sort to always end-up with the same order on the classpath
            Collections.sort(filteredList);
            return filteredList;
        }
    }

    private static List<File> filterJars(Set<File> jars, List<String> excludedPatterns) {
        List<File> filtered = new ArrayList<>();
        try (CloseableTraceScope ignored = new CloseableTraceScope("filter_jars")) {
            for (File jar : jars) {
                if (jar.getAbsolutePath().contains("target/testcases")) {
                    continue;
                }
                if (jar.getAbsolutePath().contains("/android-all/")) {
                    continue;
                }
                // Don't re-add Tradefed jars
                if (excludedPatterns != null
                        && matchExcludedFilesInClasspath(excludedPatterns, jar.getName())) {
                    continue;
                }
                // Test that the JAR is readable
                ZipFile file = null;
                try {
                    file = new ZipFile(jar);
                } catch (Exception ex) {
                    CLog.w(
                            "%s is not a proper jar. Removing it from classpath. error: %s",
                            jar, ex.getMessage());
                    continue;
                } finally {
                    StreamUtil.close(file);
                }
                // Ensure some classes never end up on our classpath. Be very careful
                // when adding new ones
                try {
                    if (excludeKnownClasses(jar)) {
                        CLog.d("jar '%s' is excluded from classpath", jar);
                        continue;
                    }
                } catch (IOException ioe) {
                    CLog.w("Error reading '%s' for classpath. error: %s", jar, ioe.getMessage());
                    continue;
                }

                filtered.add(jar);
            }
        }
        return filtered;
    }

    private static boolean excludeKnownClasses(File givenFile) throws IOException {
        try (JarFile jarFile = new JarFile(givenFile)) {
            Enumeration<JarEntry> e = jarFile.entries();
            while (e.hasMoreElements()) {
                JarEntry jarEntry = e.nextElement();
                if (jarEntry.getName().endsWith(".class")) {
                    String className = jarEntry.getName();
                    if (EXCLUDED_CLASSES.contains(className)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the given file name matches any of the files we'd like to exclude from the
     * classpath.
     */
    public static boolean matchExcludedFilesInClasspath(
            List<String> excludedPatterns, String fileName) {
        for (String excludedFileNamePattern : excludedPatterns) {
            Pattern pattern = Pattern.compile(excludedFileNamePattern);
            Matcher matcher = pattern.matcher(fileName);
            if (matcher.find()) {
                return true;
            }
        }
        return false;
    }
}
