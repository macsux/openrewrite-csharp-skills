# Authoring Concise C# Recipes with `CSharpPattern`, `CSharpTemplate`, and `Capture`

A practical guide to writing OpenRewrite C# recipes using the structural-pattern API in
`OpenRewrite.CSharp.Template`. The goal is the same as JavaScript's tagged template
literals: describe the *before* and *after* shape of code as interpolated strings, let the
SDK do the matching/templating/formatting, and only drop into imperative visitor code when
you actually need to.

> Namespace: `OpenRewrite.CSharp.Template`
> Source (SDK): `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/`
> Recipe file references in this guide are relative to the `recipes-csharp` repo root.

---

## 1. Mental model

Three small types do the heavy lifting:

| Type | Role |
|---|---|
| `Capture<T>` (built via `Capture.Expression`, `Capture.Name`, `Capture.Type`, `Capture.Of<T>`) | A typed *hole* in a pattern or template. In a pattern it becomes a wildcard that binds a subtree; in a template it becomes a substitution point. |
| `CSharpPattern` | A parsed code snippet whose holes are wildcards. Matches it against an AST node and returns a `MatchResult`. |
| `CSharpTemplate` | A parsed code snippet whose holes get filled in. Applied at a cursor with a `MatchResult` and produces a new AST node. |

A `CSharpTemplate.Rewrite(pattern, template)` call returns a ready-to-use `CSharpVisitor`
that runs `pattern.Match` against every node in `PostVisit`, applies the template on
match, and runs `AutoFormat` on the result. This single call replaces what used to be a
30-line imperative `Visit*` override.

The same interpolated-string syntax is used for both pattern and template — captures
declared once can appear in both:

```csharp
var s = Capture.Expression("s", type: "string");

return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"{s} == null || {s} == \"\""),
    CSharpTemplate.Expression($"string.IsNullOrEmpty({s})"));
```

