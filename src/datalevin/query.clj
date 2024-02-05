(ns ^:no-doc datalevin.query
  (:require
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [clojure.pprint :as pp]
   [datalevin.db :as db]
   [datalevin.relation :as r]
   [datalevin.built-ins :as built-ins]
   [datalevin.util :as u :refer [raise cond+]]
   [datalevin.lru :as lru]
   [datalevin.spill :as sp]
   [datalevin.parser :as dp]
   [datalevin.pull-api :as dpa]
   [datalevin.timeout :as timeout])
  (:import
   [clojure.lang ILookup LazilyPersistentVector]
   [datalevin.relation Relation]
   [datalevin.parser BindColl BindIgnore BindScalar BindTuple Constant
    FindColl FindRel FindScalar FindTuple PlainSymbol RulesVar SrcVar
    Variable Pattern]
   [org.eclipse.collections.impl.map.mutable UnifiedMap]
   [org.eclipse.collections.impl.list.mutable FastList]
   [java.util List]))

(defn spy [x] (pp/pprint x) x)

;; ----------------------------------------------------------------------------

(def ^:dynamic *query-cache* (lru/cache 32 :constant))

(declare -collect -resolve-clause resolve-clause)

;; Records

(defrecord Context [parsed-q rels sources rules clauses graph plan])

;; Utilities

(defn single
  [coll]
  (assert (nil? (next coll)) "Expected single element")
  (first coll))

(defn intersect-keys
  [attrs1 attrs2]
  (set/intersection (set (keys attrs1)) (set (keys attrs2))))

