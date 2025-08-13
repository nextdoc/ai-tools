(ns io.nextdoc.shadow-nrepl
  "Custom bencode nREPL client for Shadow-CLJS test execution with proper session management.
  
  Why a custom client is needed:
  - Shadow-CLJS nREPL requires sticky session management for CLJS evaluation
  - babashka/nrepl-client doesn't preserve session state between evaluations
  - Shadow-CLJS async test execution needs frame aggregation until 'done' status
  - Custom failure capture requires specific test reporter installation
  - Polling-based result collection needed for async cljs.test completion
  
  This implementation provides:
  - Raw bencode protocol handling with session persistence
  - Proper Shadow-CLJS build switching and runtime verification
  - Custom test reporter with detailed failure information capture
  - Async result polling with timeout handling
  - Modular helper functions for maintainable code structure"
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

(defn- log
  "Private logging function that can be disabled in production.
  Accepts any number of arguments and logs them via println when enabled.
  Comment out the println line to disable all debug output."
  [& args]
  ;(apply println args)  ; Comment this line to disable debug output
  nil)

(defn- send!
  "Sends a bencode message to the nREPL output stream.
  Writes the message and flushes the output stream immediately."
  [out msg] 
  (bencode/write-bencode out msg) 
  (.flush out))

(defn- read-msg
  "Reads a single bencode message from the nREPL input stream.
  Returns the decoded message as a map."
  [in]   
  (bencode/read-bencode in))

(defn- read-until-done
  "Reads nREPL messages until receiving a 'done' status.
  Implements a 10-second timeout to prevent hanging.
  Returns a vector of all received message frames."
  [in]
  (let [deadline (+ (System/currentTimeMillis) 10000)] ; 10 second timeout
    (loop [frames []]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for nREPL response" {:frames frames})))
      (let [m (read-msg in)
            frames (conj frames m)]
        (if (some #{"done"} (map #(String. %) (get m "status"))) 
          frames 
          (recur frames))))))

(defn- bytes->str
  "Converts byte array to string, handling nil values gracefully.
  nREPL returns values as byte arrays that need string conversion."
  [b]
  (when b (String. b)))

(defn- last-val
  "Extracts the last evaluation result value from nREPL message frames.
  Searches through frames for 'value' keys and returns the final one as a string."
  [frames] 
  (some->> frames (keep #(get % "value")) last bytes->str))

(defn- last-out
  "Concatenates all stdout output from nREPL message frames.
  Collects 'out' values from all frames and joins them into a single string."
  [frames] 
  (apply str (keep #(bytes->str (get % "out")) frames)))

(defn- last-err
  "Concatenates all stderr output from nREPL message frames.
  Collects 'err' values from all frames and joins them into a single string."
  [frames] 
  (apply str (keep #(bytes->str (get % "err")) frames)))

(defn- with-session
  "Establishes an nREPL connection and clones a session for test execution.
  
  Creates a socket connection to the nREPL server, clones a session to maintain
  state consistency, and executes the provided function with the connection context.
  The session ID is preserved throughout the function execution for sticky session
  management required by Shadow-CLJS.
  
  Args:
    port - The nREPL server port number
    f - Function to execute with the connection context map containing :in, :out, :sid
  
  Returns:
    Result of executing function f with the connection context"
  [port f]
  (with-open [sock (Socket. "127.0.0.1" port)
              in   (PushbackInputStream. (.getInputStream sock))
              out  (.getOutputStream sock)]
    ;; Set socket timeout to prevent hanging
    (.setSoTimeout sock 10000) ; 10 second timeout
    (log "Connected to nREPL, cloning session...")
    ;; clone session
    (send! out {"op" "clone"})
    (let [clone-frames (read-until-done in)
          _ (log "Clone response received, frames:" (count clone-frames))
          sid (or (bytes->str (get (last clone-frames) "new-session"))
                  (bytes->str (get (last clone-frames) "session")))] ;; belt+braces
      (log "Session ID:" sid)
      (f {:in in :out out :sid sid}))))

(defn- eval*
  "Evaluates Clojure/ClojureScript code in a specific namespace via nREPL.
  
  Sends an eval operation to the nREPL server using the provided session ID
  and waits for all response frames until receiving 'done' status.
  This is the core primitive for all code evaluation in the custom client.
  
  Args:
    io-map - Map containing :in (input stream), :out (output stream), :sid (session ID)
    code - String of Clojure/ClojureScript code to evaluate
    ns - Target namespace for evaluation (e.g., 'cljs.user', 'user')
  
  Returns:
    Vector of all nREPL response frames until completion"
  [{:keys [in out sid]} code ns]
  (send! out {"op" "eval" "code" code "ns" ns "session" sid})
  (read-until-done in))

(defn- switch-to-cljs-build
  "Switches the nREPL session to the specified Shadow-CLJS build environment.
  
  Performs the necessary setup to connect to a Shadow-CLJS build's JavaScript runtime:
  1. Requires shadow.cljs.devtools.api namespace
  2. Selects the specified build (e.g., :dev, :test) 
  3. Verifies CLJS runtime connectivity with a smoke test
  
  This is essential for ClojureScript test execution as it establishes the
  connection to the JavaScript environment where tests will actually run.
  
  Args:
    io - Connection context map with :in, :out, :sid keys
    build-key - String name of the Shadow-CLJS build to connect to
  
  Throws:
    ex-info if unable to connect to the CLJS runtime"
  [io build-key]
  "Switch the nREPL session to the specified Shadow-CLJS build."
  (log "Switching to Shadow-CLJS build:" build-key)
  (eval* io "(require 'shadow.cljs.devtools.api)" "user")
  (eval* io (str "(shadow.cljs.devtools.api/nrepl-select :" build-key ")") "user")
  
  ;; Smoke test to verify CLJS runtime
  (let [ping-frames (eval* io "(do (aset js/globalThis \"__ND_PING__\" 42) :ok)" "cljs.user")
        ping-val (last-val ping-frames)]
    (when (not= ping-val ":ok")
      (throw (ex-info "Failed to switch to CLJS runtime" {:frames ping-frames})))
    (log "✓ Successfully switched to CLJS runtime")))

(defn- get-reporter-code
  "Loads the ClojureScript test reporter code from an external resource file.
  
  Reads the test reporter implementation from the classpath resource at
  'io/nextdoc/cljs/test_reporter.cljs'. This external file approach improves
  maintainability by separating the CLJS code from the Clojure implementation
  and provides proper IDE support for the ClojureScript code.
  
  Returns:
    String containing the complete CLJS test reporter code
  
  Throws:
    IOException if the resource file cannot be found or read"
  []
  "Load the CLJS test reporter code from external file."
  (slurp (io/resource "io/nextdoc/cljs/test_reporter.cljs")))

(defn- install-test-reporter
  "Installs a custom ClojureScript test reporter for detailed failure capture.
  
  Evaluates the test reporter code in the ClojureScript environment to:
  1. Override cljs.test reporting methods for :fail, :error, and :summary events
  2. Capture detailed failure information including context, expected/actual values
  3. Store results in global state for later retrieval via polling
  
  The custom reporter is necessary because default cljs.test output goes to
  the browser console and isn't easily captured via nREPL.
  
  Args:
    io - Connection context map with :in, :out, :sid keys
  
  Throws:
    ex-info if the reporter installation fails"
  [io]
  "Install the test reporter that captures results and failure details in global state."
  (let [reporter-code (get-reporter-code)
        reporter-frames (eval* io reporter-code "cljs.user")
        reporter-val (last-val reporter-frames)]
    (log "Reporter result:" reporter-val)
    (when (nil? reporter-val)
      (throw (ex-info "Failed to install test reporter" {:frames reporter-frames})))
    (log "✓ Installed test reporter")))

(defn- require-test-namespaces
  "Requires and reloads all specified test namespaces in the ClojureScript environment.
  
  Dynamically builds and evaluates a require expression that loads all test
  namespaces with :reload to ensure fresh definitions. This is essential for
  test execution as it makes the test functions available in the CLJS runtime.
  
  Args:
    io - Connection context map with :in, :out, :sid keys  
    test-namespaces - Sequence of namespace symbols/strings to require
  
  Throws:
    ex-info if any namespace fails to load"
  [io test-namespaces]
  "Require all the test namespaces for execution."
  (let [require-expr (str "(do " 
                         (->> test-namespaces
                              (map #(format "(require '%s :reload)" %))
                              (clojure.string/join " "))
                         " :ok)")
        require-frames (eval* io require-expr "cljs.user")
        require-val (last-val require-frames)]
    (log "Require result:" require-val)
    (when (nil? require-val)
      (throw (ex-info "Failed to require test namespaces" {:frames require-frames})))
    (log "✓ Required test namespaces:" test-namespaces)))

(defn- start-test-execution
  "Initiates non-blocking test execution for the specified namespaces.
  
  Builds and evaluates a cljs.test/run-tests expression that starts test
  execution asynchronously. The function returns immediately while tests
  run in the background, with results captured by the custom test reporter.
  
  Args:
    io - Connection context map with :in, :out, :sid keys
    test-namespaces - Sequence of namespace symbols/strings to test
  
  Throws:
    ex-info if test execution fails to start"
  [io test-namespaces]
  "Start the test execution (non-blocking)."
  (let [run-expr (str "(do (cljs.test/run-tests " 
                     (->> test-namespaces (map #(str "'" %)) (clojure.string/join " "))
                     ") :pending)")
        run-frames (eval* io run-expr "cljs.user")
        run-val (last-val run-frames)]
    (log "Run result:" run-val)
    (when (nil? run-val)
      (throw (ex-info "Failed to start tests" {:frames run-frames})))
    (log "✓ Started test execution")))

(defn- display-test-results
  "Pretty prints test results to the terminal wrapped in XML result tags.
  
  Formats test results for human-readable terminal display using clojure.pprint
  when the result is a map, or plain println for non-map results. The XML tags
  facilitate programmatic parsing of test output by external tools.
  
  Args:
    m - Test result map or other data structure to display"
  [m]
  "Pretty print test results for terminal display."
  (println "\n<results>")
  (if (map? m)
    (pprint/pprint m)
    (println m))
  (println "</results>"))

(defn- format-test-output
  "Formats test results and nREPL output for return to the main test runner.
  
  Processes test results and nREPL frame data to create a standardized output
  format compatible with the main test runner interface. Extracts failure details
  and formats them for display, combining base output with detailed failure information.
  
  Args:
    m - Test result map containing counts and failure details
    frames - nREPL response frames containing stdout/stderr output
  
  Returns:
    Map with :values (test counts), :out (formatted output lines), :err (error lines)"
  [m frames]
  "Format test results for return to the main test runner."
  (let [base-out (last-out frames)
        base-err (last-err frames)
        base-lines (if (empty? base-out) [] [base-out])
        failure-lines (when (seq (:failures m))
                        (concat
                          ["=== DETAILED FAILURES ==="]
                          (mapcat (fn [failure]
                                    [(str (:type failure) " in " (:testing-vars failure))
                                     (when (seq (:testing-contexts failure))
                                       (str "Context: " (clojure.string/join " > " (:testing-contexts failure))))
                                     (when (:message failure)
                                       (str "Message: " (:message failure)))
                                     (str "Expected: " (:expected failure))
                                     (str "Actual:   " (:actual failure))
                                     ""]) ; Empty line between failures
                                  (:failures m))))
        all-out-lines (if failure-lines
                        (concat base-lines failure-lines)
                        base-lines)
        err-lines (if (empty? base-err) [] [base-err])]
    {:values (select-keys m [:test :pass :fail :error])  ; Only include basic counts
     :out    (remove nil? all-out-lines)
     :err    err-lines}))

(defn- poll-for-test-results
  "Polls the ClojureScript global state for test completion with timeout handling.
  
  Continuously checks for test results stored in the global __NEXTDOC_RESULT__ atom
  by the custom test reporter. Uses a 30-second timeout to prevent hanging and
  implements double EDN parsing to handle string-wrapped results from the CLJS runtime.
  
  The polling approach is necessary because ClojureScript test execution is
  asynchronous and results aren't immediately available after starting tests.
  
  Args:
    io - Connection context map with :in, :out, :sid keys
  
  Returns:
    Formatted test output map with :values, :out, and :err keys
  
  Throws:
    ex-info if timeout is reached before results are available"
  [io]
  "Poll for test results with timeout and return the final result map."
  (let [deadline (+ (System/currentTimeMillis) 30000)] ; 30s timeout
    (log "Polling for test results...")
    (loop [attempt 0]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timed out waiting for test results" {})))
      (Thread/sleep 25)
      (let [frames (eval* io "(let [a (aget js/globalThis \"__NEXTDOC_RESULT__\")]
                                (if a (pr-str @a) ::nil))" "cljs.user")
            vstr   (last-val frames)]
        (when (= 0 (mod attempt 40)) ; Print every second
          (log "Poll attempt" attempt "result:" vstr))
        (if (or (nil? vstr) (= vstr "::nil"))
          (recur (inc attempt))
          (let [first-read (edn/read-string vstr)
                m (if (string? first-read)
                    (try 
                      (edn/read-string first-read)
                      (catch Exception e
                        (log "Second read failed:" (.getMessage e))
                        first-read))
                    first-read)]
            (log "✓ Test results:" m)
            (display-test-results m)
            (format-test-output m frames)))))))

(defn run-shadow-tests
  "Main entry point for running ClojureScript tests via Shadow-CLJS nREPL.
  
  Orchestrates the complete test execution workflow:
  1. Establishes nREPL session with proper session management
  2. Switches to the specified Shadow-CLJS build environment  
  3. Installs custom test reporter for failure detail capture
  4. Requires all test namespaces with reload
  5. Starts asynchronous test execution
  6. Polls for results with timeout handling
  
  This is the main public API function called by the CLI test runner.
  
  Args:
    port - nREPL server port number
    build-id - Shadow-CLJS build identifier (keyword or string)
    test-namespaces - Sequence of test namespace symbols/strings
  
  Returns:
    Test result map with :values (counts), :out (output), :err (errors)
  
  Throws:
    Various ex-info exceptions for connection, build switching, or test execution failures"
  [port build-id test-namespaces]
  "Runs tests in Shadow-CLJS using raw nREPL protocol with proper session management."
  (let [build-key (name build-id)]
    (with-session port
      (fn [io]
        (switch-to-cljs-build io build-key)
        (install-test-reporter io)
        (require-test-namespaces io test-namespaces)
        (start-test-execution io test-namespaces)
        (poll-for-test-results io)))))
