(ns io.nextdoc.jvm-nrepl
  "Minimal bencode nREPL client for the JVM test runner.

   Replaces babashka/nrepl-client for JVM test execution because that client
   discards :err frames, which hid every real error behind generic messages
   like 'Warning: No result from nREPL execution'. This client returns
   {:vals [...] :out string :err string} so callers can surface the actual
   failure."
  (:require [bencode.core :as bencode])
  (:import (java.io PushbackInputStream)
           (java.net Socket)))

(def ^:private read-timeout-ms
  "Per-read socket timeout. Generous because a single eval can legitimately
   be silent for a long time (large reloads, slow integration tests)."
  600000)

(defn- bytes->str [b]
  (when b (String. ^bytes b "UTF-8")))

(defn- decode-msg
  "Decodes a raw bencode message map into keyword keys with string values."
  [msg]
  (reduce-kv (fn [m k v]
               (assoc m (keyword k)
                        (if (bytes? v)
                          (bytes->str v)
                          (mapv #(if (bytes? %) (bytes->str %) %) v))))
             {}
             msg))

(defn- read-until-done
  "Reads messages for the given id until a 'done' status arrives.
   Accumulates :vals, :out and :err across all frames."
  [in id]
  (loop [acc {:vals [] :out (StringBuilder.) :err (StringBuilder.)}]
    (let [msg (decode-msg (bencode/read-bencode in))
          acc (cond-> acc
                (and (= id (:id msg)) (:value msg)) (update :vals conj (:value msg))
                (:out msg) (update :out #(.append ^StringBuilder % ^String (:out msg)))
                (:err msg) (update :err #(.append ^StringBuilder % ^String (:err msg))))]
      (if (and (= id (:id msg))
               (some #{"done"} (:status msg)))
        {:vals (:vals acc)
         :out (str (:out acc))
         :err (str (:err acc))}
        (recur acc)))))

(defn eval-expr
  "Evaluates code on the nREPL server at port in a fresh session.
   Returns {:vals [string ...] :out string :err string}."
  [{:keys [host port expr]}]
  (with-open [sock (Socket. ^String (or host "127.0.0.1") (int port))
              in (PushbackInputStream. (.getInputStream sock))
              out (.getOutputStream sock)]
    (.setSoTimeout sock read-timeout-ms)
    (bencode/write-bencode out {"op" "clone" "id" "clone-1"})
    (.flush out)
    (let [session (loop []
                    (let [msg (decode-msg (bencode/read-bencode in))]
                      (or (:new-session msg) (recur))))]
      (bencode/write-bencode out {"op" "eval" "code" expr "id" "eval-1" "session" session})
      (.flush out)
      (read-until-done in "eval-1"))))
