/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.error.TestErrorIdentifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Interprets the output of tests run with Python's unittest framework and translates it into calls
 * on a series of {@link ITestInvocationListener}s. Output from these tests follows this EBNF
 * grammar:
 *
 * <p>TestReport ::= TestResult* Line TimeMetric [FailMessage*] Status. TestResult ::= string
 * “(“string”)” “…” SingleStatus. FailMessage ::= EqLine “ERROR:” string “(“string”)” Line Traceback
 * Line. SingleStatus ::= “ok” | “ERROR”. TimeMetric ::= “Ran” integer “tests in” float ”s”. Status
 * ::= “OK” | “FAILED (errors=” int “)”. Traceback ::= string+.
 *
 * <p>Example output (passing): test_size (test_rangelib.RangeSetTest) ... ok test_str
 * (test_rangelib.RangeSetTest) ... ok test_subtract (test_rangelib.RangeSetTest) ... ok
 * test_to_string_raw (test_rangelib.RangeSetTest) ... ok test_union (test_rangelib.RangeSetTest)
 * ... ok
 *
 * <p>---------------------------------------------------------------------- Ran 5 tests in 0.002s
 *
 * <p>OK
 *
 * <p>Example output (failed) test_size (test_rangelib.RangeSetTest) ... ERROR
 *
 * <p>====================================================================== ERROR: test_size
 * (test_rangelib.RangeSetTest)
 * ---------------------------------------------------------------------- Traceback (most recent
 * call last): File "test_rangelib.py", line 129, in test_rangelib raise ValueError() ValueError
 * ---------------------------------------------------------------------- Ran 1 test in 0.001s
 * FAILED (errors=1)
 *
 * <p>Example output with several edge cases (failed): testError (foo.testFoo) ... ERROR
 * testExpectedFailure (foo.testFoo) ... expected failure testFail (foo.testFoo) ... FAIL
 * testFailWithDocString (foo.testFoo) foo bar ... FAIL testOk (foo.testFoo) ... ok
 * testOkWithDocString (foo.testFoo) foo bar ... ok testSkipped (foo.testFoo) ... skipped 'reason
 * foo' testUnexpectedSuccess (foo.testFoo) ... unexpected success
 *
 * <p>====================================================================== ERROR: testError
 * (foo.testFoo) ---------------------------------------------------------------------- Traceback
 * (most recent call last): File "foo.py", line 11, in testError self.assertEqual(2+2, 5/0)
 * ZeroDivisionError: integer division or modulo by zero
 *
 * <p>====================================================================== FAIL: testFail
 * (foo.testFoo) ---------------------------------------------------------------------- Traceback
 * (most recent call last): File "foo.py", line 8, in testFail self.assertEqual(2+2, 5)
 * AssertionError: 4 != 5
 *
 * <p>====================================================================== FAIL:
 * testFailWithDocString (foo.testFoo) foo bar
 * ---------------------------------------------------------------------- Traceback (most recent
 * call last): File "foo.py", line 31, in testFailWithDocString self.assertEqual(2+2, 5)
 * AssertionError: 4 != 5
 *
 * <p>---------------------------------------------------------------------- Ran 8 tests in 0.001s
 *
 * <p>FAILED (failures=2, errors=1, skipped=1, expected failures=1, unexpected successes=1)
 *
 * <p>TODO: Consider refactoring the full class, handling is quite messy right now.
 */
public class PythonUnitTestResultParser extends MultiLineReceiver {

    private boolean mFinalizeWhenParsing = true;
    // Current test state
    private ParserState mCurrentParseState;
    private String mCurrentTestName;
    private String mCurrentTestClass;
    private String mCurrentTestStatus;
    private Matcher mCurrentMatcher;
    private StringBuilder mCurrentTraceback;
    private long mTotalElapsedTime;
    private int mTotalTestCount;
    private String mCurrentTestCaseString = null;

    // Filters used for parsing test methods.
    private Set<String> mIncludeFilters = new LinkedHashSet<>();
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    // General state
    private Collection<ITestInvocationListener> mListeners = new ArrayList<>();
    private final String mRunName;
    private Map<TestDescription, String> mTestResultCache;
    // Use a special entry to mark skipped test in mTestResultCache
    static final String SKIPPED_ENTRY = "Skipped";

