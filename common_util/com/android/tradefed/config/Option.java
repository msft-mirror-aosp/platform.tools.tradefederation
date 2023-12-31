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

package com.android.tradefed.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Map;

/**
 * Annotates a field as representing a {@link IConfiguration} option.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Option {

    static final char NO_SHORT_NAME = '0';

    public enum Importance {
        /** the option should never be treated as important */
        NEVER,
        /** the option should be treated as important only if it has no value */
        IF_UNSET,
        /** the option should always be treated as important */
        ALWAYS;
    }

    /**
     * The mandatory unique name for this option.
     * <p/>
     * This will map to a command line argument prefixed with two '-' characters.
     * For example, an {@link Option} with name 'help' would be specified with '--help' on the
     * command line.
     * <p/>
     * Names may not contain a colon eg ':'.
     */
    String name();

    /**
     * Optional abbreviated name for option.
     * This will map to a command line argument prefixed with a single '-'.
     * e.g. "-h" where h = shortName.
     *
     * '0' is reserved to mean the option has no shortName.
     **/
    char shortName() default NO_SHORT_NAME;

    /**
     * User friendly description of the option.
     */
    String description() default "";

    /**
     * The importance of the option.
     * <p/>
     * An option deemed 'important' will be displayed in the abbreviated help output. Help for an
     * unimportant option will only be displayed in the full help text.
     */
    Importance importance() default Importance.NEVER;

    /**
     * Whether the option is mandatory or optional.
     * <p />
     * The configuration framework will throw a {@code ConfigurationException} if either of the
     * following is true of a mandatory field after options have been parsed from all sources:
     * <ul>
     *   <li>The field is {@code null}.</li>
     *   <li>The field is an empty {@link Collection}.</li>
     * </ul>
     */
    boolean mandatory() default false;

    /**
     * Whether the option represents a time value.
     *
     * <p>If this is a time value, time-specific suffixes will be parsed. The field
     * <emph>MUST</emph> be a {@code long} or {@code Long} for this flag to be valid. A {@code
     * ConfigurationException} will be thrown otherwise.
     *
     * <p>The default unit is millis. The configuration framework will accept {@code s} for seconds
     * (1000 millis), {@code m} for minutes (60 seconds), {@code h} for hours (60 minutes), or
     * {@code d} for days (24 hours).
     *
     * <p>Units may be mixed and matched, so long as each unit appears at most once, and so long as
     * all units which do appear are listed in decreasing order of scale. So, for instance, {@code
     * h} may only appear before {@code m}, and may only appear after {@code d}. As a specific
     * example, "1d2h3m4s5ms" would be a valid time value, as would "4" or "4ms". All embedded
     * whitespace is discarded.
     */
    boolean isTimeVal() default false;

    /**
     * Whether the option is needed to compile instruction to rerun a test.
     *
     * <p>Result reporter may try to compile instruction on how to rerun a test and include the
     * message in the result. The instruction shall include all options that applicable to a test
     * rerun. This attribute is used to indicate if the option shall be included in such
     * instruction.
     */
    boolean requiredForRerun() default false;

    /**
     * Controls the behavior when an option is specified multiple times.  Note that this rule is
     * ignored completely for options that are {@link Collection}s or {@link Map}s.
     */
    OptionUpdateRule updateRule() default OptionUpdateRule.LAST;
}
