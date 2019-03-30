#!/bin/bash

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

# A simple helper script that runs all of the atest unit tests.
# We have 2 situations we take care of:
#   1. User wants to invoke this script by itself.
#   2. PREUPLOAD hook invokes this script.

ATEST_DIR=`dirname $0`/
ATEST_REAL_PATH=`realpath $ATEST_DIR`
PREUPLOAD_FILES=$@
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

function set_pythonpath() {
  if ! echo $PYTHONPATH | grep -q $ATEST_REAL_PATH; then
    PYTHONPATH=$ATEST_REAL_PATH:$PYTHONPATH
  fi
}

function print_summary() {
    local test_results=$1
    local coverage_run=$2
    if [[ $coverage_run == "coverage" ]]; then
        coverage report -m
        coverage html
    fi
    if [[ $test_results -eq 0 ]]; then
        echo -e "${GREEN}All unittests pass${NC}!"
    else
        echo -e "${RED}There was a unittest failure${NC}"
    fi
}

function run_atest_unittests() {
  echo "Running tests..."
  local coverage_run=$1
  local run_cmd="python"
  local rc=0
  set_pythonpath $coverage_run
  if [[ $coverage_run == "coverage" ]]; then
      # Clear previously coverage data.
      python -m coverage erase
      # Collected coverage data.
      run_cmd="coverage run --source $ATEST_REAL_PATH --append"
  fi

  for test_file in $(find $ATEST_DIR -name "*_unittest.py"); do
    if ! $run_cmd $test_file; then
      rc=1
      echo -e "${RED}$t failed${NC}"
    fi
  done
  echo
  print_summary $rc $coverage_run
  return $rc
}

# Let's check if anything is passed in, if not we assume the user is invoking
# script, but if we get a list of files, assume it's the PREUPLOAD hook.
if [[ -z $PREUPLOAD_FILES ]]; then
  run_atest_unittests
  exit $?
else
  for f in $PREUPLOAD_FILES; do
    # We only want to run this unittest if atest files have been touched.
    if [[ $f == atest/* ]]; then
      run_atest_unittests
      exit $?
    fi
  done
fi

case "$1" in
    'coverage')
        run_atest_unittests "coverage"
        ;;
    *)
        run_atest_unittests
        ;;
esac