How the interpolation works (you mostly don't need to think about this): each
`{capture}` hole is intercepted by `TemplateStringHandler` at the call site. The
`Capture<T>` is registered under its `Name`, and the placeholder `__plh_<name>__` is
substituted into the template string so it parses as a valid C# identifier. At match
time, every occurrence of `__plh_<name>__` becomes a wildcard; at apply time, every
occurrence is replaced with the bound value.

---

## 2. Choosing a scaffold: `Expression`, `Statement`, `ClassMember`, `Attribute`

Both `CSharpPattern` and `CSharpTemplate` have four position-specific factories. Pick the
one matching the **syntactic position** of the code you are matching or producing —
Roslyn parses the snippet inside a scaffold of that kind, so the type attribution and the
returned node both come out right.

| Factory | Scaffold | Returned node kind | When to use |
|---|---|---|---|
| `.Expression($"…")` | `class __T__ { object __v__ = <code>; }` | `Expression` | Anything that produces a value: `x.Foo()`, `a + b`, `"".Empty`, `new Foo()`. **The default 90 % of the time.** |
| `.Statement($"…")` | `class __T__ { void __M__() { <code>; } }` | `Statement` | A full statement: `var x = 42;`, `throw new …;`, an `if`/`for`/`while`. Use when you want to keep the `ExpressionStatement` wrapper rather than have it unwrapped. |
| `.ClassMember($"…")` | `class __T__ { <code> }` | First member (method, field, property, etc.) | Adding methods, fields, or properties to a class. |
| `.Attribute($"…")` | `class __T__ { [<code>] void __M__() {} }` | `Annotation` | Producing or matching a C# attribute like `[Test]`, `[Timeout(5000)]`. |

> There is also a deprecated `.Create($"…")` overload that auto-detects the scaffold.
> Don't use it — pick an explicit factory so the parser sees the right syntactic context.

Example (attribute scaffold):

```csharp
// OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/FactToTest.cs
return CSharpTemplate.Rewrite(
    CSharpPattern.Attribute($"Fact({args})",
        usings: ["Xunit"],
        dependencies: new Dictionary<string,string> { ["xunit"] = "2.9.3" }),
    CSharpTemplate.Attribute($"Test({args})",
        usings: ["TUnit.Core"],
        dependencies: new Dictionary<string,string> { ["TUnit"] = "1.19.74" }));
```

Example (statement scaffold) — note the `;` is part of the snippet because a statement
ends with one:

```csharp
// SDK test: OpenRewrite.Tests/Template/RewriteRuleTests.cs
var tmpl = CSharpTemplate.Statement($"Console.WriteLine({expr});\nreturn {expr};");
```

Example (class member scaffold):

```csharp
var tmpl = CSharpTemplate.ClassMember("public string Name { get; set; }");
```

---

## 3. Captures: the wildcards

Captures are how you say "match anything here." A capture is a typed placeholder; in the
template it becomes a substitution point. There are four factories:

### `Capture.Expression(name?, type?, typeParameters?, constraint?, variadic?)`

The default. Matches anything assignable to `Expression`. Optional knobs:

- **`name`** — used as the key when retrieving with `match.Get<T>(name)`. If omitted, an
  auto-generated name like `_capture_42` is assigned (so identity-by-object via
  `MatchResult.Of((capture, value), …)` still works).
- **`type`** — restrict by semantic type. The template engine writes a typed field in the
  scaffold preamble so Roslyn attributes the placeholder, and at match time the
  candidate's `Expression.Type` is checked with `TypeUtils.IsAssignableTo`. Use C#
  aliases (`"string"`, `"int"`, `"bool"`) or fully-qualified names (`"System.Guid"`,
  `"System.Collections.Generic.IEnumerable<T>"`).
- **`typeParameters`** — for generic type constraints. Each entry is a bare name or
  `"Name : Constraint1, Constraint2"` (C# where-clause syntax). The names declared here
  are introduced as type parameters on the scaffold class so the captured `type` can
  reference them.
- **`constraint`** — a `Func<T, CaptureConstraintContext, bool>` for arbitrary checks.
- **`variadic`** — see § 4.

### `Capture.Name(name?, constraint?, variadic?)`

Matches an `Identifier` (a name in a name-position, like a lambda parameter). No type
attribution; substitution is pure identifier replacement.

```csharp
// OpenRewrite.Recipes.CSharp.Migration.Dotnet/Net6/UseLinqDistinctBy.cs
var target   = Capture.Expression(type: "IEnumerable<T>", typeParameters: ["T"]);
var selector = Capture.Expression();
var g        = Capture.Name();   // identifier — used twice below

return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"{target}.GroupBy({selector}).Select({g} => {g}.First())"),
    CSharpTemplate.Expression($"{target}.DistinctBy({selector})"));
```

Using `Capture.Name` (instead of `Capture.Expression`) for the lambda parameter `g`
matters: it forces the match to require *the same identifier* in the lambda parameter
and the lambda body. An `Expression` capture there would have matched any subtree.

### `Capture.Type(name?, constraint?, variadic?)`

Matches a `NameTree` — anything in a type-position, like a generic argument or a base
type.

```csharp
// OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/MigrateXUnitAssertions.cs
var t = Capture.Type("t");

(CSharpPattern.Expression($"Assert.Throws<{t}>({action})", …),
 CSharpTemplate.Expression($"await Assert.That({action}).ThrowsExactly<{t}>()", …))
```

### `Capture.Of<T>(name?, type?, …)` where `T : J`

Generic escape hatch for AST node types without a dedicated factory — e.g.
`Capture.Of<Literal>("lit")`. The position-specific factories cover almost all real cases.

```csharp
// SDK test
var expr = Capture.Of<Expression>("expr");
```

### Type-constrained captures, in practice

The simplest and most useful constraint: "match `.ToString()` *only when the target is a
`string`*."

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Redundancy/RemoveRedundantToStringCall.cs
var s = Capture.Expression("s", type: "string");

var pat  = CSharpPattern.Expression($"{s}.ToString()");
var tmpl = CSharpTemplate.Expression($"{s}");
```

Without `type: "string"`, the recipe would strip `.ToString()` off *every* expression in
the file — disaster. With it, Roslyn-resolved type attribution constrains the match to
strings only.

Generic type parameter example:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Linq/OptimizeLinqSelectMax.cs
var x   = Capture.Expression(type: "IEnumerable<T>", typeParameters: ["T"]);
var sel = Capture.Expression();
return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"{x}.Select({sel}).Max()"),
    CSharpTemplate.Expression($"{x}.Max({sel})"));
```

The `typeParameters: ["T"]` declares `T` as a generic parameter on the scaffold, so
`IEnumerable<T>` resolves as a real generic constraint rather than a literal type name.

### Delegate constraints

When the syntactic shape isn't enough — you need to look at the cursor, the parent
context, or other AST properties — pass a `constraint` callback. It receives the
candidate node and a `CaptureConstraintContext`.

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/FindDoNotCompareWithNaN.cs
var t = Capture.Expression(constraint: (node, _) =>
    node is Identifier { SimpleName: "Double" or "Single" }
        or Primitive { Kind: JavaType.PrimitiveKind.Double or JavaType.PrimitiveKind.Float });

return CSharpPattern.Find(
    [
        CSharpPattern.Expression($"{x} == {t}.NaN"),
        CSharpPattern.Expression($"{x} != {t}.NaN"),
    ],
    (node, _, _) => Markup.CreateWarn(node, "Comparison with NaN always returns false/true."));
```

A heavier example, where the constraint reaches into the cursor to look at where the `!`
is being used:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Redundancy/UnnecessaryNullForgivingOperator.cs
var expr = Capture.Expression(constraint: (node, ctx) =>
    IsUnnecessaryNullForgiving(node, ctx.Cursor));

return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"{expr}!"),
    CSharpTemplate.Expression($"{expr}"));
```

### Dependent constraints

`CaptureConstraintContext` exposes `Captures` — every capture that has already been bound
*for this match attempt so far*. Use this when one capture's validity depends on
another's value. (Captures bind left-to-right in the pattern.)

```csharp
// SDK test, paraphrased
var a = Capture.Of<Expression>("a");
var b = Capture.Expression("b", constraint: (node, ctx) =>
    ctx.Captures.ContainsKey("a") && ctx.Captures["a"] is Literal { ValueSource: "1" });

// matches Math.Max(1, anything) but not Math.Max(2, anything)
var pat = CSharpPattern.Expression($"Math.Max({a}, {b})");
```

`CaptureConstraintContext` also surfaces `PatternType` — the Roslyn-resolved
`JavaType` of the pattern placeholder. The framework uses this internally so a typed
capture compares against the candidate's resolved type rather than the raw string. You
rarely need to read it yourself.

---

## 4. Variadic captures (zero or more)

A variadic capture matches *a list* of elements in a position that accepts a sequence:
argument lists, generic type argument lists, array initializers. Declared by passing
`variadic: new()` (i.e. `VariadicOptions<T>` with defaults — unbounded count, no extra
constraint).

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/FindDoNotUseSleep.cs
var args = Capture.Expression("args", variadic: new());
return CSharpPattern.Find(
    CSharpPattern.Expression($"Thread.Sleep({args})"),
    (node, _, _) => Markup.CreateWarn(node, "Avoid Thread.Sleep()."));
```

`Thread.Sleep(int)` and `Thread.Sleep(TimeSpan)` are both matched with one pattern.

### Bounds and list-level constraints

`VariadicOptions<T>` has three fields:

```csharp
public sealed record VariadicOptions<T>(
    int? Min = null,
    int? Max = null,
    Func<IReadOnlyList<T>, CaptureConstraintContext, bool>? Constraint = null
) where T : J;
```

```csharp
var args = Capture.Expression("args", variadic: new(Min: 1, Max: 3));    // 1..3 args
var two  = Capture.Expression("args", variadic: new(Constraint: (xs,_) => xs.Count == 2));
```

When you write `variadic: new()` C#'s target-typed `new` infers `VariadicOptions<Expression>`
from the `Capture.Expression(…)` return type, so you don't have to spell it out.

> A capture can have either `constraint:` *or* `variadic:` — not both. For list-level
> checks, use `VariadicOptions.Constraint`.

### Reading the variadic list

If the template just splices the variadic capture back in (`$"Foo({args})" → $"Bar({args})"`)
you don't have to do anything — substitution handles it. But if you want to *read* the
captured list (e.g. to inspect the second argument), use `GetList<T>`:

```csharp
if (pat.Match(mi, Cursor) is { } match)
{
    var argList = match.GetList<Expression>("args");
    if (argList.Count >= 2 && argList[1] is Literal { Value: 0 })
        return mi;                                          // skip when 2nd arg is 0
    return (J)tmpl.Apply(Cursor, values: match)!;
}
```

`GetList<T>` returns an empty `IReadOnlyList<T>` if the variadic capture didn't bind
(rather than null), so you can iterate without a null guard.

### Variadic rewrite that re-splices the list

The most common idiom: take a method invocation with N arguments, rewrite it as a
different invocation that forwards all N args unchanged.

```csharp
// e.g. Console.Write(...) → Console.WriteLine(...) with any argument list
var args = Capture.Expression("args", variadic: new());
return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"Console.Write({args})"),
    CSharpTemplate.Expression($"Console.WriteLine({args})"));
