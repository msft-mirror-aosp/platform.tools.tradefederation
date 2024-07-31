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
package com.android.tradefed.invoker.shard;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.StubTest;

import com.google.internal.android.engprod.v1.ProvideTestTargetResponse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link StrictShardHelper}. */
@RunWith(JUnit4.class)
public class DynamicShardHelperTest {
    @Test
    public void testBailoutNoITestSuite() throws Exception {
        IConfiguration config = new Configuration("fake_sharding_config", "desc");
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(new StubTest());
        config.setTests(tests);

        // Set command options such that it appears that dynamic sharding should be used.
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        setter.setOptionValue("shard-count", "3");
        setter.setOptionValue("shard-index", "1");
        setter.setOptionValue("remote-dynamic-sharding", "true");
        config.setCommandOptions(options);
        config.setCommandLine(new String[] {"empty"});

        InvocationContext ctx = new InvocationContext();
        ctx.addInvocationAttribute("invocation_id", "testPool123abc");
        ctx.addInvocationAttribute("invocation-id", "testPool123abc");
        ctx.addInvocationAttribute("attempt_id", "0");
        TestInformation testInfo = TestInformation.newBuilder().setInvocationContext(ctx).build();

        IRescheduler rescheduler = Mockito.mock(IRescheduler.class);

        IDynamicShardingClient mockClient = mock(ConfigurableGrpcDynamicShardingClient.class);
        ProvideTestTargetResponse fakeResponse = ProvideTestTargetResponse.newBuilder().build();
        doReturn(fakeResponse).when(mockClient).provideTestTarget(Mockito.any());

        DynamicShardHelper helper =
                new DynamicShardHelper() {
                    private IDynamicShardingClient getClient() {
                        return mockClient;
                    }
                };

        DynamicShardHelper spyHelper = spy(helper);

        boolean result = spyHelper.shardConfig(config, testInfo, rescheduler, null);

        verify(spyHelper, times(1))
                .shardConfigStrict(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertEquals(config.getCommandOptions().shouldRemoteDynamicShard(), true);
    }
}
