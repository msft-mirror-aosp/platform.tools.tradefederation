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

package com.android.tradefed.util.avd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.result.error.InfraErrorIdentifier;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Optional;

/** Unit tests for {@link InspectionUtil} */
@RunWith(JUnit4.class)
public class InspectionUtilTest {
    // Sample output of `top bn1`
    private static final String PROCESSES =
            "Tasks: 51 total,   1 running,  50 sleeping,   0 stopped,   0 zombie\n"
                + "Mem:    15984M total,    15640M used,      343M free,      102M buffers\n"
                + "Swap:        0M total,        0M used,        0M free,    14051M cached\n"
                + "400%cpu 288%user   0%nice   8%sys  96%idle   8%iow   0%irq   0%sirq   0%host\n"
                + "PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS\n"
                + "925 root         20   0 6.1G 2.8G 2.8G S  280  18.3  25:06.81 crosvm"
                + " --extended-status run\n"
                + "510 root         20   0 1.1G  50M  29M S  4.0   0.3   0:30.08 webRTC -group_id="
                + " -touch_fds=89\n"
                + "395 root         20   0  15M 2.9M 1.8M S  0.0   0.0   0:00.00 nginx: worker"
                + " process\n"
                + "525 root         20   0 816M  14M  13M S  0.0   0.0   0:01.46"
                + " openwrt_control_server --grpc_uds_path=/tmp/cf_avd_0/cvd-1\n"
                + "529 root         20   0 1.7G  22M  21M S  0.0   0.1   1:50.47 netsimd -s \n"
                + "923 root         20   0 6.1G 2.8G 2.8G S  280  18.3  25:06.81 crosvm"
                + " --extended-status run\n";

    // Sample output of `df -P /`
    private static final String DISKSPACE_INFO =
            "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
                    + "overlay           50620216 15385508  35218324      31% /";

    /** Test getDiskspaceUsag. */
    @Test
    public void testGetDiskspaceUsage() throws Exception {
        Optional<Integer> usedPercentage = InspectionUtil.getDiskspaceUsage(DISKSPACE_INFO);
        assertEquals((Integer) 31, usedPercentage.get());

        usedPercentage = InspectionUtil.getDiskspaceUsage("");
        assertFalse(usedPercentage.isPresent());
    }

    /** Test searchProcess. */
    @Test
    public void testSearchProcess() throws Exception {
        for (String p : InspectionUtil.EXPECTED_PROCESSES.keySet()) {
            assertTrue(InspectionUtil.searchProcess(PROCESSES, p));
        }
        assertFalse(InspectionUtil.searchProcess(PROCESSES, "non-existing"));
    }

    /** Test convertErrorSignatureToIdentifier. */
    @Test
    public void testConvertErrorSignatureToIdentifier() throws Exception {
        final String s1 = "fetch_cvd_failure_general,fetch_cvd_failure_resolve_host";
        assertEquals(
                InspectionUtil.convertErrorSignatureToIdentifier(s1),
                InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_CVD_RESOLVE_HOST);
    }
}
