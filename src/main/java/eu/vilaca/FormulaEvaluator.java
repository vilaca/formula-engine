package eu.vilaca;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

import static java.util.Objects.*;

/**
 * Represents a formula which can compute a double value
 * based on a provided {@link FormulaEvaluationContext}.
 */
class Formula {
	private final Function<FormulaEvaluationContext, Double> computation;

	public Formula(Function<FormulaEvaluationContext, Double> computation) {
		this.computation = computation;
	}

	double compute(FormulaEvaluationContext formulaEvaluationContext) {
		return computation.apply(formulaEvaluationContext);
	}
}

class FormulaEvaluationContext {

	private final StateRegistry state = new StateRegistry();
	private final ThreadLocal<Deque<String>> evaluationStack = ThreadLocal.withInitial(ArrayDeque::new);
	private final ConcurrentHashMap<String, FutureTask<Double>> cachedResults = new ConcurrentHashMap<>();

	private FormulaEvaluationContext() {
	}

	public static FormulaEvaluationContext create() {
		return new FormulaEvaluationContext();
	}

	public void createFormula(String formulaName, Function<FormulaEvaluationContext, Double> computation) {
		state.register(formulaName, new Formula(computation));
	}

	public void createConstant(String name, double value) {
		state.register(name, value);
	}

	public Deque<String> getEvaluationStack() {
		return new ArrayDeque<>(evaluationStack.get());
	}

	public void clear() {
		state.clear();
		cachedResults.clear();
		evaluationStack.get().clear(); // Clear this thread’s evaluation stack
	}

	/**
	 * Retrieves the value associated with the given name.
	 * If it is a constant, returns the constant value.
	 * If it is a formula, computes and caches the result.
	 * Detects cyclic dependencies during evaluation and throws if found.
	 *
	 * @param name the name of the formula or constant
	 * @return the computed or constant double value
	 * @throws IllegalArgumentException if the name is unknown or if a cycle is detected
	 * @throws RuntimeException         if the computation is interrupted or fails unexpectedly
	 */
	public double get(String name) {

		final var constant = state.lookupConstant(name);
		if (nonNull(constant)) {
			return constant;
		}
		final var stack = evaluationStack.get();
		if (stack.contains(name)) {
			throw new FormulaEvaluationException("Cyclic dependency detected when evaluating '" + name + "'.", stack, name);
		}
		try {
			stack.add(name);
			final var task = cachedResults.computeIfAbsent(
					name,
					key -> new FutureTask<>(() -> {
						final var formula = state.lookupFormula(key);
						if (isNull(formula)) {
							throw new FormulaEvaluationException("Unknown constant or formula: " + key + ".", stack);
						}
						return formula.compute(this);
					}));
			if (!task.isDone()) {
				task.run();
			}
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FormulaEvaluationException("Computation interrupted", stack, e.getCause());
		} catch (ExecutionException e) {
			final var cause = e.getCause();
			if (cause instanceof FormulaEvaluationException fee) {
				throw new FormulaEvaluationException(fee.getMessage());
			} else if (cause instanceof RuntimeException re) {
				throw new FormulaEvaluationException(re.getMessage(), stack, re);
			} else if (cause instanceof Error err) {
				throw new FormulaEvaluationException(err.getMessage(), stack, err);
			} else {
				throw new FormulaEvaluationException("Unexpected exception during computation", stack, cause);
			}
		} finally {
			stack.remove(name);
		}
	}
}

class StateRegistry {

	private final Map<String, Formula> formulas = new ConcurrentHashMap<>();
	private final Map<String, Double> constants = new ConcurrentHashMap<>();

	synchronized void register(String formulaName, Formula formula) {
		if (constants.containsKey(formulaName) || formulas.putIfAbsent(formulaName, formula) != null) {
			throw new IllegalArgumentException("Formula already registered: " + formulaName);
		}
	}

	synchronized void register(String constantName, double value) {
		if (formulas.containsKey(constantName) || constants.putIfAbsent(constantName, value) != null) {
			throw new IllegalArgumentException("Constant already registered: " + constantName);
		}
	}

	Formula lookupFormula(String formulaName) {
		return formulas.get(formulaName);
	}

	Double lookupConstant(String constantName) {
		return constants.get(constantName);
	}

	void clear() {
		formulas.clear();
		constants.clear();
	}
}

class FormulaEvaluationException extends RuntimeException {

	public FormulaEvaluationException(String message, Deque<String> stack, String last) {
		super(message + " Path:" + String.join(" -> ", stack) + " -> " + last + ".");
	}

	public FormulaEvaluationException(String message, Deque<String> stack, Throwable e) {
		super(message + " Path:" + String.join(" -> ", stack) + ".", e);
	}

	public FormulaEvaluationException(String message, Deque<String> stack) {
		super(message + " Path:" + String.join(" -> ", stack) + ".");
	}

	public FormulaEvaluationException(String message) {
		super(message);
	}
}