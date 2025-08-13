(ns io.nextdoc.tools
  "Tools for invoking tests via nREPL connections.
   Supports both JVM (Clojure) and ShadowCLJS (ClojureScript) environments."
  (:require
    [babashka.cli :as cli]
    [babashka.nrepl-client :as nrepl]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
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

(defn run-tests
  "JVM: Runs tests in the specified test namespaces using an nREPL connection.
   Reloads the test namespaces before running tests using clojure.tools.namespace.
   Returns a map containing test results, stdout, and stderr.
   This function is specific to JVM/Clojure environments."
  [port directories & test-namespaces]
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
          (let [test-code (str "(binding [*out* (java.io.StringWriter.)
                                    *err* (java.io.StringWriter.)]
                           (let [result (apply clojure.test/run-tests '" (pr-str (mapv symbol test-namespaces)) ")]
                             {:result result
                              :out (str *out*)
                              :err (str *err*)}))")
                {:keys [vals]} (nrepl/eval-expr {:port port
                                                 :expr test-code})]
            (if vals
              (let [{:keys [result out err]} (edn/read-string (first vals))]
                {:values result
                 :out    (some-> out (clojure.string/split-lines))
                 :err    (some-> err (clojure.string/split-lines))})
              (throw (ex-info "Failed to retrieve test results" {})))))
        (do
          (println "Reload failed...")
          (clojure.pprint/pprint reload-result))))
    (catch Throwable t
      (.printStackTrace t))))

(defn handle-parse-error
  "SHARED: Higher-order function returning an :error-fn compatible handler for CLI parsing errors.
   Takes options map with :exit? key (defaults to true) to control whether to exit the process on error.
   Used by both JVM and ShadowCLJS test runners."
  [{:keys [exit?]
    :or   {exit? true}}]
  (fn handle-parse-error
    [{:keys [spec type cause msg option] :as data}]
    (if (= :org.babashka/cli type)
      (case cause
        :require (println (format "Missing required argument:\n%s"
                                  (cli/format-opts {:spec (select-keys spec [option])})))
        (println msg))
      (throw (ex-info msg data)))
    (when exit? (System/exit 1))))

(defn parse-jvm-tests-args
  "JVM: Parses command line arguments for the JVM test runner.
   Accepts CLI args and options with :exit? key to control exit behavior.
   Returns a map with parsed :namespaces, :directories, and :port-file values.
   The :directories option is JVM-specific for clojure.tools.namespace reloading."
  [cli-args & {:keys [exit?] :or {exit? true}}]
  (cli/parse-opts cli-args
                  {:spec     {:namespaces  {:ref      "<csv list>"
                                            :desc     "The names of the test namespaces to be run"
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
        result (apply run-tests port directories namespaces)]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [fail error])]
        ; TODO JVM: Elide irrelevant stack trace lines when errors occur. This will reduce the context size during iteration.
        (some->> (:out result) (remove empty?) (seq) (str/join "\n") (#(str "<stdout>\n" % "\n</stdout>")) println)
        (some->> (:err result) (remove empty?) (seq) (str/join "\n") (#(str "<stderr>\n" % "\n</stderr>")) println)
        return-code)
      (do
        (println "Test invocation failed")
        (clojure.pprint/pprint result)
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
        (clojure.pprint/pprint result)
        1))))
