# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Set of error prone rules to ensure code quality
# PackageLocation check requires the androidCompatible=false otherwise it does not do anything.
LOCAL_ERROR_PRONE_FLAGS:= -XDandroidCompatible=false \
                          -Xep:ArrayToString:ERROR \
                          -Xep:BadInmport:ERROR \
                          -Xep:BoxedPrimitiveConstructor:ERROR \
                          -Xep:CatchFail:ERROR \
                          -Xep:CheckReturnValue:ERROR \
                          -Xep:ConstantField:ERROR \
                          -Xep:DeadException:ERROR \
                          -Xep:EqualsIncompatibleType:ERROR \
                          -Xep:ExtendingJUnitAssert:ERROR \
                          -Xep:FallThrough:ERROR \
                          -Xep:FormatString:ERROR \
                          -Xep:GetClassOnClass:ERROR \
                          -Xep:IdentityBinaryExpression:ERROR \
                          -Xep:InheritDoc:ERROR \
                          -Xep:InvalidInlineTag:ERROR \
                          -Xep:InvalidParam:ERROR \
                          -Xep:JUnit3TestNotRun:ERROR \
                          -Xep:JUnit4TestNotRun:ERROR \
                          -Xep:JUnit4ClassUsedInJUnit3:ERROR \
                          -Xep:JUnitAmbiguousTestClass:ERROR \
                          -Xep:LongLiteralLowerCaseSuffix:ERROR \
                          -Xep:MissingCasesInEnumSwitch:ERROR \
                          -Xep:MissingFail:ERROR \
                          -Xep:MissingOverride:ERROR \
                          -Xep:ModifiedButNotUsed:ERROR \
                          -Xep:MustBeClosedChecker:ERROR \
                          -Xep:Overrides:ERROR \
                          -Xep:PackageLocation:ERROR \
                          -Xep:ParameterName:ERROR \
                          -Xep:ReferenceEquality:ERROR \
                          -Xep:RemoveUnusedImports:ERROR \
                          -Xep:ReturnValueIgnored:ERROR \
                          -Xep:SelfEquals:ERROR \
                          -Xep:SizeGreaterThanOrEqualsZero:ERROR \
                          -Xep:StreamResourceLeak:ERROR \
                          -Xep:TryFailThrowable:ERROR \
                          -Xep:UnnecessaryAssignment:ERROR \
                          -Xep:UnnecessaryParentheses:ERROR \
                          -Xep:UseCorrectAssertInTests:ERROR

