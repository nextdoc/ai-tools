(do 
  (require '[cljs.test :as t])
  (when-not (aget js/globalThis "__NEXTDOC_RESULT__")
    (aset js/globalThis "__NEXTDOC_RESULT__" (atom nil)))
  (when-not (aget js/globalThis "__NEXTDOC_FAILURES__")
    (aset js/globalThis "__NEXTDOC_FAILURES__" (atom [])))
  (t/set-env! (t/empty-env))
  (when (contains? (methods t/report) [:cljs.test/default :summary])
    (remove-method t/report [:cljs.test/default :summary]))
  (when (contains? (methods t/report) [:cljs.test/default :fail])
    (remove-method t/report [:cljs.test/default :fail]))
  (when (contains? (methods t/report) [:cljs.test/default :error])
    (remove-method t/report [:cljs.test/default :error]))
  (reset! (aget js/globalThis "__NEXTDOC_FAILURES__") [])
  (defmethod t/report [:cljs.test/default :fail] [m]
    (swap! (aget js/globalThis "__NEXTDOC_FAILURES__") conj 
           {:type :fail
            :testing-contexts (:testing-contexts m)
            :testing-vars (str (:testing-vars m))
            :message (:message m)
            :expected (:expected m) 
            :actual (:actual m)}))
  (defmethod t/report [:cljs.test/default :error] [m]
    (swap! (aget js/globalThis "__NEXTDOC_FAILURES__") conj
           {:type :error
            :testing-contexts (:testing-contexts m)
            :testing-vars (str (:testing-vars m))
            :message (:message m)
            :expected (:expected m)
            :actual (:actual m)}))
  (defmethod t/report [:cljs.test/default :summary] [m]
    (let [failures @(aget js/globalThis "__NEXTDOC_FAILURES__")
          fail-count (count (filter #(= :fail (:type %)) failures))
          error-count (count (filter #(= :error (:type %)) failures))]
      (reset! (aget js/globalThis "__NEXTDOC_RESULT__")
              (assoc (select-keys m [:test :pass])
                     :fail fail-count
                     :error error-count
                     :failures failures))))
  :ok)