# OpenRewrite and Moderne CLI

This project is for OpenRewrite. It is made up many moving parts spread across multiple repos. The project focus is to do static analysis and refactoring by building Lossless Syntax Trees.

## Terminology

Lossless Syntax Tree (LST) - an OpenRewrite version of AST (Abstract Syntax Tree) that combines syntax tree with semantic compiler information (Fully qualified type information attached to each syntax node). The semantic information is usually accessible via `Type` property on the syntax objects. LST are the product of `mod build` command and are generally stored in git repo.

Attestation - the attachment of semantic type information to syntax nodes inside the syntax tree (usually inside Type property).

Recipe - an OpenRewrite component that follows a standard implementation pattern that acts on LSTs by executing a visitor. 

Search Recipe, Find Recipe - a type of recipe that does not transform the LST but instead finds some information in it. The results are usually reported by either placing a Marker on the syntax node or producing a data table

Data table - an optional output a recipe can produce when running over the code. Multiple data tables can be created by a single recipe. These are normally serialized as CSV on disk.

pTML - publishToMavenLocal gradle task

fat jar - java fat jar. When asked to created one, always assume the user is referring to "devFatJar" target unless explicitly specified otherwise

org - in openrewrite/moderne context, refers to a collection of git repos. Locally this is expressed by a folder with nested organized subfolder structure that contains multiple git repos. Most `mod` commands can be run against "org" and is meant to act on multiple git repos as part of same batch. Note that each git repo will receive it's own `./moderne` folder containing build/run results

## moderne cli 

Aliases: (aka `mod`, `modw`)
Repo: https://github.com/moderneinc/moderne-cli/
Depends On: OpenRewrite sdk

A proprietery cli tool from Moderne that is responsible for acting on source repos to do things like building LSTs, running recipes, examining / applying results. 

## OpenRewrite

Aliases: rewrite, sdk
Repo: https://github.com/openrewrite/rewrite/

The core library used to parse source files into LSTs and run recipes against them. The technology works across languages, not just Java. Recipes can be written in different languages, with Java being "execution harness" which can delegate parsing / recipe execution to subprocesses that handle specific languages such as C#, javascript, python, etc. Java communicates to them via JSONRPC protocol.

## Recipes

Many repos have recipes that act as plugins for mod cli. They can be written in different languages and distributed via package manager for corresponding ecosystem (maven, pip, nuget, npm, etc). Many recipes are developed by Moderne with links below

CSharp recipes: https://github.com/moderneinc/recipes-csharp

## PreThink

Prethink is type of recipes that perform static analysis on source code and write helpful information to assist AI when working with larger codebases. It creates a combination of artifacts (markdowns, csv, etc) to describe the codebase and these files are generally commited as part of the source code.

Repo: https://github.com/moderneinc/rewrite-prethink


## Cross Language Architecture

Java and other languages communicate via JsonRPC protocol to do things like language file parsing and running recipes. 
mod CLI depends on rewrite sdk. Rewrite SDK launches RPC server executable implemented in each target language and establishes connection to it. For .NET, this is OpenRewrite.CSharp.Tool nuget package distributed and installed via `dotnet tool install`.  This rpc server is responsible for actual execution of recipes written in non java languages. The version of the nuget package is included into as embedded resource into the mod jar file. 


## Local development

Recipe packages are registered in `mod` using `mod config recipe <jar|nuget> install <packageId>@<packageVersion>`. Once registered, this command will use latest version of that package from local package cache (~/.m2, ~/.nuget/packages, etc). It only needs to be rerun if registering new package or version change. When iterating locally, the version doesn't change between builds. For .NET based packages, it will always have a `-zlocal` suffix. 

### Ensuring we're running latest code
1. pTML on rewrite sdk. This will also publish nuget portions into global nuget cache. In both cases, the pTML ensures that cache is loaded with latest code even if version number hasn't changed.
2. Build mod cli devFatJar
3. publish latest recipes into nuget global cache via pTML on the recipes repo. 