```

---

## 5. Patterns

### `Match` — get a `MatchResult` you can read

`CSharpPattern.Match(J tree, Cursor cursor)` returns a `MatchResult?` — non-null when the
pattern matched. Use it whenever you want to inspect what was captured before deciding
what to do (apply a template, return a warning, do nothing). The framework also exposes
declarative `Rewrite` / `Find` factories that wrap this call so you almost never need to
write it yourself — but you reach for `Match` directly when the pattern alone isn't
enough to decide.

The minimal shape:

```csharp
public override J VisitMethodInvocation(MethodInvocation mi, ExecutionContext ctx)
{
    mi = (MethodInvocation)base.VisitMethodInvocation(mi, ctx);

    if (_pattern.Match(mi, Cursor) is { } match)        // pattern matched
    {
        var arg = match.Get<Expression>("arg");          // read what got captured
        if (arg is Literal { Value: 0 })                 // extra business check
            return mi;                                   // bail out
        return (J)_template.Apply(Cursor, values: match)!;
    }
    return mi;
}
```

A full real example, where two patterns trip different rewrites against the same node:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Simplification/UseStringEquals.cs
public override J VisitBinary(Binary binary, ExecutionContext ctx)
{
    binary = (Binary)base.VisitBinary(binary, ctx);

    if (IsInsideEqualsOverride())                       // cursor-context check
        return binary;

    if (patEq.Match(binary, Cursor) is { } matchEq      // try ==
        && IsStringComparison(matchEq))                 // …with extra check on captures
        return (J)tmplEq.Apply(Cursor, values: matchEq)!;

    if (patNeq.Match(binary, Cursor) is { } matchNeq    // try !=
        && IsStringComparison(matchNeq))
        return (J)tmplNeq.Apply(Cursor, values: matchNeq)!;

    return binary;
}

private static bool IsStringComparison(MatchResult match)
{
    var l = match.Get<Expression>("lhs");
    var r = match.Get<Expression>("rhs");
    if (l is Literal { ValueSource: "null" or "\"\"" }) return false;
    if (r is Literal { ValueSource: "null" or "\"\"" }) return false;
    // … more checks …
    return true;
}
```

Note the pattern:

1. Override the most specific `Visit*` you can (`VisitBinary`, not `PostVisit`). Roslyn
   only calls it on the AST nodes that could possibly match — cheaper, and the type is
   already narrowed.
2. Call `base.Visit*` first so descendants are visited before you decide on this node.
3. `Match(node, Cursor) is { } match` — both a null check and a binding in one C#
   pattern-matching expression.
4. Read captures from `match` for further checks. Bail out by returning the original
   node if anything fails.
5. On success, hand `match` straight to `template.Apply(..., values: match)`.

### `Matches` — boolean shortcut when you don't need the captures

`pattern.Matches(tree, cursor)` is the same call but returns `bool`. Use it when the
pattern has no useful captures and you just want a "did it match?" gate:

```csharp
// OpenRewrite.Recipes.CSharp.Migration.Dotnet/Net10/FindDistributedContextPropagator.cs
public override J VisitFieldAccess(FieldAccess fa, ExecutionContext ctx)
{
    fa = (FieldAccess)base.VisitFieldAccess(fa, ctx);
    if (currentPat.Matches(fa, Cursor))                 // boolean — no captures needed
        return Markup.CreateWarn(fa, WarnMessage);
    return fa;
}

public override J VisitMethodInvocation(MethodInvocation mi, ExecutionContext ctx)
{
    mi = (MethodInvocation)base.VisitMethodInvocation(mi, ctx);
    if (createPat.Matches(mi, Cursor))
        return Markup.CreateWarn(mi, WarnMessage);
    return mi;
}
```

`Matches` is just `Match(...) != null` — pick whichever reads cleaner at the call site.

### `pattern.Find(tree, cursor, …)` — one-shot mark-if-match

A convenience wrapper around `Match` that does "if matched, run the annotator; else
return the node unchanged":

```csharp
return pat.Find(mi, Cursor, (node, _, _) => Markup.CreateWarn(node, "avoid this API"));
```

Equivalent to:

```csharp
if (pat.Match(mi, Cursor) is { } m)
    return Markup.CreateWarn(mi, "avoid this API");
return mi;
```

Reach for `pattern.Find` inside an existing visitor when you only need to mark and the
match metadata isn't relevant to the annotator's decision.

### Fast-reject and why per-node iteration is cheap

Internally, `Match` does a cheap fast-reject: if the pattern root is concrete (not a
capture placeholder) and the candidate has a different node type — and no cross-type
equivalence is known (e.g. `Binary` ↔ `IsPattern`) — it returns `null` without
allocating a comparator. That keeps `PostVisit`-on-every-node iteration cheap, which is
why the declarative `Rewrite`/`Find` factories can afford to run a pattern against every
node in the tree.

### `Find` visitors — search-only recipes

`CSharpPattern.Find(…)` returns a visitor that walks the tree and tags every match with
a marker. Use it for "find places that smell bad" recipes — no rewriting, just a warning
or a search-result marker.

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/FindDoNotRaiseNotImplementedException.cs
var args = Capture.Expression("args", variadic: new());
return CSharpPattern.Find(
    CSharpPattern.Expression($"new NotImplementedException({args})"),
    (node, _, _) => Markup.CreateWarn(node,
        "Do not throw NotImplementedException. Throw a more specific exception."));
