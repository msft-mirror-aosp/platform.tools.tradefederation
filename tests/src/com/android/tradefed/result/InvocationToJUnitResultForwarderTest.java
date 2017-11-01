/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestIdentifier;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;

import org.easymock.EasyMock;

import java.util.Collections;
import java.util.Map;

/**
 * Unit tests for {@link InvocationToJUnitResultForwarder}.
 */
public class InvocationToJUnitResultForwarderTest extends TestCase {

    private static final String TEST_NAME = "testName";
    private static final String CLASS_NAME = "className";
    private TestListener mJUnitListener;
    private InvocationToJUnitResultForwarder mTestForwarder;
    private TestIdentifier mTestIdentifier;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mJUnitListener = EasyMock.createMock(TestListener.class);
        mTestForwarder = new InvocationToJUnitResultForwarder(mJUnitListener);
        mTestIdentifier = new TestIdentifier(CLASS_NAME, TEST_NAME);
    }

    /**
     * Simple test for {@link InvocationToJUnitResultForwarder#testEnded(TestIdentifier, Map)}.
     * <p/>
     * Verifies that data put into TestIdentifier is forwarded in correct format
     */
    public void testTestEnded() {
        Map<String, String> emptyMap = Collections.emptyMap();
        mJUnitListener.endTest((Test) EasyMock.anyObject());
        EasyMock.replay(mJUnitListener);
        mTestForwarder.testEnded(mTestIdentifier, emptyMap);
        // TODO: check format
    }

    // TODO: add more tests
}