### When to rebuild
- When making changes to c# recipes project, only pTML on that project is required. Latest versions are pulled automatically from latest global nuget cache. Changes to recipes DO NOT require LST to be rebuilt and should be skipped.
- When there are any changes to LST classes or parser, then CLI fat jar must be rebuilt and LST MUST be rebuilt via `mod build`.
- When there are user asks to rebuild fat jar, this ALWAYS requires pTML on rewrite sdk

#### Testing
Always test .NET code by running gradle "test" target as this ensures that java server is available when running certain tests that depend on it. Standard `dotnet test` WILL fail for a number of tests that rely on this bridge (it can still be used for intermediate testing to get quick results).

Before opening PR, ALWAYS ensure that gradle test target has been run with latest changes of the code and ALL tests are passing an all repos that had changes.

### Other rules
- NEVER clear out .m2 or nuget global cache unless deleting a single targeted package. This should not be necessary under normal cicumstances as pTML target ensures that global caches are populated with latest build versions.
- The CLI uses `gradle/libs.version.toml` file to control which module versions are loaded by default. When iterating locally, the file needs to be adjusted to make rewrite and rewrite-csharp modules use `latest.integration` instead of `latest.release` so it can pick up the latest from maven cache. However, this change should NEVER be checked in.
- proactively clearing m2 / nuget global caches is NOT required. pTML already has logic to do this. Only start investigating if caching is issue if there are suspecion that code that was added did not in fact run as expected.
- If user asks to rebuild, depending on the project the following action should be taken:
  - CLI - gradle devFatJar target (preceeded by pTML of the SDK repo)
  - Rewrite SDK - pTML gradle target
  - Recipes - pTML gradle target 

