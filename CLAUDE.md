# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Babashka-based AI coding tool that provides utilities for connecting to nREPL servers and running tests. The tool is designed for Test-Driven Development (TDD) workflows with AI coding agents.

## Key Commands

### Primary Test Runner Command
```bash
bb nrepl:test -n <fully-qualified-test-namespace>
```

Options:
- `-n, --namespaces`: Comma-separated list of test namespaces or individual tests to run (required)
  - Full namespace: `my.project.core-test`
  - Individual test: `my.project.core-test/test-function-name`
  - Mixed: `my.project.core-test/specific-test,my.project.utils-test`
- `-d, --directories`: Comma-separated list of directories to scan for changes & reload before running tests (optional)  
- `-p, --port-file`: Path to the file containing the nREPL port (default: ".nrepl-port")
- `-f, --filter`: Filter stack traces to remove internal frames (default: true, set to false for full stack traces)

Examples:
```bash
# Run all tests in a namespace
bb nrepl:test -n my.project.core-test

# Run a specific test function
bb nrepl:test -n my.project.core-test/test-addition

# Mix individual tests and full namespaces
bb nrepl:test -n my.project.core-test/specific-test,my.project.utils-test -d src,dev

# Disable stack trace filtering for debugging
bb nrepl:test -n my.project.core-test --filter false
```

**Note:** Individual test execution is currently only supported for JVM Clojure tests, not ClojureScript.

## Architecture

### Core Components
- **io.nextdoc.tools**: Main namespace containing test runner logic (`src/io/nextdoc/tools.cljc`)
- **nREPL Integration**: Uses babashka/nrepl-client for REPL connectivity
- **Test Execution**: Leverages clojure.tools.namespace for hot reloading and clojure.test for test execution

### Key Functions
- `run-tests-task`: Main entry point that parses CLI args and orchestrates test execution
- `run-tests`: Core test runner that reloads namespaces and executes tests via nREPL
- `read-port`: Reads nREPL port from file (defaults to `.nrepl-port`)

## TDD Workflow Integration

This tool is specifically designed for TDD workflows where:
1. Tests are written first
2. Code changes are made
3. Tests are run immediately via nREPL for fast feedback
4. The cycle repeats

### Prerequisites
- An active nREPL server must be running in the target project
- The target project must have `clojure.tools.namespace` available
- Port file (`.nrepl-port`) must exist and contain the nREPL port number

### Return Codes
- `0`: All tests passed
- Non-zero: Number of failed + error tests (used by AI agents to detect test failures)

## Development Notes

### Project Structure
```
src/io/nextdoc/tools.cljc  # Main implementation
bb.edn                     # Babashka configuration with nrepl:test task
deps.edn                   # Basic Clojure deps (IDE recognition)
```

### Output Handling
- stdout/stderr from tests are captured and displayed with `<stdout>` and `<stderr>` tags
- Reload status is printed to help debug namespace loading issues
- Test results include detailed failure/error counts
- Stack traces are filtered by default to remove internal frames (Java/Clojure/Babashka internals)
- Filtering indicators show how many frames were removed (e.g., "Stack trace (cleaned - 295 internal frames filtered)")
- Use `--filter false` to see full unfiltered stack traces when needed

## AI Agent Integration

This tool provides fast test execution feedback for AI coding agents without requiring additional infrastructure beyond a running nREPL server. It serves as a bridge between file-based code changes and REPL-based test execution.