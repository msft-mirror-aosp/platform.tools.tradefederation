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

package com.android.tradefed;

import com.android.tradefed.auth.ServiceAccountKeyCredentialFactoryTest;
import com.android.tradefed.build.AppDeviceBuildInfoTest;
import com.android.tradefed.build.BootstrapBuildProviderTest;
import com.android.tradefed.build.BuildInfoTest;
import com.android.tradefed.build.DeviceBuildDescriptorTest;
import com.android.tradefed.build.DeviceBuildInfoTest;
import com.android.tradefed.build.DeviceFolderBuildInfoTest;
import com.android.tradefed.build.FileDownloadCacheTest;
import com.android.tradefed.build.LocalDeviceBuildProviderTest;
import com.android.tradefed.build.cache.PartialZipDownloadCacheTest;
import com.android.tradefed.build.content.ArtifactDetailsTest;
import com.android.tradefed.build.content.ContentAnalysisResultsTest;
import com.android.tradefed.build.content.DeviceMerkleTreeTest;
import com.android.tradefed.build.content.TestContentAnalyzerTest;
import com.android.tradefed.cache.MerkleTreeTest;
import com.android.tradefed.cache.UploadManifestTest;
import com.android.tradefed.cache.remote.ByteStreamDownloaderTest;
import com.android.tradefed.cache.remote.ByteStreamUploaderTest;
import com.android.tradefed.cache.remote.ChunkerTest;
import com.android.tradefed.cache.remote.RemoteCacheClientTest;
import com.android.tradefed.clearcut.ClearcutClientTest;
import com.android.tradefed.cluster.ClusterBuildProviderTest;
import com.android.tradefed.cluster.ClusterCommandConfigBuilderTest;
import com.android.tradefed.cluster.ClusterCommandEventTest;
import com.android.tradefed.cluster.ClusterCommandLauncherTest;
import com.android.tradefed.cluster.ClusterCommandSchedulerTest;
import com.android.tradefed.cluster.ClusterCommandTest;
import com.android.tradefed.cluster.ClusterDeviceMonitorTest;
import com.android.tradefed.cluster.ClusterEventUploaderTest;
import com.android.tradefed.cluster.ClusterHostUtilTest;
import com.android.tradefed.cluster.ClusterLogSaverTest;
import com.android.tradefed.cluster.SubprocessConfigBuilderTest;
import com.android.tradefed.cluster.SubprocessReportingHelperTest;
import com.android.tradefed.cluster.TestOutputUploaderTest;
import com.android.tradefed.command.CommandFileParserTest;
import com.android.tradefed.command.CommandFileWatcherTest;
import com.android.tradefed.command.CommandInterrupterTest;
import com.android.tradefed.command.CommandOptionsTest;
import com.android.tradefed.command.CommandRunnerTest;
import com.android.tradefed.command.CommandSchedulerTest;
import com.android.tradefed.command.ConsoleTest;
import com.android.tradefed.command.console.ConfigCompleterTest;
import com.android.tradefed.config.ArgsOptionParserTest;
import com.android.tradefed.config.ConfigurationDefTest;
import com.android.tradefed.config.ConfigurationDescriptorTest;
import com.android.tradefed.config.ConfigurationFactoryTest;
import com.android.tradefed.config.ConfigurationTest;
import com.android.tradefed.config.ConfigurationXmlParserTest;
import com.android.tradefed.config.DynamicRemoteFileResolverTest;
import com.android.tradefed.config.GlobalConfigurationTest;
import com.android.tradefed.config.OptionCopierTest;
import com.android.tradefed.config.OptionSetterTest;
import com.android.tradefed.config.OptionUpdateRuleTest;
import com.android.tradefed.config.RetryConfigurationFactoryTest;
import com.android.tradefed.config.SandboxConfigurationFactoryTest;
import com.android.tradefed.config.filter.CommandOptionsGetterTest;
import com.android.tradefed.config.filter.GlobalFilterGetterTest;
import com.android.tradefed.config.filter.OptionFetcherTest;
import com.android.tradefed.config.gcs.GCSConfigurationFactoryTest;
import com.android.tradefed.config.gcs.GCSConfigurationServerTest;
import com.android.tradefed.config.proxy.AutomatedReportersTest;
import com.android.tradefed.config.remote.GcsRemoteFileResolverTest;
import com.android.tradefed.config.remote.HttpRemoteFileResolverTest;
import com.android.tradefed.config.remote.LocalFileResolverTest;
import com.android.tradefed.config.yaml.ConfigurationYamlParserTest;
import com.android.tradefed.device.AndroidDebugBridgeWrapperTest;
import com.android.tradefed.device.BackgroundDeviceActionTest;
import com.android.tradefed.device.DeviceManagerTest;
import com.android.tradefed.device.DeviceSelectionOptionsTest;
import com.android.tradefed.device.DeviceStateMonitorTest;
import com.android.tradefed.device.DumpsysPackageReceiverTest;
import com.android.tradefed.device.FastbootHelperTest;
import com.android.tradefed.device.LocalAndroidVirtualDeviceTest;
import com.android.tradefed.device.ManagedDeviceListTest;
import com.android.tradefed.device.ManagedTestDeviceFactoryTest;
import com.android.tradefed.device.NativeDeviceTest;
import com.android.tradefed.device.RemoteAndroidDeviceTest;
import com.android.tradefed.device.TestDeviceTest;
import com.android.tradefed.device.WaitDeviceRecoveryTest;
import com.android.tradefed.device.WifiCommandUtilTest;
import com.android.tradefed.device.WifiHelperTest;
import com.android.tradefed.device.cloud.AcloudConfigParserTest;
import com.android.tradefed.device.cloud.CommonLogRemoteFileUtilTest;
import com.android.tradefed.device.cloud.GceAvdInfoTest;
import com.android.tradefed.device.cloud.GceManagerTest;
import com.android.tradefed.device.cloud.GceSshTunnelMonitorTest;
import com.android.tradefed.device.cloud.NestedRemoteDeviceTest;
import com.android.tradefed.device.cloud.OxygenUtilTest;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDeviceTest;
import com.android.tradefed.device.cloud.RemoteFileUtilTest;
import com.android.tradefed.device.connection.AdbSshConnectionTest;
import com.android.tradefed.device.connection.AdbTcpConnectionTest;
import com.android.tradefed.device.contentprovider.ContentProviderHandlerTest;
import com.android.tradefed.device.helper.TelephonyHelperTest;
import com.android.tradefed.device.internal.DeviceReleaseReporterTest;
import com.android.tradefed.device.internal.DeviceResetFeatureTest;
import com.android.tradefed.device.internal.DeviceResetHandlerTest;
import com.android.tradefed.device.internal.DeviceSnapshotFeatureTest;
import com.android.tradefed.device.internal.DeviceSnapshotHandlerTest;
import com.android.tradefed.device.internal.EarlyDeviceReleaseFeatureTest;
import com.android.tradefed.device.metric.AtraceCollectorTest;
import com.android.tradefed.device.metric.AtraceRunMetricCollectorTest;
import com.android.tradefed.device.metric.AutoLogCollectorTest;
import com.android.tradefed.device.metric.BaseDeviceMetricCollectorTest;
import com.android.tradefed.device.metric.BluetoothHciSnoopLogCollectorTest;
import com.android.tradefed.device.metric.BugreportzOnFailureCollectorTest;
import com.android.tradefed.device.metric.BugreportzOnTestCaseFailureCollectorTest;
import com.android.tradefed.device.metric.ClangCodeCoverageCollectorTest;
import com.android.tradefed.device.metric.CodeCoverageCollectorTest;
import com.android.tradefed.device.metric.DebugHostLogOnFailureCollectorTest;
import com.android.tradefed.device.metric.DeviceMetricDataTest;
import com.android.tradefed.device.metric.EmulatorMemoryCpuCapturerTest;
import com.android.tradefed.device.metric.FilePullerDeviceMetricCollectorTest;
import com.android.tradefed.device.metric.FilePullerLogCollectorTest;
import com.android.tradefed.device.metric.GcovCodeCoverageCollectorTest;
import com.android.tradefed.device.metric.GcovKernelCodeCoverageCollectorTest;
import com.android.tradefed.device.metric.HostStatsdMetricCollectorTest;
import com.android.tradefed.device.metric.JavaCodeCoverageCollectorTest;
import com.android.tradefed.device.metric.LogcatOnFailureCollectorTest;
import com.android.tradefed.device.metric.LogcatTimingMetricCollectorTest;
import com.android.tradefed.device.metric.PerfettoPullerMetricCollectorTest;
import com.android.tradefed.device.metric.RebootReasonCollectorTest;
import com.android.tradefed.device.metric.RuntimeRestartCollectorTest;
import com.android.tradefed.device.metric.ScreenshotOnFailureCollectorTest;
import com.android.tradefed.device.metric.ShowmapPullerMetricCollectorTest;
import com.android.tradefed.device.metric.TraceCmdCollectorTest;
import com.android.tradefed.device.recovery.BatteryUnavailableDeviceRecoveryTest;
import com.android.tradefed.device.recovery.RunConfigDeviceRecoveryTest;
import com.android.tradefed.device.recovery.UsbResetMultiDeviceRecoveryTest;
import com.android.tradefed.device.recovery.UsbResetRunConfigRecoveryTest;
import com.android.tradefed.error.HarnessExceptionTest;
import com.android.tradefed.external_dependency.ExternalDependencyTest;
import com.android.tradefed.host.LocalHostResourceManagerTest;
import com.android.tradefed.host.gcs.GCSHostResourceManagerTest;
import com.android.tradefed.invoker.InvocationContextTest;
import com.android.tradefed.invoker.InvocationExecutionTest;
import com.android.tradefed.invoker.RemoteInvocationExecutionTest;
import com.android.tradefed.invoker.SandboxedInvocationExecutionTest;
import com.android.tradefed.invoker.ShardListenerTest;
import com.android.tradefed.invoker.ShardMainResultForwarderTest;
import com.android.tradefed.invoker.TestInvocationMultiTest;
import com.android.tradefed.invoker.TestInvocationTest;
import com.android.tradefed.invoker.UnexecutedTestReporterThreadTest;
import com.android.tradefed.invoker.logger.CurrentInvocationTest;
import com.android.tradefed.invoker.logger.InvocationLocalTest;
import com.android.tradefed.invoker.logger.InvocationMetricLoggerTest;
import com.android.tradefed.invoker.logger.TfObjectTrackerTest;
import com.android.tradefed.invoker.sandbox.ParentSandboxInvocationExecutionTest;
import com.android.tradefed.invoker.shard.DynamicShardHelperTest;
import com.android.tradefed.invoker.shard.ParentShardReplicateTest;
import com.android.tradefed.invoker.shard.RemoteDynamicPoolTest;
import com.android.tradefed.invoker.shard.ShardHelperTest;
import com.android.tradefed.invoker.shard.StrictShardHelperTest;
import com.android.tradefed.invoker.shard.TestsPoolPollerTest;
import com.android.tradefed.invoker.shard.token.CecControllerTokenProviderTest;
import com.android.tradefed.invoker.shard.token.TelephonyTokenProviderTest;
import com.android.tradefed.invoker.shard.token.TokenProviderHelperTest;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorServiceTest;
import com.android.tradefed.lite.DryRunnerTest;
import com.android.tradefed.lite.HostUtilsTest;
import com.android.tradefed.log.FileLoggerTest;
import com.android.tradefed.log.HistoryLoggerTest;
import com.android.tradefed.log.LogRegistryTest;
import com.android.tradefed.log.SimpleFileLoggerTest;
import com.android.tradefed.log.TerribleFailureEmailHandlerTest;
import com.android.tradefed.monitoring.LabResourceDeviceMonitorTest;
import com.android.tradefed.monitoring.collector.DeviceBatteryResourceMetricCollectorTest;
import com.android.tradefed.monitoring.collector.DeviceInternetAccessibilityResourceMetricCollectorTest;
import com.android.tradefed.monitoring.collector.DeviceStorageAgeResourceMetricCollectorTest;
import com.android.tradefed.monitoring.collector.DeviceWifiResourceMetricCollectorTest;
import com.android.tradefed.monitoring.collector.ResourceMetricUtilTest;
import com.android.tradefed.observatory.TestDiscoveryExecutorTest;
import com.android.tradefed.observatory.TestDiscoveryInvokerTest;
import com.android.tradefed.postprocessor.AggregatePostProcessorTest;
import com.android.tradefed.postprocessor.AveragePostProcessorTest;
import com.android.tradefed.postprocessor.BasePostProcessorTest;
import com.android.tradefed.postprocessor.MetricFilePostProcessorTest;
import com.android.tradefed.postprocessor.PerfettoGenericPostProcessorTest;
import com.android.tradefed.postprocessor.StatsdBeforeAfterGaugeMetricPostProcessorTest;
import com.android.tradefed.postprocessor.StatsdEventMetricPostProcessorTest;
import com.android.tradefed.postprocessor.StatsdGenericPostProcessorTest;
import com.android.tradefed.result.ATestFileSystemLogSaverTest;
import com.android.tradefed.result.BugreportCollectorTest;
import com.android.tradefed.result.CollectingTestListenerTest;
import com.android.tradefed.result.ConsoleResultReporterTest;
import com.android.tradefed.result.CountingTestResultListenerTest;
import com.android.tradefed.result.DeviceFileReporterTest;
import com.android.tradefed.result.FailureDescriptionTest;
import com.android.tradefed.result.FileSystemLogSaverTest;
import com.android.tradefed.result.InvocationToJUnitResultForwarderTest;
import com.android.tradefed.result.JUnitToInvocationResultForwarderTest;
import com.android.tradefed.result.JsonHttpTestResultReporterTest;
import com.android.tradefed.result.LUCIResultReporterTest;
import com.android.tradefed.result.LegacySubprocessResultsReporterTest;
import com.android.tradefed.result.LogFileSaverTest;
import com.android.tradefed.result.LogcatCrashResultForwarderTest;
import com.android.tradefed.result.MetricsXMLResultReporterTest;
import com.android.tradefed.result.MultiFailureDescriptionTest;
import com.android.tradefed.result.PassingTestFileReporterTest;
import com.android.tradefed.result.ReportPassedTestsTest;
import com.android.tradefed.result.SnapshotInputStreamSourceTest;
import com.android.tradefed.result.SubprocessResultsReporterTest;
import com.android.tradefed.result.TestDescriptionTest;
import com.android.tradefed.result.TestResultListenerTest;
import com.android.tradefed.result.TestResultTest;
import com.android.tradefed.result.TestRunResultTest;
import com.android.tradefed.result.TestSummaryTest;
import com.android.tradefed.result.XmlResultReporterTest;
import com.android.tradefed.result.ddmlib.TestRunToTestInvocationForwarderTest;
import com.android.tradefed.result.error.ErrorIdentifierTest;
import com.android.tradefed.result.proto.FileProtoResultReporterTest;
import com.android.tradefed.result.proto.InvocationProtoResultReporterTest;
import com.android.tradefed.result.proto.ModuleProtoResultReporterTest;
import com.android.tradefed.result.proto.ProtoResultParserTest;
import com.android.tradefed.result.proto.ProtoResultReporterTest;
import com.android.tradefed.result.proto.StreamProtoResultReporterTest;
import com.android.tradefed.result.resultdb.ResultDBReporterTest;
import com.android.tradefed.result.resultdb.ResultDBUtilTest;
import com.android.tradefed.result.skipped.SkipFeatureTest;
import com.android.tradefed.result.skipped.SkipManagerTest;
import com.android.tradefed.result.skipped.SkipReasonTest;
import com.android.tradefed.result.suite.FormattedGeneratorReporterTest;
import com.android.tradefed.result.suite.XmlSuiteResultFormatterTest;
import com.android.tradefed.retry.BaseRetryDecisionTest;
import com.android.tradefed.retry.ResultAggregatorTest;
import com.android.tradefed.sandbox.SandboxConfigDumpTest;
import com.android.tradefed.sandbox.SandboxConfigUtilTest;
import com.android.tradefed.sandbox.SandboxInvocationRunnerTest;
import com.android.tradefed.sandbox.TradefedSandboxTest;
import com.android.tradefed.service.management.DeviceManagementGrpcServerTest;
import com.android.tradefed.service.management.TestInvocationManagementServerTest;
import com.android.tradefed.suite.checker.ActivityStatusCheckerTest;
import com.android.tradefed.suite.checker.DeviceBaselineCheckerTest;
import com.android.tradefed.suite.checker.DeviceSettingCheckerTest;
import com.android.tradefed.suite.checker.DeviceStorageStatusCheckerTest;
import com.android.tradefed.suite.checker.EnforcedSeLinuxCheckerTest;
import com.android.tradefed.suite.checker.FilesystemRootCheckerTest;
import com.android.tradefed.suite.checker.KeyguardStatusCheckerTest;
import com.android.tradefed.suite.checker.LeakedThreadStatusCheckerTest;
import com.android.tradefed.suite.checker.ShellStatusCheckerTest;
import com.android.tradefed.suite.checker.SystemServerFileDescriptorCheckerTest;
import com.android.tradefed.suite.checker.SystemServerStatusCheckerTest;
import com.android.tradefed.suite.checker.TimeStatusCheckerTest;
import com.android.tradefed.suite.checker.UserCheckerTest;
import com.android.tradefed.suite.checker.baseline.CommandBaselineSetterTest;
import com.android.tradefed.suite.checker.baseline.DeviceBaselineSetterTest;
import com.android.tradefed.suite.checker.baseline.LockSettingsBaselineSetterTest;
import com.android.tradefed.suite.checker.baseline.SettingsBaselineSetterTest;
import com.android.tradefed.targetprep.AllTestAppsInstallSetupTest;
import com.android.tradefed.targetprep.AoaTargetPreparerTest;
import com.android.tradefed.targetprep.AppSetupTest;
import com.android.tradefed.targetprep.BaseTargetPreparerTest;
import com.android.tradefed.targetprep.CompileBootImageWithSpeedTargetPreparerTest;
import com.android.tradefed.targetprep.CreateUserPreparerTest;
import com.android.tradefed.targetprep.DefaultTestsZipInstallerTest;
import com.android.tradefed.targetprep.DeviceActionTargetPreparerTest;
import com.android.tradefed.targetprep.DeviceFlashPreparerTest;
import com.android.tradefed.targetprep.DeviceOwnerTargetPreparerTest;
import com.android.tradefed.targetprep.DeviceSetupTest;
import com.android.tradefed.targetprep.DeviceStorageFillerTest;
import com.android.tradefed.targetprep.DeviceStringPusherTest;
import com.android.tradefed.targetprep.DisableSELinuxTargetPreparerTest;
import com.android.tradefed.targetprep.DynamicSystemPreparerTest;
import com.android.tradefed.targetprep.FastbootCommandPreparerTest;
import com.android.tradefed.targetprep.FastbootDeviceFlasherTest;
import com.android.tradefed.targetprep.FeatureFlagTargetPreparerTest;
import com.android.tradefed.targetprep.FlashingResourcesParserTest;
import com.android.tradefed.targetprep.GkiDeviceFlashPreparerTest;
import com.android.tradefed.targetprep.GsiDeviceFlashPreparerTest;
import com.android.tradefed.targetprep.InstallAllTestZipAppsSetupTest;
import com.android.tradefed.targetprep.InstallApexModuleTargetPreparerTest;
import com.android.tradefed.targetprep.InstallApkSetupTest;
import com.android.tradefed.targetprep.InstallKernelModulePreparerTest;
import com.android.tradefed.targetprep.InstrumentationPreparerTest;
import com.android.tradefed.targetprep.KnownFailurePreparerTest;
import com.android.tradefed.targetprep.LocalEmulatorLaunchTest;
import com.android.tradefed.targetprep.MixKernelTargetPreparerTest;
import com.android.tradefed.targetprep.ModuleOemTargetPreparerTest;
import com.android.tradefed.targetprep.ModulePusherTest;
import com.android.tradefed.targetprep.OtaUpdateDeviceFlasherTest;
import com.android.tradefed.targetprep.PushFilePreparerTest;
import com.android.tradefed.targetprep.PythonVirtualenvPreparerTest;
import com.android.tradefed.targetprep.RebootTargetPreparerTest;
import com.android.tradefed.targetprep.RestartSystemServerTargetPreparerTest;
import com.android.tradefed.targetprep.RootTargetPreparerTest;
import com.android.tradefed.targetprep.RunCommandTargetPreparerTest;
import com.android.tradefed.targetprep.RunHostCommandTargetPreparerTest;
import com.android.tradefed.targetprep.RunHostScriptTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnCloneProfileTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnPrivateProfileTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnSdkSandboxTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnSecondaryUserTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnSystemUserTargetPreparerTest;
import com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparerTest;
import com.android.tradefed.targetprep.StopServicesSetupTest;
import com.android.tradefed.targetprep.SwitchUserTargetPreparerTest;
import com.android.tradefed.targetprep.SystemUpdaterDeviceFlasherTest;
import com.android.tradefed.targetprep.TargetSetupErrorTest;
import com.android.tradefed.targetprep.TemperatureThrottlingWaiterTest;
import com.android.tradefed.targetprep.TestAppInstallSetupTest;
import com.android.tradefed.targetprep.TestFilePushSetupTest;
import com.android.tradefed.targetprep.UserCleanerTest;
import com.android.tradefed.targetprep.VisibleBackgroundUserPreparerTest;
import com.android.tradefed.targetprep.adb.AdbStopServerPreparerTest;
import com.android.tradefed.targetprep.app.NoApkTestSkipperTest;
import com.android.tradefed.targetprep.incremental.ApkChangeDetectorTest;
import com.android.tradefed.targetprep.multi.MergeMultiBuildTargetPreparerTest;
import com.android.tradefed.targetprep.multi.MixImageZipPreparerTest;
import com.android.tradefed.targetprep.multi.PairingMultiTargetPreparerTest;
import com.android.tradefed.targetprep.suite.SuiteApkInstallerTest;
import com.android.tradefed.testtype.AndroidJUnitTestTest;
import com.android.tradefed.testtype.ArtGTestTest;
import com.android.tradefed.testtype.ArtRunTestTest;
import com.android.tradefed.testtype.DeviceBatteryLevelCheckerTest;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunnerTest;
import com.android.tradefed.testtype.DeviceSuiteTest;
import com.android.tradefed.testtype.DeviceTestCaseTest;
import com.android.tradefed.testtype.DeviceTestSuiteTest;
import com.android.tradefed.testtype.FakeTestTest;
import com.android.tradefed.testtype.GTestBaseTest;
import com.android.tradefed.testtype.GTestListTestParserTest;
import com.android.tradefed.testtype.GTestListenerTest;
import com.android.tradefed.testtype.GTestResultParserTest;
import com.android.tradefed.testtype.GTestTest;
import com.android.tradefed.testtype.GTestXmlResultParserTest;
import com.android.tradefed.testtype.GoogleBenchmarkResultParserTest;
import com.android.tradefed.testtype.GoogleBenchmarkTestTest;
import com.android.tradefed.testtype.HostGTestTest;
import com.android.tradefed.testtype.HostTestTest;
import com.android.tradefed.testtype.InstalledInstrumentationsTestTest;
import com.android.tradefed.testtype.InstrumentationFileTestTest;
import com.android.tradefed.testtype.InstrumentationTestTest;
import com.android.tradefed.testtype.IsolatedHostTestTest;
import com.android.tradefed.testtype.JarHostTestTest;
import com.android.tradefed.testtype.NativeBenchmarkTestParserTest;
import com.android.tradefed.testtype.NativeBenchmarkTestTest;
import com.android.tradefed.testtype.NativeStressTestParserTest;
import com.android.tradefed.testtype.NativeStressTestTest;
import com.android.tradefed.testtype.NoisyDryRunTestTest;
import com.android.tradefed.testtype.PythonUnitTestResultParserTest;
import com.android.tradefed.testtype.PythonUnitTestRunnerTest;
import com.android.tradefed.testtype.TestTimeoutEnforcerTest;
import com.android.tradefed.testtype.TfTestLauncherTest;
import com.android.tradefed.testtype.UsbResetTestTest;
import com.android.tradefed.testtype.binary.ExecutableHostTestTest;
import com.android.tradefed.testtype.binary.ExecutableTargetTestTest;
import com.android.tradefed.testtype.binary.KTapResultParserTest;
import com.android.tradefed.testtype.binary.KUnitModuleTestTest;
import com.android.tradefed.testtype.binary.KernelTargetTestTest;
import com.android.tradefed.testtype.host.CoverageMeasurementForwarderTest;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4TestTest;
import com.android.tradefed.testtype.junit4.DeviceParameterizedRunnerTest;
import com.android.tradefed.testtype.junit4.JUnit4ResultForwarderTest;
import com.android.tradefed.testtype.mobly.MoblyBinaryHostTestTest;
import com.android.tradefed.testtype.mobly.MoblyYamlResultParserTest;
import com.android.tradefed.testtype.python.PythonBinaryHostTestTest;
import com.android.tradefed.testtype.rust.RustBinaryHostTestTest;
import com.android.tradefed.testtype.rust.RustBinaryTestTest;
import com.android.tradefed.testtype.rust.RustTestBaseTest;
import com.android.tradefed.testtype.rust.RustTestResultParserTest;
import com.android.tradefed.testtype.suite.AtestRunnerTest;
import com.android.tradefed.testtype.suite.BaseTestSuiteTest;
import com.android.tradefed.testtype.suite.GranularRetriableTestWrapperTest;
import com.android.tradefed.testtype.suite.ITestSuiteIntegrationTest;
import com.android.tradefed.testtype.suite.ITestSuiteMultiTest;
import com.android.tradefed.testtype.suite.ITestSuiteTest;
import com.android.tradefed.testtype.suite.ModuleDefinitionMultiTest;
import com.android.tradefed.testtype.suite.ModuleDefinitionTest;
import com.android.tradefed.testtype.suite.ModuleListenerTest;
import com.android.tradefed.testtype.suite.ModuleMergerTest;
import com.android.tradefed.testtype.suite.ModuleSplitterTest;
import com.android.tradefed.testtype.suite.RemoteTestTimeOutEnforcerTest;
import com.android.tradefed.testtype.suite.ResolvePartialDownloadTest;
import com.android.tradefed.testtype.suite.SuiteModuleLoaderTest;
import com.android.tradefed.testtype.suite.SuiteTestFilterTest;
import com.android.tradefed.testtype.suite.TestMappingSuiteRunnerTest;
import com.android.tradefed.testtype.suite.TestSuiteInfoTest;
import com.android.tradefed.testtype.suite.TfSuiteRunnerTest;
import com.android.tradefed.testtype.suite.ValidateSuiteConfigHelperTest;
import com.android.tradefed.testtype.suite.module.ArchModuleControllerTest;
import com.android.tradefed.testtype.suite.module.BaseModuleControllerTest;
import com.android.tradefed.testtype.suite.module.CarModuleControllerTest;
import com.android.tradefed.testtype.suite.module.DeviceFeatureModuleControllerTest;
import com.android.tradefed.testtype.suite.module.KernelTestModuleControllerTest;
import com.android.tradefed.testtype.suite.module.MaxSdkModuleControllerTest;
import com.android.tradefed.testtype.suite.module.MinApiLevelModuleControllerTest;
import com.android.tradefed.testtype.suite.module.NativeBridgeModuleControllerTest;
import com.android.tradefed.testtype.suite.module.PackageInstalledModuleControllerTest;
import com.android.tradefed.testtype.suite.module.ShippingApiLevelModuleControllerTest;
import com.android.tradefed.testtype.suite.params.InstantAppHandlerTest;
import com.android.tradefed.testtype.suite.params.MainlineModuleHandlerTest;
import com.android.tradefed.testtype.suite.params.ModuleParametersHelperTest;
import com.android.tradefed.testtype.suite.params.RunOnSdkSandboxHandlerTest;
import com.android.tradefed.testtype.suite.params.SecondaryUserHandlerTest;
import com.android.tradefed.testtype.suite.params.SecondaryUserOnSecondaryDisplayHandlerTest;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnCloneProfileParameterHandlerTest;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnPrivateProfileParameterHandlerTest;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnSecondaryUserParameterHandlerTest;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnWorkProfileParameterHandlerTest;
import com.android.tradefed.testtype.suite.retry.ResultsPlayerTest;
import com.android.tradefed.testtype.suite.retry.RetryReschedulerTest;
import com.android.tradefed.util.AaptParserTest;
import com.android.tradefed.util.AbiFormatterTest;
import com.android.tradefed.util.AbiUtilsTest;
import com.android.tradefed.util.AdbRootElevatorTest;
import com.android.tradefed.util.AppVersionFetcherTest;
import com.android.tradefed.util.ArrayUtilTest;
import com.android.tradefed.util.BluetoothUtilsTest;
import com.android.tradefed.util.BugreportTest;
import com.android.tradefed.util.BuildTestsZipUtilsTest;
import com.android.tradefed.util.BundletoolUtilTest;
import com.android.tradefed.util.ByteArrayListTest;
import com.android.tradefed.util.ClangProfileIndexerTest;
import com.android.tradefed.util.ClassPathScannerTest;
import com.android.tradefed.util.ConditionPriorityBlockingQueueTest;
import com.android.tradefed.util.DeviceActionUtilTest;
import com.android.tradefed.util.DirectedGraphTest;
import com.android.tradefed.util.EmailTest;
import com.android.tradefed.util.FakeTestsZipFolderTest;
import com.android.tradefed.util.FileIdleMonitorTest;
import com.android.tradefed.util.FileUtilTest;
import com.android.tradefed.util.FixedByteArrayOutputStreamTest;
import com.android.tradefed.util.FuseUtilTest;
import com.android.tradefed.util.GCSFileDownloaderTest;
import com.android.tradefed.util.GceRemoteCmdFormatterTest;
import com.android.tradefed.util.GoogleApiClientUtilTest;
import com.android.tradefed.util.HprofAllocSiteParserTest;
import com.android.tradefed.util.JUnitXmlParserTest;
import com.android.tradefed.util.JavaCodeCoverageFlusherTest;
import com.android.tradefed.util.KernelModuleUtilsTest;
import com.android.tradefed.util.KeyguardControllerStateTest;
import com.android.tradefed.util.ListInstrumentationParserTest;
import com.android.tradefed.util.LocalRunInstructionBuilderTest;
import com.android.tradefed.util.LogcatEventParserTest;
import com.android.tradefed.util.MetricUtilityTest;
import com.android.tradefed.util.ModuleTestTypeUtilTest;
import com.android.tradefed.util.MultiMapTest;
import com.android.tradefed.util.NativeCodeCoverageFlusherTest;
import com.android.tradefed.util.PairTest;
import com.android.tradefed.util.PropertyChangerTest;
import com.android.tradefed.util.ProtoUtilTest;
import com.android.tradefed.util.PsParserTest;
import com.android.tradefed.util.PythonVirtualenvHelperTest;
import com.android.tradefed.util.QuotationAwareTokenizerTest;
import com.android.tradefed.util.RegexTrieTest;
import com.android.tradefed.util.RemoteZipTest;
import com.android.tradefed.util.RequestUtilTest;
import com.android.tradefed.util.ResourceUtilTest;
import com.android.tradefed.util.RestApiHelperTest;
import com.android.tradefed.util.RunUtilTest;
import com.android.tradefed.util.SearchArtifactUtilTest;
import com.android.tradefed.util.SerializationUtilTest;
import com.android.tradefed.util.ShellOutputReceiverStreamTest;
import com.android.tradefed.util.SimplePerfStatResultParserTest;
import com.android.tradefed.util.SimplePerfUtilTest;
import com.android.tradefed.util.SimpleStatsTest;
import com.android.tradefed.util.SizeLimitedOutputStreamTest;
import com.android.tradefed.util.Sl4aBluetoothUtilTest;
import com.android.tradefed.util.SparseImageUtilTest;
import com.android.tradefed.util.StreamUtilTest;
import com.android.tradefed.util.StringEscapeUtilsTest;
import com.android.tradefed.util.StringUtilTest;
import com.android.tradefed.util.SubprocessTestResultsParserTest;
import com.android.tradefed.util.TableBuilderTest;
import com.android.tradefed.util.TableFormatterTest;
import com.android.tradefed.util.TarUtilTest;
import com.android.tradefed.util.TargetFileUtilsTest;
import com.android.tradefed.util.TestRunnerUtilTest;
import com.android.tradefed.util.TimeUtilTest;
import com.android.tradefed.util.TimeValTest;
import com.android.tradefed.util.VersionParserTest;
import com.android.tradefed.util.ZipUtil2Test;
import com.android.tradefed.util.ZipUtilTest;
import com.android.tradefed.util.executor.ParallelDeviceExecutorTest;
import com.android.tradefed.util.hostmetric.AbstractHostMonitorTest;
import com.android.tradefed.util.hostmetric.HeapHostMonitorTest;
import com.android.tradefed.util.image.DeviceImageTrackerTest;
import com.android.tradefed.util.keystore.JSONFileKeyStoreClientTest;
import com.android.tradefed.util.keystore.JSONFileKeyStoreFactoryTest;
import com.android.tradefed.util.net.HttpHelperTest;
import com.android.tradefed.util.net.HttpMultipartPostTest;
import com.android.tradefed.util.proto.TestRecordProtoUtilTest;
import com.android.tradefed.util.proto.TfMetricProtoUtilTest;
import com.android.tradefed.util.sl4a.Sl4aClientTest;
import com.android.tradefed.util.sl4a.Sl4aEventDispatcherTest;
import com.android.tradefed.util.statsd.ConfigUtilTest;
import com.android.tradefed.util.statsd.MetricUtilTest;
import com.android.tradefed.util.testmapping.TestInfoTest;
import com.android.tradefed.util.testmapping.TestMappingTest;
import com.android.tradefed.util.zip.CentralDirectoryInfoTest;
import com.android.tradefed.util.zip.MergedZipEntryCollectionTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/**
 * A test suite for all Trade Federation unit tests running under Junit4.
 *
 * <p>All tests listed here should be self-contained, and should not require any external
 * dependencies.
 */
