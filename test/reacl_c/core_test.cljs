(ns reacl-c.test.core-test
  (:require [reacl-c.core :as c :include-macros true]
            [reacl-c.base :as base]
            [reacl-c.dom :as dom]
            [reacl-c.test-util.core :as tu]
            [reacl-c.browser :as browser]
            [active.clojure.lens :as lens]
            [active.clojure.functions :as f]
            [schema.core :as s]
            [reacl-c.test-util.perf :as perf]
            [cljs.test :refer (is deftest testing) :include-macros true]))

#_(deftest static-performance
  (let [env (tu/env (dom/div {:id "a"}
                             (apply dom/div (repeat 20 (dom/span)))
                             (c/fragment (apply dom/span (repeat 15 "x")))))
        tupd (fn [n]
               (let [st (js/window.performance.now)]
                 (doseq [i (range n)]
                   (tu/update! env i))
                 (- (js/window.performance.now) st)))]
    (tu/mount! env 0)
    (is (= nil
           [(tupd 100) (tupd 1000) (tupd 10000)]))
    ;; with opt: [147.63500000117347 962.6399999833666 7743.50499996217]
    ;; no opt:   [241.18999997153878 1239.70500001451 8591.40999999363]
    ;; at least it does not get worse :-/

    ;; opt: (not (= nil [136.2799999769777 924.7550000436604 8942.974999954458]))
    ;; no opt: (not (= nil [127.08000000566244 1228.9699999964796 11590.480000013486]))
    
    )
  )

(deftest item-equality-test
  ;; items should be referentially equal
  (testing "fragment"
    (is (= (c/fragment) (c/fragment)))
    (is (= (c/fragment (dom/div)) (c/fragment (dom/div))))
    ;; to simplify conditional rendering (via 'when'), nil is the same as an empty fragment
    (is (= nil (c/fragment)))
    (is (= nil c/empty)))
  (testing "div"
    (is (= (dom/div) (dom/div)))
    (is (= (dom/div "a") (dom/div "a")))
    (is (= (dom/div {:onclick identity}) (dom/div {:onclick identity})))
    (is (= (dom/div (dom/div "a")) (dom/div (dom/div "a")))))
  (testing "dynamic"
    (let [f (fn [x] (dom/div x))]
      (is (= (c/dynamic f) (c/dynamic f)))))
  (testing "focus"
    (is (= (c/focus :a (dom/div)) (c/focus :a (dom/div)))))
  (testing "handle-action"
    (let [f (fn [state a])]
      (is (= (c/handle-action (dom/div) f) (c/handle-action (dom/div) f)))))
  (testing "add-state"
    (is (= (c/add-state :a :b (dom/div)) (c/add-state :a :b (dom/div)))))
  (testing "keyed"
    (is (= (c/keyed (dom/div) :a) (c/keyed (dom/div) :a))))
  (testing "once"
    (is (= (c/once (f/constantly (c/return :action :a)) (f/constantly (c/return :action :b))) (c/once (f/constantly (c/return :action :a)) (f/constantly (c/return :action :b))))))
  (testing "with-async-actions"
    (is (= (c/with-async-actions :f :a) (c/with-async-actions :f :a))))
  (testing "monitor-state"
    (is (= (c/handle-state-change (dom/div) :f) (c/handle-state-change (dom/div) :f))))
  )

(deftest subscription-test
  (let [subscribed (atom nil)
        sub-impl (fn [deliver! x]
                   (reset! subscribed deliver!)
                   (fn []
                     (reset! subscribed nil)))
        sub (c/subscription sub-impl :x)
        env (tu/env (c/dynamic (fn [v]
                                 (if v sub ""))))]
    (tu/mount! env false)
    (is (not @subscribed))
    
    ;; sub on mount
    (tu/update! env true)
    (is @subscribed)

    ;; test actions emitted.
    (is (= (c/return :action ::act)
           (tu/with-env-return env
             (fn []
               (@subscribed ::act)))))
    
    ;; unsub on unmount
    (tu/update! env false)
    (is (not @subscribed))))

(deftest defn-subscription-test
  (let [x (atom nil)]
    (c/defn-subscription defn-subscription-test-1 deliver! [arg]
      (reset! x arg)
      (fn [] nil))

    (-> (tu/env (defn-subscription-test-1 :arg))
        (tu/mount! nil))
    (is (= :arg @x))))

