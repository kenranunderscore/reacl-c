(ns reacld.core
  (:require [reacld.impl :as impl]
            [reacld.base :as base]))

(defn run [dom e initial-state] ;; TODO: move to reacl/react namespace?
  (impl/run dom e initial-state))

(defn dom-attributes? [v]
  (and (map? v)
       (not (satisfies? base/E v))))

(defn- analyze-dom-args [args]
  (if (empty? args)
    [{} args]
    (let [x (first args)]
      (if (dom-attributes? x)
        [x (rest args)]
        [{} args]))))

(defn- event? [k]
  (.startsWith (name k) "on"))

(defn- split-events [attrs]
  ;; OPT
  [(into {} (remove #(event? (first %)) attrs))
   (into {} (filter #(event? (first %)) attrs))])

(defn with-async-actions [f & args]
  (base/WithAsyncActions. f args))

(defn- dom [f]
  ;; TODO: use (with-async-actions (fn [deliver! ])) ?
  (fn [& args]
    (let [[attrs_ children] (analyze-dom-args args)
          [attrs events] (split-events attrs_)]
      (base/Dom. f attrs events children))))

(def div (dom "div"))
(def input (dom "input"))
(def form (dom "form"))
(def button (dom "button"))
(def h3 (dom "h3"))
(def fragment (dom "fragment"))
;; TODO: rest of dom; other namespace?

(defn dynamic [f & args]
  (base/WithState. f args))

(defn focus [e lens]
  (base/Focus. e lens))

(def pass-action (base/PassAction.))

(defn multi-action [& actions]
  (base/MultiAction. actions))

(def no-action (multi-action))

(defn handle-actions
  "Handle all action emitted by e into a new state which must be
  returned by `(f action & args)`."
  [e f & args]
  (base/HandleAction. e f args))

(let [h (fn [app-state action pred f args]
          (if (pred action)
            (apply f app-state action args)
            pass-action))]
  (defn handle-action [e pred f & args]
    (handle-actions e h pred f args)))

(defn map-actions [e f & args]
  (base/MapAction. e f args))

(defrecord ^:private SetStateAction [new-state])

(let [set (fn [state {new-state :new-state}]
            new-state)
      set? #(and (instance? SetStateAction %) %)]
  (defn with-state-setter [f & args]
    ;; TODO 'args' not really neeeded, if f is called immediately.
    ;; TODO: must that action be unique to 'this state'? Could it interfere with others? or use a message then?
    (-> (apply f ->SetStateAction args)
        (handle-action set? set))))

(let [h (fn [set-state f args]
          (apply dynamic f set-state args))]
  (defn interactive [f & args]
    (with-state-setter h f args)))

(defn- id-merge [m1 m2]
  (reduce-kv (fn [r k v]
               (if (identical? (get r k) v)
                 r
                 (assoc r k v)))
             m1
             m2))

(defn merge-lens
  ([[s1 s2]] (merge s1 s2))
  ([[s1 s2] ns]
   ;; Note: if s1/s2 are records, then this restores that:
   ;; id-merge makes the result be identical? if all updated keys are identical?
   [(id-merge s1 (select-keys ns (keys s1)))
    (id-merge s2 (select-keys ns (keys s2)))]))

(defn id-lens
  ([v] v)
  ([_ v] v))

(defn add-state [initial lens e] ;; aka extend-state?
  (base/LocalState. (focus e lens) initial))

(defn hide-state [e initial lens]
  (add-state initial lens e))

(defn hide-merged-state [e initial]
  (add-state initial merge-lens e))

(defn- isolate-lens
  ([[outer inner]] inner)
  ([[outer inner] new-inner] [outer new-inner]))

(defn isolate-state [initial-state e]
  (add-state initial-state isolate-lens e))

(defn keyed [e key]
  ;; FIXME: because we wrap classes so much, keys can easily not be where they 'should' be - maybe copy the keys when wrapping?
  (base/Keyed. e key))

(defn while-mounted [e mount unmount]
  (base/WhileMounted. e mount unmount))

(defrecord ^:private EffectAction [f args]
  base/Effect
  (-run-effect! [this] (apply f args)))

(defn effect-action [f & args]
  (EffectAction. f args))

(letfn [(stu [deliver! f args]
          (while-mounted (fragment) ;; TODO: fragment, or wrap an existing element?
                         (fn []
                           (apply f deliver! args))
                         (fn [stop!]
                           (stop!))))]
  (defn subscribe [f & args]
    (with-async-actions stu f args)))

(defn monitor-state [e f & args]
  (base/MonitorState. e f args))

;; TODO: allow access to side-effects of rendering, like .clientHeight of the dom (did-update + ref maybe)
