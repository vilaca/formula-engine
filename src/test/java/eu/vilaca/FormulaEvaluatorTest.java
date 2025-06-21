package eu.vilaca;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FormulaEvaluatorTest {

	@Test
	void testConstantEvaluation() {
		final var context = FormulaEvaluationContext.create();
		context.createConstant("@pi", Math.PI);
		double result = context.get("@pi");
		assertEquals(Math.PI, result);
	}

	@Test
	void testSimpleFormulaEvaluation() {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@two", 2);
		context.createFormula("@doubleTwo", ctx -> ctx.get("@two") * 2);

		double result = context.get("@doubleTwo");
		assertEquals(4, result);
	}

	@Test
	void testNestedFormulaEvaluation() {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@pi", Math.PI);
		context.createConstant("@four", 4);
		context.createFormula("@quarterPi", ctx -> ctx.get("@pi") / ctx.get("@four"));

		double result = context.get("@quarterPi");
		assertEquals(Math.PI / 4, result);
	}

	@Test
	void testCyclicDependencyDetection() {
		final var context = FormulaEvaluationContext.create();

		context.createFormula("@a", ctx -> ctx.get("@b") + 1);
		context.createFormula("@b", ctx -> ctx.get("@a") + 1);

		FormulaEvaluationException ex = assertThrows(FormulaEvaluationException.class, () -> context.get("@a"));

		assertTrue(ex.getMessage().contains("Cyclic dependency detected"));
	}

	@Test
	void testUnknownFormulaThrowsException() {
		final var context = FormulaEvaluationContext.create();
		var ex = assertThrows(FormulaEvaluationException.class, () -> context.get("@unknown"));
		assertTrue(ex.getMessage().contains("Unknown constant or formula"));
	}

	@Test
	void testCachingBehavior() {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@five", 5);
		final int[] computeCount = {0};

		context.createFormula("@incrementFive", ctx -> {
			computeCount[0]++;
			return ctx.get("@five") + 1;
		});

		double result1 = context.get("@incrementFive");
		double result2 = context.get("@incrementFive");

		assertEquals(6, result1);
		assertEquals(6, result2);
		assertEquals(1, computeCount[0], "Formula should be computed only once due to caching");
	}

	@Test
	void testDeepFormulaDependency() {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@one", 1);
		context.createFormula("@two", ctx -> ctx.get("@one") + 1);
		context.createFormula("@three", ctx -> ctx.get("@two") + 1);
		context.createFormula("@four", ctx -> ctx.get("@three") + 1);

		double result = context.get("@four");
		assertEquals(4, result);
	}

	@Test
	void testLargeDependencyGraph() {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@base", 1);

		// Create 100 dependent formulas: @f1 = @base + 1, @f2 = @f1 + 1, ..., @f100 = @f99 + 1
		for (int i = 1; i <= 100; i++) {
			String dependency = (i == 1) ? "@base" : "@f" + (i - 1);
			context.createFormula("@f" + i, ctx -> ctx.get(dependency) + 1);
		}

		double result = context.get("@f100");
		assertEquals(101, result, "Last formula should evaluate to 101");
	}

	@Test
	void testCycleErrorMessageContainsPath() {
		final var context = FormulaEvaluationContext.create();

		context.createFormula("@x", ctx -> ctx.get("@y") + 1);
		context.createFormula("@y", ctx -> ctx.get("@x") + 1);

		var ex = assertThrows(FormulaEvaluationException.class, () -> context.get("@x"));

		String message = ex.getMessage();
		assertTrue(message.contains("@x -> @y -> @x"), "Cycle path should appear in exception message");
	}

	@Test
	void testClearCache() {
		final var context = FormulaEvaluationContext.create();

		AtomicInteger computeCount = new AtomicInteger(0);
		context.createFormula("@recompute", x -> {
			computeCount.incrementAndGet();
			return 99.0;
		});

		assertEquals(99.0, context.get("@recompute"));
		context.clear();  // Clear cached results
		assertThrows(FormulaEvaluationException.class, () -> context.get("@recompute"));
		assertEquals(1, computeCount.get(), "Should recompute after cache is cleared");
	}

	@Test
	void testThreadLocalIsolation() throws InterruptedException, ExecutionException {
		final var context = FormulaEvaluationContext.create();

		// Prepare formulas/constants in main thread (shared registry)
		context.createConstant("@base", 100);
		context.createFormula("@addOne", ctx -> ctx.get("@base") + 1);
		context.createFormula("@addTwo", ctx -> ctx.get("@addOne") + 1);

		Callable<Double> task1 = () -> context.get("@addOne"); // should be 101
		Callable<Double> task2 = () -> context.get("@addTwo"); // should be 102

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<Double> future1 = executor.submit(task1);
		Future<Double> future2 = executor.submit(task2);

		assertEquals(101, future1.get());
		assertEquals(102, future2.get());

		executor.shutdown();
	}

	@Test
	void testNoFalseCycleDetectionAcrossThreads() throws InterruptedException, ExecutionException {
		final var context = FormulaEvaluationContext.create();

		context.createFormula("@x", ctx -> ctx.get("@y") + 1);
		context.createFormula("@y", ctx -> ctx.get("@x") + 1);

		Callable<FormulaEvaluationException> task = () -> {
			try {
				context.get("@x");
				return null; // Shouldn't reach
			} catch (FormulaEvaluationException ex) {
				return ex;
			}
		};

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<FormulaEvaluationException> future1 = executor.submit(task);
		Future<FormulaEvaluationException> future2 = executor.submit(task);

		FormulaEvaluationException ex1 = future1.get();
		FormulaEvaluationException ex2 = future2.get();

		assertNotNull(ex1);
		assertNotNull(ex2);
		assertTrue(ex1.getMessage().contains("@x"));
		assertTrue(ex2.getMessage().contains("@x"));

		executor.shutdown();
	}

	@Test
	void testConcurrentCacheUsage() throws InterruptedException, ExecutionException {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@const", 5);
		final int[] computeCount = {0};

		context.createFormula("@square", ctx -> {
			synchronized (computeCount) {
				computeCount[0]++;
			}
			return ctx.get("@const") * ctx.get("@const");
		});

		Callable<Double> task = () -> context.get("@square");

		ExecutorService executor = Executors.newFixedThreadPool(10);
		@SuppressWarnings("unchecked")
		Future<Double>[] futures = (Future<Double>[]) new Future<?>[10];

		for (int i = 0; i < 10; i++) {
			futures[i] = executor.submit(task);
		}

		for (Future<Double> future : futures) {
			assertEquals(25, future.get());
		}

		executor.shutdown();

		// Should have computed only once due to caching
		assertEquals(1, computeCount[0], "Formula should have been computed only once due to caching");
	}

	@Test
	void testStressEvaluation_ManyThreads() throws Exception {

		final var context = FormulaEvaluationContext.create();

		context.createConstant("@pi", Math.PI);

		ExecutorService executor = Executors.newFixedThreadPool(10);
		Callable<Double> task = () -> context.get("@pi");

		int threadCount = 100;
		List<Future<Double>> results = executor.invokeAll(
				java.util.Collections.nCopies(threadCount, task)
		);

		for (Future<Double> future : results) {
			assertEquals(Math.PI, future.get(), "All threads should get the same constant");
		}

		executor.shutdown();
	}

	@Test
	void testDuplicateConstantRegistrationThrows() {
		final var context = FormulaEvaluationContext.create();
		context.createConstant("@const", 1.0);
		IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> context.createConstant("@const", 2.0));
		assertTrue(ex.getMessage().contains("Constant already registered"));
	}

	@Test
	void testDuplicateFormulaRegistrationThrows() {
		final var context = FormulaEvaluationContext.create();
		context.createFormula("@formula", ctx -> 1.0);
		IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> context.createFormula("@formula", ctx -> 2.0));
		assertTrue(ex.getMessage().contains("Formula already registered"));
	}

	@Test
	void testLookupUnregisteredFormulaReturnsNull() {
		final var ctx = FormulaEvaluationContext.create();
		assertThrows(FormulaEvaluationException.class, () -> ctx.get("@nonexistent"));
	}

	@Test
	void testThreadLocalStackIsolation() throws Exception {
		final var context = FormulaEvaluationContext.create();
		context.createConstant("@val", 1);
		context.createFormula("@inc", ctx -> ctx.get("@val") + 1);

		Callable<Deque<String>> task = () -> {
			context.get("@inc");
			return context.getEvaluationStack();
		};

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<Deque<String>> future1 = executor.submit(task);
		Future<Deque<String>> future2 = executor.submit(task);

		Deque<String> stack1 = future1.get();
		Deque<String> stack2 = future2.get();

		// Even though ThreadLocal, after computation the stack must be cleared
		assertTrue(stack1.isEmpty(), "Thread 1 evaluation stack should be empty after evaluation");
		assertTrue(stack2.isEmpty(), "Thread 2 evaluation stack should be empty after evaluation");

		executor.shutdown();
	}

	@Test
	void testNoContentionDuringEvaluation() throws InterruptedException {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@value", 42);
		context.createFormula("@double", ctx -> ctx.get("@value") * 2);

		Runnable task = () -> {
			for (int i = 0; i < 1000; i++) {
				assertEquals(84, context.get("@double"));
			}
		};

		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		// Start contention monitoring
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		bean.setThreadContentionMonitoringEnabled(true);

		long startTime = System.nanoTime();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(task);
		}

		executor.shutdown();
		assert executor.awaitTermination(10, TimeUnit.SECONDS);

		long endTime = System.nanoTime();
		long durationMs = (endTime - startTime) / 1_000_000;

		System.out.println("Execution time: " + durationMs + " ms");

		// Check for threads with contention or excessive blocking
		long[] threadIds = bean.getAllThreadIds();
		ThreadInfo[] infos = bean.getThreadInfo(threadIds, true, true);

		for (ThreadInfo info : infos) {
			if (info == null || !info.getThreadName().startsWith("pool-")) {
				continue;
			}
			if (info.getThreadState() == Thread.State.BLOCKED) {
				System.err.println("Blocked thread detected: " + info.getThreadName());
			}
			if (info.getBlockedCount() > 0) {
				System.err.println("Thread " + info.getThreadName() +
						" was blocked " + info.getBlockedCount() + " times.");
				assertEquals(0, info.getBlockedCount(), "Thread was unexpectedly blocked: " + info.getThreadName());
			}
		}
	}

	@Test
	void testConcurrentRandomAccess() throws InterruptedException {
		final var ctx = FormulaEvaluationContext.create();

		for (int i = 0; i < 50; i++) {
			ctx.createConstant("@const" + i, i);
		}

		ExecutorService executor = Executors.newFixedThreadPool(8);
		Runnable task = () -> {
			for (int i = 0; i < 1000; i++) {
				String key = "@const" + (i % 50);
				assertEquals(i % 50, ctx.get(key));
			}
		};

		for (int i = 0; i < 8; i++) {
			executor.submit(task);
		}

		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	@Test
	void testRaceConditionDuringConcurrentEvaluation() throws InterruptedException, ExecutionException {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@const", 7);
		AtomicInteger computeCount = new AtomicInteger(0);

		context.createFormula("@raceTest", ctx -> {
			try {
				Thread.sleep(2); // Force interleaving
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			computeCount.incrementAndGet();
			return ctx.get("@const") * 3;
		});

		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Future<?>> futures = new java.util.ArrayList<>();

		Runnable task = () -> {
			for (int i = 0; i < 100; i++) {
				//ctx.clear();  // Force recomputation
				double result = context.get("@raceTest");
				assertEquals(21, result);
			}
		};

		// Submit tasks
		for (int i = 0; i < 8; i++) {
			futures.add(executor.submit(task));
		}

		// Wait for all tasks to complete
		for (Future<?> future : futures) {
			future.get(); // Will throw if any exceptions in threads
		}

		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

		// Now guaranteed that all tasks finished
		assertEquals(1, computeCount.get(), "Expected 1, got " + computeCount.get());
	}

	@Test
	void testNoSharedStateBetweenThreads() throws Exception {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@val", 3);
		AtomicInteger sharedCounter = new AtomicInteger(0);

		context.createFormula("@test", ctx -> {
			int current = sharedCounter.incrementAndGet();
			// Simulate long compute
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return ctx.get("@val") + current;
		});

		ExecutorService executor = Executors.newFixedThreadPool(4);
		Callable<Double> task = () -> context.get("@test");

		List<Future<Double>> results = executor.invokeAll(
				java.util.Collections.nCopies(4, task)
		);

		for (Future<Double> future : results) {
			double val = future.get();
			// Should be between 4 and 7 because 4 threads incremented the sharedCounter
			assertTrue(val >= 4 && val <= 7, "Unexpected result: " + val);
		}

		executor.shutdown();
	}

	@Test
	void testHeavyLoadContentionDetection() throws Exception {
		final var context = FormulaEvaluationContext.create();

		context.createConstant("@num", 42);
		context.createFormula("@compute", ctx -> ctx.get("@num") * 2);

		ExecutorService executor = Executors.newFixedThreadPool(16);
		Callable<Double> task = () -> {
			for (int i = 0; i < 500; i++) {
				double result = context.get("@compute");
				assertEquals(84, result);
			}
			return 84.0;
		};

		List<Future<Double>> futures = executor.invokeAll(Collections.nCopies(16, task));

		for (Future<Double> future : futures) {
			assertEquals(84.0, future.get());
		}

		executor.shutdown();
		assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
	}

	@Test
	void testUnknownFormulaIsNotCached() {
		final var context = FormulaEvaluationContext.create();

		// First access: should throw and NOT cache
		assertThrows(FormulaEvaluationException.class, () -> context.get("@unknown"));

		// Confirm: cachedResults should NOT contain "@unknown"
		var evaluationStack = context.getEvaluationStack();
		assertFalse(evaluationStack.contains("@unknown"), "Unknown formula was unexpectedly cached in evaluation stack.");

		// Attempt again: should throw again, meaning it's not cached
		assertThrows(FormulaEvaluationException.class, () -> context.get("@unknown"));
	}

	@Test
	void testFormulaDependingOnUnknownConstantThrows() {
		final var context = FormulaEvaluationContext.create();
		context.createFormula("@foo", c -> c.get("@missing"));
		var ex = assertThrows(FormulaEvaluationException.class, () -> context.get("@foo"));
		assertTrue(ex.getMessage().contains("Unknown"), "Exception should mention unknown constant or formula");
	}
}