    // Constant tokens that appear in the result grammar.
    static final String EQUAL_LINE =
            "======================================================================";
    static final String DASH_LINE =
            "----------------------------------------------------------------------";
    static final String TRACEBACK_LINE = "Traceback (most recent call last):";

    static final Pattern PATTERN_TEST_SUCCESS = Pattern.compile("ok|expected failure");
    static final Pattern PATTERN_TEST_FAILURE = Pattern.compile("FAIL|ERROR");
    static final Pattern PATTERN_TEST_SKIPPED = Pattern.compile("skipped '.*");
    static final Pattern PATTERN_TEST_UNEXPECTED_SUCCESS = Pattern.compile("unexpected success");

    static final Pattern PATTERN_ONE_LINE_RESULT =
            Pattern.compile(
                    "(\\S*) \\((\\S*)\\) \\.\\.\\. "
                            + "(ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)?");
    static final Pattern PATTERN_TWO_LINE_RESULT_FIRST = Pattern.compile("(\\S*) \\((\\S*)\\)");
    static final Pattern PATTERN_TWO_LINE_RESULT_SECOND =
            Pattern.compile(
                    "(.*) \\.\\.\\. "
                            + "(ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)");
    static final Pattern PATTERN_TWO_LINE_RESULT_SECOND_ERROR =
            Pattern.compile(
                    "(.*) \\.\\.\\. error: (.*)"
                            + "(ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)",
                    Pattern.DOTALL);
    static final Pattern PATTERN_FAIL_MESSAGE =
            Pattern.compile("(FAIL|ERROR): (\\S*) \\((\\S*)\\)( \\(.*\\))?");

    static final Pattern PATTERN_RUN_SUMMARY =
            Pattern.compile("Ran (\\d+) tests? in (\\d+(.\\d*)?)s(.*)");

    /** In case of error spanning over multiple lines. */
    static final Pattern MULTILINE_RESULT_WITH_WARNING =
            Pattern.compile("(.*) \\.\\.\\. (.*)", Pattern.DOTALL);

    static final Pattern MULTILINE_FINAL_RESULT_WITH_WARNING =
            Pattern.compile("(.*) \\.\\.\\. (.*)ok(.*)", Pattern.DOTALL);

    /** Unexpected (multiline) text between ... and test status - likely corrupted */
    static final Pattern PATTERN_MULTILINE_RESULT_FIRST =
            Pattern.compile("(\\S*) \\((\\S*)\\) \\.\\.\\. .+");

    static final Pattern PATTERN_MULTILINE_RESULT_FIRST_NEGATIVE =
            Pattern.compile(
                    "(\\S*) \\((\\S*)\\) \\.\\.\\. (ok|expected failure|FAIL|ERROR|error|skipped"
                            + " '.*'|unexpected success).*");
    static final Pattern PATTERN_MULTILINE_RESULT_LAST =
            Pattern.compile("(ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)");

    static final Pattern PATTERN_RUN_RESULT = Pattern.compile("(OK|FAILED).*");

    enum ParserState {
        TEST_CASE,
        FAIL_MESSAGE,
        TRACEBACK,
        SUMMARY,
        COMPLETE,
    }

    private class PythonUnitTestParseException extends Exception {
        static final long serialVersionUID = -3387516993124229948L;

        public PythonUnitTestParseException(String reason) {
            super(reason);
        }
    }

    /**
     * Create a new {@link PythonUnitTestResultParser} that reports to the given {@link
     * ITestInvocationListener}.
     */
    public PythonUnitTestResultParser(ITestInvocationListener listener, String runName) {
        this(Arrays.asList(listener), runName);
    }

