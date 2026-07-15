---
name: writing-dotnet-recipes-using-templates
description: >-
  Authoring concise OpenRewrite C# (.NET) recipes with the structural-pattern
  template API — CSharpPattern, CSharpTemplate, Capture, and MatchResult in the
  OpenRewrite.CSharp.Template namespace. Use when writing, reviewing, or
  debugging a C# recipe that matches, rewrites, or finds code patterns:
  typed and variadic captures, type-constrained matching, the declarative
  Rewrite/Find factories vs. an imperative Visit* visitor, Raw.Code splicing,
  Before/After/Replace coordinates, and AutoFormat.
---

# Writing .NET (C#) Recipes Using Templates

A guide to authoring OpenRewrite C# recipes with the structural-pattern API in
`OpenRewrite.CSharp.Template`. Describe the *before* and *after* shape of code as
interpolated strings, let the SDK do the matching/templating/formatting, and only drop
into imperative visitor code when you actually need to.

## When to use

Reach for this skill whenever the task involves a C# OpenRewrite recipe — creating one,
reviewing one, or debugging why one over/under-matches. Specifically:

- Matching, rewriting, or finding C# code patterns with `CSharpPattern` / `CSharpTemplate`.
- Declaring wildcards with `Capture.Expression` / `Capture.Name` / `Capture.Type` /
  `Capture.Of<T>`, including type-constrained, delegate-constrained, dependent, and
  variadic captures.
- Choosing between the declarative `Rewrite` / `Find` visitor factories and an imperative
  `Visit*` override that calls `Match` + `Apply`.
- Reading captures from a `MatchResult` (`Get` / `GetList` / `Has`) or building one with
  `MatchResult.Of`.
- Splicing literal text with `Raw.Code`, placing results with `Replace` / `Before` /
  `After` coordinates, adding `usings:` / `dependencies:`, and getting whitespace right
  with `AutoFormat` / `MaybeAutoFormat`.

## Mental model

Three small types do the heavy lifting:

| Type | Role |
|---|---|
| `Capture<T>` (`Capture.Expression`, `.Name`, `.Type`, `.Of<T>`) | A typed *hole*. In a pattern it is a wildcard that binds a subtree; in a template it is a substitution point. |
| `CSharpPattern` | A parsed snippet whose holes are wildcards. `Match` it against an AST node → `MatchResult`. |
| `CSharpTemplate` | A parsed snippet whose holes get filled in. `Apply` it at a cursor with a `MatchResult` → a new AST node. |

`CSharpTemplate.Rewrite(pattern, template)` returns a ready-to-use visitor: it runs
`pattern.Match` on every node, applies the template on match, and `AutoFormat`s the
result — replacing what used to be a 30-line imperative override.

## Pick the scaffold matching the syntactic position

`.Expression` (values — the default 90% of the time) · `.Statement` (a full statement,
keep the trailing `;`) · `.ClassMember` (methods/fields/properties) · `.Attribute`
(`[Test]`, `[Timeout(5000)]`). Avoid the deprecated auto-detecting `.Create`.

## Decision tree

1. **Pure search** (find, don't change) → `CSharpPattern.Find(pattern, "message")`
   (or array / `(pattern, annotator)`-pair overloads).
2. **Pure rewrite, pattern is sufficient** → `CSharpTemplate.Rewrite(pattern, template)`
   in `GetVisitor()` — often one line.
3. **Rewrite needs extra checks the pattern can't express** → small visitor: override the
   most specific `Visit*`, call `pattern.Match`, run the check, `Apply`, `MaybeAutoFormat`.
4. **Inputs don't come from a pattern** (recipe options, accumulator state) → build a
   `CSharpTemplate` (often with `Raw.Code`), then `Apply(cursor, values: MatchResult.Of(...))`.

## Full guide

Read **[writing-dotnet-recipes-using-templates.md](writing-dotnet-recipes-using-templates.md)**
for the complete reference, including: every capture factory and constraint flavor,
variadic bounds, `Match` / `Matches` / `Find`, coordinates, `Raw.Code` vs. `Capture`,
`MatchResult` reading and building, the `Rewrite` / `Find` factory overloads, AutoFormat
rules, common gotchas, and worked end-to-end examples (a–n) plus hero recipes to read.
