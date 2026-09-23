Build errors for commit e799b20
Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException: Compilation error. See log for more details
Execution failed for task ':app:compileDebugKotlin'.
FAILURE: Build failed with an exception.
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/ui/EditorViewModel.kt:674:5 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/ui/EditorViewModel.kt:675:1 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:100 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:103 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:106 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:107 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:115 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:116 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:120 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:23 Expecting context receivers
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:24 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:39 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:40 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:46 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:47 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:57 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:58 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:63 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:64 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:69 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:70 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:9 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:90 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:92 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:262:98 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:263:5 Expecting a top level declaration
e: file:///home/runner/work/Longtoshortclipper/Longtoshortclipper/app/src/main/java/com/shortsclipper/video/ExportManager.kt:264:1 Expecting a top level declaration
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:compileDebugKotlin'.

=== failing unit tests ===

=== last 30 log lines ===
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:62)
	at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:100)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:62)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:200)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:195)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:157)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.internal.operations.DefaultBuildOperationExecutor.call(DefaultBuildOperationExecutor.java:73)
	at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:59)
	at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:174)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:187)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:120)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:162)
	at org.gradle.internal.Factories$1.create(Factories.java:31)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:264)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:128)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:133)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:157)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:126)
	... 2 more


BUILD FAILED in 2m 28s
23 actionable tasks: 23 executed