(defn- looks-like?
  [pattern form]
  (cond
    (= '_ pattern)    true
    (= '[*] pattern)  (sequential? form)
    (symbol? pattern) (= form pattern)

    (sequential? pattern)
    (if (= (last pattern) '*)
      (and (sequential? form)
           (every? (fn [[pattern-el form-el]] (looks-like? pattern-el form-el))
                   (map vector (butlast pattern) form)))
      (and (sequential? form)
           (= (count form) (count pattern))
           (every? (fn [[pattern-el form-el]] (looks-like? pattern-el form-el))
                   (map vector pattern form))))
    :else ;; (predicate? pattern)
    (pattern form)))

(defn source? [sym] (and (symbol? sym) (= \$ (first (name sym)))))

(defn free-var? [sym] (and (symbol? sym) (= \? (first (name sym)))))

(defn lookup-ref? [form] (looks-like? [keyword? '_] form))

;;

(defn parse-rules
  [rules]
  (let [rules (if (string? rules) (edn/read-string rules) rules)]
    (dp/parse-rules rules) ;; validation
    (group-by ffirst rules)))

(defn empty-rel
  ^Relation [binding]
  (let [vars (->> (dp/collect-vars-distinct binding)
                  (map :symbol))]
    (r/relation! (zipmap vars (range)) (FastList.))))

(defprotocol IBinding
  ^Relation (in->rel [binding value]))

(extend-protocol IBinding
  BindIgnore
  (in->rel [_ _]
    (r/prod-rel))

  BindScalar
  (in->rel [binding value]
    (r/relation! {(get-in binding [:variable :symbol]) 0}
                 (doto (FastList.) (.add (into-array Object [value])))))

  BindColl
  (in->rel [binding coll]
    (cond
      (not (u/seqable? coll))
      (raise "Cannot bind value " coll " to collection " (dp/source binding)
             {:error :query/binding, :value coll, :binding (dp/source binding)})
      (empty? coll)
      (empty-rel binding)
      :else
      (->> coll
           (map #(in->rel (:binding binding) %))
           (reduce r/sum-rel))))

  BindTuple
  (in->rel [binding coll]
    (cond
      (not (u/seqable? coll))
      (raise "Cannot bind value " coll " to tuple " (dp/source binding)
             {:error :query/binding, :value coll, :binding (dp/source binding)})
      (< (count coll) (count (:bindings binding)))
      (raise "Not enough elements in a collection " coll " to bind tuple "
             (dp/source binding)
             {:error :query/binding, :value coll, :binding (dp/source binding)})
      :else
      (reduce r/prod-rel
              (map #(in->rel %1 %2) (:bindings binding) coll)))))

(defn resolve-in
  [context [binding value]]
  (cond
    (and (instance? BindScalar binding)
         (instance? SrcVar (:variable binding)))
    (update context :sources assoc (get-in binding [:variable :symbol]) value)
    (and (instance? BindScalar binding)
         (instance? RulesVar (:variable binding)))
    (assoc context :rules (parse-rules value))
    :else
    (update context :rels conj (in->rel binding value))))

(defn resolve-ins
  [context values]
  (let [bindings (get-in context [:parsed-q :qin])]
    (reduce resolve-in context (zipmap bindings values))))

;;

(def ^{:dynamic true
       :doc "List of symbols in current pattern that might potentiall be resolved to refs"}
  *lookup-attrs* nil)

(def ^{:dynamic true
       :doc "Default pattern source. Lookup refs, patterns, rules will be resolved with it"}
  *implicit-source* nil)

(defn getter-fn
  [attrs attr]
  (let [idx (attrs attr)]
    (if (contains? *lookup-attrs* attr)
      (if (int? idx)
        (let [idx (int idx)]
          (fn contained-int-getter-fn [tuple]
            (let [eid (if (u/array? tuple)
                        (aget ^objects tuple idx)
                        (nth tuple idx))]
              (cond
                (number? eid)     eid ;; quick path to avoid fn call
                (sequential? eid) (db/entid *implicit-source* eid)
                (u/array? eid)    (db/entid *implicit-source* eid)
                :else             eid))))
        ;; If the index is not an int?, the target can never be an array
        (fn contained-getter-fn [tuple]
          (let [eid (.valAt ^ILookup tuple idx)]
            (cond
              (number? eid)     eid ;; quick path to avoid fn call
              (sequential? eid) (db/entid *implicit-source* eid)
              (u/array? eid)    (db/entid *implicit-source* eid)
              :else             eid))))
      (if (int? idx)
        (let [idx (int idx)]
          (fn int-getter [tuple]
            (if (u/array? tuple)
              (aget ^objects tuple idx)
              (nth tuple idx))))
        ;; If the index is not an int?, the target can never be an array
        (fn getter [tuple] (.valAt ^ILookup tuple idx))))))

(defn tuple-key-fn
  [attrs common-attrs]
  (let [n (count common-attrs)]
    (if (== n 1)
      (getter-fn attrs (first common-attrs))
      (let [^objects getters-arr (into-array Object common-attrs)]
        (loop [i 0]
          (if (< i n)
            (do
              (aset getters-arr i (getter-fn attrs (aget getters-arr i)))
              (recur (unchecked-inc i)))
            (fn [tuple]
              (let [^objects arr (make-array Object n)]
                (loop [i 0]
                  (if (< i n)
                    (do
                      (aset arr i ((aget getters-arr i) tuple))
                      (recur (unchecked-inc i)))
                    (LazilyPersistentVector/createOwning arr)))))))))))

(defn -group-by
  [f init coll]
  (let [^UnifiedMap ret (UnifiedMap.)]
    (doseq [x    coll
            :let [k (f x)]]
      (.put ret k (conj (.getIfAbsentPut ret k init) x)))
    ret))

(defn hash-attrs [key-fn tuples] (-group-by key-fn '() tuples))

(defn hash-join
  [rel1 rel2]
  (let [tuples1      (:tuples rel1)
        tuples2      (:tuples rel2)
        attrs1       (:attrs rel1)
        attrs2       (:attrs rel2)
        common-attrs (vec (intersect-keys attrs1 attrs2))
        keep-attrs1  (keys attrs1)
        keep-attrs2  (->> attrs2
                          (reduce-kv (fn keeper [vec k _]
                                       (if (attrs1 k)
                                         vec
                                         (conj! vec k)))
                                     (transient []))
                          persistent!) ; keys in attrs2-attrs1
        keep-idxs1   (to-array (vals attrs1))
        keep-idxs2   (to-array (->Eduction (map attrs2) keep-attrs2))
        key-fn1      (tuple-key-fn attrs1 common-attrs)
        key-fn2      (tuple-key-fn attrs2 common-attrs)]
    (if (< (count tuples1) (count tuples2))
      (let [^UnifiedMap hash (hash-attrs key-fn1 tuples1)]
        (r/relation! (zipmap (u/concatv keep-attrs1 keep-attrs2) (range))
                     (reduce
                       (fn outer [acc tuple2]
                         (let [key (key-fn2 tuple2)]
                           (if-some [tuples1 (.get hash key)]
                             (reduce
                               (fn inner [^List acc tuple1]
                                 (.add acc
                                       (r/join-tuples
                                         tuple1 keep-idxs1 tuple2 keep-idxs2))
                                 acc)
                               acc tuples1)
                             acc)))
                       (FastList.)
                       tuples2)))
      (let [^UnifiedMap hash (hash-attrs key-fn2 tuples2)]
        (r/relation! (zipmap (u/concatv keep-attrs1 keep-attrs2) (range))
                     (reduce
                       (fn outer [acc tuple1]
                         (let [key (key-fn1 tuple1)]
                           (if-some [tuples2 (.get hash key)]
                             (reduce
                               (fn inner [^List acc tuple2]
                                 (.add acc
                                       (r/join-tuples
                                         tuple1 keep-idxs1 tuple2 keep-idxs2))
                                 acc)
                               acc tuples2)
                             acc)))
                       (FastList.)
                       tuples1))))))

(defn subtract-rel
  [a b]
  (let [{attrs-a :attrs, tuples-a :tuples} a
        {attrs-b :attrs, tuples-b :tuples} b

        attrs            (vec (intersect-keys attrs-a attrs-b))
        key-fn-b         (tuple-key-fn attrs-b attrs)
        ^UnifiedMap hash (hash-attrs key-fn-b tuples-b)
        key-fn-a         (tuple-key-fn attrs-a attrs)]
    (assoc a :tuples (filterv #(nil? (.get hash (key-fn-a %))) tuples-a))))

(defn lookup-pattern-db
  [db pattern]
  ;; TODO optimize with bound attrs min/max values here
  (let [search-pattern (mapv #(if (or (= % '_) (free-var? %)) nil %)
                             pattern)
        datoms         (db/-search db search-pattern)
        attr->prop     (->> (map vector pattern ["e" "a" "v"])
                            (filter (fn [[s _]] (free-var? s)))
                            (into {}))]
    (r/relation! attr->prop datoms)))

(defn matches-pattern?
  [pattern tuple]
  (loop [tuple   tuple
         pattern pattern]
    (if (and tuple pattern)
      (let [t (first tuple)
            p (first pattern)]
        (if (or (= p '_) (free-var? p) (= t p))
          (recur (next tuple) (next pattern))
          false))
      true)))

(defn lookup-pattern-coll
  [coll pattern]
  (let [data      (filter #(matches-pattern? pattern %) coll)
        attr->idx (->> (map vector pattern (range))
                       (filter (fn [[s _]] (free-var? s)))
                       (into {}))]
    (r/relation! attr->idx (u/map-fl to-array data))))

(defn normalize-pattern-clause
  [clause]
  (if (source? (first clause))
    clause
    (into ['$] clause)))

(defn lookup-pattern
  [source pattern]
  (if (db/-searchable? source)
    (lookup-pattern-db source pattern)
    (lookup-pattern-coll source pattern)))

#_(defn- pattern-size
    [source pattern]
    (if (db/-searchable? source)
      (let [search-pattern (mapv #(if (symbol? %) nil %) pattern)]
        (db/-count source search-pattern))
      (count (filter #(matches-pattern? pattern %) source))))

(defn collapse-rels
  [rels new-rel]
  (loop [rels    rels
         new-rel new-rel
         acc     []]
    (if-some [rel (first rels)]
      (if (not-empty (intersect-keys (:attrs new-rel) (:attrs rel)))
        (recur (next rels) (hash-join rel new-rel) acc)
        (recur (next rels) new-rel (conj acc rel)))
      (conj acc new-rel))))

(defn- rel-with-attr
  [context sym]
  (some #(when (contains? (:attrs %) sym) %) (:rels context)))

(defn- context-resolve-val
  [context sym]
  (when-some [rel (rel-with-attr context sym)]
    (when-some [tuple (first (:tuples rel))]
      (let [tg (if (u/array? tuple) r/typed-aget get)]
        (tg tuple ((:attrs rel) sym))))))

(defn- rel-contains-attrs? [rel attrs] (some #(contains? (:attrs rel) %) attrs))

(defn- rel-prod-by-attrs
  [context attrs]
  (let [rels       (filter #(rel-contains-attrs? % attrs) (:rels context))
        production (reduce r/prod-rel rels)]
    [(update context :rels #(remove (set rels) %)) production]))

(defn- dot-form [f] (when (and (symbol? f) (str/starts-with? (name f) ".")) f))

(defn- dot-call
  [f args]
  (let [obj   (first args)
        oc    (.getClass ^Object obj)
        fname (subs (name f) 1)
        as    (rest args)
        res   (if (zero? (count as))
                (. (.getDeclaredMethod oc fname nil) (invoke obj nil))
                (. (.getDeclaredMethod
                     oc fname
                     (into-array Class (map #(.getClass ^Object %) as)))
                   (invoke obj (into-array Object as))))]
    (when (not= res false) res)))

(defn- make-call
  [f args]
  (if (dot-form f)
    (dot-call f args)
    (apply f args)))

(defn -call-fn
  [context rel f args]
  (let [sources              (:sources context)
        attrs                (:attrs rel)
        len                  (count args)
        ^objects static-args (make-array Object len)
        ^objects tuples-args (make-array Object len)]
    (dotimes [i len]
      (let [arg (nth args i)]
        (if (symbol? arg)
          (if-some [source (get sources arg)]
            (aset static-args i source)
            (aset tuples-args i (get attrs arg)))
          (aset static-args i arg))))
    (if false
      (fn [tuple]
        ;; TODO raise if not all args are bound
        (let [args (aclone static-args)]
          (dotimes [i len]
            (when-some [tuple-idx (aget tuples-args i)]
              (let [tg (if (u/array? tuple) r/typed-aget get)
                    v  (tg tuple tuple-idx)]
                (aset args i v))))
          (make-call f args)))
      (fn [tuple]
        ;; TODO raise if not all args are bound
        (dotimes [i len]
          (when-some [tuple-idx (aget tuples-args i)]
            (let [tg (if (u/array? tuple) r/typed-aget get)
                  v  (tg tuple tuple-idx)]
              (aset static-args i v))))
        (make-call f static-args)))))

(defn- resolve-sym
  [sym]
  (when-let [v (or (resolve sym)
                   (when (find-ns 'pod.huahaiy.datalevin)
                     (ns-resolve 'pod.huahaiy.datalevin sym)))]
    @v))

(defn filter-by-pred
  [context clause]
  (let [[[f & args]]         clause
        pred                 (or (get built-ins/query-fns f)
                                 (context-resolve-val context f)
                                 (dot-form f)
                                 (resolve-sym f)
                                 (when (nil? (rel-with-attr context f))
                                   (raise "Unknown predicate '" f " in " clause
                                          {:error :query/where, :form clause,
                                           :var   f})))
        [context production] (rel-prod-by-attrs context (filter symbol? args))

        new-rel (if pred
                  (let [tuple-pred (-call-fn context production pred args)]
                    (update production :tuples #(filter tuple-pred %)))
                  (assoc production :tuples []))]
    (update context :rels conj new-rel)))

(defonce pod-fns (atom {}))

(defn bind-by-fn
  [context clause]
  (let [[[f & args] out] clause
        binding          (dp/parse-binding out)
        fun              (or (get built-ins/query-fns f)
                             (context-resolve-val context f)
                             (resolve-sym f)
                             (dot-form f)
                             (when (nil? (rel-with-attr context f))
                               (raise "Unknown function '" f " in " clause
                                      {:error :query/where, :form clause,
                                       :var   f})))
        fun              (if-let [s (:pod.huahaiy.datalevin/inter-fn fun)]
                           (@pod-fns s)
                           fun)

        [context production] (rel-prod-by-attrs context (filter symbol? args))
        new-rel
        (if fun
          (let [tuple-fn (-call-fn context production fun args)
                rels     (for [tuple (:tuples production)
                               :let  [val (tuple-fn tuple)]
                               :when (not (nil? val))]
                           (r/prod-rel
                             (r/relation! (:attrs production)
                                          (doto (FastList.) (.add tuple)))
                             (in->rel binding val)))]
            (if (empty? rels)
              (r/prod-rel production (empty-rel binding))
              (reduce r/sum-rel rels)))
          (r/prod-rel (assoc production :tuples []) (empty-rel binding)))]
    (update context :rels collapse-rels new-rel)))

;;; RULES

(def rule-head #{'_ 'or 'or-join 'and 'not 'not-join})

(defn rule?
  [context clause]
  (cond+
    (not (sequential? clause))
    false

    :let [head (if (source? (first clause))
                 (second clause)
                 (first clause))]

    (not (symbol? head))
    false

    (free-var? head)
    false

    (contains? rule-head head)
    false

    (not (contains? (:rules context) head))
    (raise "Unknown rule '" head " in " clause
           {:error :query/where :form clause})

    :else true))

(def rule-seqid (atom 0))

(defn expand-rule
  [clause context]
  (let [[rule & call-args] clause
        seqid              (swap! rule-seqid inc)
        branches           (get (:rules context) rule)]
    (for [branch branches
          :let   [[[_ & rule-args] & clauses] branch
                  replacements (zipmap rule-args call-args)]]
      (walk/postwalk
        #(if (free-var? %)
           (u/some-of
             (replacements %)
             (symbol (str (name %) "__auto__" seqid)))
           %)
        clauses))))

(defn remove-pairs
  [xs ys]
  (let [pairs (->> (map vector xs ys)
                   (remove (fn [[x y]] (= x y))))]
    [(map first pairs)
     (map second pairs)]))

(defn rule-gen-guards
  [rule-clause used-args]
  (let [[rule & call-args] rule-clause
        prev-call-args     (get used-args rule)]
    (for [prev-args prev-call-args
          :let      [[call-args prev-args] (remove-pairs call-args prev-args)]]
      [(u/concatv ['-differ?] call-args prev-args)])))

(defn collect-vars [clause] (set (u/walk-collect clause free-var?)))

(defn split-guards
  [clauses guards]
  (let [bound-vars (collect-vars clauses)
        pred       (fn [[[_ & vars]]] (every? bound-vars vars))]
    [(filter pred guards)
     (remove pred guards)]))

(defn solve-rule
  [context clause]
  (let [final-attrs     (filter free-var? clause)
        final-attrs-map (zipmap final-attrs (range))
        ;;         clause-cache    (atom {}) ;; TODO
        solve           (fn [prefix-context clauses]
                          (reduce -resolve-clause prefix-context clauses))
        empty-rels?     (fn [context]
                          (some #(empty? (:tuples %)) (:rels context)))]
    (loop [stack (list {:prefix-clauses []
                        :prefix-context context
                        :clauses        [clause]
                        :used-args      {}
                        :pending-guards {}})
           rel   (r/relation! final-attrs-map (FastList.))]
      (if-some [frame (first stack)]
        (let [[clauses [rule-clause & next-clauses]]
              (split-with #(not (rule? context %)) (:clauses frame))]
          (if (nil? rule-clause)

            ;; no rules -> expand, collect, sum
            (let [context (solve (:prefix-context frame) clauses)
                  tuples  (u/distinct-by vec (-collect context final-attrs))
                  new-rel (r/relation! final-attrs-map tuples)]
              (recur (next stack) (r/sum-rel rel new-rel)))

            ;; has rule -> add guards -> check if dead -> expand rule -> push to stack, recur
            (let [[rule & call-args] rule-clause
                  guards
                  (rule-gen-guards rule-clause (:used-args frame))
                  [active-gs pending-gs]
                  (split-guards (u/concatv (:prefix-clauses frame) clauses)
                                (u/concatv guards (:pending-guards frame)))]
              (if (some #(= % '[(-differ?)]) active-gs) ;; trivial always false case like [(not= [?a ?b] [?a ?b])]

                ;; this branch has no data, just drop it from stack
                (recur (next stack) rel)

                (let [prefix-clauses (u/concatv clauses active-gs)
                      prefix-context (solve (:prefix-context frame)
                                            prefix-clauses)]
                  (if (empty-rels? prefix-context)

                    ;; this branch has no data, just drop it from stack
                    (recur (next stack) rel)

                    ;; need to expand rule to branches
                    (let [used-args (assoc (:used-args frame) rule
                                           (conj (get (:used-args frame)
                                                      rule [])
                                                 call-args))
                          branches  (expand-rule rule-clause context)]
                      (recur (u/concatv
                               (for [branch branches]
                                 {:prefix-clauses prefix-clauses
                                  :prefix-context prefix-context
                                  :clauses        (u/concatv branch next-clauses)
                                  :used-args      used-args
                                  :pending-guards pending-gs})
                               (next stack))
                             rel))))))))
        rel))))

(defn resolve-pattern-lookup-refs
  [source pattern]
  (if (db/-searchable? source)
    (let [[e a v] pattern]
      (->
        [(if (or (lookup-ref? e) (keyword? e)) (db/entid-strict source e) e)
         a
         (if (and v (keyword? a) (db/ref? source a) (or (lookup-ref? v) (keyword? v)))
           (db/entid-strict source v) v)]
        (subvec 0 (count pattern))))
    pattern))

(defn dynamic-lookup-attrs
  [source pattern]
  (let [[e a v] pattern]
    (cond-> #{}
      (free-var? e)         (conj e)
      (and
        (free-var? v)
        (not (free-var? a))
        (db/ref? source a)) (conj v))))

#_(defn- clause-size
    [clause]
    (let [source  *implicit-source*
          pattern (resolve-pattern-lookup-refs source clause)]
      (pattern-size source pattern)))

(defn limit-rel
  [rel vars]
  (when-some [attrs' (not-empty (select-keys (:attrs rel) vars))]
    (assoc rel :attrs attrs')))

(defn limit-context
  [context vars]
  (assoc context
         :rels (->> (:rels context)
                    (keep #(limit-rel % vars)))))

(defn bound-vars
  [context]
  (into (sp/new-spillable-set) (mapcat #(keys (:attrs %)) (:rels context))))

(defn check-bound
  [bound vars form]
  (when-not (set/subset? vars bound)
    (let [missing (set/difference (set vars) bound)]
      (raise "Insufficient bindings: " missing " not bound in " form
             {:error :query/where :form form :vars missing}))))

(defn check-free-same
  [bound branches form]
  (let [free (mapv #(set/difference (collect-vars %) bound) branches)]
    (when-not (apply = free)
      (raise "All clauses in 'or' must use same set of free vars, had " free
             " in " form
             {:error :query/where :form form :vars free}))))

(defn check-free-subset
  [bound vars branches]
  (let [free (set (remove bound vars))]
    (doseq [branch branches]
      (when-some [missing (not-empty
                            (set/difference free (collect-vars branch)))]
        (prn branch bound vars free)
        (raise "All clauses in 'or' must use same set of free vars, had "
               missing " not bound in " branch
               {:error :query/where :form branch :vars missing})))))

(defn -resolve-clause
  ([context clause]
   (-resolve-clause context clause clause))
  ([context clause orig-clause]
   (condp looks-like? clause
     [[symbol? '*]] ;; predicate [(pred ?a ?b ?c)]
     (do
       (check-bound (bound-vars context) (filter free-var? (nfirst clause))
                    clause)
       (filter-by-pred context clause))

     [[symbol? '*] '_] ;; function [(fn ?a ?b) ?res]
     (do
       (check-bound (bound-vars context) (filter free-var? (nfirst clause))
                    clause)
       (bind-by-fn context clause))

     [source? '*] ;; source + anything
     (let [[source-sym & rest] clause]
       (binding [*implicit-source* (get (:sources context) source-sym)]
         (-resolve-clause context rest clause)))

     '[or *] ;; (or ...)
     (let [[_ & branches] clause
           _              (check-free-same (bound-vars context) branches clause)
           contexts       (map #(resolve-clause context %) branches)
           rels           (map #(reduce hash-join (:rels %)) contexts)]
       (assoc (first contexts) :rels [(reduce r/sum-rel rels)]))

     '[or-join [[*] *] *] ;; (or-join [[req-vars] vars] ...)
     (let [[_ [req-vars & vars] & branches] clause
           bound                            (bound-vars context)]
       (check-bound bound req-vars orig-clause)
       (check-free-subset bound vars branches)
       (recur context (list* 'or-join (concat req-vars vars) branches) clause))

     '[or-join [*] *] ;; (or-join [vars] ...)
     (let [[_ vars & branches] clause
           vars                (set vars)
           _                   (check-free-subset (bound-vars context) vars
                                                  branches)
           join-context        (limit-context context vars)
           contexts            (map #(-> join-context (resolve-clause %)
                                         (limit-context vars))
                                    branches)
           rels                (map #(reduce hash-join (:rels %)) contexts)
           sum-rel             (reduce r/sum-rel rels)]
       (update context :rels collapse-rels sum-rel))

     '[and *] ;; (and ...)
     (let [[_ & clauses] clause]
       (reduce resolve-clause context clauses))

     '[not *] ;; (not ...)
     (let [[_ & clauses]    clause
           bound            (bound-vars context)
           negation-vars    (collect-vars clauses)
           _                (when (empty? (set/intersection bound negation-vars))
                              (raise "Insufficient bindings: none of "
                                     negation-vars " is bound in " orig-clause
                                     {:error :query/where :form orig-clause}))
           context'         (assoc context :rels
                                   [(reduce hash-join (:rels context))])
           negation-context (reduce resolve-clause context' clauses)
           negation         (subtract-rel
                              (single (:rels context'))
                              (reduce hash-join (:rels negation-context)))]
       (assoc context' :rels [negation]))

     '[not-join [*] *] ;; (not-join [vars] ...)
     (let [[_ vars & clauses] clause
           bound              (bound-vars context)
           _                  (check-bound bound vars orig-clause)
           context'           (assoc context :rels
                                     [(reduce hash-join (:rels context))])
           join-context       (limit-context context' vars)
           negation-context   (-> (reduce resolve-clause join-context clauses)
                                  (limit-context vars))
           negation           (subtract-rel
                                (single (:rels context'))
                                (reduce hash-join (:rels negation-context)))]
       (assoc context' :rels [negation]))

     '[*] ;; pattern
     (let [source   *implicit-source*
           pattern  (resolve-pattern-lookup-refs source clause)
           relation (lookup-pattern source pattern)]
       (binding [*lookup-attrs* (if (db/-searchable? source)
                                  (dynamic-lookup-attrs source pattern)
                                  *lookup-attrs*)]
         (update context :rels collapse-rels relation))))))

(defn resolve-clause
  [context clause]
  (if (rule? context clause)
    (if (source? (first clause))
      (binding [*implicit-source* (get (:sources context) (first clause))]
        (resolve-clause context (next clause)))
      (update context :rels collapse-rels (solve-rule context clause)))
    (-resolve-clause context clause)))

(defn- or-join-var?
  [clause s]
  (and (list? clause)
       (= 'or-join (first clause))
       (some #(= % s) (tree-seq sequential? seq (second clause)))))

(defn- plugin-inputs*
  [parsed-q inputs]
  (let [qins    (:qin parsed-q)
        finds   (tree-seq sequential? seq (:qorig-find parsed-q))
        owheres (:qorig-where parsed-q)
        to-rm   (keep-indexed
                  (fn [i qin]
                    (let [v (:variable qin)
                          s (:symbol v)]
                      (when (and (instance? BindScalar qin)
                                 (instance? Variable v)
                                 (not (fn? (nth inputs i)))
                                 (not (some #(= s %) finds))
                                 (not (some #(or-join-var? % s) owheres)))
                        [i s])))
                  qins)
        rm-idxs (set (map first to-rm))
        smap    (reduce (fn [m [i s]] (assoc m s (nth inputs i))) {} to-rm)]
    [(assoc parsed-q
            :qwhere (reduce (fn [ws [s v]]
                              (walk/postwalk (fn [e]
                                               (if (and (instance? Variable e)
                                                        (= s (:symbol e)))
                                                 (Constant. v)
                                                 e))
                                             ws))
                            (:qwhere parsed-q) smap)
            :qorig-where (walk/postwalk-replace smap owheres)
            :qin (u/remove-idxs rm-idxs qins))
     (u/remove-idxs rm-idxs inputs)]))

(defn- plugin-inputs
  "optimization that plugs simple value inputs into where clauses"
  [parsed-q inputs]
  (let [ins (:qin parsed-q)
        cb  (count ins)
        cv  (count inputs)]
    (cond
      (< cb cv)
      (raise "Extra inputs passed, expected: "
             (mapv #(:source (meta %)) ins) ", got: " cv
             {:error :query/inputs :expected ins :got inputs})
      (> cb cv)
      (raise "Too few inputs passed, expected: "
             (mapv #(:source (meta %)) ins) ", got: " cv
             {:error :query/inputs :expected ins :got inputs})
      :else
      (plugin-inputs* parsed-q inputs))))

(defn- split-clauses
  [context]
  (let [ptn-idxs (set (u/idxs-of #(instance? Pattern %)
                                 (get-in context [:parsed-q :qwhere])))
        clauses  (get-in context [:parsed-q :qorig-where])]
    [(u/keep-idxs ptn-idxs clauses) (u/remove-idxs ptn-idxs clauses)]))

(defn- init-graph
  [[patterns clauses]]
  [patterns clauses])

(defn- pushdown-predicates
  "optimization that push down predicates into value scans"
  [[graph clauses]]
  [graph clauses])

;; TODO group by source
(defn- build-graph
  "Split clauses, turn the group of clauses to be optimized into a query    graph that looks like this:

  {?e  {:links [{:type :ref :tgt ?e0 :attr :friend}
                {:type :val-eq :tgt ?e1 :attrs {?e :age ?e1 :age}}
                {:type  :val-pred :tgt ?e2 :pred (< ?a ?y)
                 :attrs {?e :age ?e2 :year}}]
        :bound {:name {:val \"Tom\" :count 5}}
        :free  {:age    {:var ?a :count 10890}
                :salary {:var ?s :pred (< ?s 200000)}
                :friend {:var ?e0 :count 2500}}}
   ?e0 {:links [{:type :_ref :tgt ?e :attr :friend}]
        :free  {:age {:var ?a :count 10890}}}
   ...}

  Remaining clauses will be joined after the graph produces a relation"
  [context]
  (let [clauses (get-in context [:parsed-q :qorig-where])]
    (if (< 1 (count clauses))
      (let [[graph clauses] (-> context
                                (split-clauses)
                                (init-graph)
                                (pushdown-predicates))]
        (assoc context :clauses clauses :graph graph))
      (assoc context :clauses clauses))))

(defn- optimize-plan
  [context]
  (if-let [graph (:graph context)]
    (assoc context :plan graph)
    context))

(defn- execute-plan
  [context]
  (reduce resolve-clause context (:plan context)))

(defn -q
  [context]
  (binding [*implicit-source* (get (:sources context) '$)]
    (as-> context c
      (execute-plan c)
      (reduce resolve-clause c (:clauses c)))))

(defn -collect-tuples
  [acc rel ^long len copy-map]
  (->Eduction
    (comp
      (map (fn [^{:tag "[[Ljava.lang.Object;"} t1]
             (->Eduction
               (map (fn [t2]
                      (let [res (aclone t1)]
                        (if (u/array? t2)
                          (dotimes [i len]
                            (when-some [idx (aget ^objects copy-map i)]
                              (aset res i (aget ^objects t2 idx))))
                          (dotimes [i len]
                            (when-some [idx (aget ^objects copy-map i)]
                              (aset res i (get t2 idx)))))
                        res)))
               (:tuples rel))))
      cat)
    acc))

(defn -collect
  ([context symbols]
   (let [rels (:rels context)]
     (-collect [(make-array Object (count symbols))] rels symbols)))
  ([acc rels symbols]
   (cond+
     :let [rel (first rels)]

     (nil? rel) acc

     ;; one empty rel means final set has to be empty
     (empty? (:tuples rel)) []

     :let [keep-attrs (select-keys (:attrs rel) symbols)]

     (empty? keep-attrs) (recur acc (next rels) symbols)

     :let [copy-map (to-array (map #(get keep-attrs %) symbols))
           len      (count symbols)]

     :else
     (recur (-collect-tuples acc rel len copy-map) (next rels) symbols))))

(defn collect
  [context symbols]
  (into (sp/new-spillable-set) (map vec) (-collect context symbols)))

(defprotocol IContextResolve
  (-context-resolve [var context]))

(extend-protocol IContextResolve
  Variable
  (-context-resolve [var context]
    (context-resolve-val context (.-symbol var)))
  SrcVar
  (-context-resolve [var context]
    (get-in context [:sources (.-symbol var)]))
  PlainSymbol
  (-context-resolve [var _]
    (or (get built-ins/aggregates (.-symbol var))
        (resolve-sym (.-symbol var))))
  Constant
  (-context-resolve [var _]
    (.-value var)))

(defn -aggregate
  [find-elements context tuples]
  (mapv (fn [element fixed-value i]
          (if (dp/aggregate? element)
            (let [f    (-context-resolve (:fn element) context)
                  args (map #(-context-resolve % context)
                            (butlast (:args element)))
                  vals (map #(nth % i) tuples)]
              (apply f (u/concatv args [vals])))
            fixed-value))
        find-elements
        (first tuples)
        (range)))

(defn aggregate
  [find-elements context resultset]
  (let [group-idxs (u/idxs-of (complement dp/aggregate?) find-elements)
        group-fn   (fn [tuple] (map #(nth tuple %) group-idxs))
        grouped    (group-by group-fn resultset)]
    (for [[_ tuples] grouped]
      (-aggregate find-elements context tuples))))

(defn map* [f xs]
  (persistent! (reduce #(conj! %1 (f %2)) (transient (empty xs)) xs)))

(defn tuples->return-map
  [return-map tuples]
  (let [symbols (:symbols return-map)
        idxs    (range 0 (count symbols))]
    (map* (fn [tuple]
            (persistent!
              (reduce
                (fn [m i] (assoc! m (nth symbols i) (nth tuple i)))
                (transient {}) idxs)))
          tuples)))

(defprotocol IPostProcess
  (-post-process [find return-map tuples]))

(extend-protocol IPostProcess
  FindRel
  (-post-process [_ return-map tuples]
    (if (nil? return-map)
      tuples
      (tuples->return-map return-map tuples)))

  FindColl
  (-post-process [_ _ tuples]
    (into [] (map first) tuples))

  FindScalar
  (-post-process [_ _ tuples]
    (ffirst tuples))

  FindTuple
  (-post-process [_ return-map tuples]
    (if (some? return-map)
      (first (tuples->return-map return-map [(first tuples)]))
      (first tuples))))

(defn- pull
  [find-elements context resultset]
  (let [resolved (for [find find-elements]
                   (when (dp/pull? find)
                     (let [db      (-context-resolve (:source find) context)
                           pattern (-context-resolve (:pattern find) context)]
                       (dpa/parse-opts db pattern))))]
    (for [tuple resultset]
      (mapv
        (fn [parsed-opts el]
          (if parsed-opts
            (dpa/pull-impl parsed-opts el)
            el))
        resolved
        tuple))))

(defn q
  [q & inputs]
  (let [parsed-q (lru/-get *query-cache* q #(dp/parse-query q))]
    ;; (println "-----")
    ;; (println parsed-q)
    (binding [timeout/*deadline* (timeout/to-deadline (:qtimeout parsed-q))]
      (let [find              (:qfind parsed-q)
            find-elements     (dp/find-elements find)
            find-vars         (dp/find-vars find)
            result-arity      (count find-elements)
            with              (:qwith parsed-q)
            all-vars          (u/concatv find-vars (map :symbol with))
            [parsed-q inputs] (plugin-inputs parsed-q inputs)
            context           (-> (Context. parsed-q [] {} {} [] {} [])
                                  (resolve-ins inputs)
                                  (build-graph)
                                  (optimize-plan))
            resultset         (-> (-q context)
                                  (collect all-vars))]
        (cond->> resultset
          with
          (mapv #(vec (subvec % 0 result-arity)))

          (some dp/aggregate? find-elements)
          (aggregate find-elements context)

          (some dp/pull? find-elements)
          (pull find-elements context)

          true
          (-post-process find (:qreturn-map parsed-q)))))))
