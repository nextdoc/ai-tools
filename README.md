# Babashka AI Coding Tools

Babashka tasks for running tests via nREPL connections in both JVM Clojure and ClojureScript environments.

## Overview

Provides utilities for connecting to nREPL servers and running tests in both JVM Clojure and ClojureScript (via Shadow-CLJS) environments.

Runs in Babashka, making it fast & easy for AI agents to run tests from the command line with minimal setup.

Supports both traditional JVM test workflows and modern ClojureScript development with Shadow-CLJS builds.

## Features

### JVM Clojure (`nrepl:test`)
- Connect to nREPL servers
- Reload namespaces before running tests using tools.namespace
- Capture reload errors
- Run tests in specified namespaces or individual test functions
- Capture test output (stdout/stderr)

### ClojureScript via Shadow-CLJS (`nrepl:test-shadow`)
- Connect to Shadow-CLJS nREPL servers
- Switch to specified Shadow-CLJS builds with runtime verification
- Custom test reporter for detailed failure capture
- Async test execution with polling-based result collection
- Pretty-printed results in XML tags for easy parsing

## Unique value proposition

Why use this instead of other options?

- if you prefer a test driven workflow
- all changes are written to source before being sent to the REPL
- fresh nREPL connection for every test run ensures clean state
- can be used with AI agents that are cheaper e.g. Aider
- agent flexibility allows use of any coding model

## Installation

### Prerequisites

