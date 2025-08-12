(ns io.nextdoc.shadow-nrepl
  "Raw bencode nREPL client for Shadow-CLJS with proper session management."
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

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
    (println "Connected to nREPL, cloning session...")
    ;; clone session
    (send! out {"op" "clone"})
    (let [clone-frames (read-until-done in)
          _ (println "Clone response received, frames:" (count clone-frames))
          sid (or (bytes->str (get (last clone-frames) "new-session"))
                  (bytes->str (get (last clone-frames) "session")))] ;; belt+braces
      (println "Session ID:" sid)
      (f {:in in :out out :sid sid}))))

(defn eval* [{:keys [in out sid]} code ns]
  (send! out {"op" "eval" "code" code "ns" ns "session" sid})
  (read-until-done in))

(defn run-shadow-tests [port build-id test-namespaces]
  "Runs tests in Shadow-CLJS using raw nREPL protocol with proper session management."
  (let [build-key (name build-id)]
    (with-session port
      (fn [io]
        ;; 1) switch session to CLJS build
        (println "Switching to Shadow-CLJS build:" build-key)
        (eval* io "(require 'shadow.cljs.devtools.api)" "user")
        (eval* io (str "(shadow.cljs.devtools.api/nrepl-select :" build-key ")") "user")
        
        ;; 2) smoke test to verify CLJS runtime
        (let [ping-frames (eval* io "(do (aset js/globalThis \"__ND_PING__\" 42) :ok)" "cljs.user")
              ping-val (last-val ping-frames)]
          (when (not= ping-val ":ok")
            (throw (ex-info "Failed to switch to CLJS runtime" {:frames ping-frames})))
          (println "✓ Successfully switched to CLJS runtime"))
        
        ;; 3) reporter + env (portable global)
        (let [reporter-frames (eval* io 
                                "(do (require '[cljs.test :as t])
                                     (when-not (aget js/globalThis \"__NEXTDOC_RESULT__\")
                                       (aset js/globalThis \"__NEXTDOC_RESULT__\" (atom nil)))
                                     (t/set-env! (t/empty-env))
                                     (when (contains? (methods t/report) [:cljs.test/default :summary])
                                       (remove-method t/report [:cljs.test/default :summary]))
                                     (defmethod t/report [:cljs.test/default :summary] [m]
                                       (reset! (aget js/globalThis \"__NEXTDOC_RESULT__\")
                                               (select-keys m [:test :pass :fail :error])))
                                     :ok)" 
                                "cljs.user")
              reporter-val (last-val reporter-frames)]
          (println "Reporter result:" reporter-val)
          (when (nil? reporter-val)
            (throw (ex-info "Failed to install test reporter" {:frames reporter-frames})))
          (println "✓ Installed test reporter"))
        
        ;; 4) require tests
        (let [require-expr (str "(do " 
                               (->> test-namespaces
                                    (map #(format "(require '%s :reload)" %))
                                    (clojure.string/join " "))
                               " :ok)")
              require-frames (eval* io require-expr "cljs.user")
              require-val (last-val require-frames)]
          (println "Require result:" require-val)
          (when (nil? require-val)
            (throw (ex-info "Failed to require test namespaces" {:frames require-frames})))
          (println "✓ Required test namespaces:" test-namespaces))
        
        ;; 5) run tests (non-blocking)
        (let [run-expr (str "(do (cljs.test/run-tests " 
                           (->> test-namespaces (map #(str "'" %)) (clojure.string/join " "))
                           ") :pending)")
              run-frames (eval* io run-expr "cljs.user")
              run-val (last-val run-frames)]
          (println "Run result:" run-val)
          (when (nil? run-val)
            (throw (ex-info "Failed to start tests" {:frames run-frames})))
          (println "✓ Started test execution"))
        
        ;; 6) poll for results with timeout
        (let [deadline (+ (System/currentTimeMillis) 30000)] ; 30s timeout
          (println "Polling for test results...")
          (loop [attempt 0]
            (when (> (System/currentTimeMillis) deadline)
              (throw (ex-info "Timed out waiting for test results" {})))
            (Thread/sleep 25)
            (let [frames (eval* io "(let [a (aget js/globalThis \"__NEXTDOC_RESULT__\")]
                                      (if a (pr-str @a) ::nil))" "cljs.user")
                  vstr   (last-val frames)]
              (when (= 0 (mod attempt 40)) ; Print every second
                (println "Poll attempt" attempt "result:" vstr))
              (if (or (nil? vstr) (= vstr "::nil"))
                (recur (inc attempt))
                (let [m (edn/read-string vstr)]
                  (println "✓ Test results:" m)
                  {:values m
                   :out (last-out frames)
                   :err (last-err frames)})))))))))