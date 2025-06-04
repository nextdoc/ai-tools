(ns io.nextdoc.tools
  (:require
    [babashka.nrepl-client :as nrepl]
    [babashka.cli :as cli]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.string]))

(defn read-port
  "Reads a port number from a file and returns it as an integer.
   Prints a connection message to stdout."
  [port-file]
  (let [port (Integer/parseInt (slurp port-file))]
    (println "Connected:" port-file ">" port "...")
    port))

(defn run-tests
  "Runs tests in the specified test namespaces using an nREPL connection.
   Reloads the test namespaces before running tests.
   Returns a map containing test results, stdout, and stderr."
  [port directories & test-namespaces]
  (try

    ; Reload any updated source
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
          ; Run tests
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
  "Higher-order function returning an :error-fn compatible handler for CLI parsing errors.
   Takes options map with :exit? key (defaults to true) to control whether to exit the process on error."
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

(defn parse-tests-args
  "Parses command line arguments for the test runner.
   Accepts CLI args and options with :exit? key to control exit behavior.
   Returns a map with parsed :namespaces and :port-file values."
  [cli-args & {:keys [exit?] :or {exit? true}}]
  (cli/parse-opts cli-args
                  {:spec     {:namespaces  {:ref      "<csv list>"
                                            :desc     "The names of the test namespaces to be run"
                                            :require  true
                                            :alias    :n
                                            :coerce   #(clojure.string/split % #",")
                                            :validate seq}
                              :directories {:ref     "<csv list>"
                                            :desc    "Comma-separated list of directories (relative to target project) to scanned for changes & reloaded before running tests"
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

(comment (parse-tests-args ["-n" "a.b.c" "-d" "dev,src"] {:exit? false}))

(defn run-tests-task
  "Main entry point for the test runner task.
   Parses command line arguments, connects to the nREPL server,
   runs the specified tests, and returns an exit code based on test results."
  [args]
  (let [{:keys [namespaces directories port-file]} (parse-tests-args args)
        port (read-port port-file)
        result (apply run-tests port directories namespaces)]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [fail error])]
        (some->> (:out result) (remove empty?) (seq) (str/join "\n") (#(str "<stdout>\n" % "\n</stdout>")) println)
        (some->> (:err result) (remove empty?) (seq) (str/join "\n") (#(str "<stderr>\n" % "\n</stderr>")) println)
        return-code)
      (do
        (println "Test invocation failed")
        (clojure.pprint/pprint result)
        1))))