```

Overloads:

```csharp
// 1. Single pattern, default SearchResult marker:
CSharpPattern.Find(pattern, "found it");

// 2. Single pattern, custom annotator:
CSharpPattern.Find(pattern, (node, cursor, match) => Markup.CreateWarn(node, "…"));

// 3. Multiple patterns, one shared annotator/description (first match wins):
CSharpPattern.Find([p1, p2, p3], "use IsNaN() instead");

// 4. Multiple (pattern, annotator) pairs — different messages per pattern:
CSharpPattern.Find(
    (countPredN, (node,_,_) => Markup.CreateWarn(node, "Count(predicate) could be optimized")),
    (countGtN,   (node,_,_) => Markup.CreateWarn(node, "Count() > N could use Skip/Any")));
```

The `annotator` returns the node to put back in place. Common choices:
`SearchResult.Found(node, "…")` (default) or `Markup.CreateWarn(node, "…")` (renders
as a `/*~~>*/` marker plus a warning).

### `pattern.Find(tree, cursor, …)` — single-shot

For one-off marking inside an existing visitor:

```csharp
return pat.Find(methodInvocation, Cursor, (node, _, _) => Markup.CreateWarn(node, "…"));
```

---

## 6. Templates

### `Apply`

A `CSharpTemplate` has `Apply(Cursor cursor, MatchResult? values = null, CSharpCoordinates? coordinates = null)`.

- `cursor` — the cursor where the result is being grafted in. Used for prefix
  preservation and outer-precedence parenthesization.
- `values` — substitutions for captures. Pass the `MatchResult` you got from
  `pattern.Match(…)`, or build one with `MatchResult.Of(…)` (see § 7). Omit when the
  template has no captures.
- `coordinates` — *where* relative to the cursor. Defaults to
  `CSharpCoordinates.Replace(<the cursor node>)`. Other modes: `Before(node)` and
  `After(node)`.

The returned tree has the original prefix applied but is **not formatted**. The caller
is responsible for calling `AutoFormat` / `MaybeAutoFormat` (see § 9).

### Apply with no captures (substitution-free template)

When the template is a static rewrite (no `{capture}` holes), just call `Apply(Cursor)`:

```csharp
// OpenRewrite.Recipes.CSharp.Migration.Dotnet/Net6/UseCryptoFactoryMethods.cs
public override J VisitNewClass(NewClass newClass, ExecutionContext ctx)
{
    newClass = (NewClass)base.VisitNewClass(newClass, ctx);

    var typeName = GetTypeName(newClass.Clazz);
    if (typeName == null || !AbstractCryptoTypes.Contains(typeName))
        return newClass;

    // Build a literal-substituted template per call (typeName splices in via Raw.Code).
    var tmpl = CSharpTemplate.Expression(
        $"{Raw.Code(typeName)}.Create()",
        usings: ["System.Security.Cryptography"]);
    return tmpl.Apply(Cursor)!;        // no values — no captures
}
```

This pattern is also common when synthesizing a fresh `Annotation`:

```csharp
// OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/DisposableToAfterTest.cs
private static readonly CSharpTemplate AfterTestTemplate =
    CSharpTemplate.Attribute($"After(HookType.Test)", usings: ["TUnit.Core"]);

// later, inside a visit method:
var afterAttr = ((Annotation)AfterTestTemplate.Apply(Cursor)!).WithPrefix(Space.Empty);
```

### Apply with captures (the common case)

When the template has holes, pass the `MatchResult` straight through from `Match`:

```csharp
if (pat.Match(mi, Cursor) is { } match)
    return (J)tmpl.Apply(Cursor, values: match)!;
```

Or supply captures manually (see § 7 for the `MatchResult.Of` overloads):

```csharp
// OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/FactTimeoutToTimeoutAttribute.cs
var timeoutAnnotation = ((Annotation)TimeoutTemplate.Apply(
    Cursor,
    values: MatchResult.Of(("value", timeoutValue)))!).WithPrefix(Space.Empty);
```

### Coordinates — `Replace` (default), `Before`, `After`

`CSharpCoordinates` controls *where* the rendered template lands in the tree. There are
three modes:

```csharp
CSharpCoordinates.Replace(node);   // default — substitute node with the template result
CSharpCoordinates.Before(node);    // insert template result as a preceding sibling
CSharpCoordinates.After(node);     // insert template result as a following sibling
```

`Replace` is what every rewriting recipe in this repo uses, because the declarative
`Rewrite` factory and the typical "match-then-apply" loop are both per-node
replacements. You only need `Before` / `After` when adding a sibling without removing
the anchor — e.g. inserting a guard statement above an existing one, or appending a new
method after a matched one. Conceptually:

```csharp
// "Insert a null-check before the matched call site, but keep the call."
var guard = CSharpTemplate.Statement($"if ({arg} is null) throw new ArgumentNullException(nameof({arg}));");
return guard.Apply(Cursor, values: match, coordinates: CSharpCoordinates.Before(stmt))!;
```

In practice you'll see this used most when the surrounding tree is a list (block
statements, class members) and the template adds to that list rather than substituting
into a hole.

### `usings:` — namespace imports

If the template references a type from a namespace not currently imported, declare it:

```csharp
// OpenRewrite.Recipes.CSharp.Migration.Dotnet/Net6/UseCryptoFactoryMethods.cs
var tmpl = CSharpTemplate.Expression(
    $"{Raw.Code(typeName)}.Create()",
    usings: ["System.Security.Cryptography"]);
```

If the using isn't already present in the target file, the framework adds it.

### `dependencies:` — NuGet packages

A map of `package id → version` that the *pattern* needs available, or that the
*template* should inject as a `PackageReference` into the project.

```csharp
// FactToTest.cs — pattern needs xunit attributed; template adds TUnit
CSharpPattern.Attribute($"Fact({args})",
    usings: ["Xunit"],
    dependencies: new Dictionary<string,string> { ["xunit"] = "2.9.3" }),
