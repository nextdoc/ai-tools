(ns io.nextdoc.shadow-nrepl
  "Raw bencode nREPL client for Shadow-CLJS with proper session management."
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

(defn- log
  "Private logging function that can be disabled in production."
  [& args]
  ;(apply println args)  ; Comment this line to disable debug output
  nil)

(defn- send! [out msg] 
  (bencode/write-bencode out msg) 
  (.flush out))

(defn- read-msg [in]   
  (bencode/read-bencode in))

(defn- read-until-done [in]
  (let [deadline (+ (System/currentTimeMillis) 10000)] ; 10 second timeout
    (loop [frames []]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for nREPL response" {:frames frames})))
      (let [m (read-msg in)
            frames (conj frames m)]
        (if (some #{"done"} (map #(String. %) (get m "status"))) 
          frames 
          (recur frames))))))

(defn- bytes->str [b]
  (when b (String. b)))

(defn- last-val [frames] 
  (some->> frames (keep #(get % "value")) last bytes->str))

(defn- last-out [frames] 
  (apply str (keep #(bytes->str (get % "out")) frames)))

(defn- last-err [frames] 
  (apply str (keep #(bytes->str (get % "err")) frames)))

(defn with-session [port f]
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

(defn eval* [{:keys [in out sid]} code ns]
  (send! out {"op" "eval" "code" code "ns" ns "session" sid})
  (read-until-done in))

(defn- switch-to-cljs-build [io build-key]
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

(defn- install-test-reporter [io]
  "Install the test reporter that captures results and failure details in global state."
  (let [reporter-code "(do 
                         (require '[cljs.test :as t])
                         (when-not (aget js/globalThis \"__NEXTDOC_RESULT__\")
                           (aset js/globalThis \"__NEXTDOC_RESULT__\" (atom nil)))
                         (when-not (aget js/globalThis \"__NEXTDOC_FAILURES__\")
                           (aset js/globalThis \"__NEXTDOC_FAILURES__\" (atom [])))
                         (t/set-env! (t/empty-env))
                         (when (contains? (methods t/report) [:cljs.test/default :summary])
                           (remove-method t/report [:cljs.test/default :summary]))
                         (when (contains? (methods t/report) [:cljs.test/default :fail])
                           (remove-method t/report [:cljs.test/default :fail]))
                         (when (contains? (methods t/report) [:cljs.test/default :error])
                           (remove-method t/report [:cljs.test/default :error]))
                         (reset! (aget js/globalThis \"__NEXTDOC_FAILURES__\") [])
                         (defmethod t/report [:cljs.test/default :fail] [m]
                           (swap! (aget js/globalThis \"__NEXTDOC_FAILURES__\") conj 
                                  {:type :fail
                                   :testing-contexts (:testing-contexts m)
                                   :testing-vars (str (:testing-vars m))
                                   :message (:message m)
                                   :expected (:expected m) 
                                   :actual (:actual m)}))
                         (defmethod t/report [:cljs.test/default :error] [m]
                           (swap! (aget js/globalThis \"__NEXTDOC_FAILURES__\") conj
                                  {:type :error
                                   :testing-contexts (:testing-contexts m)
                                   :testing-vars (str (:testing-vars m))
                                   :message (:message m)
                                   :expected (:expected m)
                                   :actual (:actual m)}))
                         (defmethod t/report [:cljs.test/default :summary] [m]
                           (let [failures @(aget js/globalThis \"__NEXTDOC_FAILURES__\")
                                 fail-count (count (filter #(= :fail (:type %)) failures))
                                 error-count (count (filter #(= :error (:type %)) failures))]
                             (reset! (aget js/globalThis \"__NEXTDOC_RESULT__\")
                                     (assoc (select-keys m [:test :pass])
                                            :fail fail-count
                                            :error error-count
                                            :failures failures))))
                         :ok)"
        reporter-frames (eval* io reporter-code "cljs.user")
        reporter-val (last-val reporter-frames)]
    (log "Reporter result:" reporter-val)
    (when (nil? reporter-val)
      (throw (ex-info "Failed to install test reporter" {:frames reporter-frames})))
    (log "✓ Installed test reporter")))

(defn- require-test-namespaces [io test-namespaces]
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

(defn- start-test-execution [io test-namespaces]
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

(defn- display-test-results [m]
  "Pretty print test results for terminal display."
  (println "\n<results>")
  (if (map? m)
    (pprint/pprint m)
    (println m))
  (println "</results>"))

(defn- format-test-output [m frames]
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

(defn- poll-for-test-results [io]
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
                _ (log "First read type:" (type first-read) "value:" first-read)
                m (if (string? first-read)
                    (try 
                      (edn/read-string first-read)
                      (catch Exception e
                        (log "Second read failed:" (.getMessage e))
                        first-read))
                    first-read)]
            (log "✓ Test results:" m)
            (log "Final type of parsed result:" (type m) "Is map?" (map? m))
            (display-test-results m)
            (format-test-output m frames)))))))

(defn run-shadow-tests [port build-id test-namespaces]
  "Runs tests in Shadow-CLJS using raw nREPL protocol with proper session management."
  (let [build-key (name build-id)]
    (with-session port
      (fn [io]
        (switch-to-cljs-build io build-key)
        (install-test-reporter io)
        (require-test-namespaces io test-namespaces)
        (start-test-execution io test-namespaces)
        (poll-for-test-results io)))))