**For JVM Clojure testing:**
- The REPL where your tests will run must have [tools.namespace](https://github.com/clojure/tools.namespace) installed
- Active nREPL server (usually writes port to `.nrepl-port`)

**For ClojureScript testing:**
- Running Shadow-CLJS build with active nREPL server
- Shadow-CLJS writes nREPL port to `.shadow-cljs/nrepl.port`
- No additional dependencies required (uses Shadow-CLJS hot reloading)

### Setup

Add the following dependencies to your bb.edn (check for latest commit)

```clojure
babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client"
                       :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}
nextdoc/ai-tools {:git/url "https://github.com/nextdoc/ai-tools.git"
                  :git/sha "f30c664e96af56e8392ff4bd1fca6147d11f589a"}
```

Add both tasks (from the bb.edn in this project) to your bb.edn

```clojure
nrepl:test        {:requires [[io.nextdoc.tools :as tools]]
                   :doc      "Run a test in the JVM using an nrepl connection i.e. fast test runner from cli"
                   :task     (System/exit (tools/run-tests-task *command-line-args*))}

nrepl:test-shadow {:requires [[io.nextdoc.tools :as tools]]
                   :doc      "Run a test in Shadow-CLJS using an nrepl connection for ClojureScript testing"
                   :task     (System/exit (tools/run-cljs-tests-task *command-line-args*))}
```

Run the tasks to confirm installation and see the options

```bash
bb nrepl:test          # JVM Clojure
bb nrepl:test-shadow   # ClojureScript
```

### Command Line Options

#### JVM Clojure (`nrepl:test`)
- `-n, --namespaces`: Comma-separated list of test namespaces or individual tests to run (required)
  - Full namespace: `my.project.core-test`
  - Individual test: `my.project.core-test/test-function-name`
  - Mixed: `my.project.core-test/specific-test,my.project.utils-test`
- `-d, --directories`: Comma-separated list of directories to scan for changes & reload before running tests (optional)
- `-p, --port-file`: Path to the file containing the nREPL port (default: ".nrepl-port")

The --directories option is useful if some of your sources fail to reload cleanly using tools.namespace.

**Note:** Individual test execution is currently only supported for JVM Clojure tests, not ClojureScript.

#### ClojureScript (`nrepl:test-shadow`)
- `-n, --namespaces`: Comma-separated list of test namespaces to run (required)
- `-b, --build-id`: Shadow-CLJS build ID to connect to (optional, e.g., dev, test)
- `-p, --port-file`: Path to the file containing the nREPL port (default: ".shadow-cljs/nrepl.port")

## Usage

The tasks are intended to be used with AI coding agents as test runners.

### When to Use Which

- **Use `nrepl:test`** for JVM Clojure testing with traditional REPL workflows
- **Use `nrepl:test-shadow`** for ClojureScript testing via Shadow-CLJS builds

### Agent Instructions

If using a coding agent that uses markdown to know about the test runners,
add this text to your agent instructions...

```
# JVM Clojure tests
Run tests using this command `bb nrepl:test -n <fully qualified test namespace>`
Run individual test: `bb nrepl:test -n <namespace>/<test-function>`

# ClojureScript tests  
Run tests using this command `bb nrepl:test-shadow -n <fully qualified test namespace>`
Optionally specify build: `bb nrepl:test-shadow -n <namespace> -b <build-id>`
```

Ensure the appropriate REPL/build is running:
- **JVM**: Start your nREPL server (port in `.nrepl-port`)
- **ClojureScript**: Start Shadow-CLJS build (port in `.shadow-cljs/nrepl.port`)

## Test Runners

### JVM Clojure Examples
```bash
# Run all tests in a namespace
bb nrepl:test -n my.project.core-test

# Run a specific test function
bb nrepl:test -n my.project.core-test/test-addition

# Run multiple test functions from the same namespace
bb nrepl:test -n my.project.core-test/test-addition,my.project.core-test/test-subtraction

# Mix individual tests and full namespaces
bb nrepl:test -n my.project.core-test/specific-test,my.project.utils-test

# With directory reloading
bb nrepl:test -n my.project.core-test,my.project.utils-test -d src,dev
```

### ClojureScript Examples
```bash
bb nrepl:test-shadow -n my.project.core-test
bb nrepl:test-shadow -n my.project.core-test -b dev
bb nrepl:test-shadow -n my.project.core-test,my.project.utils-test
```

### Exit Codes and Output

Both tasks will return a zero exit code if the tests pass.
The return code is used by most coding agents to determine if a test passed or not.

If standard out or standard error is present this will be echoed to the terminal.
This allows coding agents to see logs and exceptions from test runs.

ClojureScript test results are wrapped in `<results>` XML tags for easier parsing by external tools.

## Workflows

The Test Runner task is designed for a TDD style development workflow.

Tests can be in their own namespace or it can be useful to create a fiddle namespace and have sample code and a test to
drive that code in the same file.

There are many AI development agents that can be used with this task.
What follows is the setup and workflow for the agents we have tested...

### Aider

Aider is a very precise AI development agent.
Precision means that you manage all the files in its context and which files are readable and writable.
This provides a high level of control but requires more manual work to manage the context.

In your .aider.conf.yaml file you will want...

```yaml
auto-commits: false
watch-files: true
# Choose the appropriate test command for your project:
test-cmd: bb nrepl:test -n your.test.namespace        # For JVM Clojure
# test-cmd: bb nrepl:test-shadow -n your.test.namespace # For ClojureScript
auto-test: true
yes-always: true
```

This will allow you to [add comments](https://aider.chat/docs/usage/watch.html#aider-in-your-ide) in any namespace.
Aider will:

- detect via its file watcher
- respond with answers and/or changes
- reload [some or all](https://github.com/nextdoc/ai-tools#command-line-options) of the changed namespaces
- fix any errors it notices if the updated source doesn't compile
- re-run the tests using the test runner task
- add the output from the test runner to its context
- fix any errors it notices if the test runner returns a non-zero exit code
- iterate on the change test loop until the test runner returns a zero exit code

Tips:

- use [/web](https://aider.chat/docs/usage/images-urls.html#web-pages) to load a API references and example code into
  the context
- or use /load to load many local and remote resources
- keep the context size under 30k. Iteration tends to slow down around this point. /clear and reload init context

The biggest downside of working with this level of automation is that there is no human in the loop interruption unless
Aider iterates until it makes the tests pass or it hits its maximum number of **reflections**.
You can inject yourself back into the loop by using CTRL-C at any time in the terminal.

The upside is this is a very lean and focused automated loop which you can control with a great deal of precision.
This provides the full agentic power of the model, but with the smallest amount of token consumption.

### Claude Code

Claude code represents a higher level of agentic coding compared to Aider.
When using this TDD style workflow, the following features provide increased benefit:

- Context is automatically managed and compacted when required
- More tools are available
- The agent can adjust the Babashka task invocation on its own or with your instruction i.e. run different tests without restart

Add the following instruction to your CLAUDE.md

```markdown
# Run single test via nREPL (fast iteration). Use this for "TDD mode" iteration.

# The developer will indicate when to operate in "TDD mode".

# More information on how to use this task is available at https://raw.githubusercontent.com/nextdoc/ai-tools/refs/heads/master/README.md

# JVM Clojure tests
bb nrepl:test -n <Fully qualified test namespace>
# Or run individual test:
bb nrepl:test -n <namespace>/<test-function>

# ClojureScript tests (Shadow-CLJS)
bb nrepl:test-shadow -n <Fully qualified test namespace>
```

This allows you to instruct the agent to use **TDD mode** with a specific test or fiddle namespace.
It will run this task and benefit from the speed and other values described above.

Tips:

- imbalanced parentheses happens less and less with higher power models. When you notice the agent in a loop with
  this it's often faster to interrupt and fix it manually. Then ask it to resume.
- To enjoy the same context specific benefit of an Aider AI comment, add a comment anywhere with as much detail as you
  need. Then instruct Claude to implement it using the specific line number. This provides the location and file
  context which improves the knowledge of the agent when it starts.

## Gotchas

It can be useful to instruct the agent to add logging or print lines to see what is happening when running tests.
If you have any core async and logging inside those go blocks then this can suppress standard out capture by the test
runner.
In this case you will see the standard out in your host REPL instead. You can paste it into the conversation with
the agent.

## Alternatives

This tool is designed to provide fast test execution and feedback for coding agents without need for any extra
infrastructure. Just a simple command line task for a coding agent to invoke.

It has a single tool for interacting with your REPL and that is a code-reloading test invocation.
A test can be used as a proxy for eval, so it is not limited to test workflows only.

If you want finer grained tools and more control then you need a more sophisticated integration.

For the next level of sophistication you probably want to look at
an [MCP server](https://github.com/bhauman/clojure-mcp/tree/main).

## Is it safe?

This is **buyer beware**. Any coding agent that can write and evaluate code on your computer has associated
risks.

The responsibility for checking the code being run is on you.

You might think that providing access to a REPL raises the risk of unwanted side effects.
This is partially true but most coding agents can also write bash scripts and pose the same level of danger from running
those scripts.

## License

Copyright © 2025

Distributed under the Eclipse Public License version 1.0.