CSharpTemplate.Attribute($"Test({args})",
    usings: ["TUnit.Core"],
    dependencies: new Dictionary<string,string> { ["TUnit"] = "1.19.74" })
```

Versions can be floats (`"13.*"`) for type-attribution-only purposes.

### `context:` — extra declarations

A list of extra source snippets to add inside the scaffold class. Rarely needed in
recipes — exists so a template that calls a helper method can declare a stub of that
method to get parser/type attribution.

### `Raw.Code(…)` — splice strings at construction time

Captures bind at *apply* time (one value per match). `Raw.Code(string)` splices a string
into the template source code at *construction* time, before Roslyn ever parses it. Use
it when the variability is a recipe option (string) rather than an AST subtree.

```csharp
// e.g. recipe option `string Level = "Info"`
var tmpl = CSharpTemplate.Expression($"logger.{Raw.Code(Level)}({msg})");
```

This is exactly equivalent to building the string yourself and passing it to
`CSharpTemplate.Expression(string)` — `Raw` just lets you keep the interpolated-string
style.

A complete real example, where the type name is pulled per-node from the matched AST
and spliced into a fresh template each time:

```csharp
// OpenRewrite.Recipes.CSharp.Migration.Dotnet/Net6/UseCryptoFactoryMethods.cs
public override J VisitNewClass(NewClass newClass, ExecutionContext ctx)
{
    newClass = (NewClass)base.VisitNewClass(newClass, ctx);

    var typeName = GetTypeName(newClass.Clazz);                       // e.g. "SHA256"
    if (typeName == null || !AbstractCryptoTypes.Contains(typeName))
        return newClass;

    // Per-node template, type name spliced in literally:
    var tmpl = CSharpTemplate.Expression(
        $"{Raw.Code(typeName)}.Create()",
        usings: ["System.Security.Cryptography"]);
    return tmpl.Apply(Cursor)!;                                       // no captures
}
```

`Raw.Code` vs a `Capture`:

| | `Raw.Code(...)` | `Capture.Expression(...)` |
|---|---|---|
| When the value is known | At template construction (recipe option, name extracted from cursor before building the template) | At match time (extracted from the matched AST) |
| Bound to | A `string` | An AST subtree |
| Reusable across calls | No (the template embeds the literal text) | Yes (one template handles many shapes) |
| Re-parseable | Yes — it becomes ordinary source text | Stays as an AST node; not re-parsed |

---

## 7. `MatchResult` — capture values

A successful `pattern.Match(tree, cursor)` returns a `MatchResult`. Pass it to
`template.Apply(cursor, values: match)` to substitute captures. The template engine
looks up each placeholder by *name*, so the captures used in the template don't have to
be the same `Capture<T>` *objects* used in the pattern, as long as the names line up.

### Reading captures imperatively

When you need to inspect what was bound — for example to apply extra business logic
before deciding to apply the template:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Simplification/UseStringEquals.cs
if (patEq.Match(binary, Cursor) is { } match && IsStringComparison(match))
    return (J)tmplEq.Apply(Cursor, values: match)!;

private static bool IsStringComparison(MatchResult match)
{
    var l = match.Get<Expression>("lhs");
    var r = match.Get<Expression>("rhs");
    // … further checks on l and r …
}
```

API:

```csharp
match.Has("lhs");                       // was a capture bound?
match.Has(capture);                     // overload taking ICapture
match.Get<Expression>("lhs");           // get by name
match.Get(capture);                     // get by Capture<T> — type-inferred
match.GetList<Expression>("args");      // for variadic captures
```

#### Using `Has` for optional captures

A capture is "bound" only if its placeholder appeared in the matched subtree. If your
pattern has an optional segment (e.g. a `message` argument that may or may not be
present in different overloads), use `Has` to branch:

```csharp
if (match.Has("message"))
{
    var msg = match.Get<Expression>("message")!;
    return (J)withMessageTmpl.Apply(Cursor, values: match)!;
}
return (J)withoutMessageTmpl.Apply(Cursor, values: match)!;
```

#### Reading captures by `Capture<T>` object

The string-keyed `Get<T>("lhs")` works, but it forces you to keep the name in sync.
The object-keyed overload doesn't:

```csharp
var lhs = Capture.Expression("lhs", type: "string");
…
if (pat.Match(binary, Cursor) is { } match)
{
    var left = match.Get(lhs);           // type inferred as Expression?
    // refactor "lhs" → "left" — no string changes elsewhere
}
```

### Building a `MatchResult` from scratch

When values come from imperative extraction rather than a pattern match, build one with
`MatchResult.Of`:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Naming/UseNameofOperator.cs
(J)NameofTmpl.Apply(Cursor, values: MatchResult.Of(("param", paramId)))!,
```

Or, more type-safely, using the capture objects themselves as keys:

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/UseCoalesceExpressionFromNullCheck.cs
var coalesce = CSharpTemplate.Expression($"{_x} ?? {_y}");

return (Expression)coalesce.Apply(
    Cursor,
    values: MatchResult.Of(
        (_x, (J)checkedVar),
        (_y, (J)fallback)))!;
```

This avoids hand-synchronizing string names with capture objects — refactor a capture's
name and the call site stays correct.

---

## 8. Visitor factories — `Rewrite` and `Find`

The single biggest source of conciseness in this API. Three overloads each, with the
same shape: one rule, multiple-patterns-one-template, or independent rule pairs.

### `CSharpTemplate.Rewrite(pattern, template)`

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/UseEmptyStringLiteral.cs
public override JavaVisitor<ExecutionContext> GetVisitor() =>
    CSharpTemplate.Rewrite(
        CSharpPattern.Expression($"\"\""),
        CSharpTemplate.Expression($"string.Empty"));
```

That entire recipe body is the `Rewrite` call. Under the hood it produces a visitor that
runs `pattern.Match` in `PostVisit` on every node, applies the template on match, and
runs `AutoFormat` on the result.

### `CSharpTemplate.Rewrite([p1, p2, …], template)` — many patterns, one template

```csharp
var s = Capture.Expression("s", type: "string");
return CSharpTemplate.Rewrite(
    [
        CSharpPattern.Expression($"{s} == null || {s} == \"\""),
        CSharpPattern.Expression($"{s} == null || {s}.Length == 0"),
    ],
    CSharpTemplate.Expression($"string.IsNullOrEmpty({s})"));
