(ns io.nextdoc.tools
  (:require
    [babashka.nrepl-client :as nrepl]
    [babashka.cli :as cli]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.string]))

(defn read-port
  [port-file]
  (let [port (Integer/parseInt (slurp port-file))]
    (println "Connected:" port-file ">" port "...")
    port))

(defn run-tests [port & test-namespaces]
  (try
    ; Reload any updated source
    (doseq [tns test-namespaces]
      (nrepl/eval-expr {:port port
                        :expr (pr-str (list 'clojure.core/require (symbol tns) :reload-all))}))
    (println "Reloaded" test-namespaces "...")
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
        (throw (ex-info "Failed to retrieve test results" {}))))
    (catch Throwable t
      (.printStackTrace t))))

(defn handle-parse-error
  "HOF returning an :error-fn compatible handler"
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
  [cli-args & {:keys [exit?] :or {exit? true}}]
  (cli/parse-opts cli-args
                  {:spec     {:namespaces {:ref      "<csv list>"
                                           :desc     "The names of the test namespaces to be run"
                                           :require  true
                                           :alias    :n
                                           :coerce   #(clojure.string/split % #",")
                                           :validate seq}
                              :port-file  {:ref      "<file path>"
                                           :desc     "The file containing the repl network port. defaults to .nrepl-port"
                                           :require  false
                                           :default  ".nrepl-port"
                                           :alias    :p
                                           :validate (fn [file]
                                                       (and (string? file)
                                                            (.exists (io/file file))))}}
                   :error-fn (handle-parse-error {:exit? exit?})}))

(comment (parse-tests-args ["-n" "a.b.c"] {:exit? false}))

(defn run-tests-task
  [args]
  (let [{:keys [namespaces port-file]} (parse-tests-args args)
        port (read-port port-file)
        result (apply run-tests port namespaces)]
    (if (seq (:values result))
      (let [{:keys [fail error]} (:values result)
            return-code (reduce + [fail error])]
        (some->> (:out result) (remove empty?) (seq) (str/join "\n") (#(str "<stdout>\n" % "\n</stdout>")) println)
        (some->> (:err result) (remove empty?) (seq) (str/join "\n") (#(str "<stderr>\n" % "\n</stderr>")) println)
        return-code)
      (do
        (println "Test invocation failed")
        1))))