    /**
     * Create a new {@link PythonUnitTestResultParser} that reports to the given {@link
     * ITestInvocationListener}s.
     */
    public PythonUnitTestResultParser(
            Collection<ITestInvocationListener> listeners, String runName) {
        this(listeners, runName, new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    /**
     * Create a new {@link PythonUnitTestResultParser} that reports to the given {@link
     * ITestInvocationListener}s, with specified include and exclude filters.
     */
    public PythonUnitTestResultParser(
            Collection<ITestInvocationListener> listeners,
            String runName,
            Set<String> includeFilters,
            Set<String> excludeFilters) {
        mListeners.addAll(listeners);
        mRunName = runName;
        mTestResultCache = new LinkedHashMap<>();
        mIncludeFilters = includeFilters;
        mExcludeFilters = excludeFilters;
        mCurrentParseState = ParserState.TEST_CASE;
    }

    /**
     * Process Python unittest output and report parsed results.
     *
     * <p>This method should be called only once with the full output, unlike the base method in
     * {@link MultiLineReceiver}.
     */
    @Override
    public void processNewLines(String[] lines) {
        try {
            if (lines.length < 1 || isTracebackLine(lines[0])) {
                throw new PythonUnitTestParseException("Test execution failed");
            }

            for (String line : lines) {
                parse(line);
            }

            if (mFinalizeWhenParsing) {
                finalizeParser();
            }
        } catch (PythonUnitTestParseException e) {
            throw new HarnessRuntimeException(
                    e.getMessage(), TestErrorIdentifier.OUTPUT_PARSER_ERROR);
        }
    }

    public void setFinalizeWhenParsing(boolean shouldFinalize) {
        mFinalizeWhenParsing = shouldFinalize;
    }

    public void finalizeParser() {
        if (mCurrentParseState != ParserState.COMPLETE) {
            throw new HarnessRuntimeException(
                    "Parser finished in unexpected state " + mCurrentParseState.toString(),
                    TestErrorIdentifier.OUTPUT_PARSER_ERROR);
        }
    }

    /** Parse the next result line according to current parser state. */
    void parse(String line) throws PythonUnitTestParseException {
        CLog.v(line);
        switch (mCurrentParseState) {
            case TEST_CASE:
                processTestCase(line);
                break;
            case TRACEBACK:
                processTraceback(line);
                break;
            case SUMMARY:
                processRunSummary(line);
                break;
            case FAIL_MESSAGE:
                processFailMessage(line);
                break;
            case COMPLETE:
                break;
        }
    }

    /** Process a test case line and collect the test name, class, and status. */
    void processTestCase(String line) throws PythonUnitTestParseException {
        if (mCurrentTestCaseString != null) {
            mCurrentTestCaseString = mCurrentTestCaseString + "\n" + line;
            line = mCurrentTestCaseString;
        }

        if (isEqualLine(line)) {
            // equal line before fail message
            mCurrentParseState = ParserState.FAIL_MESSAGE;
            mCurrentTestCaseString = null;
        } else if (isDashLine(line)) {
            // dash line before run summary
            mCurrentParseState = ParserState.SUMMARY;
            mCurrentTestCaseString = null;
        } else if (lineStartswithPattern(line, PATTERN_ONE_LINE_RESULT)) {
            // The below parsing is involved due to output from tests that use Python's subTest
            // feature. In particular, a line could contain multiple test case summary lines such
            // as; a (T) ... b (T) ... ok.
            // TODO(hzalek): Consider adding a Python support library for writing the output of the
            // test in a structured format to avoid parsing string output.
            List<MatchResult> matchResults = new ArrayList<>();

            // Collect the results to avoid modifying state in case the entire line doesn't match.
            do {
                matchResults.add(mCurrentMatcher.toMatchResult());
            } while (mCurrentMatcher.find());

            int lastMatchEnd = matchResults.get(matchResults.size() - 1).end();
            if (lastMatchEnd != line.length()) {
                boolean canBeMultiline =
                        !lineMatchesPattern(line, PATTERN_MULTILINE_RESULT_FIRST_NEGATIVE);
                if (canBeMultiline && lineMatchesPattern(line, PATTERN_MULTILINE_RESULT_FIRST)) {
                    mCurrentTestName = mCurrentMatcher.group(1);
                    mCurrentTestClass =
                            removeTestNameFromClassNameGroup(
                                    mCurrentMatcher.group(2), mCurrentTestName);
                    mCurrentTestCaseString = null;
                }
                return; // The entire line doesn't match so just ignore it.
            }

            for (MatchResult r : matchResults) {
                mCurrentTestName = r.group(1);
                mCurrentTestClass = removeTestNameFromClassNameGroup(r.group(2), mCurrentTestName);
                mCurrentTestStatus = r.group(3);

                // Tests with failed subtests have no status printed so we add an entry with 'FAIL'
                // status. In any case, subsequent failed subtest assertions have the same test name
                // and will clobber whatever status we set here. Passed subtest assertions don't
                // appear in the output and do not risk overwriting the status.
                if (mCurrentTestStatus == null) {
                    mCurrentTestStatus = "FAIL";
                }

                reportNonFailureTestResult();
            }

            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, PATTERN_MULTILINE_RESULT_LAST)) {
            mCurrentTestStatus = mCurrentMatcher.group(1);
            reportNonFailureTestResult();
            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, PATTERN_TWO_LINE_RESULT_FIRST)) {
            mCurrentTestName = mCurrentMatcher.group(1);
            mCurrentTestClass =
                    removeTestNameFromClassNameGroup(mCurrentMatcher.group(2), mCurrentTestName);
            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, PATTERN_TWO_LINE_RESULT_SECOND)) {
            mCurrentTestStatus = mCurrentMatcher.group(2);
            reportNonFailureTestResult();
            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, PATTERN_TWO_LINE_RESULT_SECOND_ERROR)) {
            // Skip that odd error message
            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, MULTILINE_FINAL_RESULT_WITH_WARNING)) {
            StringBuilder message = new StringBuilder("Test seems to pass but with Warnings:\n");
            message.append(mCurrentMatcher.group(2));
            mCurrentTraceback = message;
            reportFailureTestResult();
            mCurrentTestCaseString = null;
        } else if (lineMatchesPattern(line, MULTILINE_RESULT_WITH_WARNING)) {
            if (mCurrentTestCaseString == null) {
                mCurrentTestCaseString = line;
            }
        }
    }

    String removeTestNameFromClassNameGroup(String classNameGroup, String testName) {
        if (!classNameGroup.endsWith(testName)) {
            return classNameGroup;
        }
        Pattern p = Pattern.compile("(.*)\\." + testName);
        Matcher matcher = p.matcher(classNameGroup);
        if (!matcher.matches()) {
            return classNameGroup;
        }
        return matcher.group(1);
    }

    /** Process a fail message line and collect the test name, class, and status. */
    void processFailMessage(String line) {
        if (isDashLine(line)) {
            // dash line before traceback
            mCurrentParseState = ParserState.TRACEBACK;
            mCurrentTraceback = new StringBuilder();
        } else if (lineMatchesPattern(line, PATTERN_FAIL_MESSAGE)) {
            mCurrentTestName = mCurrentMatcher.group(2);
            mCurrentTestClass =
                    removeTestNameFromClassNameGroup(mCurrentMatcher.group(3), mCurrentTestName);
            mCurrentTestStatus = mCurrentMatcher.group(1);
        }
        // optional docstring - do nothing
    }

    /** Process a traceback line and append it to the full traceback message. */
    void processTraceback(String line) {
        if (isDashLine(line)) {
            // dash line before run summary
            mCurrentParseState = ParserState.SUMMARY;
            reportFailureTestResult();
        } else if (isEqualLine(line)) {
            // equal line before another fail message followed by traceback
            mCurrentParseState = ParserState.FAIL_MESSAGE;
            reportFailureTestResult();
        } else {
            if (mCurrentTraceback.length() > 0) {
                mCurrentTraceback.append(System.lineSeparator());
            }
            mCurrentTraceback.append(line);
        }
    }

    /** Process the run summary line and collect the test count and run time. */
    void processRunSummary(String line) {
        if (lineMatchesPattern(line, PATTERN_RUN_SUMMARY)) {
            mTotalTestCount = Integer.parseInt(mCurrentMatcher.group(1));
            double timeInSeconds = Double.parseDouble(mCurrentMatcher.group(2));
            mTotalElapsedTime = (long) (timeInSeconds * 1000D);
            reportToListeners();
            mCurrentParseState = ParserState.COMPLETE;
        }
        // ignore status message on the last line because Python consider "unexpected success"
        // passed while we consider it failed
    }

    boolean isEqualLine(String line) {
        return line.startsWith(EQUAL_LINE);
    }

    boolean isDashLine(String line) {
        return line.startsWith(DASH_LINE);
    }

    boolean isTracebackLine(String line) {
        return line.startsWith(TRACEBACK_LINE);
    }

    /** Check if the given line matches the given pattern and caches the matcher object */
    private boolean lineMatchesPattern(String line, Pattern p) {
        mCurrentMatcher = p.matcher(line);
        return mCurrentMatcher.matches();
    }

    private boolean lineStartswithPattern(String line, Pattern p) {
        mCurrentMatcher = p.matcher(line);
        return mCurrentMatcher.find();
    }

    /** Send recorded test results to all listeners. */
    private void reportToListeners() {
        for (ITestInvocationListener listener : mListeners) {
            listener.testRunStarted(mRunName, mTotalTestCount);

            for (Entry<TestDescription, String> test : mTestResultCache.entrySet()) {
                listener.testStarted(test.getKey());
                if (SKIPPED_ENTRY.equals(test.getValue())) {
                    listener.testIgnored(test.getKey());
                } else if (test.getValue() != null) {
                    listener.testFailed(test.getKey(), test.getValue());
                }
                listener.testEnded(test.getKey(), new HashMap<String, Metric>());
            }
            listener.testRunEnded(mTotalElapsedTime, new HashMap<String, Metric>());
        }
    }

    /** Record a non-failure test case. */
    private void reportNonFailureTestResult() throws PythonUnitTestParseException {
        TestDescription testId = new TestDescription(mCurrentTestClass, mCurrentTestName);

        if (shouldSkipCurrentTest(
                mCurrentTestClass, mCurrentTestName, mIncludeFilters, mExcludeFilters)) {
            // Force to skip any test not listed in include filters, or listed in exclude filters.
            mTestResultCache.put(testId, SKIPPED_ENTRY);
        } else if (PATTERN_TEST_SUCCESS.matcher(mCurrentTestStatus).matches()) {
            mTestResultCache.put(testId, null);
        } else if (PATTERN_TEST_SKIPPED.matcher(mCurrentTestStatus).matches()) {
            mTestResultCache.put(testId, SKIPPED_ENTRY);
        } else if (PATTERN_TEST_UNEXPECTED_SUCCESS.matcher(mCurrentTestStatus).matches()) {
            mTestResultCache.put(testId, "Test unexpected succeeded");
        } else if (PATTERN_TEST_FAILURE.matcher(mCurrentTestStatus).matches()) {
            // do nothing for now, report only after traceback is collected
        } else {
            throw new PythonUnitTestParseException("Unrecognized test status");
        }
    }

    /** Record a failed test case and its traceback message. */
    private void reportFailureTestResult() {
        TestDescription testId = new TestDescription(mCurrentTestClass, mCurrentTestName);
        if (shouldSkipCurrentTest(
                mCurrentTestClass, mCurrentTestName, mIncludeFilters, mExcludeFilters)) {
            mTestResultCache.put(testId, SKIPPED_ENTRY);
        } else {
            mTestResultCache.put(testId, mCurrentTraceback.toString());
        }
    }

    /**
     * Check if current test should be skipped.
     *
     * @return true if the test should be skipped.
     */
    @VisibleForTesting
    boolean shouldSkipCurrentTest(
            String testClass,
            String testName,
            Set<String> includeFilters,
            Set<String> excludeFilters) {
        // Force to skip any test not listed in include filters, or listed in exclude filters.
        // exclude filters have highest priority.
        if (excludeFilters.contains(testClass + "#" + testName)
                || excludeFilters.contains(testClass)) {
            return true;
        }
        if (!includeFilters.isEmpty()) {
            if (includeFilters.contains(testClass + "#" + testName)
                    || includeFilters.contains(testClass)) {
                return false;
            }
            for (String filter : includeFilters) {
                // If the filter is in fnMatch format, assume the tests have already been filtered.
                if (isFnMatchFormat(filter)) {
                    return false;
                }
                // Also ensure the filter matches the fully.qualified.ClassName
                String fullyQualifiedClassNameFilter = "(\\w*\\.)*" + filter;
                try {
                    if (testClass.matches(fullyQualifiedClassNameFilter)
                            || (testClass + "#" + testName)
                                    .matches(fullyQualifiedClassNameFilter)) {
                        return false;
                    }
                } catch (PatternSyntaxException pse) {
                    // Ignore.
                }
                // If the filter is not in Class#method format, apply it broadly as a pattern.
                try {
                    if (!filter.matches("(\\w*\\.)*\\w*#\\w*")) {
                        if ((testClass + "#" + testName).matches(".*" + filter + ".*")) {
                            return false;
                        }
                    }
                } catch (PatternSyntaxException pse) {
                    // Ignore.
                }
            }
            return true;
        }
        return false;
    }

    private boolean isFnMatchFormat(String filter) {
        // A Fnmatch-formatted filter can contain any of the following characters:
        // '*', '?', set of square brackets '[' and ']', '!'.
        if (filter.matches(".*\\*.*")
                || filter.matches(".*\\?.*")
                || filter.matches(".*\\[.*].*")
                || filter.matches(".*\\!.*")) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
