(ns io.nextdoc.clean-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.nextdoc.clean :as clean]))

(deftest clean-test-output-preserves-malli-exception-data
  (testing "clean-test-output preserves Malli validation error data between actual: and stack trace"
    (let [input-lines (-> (io/resource "malli-exception-output.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "{:function com.example.domain.business/process-payment-request")
          "Malli exception data should be preserved in filtered output")
      (is (str/includes? result-str ":input-exception")
          "input-exception key should be visible")
      (is (str/includes? result-str "missing required key")
          "Error message 'missing required key' should be visible")
      (is (str/includes? result-str "com.example.domain.business_test$fn__83345.invokeStatic (business_test.clj:210)")
          "Application code frame from test should be preserved")
      (is (str/includes? result-str "com.example.test_fixtures$reset_cache.invokeStatic (test_fixtures.clj:50)")
          "Application code frame from test fixtures should be preserved")
      (is (str/includes? result-str "Stack trace (cleaned")
          "Should show that stack trace was cleaned")
      (is (not (str/includes? result-str "clojure.lang.RestFn.applyTo"))
          "Internal Clojure frames should be filtered out")
      (is (not (str/includes? result-str "clojure.test$test_var$fn__9856.invoke"))
          "Internal clojure.test frames should be filtered out"))))

(deftest clean-test-output-preserves-ex-data
  (testing "Preserves ex-data maps between actual: and stack trace"
    (let [input-lines (-> (io/resource "exception-with-exdata.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "{:type :validation-error")
          "ex-data map should be preserved")
      (is (str/includes? result-str ":errors {:email")
          "Nested error details should be preserved")
      (is (str/includes? result-str "com.example.api.handler_test$fn__42156.invokeStatic (handler_test.clj:47)")
          "Application code frames should be preserved")
      (is (not (str/includes? result-str "clojure.test$test_var.invokeStatic"))
          "Internal test frames should be filtered"))))

(deftest clean-test-output-preserves-test-structure
  (testing "Preserves test headers, failures, errors, and summaries"
    (let [input-lines (-> (io/resource "multiple-test-sections.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "Testing com.example.core.utils-test")
          "First test header should be preserved")
      (is (str/includes? result-str "Testing com.example.core.validation-test")
          "Second test header should be preserved")
      (is (str/includes? result-str "FAIL in (validate-email)")
          "FAIL marker should be preserved")
      (is (str/includes? result-str "ERROR in (validate-phone)")
          "ERROR marker should be preserved")
      (is (str/includes? result-str "Ran 15 tests containing 42 assertions")
          "Test summary should be preserved")
      (is (str/includes? result-str "2 failures, 1 errors")
          "Failure/error counts should be preserved"))))

(deftest clean-test-output-shows-filtering-stats
  (testing "Shows how many internal frames were filtered"
    (let [input-lines (-> (io/resource "heavy-stack-trace.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "Stack trace (cleaned")
          "Should indicate stack trace was cleaned")
      (is (re-find #"cleaned - \d+ internal frames filtered" result-str)
          "Should show count of filtered frames")
      (is (str/includes? result-str "com.example.core.processor$process.invokeStatic (processor.clj:15)")
          "Application frames should be preserved")
      (is (str/includes? result-str "com.example.recursion_test$fn__99999.invokeStatic (recursion_test.clj:12)")
          "Test frames should be preserved")
      (is (not (str/includes? result-str "clojure.core$apply.invokeStatic"))
          "Internal clojure.core frames should be filtered")
      (is (not (str/includes? result-str "clojure.lang.RT.boundedLength"))
          "Internal clojure.lang frames should be filtered"))))

(deftest clean-test-output-handles-mixed-frame-formats
  (testing "Handles both 'at ' prefixed and bare stack frame formats"
    (let [input-lines (-> (io/resource "mixed-frame-formats.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "com.example.adapter.ServiceAdapter$invoke.invokeStatic (service_adapter.clj:78)")
          "Bare format frames should be detected and preserved")
      (is (str/includes? result-str "com.example.formats_test$fn__11111.invokeStatic (formats_test.clj:7)")
          "Application frames should be preserved regardless of format")
      (is (not (str/includes? result-str "at java.util.HashMap.get"))
          "Java internal frames with 'at ' prefix should be filtered"))))

(deftest clean-test-output-handles-exception-without-stack-trace
  (testing "Handles exceptions with ex-data but no stack trace"
    (let [input-lines (-> (io/resource "exception-no-stack-trace.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "{:timeout-ms 5000")
          "Exception data should be preserved")
      (is (str/includes? result-str ":url \"https://api.example.com/data\"")
          "All ex-data fields should be preserved")
      (is (str/includes? result-str "Ran 1 tests containing 1 assertions")
          "Test summary should follow exception data"))))

(deftest clean-test-output-handles-blank-lines
  (testing "Handles excessive blank lines while preserving structure"
    (let [input-lines (-> (io/resource "blank-line-handling.txt") slurp str/split-lines)
          result (clean/clean-test-output input-lines)
          result-str (str/join "\n" result)]
      (is (str/includes? result-str "Testing com.example.whitespace-test")
          "Test header should be preserved")
      (is (str/includes? result-str "ERROR in (blank-lines-test)")
          "Error marker should be preserved")
      (is (str/includes? result-str "expected: nil")
          "Expected line should be preserved")
      (is (str/includes? result-str "com.example.whitespace_test$fn__55555.invokeStatic")
          "Application frames should be preserved"))))
