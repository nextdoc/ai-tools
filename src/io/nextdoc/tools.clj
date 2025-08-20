(ns io.nextdoc.tools
  "Tools for invoking tests via nREPL connections.
   Supports both JVM (Clojure) and ShadowCLJS (ClojureScript) environments."
  (:require
    [babashka.cli :as cli]
    [babashka.nrepl-client :as nrepl]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [io.nextdoc.shadow-nrepl :as shadow-nrepl]))

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
   Returns nil if the execution fails, which will be filtered out during result combination."
  [port code]
  (println "\n===== Executing test code: =====")
  (println code)
  (println "=================================\n")
  (try
    (let [response (nrepl/eval-expr {:port port :expr code})
          {:keys [vals err out]} response]
      (println "Response - vals:" vals "err:" err "out:" out)
      (when err
        (println "nREPL error:" err))
      (when out
        (println "nREPL output:" out))
      (cond
        (and vals (seq vals)) (edn/read-string (first vals))
        err nil
        :else (do
                (println "Warning: No result from nREPL execution")
                (println "Full response:" response)
                nil)))
    (catch Exception e
      (println "Failed to execute test code:" (.getMessage e))
      (println "Stack trace:" (.printStackTrace e))
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

(defn run-tests
  "JVM: Runs tests in the specified test namespaces using an nREPL connection.
   Reloads the test namespaces before running tests using clojure.tools.namespace.
   Returns a map containing test results, stdout, and stderr.
   This function is specific to JVM/Clojure environments."
  [port directories test-entries]
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
                ;; Execute tests separately and combine results
                ;; We use separate nREPL evaluations for namespaces and individual tests,
                ;; then combine the results. For individual tests, we run each one separately
                ;; to avoid nREPL issues with multiple test-var calls in one expression.
                results (cond-> []
                          (seq filtered-namespaces)
                          (conj (execute-test-code port (generate-namespace-test-code filtered-namespaces)))
                          
                          (seq individual-tests)
                          (into (map #(execute-test-code port (generate-individual-test-code %)) individual-tests)))
                
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
                                                             (.exists (io/file file))))}}
                   :error-fn (handle-parse-error {:exit? exit?})}))

(comment (parse-jvm-tests-args ["-n" "a.b.c" "-d" "dev,src"] {:exit? false}))

(defn run-tests-task
  "JVM: Main entry point for the JVM test runner task.
   Parses command line arguments, connects to the nREPL server,
   runs the specified tests, and returns an exit code based on test results.
   This function is specific to JVM/Clojure environments."
  [args]
  (let [{:keys [namespaces directories port-file]} (parse-jvm-tests-args args)
        port (read-port port-file)
        result (run-tests port directories namespaces)]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [fail error])]
        ; TODO JVM: Elide irrelevant stack trace lines when errors occur. This will reduce the context size during iteration.
        (some->> (:out result) (remove empty?) (seq) (str/join "\n") (#(str "<stdout>\n" % "\n</stdout>")) println)
        (some->> (:err result) (remove empty?) (seq) (str/join "\n") (#(str "<stderr>\n" % "\n</stderr>")) println)
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
                                                            (.exists (io/file file))))}}
                   :error-fn (handle-parse-error {:exit? exit?})}))


(defn run-cljs-tests-task
  "CLJS: Main entry point for the Shadow-CLJS test runner task.
   Parses command line arguments, connects to the Shadow-CLJS nREPL server,
   runs the specified tests, and returns an exit code based on test results."
  [args]
  (let [{:keys [namespaces build-id port-file]} (parse-cljs-tests-args args)
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
