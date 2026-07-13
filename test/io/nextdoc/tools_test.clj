(ns io.nextdoc.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.nextdoc.tools :as tools]))

(deftest duplicate-flags-test
  (testing "single flags pass"
    (is (= #{} (tools/duplicate-flags ["-n" "a.b" "-d" "src,test"]))))
  (testing "repeated short flag detected"
    (is (= #{:namespaces} (tools/duplicate-flags ["-n" "a.b" "-n" "c.d"]))))
  (testing "mixed short and long form of same flag detected"
    (is (= #{:namespaces} (tools/duplicate-flags ["-n" "a.b" "--namespaces" "c.d"]))))
  (testing "multiple repeated flags all reported"
    (is (= #{:namespaces :directories}
           (tools/duplicate-flags ["-n" "a" "-n" "b" "-d" "src" "-d" "test"])))))

(deftest path-like-entries-test
  (testing "namespace symbols pass"
    (is (= [] (tools/path-like-entries ["components.cdb.cdb-records-test"]))))
  (testing "slash notation for individual tests passes"
    (is (= [] (tools/path-like-entries ["components.cdb.cdb-records-test/my-test"]))))
  (testing "file extension rejected"
    (is (= ["test/components/foo_test.clj"]
           (tools/path-like-entries ["test/components/foo_test.clj"]))))
  (testing "cljs and cljc extensions rejected"
    (is (= ["a/b.cljs" "a/b.cljc"] (tools/path-like-entries ["a/b.cljs" "a/b.cljc"]))))
  (testing "multiple slashes rejected even without extension"
    (is (= ["test/components/foo"] (tools/path-like-entries ["test/components/foo"]))))
  (testing "mixed list returns only bad entries"
    (is (= ["src/foo.clj"]
           (tools/path-like-entries ["a.b-test" "src/foo.clj" "a.b-test/one"])))))
