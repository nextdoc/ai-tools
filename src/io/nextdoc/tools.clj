(ns io.nextdoc.tools
  "Tools for invoking tests via nREPL connections.
   Supports both JVM (Clojure) and ShadowCLJS (ClojureScript) environments.
   
   Key features:
   - Run individual test functions using slash notation (namespace/test-function)
   - Smart filtering to avoid duplicate test execution
   - Stack trace filtering for cleaner error output
   - Mixed mode execution (individual tests + full namespaces)"
  (:require
    [babashka.cli :as cli]
    [babashka.nrepl-client :as nrepl]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [io.nextdoc.shadow-nrepl :as shadow-nrepl]))

;; Forward declaration for stack trace filtering function used in execute-test-code
(declare clean-test-output)

(defn read-port
  "SHARED: Reads a port number from a file and returns it as an integer.
   Prints a connection message to stdout.
   Used by both JVM and ShadowCLJS test runners."
  [port-file]
  (let [port (Integer/parseInt (slurp port-file))]
    (println "Connected:" port-file ">" port "...")
    port))

(defn- parse-test-entries
  "Parses namespace entries and separates them into namespaces and individual tests.
   Uses slash notation to identify individual tests (e.g., 'my.ns/test-function').
   Returns a map with :namespaces and :individual-tests keys."
  [entries]
  (reduce (fn [acc entry]
            (if (str/includes? entry "/")
              (let [[ns-name test-name] (str/split entry #"/" 2)]
                (update acc :individual-tests conj {:namespace (symbol ns-name) 
                                                    :test-name (symbol test-name)}))
              (update acc :namespaces conj (symbol entry))))
          {:namespaces [] :individual-tests []}
          entries))

(defn- generate-namespace-test-code
  "Generates the code string for running tests in full namespaces."
  [namespaces]
  (str "(binding [*out* (java.io.StringWriter.)
                  *err* (java.io.StringWriter.)]
          (let [result (apply clojure.test/run-tests '" (pr-str namespaces) ")]
            {:result result
             :out (str *out*)
             :err (str *err*)}))"))

(defn- make-var-reference
  "Creates a var reference string for a test.
   Uses the test name as-is since Clojure allows hyphens in function names."
  [{:keys [namespace test-name]}]
  (str "#'" namespace "/" (name test-name)))

(defn- generate-individual-test-code
  "Generates the code string for running a single individual test function using run-test-var."
  [{:keys [namespace test-name] :as test}]
  (str "(binding [*out* (java.io.StringWriter.)
                  *err* (java.io.StringWriter.)]
          (do (require '" namespace ")
              (let [result (clojure.test/run-test-var " (make-var-reference test) ")]
                {:result result
                 :out (str *out*)
                 :err (str *err*)})))"))

(defn- execute-test-code
  "Executes test code via nREPL and returns the parsed result.
   Captures all output (including test errors) for optional filtering.
   When filter? is true, applies stack trace filtering for cleaner output.
   Returns nil if the execution fails, which will be filtered out during result combination."
  [port code filter?]
  ;; Uncomment for debugging:
  ;; (println "\n===== Executing test code: =====")
  ;; (println code)
  ;; (println "=================================\n")
  (try
    ;; Capture all output from nREPL (including what eval-expr prints to *out*)
    (let [output-buffer (java.io.StringWriter.)
          result (binding [*out* output-buffer]
                   ;; eval-expr prints server output to *out*, which we're capturing
                   (let [response (nrepl/eval-expr {:port port :expr code})
                         {:keys [vals err out]} response]
                     ;; Return both the response and captured output
                     {:response response
                      :captured-output (str output-buffer)
                      :vals vals
                      :err err 
                      :out out}))
          {:keys [captured-output vals err]} result]
      
      ;; Print the captured output (filtered or unfiltered based on filter? flag)
      (when (not (str/blank? captured-output))
        (if filter?
          (let [cleaned-lines (clean-test-output captured-output)]
            (doseq [line cleaned-lines]
              (println line)))
          (print captured-output)))
      
      ;; Uncomment for debugging:
      ;; (when err
      ;;   (println "nREPL error:" err))
      ;; (when out
      ;;   (println "nREPL structured output:" out))
      
      ;; Parse and return the test result
      (cond
        (and vals (seq vals)) (edn/read-string (first vals))
        err nil
        :else (do
                (println "Warning: No result from nREPL execution")
                nil)))
    (catch Exception e
      (println "Failed to execute test code:" (.getMessage e))
      nil)))

(defn- merge-test-result
  "Merges a single test result into the accumulator."
  [acc {:keys [result out err]}]
  (if result
    (-> acc
        (update :test (fnil + 0) (:test result))
        (update :pass (fnil + 0) (:pass result))
        (update :fail (fnil + 0) (:fail result))
        (update :error (fnil + 0) (:error result))
        (update :out (fnil into []) (if out (clojure.string/split-lines out) []))
        (update :err (fnil into []) (if err (clojure.string/split-lines err) [])))
    acc))

(defn- combine-test-results
  "Combines multiple test results into a single result map."
  [results]
  (reduce merge-test-result
          {:test 0 :pass 0 :fail 0 :error 0 :out [] :err []}
          (remove nil? results)))

;; Stack trace filtering utilities for cleaner test output
;; These utilities filter stack traces from nREPL test execution to remove:
;; - Java/Clojure internals (java.lang.*, clojure.lang.*, clojure.test$*)
;; - Babashka/SCI internals (sci.lang.*, sci.impl.*, babashka.main$*)
;; - Generated evaluation forms (user$eval*)
;;
;; The filtering captures nREPL output during eval-expr execution and processes
;; it before displaying to the user, providing much cleaner error diagnostics.

(defn- looks-like-stack-frame?
  "Detects if a line looks like a stack trace frame.
   Handles both 'at ...' format and bare 'package.Class.method (File:line)' format."
  [line]
  (let [trimmed (str/trim line)]
    (or (str/starts-with? trimmed "at ")
        ;; Match pattern like: "package.Class.method (File:123)"
        (re-find #"[\w\.\$]+\s+\([^)]+:\d+\)" trimmed))))

(defn- clean-stack-trace
  "Cleans and formats a stack trace for more readable test output.
   Removes Java/Clojure/Babashka internals, detects recursion patterns, 
   limits output length, and adds filtering indicators showing how many frames were removed."
  [stack-trace-lines]
  (let [;; Parse stack frames from the string output, handling various formats
        raw-frames (->> stack-trace-lines
                        (map str/trim)
                        (filter (fn [line]
                                  (or (str/starts-with? line "at ")
                                      (and (re-find #"[\w\$]+" line)
                                           (re-find #"\(" line)))))
                        (map (fn [line]
                               (if (str/starts-with? line "at ")
                                 (subs line 3)  ; Remove "at " prefix
                                 line))))
        
        ;; Categorize frames to preserve application code
        is-application-frame? (fn [frame]
                                ;; Keep if it's user code (not java/clojure/common libs)
                                ;; AND has a .clj/.cljs file reference
                                (and (not (or (str/includes? frame "java.")
                                              (str/includes? frame "clojure.")
                                              (str/includes? frame "sci.")
                                              (str/includes? frame "babashka.")
                                              (str/includes? frame "nrepl.")
                                              (str/includes? frame "malli.core$")
                                              (str/includes? frame "malli.instrument$")))
                                     (re-find #"\.cljs?:\d+\)" frame)))

        ;; Separate filtering - keep important frames
        filtered-frames (->> raw-frames
                             (remove (fn [frame]
                                       ;; ALWAYS keep application frames
                                       (when-not (is-application-frame? frame)
                                         (or
                                           ;; Filter out Clojure/Java internals
                                           (str/includes? frame "java.lang.Exception")
                                           (str/includes? frame "java.lang.RuntimeException")
                                           (str/includes? frame "clojure.lang.ExceptionInfo")
                                           (str/includes? frame "clojure.lang.AFn")
                                           (str/includes? frame "clojure.lang.RestFn")
                                           (str/includes? frame "clojure.lang.LazySeq")
                                           (str/includes? frame "clojure.lang.RT")
                                           (str/includes? frame "clojure.lang.Compiler")
                                           (str/includes? frame "clojure.test$")
                                           (str/includes? frame "clojure.core$apply")
                                           (str/includes? frame "clojure.core$map")
                                           (str/includes? frame "clojure.core$eval")
                                           (str/includes? frame "clojure.core$with_bindings")
                                           (str/includes? frame "clojure.main$repl")

                                           ;; Filter out Babashka internals (SCI - Small Clojure Interpreter)
                                           (str/includes? frame "sci.lang.")
                                           (str/includes? frame "sci.impl.")
                                           (str/includes? frame "sci.core$")
                                           (str/includes? frame "babashka.main$")
                                           (str/starts-with? frame "user$eval") ; Generated eval forms
                                           )))))
        
        ;; Check for recursion pattern
        recursion? (and (> (count filtered-frames) 20)
                       (let [sample (take 10 filtered-frames)]
                         (some (fn [i] 
                                 (= sample (take 10 (drop (* i 10) filtered-frames))))
                               (range 1 3))))
        
        ;; Calculate filtering stats
        filtered-count (- (count raw-frames) (count filtered-frames))
        
        ;; Format output with filtering information
        header (if (pos? filtered-count)
                 (str "    Stack trace (cleaned - " filtered-count " internal frames filtered):")
                 "    Stack trace:")
        
        cleaned-frames (if recursion?
                         (concat [header]
                                (map #(str "    " %) (take 8 filtered-frames))
                                ["    ... [Recursion detected - pattern repeats] ..."]
                                (map #(str "    " %) (take-last 2 filtered-frames)))
                         (concat [header]
                                (map #(str "    " %) (take 20 filtered-frames))))]
    cleaned-frames))

(defn- clean-test-output
  "Cleans test output to remove excessive stack traces and noise.
   Preserves exception data (like Malli validation errors) that appears
   between the 'actual:' line and the start of the stack trace."
  [output-lines]
  (let [lines (if (string? output-lines)
                (str/split-lines output-lines)
                output-lines)]
    (loop [result []
           remaining lines
           in-exception-data? false
           in-stack-trace? false
           stack-trace-buffer []
           error-header nil]
      (if (empty? remaining)
        (cond
          ;; If we ended while processing stack trace, clean and add it
          in-stack-trace?
          (-> result
              (into (clean-stack-trace stack-trace-buffer))
              (conj ""))  ; Add blank line after stack trace

          ;; Otherwise return as-is
          :else result)

        (let [line (first remaining)
              trimmed (str/trim line)]
          (cond
            ;; Testing header line - always keep
            (str/starts-with? trimmed "Testing ")
            (recur (conj result "" line) (rest remaining) false false [] nil)

            ;; Start of error or failure - save header
            (or (str/starts-with? trimmed "ERROR in")
                (str/starts-with? trimmed "FAIL in"))
            (recur (conj result "" line) (rest remaining) false false [] line)

            ;; Error description lines after ERROR/FAIL
            (and error-header
                 (not (str/starts-with? trimmed "expected:"))
                 (not (str/starts-with? trimmed "actual:")))
            (recur (conj result line) (rest remaining) false false [] error-header)

            ;; Expected line - keep and prepare for exception data
            (str/starts-with? trimmed "expected:")
            (recur (conj result line) (rest remaining) false false [] nil)

            ;; Actual line - keep and start looking for exception data
            (str/starts-with? trimmed "actual:")
            (recur (conj result line) (rest remaining) true false [] nil)

            ;; Exception data phase: keep lines until we hit a stack frame
            (and in-exception-data?
                 (not in-stack-trace?)
                 (not (looks-like-stack-frame? line)))
            (if (or (str/blank? trimmed)
                    (str/starts-with? trimmed "Ran ")
                    (str/starts-with? trimmed "ERROR ")
                    (str/starts-with? trimmed "FAIL ")
                    (str/starts-with? trimmed "Testing "))
              ;; End of exception without stack trace
              (recur (conj result line) (rest remaining) false false [] nil)
              ;; Exception data - keep it
              (recur (conj result line) (rest remaining) true false [] nil))

            ;; First stack frame detected - switch to stack trace mode
            (and in-exception-data?
                 (not in-stack-trace?)
                 (looks-like-stack-frame? line))
            (recur result (rest remaining) false true [line] nil)

            ;; Stack trace lines - collect in buffer
            (and in-stack-trace?
                 (or (looks-like-stack-frame? line)
                     (and (not (str/blank? trimmed))
                          (not (str/starts-with? trimmed "Ran "))
                          (not (str/starts-with? trimmed "Testing "))
                          (not (str/starts-with? trimmed "ERROR "))
                          (not (str/starts-with? trimmed "FAIL ")))))
            (recur result (rest remaining) false true (conj stack-trace-buffer line) nil)

            ;; End of stack trace - clean buffer and add to result
            (and in-stack-trace?
                 (or (str/blank? trimmed)
                     (str/starts-with? trimmed "Ran ")
                     (str/starts-with? trimmed "ERROR ")
                     (str/starts-with? trimmed "FAIL ")
                     (str/starts-with? trimmed "Testing ")))
            (let [cleaned (clean-stack-trace stack-trace-buffer)
                  new-result (into result cleaned)]
              ;; Re-process current line as it's not part of stack trace
              (recur new-result remaining false false [] nil))

            ;; Test summary line - always keep
            (re-find #"Ran \d+ tests containing \d+ assertions" trimmed)
            (recur (conj result "" line) (rest remaining) false false [] nil)

            ;; Regular line
            :else
            (recur (if (str/blank? trimmed)
                     result  ; Skip extra blank lines
                     (conj result line))
                   (rest remaining)
                   false
                   false
                   []
                   nil)))))))

(defn run-tests
  "JVM: Runs tests in the specified test namespaces using an nREPL connection.
   Supports both full namespaces and individual test functions using slash notation.
   Reloads the test namespaces before running tests using clojure.tools.namespace.
   Applies smart filtering to avoid duplicate execution when both individual tests 
   and their containing namespaces are specified. Stack traces can be filtered for readability.
   Returns a map containing test results, stdout, and stderr.
   This function is specific to JVM/Clojure environments."
  [port directories test-entries filter?]
  (try

    ; JVM: Reload any updated source using clojure.tools.namespace
    (let [reload-result (if (seq directories)
                          (let [form1 "(require 'clojure.tools.namespace.repl)"
                                form2 (str/join (conj (into ["(clojure.tools.namespace.repl/set-refresh-dirs "]
                                                            (map pr-str directories))
                                                      ")"))
                                form3 "(clojure.tools.namespace.repl/refresh)"
                                refresh-dirs-code (str/join "\n" [form1 form2 form3])]
                            (-> {:port port
                                 :expr refresh-dirs-code}
                                (nrepl/eval-expr)
                                :vals
                                (last)))
                          (-> {:port port
                               :expr (pr-str
                                       '((requiring-resolve 'clojure.tools.namespace.repl/refresh)))}
                              (nrepl/eval-expr)
                              :vals
                              (last)))
          reload-success? (= ":ok" reload-result)]
      (if reload-success?
        (do
          (println "Reloaded" directories reload-result)
          ; JVM: Run tests using clojure.test
          (let [parsed (parse-test-entries test-entries)
                {:keys [namespaces individual-tests]} parsed
                
                ;; Smart filtering: When both individual tests and full namespaces are specified,
                ;; we filter out namespaces that have individual tests to avoid running the same
                ;; test twice. For example: if both "my.ns/test1" and "my.ns" are specified,
                ;; we only run "my.ns/test1" individually and skip the full "my.ns" namespace.
                individual-test-namespaces (set (map :namespace individual-tests))
                filtered-namespaces (remove individual-test-namespaces namespaces)
                ;; We use separate nREPL evaluations for namespaces and individual tests,
                ;; then combine the results. For individual tests, we run each one separately
                ;; to avoid nREPL issues with multiple test-var calls in one expression.
                results (cond-> []
                          (seq filtered-namespaces)
                          (conj (execute-test-code port (generate-namespace-test-code filtered-namespaces) filter?))
                          
                          (seq individual-tests)
                          (into (map #(execute-test-code port (generate-individual-test-code %) filter?) individual-tests)))
                
                ;; Combine all results
                combined-result (combine-test-results results)]
            
            (if (pos? (:test combined-result))
              {:values (select-keys combined-result [:test :pass :fail :error])
               :out (:out combined-result)
               :err (:err combined-result)}
              (throw (ex-info "No tests were executed" 
                              {:namespaces namespaces
                               :individual-tests individual-tests
                               :filtered-namespaces filtered-namespaces})))))
        (do
          (println "Reload failed...")
          (pprint/pprint reload-result))))
    (catch Throwable t
      (.printStackTrace t))))

(defn handle-parse-error
  "SHARED: Higher-order function returning an :error-fn compatible handler for CLI parsing errors.
   Takes options map with :exit? key (defaults to true) to control whether to exit the process on error.
   Used by both JVM and ShadowCLJS test runners."
  [{:keys [exit?]
    :or   {exit? true}}]
  (fn handle-parse-error
    [{:keys [_spec type cause msg option] :as _data}]
    (if (= :org.babashka/cli type)
      (case cause
        :require (println (format "Missing required argument:\n%s"
                                   (cli/format-opts {:spec (select-keys _spec [option])})))
        (println msg))
      (throw (ex-info msg _data)))
    (when exit? (System/exit 1))))

(defn parse-jvm-tests-args
  "JVM: Parses command line arguments for the JVM test runner.
   Accepts CLI args and options with :exit? key to control exit behavior.
   Returns a map with parsed :namespaces, :directories, and :port-file values.
   The :directories option is JVM-specific for clojure.tools.namespace reloading."
  [cli-args & {:keys [exit?] :or {exit? true}}]
  (cli/parse-opts cli-args
                  {:spec     {:namespaces  {:ref      "<csv list>"
                                            :desc     "The names of the test namespaces to be run, or namespace/test-function for individual tests"
                                            :require  true
                                            :alias    :n
                                            :coerce   #(clojure.string/split % #",")
                                            :validate seq}
                              :directories {:ref     "<csv list>"
                                            :desc    "Comma-separated list of directories (relative to target project) to scanned for changes & reloaded before running tests (JVM only)"
                                            :require false
                                            :alias   :d
                                            :coerce  #(clojure.string/split % #",")
                                            :default []}
                              :port-file   {:ref      "<file path>"
                                            :desc     "The file containing the repl network port. defaults to .nrepl-port"
                                            :require  false
                                            :default  ".nrepl-port"
                                            :alias    :p
                                            :validate (fn [file]
                                                        (and (string? file)
                                                             (.exists (io/file file))))}
                              :filter      {:ref      "<boolean>"
                                            :desc     "Filter stack traces to remove internal frames (default: true)"
                                            :require  false
                                            :default  true
                                            :alias    :f
                                            :coerce   :boolean}}
                   :error-fn (handle-parse-error {:exit? exit?})}))

(comment (parse-jvm-tests-args ["-n" "a.b.c" "-d" "dev,src"] {:exit? false}))

(defn run-tests-task
  "JVM: Main entry point for the JVM test runner task.
   Parses command line arguments, connects to the nREPL server,
   runs the specified tests (supporting both namespaces and individual test functions),
   optionally applies stack trace filtering, and returns an exit code based on test results.
   This function is specific to JVM/Clojure environments."
  [args]
  (let [{:keys [namespaces directories port-file] filter? :filter} (parse-jvm-tests-args args)
        port (read-port port-file)
        result (run-tests port directories namespaces filter?)]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [fail error])]
        ;; Output test results (filtered or unfiltered based on filter option)
        (some->> (:out result) 
                 (remove empty?) 
                 seq 
                 ((if filter? clean-test-output identity))
                 (str/join "\n") 
                 (#(str "<stdout>\n" % "\n</stdout>")) 
                 println)
        (some->> (:err result) 
                 (remove empty?) 
                 seq 
                 ((if filter? clean-test-output identity))
                 (str/join "\n") 
                 (#(str "<stderr>\n" % "\n</stderr>")) 
                 println)
        return-code)
      (do
        (println "Test invocation failed")
        (pprint/pprint result)
        1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shadow-CLJS Test Runner Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Design for Shadow-CLJS integration:
;;
;; 1) Architecture
;;    - Delegates to io.nextdoc.shadow-nrepl namespace for actual implementation
;;    - Uses custom bencode nREPL client for proper session management
;;    - Shadow writes nREPL port to .shadow-cljs/nrepl.port
;;
;; 2) Implementation approach
;;    - Custom raw bencode nREPL client (not babashka/nrepl-client)
;;    - Proper session management with sticky session IDs
;;    - Custom test reporter with detailed failure capture
;;    - Polling-based async result collection
;;
;; 3) Key differences from JVM runner:
;;    - Uses Shadow-CLJS API for build switching
;;    - Custom test reporter captures detailed failures
;;    - Async test execution with polling for results
;;    - Pretty-printed results in <results> XML tags
;;    - Modular design with separate helper functions
;;
;; 4) BB.edn usage:
;;    - Separate tasks: nrepl:test (JVM) and nrepl:test-shadow (CLJS)
;;    - User explicitly chooses which runtime to target
;;    - No automatic dispatch between JVM/CLJS modes

(defn parse-cljs-tests-args
  "CLJS: Parses command line arguments for the Shadow-CLJS test runner.
   Accepts CLI args and options with :exit? key to control exit behavior.
   Returns a map with parsed :namespaces, :build-id, and :port-file values."
  [cli-args & {:keys [exit?] :or {exit? true}}]
  (cli/parse-opts cli-args
                  {:spec     {:namespaces {:ref      "<csv list>"
                                           :desc     "The names of the test namespaces to be run"
                                           :require  true
                                           :alias    :n
                                           :coerce   #(clojure.string/split % #",")
                                           :validate seq}
                              :build-id   {:ref     "<build-id>"
                                           :desc    "Optional Shadow-CLJS build ID (e.g., dev, test)"
                                           :require false
                                           :alias   :b
                                           :coerce  keyword}
                              :port-file  {:ref      "<file path>"
                                           :desc     "The file containing the repl network port. defaults to .shadow-cljs/nrepl.port"
                                           :require  false
                                           :default  ".shadow-cljs/nrepl.port"
                                           :alias    :p
                                           :validate (fn [file]
                                                       (and (string? file)
                                                            (.exists (io/file file))))}
                              :filter     {:ref      "<boolean>"
                                           :desc     "Filter stack traces to remove internal frames (default: true)"
                                           :require  false
                                           :default  true
                                           :alias    :f
                                           :coerce   :boolean}}
                   :error-fn (handle-parse-error {:exit? exit?})}))


(defn run-cljs-tests-task
  "CLJS: Main entry point for the Shadow-CLJS test runner task.
   Parses command line arguments, connects to the Shadow-CLJS nREPL server,
   runs the specified tests, and returns an exit code based on test results."
  [args]
  (let [{:keys [namespaces build-id port-file] filter? :filter} (parse-cljs-tests-args args)
        port (read-port port-file)
        result (try
                 ;; Use the custom bencode client for proper session management
                 (shadow-nrepl/run-shadow-tests port build-id namespaces)
                 (catch Throwable t
                   {:values {:test 0 :pass 0 :fail 0 :error 1}
                    :out    []
                    :err    [(str "Error: " (.getMessage t))]}))]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [(or fail 0) (or error 0)])]
        ;; Output handling for CLJS (typically minimal as Shadow handles console output)
        (some->> (:out result) (remove empty?) (seq) (str/join "\n") (#(str "<stdout>\n" % "\n</stdout>")) println)
        (some->> (:err result) (remove empty?) (seq) (str/join "\n") (#(str "<stderr>\n" % "\n</stderr>")) println)
        return-code)
      (do
        (println "CLJS test invocation failed")
        (pprint/pprint result)
        1))))