```

### `CSharpTemplate.Rewrite((p, t), (p, t), …)` — independent rule pairs

```csharp
return CSharpTemplate.Rewrite(
    (CSharpPattern.Expression($"Assert.True({subject})"),  CSharpTemplate.Expression($"await Assert.That({subject}).IsTrue()")),
    (CSharpPattern.Expression($"Assert.False({subject})"), CSharpTemplate.Expression($"await Assert.That({subject}).IsFalse()")),
    (CSharpPattern.Expression($"Assert.Null({subject})"),  CSharpTemplate.Expression($"await Assert.That({subject}).IsNull()")));
```

First match wins per node.

### `CSharpPattern.Find(…)` — same shape, but for marking

The `Find` overloads on `CSharpPattern` mirror `Rewrite`: one pattern + one annotator,
many patterns + one annotator, or independent `(pattern, annotator)` pairs. See § 5.

---

## 9. AutoFormat — keeping whitespace honest

The declarative `Rewrite` factory calls `AutoFormat` for you. The imperative path does
*not* — you have to call it yourself. There are two flavors:

- **`MaybeAutoFormat(before, after, ctx, cursor)`** — schedules deferred batch
  formatting. Cheap; called from inside a `Visit*` method.
- **`AutoFormat(after, ctx, cursor)` (extension)** — formats immediately.

Always format **at the node you modified**, not at the compilation-unit level — CU-level
formatting reformats unrelated code in the same file.

```csharp
// OpenRewrite.Recipes.CSharp.CodeQuality/Style/AddBracesToIfElse.cs
public override J VisitIf(If ifStatement, ExecutionContext ctx)
{
    var before = ifStatement;
    ifStatement = (If)base.VisitIf(ifStatement, ctx);
    // … wrap then/else in blocks …
    return MaybeAutoFormat(before, ifStatement, ctx, Cursor);
}
```

Inside synthesized nodes (built via `Space.Empty`), let `AutoFormat` decide indentation
and newlines. Only use `Space.SingleSpace` where tokens must not visually merge (e.g.
between a keyword and its operand).

---

## 10. When to use which API surface

A rough decision tree:

1. **Pure search recipe** — finds smelly code, doesn't change it.
   - One pattern → `CSharpPattern.Find(pattern, "message")`.
   - Many patterns, same message → `CSharpPattern.Find([p1, p2], "message")`.
   - Many patterns, different messages → `CSharpPattern.Find((p1, ann1), (p2, ann2))`.

2. **Pure rewrite, no extra logic** — the pattern's structural shape and capture
   constraints are sufficient to decide.
   - One rule → `CSharpTemplate.Rewrite(pattern, template)` in `GetVisitor()`. **One line.**
   - Many patterns → array or rule-pairs overload.

3. **Rewrite needs extra checks the pattern can't express** — e.g. "is this inside an
   `Equals(object)` override?" or "are both operands non-null literals?"
   - Write a small visitor: override the most specific `Visit*` you can
     (`VisitBinary`, `VisitMethodInvocation`, etc.), call `pattern.Match`, run your
     extra check, then `tmpl.Apply` and `MaybeAutoFormat`.
   - Hold the pattern + template as fields/`static readonly`; build them in
     `GetVisitor()` and pass to the visitor.

4. **Rewrite where the inputs don't come from a pattern at all** — synthesizing code
   from recipe options, scanning accumulator state, etc.
   - Build a `CSharpTemplate` (often with `Raw.Code(…)`), then call
     `template.Apply(cursor, values: MatchResult.Of((cap, value), …))`.

5. **Prefer specific `Visit*` overrides over `PostVisit`** when:
   - You want cursor-context checks ("am I inside an X?").
   - You're combining `pattern.Match` with non-trivial post-processing.
   - You need to call `MaybeAutoFormat` explicitly.

   `PostVisit` (the default for the declarative factory) is fine when the pattern
   carries all the information needed.

---

## 11. Worked examples

### a) One-liner rewrite

```csharp
// UseEmptyStringLiteral.cs
public override JavaVisitor<ExecutionContext> GetVisitor() =>
    CSharpTemplate.Rewrite(
        CSharpPattern.Expression($"\"\""),
        CSharpTemplate.Expression($"string.Empty"));
```

### b) Type-constrained rewrite

```csharp
// RemoveRedundantToStringCall.cs
public override JavaVisitor<ExecutionContext> GetVisitor()
{
    var s = Capture.Expression("s", type: "string");
    return CSharpTemplate.Rewrite(
        CSharpPattern.Expression($"{s}.ToString()"),
        CSharpTemplate.Expression($"{s}"));
}
```

### c) Multiple patterns → one template, with type attribution

```csharp
// UseStringIsNullOrEmpty.cs
public override JavaVisitor<ExecutionContext> GetVisitor()
{
    var s = Capture.Expression("s", type: "string");
    return CSharpTemplate.Rewrite(
        [
            CSharpPattern.Expression($"{s} == null || {s} == \"\""),
            CSharpPattern.Expression($"{s} == null || {s}.Length == 0"),
        ],
        CSharpTemplate.Expression($"string.IsNullOrEmpty({s})"));
}
```

### d) Imperative match + apply with extra business checks

```csharp
// UseStringEquals.cs
public override J VisitBinary(Binary binary, ExecutionContext ctx)
{
    binary = (Binary)base.VisitBinary(binary, ctx);

    if (IsInsideEqualsOverride()) return binary;

    if (patEq.Match(binary, Cursor) is { } match && IsStringComparison(match))
        return (J)tmplEq.Apply(Cursor, values: match)!;
    if (patNeq.Match(binary, Cursor) is { } matchNeq && IsStringComparison(matchNeq))
        return (J)tmplNeq.Apply(Cursor, values: matchNeq)!;

    return binary;
}
```

### e) Search-only with custom warning

```csharp
// FindDoNotCompareWithNaN.cs
public override JavaVisitor<ExecutionContext> GetVisitor()
{
    var x = Capture.Expression();
    var t = Capture.Expression(constraint: (n, _) =>
        n is Identifier { SimpleName: "Double" or "Single" }
            or Primitive { Kind: JavaType.PrimitiveKind.Double or JavaType.PrimitiveKind.Float });

    return CSharpPattern.Find(
        [
            CSharpPattern.Expression($"{x} == {t}.NaN"),
            CSharpPattern.Expression($"{x} != {t}.NaN"),
        ],
        (node, _, _) => Markup.CreateWarn(node,
            "Comparison with NaN always returns false/true. Use double.IsNaN()."));
}
```

### f) Synthesizing a template from recipe options with `Raw.Code`

```csharp
// UseCryptoFactoryMethods.cs (sketch)
var tmpl = CSharpTemplate.Expression(
    $"{Raw.Code(typeName)}.Create()",
    usings: ["System.Security.Cryptography"]);
