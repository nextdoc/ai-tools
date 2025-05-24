# Babashka AI Coding Tools

Babashka tasks for running tests via nREPL connections.

## Overview

Provides utilities for connecting to nREPL servers and running tests.
Runs in Babashka, making it fast & easy for Agents to run tests from the command line.

## Features

- Connect to nREPL servers
- Run tests in specified namespaces
- Reload namespaces before running tests
- Capture test output (stdout/stderr)

## Installation

The REPL where your tests will run must have [tools.namespace](https://github.com/clojure/tools.namespace) installed

Add the following dependencies to your bb.edn (check for latest commit)

```clojure
{babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client"
                        :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}
 nextdoc/ai-tools      {:git/url "https://github.com/nextdoc/ai-tools.git"
                        :git/sha "f2d7c7593e4aa29ae8d8c78eaf350c902b9c68d4"}}
```

Add this task (from the bb.edn in this project) to your bb.edn

```clojure
{nrepl:test {:requires [[io.nextdoc.tools :as tools]]
             :doc      "Run a test in the JVM using an nrepl connection i.e. fast test runner from cli"
             :task     (System/exit (tools/run-tests-task *command-line-args*))}}
```

Run the task to confirm installation and see the options

```bash
bb nrepl:test
```

### Command Line Options

- `-n, --namespaces`: Comma-separated list of test namespaces to run (required)
- `-p, --port-file`: Path to the file containing the nREPL port (default: ".nrepl-port")

## Usage

The task is intended to be used with AI coding agents as a test runner.
Add this text to your agent instructions...

```
Run tests using this command `bb nrepl:test -n <fully qualified test namespace>`
```

Ensure the REPL in your project is started.

## Test runner

Run the task with valid options to run your test(s)

The task will return a zero exit code if the tests pass.
The return code is used by most coding agents to determine if a test passed or not.

If standard out or standard error is present this will be echoed to the terminal.
This allows coding agents to see logs and exceptions from test runs.

## Alternatives

This tool is designed to provide fast test execution and feedback for coding agents without need for any extra
infrastructure. Just a simple command line task for a coding agent to invoke.

For the next level of sophistication you probably want to look for an MCP server.
There are quite a few MCP servers that allow REPL connections and evaluations.

## Is it safe?

This is a **buyer beware** situation. Any coding agent that can write and evaluate code on your computer has associated
risks.

The responsibility for checking the code being run is on you.

You might think that providing access to a REPL raises the risk of unwanted side effects.
This is partially true but most coding agents can also write bash scripts and pose the same level of danger from running
those scripts.

## License

Copyright © 2025

Distributed under the Eclipse Public License version 1.0.
