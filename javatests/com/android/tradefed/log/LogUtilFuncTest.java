/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.tradefed.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A set of functional testcases for {@link LogUtil} */
@RunWith(JUnit4.class)
public class LogUtilFuncTest {
    private static final String CLASS_NAME = "LogUtilFuncTest";
    private static final String STRING = "hallo!";

    @Test
    public void testCLog_v() {
        CLog.v("this is CLog.v");
        CLog.v("this is CLog.v with a format string: %s has length %d", STRING, STRING.length());
    }

    @Test
    public void testCLog_d() {
        CLog.d("this is CLog.d");
        CLog.d("this is CLog.d with a format string: %s has length %d", STRING, STRING.length());
    }

    @Test
    public void testCLog_i() {
        CLog.i("this is CLog.i");
        CLog.i("this is CLog.i with a format string: %s has length %d", STRING, STRING.length());
    }

    @Test
    public void testCLog_w() {
        CLog.w("this is CLog.w");
        CLog.w("this is CLog.w with a format string: %s has length %d", STRING, STRING.length());
    }

    @Test
    public void testCLog_e() {
        CLog.e("this is CLog.e");
        CLog.e("this is CLog.e with a format string: %s has length %d", STRING, STRING.length());
    }

    /** Verify that getClassName can get the desired class name from the stack trace. */
    @Test
    public void testCLog_getClassName() {
        String klass = CLog.getClassName(1);
        assertTrue(CLASS_NAME.equals(klass));
    }

    /** Verify that findCallerClassName is able to find this class's name from the stack trace. */
    @Test
    public void testCLog_findCallerClassName() {
        String klass = CLog.findCallerClassName();
        assertEquals(CLASS_NAME, klass);
    }

    /**
     * Verify that findCallerClassName() is able to find the calling class even if it is deeper in
     * the stack trace.
     */
    @Test
    public void testCLog_findCallerClassName_callerDeeperInStackTrace() {
        Throwable t = new Throwable();

        // take a real stack trace, but prepend it with some fake frames
        List<StackTraceElement> list = new ArrayList<StackTraceElement>(
            Arrays.asList(t.getStackTrace()));
        for (int i = 0; i < 5; i++) {
            list.add(0, new StackTraceElement(
                    CLog.class.getName(), "fakeMethod" + i, "fakefile", 1));
        }
        t.setStackTrace(list.toArray(new StackTraceElement[list.size()]));

        String klass = CLog.findCallerClassName(t);
        assertEquals(CLASS_NAME, klass);
    }

    /** Verify that findCallerClassName() returns "Unknown" when there's an empty stack trace */
    @Test
    public void testCLog_findCallerClassName_emptyStackTrace() {
        Throwable t = new Throwable();

        StackTraceElement[] emptyStackTrace = new StackTraceElement[0];
        t.setStackTrace(emptyStackTrace);

        String klass = CLog.findCallerClassName(t);
        assertEquals("Unknown", klass);
    }

    /**
     * Verify that parseClassName() is able to parse the class name out of different formats of
     * stack trace frames
     */
    @Test
    public void testCLog_parseClassName() {
        assertEquals("OuterClass", CLog.parseClassName(
                "com.android.tradefed.log.OuterClass$InnerClass"));
        assertEquals("OuterClass", CLog.parseClassName("com.android.tradefed.log.OuterClass"));
        assertEquals("SimpleClassNameOnly", CLog.parseClassName("SimpleClassNameOnly"));
    }

    @Test
    public void testLogAndDisplay_specialSerial() {
        CLog.logAndDisplay(LogLevel.VERBOSE, "[fe80::ba27:ebff:feb3:e8%em1]:5555");
    }
}
