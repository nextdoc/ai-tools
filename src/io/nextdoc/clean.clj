(ns io.nextdoc.clean
  "Utilities for cleaning test output by filtering stack traces and noise.

   Key features:
   - Preserves exception data (Malli validation errors, ex-data, etc.)
   - Filters internal Java/Clojure/Babashka frames while keeping application code
   - Detects and handles recursion patterns
   - Shows filtering statistics"
  (:require [clojure.string :as str]))

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

(defn- testing-header? [line]
  (str/starts-with? (str/trim line) "Testing "))

(defn- error-marker? [line]
  (let [t (str/trim line)]
    (or (str/starts-with? t "ERROR in")
        (str/starts-with? t "FAIL in"))))

(defn- expected-line? [line]
  (str/starts-with? (str/trim line) "expected:"))

(defn- actual-line? [line]
  (str/starts-with? (str/trim line) "actual:"))

(defn- test-summary? [line]
  (re-find #"Ran \d+ tests containing \d+ assertions" (str/trim line)))

(defn- end-marker?
  "Check if line marks end of current section"
  [line]
  (let [t (str/trim line)]
    (or (str/blank? t)
        (str/starts-with? t "Ran ")
        (error-marker? line)
        (testing-header? line))))

(defn- parse-stack-frames
  "Extract stack frames from raw output lines."
  [lines]
  (->> lines
       (map str/trim)
       (filter (fn [line]
                 (or (str/starts-with? line "at ")
                     (and (re-find #"[\w\$]+" line)
                          (re-find #"\(" line)))))
       (map (fn [line]
              (if (str/starts-with? line "at ")
                (subs line 3)  ; Remove "at " prefix
                line)))))

(defn- application-frame?
  "Check if frame is from application code (not internal libraries)."
  [frame]
  (and (not (or (str/includes? frame "java.")
                (str/includes? frame "clojure.")
                (str/includes? frame "sci.")
                (str/includes? frame "babashka.")
                (str/includes? frame "nrepl.")
                (str/includes? frame "malli.core$")
                (str/includes? frame "malli.instrument$")))
       (re-find #"\.cljs?:\d+\)" frame)))

(defn- internal-frame?
  "Check if frame should be filtered as internal implementation."
  [frame]
  (and (not (application-frame? frame))
       (or (str/includes? frame "java.lang.Exception")
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
           (str/includes? frame "sci.lang.")
           (str/includes? frame "sci.impl.")
           (str/includes? frame "sci.core$")
           (str/includes? frame "babashka.main$")
           (str/starts-with? frame "user$eval"))))

(defn- filter-internal-frames
  "Remove internal frames while keeping application code."
  [frames]
  (remove internal-frame? frames))

(defn- detect-recursion
  "Detect if frames show a recursive pattern."
  [frames]
  (and (> (count frames) 20)
       (let [sample (take 10 frames)]
         (some #(= sample (take 10 (drop (* % 10) frames)))
               (range 1 3)))))

(defn- format-stack-trace
  "Format frames with header showing filtering stats."
  [frames original-count]
  (let [filtered-count (- original-count (count frames))
        header (if (pos? filtered-count)
                 (str "    Stack trace (cleaned - " filtered-count " internal frames filtered):")
                 "    Stack trace:")
        recursion? (detect-recursion frames)]
    (if recursion?
      (concat [header]
              (map #(str "    " %) (take 8 frames))
              ["    ... [Recursion detected - pattern repeats] ..."]
              (map #(str "    " %) (take-last 2 frames)))
      (concat [header]
              (map #(str "    " %) (take 20 frames))))))

(defn- clean-stack-trace
  "Cleans and formats a stack trace for more readable test output.
   Removes Java/Clojure/Babashka internals, detects recursion patterns,
   limits output length, and adds filtering indicators showing how many frames were removed."
  [stack-trace-lines]
  (let [raw-frames (parse-stack-frames stack-trace-lines)
        filtered-frames (filter-internal-frames raw-frames)]
    (format-stack-trace filtered-frames (count raw-frames))))

(defn- process-normal-mode
  "Process a line in normal mode."
  [state line]
  (cond
    (testing-header? line)
    (-> state
        (assoc :mode :normal :error-header nil :stack-buffer [])
        (update :result conj "" line))

    (error-marker? line)
    (-> state
        (assoc :mode :normal :error-header line :stack-buffer [])
        (update :result conj "" line))

    (and (:error-header state)
         (not (expected-line? line))
         (not (actual-line? line)))
    (update state :result conj line)

    (expected-line? line)
    (-> state
        (assoc :error-header nil :stack-buffer [])
        (update :result conj line))

    (actual-line? line)
    (-> state
        (assoc :mode :exception-data :stack-buffer [])
        (update :result conj line))

    (test-summary? line)
    (-> state
        (assoc :mode :normal :stack-buffer [])
        (update :result conj "" line))

    :else
    (update state :result
            (fn [r]
              (if (str/blank? (str/trim line))
                r  ; Skip extra blank lines
                (conj r line))))))

(defn- process-exception-data-mode
  "Process a line in exception-data mode."
  [state line]
  (cond
    (looks-like-stack-frame? line)
    (assoc state :mode :stack-trace :stack-buffer [line])

    (end-marker? line)
    (-> state
        (assoc :mode :normal)
        (update :result conj line))

    :else
    (update state :result conj line)))

(defn- process-stack-trace-mode
  "Process a line in stack-trace mode. Returns [new-state reprocess?] tuple."
  [state line]
  (cond
    (or (looks-like-stack-frame? line)
        (and (not (str/blank? (str/trim line)))
             (not (end-marker? line))))
    [(update state :stack-buffer conj line) false]

    (end-marker? line)
    (let [cleaned (clean-stack-trace (:stack-buffer state))
          new-result (into (:result state) cleaned)]
      [(assoc state :mode :normal :result new-result :stack-buffer []) true])

    :else
    [state false]))

(defn- process-line
  "Process a single line based on current mode. Returns [new-state reprocess?] tuple."
  [state line]
  (case (:mode state)
    :normal [(process-normal-mode state line) false]
    :exception-data [(process-exception-data-mode state line) false]
    :stack-trace (process-stack-trace-mode state line)))

(defn clean-test-output
  "Cleans test output to remove excessive stack traces and noise.
   Preserves exception data (like Malli validation errors) that appears
   between the 'actual:' line and the start of the stack trace."
  [output-lines]
  (let [lines (if (string? output-lines)
                (str/split-lines output-lines)
                output-lines)]
    (loop [state {:mode :normal
                  :result []
                  :stack-buffer []
                  :error-header nil}
           remaining lines]
      (if (empty? remaining)
        ;; Handle end of input - flush any buffered stack trace
        (if (= :stack-trace (:mode state))
          (into (:result state) (clean-stack-trace (:stack-buffer state)))
          (:result state))

        (let [line (first remaining)
              [new-state reprocess?] (process-line state line)]
          (recur new-state
                 (if reprocess? remaining (rest remaining))))))))
