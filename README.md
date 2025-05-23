# NextDoc Tools

Babashka tasks for running tests via nREPL connections.

## Overview

NextDoc Tools provides utilities for connecting to nREPL servers and running tests.
It's designed to work with Babashka, making it easy to run tests from the command line or programmatically.

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

### Running Tests

Run the task with options to run your test(s)

The task will return a 0 exit code if the tests pass.

## License

Copyright © 2025

Distributed under the Eclipse Public License version 1.0.