- When running any csharp recipe through `mod` for first time in claude session, confirm that recipe package versions registered with `mod` are the same as the versions of recipe packages from recipe repo builds (so ensure that the version produced by pTML on recipes repo is the same one that's registered in mod). If it's not, register the newest version of the package with mod.
- Changes to Gradle files require consultation with the user first. If you are planning to make a change, first analyze carefully if you considered how it is supposed to work right now, and if you still feel change is required STOP and explain IN DETAIL what problem the change would address and how it would work. Give user the diff you're going to introduce to the file. 

### Version bumps
The solution uses nebula release management and recieves automatic version number. If the local version is behind origin, and a new maven release has been made that includes a higher version of rewrite / recipes then rebuildling local mod cli will NOT pick up the local version loaded into pTML but instead use the one from the maven. If it looks like expected code changes haven't "kicked in", this is a common reason why. The solution is to rebase the code on to remote to ensure local builds receive the highest version number and are selected.


## Working with mod CLI

Key commands:

`mod config recipes <jar|nuget|go|npm|pip> install <packageId>@<packageVersion>` - install recipes from a given package into mod. This delegates to native package manager based on each ecosystem to obtain the necessary package version from remote source. 

`mod config recipes <jar|nuget|go|npm|pip> delete <packageId>` - unregistered package

`mod config recipes list` - list all registered **packages** that contribute recipes and recipe counts each one is contributing (doesn't list actual recipes within them).

`mod build <path>` - builds LSTs for a given folder. The folder may be a "folder org" made up of many subfolders / git repos. Each git repo will be a seperate LST built, but mod build can be run org folder to build many repos in one command as batch. Also, mod build REQUIRES that the folder containing source code be git initialized, commited and have remote `origin` with a url that follows <hostAddress>/org/repo pattern (doesn't have to be a valid URL - just needs to be configured). Make sure you do this if you're generating synthetic sample source project to test `mod` against.  

`mod run --recipe=<FullyQualifiedClassName> <path>` runs recipe on the repo at given path. Recipe must be one of the FQN that were registered from one of the recipe packages

`mod git apply --last-recipe-run` - apply git patch generated inside moderne run directory to the repo. This can be run against org folder so as to apply all git patches in bulk.

`mod git <git command>` - can delegate git operations, but also works on org folders. Useful for when iteratingon org folder instead of individual repos  (executing same git operation on each git repo). 

### Mod fat jar
Mod is distributed as executabled (production release), but for local development a fat jar is produced via `devFatJar` target and executed via `modw` shell script at the root of CLI source repo. ALWAYS go through this wrapper script `modw` when executing `mod` CLI.

### Errors
#### Parse failures
Happens when an particular source file cannot be parsed with a syntax parser. This usually requires looking into the underlying parser. The actual parse failures need to be obtained by running the following recipe that produces a data table containing specific files/locations and error messages.
```
mod run . --recipe FindParseFailures
```
Once the recipe finishes the data tables are available inside the repo's `.moderne/runs/<runId>/datatables` folder

#### RPC desync
RPC errors that look like where one side was expecting a different message from one recieved are caused by desynchrnoization between java/.net (or other language) implementation. In order to work correctly, the LST model classes, RpcSender / RpcReceiver must be in perfect sync. 
Desynchronization most often caused by wrong versions of packages being working together. 90% of the time it's caused by stale cache issues. Ensure that you pTML on rewrite repo which loads the latest version build in global nuget cache from where it is run. When encountering this be 100% sure without assumptions that the mod cli (and the version of the rewrite sdk that went into it) is the one that it expects to work with for OpenRewrite.CSharp.Tool runner. Almost always it's not a code issue.

When genuine desync does creep up is usually when changes were made to one language without updating the others (or making corresponding changes to sender/reciever). 

## Working in Conductor
When using Conductor as the claude code wrapper, special rules apply. Conductor creates unique work trees for each repo allowing parallel developement and shipping of features. Worktrees are created in ~/conductor/ subfolders and this is how one can detect that work is being done inside conductor. Under normal circumstances the user will provide related repos in additional to primary working directory - check the Environment block’s Additional working directories field to confirm injected worktrees. Don’t infer from prompt prose and don’t probe disk - the paths to these additional worktrees must be explicitly provided and appear in your context. This will ensure claude has access to 3 key repos:
- mod CLI
- rewrite sdk
- recipes

all 3 are expected to be conductor worktrees. If the user forgot to add them, STOP and instruct the user to do so.
- ALWAYS work only on the conductor worktrees when the primary workdir is conductor. NEVER try to access connonical worktrees (default "non-conductor") for either reading or writing, nor try to "self discover" correct repositories. If the user has not injected them into context STOP AND TELL THE USER.

- when running any mod command, assume it must be run via `modw` script from the cli conductor worktree that is explicitly injected into context. NEVER run global `mod` unless explicitly told otherwise.

- `rewrite-csharp` inside conductor worktree requires a symlink under `external/openrewrite/rewrite` pointing to  `rewrite` conductor worktree. When starting a new chat ensure this symlink exists. Also ensure that additionalDirs is the same on every new user message as the one prior. If the sdk directory changed from last message, the symlink MUST be updated. Recipes project has two solution files:

  - `Recipes.slnx` - ties rewrite sdk via PackageReference
  - `Recipes.Source.slnx` - imports rewrite sdk csharp project as solution via symlink. The user prefer to use this mode when using IDE as it allows making changes to both recipes and sdk seamlessly without dealing with republishing packages. 

  When iterating inside conductor, Rewrite.Sources.slnx should be used 

- the user MAY add a working set directory containing an org of git repos. This is to be used as implicit input for evaluation of mod cli commands like `build` and recipe `run`.

## Source-Linked Mode (CLI Repo)

Builds the CLI fat jar against a local rewrite checkout via Gradle composite build, so rewrite (Java + C#) changes flow in without publishing. Avoids ~/.m2 / ~/.nuget collisions between parallel worktrees. This is the preferred way to work inside conductor to ensure isolated parallel work streams





**Setup (initial or if user changes linked workspaces)

Do this once on first request or if user changes recipes to a different workspace. It REQUIRES that the user has provided rewrite sdk and recipes repos as additional workspaces (injected into environment block in context)

- create symlinks to related repos under `external/<orgname>/<reponame>` folder structure
- modify `.claude/settings.local.json` to use expanded absolute paths (it doesn't do variable expansion). Ensure that expanded env vars are explicitly used in any commands inside this turn as the `settings.local.json` changes will not take effect until next conversation turn.
- ensure the dev fat jar is built (see special command below)
- set env vars for remainder of the session:
```
export REWRITE_DOTNET_RPC_SERVER=`<REWRITE_SDK_REPO>/rewrite-csharp/csharp/OpenRewrite.Tool/OpenRewrite.Tool.csproj`
export MODERNE_CLI_HOME="$CONDUCTOR_WORKSPACE_PATH/.local/.moderne/cli"
```
- `dotnet build Recipes.Source.slnx` recipes repo
- register recipe packages:
  ```
  modw config recipes nuget install <LINKED_RECIPE_REPO>/OpenRewrite.Recipes.CSharp.Core/bin/Debug/net10.0/OpenRewrite.Recipes.CSharp.Core.dll
   modw config recipes nuget install <LINKED_RECIPE_REPO>/OpenRewrite.Recipes.CSharp.CodeQuality/bin/Debug/net10.0/OpenRewrite.Recipes.CSharp.CodeQuality.dll

 modw config recipes nuget install <LINKED_RECIPE_REPO>/OpenRewrite.Recipes.CSharp.Migration.Dotnet/bin/Debug/net10.0/OpenRewrite.Recipes.CSharp.Migration.Dotnet.dll
  ```
  
**Building the fat jar:**

`./gradlew :mod:devFatJar -I .local/gradle/local-rewrite.init.gradle.kts`


## Expectations

All repos (primary and additional) are all considered single source code. The can existing in one or more of these repos - you are free to make changes in all of them. When something does not work, it may very well be because of bugs not in the recipe itself but in the rewrite-csharp building blocks that created the LST, remoting protocol, or other issues. These are components of the same solution, and your goal is to track down underlying issues and fix them until the recipe works. 

## LST Modelling
The project is built with the goal of reusing as many of Java's LST models as possible when modelling constructs in other languages. This may mean more complexity in printer / parser to make it fit. When something cannot fit cleanly, language specific syntax classes may be created. 

LSTs do not have tokens for most keywords / punctionations. They are represented implicitly. Examine CSharpParser / CSharpPrinter for indepth examples on how to model.

- each "node" in a LST hierarchy has a property called "Space" that captures whitespace to the left of the that node. 
- the project contains special "wrapper" types which will be treated differently. These include JRightPadded, JLeftPadded and JContainer. Their purpose is to capture whitespace when it's not possible to do so via field "Space" on the node itself
- if there is a token that goes after the node (such as a comma), this whitespace is captured by wrapping the whole node in a JRightPadded type. For example a comma separated list of items such as this "a, b, c" would need to be modelled as JRightPadded because there could be space between the node text and the comma (ex: "a , b   , c"). When node is wrapped by JRightPadded, it is put into the Element property, and the space to the right of the node is put into the Space property for JRightPadded
- JContainer is a special "wrapper type" that contains a collection of JRightPadded elements. It is used to model a collection of elements separated by delimeter that also have tokens at the start and end end of the collection. For example: "   (a, b, c )". The whitespace on JContainer itself is used to represent whitespace to the LEFT of start token (in the example above whitespace before the opening bracket). In the context of CSharpParserVisitor, the value of JContainer.Space property would be captured via  "Format(Leading(openingToken))" (in example above, opening token would be '(')).  
- when creating instances of JRightPadded or JContainer, use static methods JRightPadded.Create and JContainer.Create
CSharpParserVisitor is a class is an implementation of Roslyn's CSharpSyntaxVisitor and is used to map Roslyn's syntax to LST. The following are details on how class CSharpParserVisitor is modeled. 
- if tokens exist in between node elements, then their whitespace between the node on the left side of the token is captured into JRightPadded and the node is placed inside it.
- use existing methods in in CSharpParserVisitor and CSharp printer as guide when asked to generate new overriding methods
- changes to any type that is inside java's core LSTs (inside J class) are never to be done without consulting with the user first. These are core across languages and changes are carefully controlled

The following rules apply to CSharpPrinter:
- no whitespace should ever be rendered from string constants. All whitespace is captured inside properties of type Space and on wrapping types that will appear on LST elements and should be rendered that way. For example if you're trying to render something like this p.Append(' ]'); where there is a space inside string, that would be incorrect. That space would have been contained in a node before this token and (almost certainly of type `RightPadded` under normal conditions) and would have been rendered due by earlier call to VisitRightPadded. Emitting a single hardcoded ' ' is almost always wrong as there can be many. These should all be go through VisitSpace for proper handling.

### Key Files
- CSharpParserVisitor - uses Roslyn to create LST representation
- CSharpPrinter - converts LST into c# source file
- SolutionParser - responsible for using MSBuildWorkspace to load sln/csproj for project building
- DotNetBuildStep (CLI repo) - entry point from CLI side for processing .net code and project files

## Writing Recipes
- ALWAYS use type attestation when targeting specific API methods. NEVER rely on only syntax name matches without confirming full type information
- Prefer using recipe template building blocks over raw LST manipulation where possible
- Aim for tests to have 100% code coverage for all recipe code paths
- Consult appropriate skill in recipe repos that contains more info on recipe writing best practices (writing-csharp-recipes)

## Iterating on Recipes

The goal is to ensure that recipes are doing the right thing based on their intent. This means that

- they should be accurate in code they target
- they should ideally leave the code compilable except in extreme edge cases that would make it too difficult to generalize
- they are "feature complete". That means nothing is deferred for "future improvements".

Generally this involves 

- Using some org made up for a sample set of git repos (usually open source) we're attempting to test recipes against
- Determining the starting state of that repo (try to compile it / run tests first time and take note of any preexisting issues)
- Using latest mod CLI / csharp recipes against to build / run against a sample set org of repos
- Critically looking at any issues / errors during this process. 
- Verifying that conversion succeeded by applying the the results of the recipe run against the repo (`mod git apply`)
- Verifying the repo works as expected
  - Compiles / no new warnings
  - Tests pass
  - Critically examine key areas of the app after each conversion by looking mainly csproj file, entrypoint/startup to make sure they align with latest best practices for how the code should be structured for the target state. So if there are new "prefered" stucture / api that should be used, then that's how it should be done. Example: top level statements if the version allows it in entrypoint, no Startup file as that approach has fallen out of favour, the current recommendation for using appropriate app builder (this has changed multiple times over the years). It is EXPECTED that you use inferrence to look at application code to verify:
    - Code was properly transformed where expected in files that were touched
    - No "damage" was done to existing logic by transformation
    - No transformations that SHOULD have been done to touched files but where not. In this case SHOULD referres to BOTH what recipes intended to do AND what would generally be expected final state of such file if the conversion was to new target state was done correctly. This means identifying gaps in existing recipes or missing recipes that should be added to do additional transformations where needed
  - Iterate on recipes / sdk / cli to make appropriate fixes. When issues are discovered, isolate them in unit tests, make the fix so the tests pass, then do full test on the actual repo where issue was detected
  - Reset the repo to original state between runs (`mod git reset --hard`)

### Parallelizing the work

When iterating on recipes, a lot of time is spent waiting for `mod build` or `mod run` to finish. Instead of running a single command at org level, run them individually at repo level when possible in parallel (up to 7 concurrent mod executions). Short commands that don't use rpc are not bound by this work (ex. `mod git`, `mod config`). Prioritize running mod commands that give you results to perform next steps: get recipes running on repos that have finished building lsts, once those are done start checking if they are good. 

Try to batch modifications / changes across runs so many issues are addressed simultaneously next time you run `mod build` / `mod run`. That means try to identify and fix as many issues as possible in a given working set FIRST, make necessary code changes, but deffer rebuilding CLI jar / recipes as to not interrupt any in builds / recipe runs. So instead of focusing on single issue at a time, try to identify and fix ALL issues you spot between rebuilds and verify that they are properly fixed in next run. 
