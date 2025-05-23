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

Copy the task from the bb.edn in this project. Add it to your bb.edn

Run the task to confirm install and see the options

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

### Test runner

Run the task with options to run your test(s)

The task will return a 0 exit code if the tests pass.
This is used by most coding agents to determine if a test passed or not.

If standard out or standard error is present this will be echoed to the terminal.
This allows coding agents to see logs and exceptions from test runs.

## License

Copyright © 2025

Distributed under the Eclipse Public License version 1.0.
