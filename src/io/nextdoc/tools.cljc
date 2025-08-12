(ns io.nextdoc.tools
  "Tools for invoking tests via nREPL connections.
   Supports both JVM (Clojure) and ShadowCLJS (ClojureScript) environments."
  (:require
    [babashka.cli :as cli]
    [babashka.nrepl-client :as nrepl]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.string]))

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
;; 1) Shadow build + nREPL basics
;;    - Shadow writes nREPL port to .shadow-cljs/nrepl.port
;;    - Can use :node-test target for CI/fallback or browser-test for DOM
;;    - Hot-reload is handled by Shadow's :after-load hooks
;;
;; 2) CLJS test runner approach
;;    - Connect to Shadow's nREPL port (default: .shadow-cljs/nrepl.port)
;;    - Switch session to CLJS using Shadow's API
;;    - Use (require 'ns :reload) instead of tools.namespace
;;    - Run tests using cljs.test with async completion handling
;;
;; 3) Key differences from JVM runner:
;;    - No tools.namespace (Shadow handles compilation/watching)
;;    - Async test execution (cljs.test/run-tests is async)
;;    - Optional build-id to select specific Shadow build
;;    - Returns promise-based results in CLJS environment
;;
;; 4) BB.edn usage:
;;    - Separate tasks for JVM (nrepl:test) and CLJS (nrepl:cljs-test)
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

(def ^:private pending-token "::nextdoc/pending")

(defn- cljs-install-reporter-form
  "Builds a CLJS form that installs a test reporter."
  []
  "(do
     (require '[cljs.test :as t])
     ;; Use globalThis for portable global access
     (when-not (aget js/globalThis \"__NEXTDOC_RESULT__\")
       (aset js/globalThis \"__NEXTDOC_RESULT__\" (atom nil)))
     ;; Reset test environment for fresh counters
     (t/set-env! (t/empty-env))
     ;; Hook :summary for reliable completion signaling
     (when (contains? (methods t/report) [:cljs.test/default :summary])
       (remove-method t/report [:cljs.test/default :summary]))
     (defmethod t/report [:cljs.test/default :summary] [m]
       (reset! (aget js/globalThis \"__NEXTDOC_RESULT__\")
               (select-keys m [:test :pass :fail :error])))
     :ok)")

(defn- cljs-require-tests-form
  "Builds a CLJS form that requires test namespaces."
  [test-namespaces]
  (let [reqs (->> test-namespaces
                  (map #(format "(require '%s :reload)" %))
                  (clojure.string/join " "))]
    (str "(do " reqs " :ok)")))

(defn- cljs-run-tests-form
  "Builds a CLJS form that runs tests."
  [test-namespaces]
  (let [nslist (->> test-namespaces (map #(str "'" %)) (clojure.string/join " "))]
    (str "(do (cljs.test/run-tests " nslist ") " (pr-str pending-token) ")")))

(def ^:private cljs-peek-form
  "(let [a (aget js/globalThis \"__NEXTDOC_RESULT__\")]
     (if a (pr-str @a) ::nil))")                           ;; returns the symbol ::nil until the atom exists or is still nil

(defn run-cljs-tests
  "CLJS: Runs tests in the specified test namespaces using a Shadow-CLJS nREPL connection.
   Switches to CLJS REPL, reloads namespaces, and runs tests asynchronously.
   Uses polling to capture async test results."
  [port build-id & test-namespaces]
  (try
    ;; Use the custom bencode client for proper session management
    (require 'io.nextdoc.shadow-nrepl)
    (let [run-shadow-tests (resolve 'io.nextdoc.shadow-nrepl/run-shadow-tests)]
      (run-shadow-tests port build-id test-namespaces))
    
    (catch Throwable t
      {:values {:test 0 :pass 0 :fail 0 :error 1}
       :out    []
       :err    [(str "Error: " (.getMessage t))]})))

(defn run-cljs-tests-task
  "CLJS: Main entry point for the Shadow-CLJS test runner task.
   Parses command line arguments, connects to the Shadow-CLJS nREPL server,
   runs the specified tests, and returns an exit code based on test results."
  [args]
  (let [{:keys [namespaces build-id port-file]} (parse-cljs-tests-args args)
        port (read-port port-file)
        result (apply run-cljs-tests port build-id namespaces)]
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