(deftest defn-named-test
  (testing "schematized args and state"
    (c/defn-named ^:always-validate defn-named-test-1 [a :- schema.core/Str]
      (dom/div (str a)))
    (is (base/item? (defn-named-test-1 "foo")))

    (try (defn-named-test-1 42)
         (is false)
         (catch :default e
           (is true))))

  (testing "it is named"
    (c/defn-named defn-named-test-2 "mydoc" [a]
      (dom/div (str a)))
    (is (contains? (meta defn-named-test-2) :reacl-c.core/name-id))

    ;; no clue why this test fails: (is (= '([a]) (:arglists (meta #'defn-named-test-2))))
    (is (= "mydoc" (:doc (meta #'defn-named-test-2)))))

  (testing "checks arity"
    (c/defn-named defn-named-test-3 [a]
      (dom/div (str a)))
    (try (defn-named-test-3)
         (is false)
         (catch :default e
           (is true)))))

(deftest defn-dynamic-test
  (testing "schematized args and state"
    (c/defn-dynamic ^:always-validate defn-dynamic-test-1 state :- schema.core/Int [a :- schema.core/Str]
      (dom/div (str state a)))
    (is (base/item? (defn-dynamic-test-1 "foo")))

    (do (tu/mount! (tu/env (defn-dynamic-test-1 "abc")) 42)
        (is true))

    ;; throws on data not matching schema:
    (tu/preventing-error-log
     (fn []
       (try (defn-dynamic-test-1 42)
         
            (is false)
            (catch :default e
              (is true)))
       (try (tu/mount! (tu/env (defn-dynamic-test-1 "abc")) "42")
            (is false)
            (catch :default e
              (is true))))))

  (testing "it is named"
    (c/defn-dynamic defn-dynamic-test-2 "mydoc" state [a]
      (dom/div (str state a)))
    (is (contains? (meta defn-dynamic-test-2) :reacl-c.core/name-id))
    ;; no clue why this test fails: (is (= '([a]) (:arglists (meta #'defn-dynamic-test-2))))
    (is (= "mydoc" (:doc (meta #'defn-dynamic-test-2))))
    )

  (testing "checks arity on call"
    (c/defn-dynamic defn-dynamic-test-3 state [a]
      (dom/div (str state a)))
    (try (defn-dynamic-test-3)
         (is false)
         (catch :default e
           (is true))))
  
  (testing "a regression with varargs"
    (c/defn-dynamic defn-dynamic-test-4 state [& args]
      (dom/div))

    (is (= (defn-dynamic-test-4) (defn-dynamic-test-4)))

    (is (= (defn-dynamic-test-4 "x") (defn-dynamic-test-4 "x")))

    (c/defn-dynamic defn-dynamic-test-5 "docstring" state [a1 & args]
      (dom/div))

    (is (= "docstring")
        (:doc (meta #'defn-dynamic-test-5)))

    (is (= (defn-dynamic-test-5 "x") (defn-dynamic-test-5 "x")))

    (is (= (defn-dynamic-test-5 "x" "y") (defn-dynamic-test-5 "x" "y")))))

(deftest static-test
  (let [env (tu/env (c/static #(c/dynamic (fn [st] (dom/div (str st))))))]
    (tu/mount! env "foo")
    (is (nil? (tu/find env (dom/div "foo"))))
    (tu/update! env "bar")
    (is (nil? (tu/find env (dom/div "bar")))))

  (c/defn-static static-test-1 [a]
    (dom/div (str a)))
  (let [env (tu/env (c/dynamic (fn [st] (static-test-1 st))))]
    (tu/mount! env "foo")
    (is (some? (tu/find env (dom/div "foo"))))
    (tu/update! env "bar")
    (is (some? (tu/find env (dom/div "bar")))))

  (let [env (tu/env (c/static #(c/once (fn [_] (c/return :state "foo")))))]
    (tu/preventing-error-log
     (fn []
       (try (tu/mount! env :x)
            (is false)
            (catch :default e
              (is true)))))))


(deftest with-async-messages-test
  (let [env (tu/env (c/with-ref (fn [ref]
                                  (c/with-async-messages
                                    (fn [send!]
                                      (dom/div (-> (c/handle-message (fn [state msg]
                                                                       (c/return :state msg))
                                                                     (dom/div))
                                                   (c/set-ref ref))
                                               (-> (c/once (f/constantly (c/return :action ::test)))
                                                   (c/handle-action (fn [_ _]
                                                                      (send! ref :msg)
                                                                      (c/return))))))))))]
    (is (= (c/return :state :msg)
           (tu/mount! env :st)))))

(deftest sync-messages-test
  (let [env (tu/env (c/with-ref (fn [ref]
                                  (c/with-async-messages
                                    (fn [send!]
                                      (dom/div (-> (c/handle-message (fn [state msg]
                                                                       (c/return :state msg))
                                                                     (dom/div))
                                                   (c/set-ref ref))
                                               (c/once (f/constantly (c/return :message [ref :msg])))))))))]
    (is (= (c/return :state :msg)
           (tu/mount! env :st)))))

(deftest app-send-message-test
  (let [e (js/document.createElement "div")
        received (atom nil)
        app (browser/run e
              (c/handle-message (fn [state msg]
                                  (reset! received msg)
                                  (c/return))
                                c/empty)
              nil)]
    (c/send-message! app ::hello)
    (is (= ::hello @received))))

(deftest map-messages-test
  (let [env (tu/env (c/map-messages (fn [msg] [:x msg])
                                    (c/handle-message (fn [state msg]
                                                        (c/return :state msg))
                                                      (dom/div))))]
    (tu/mount! env :st)
    (is (= (c/return :state [:x :msg])
           (tu/send-message! (tu/get-component env) :msg)))))

(deftest redirect-messages-test
  (let [env (tu/env (c/with-ref
                      (fn [ref]
                        (c/fragment
                         (c/redirect-messages ref
                                              (dom/div (dom/div)
                                                       (-> (c/handle-message (fn [state msg]
                                                                               (c/return :state msg))
                                                                             (dom/div))
                                                           (c/set-ref ref))))))))]
    (tu/mount! env :st)
    (is (= (c/return :state :msg)
           (tu/send-message! (tu/get-component env) :msg))))
  )

(deftest once-test
  ;; state dependant.
  (let [env (tu/env (c/once (f/partial c/return :action)
                            (f/partial c/return :action)))]
    (is (= (c/return :action ::up)
           (tu/mount!! env ::up)))

    (is (= (c/return)
           (tu/update!! env ::up)))

    (is (= (c/return :action ::new)
           (tu/update!! env ::new)))

    (tu/update!! env ::down)
    (is (= (c/return :action ::down)
           (tu/unmount!! env))))

  ;; state independant
  (let [env (tu/env (c/once (f/constantly (c/return :action ::up))
                            (f/constantly (c/return :action ::down))))]
    (is (= (c/return :action ::up)
           (tu/mount!! env true))) 

    (is (= (c/return)
           (tu/update!! env true)))

    (is (= (c/return)
           (tu/update!! env false))) 

    (is (= (c/return :action ::down)
           (tu/unmount!! env)))))

(deftest with-ref
  ;; important for with-ref, must be the same ref for a unique item, even if the state changes.
  (let [last-ref (atom nil)
        env (tu/env (c/with-ref (fn [ref]
                                  (reset! last-ref ref)
                                  c/empty)))]
    (let [_ (tu/mount!! env true)
          r1 @last-ref

          _ (tu/update!! env false)
          r3 @last-ref

          _ (tu/update!! env false)
          r2 @last-ref]

      (is (= r1 r2) "ref is same on same state")

      (is (= r1 r3) "ref is same on different states"))))

(deftest effect-test
  (c/defn-effect effect-test-1 [foo]
    (c/return))

  (is (= (effect-test-1 :foo)
         (effect-test-1 :foo))))

(deftest handle-effect-result-test
  (testing "results can be received"
    (let [count (atom 0)
          eff (c/effect (fn []
                          (swap! count inc)))
          env (tu/env (c/handle-effect-result (fn [state uuid]
                                                (c/return :state uuid))
                                              eff))]
      (is (= (c/return :state 1)
             (tu/mount!! env nil)))
    
      (is (= (c/return)
             (tu/update!! env nil)))))

  (testing "effects can be mapped and handled"
    (let [eff (c/effect (fn []
                          :foo))
          env (tu/env (-> (c/handle-effect-result (fn [state uuid]
                                                    (c/return :state uuid))
                                                  eff)
                          (c/map-effects {eff (c/const-effect :bar)})))]
      (is (= (c/return :state :bar)
             (tu/mount!! env nil)))))
  )

(deftest ref-let-test
  (let [env (tu/env (c/ref-let [it-1 (c/handle-message (fn [state msg]
                                                         (c/return :state [:it-1 msg]))
                                                       c/empty)
                                it-2 (c/handle-message (fn [state msg]
                                                         (c/return :state [:it-2 msg]))
                                                       c/empty)]
                               (c/handle-message
                                (fn [state msg]
                                  (if (odd? msg)
                                    (c/return :message [it-1 msg])
                                    (c/return :message [it-2 msg])))
                                (dom/div it-1 it-2))))]
    (tu/mount! env nil)
    (is (= (c/return :state [:it-1 1])
           (tu/send-message! env 1)))
    (is (= (c/return :state [:it-2 2])
           (tu/send-message! env 2)))))

(deftest handle-action-test
  (testing "basics"
    (let [foobar (c/name-id "foobar")
          item (c/named foobar (dom/div))
          env (tu/env (c/handle-action item
                                       (fn [state a]
                                         (c/return :state (conj state a)))))]
    
      (tu/mount! env [])
      (is (some? (tu/find env item)))
    
      (is (= (c/return :state [:a])
             (tu/inject-action! (tu/find env item)
                                :a)))

      (tu/update! env [:a])
      (is (= (c/return :state [:a :b])
             (tu/inject-action! (tu/find env item)
                                :b)))))
  (testing "plain state return"
    (let [item (c/dynamic (f/constantly (dom/div)))
          env (tu/env (c/handle-action item
                                       conj))]
      (tu/mount! env [])
      (is (= (c/return :state [:a])
             (tu/inject-action! (tu/find env item)
                                :a))))))

(deftest map-effects-test
  (let [foobar (c/name-id "foobar")
        item (c/named foobar (dom/div))]

    (testing "simple replacement"
      (let [a (atom 0)
            eff1 (c/effect (fn [] (reset! a 1)))
            eff2 (c/effect (fn [] (reset! a 2)))
            env (tu/env (c/map-effects item
                                       {eff1 eff2}))]

        (tu/mount! env [])
        (is (= (c/return)
               (tu/inject-action! (tu/find env item)
                                  eff1)))
        (is (= 2 @a))))

    (testing "in compositions"
      (let [a (atom 0)
            b (atom 0)
            eff1 (c/effect (fn [] (swap! a inc)))
            eff2 (c/effect (fn [] (swap! b inc)))
            
            env (tu/env (c/map-effects item
                                       {eff1 eff2}))]

        (tu/mount! env [])
        (tu/inject-action! (tu/find env item)
                           (c/par-effects eff1 eff1))
        (is (= 0 @a))
        (is (= 2 @b))))    
    ))

(deftest try-catch-test
  (let [env (tu/env (c/try-catch (c/dynamic #(if (:throw? %)
                                               (throw (ex-info "Test" {:value :foo}))
                                               (dom/div "Ok")))
                                 (c/dynamic (fn [[state error]]
                                              (dom/div "Error" (pr-str error)
                                                       (if (:reset? state)
                                                         (c/once (f/constantly (c/return :state [{} nil])))
                                                         c/empty))))))]
    (tu/mount! env {})
    (is (some? (tu/find env (dom/div "Ok"))))

    (tu/preventing-error-log
     (fn []
       (tu/update! env {:throw? true})))
    (is (some? (tu/find env (dom/div "Error"))))

    (is (= (c/return :state {})
           (tu/update! env {:throw? false :reset? true})))
    (is (some? (tu/find env (dom/div "Ok"))))
    ))

(deftest with-state-test
  (let [env (tu/env (c/with-state foo (dom/div foo)))]
    (tu/mount! env "Ok")
    (is (some? (tu/find env (dom/div "Ok")))))

  (let [env (tu/env (c/with-state foo :- s/Str (dom/div foo)))]
    (tu/mount! env "Ok")
    (is (some? (tu/find env (dom/div "Ok")))))

  (let [env (tu/env (c/with-state :static (c/dynamic dom/div)))]
    (tu/mount! env :bar)
    (is (some? (tu/find env (dom/div)))))

  (let [env (tu/env (c/with-state [a b] (dom/div a b)))]
    (tu/mount! env ["foo" "bar"])
    (is (some? (tu/find env (dom/div "foo" "bar")))))

  (let [env (tu/env (c/with-state [a b :local "bar"] (dom/div a b)))]
    (tu/mount! env "foo")
    (is (some? (tu/find env (dom/div "foo" "bar"))))))

(deftest defn-test
  (c/defn defn-test-1 "foo" [a :- s/Str]
    (dom/div a))

  (is (= "foo" (:doc (meta #'defn-test-1))))
  
  (let [env (tu/env (defn-test-1 "Ok"))]
    (tu/mount! env "Ok")
    (is (some? (tu/find env (dom/div "Ok")))))

  (c/defn defn-test-2 [p]
    {:pre [(string? p)]}
    (c/with-state [a b :local "bar"]
      (dom/div a b p)))

  (let [env (tu/env (defn-test-2 "baz"))]
    (tu/mount! env "foo")
    (is (some? (tu/find env (dom/div "foo" "bar" "baz")))))

  ;; and with-state optimized (made static)
  (is (not (perf/find-first-difference (defn-test-2 "foo") (defn-test-2 "foo"))))
  )