@RunWith(Suite.class)
@SuiteClasses({
    // Auth
    ServiceAccountKeyCredentialFactoryTest.class,

    // build
    AppDeviceBuildInfoTest.class,
    BootstrapBuildProviderTest.class,
    BuildInfoTest.class,
    DeviceBuildInfoTest.class,
    DeviceBuildDescriptorTest.class,
    DeviceFolderBuildInfoTest.class,
    FileDownloadCacheTest.class,
    LocalDeviceBuildProviderTest.class,

    // build.cache
    PartialZipDownloadCacheTest.class,

    // build.content
    ArtifactDetailsTest.class,
    ContentAnalysisResultsTest.class,
    DeviceMerkleTreeTest.class,
    TestContentAnalyzerTest.class,

    // cache
    MerkleTreeTest.class,
    ByteStreamDownloaderTest.class,
    ByteStreamUploaderTest.class,
    ChunkerTest.class,
    RemoteCacheClientTest.class,
    UploadManifestTest.class,

    // clearcut
    ClearcutClientTest.class,

    // cluster
    ClusterBuildProviderTest.class,
    ClusterCommandConfigBuilderTest.class,
    ClusterCommandEventTest.class,
    ClusterCommandLauncherTest.class,
    ClusterCommandSchedulerTest.class,
    ClusterCommandTest.class,
    ClusterDeviceMonitorTest.class,
    ClusterEventUploaderTest.class,
    ClusterHostUtilTest.class,
    ClusterLogSaverTest.class,
    SubprocessConfigBuilderTest.class,
    SubprocessReportingHelperTest.class,
    TestOutputUploaderTest.class,

    // command
    CommandFileParserTest.class,
    CommandFileWatcherTest.class,
    CommandInterrupterTest.class,
    CommandOptionsTest.class,
    CommandRunnerTest.class,
    CommandSchedulerTest.class,
    ConsoleTest.class,

    // command.console
    ConfigCompleterTest.class,

    // config
    ArgsOptionParserTest.class,
    ConfigurationDefTest.class,
    ConfigurationDescriptorTest.class,
    ConfigurationFactoryTest.class,
    ConfigurationTest.class,
    ConfigurationXmlParserTest.class,
    DynamicRemoteFileResolverTest.class,
    GlobalConfigurationTest.class,
    OptionCopierTest.class,
    OptionSetterTest.class,
    OptionUpdateRuleTest.class,
    RetryConfigurationFactoryTest.class,
    SandboxConfigurationFactoryTest.class,

    // config.filter
    CommandOptionsGetterTest.class,
    GlobalFilterGetterTest.class,
    OptionFetcherTest.class,

    // config.gcs
    GCSConfigurationServerTest.class,
    GCSConfigurationFactoryTest.class,

    // config.proxy
    AutomatedReportersTest.class,

    // config.remote
    GcsRemoteFileResolverTest.class,
    HttpRemoteFileResolverTest.class,
    LocalFileResolverTest.class,

    // config.yaml
    ConfigurationYamlParserTest.class,

    // device
    AndroidDebugBridgeWrapperTest.class,
    BackgroundDeviceActionTest.class,
    DeviceManagerTest.class,
    DeviceSelectionOptionsTest.class,
    DeviceStateMonitorTest.class,
    DumpsysPackageReceiverTest.class,
    FastbootHelperTest.class,
    LocalAndroidVirtualDeviceTest.class,
    ManagedDeviceListTest.class,
    ManagedTestDeviceFactoryTest.class,
    NativeDeviceTest.class,
    RemoteAndroidDeviceTest.class,
    PropertyChangerTest.class,
    TargetFileUtilsTest.class,
    TestDeviceTest.class,
    WaitDeviceRecoveryTest.class,
    WifiHelperTest.class,
    WifiCommandUtilTest.class,

    // device.cloud
    AcloudConfigParserTest.class,
    CommonLogRemoteFileUtilTest.class,
    GceAvdInfoTest.class,
    GceManagerTest.class,
    GceSshTunnelMonitorTest.class,
    NestedRemoteDeviceTest.class,
    OxygenUtilTest.class,
    RemoteAndroidVirtualDeviceTest.class,
    RemoteFileUtilTest.class,

    // device.connection
    AdbSshConnectionTest.class,
    AdbTcpConnectionTest.class,

    // device.contentprovider
    ContentProviderHandlerTest.class,

    // device.helper
    TelephonyHelperTest.class,

    // device.internal
    DeviceResetFeatureTest.class,
    DeviceResetHandlerTest.class,
    DeviceSnapshotFeatureTest.class,
    DeviceSnapshotHandlerTest.class,
    EarlyDeviceReleaseFeatureTest.class,
    DeviceReleaseReporterTest.class,

    // device.metric
    AtraceCollectorTest.class,
    AtraceRunMetricCollectorTest.class,
    AutoLogCollectorTest.class,
    BaseDeviceMetricCollectorTest.class,
    BluetoothHciSnoopLogCollectorTest.class,
    BugreportzOnTestCaseFailureCollectorTest.class,
    BugreportzOnFailureCollectorTest.class,
    ClangCodeCoverageCollectorTest.class,
    CodeCoverageCollectorTest.class,
    DebugHostLogOnFailureCollectorTest.class,
    DeviceMetricDataTest.class,
    EmulatorMemoryCpuCapturerTest.class,
    FilePullerDeviceMetricCollectorTest.class,
    FilePullerLogCollectorTest.class,
    GcovCodeCoverageCollectorTest.class,
    GcovKernelCodeCoverageCollectorTest.class,
    HostStatsdMetricCollectorTest.class,
    JavaCodeCoverageCollectorTest.class,
    LogcatOnFailureCollectorTest.class,
    LogcatTimingMetricCollectorTest.class,
    PerfettoPullerMetricCollectorTest.class,
    RebootReasonCollectorTest.class,
    RuntimeRestartCollectorTest.class,
    ScreenshotOnFailureCollectorTest.class,
    ShowmapPullerMetricCollectorTest.class,
    TraceCmdCollectorTest.class,

    // device.recovery
    BatteryUnavailableDeviceRecoveryTest.class,
    RunConfigDeviceRecoveryTest.class,
    UsbResetMultiDeviceRecoveryTest.class,
    UsbResetRunConfigRecoveryTest.class,

    // error
    HarnessExceptionTest.class,

    // external_dependency
    ExternalDependencyTest.class,

    // host
    LocalHostResourceManagerTest.class,

    // host.gcs
    GCSHostResourceManagerTest.class,

    // invoker
    InvocationContextTest.class,
    InvocationExecutionTest.class,
    RemoteInvocationExecutionTest.class,
    ShardListenerTest.class,
    ShardMainResultForwarderTest.class,
    TestInvocationMultiTest.class,
    TestInvocationTest.class,
    UnexecutedTestReporterThreadTest.class,

    // invoker.logger
    CurrentInvocationTest.class,
    InvocationLocalTest.class,
    InvocationMetricLoggerTest.class,
    TfObjectTrackerTest.class,

    // invoker.shard
    DynamicShardHelperTest.class,
    ParentShardReplicateTest.class,
    RemoteDynamicPoolTest.class,
    ShardHelperTest.class,
    StrictShardHelperTest.class,
    TestsPoolPollerTest.class,

    // invoker.shard.token
    CecControllerTokenProviderTest.class,
    TelephonyTokenProviderTest.class,
    TokenProviderHelperTest.class,

    // invoker.sandbox
    ParentSandboxInvocationExecutionTest.class,

    // invoker.tracing
    TracePropagatingExecutorServiceTest.class,

    // lite
    DryRunnerTest.class,
    HostUtilsTest.class,

    // log
    FileLoggerTest.class,
    HistoryLoggerTest.class,
    LogRegistryTest.class,
    SimpleFileLoggerTest.class,
    TerribleFailureEmailHandlerTest.class,

    // observatory
    TestDiscoveryExecutorTest.class,
    TestDiscoveryInvokerTest.class,

    // postprocessor
    AggregatePostProcessorTest.class,
    AveragePostProcessorTest.class,
    BasePostProcessorTest.class,
    MetricFilePostProcessorTest.class,
    PerfettoGenericPostProcessorTest.class,
    StatsdBeforeAfterGaugeMetricPostProcessorTest.class,
    StatsdEventMetricPostProcessorTest.class,
    StatsdGenericPostProcessorTest.class,

    // result
    ATestFileSystemLogSaverTest.class,
    BugreportCollectorTest.class,
    CollectingTestListenerTest.class,
    ConsoleResultReporterTest.class,
    CountingTestResultListenerTest.class,
    DeviceFileReporterTest.class,
    FailureDescriptionTest.class,
    FileSystemLogSaverTest.class,
    InvocationToJUnitResultForwarderTest.class,
    JsonHttpTestResultReporterTest.class,
    JUnitToInvocationResultForwarderTest.class,
    LocalRunInstructionBuilderTest.class,
    LogcatCrashResultForwarderTest.class,
    LogFileSaverTest.class,
    LUCIResultReporterTest.class,
    MetricsXMLResultReporterTest.class,
    MultiFailureDescriptionTest.class,
    SnapshotInputStreamSourceTest.class,
    SubprocessResultsReporterTest.class,
    PassingTestFileReporterTest.class,
    ReportPassedTestsTest.class,
    TestDescriptionTest.class,
    TestResultListenerTest.class,
    TestResultTest.class,
    TestRunResultTest.class,
    TestSummaryTest.class,
    XmlResultReporterTest.class,

    // result.ddmlib
    TestRunToTestInvocationForwarderTest.class,

    // result.error
    ErrorIdentifierTest.class,

    // result.proto
    FileProtoResultReporterTest.class,
    InvocationProtoResultReporterTest.class,
    ModuleProtoResultReporterTest.class,
    ProtoResultParserTest.class,
    ProtoResultReporterTest.class,
    StreamProtoResultReporterTest.class,

    // result.resultdb
    ResultDBReporterTest.class,
    ResultDBUtilTest.class,

    // result.skipped
    SkipFeatureTest.class,
    SkipManagerTest.class,
    SkipReasonTest.class,

    // result.suite
    FormattedGeneratorReporterTest.class,
    XmlSuiteResultFormatterTest.class,

    // retry
    BaseRetryDecisionTest.class,
    ResultAggregatorTest.class,

    // targetprep
    AllTestAppsInstallSetupTest.class,
    AoaTargetPreparerTest.class,
    AppSetupTest.class,
    BaseTargetPreparerTest.class,
    CompileBootImageWithSpeedTargetPreparerTest.class,
    CreateUserPreparerTest.class,
    DefaultTestsZipInstallerTest.class,
    DeviceActionTargetPreparerTest.class,
    DeviceFlashPreparerTest.class,
    DeviceOwnerTargetPreparerTest.class,
    DeviceSetupTest.class,
    DeviceStorageFillerTest.class,
    DeviceStringPusherTest.class,
    DisableSELinuxTargetPreparerTest.class,
    DynamicSystemPreparerTest.class,
    FastbootCommandPreparerTest.class,
    FastbootDeviceFlasherTest.class,
    FeatureFlagTargetPreparerTest.class,
    FlashingResourcesParserTest.class,
    GkiDeviceFlashPreparerTest.class,
    GsiDeviceFlashPreparerTest.class,
    InstallAllTestZipAppsSetupTest.class,
    InstallApexModuleTargetPreparerTest.class,
    InstallApkSetupTest.class,
    InstallKernelModulePreparerTest.class,
    InstrumentationPreparerTest.class,
    KnownFailurePreparerTest.class,
    LocalEmulatorLaunchTest.class,
    MixKernelTargetPreparerTest.class,
    ModuleOemTargetPreparerTest.class,
    ModulePusherTest.class,
    PushFilePreparerTest.class,
    PythonVirtualenvPreparerTest.class,
    RebootTargetPreparerTest.class,
    RestartSystemServerTargetPreparerTest.class,
    RootTargetPreparerTest.class,
    RunCommandTargetPreparerTest.class,
    RunHostCommandTargetPreparerTest.class,
    RunHostScriptTargetPreparerTest.class,
    RunOnCloneProfileTargetPreparerTest.class,
    RunOnPrivateProfileTargetPreparerTest.class,
    RunOnSdkSandboxTargetPreparerTest.class,
    RunOnSecondaryUserTargetPreparerTest.class,
    RunOnSystemUserTargetPreparerTest.class,
    RunOnWorkProfileTargetPreparerTest.class,
    StopServicesSetupTest.class,
    SystemUpdaterDeviceFlasherTest.class,
    TargetSetupErrorTest.class,
    TemperatureThrottlingWaiterTest.class,
    TestAppInstallSetupTest.class,
    TestFilePushSetupTest.class,
    SwitchUserTargetPreparerTest.class,
    UserCleanerTest.class,
    VisibleBackgroundUserPreparerTest.class,
    OtaUpdateDeviceFlasherTest.class,

    // targetprep.adb
    AdbStopServerPreparerTest.class,

    // targetprep.app
    NoApkTestSkipperTest.class,

    // targetprep.incremental
    ApkChangeDetectorTest.class,

    // targetprep.multi
    MergeMultiBuildTargetPreparerTest.class,
    MixImageZipPreparerTest.class,
    PairingMultiTargetPreparerTest.class,

    // targetprep.suite
    SuiteApkInstallerTest.class,

    // sandbox
    SandboxConfigDumpTest.class,
    SandboxConfigUtilTest.class,
    SandboxedInvocationExecutionTest.class,
    SandboxInvocationRunnerTest.class,
    TradefedSandboxTest.class,

    // service.management
    DeviceManagementGrpcServerTest.class,
    TestInvocationManagementServerTest.class,

    // suite/checker
    ActivityStatusCheckerTest.class,
    DeviceBaselineCheckerTest.class,
    DeviceSettingCheckerTest.class,
    DeviceStorageStatusCheckerTest.class,
    EnforcedSeLinuxCheckerTest.class,
    FilesystemRootCheckerTest.class,
    KeyguardStatusCheckerTest.class,
    LeakedThreadStatusCheckerTest.class,
    ShellStatusCheckerTest.class,
    SystemServerFileDescriptorCheckerTest.class,
    SystemServerStatusCheckerTest.class,
    TimeStatusCheckerTest.class,
    UserCheckerTest.class,

    // suite/checker/baseline
    CommandBaselineSetterTest.class,
    DeviceBaselineSetterTest.class,
    LockSettingsBaselineSetterTest.class,
    SettingsBaselineSetterTest.class,

    // testtype
    AndroidJUnitTestTest.class,
    ArtGTestTest.class,
    ArtRunTestTest.class,
    CoverageMeasurementForwarderTest.class,
    DeviceBatteryLevelCheckerTest.class,
    DeviceJUnit4ClassRunnerTest.class,
    DeviceSuiteTest.class,
    DeviceTestCaseTest.class,
    DeviceTestSuiteTest.class,
    FakeTestTest.class,
    GoogleBenchmarkResultParserTest.class,
    GoogleBenchmarkTestTest.class,
    GTestBaseTest.class,
    GTestListTestParserTest.class,
    GTestListenerTest.class,
    GTestResultParserTest.class,
    GTestTest.class,
    GTestXmlResultParserTest.class,
    HostGTestTest.class,
    HostTestTest.class,
    InstalledInstrumentationsTestTest.class,
    InstrumentationFileTestTest.class,
    InstrumentationTestTest.class,
    IsolatedHostTestTest.class,
    JarHostTestTest.class,
    NativeBenchmarkTestParserTest.class,
    NativeBenchmarkTestTest.class,
    NativeStressTestParserTest.class,
    NativeStressTestTest.class,
    NoisyDryRunTestTest.class,
    PythonUnitTestResultParserTest.class,
    PythonUnitTestRunnerTest.class,
    TestTimeoutEnforcerTest.class,
    TfTestLauncherTest.class,
    UsbResetTestTest.class,

    // testtype/binary
    ExecutableHostTestTest.class,
    ExecutableTargetTestTest.class,
    KernelTargetTestTest.class,
    KTapResultParserTest.class,
    KUnitModuleTestTest.class,

    // testtype/junit4
    BaseHostJUnit4TestTest.class,
    DeviceParameterizedRunnerTest.class,
    JUnit4ResultForwarderTest.class,

    // testtype/mobly
    MoblyBinaryHostTestTest.class,
    MoblyYamlResultParserTest.class,

    // testtype/python
    PythonBinaryHostTestTest.class,

    // testtype/rust
    RustBinaryHostTestTest.class,
    RustBinaryTestTest.class,
    RustTestBaseTest.class,
    RustTestResultParserTest.class,

    // testtype/suite
    AtestRunnerTest.class,
    BaseTestSuiteTest.class,
    GranularRetriableTestWrapperTest.class,
    ITestSuiteIntegrationTest.class,
    ITestSuiteMultiTest.class,
    ITestSuiteTest.class,
    ModuleDefinitionMultiTest.class,
    ModuleDefinitionTest.class,
    ModuleListenerTest.class,
    ModuleMergerTest.class,
    ModuleSplitterTest.class,
    RemoteTestTimeOutEnforcerTest.class,
    ResolvePartialDownloadTest.class,
    SuiteModuleLoaderTest.class,
    SuiteTestFilterTest.class,
    TestMappingSuiteRunnerTest.class,
    TestSuiteInfoTest.class,
    TfSuiteRunnerTest.class,
    ValidateSuiteConfigHelperTest.class,

    // testtype/suite/module
    ArchModuleControllerTest.class,
    BaseModuleControllerTest.class,
    CarModuleControllerTest.class,
    DeviceFeatureModuleControllerTest.class,
    KernelTestModuleControllerTest.class,
    MaxSdkModuleControllerTest.class,
    MinApiLevelModuleControllerTest.class,
    NativeBridgeModuleControllerTest.class,
    PackageInstalledModuleControllerTest.class,
    ShippingApiLevelModuleControllerTest.class,

    // testtype/suite/params
    InstantAppHandlerTest.class,
    MainlineModuleHandlerTest.class,
    ModuleParametersHelperTest.class,
    RunOnSdkSandboxHandlerTest.class,
    RunOnSecondaryUserParameterHandlerTest.class,
    RunOnWorkProfileParameterHandlerTest.class,
    SecondaryUserHandlerTest.class,
    SecondaryUserOnSecondaryDisplayHandlerTest.class,
    RunOnCloneProfileParameterHandlerTest.class,
    RunOnPrivateProfileParameterHandlerTest.class,

    // testtype/suite/retry
    ResultsPlayerTest.class,
    RetryReschedulerTest.class,

    // util
    AaptParserTest.class,
    AbiFormatterTest.class,
    AbiUtilsTest.class,
    AdbRootElevatorTest.class,
    AppVersionFetcherTest.class,
    ArrayUtilTest.class,
    BluetoothUtilsTest.class,
    BugreportTest.class,
    BuildTestsZipUtilsTest.class,
    BundletoolUtilTest.class,
    ByteArrayListTest.class,
    CentralDirectoryInfoTest.class,
    ClangProfileIndexerTest.class,
    ClassPathScannerTest.class,
    ConditionPriorityBlockingQueueTest.class,
    DeviceActionUtilTest.class,
    DirectedGraphTest.class,
    EmailTest.class,
    FakeTestsZipFolderTest.class,
    FileIdleMonitorTest.class,
    FileUtilTest.class,
    FixedByteArrayOutputStreamTest.class,
    FuseUtilTest.class,
    GceRemoteCmdFormatterTest.class,
    GCSFileDownloaderTest.class,
    GoogleApiClientUtilTest.class,
    HprofAllocSiteParserTest.class,
    JavaCodeCoverageFlusherTest.class,
    JUnitXmlParserTest.class,
    KernelModuleUtilsTest.class,
    KeyguardControllerStateTest.class,
    LegacySubprocessResultsReporterTest.class,
    ListInstrumentationParserTest.class,
    LogcatEventParserTest.class,
    MetricUtilityTest.class,
    ModuleTestTypeUtilTest.class,
    MultiMapTest.class,
    MergedZipEntryCollectionTest.class,
    NativeCodeCoverageFlusherTest.class,
    PairTest.class,
    ProtoUtilTest.class,
    PsParserTest.class,
    PythonVirtualenvHelperTest.class,
    QuotationAwareTokenizerTest.class,
    RegexTrieTest.class,
    RemoteZipTest.class,
    ResourceUtilTest.class,
    RestApiHelperTest.class,
    RequestUtilTest.class,
    RunUtilTest.class,
    SearchArtifactUtilTest.class,
    SerializationUtilTest.class,
    ShellOutputReceiverStreamTest.class,
    SimplePerfStatResultParserTest.class,
    SimplePerfUtilTest.class,
    SimpleStatsTest.class,
    SizeLimitedOutputStreamTest.class,
    Sl4aBluetoothUtilTest.class,
    SparseImageUtilTest.class,
    StreamUtilTest.class,
    StringEscapeUtilsTest.class,
    StringUtilTest.class,
    SubprocessTestResultsParserTest.class,
    TableBuilderTest.class,
    TableFormatterTest.class,
    TarUtilTest.class,
    TestRunnerUtilTest.class,
    TfTestLauncherTest.class,
    TimeUtilTest.class,
    TimeValTest.class,
    VersionParserTest.class,
    ZipUtilTest.class,
    ZipUtil2Test.class,

    // util/executor
    ParallelDeviceExecutorTest.class,

    // util/hostmetric
    AbstractHostMonitorTest.class,
    HeapHostMonitorTest.class,

    // util/image
    DeviceImageTrackerTest.class,

    // util/keystore
    JSONFileKeyStoreClientTest.class,
    JSONFileKeyStoreFactoryTest.class,

    // util/net
    HttpHelperTest.class,
    HttpMultipartPostTest.class,

    // util/proto
    TestRecordProtoUtilTest.class,
    TfMetricProtoUtilTest.class,

    // util/sl4a
    Sl4aClientTest.class,
    Sl4aEventDispatcherTest.class,

    // util/statsd
    ConfigUtilTest.class,
    MetricUtilTest.class,

    // util/testmapping
    TestInfoTest.class,
    TestMappingTest.class,

    // monitoring
    LabResourceDeviceMonitorTest.class,
    // monitoring/collector
    DeviceBatteryResourceMetricCollectorTest.class,
    DeviceInternetAccessibilityResourceMetricCollectorTest.class,
    DeviceStorageAgeResourceMetricCollectorTest.class,
    DeviceWifiResourceMetricCollectorTest.class,
    ResourceMetricUtilTest.class
})
public class UnitTests {
    // empty of purpose
}
