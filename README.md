# FormulaEngine

A Java lightweight, thread-safe formula evaluation engine for defining and computing constants and formulas with dependency resolution, caching, and cycle detection.

## Features

- ✅ **Define constants and formulas**
- ✅ **Evaluate formulas with dependency resolution**
- ✅ **Automatic caching for computed values**
- ✅ **Cycle detection to prevent infinite loops**
- ✅ **Thread-safe and concurrency-friendly**
- ✅ **Clear evaluation context separation**
- ✅ **Less than 200 lines of code, single java source file**

## Clone and build
```
git clone https://github.com/yourname/formula-engine.git
cd formula-engine
./gradlew build
```

## Usage example

```
FormulaEvaluationContext context = FormulaEvaluationContext.create();

context.createConstant("PI", Math.PI);

context.createFormula("circle_area", ctx -> {
    double r = 5.0;
    return ctx.get("PI") * r * r;
});

double area = context.get("circle_area");
System.out.println("Circle area: " + area);
```