return tmpl.Apply(Cursor)!;
```

### g) Producing an attribute with manual capture binding

```csharp
// FactTimeoutToTimeoutAttribute.cs
private static readonly Capture<Expression> Value = Capture.Expression("value");
private static readonly CSharpTemplate TimeoutTemplate = CSharpTemplate.Attribute($"Timeout({Value})");

// in VisitAnnotatedStatement:
var timeoutAnnotation = ((Annotation)TimeoutTemplate.Apply(
    Cursor,
    values: MatchResult.Of(("value", timeoutValue)))!).WithPrefix(Space.Empty);
```

### h) Boolean `Matches` — pure search across two node kinds

When the pattern is structural and the captures aren't needed, `Matches` is the lighter
call. Here the same warning is attached to two different node positions (field access
and method invocation):

```csharp
// FindDistributedContextPropagator.cs
public override ITreeVisitor<ExecutionContext> GetVisitor()
{
    var currentPat = CSharpPattern.Expression(
        $"DistributedContextPropagator.Current",
        usings: ["System.Diagnostics"]);
    var createPat = CSharpPattern.Expression(
        $"DistributedContextPropagator.CreateDefaultPropagator()",
        usings: ["System.Diagnostics"]);
    return Check(UsesType("System.Diagnostics.DistributedContextPropagator"),
                 new Visitor(currentPat, createPat));
}

private class Visitor(CSharpPattern currentPat, CSharpPattern createPat)
    : CSharpVisitor<ExecutionContext>
{
    public override J VisitFieldAccess(FieldAccess fa, ExecutionContext ctx)
    {
        fa = (FieldAccess)base.VisitFieldAccess(fa, ctx);
        if (currentPat.Matches(fa, Cursor))
            return Markup.CreateWarn(fa, WarnMessage);
        return fa;
    }

    public override J VisitMethodInvocation(MethodInvocation mi, ExecutionContext ctx)
    {
        mi = (MethodInvocation)base.VisitMethodInvocation(mi, ctx);
        if (createPat.Matches(mi, Cursor))
            return Markup.CreateWarn(mi, WarnMessage);
        return mi;
    }
}
```

Notice the `Check(UsesType(...), visitor)` wrapper — a precondition gate so the visitor
only runs on files that actually reference `DistributedContextPropagator`. Pairs well
with `Matches` (no captures to thread through).

### i) `Capture.Name` to enforce identifier identity

The lambda parameter and its use in the body must be the *same identifier*. Using
`Capture.Name` (not `Capture.Expression`) is what makes the pattern bind both positions
to the same name:

```csharp
// UseLinqDistinctBy.cs
public override JavaVisitor<ExecutionContext> GetVisitor()
{
    var target   = Capture.Expression(type: "IEnumerable<T>", typeParameters: ["T"]);
    var selector = Capture.Expression();
    var g        = Capture.Name();      // <-- identifier; must match itself

    return CSharpTemplate.Rewrite(
        // matches: items.GroupBy(x => x.Key).Select(g => g.First())  — g & g must agree
        CSharpPattern.Expression($"{target}.GroupBy({selector}).Select({g} => {g}.First())"),
        CSharpTemplate.Expression($"{target}.DistinctBy({selector})"));
}
```

If `g` were `Capture.Expression()`, then `.Select(a => b.First())` (different
identifiers) would also match — wrong.

### j) `Capture.Type` for a type-position hole

```csharp
// MigrateXUnitAssertions.cs (excerpt)
var t      = Capture.Type("t");
var action = Capture.Expression("action");

return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"Assert.Throws<{t}>({action})",
        usings: ["Xunit"], dependencies: XunitDeps),
    CSharpTemplate.Expression($"await Assert.That({action}).ThrowsExactly<{t}>()",
        usings: ["TUnit.Assertions"], dependencies: TunitDeps));
```

`Capture.Type` is the right choice for the generic argument `<T>` — it parses in a
type-context scaffold so Roslyn knows it's a type, not an expression.

### k) `Capture.Of<T>` — capture a specific AST node kind

When you need to match (or substitute) something that is not a generic Expression but a
more specific node (e.g. a `Literal`, a `Block`, a `Statement`), use `Capture.Of<T>`:

```csharp
// match `Math.Max(<some literal>, anything)` but not `Math.Max(x, y)`
var lit  = Capture.Of<Literal>("lit");
var rest = Capture.Expression();

return CSharpPattern.Find(
    CSharpPattern.Expression($"Math.Max({lit}, {rest})"),
    (node, _, m) =>
    {
        var literal = m.Get<Literal>("lit");
        return Markup.CreateWarn(node, $"Literal {literal!.ValueSource} should be a named constant.");
    });
```

The `T` in `Capture.Of<T>` is checked at match time — if the candidate isn't assignable
to `T`, the match fails. This is stricter than `Capture.Expression()`, which accepts any
`Expression`.

### l) Variadic capture with a list-level constraint

```csharp
// match Foo(a, b) — exactly two args — and forward them
var args = Capture.Expression("args",
    variadic: new(Min: 2, Max: 2));     // bounds, no further check

return CSharpTemplate.Rewrite(
    CSharpPattern.Expression($"Foo({args})"),
    CSharpTemplate.Expression($"Bar({args})"));
```

Or with a callback:

```csharp
// match Foo(...) where the first arg is a literal "ok"
var args = Capture.Expression("args",
    variadic: new(Constraint: (xs, _) =>
        xs.Count >= 1 && xs[0] is Literal { ValueSource: "\"ok\"" }));
