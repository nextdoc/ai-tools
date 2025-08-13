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
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net Socket)
           (java.io PushbackInputStream)
           (java.util UUID)))

(defn- log
  "Private logging function that can be disabled in production.
  Accepts any number of arguments and logs them via println when enabled.
  Comment out the println line to disable all debug output."
  [& _args]
  ;(apply println _args)  ; Comment this line to disable debug output
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

(defn- gen-id
  "Generates a unique ID for nREPL message correlation."
  []
  (str (UUID/randomUUID)))

(defn- read-until-done
  "Reads nREPL messages until receiving a 'done' status for the specified ID.
  Implements a 10-second timeout to prevent hanging.
  Returns a vector of all received message frames."
  ([in] (read-until-done in nil))  ; backward compat
  ([in wanted-id]
  (let [deadline (+ (System/currentTimeMillis) 10000)] ; 10 second timeout
    (loop [frames []]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for nREPL response" {:frames frames})))
      (let [m (read-msg in)
            frames (conj frames m)
            status (some-> (get m "status") (->> (map #(String. ^bytes %))) set)
            id (when-let [id-bytes (get m "id")] (String. ^bytes id-bytes))]
        (if (or (nil? wanted-id)  ; backward compat
                (and (= id wanted-id) (contains? status "done"))
                (and (nil? wanted-id) (contains? status "done")))
          frames
          (recur frames)))))))

(defn- bytes->str
  "Converts byte array to string, handling nil values gracefully.
  nREPL returns values as byte arrays that need string conversion."
  [b]
  (when b (String. ^bytes b "UTF-8")))

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
    (let [clone-id (gen-id)]
      (send! out {"op" "clone" "id" clone-id})
      (let [clone-frames (read-until-done in clone-id)
          _ (log "Clone response received, frames:" (count clone-frames))
            sid (or (bytes->str (get (last clone-frames) "new-session"))
                    (bytes->str (get (last clone-frames) "session")))] ;; belt+braces
        (log "Session ID:" sid)
        (f {:in in :out out :sid sid})))))

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
  (let [id (gen-id)]
    (send! out {"op" "eval" "code" code "ns" ns "session" sid "id" id})
    (read-until-done in id)))

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
  (log "Switching to Shadow-CLJS build:" build-key)
  ((:eval* io eval*) io "(require 'shadow.cljs.devtools.api)" "user")
  ((:eval* io eval*) io (str "(shadow.cljs.devtools.api/nrepl-select :" build-key ")") "user")
  
  ;; Smoke test to verify CLJS runtime
  (let [ping-frames ((:eval* io eval*) io "(do (aset js/globalThis \"__ND_PING__\" 42) :ok)" "cljs.user")
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
  (let [reporter-code (get-reporter-code)
        reporter-frames ((:eval* io eval*) io reporter-code "cljs.user")
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
  (let [require-expr (str "(do " 
                         (->> test-namespaces
                              (map #(format "(require '%s :reload)" %))
                              (clojure.string/join " "))
                         " :ok)")
        require-frames ((:eval* io eval*) io require-expr "cljs.user")
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
  (let [run-expr (str "(do (cljs.test/run-tests " 
                     (->> test-namespaces (map #(str "'" %)) (clojure.string/join " "))
                     ") :pending)")
        run-frames ((:eval* io eval*) io run-expr "cljs.user")
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
  (let [base-out (last-out frames)
        base-err (last-err frames)
        base-lines (if (empty? base-out) [] [base-out])
        err-lines (if (empty? base-err) [] [base-err])]
    {:values (select-keys m [:test :pass :fail :error])  ; Only include basic counts
     :out    (remove nil? base-lines)
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
  (let [deadline (+ (System/currentTimeMillis) 30000)] ; 30s timeout
    (log "Polling for test results...")
    (loop [attempt 0]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timed out waiting for test results" {})))
      (Thread/sleep 25)
      (let [frames ((:eval* io eval*) io "(let [a (aget js/globalThis \"__NEXTDOC_RESULT__\")]
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

(defn- create-accumulating-reader
  "Creates a read-until-done function that accumulates stdout/stderr to provided atoms.
  
  Args:
    out* - StringBuilder atom for stdout accumulation
    err* - StringBuilder atom for stderr accumulation
  
  Returns:
    Function that reads nREPL messages until 'done' while accumulating output"
  [out* err*]
  (fn [in wanted-id]
    (let [deadline (+ (System/currentTimeMillis) 10000)]
      (loop [frames []]
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timeout waiting for nREPL response" {:frames frames})))
        (let [m (read-msg in)
              frames (conj frames m)]
          ;; Accumulate any :out/:err to session-wide accumulators
          (when-let [s (bytes->str (get m "out"))]
            (.append ^StringBuilder @out* s))
          (when-let [s (bytes->str (get m "err"))]
            (.append ^StringBuilder @err* s))
          (let [status (some-> (get m "status") (->> (map bytes->str)) set)
                id (some-> (get m "id") bytes->str)]
            (if (and (= id wanted-id) (contains? status "done"))
              frames
              (recur frames))))))))

(defn- create-accumulating-eval
  "Creates an eval* function that uses the accumulating reader.
  
  Args:
    read-until-done-fn - The accumulating read-until-done function
  
  Returns:
    Function that evaluates code while accumulating output"
  [read-until-done-fn]
  (fn [{:keys [in out sid]} code ns]
    (let [id (gen-id)]
      (send! out {"op" "eval" "code" code "ns" ns "session" sid "id" id})
      (read-until-done-fn in id))))

(defn- drain-remaining-output
  "Drains any remaining async output from the nREPL connection.
  
  Args:
    io - Connection context
    out* - StringBuilder atom for stdout accumulation  
    err* - StringBuilder atom for stderr accumulation
    timeout-ms - How long to wait for additional output (default 200ms)"
  ([io out* err*] (drain-remaining-output io out* err* 200))
  ([io out* err* timeout-ms]
   (try
     (let [quiet-deadline (+ (System/currentTimeMillis) timeout-ms)]
       (loop []
         (if (< (System/currentTimeMillis) quiet-deadline)
           (do
             ;; Only read if data is available to avoid blocking
             (when (> (.available (:in io)) 0)
               (when-let [m (try (read-msg (:in io)) (catch Exception _ nil))]
                 (when-let [s (bytes->str (get m "out"))]
                   (.append ^StringBuilder @out* s))
                 (when-let [s (bytes->str (get m "err"))]
                   (.append ^StringBuilder @err* s))))
             ;; Small sleep to avoid busy waiting
             (Thread/sleep 10)
             (recur))
           nil)))
     (catch Exception _ nil))))

(defn- finalize-result-with-accumulated-output
  "Combines test results with accumulated session output.
  
  Args:
    result - Test result map from poll-for-test-results
    out* - StringBuilder atom containing accumulated stdout
    err* - StringBuilder atom containing accumulated stderr
  
  Returns:
    Enhanced result map with :out and :err from session accumulation"
  [result out* err*]
  (-> result
      (assoc :out (let [s (str @out*)] (cond-> [] (seq s) (conj s))))
      (assoc :err (let [s (str @err*)] (cond-> [] (seq s) (conj s))))))

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
  (let [build-key (name build-id)]
    (with-session port
      (fn [io]
        ;; Create session-wide output accumulators
        (let [out* (atom (StringBuilder.))
              err* (atom (StringBuilder.))
              ;; Create accumulating reader and eval functions
              accumulating-reader (create-accumulating-reader out* err*)
              accumulating-eval (create-accumulating-eval accumulating-reader)
              ;; Create IO context with accumulating eval function  
              io-with-accumulation (assoc io :eval* accumulating-eval)]
          
          ;; Run the test workflow with output accumulation
          (switch-to-cljs-build io-with-accumulation build-key)
          (install-test-reporter io-with-accumulation)
          (require-test-namespaces io-with-accumulation test-namespaces)
          (start-test-execution io-with-accumulation test-namespaces)
          (let [result (poll-for-test-results io-with-accumulation)]
            ;; Drain any remaining async output
            (drain-remaining-output io out* err*)
            ;; Return result with accumulated output
            (finalize-result-with-accumulated-output result out* err*)))))))
