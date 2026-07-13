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
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [io.nextdoc.clean :as clean]
    [io.nextdoc.jvm-nrepl :as jvm-nrepl]
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

(defn- generate-reload-code
  "Generates code that reloads changed namespaces using a tracker OWNED BY THIS
   TOOL, stored in the target JVM under io.nextdoc.test-runner-state/state.

   Deliberately does NOT use clojure.tools.namespace.repl/refresh or
   set-refresh-dirs: those mutate the REPL's global refresh state, and
   narrowing the dirs there makes tools.namespace treat files under the
   removed dirs as deleted — unloading dev namespaces, stripping the user
   namespace bare, and breaking every later nREPL session in that JVM.

   On the first run for a given dirs value the tracker has no baseline, so it
   reloads only files modified since the JVM started (anything older is
   already compiled into the running image). Later runs reload exactly what
   changed since the previous run."
  [directories requested-namespaces]
  (str "(try
  (require 'clojure.tools.namespace.dir
           'clojure.tools.namespace.track
           'clojure.tools.namespace.reload)
  (let [state-ns (create-ns 'io.nextdoc.test-runner-state)
        state-var (or (ns-resolve state-ns 'state) (intern state-ns 'state (atom nil)))
        state-atom (deref state-var)
        dirs " (pr-str (vec directories)) "
        requested '" (pr-str (set requested-namespaces)) "
        prev (deref state-atom)
        fresh (not= dirs (:dirs prev))
        tracker (if fresh (clojure.tools.namespace.track/tracker) (:tracker prev))
        scanned (clojure.tools.namespace.dir/scan-dirs tracker dirs)
        loaded (set (map ns-name (all-ns)))
        filemap (get scanned :clojure.tools.namespace.file/filemap)
        ns-mtime (reduce-kv (fn [m file n] (update m n (fnil max 0) (.lastModified file))) {} filemap)
        jvm-start (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))
        keep-ns? (fn [n] (and (or (contains? loaded n) (contains? requested n))
                              (or (not fresh) (> (get ns-mtime n 0) jvm-start))))
        load-list (filterv keep-ns? (get scanned :clojure.tools.namespace.track/load))
        unload-list (filterv keep-ns? (get scanned :clojure.tools.namespace.track/unload))
        pruned (assoc scanned
                 :clojure.tools.namespace.track/load load-list
                 :clojure.tools.namespace.track/unload unload-list)
        reloaded (clojure.tools.namespace.reload/track-reload pruned)
        err (get reloaded :clojure.tools.namespace.reload/error)
        err-ns (get reloaded :clojure.tools.namespace.reload/error-ns)]
    (reset! state-atom {:dirs dirs
                        :tracker (dissoc reloaded
                                         :clojure.tools.namespace.reload/error
                                         :clojure.tools.namespace.reload/error-ns)})
    {:reloaded (mapv str load-list)
     :fresh fresh
     :error (when err
              (str \"while reloading \" err-ns \": \" (.getMessage err)
                   (when-let [c (.getCause err)] (str \" | cause: \" (.getMessage c)))))})
  (catch Throwable t
    {:error (str (.getMessage t)
                 (when-let [c (.getCause t)] (str \" | cause: \" (.getMessage c))))}))"))

(defn- generate-namespace-test-code
  "Generates the code string for running tests in full namespaces.
   Requires each namespace first (capturing load failures per namespace) and,
   when zero tests run, returns a per-namespace diagnosis so the caller can
   distinguish a typo from a namespace with no clojure.test deftests."
  [namespaces]
  (str "(binding [*out* (java.io.StringWriter.)
          *err* (java.io.StringWriter.)]
  (let [nss '" (pr-str (vec namespaces)) "
        load-errors (into {}
                          (keep (fn [n]
                                  (try (require n) nil
                                       (catch Throwable t
                                         [n (str (.getMessage t)
                                                 (when-let [c (.getCause t)]
                                                   (str \" | cause: \" (.getMessage c))))]))))
                          nss)
        runnable (vec (remove (set (keys load-errors)) nss))
        result (if (seq runnable)
                 (apply clojure.test/run-tests runnable)
                 {:test 0 :pass 0 :fail 0 :error 0})
        deftest-count (fn [n] (if-let [ns-obj (find-ns n)]
                                (count (filter (fn [[_ v]] (:test (meta v))) (ns-interns ns-obj)))
                                0))
        diagnosis (when (zero? (get result :test 0))
                    (mapv (fn [n]
                            (str n \": \"
                                 (cond
                                   (contains? load-errors n) (str \"failed to load - \" (get load-errors n))
                                   (nil? (find-ns n)) \"not loaded (not on classpath?)\"
                                   (zero? (deftest-count n)) \"loaded but contains no clojure.test deftest vars - Greenlight or other non-clojure.test tests cannot run via this tool\"
                                   :else \"has deftest vars but clojure.test ran 0 tests\")))
                          nss))]
    {:result result
     :diagnosis diagnosis
     :out (str *out*)
     :err (str *err*)}))"))

(defn- generate-individual-test-code
  "Generates the code string for running a single test function via run-test-var,
   with a diagnosis instead of an opaque failure when the var is missing or not
   a deftest."
  [{:keys [namespace test-name]}]
  (let [full-name (str namespace "/" test-name)]
    (str "(binding [*out* (java.io.StringWriter.)
          *err* (java.io.StringWriter.)]
  (let [r (try
            (require '" namespace ")
            (let [v (resolve '" full-name ")]
              (cond
                (nil? v) {:diagnosis [\"" full-name ": var not found in loaded namespace\"]}
                (nil? (:test (meta v))) {:diagnosis [\"" full-name ": var exists but is not a clojure.test deftest\"]}
                :else {:result (clojure.test/run-test-var v)}))
            (catch Throwable t
              {:diagnosis [(str \"" full-name ": failed to load namespace - \" (.getMessage t))]}))]
    (merge r {:out (str *out*) :err (str *err*)})))")))

(defn- execute-code
  "Evaluates code via nREPL and returns the edn-parsed final value with the
   server's stderr attached as :nrepl-err. Prints the server's stdout
   (optionally filtered). When the eval produces no value the stderr IS the
   error, so it is returned as :eval-error instead of being dropped."
  [port code filter?]
  (try
    (let [{:keys [vals out err]} (jvm-nrepl/eval-expr {:port port :expr code})]
      (when-not (str/blank? out)
        (if filter?
          (doseq [line (clean/clean-test-output out)]
            (println line))
          (print out)))
      (let [value (some-> (last vals) edn/read-string)]
        (cond
          (map? value) (assoc value :nrepl-err err)
          (not (str/blank? err)) {:eval-error err}
          :else {:eval-error "nREPL eval returned no value and no error output"})))
    (catch Exception e
      {:eval-error (str "nREPL connection/eval failed: " (ex-message e))})))

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

(defn- reload-summary
  "One honest line about what the reload actually did."
  [{:keys [reloaded fresh]} directories]
  (let [n (count reloaded)
        shown (take 15 reloaded)]
    (str "Reloaded " n " changed namespace" (when (not= 1 n) "s")
         " from dirs " (pr-str (vec directories))
         (when fresh " (new tracker: files modified since JVM start)")
         (when (pos? n)
           (str ": " (str/join " " shown) (when (< 15 n) " ..."))))))

(defn run-tests
  "JVM: Runs tests in the specified test namespaces using an nREPL connection.
   Supports both full namespaces and individual test functions using slash notation.
   Reloads changed code first via a tool-owned tracker (see generate-reload-code);
   pass empty directories to skip reloading entirely.
   Returns {:values {:test :pass :fail :error} :out [...] :err [...]} on a run
   that executed tests, or {:invocation-error \"...\"} when the run could not
   execute tests at all (reload failure, connection failure, zero tests)."
  [port directories test-entries filter?]
  (let [{:keys [namespaces individual-tests]} (parse-test-entries test-entries)
        requested (set (concat namespaces (map :namespace individual-tests)))
        reload-problem
        (if (empty? directories)
          (do (println "Reload skipped (no directories)") nil)
          (let [r (execute-code port (generate-reload-code directories requested) false)]
            (cond
              (:eval-error r) (str "Reload failed: " (:eval-error r))
              (:error r) (str "Reload failed " (:error r)
                              (when-not (str/blank? (:nrepl-err r))
                                (str "\n" (:nrepl-err r))))
              :else (do (println (reload-summary r directories)) nil))))]
    (if reload-problem
      {:invocation-error reload-problem}
      ;; Smart filtering: when both my.ns/test1 and my.ns are specified, run only
      ;; my.ns/test1 individually and skip the full my.ns namespace.
      (let [individual-test-namespaces (set (map :namespace individual-tests))
            filtered-namespaces (vec (remove individual-test-namespaces namespaces))
            ;; Namespaces and individual tests run as separate nREPL evaluations
            ;; to avoid nREPL issues with multiple test-var calls in one expression.
            results (cond-> []
                      (seq filtered-namespaces)
                      (conj (execute-code port (generate-namespace-test-code filtered-namespaces) filter?))

                      (seq individual-tests)
                      (into (map #(execute-code port (generate-individual-test-code %) filter?) individual-tests)))
            eval-errors (keep :eval-error results)
            ;; Fold the server's stderr frames into each result's :err output
            combined (combine-test-results
                       (map #(assoc % :err (str (:err %) (:nrepl-err %))) results))
            diagnosis (vec (mapcat :diagnosis results))]
        (cond
          (seq eval-errors)
          {:invocation-error (str/join "\n" eval-errors)}

          (zero? (:test combined))
          {:invocation-error
           (str "No tests were executed:\n"
                (str/join "\n" (map #(str "  " %) diagnosis)))}

          :else
          (cond-> {:values (select-keys combined [:test :pass :fail :error])
                   :out (:out combined)
                   :err (:err combined)}
            (seq diagnosis) (assoc :diagnosis diagnosis)))))))

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

(defn- validate-comma-delimited
  "Predicate that validates a collection of directory paths doesn't contain colon-separated values.
   If a single element contains colons but no path separators, it's likely the user
   used colon delimiters instead of commas.
   Returns true if valid, false otherwise."
  [dirs]
  (if (sequential? dirs)
    (not-any? (fn [dir]
                (and (string? dir)
                     (str/includes? dir ":")
                     (not (str/includes? dir "/"))))  ; Allow colons in absolute paths like C:/...
              dirs)
    true))

(defn- comma-delimited-error-msg
  "Generates a helpful error message when colon delimiters are detected instead of commas.
   Used as :ex-msg function for babashka.cli validation."
  [{:keys [value]}]
  (let [bad-dir (first (filter (fn [dir]
                                 (and (string? dir)
                                      (str/includes? dir ":")
                                      (not (str/includes? dir "/"))))
                               value))]
    (str "Invalid delimiter: directories must be comma-separated, not colon-separated. "
         "Found colons in: \"" bad-dir "\". "
         "Did you use ':' instead of ',' to separate directories? "
         "Use commas instead, e.g., \"src,test,dev\"")))

(def ^:private jvm-flag-aliases
  {"-n" :namespaces "--namespaces" :namespaces
   "-d" :directories "--directories" :directories
   "-p" :port-file "--port-file" :port-file
   "-f" :filter "--filter" :filter})

(defn duplicate-flags
  "Returns the set of logical flags passed more than once (e.g. -n a -n b).
   babashka.cli is last-wins for repeated flags, which silently drops all but
   the final value, so repeats are rejected up front."
  [args]
  (->> args
       (keep jvm-flag-aliases)
       frequencies
       (keep (fn [[flag n]] (when (< 1 n) flag)))
       set))

(defn path-like-entries
  "Namespace entries that look like file paths rather than namespace symbols
   (a .clj/.cljs/.cljc suffix, or more than the single slash used by the
   ns/test-fn notation)."
  [entries]
  (filterv (fn [entry]
             (or (some? (re-find #"\.clj[cs]?$" entry))
                 (< 1 (count (re-seq #"/" entry)))))
           entries))

(defn- default-reload-dirs
  "Dirs reloaded when -d is omitted: src and test when they exist under the
   current project. Never falls back to the whole classpath."
  []
  (filterv #(.isDirectory (io/file %)) ["src" "test"]))

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
                                            :validate {:pred seq
                                                       :ex-msg (constantly "Namespaces list cannot be empty")}}
                              :directories {:ref      "<csv list>"
                                            :desc     "Comma-separated list of directories (relative to target project) scanned for changes & reloaded before running tests (JVM only). Defaults to src,test when they exist. Pass 'none' to skip reloading."
                                            :require  false
                                            :alias    :d
                                            :coerce   #(clojure.string/split % #",")
                                            :validate {:pred validate-comma-delimited
                                                       :ex-msg comma-delimited-error-msg}
                                            :default  []}
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
   Parses and validates command line arguments, connects to the nREPL server,
   runs the specified tests (supporting both namespaces and individual test
   functions), and optionally applies stack trace filtering.
   Exit codes: 0 = all tests passed, 1 = test failures/errors,
   2 = the run could not execute tests (bad arguments, reload failure,
   connection failure, or zero tests found)."
  [args]
  (let [dups (duplicate-flags args)]
    (if (seq dups)
      (do (println (str "Repeated flag(s): " (str/join ", " (map name dups))
                        ". Each flag takes ONE comma-separated list, e.g. -n ns.one,ns.two"
                        " - repeated flags are last-wins and silently drop earlier values."))
          2)
      (let [{:keys [namespaces directories port-file] filter? :filter} (parse-jvm-tests-args args)
            bad-entries (path-like-entries namespaces)]
        (if (seq bad-entries)
          (do (println (str "These -n entries look like file paths, not namespaces: "
                            (str/join ", " bad-entries)
                            ". Pass namespace symbols, e.g. components.cdb.cdb-records-test"
                            " (or ns/test-name for an individual test)."))
              2)
          (let [port (read-port port-file)
                dirs (cond
                       (= ["none"] directories) []
                       (seq directories) directories
                       :else (default-reload-dirs))
                result (run-tests port dirs namespaces filter?)]
            (if-let [problem (:invocation-error result)]
              (do (println "Test invocation failed:")
                  (println problem)
                  2)
              (let [{:keys [fail error]} (:values result)]
                ;; Output test results (filtered or unfiltered based on filter option)
                (some->> (:out result)
                         (remove empty?)
                         seq
                         ((if filter? clean/clean-test-output identity))
                         (str/join "\n")
                         (#(str "<stdout>\n" % "\n</stdout>"))
                         println)
                (some->> (:err result)
                         (remove empty?)
                         seq
                         ((if filter? clean/clean-test-output identity))
                         (str/join "\n")
                         (#(str "<stderr>\n" % "\n</stderr>"))
                         println)
                (some->> (:diagnosis result)
                         seq
                         (str/join "\n")
                         (#(str "<diagnosis>\n" % "\n</diagnosis>"))
                         println)
                (if (pos? (+ fail error)) 1 0)))))))))

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
                                           :validate {:pred seq
                                                      :ex-msg (constantly "Namespaces list cannot be empty")}}
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