```

### m) Dependent constraint — capture `b` depends on capture `a`

```csharp
// match Math.Max(1, anything) but reject Math.Max(2, anything)
var a = Capture.Of<Expression>("a");
var b = Capture.Expression("b", constraint: (node, ctx) =>
    ctx.Captures.TryGetValue("a", out var bound)
    && bound is Literal { ValueSource: "1" });

return CSharpPattern.Find(
    CSharpPattern.Expression($"Math.Max({a}, {b})"),
    "first argument is the literal 1");
```

`ctx.Captures` is read-only and contains everything bound *so far* in this match
attempt. Captures bind left-to-right in the pattern's source order, so `b`'s constraint
can read `a` but not vice versa.

### n) Full recipe scaffolding for the imperative case

What a complete imperative recipe looks like, end-to-end. Build pattern + template in
`GetVisitor()`, pass them into a private visitor class, override the right `Visit*`,
call `Match`, do business checks, `Apply`, return:

```csharp
[Category, CSharp, CodeQuality, Simplification]
public class UseStringEquals : Recipe
{
    public override string DisplayName => "Use string.Equals for string comparison";
    public override string Description  => "...";

    public override JavaVisitor<ExecutionContext> GetVisitor()
    {
        var lhs = Capture.Expression("lhs", type: "string");
        var rhs = Capture.Expression("rhs", type: "string");

        var patEq   = CSharpPattern.Expression($"{lhs} == {rhs}");
        var tmplEq  = CSharpTemplate.Expression(
            $"string.Equals({lhs}, {rhs}, StringComparison.Ordinal)");
        var patNeq  = CSharpPattern.Expression($"{lhs} != {rhs}");
        var tmplNeq = CSharpTemplate.Expression(
            $"!string.Equals({lhs}, {rhs}, StringComparison.Ordinal)");

        return new Visitor(patEq, tmplEq, patNeq, tmplNeq);
    }

    private class Visitor(CSharpPattern patEq,  CSharpTemplate tmplEq,
                          CSharpPattern patNeq, CSharpTemplate tmplNeq)
        : CSharpVisitor<ExecutionContext>
    {
        public override J VisitBinary(Binary binary, ExecutionContext ctx)
        {
            binary = (Binary)base.VisitBinary(binary, ctx);
            if (IsInsideEqualsOverride()) return binary;

            if (patEq.Match(binary, Cursor) is { } m1 && IsStringComparison(m1))
                return (J)tmplEq.Apply(Cursor, values: m1)!;
            if (patNeq.Match(binary, Cursor) is { } m2 && IsStringComparison(m2))
                return (J)tmplNeq.Apply(Cursor, values: m2)!;
            return binary;
        }
        // … IsStringComparison, IsInsideEqualsOverride …
    }
}
```

Note the recurring shape: captures and patterns are constructed once per `GetVisitor()`
call (cheap — they cache their parsed tree on first use), held by the visitor as ctor
arguments, and used in every relevant `Visit*` override.

---

## 12. Common gotchas

- **Don't use `.Create(…)`.** It's obsolete. Pick `.Expression`, `.Statement`,
  `.ClassMember`, or `.Attribute` explicitly so the scaffold matches the syntactic
  position.
- **Match the scaffold to your code's position.** Trying to match `if (x) { … }` with
  `.Expression` will not work — use `.Statement`. Trying to match `[Foo]` with
  `.Expression` won't work either — use `.Attribute`.
- **Type-constrain whenever the surface syntax is ambiguous.** Without
  `type: "string"`, a `.ToString()` rewrite hits every object in the codebase. The
  Roslyn-resolved type is what makes recipes safe.
- **Variadic xor single constraint.** A `Capture<T>` may have either `constraint:` or
  `variadic:`, not both. List-level checks go in `VariadicOptions.Constraint`.
- **Capture names must be unique within a single pattern/template pair.** Two
  `Capture.Expression()` calls with the same name will collide — let the auto-generated
  name handle uniqueness, or pass distinct names.
- **`{capture}` interpolation only works inside the template-string overload.** Plain
  `string`-overloads (`.Expression("…")`) are literal — they don't intercept captures. A
  capture in a plain string will fall back to `__plh_<name>__` via `ToString()`, which
  won't be registered. Always use `$"…"` when the snippet has holes.
- **AutoFormat is your job in imperative visitors.** The declarative `Rewrite` factory
  formats; explicit `Visit*` overrides do not. Call `MaybeAutoFormat(before, after, ctx, Cursor)`.
- **Format at the change site, not the CU.** Reformatting the whole file ripples
  whitespace changes into unrelated code.
- **`Capture.Name` vs `Capture.Expression` for identifiers.** When the same identifier
  must appear twice (e.g. a lambda parameter and its use in the body), `Capture.Name`
  enforces identity. `Capture.Expression` would match any subtree at each site.
- **Captures bind left-to-right.** A constraint on capture `b` that depends on capture
  `a` via `ctx.Captures["a"]` works because `a` is bound first. The reverse won't.

---

## 13. Hero examples to read end-to-end

If you want to internalize the patterns, read these complete recipes (paths relative to
the `recipes-csharp` repo root):

- **Simplest one-liner** —
  `OpenRewrite.Recipes.CSharp.CodeQuality/Style/UseEmptyStringLiteral.cs`
- **Imperative match-and-apply with business logic** —
  `OpenRewrite.Recipes.CSharp.CodeQuality/Simplification/UseStringEquals.cs`
- **Many-rule structural rewrite (cross-framework migration)** —
  `OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/MigrateXUnitAssertions.cs`
- **Attribute templates with manual capture binding** —
  `OpenRewrite.Recipes.CSharp.Migration.TUnit/FromXUnit/FactTimeoutToTimeoutAttribute.cs`

And for reference, the SDK source files that define the API:

- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/CSharpTemplate.cs`
- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/CSharpPattern.cs`
- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/Capture.cs`
- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/MatchResult.cs`
- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/CSharpCoordinates.cs`
- `rewrite-csharp/csharp/OpenRewrite/CSharp/Template/Raw.cs`
