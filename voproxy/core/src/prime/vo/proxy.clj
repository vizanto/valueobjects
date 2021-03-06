;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo.proxy
  "This namespace defines the VOProxy, VOSearchProxy and VOHistoryProxy
  protocols. The namespace also includes helper functions for easy
  creation of implementations of these protocols."
  (:refer-clojure :exclude [get update])
  (:require [fast-zip.core :as zip]
            [taoensso.timbre :as log]
            [clojure.core :as clj]
            [prime.vo :refer (id-field vo-zipper)]
            [prime.vo.definition :refer (companion-object-symbol)]
            [prime.utils :refer (guard-let index-of forcat)]))


;;; Protocol definitions.

(defprotocol VOProxy
  "WARNING: Proxy cannot guarantee consistency!"

  ;; Standard storage functions.
  (get-vo [vo] [proxy vo]
    "Get will get you a VO based on ID")

  (put-vo [vo] [proxy vo] [proxy vo options]
    "Putting will instert the new VO, or it will replace an existing VO
    completely if it already exists. The VO needs to have an ID.")

  (update [vo] [proxy vo] [proxy vo id] [proxy vo id options]
    "Updating will 'deep-merge' the new VO data with the existing VO.
    Sequential values are replaced, not joined in any way. One can specify
    the ID to look up the existing VO, otherwise it requires an ID in
    the new VO data. If the VO does not exist yet, it is upserted.")

  (delete [vo] [proxy vo] [proxy vo options]
    "Deletes a VO. Use with care.")

  ;; Delta changes functions.
  (append-to [vo path path-vars value] [this vo path path-vars value]
    [this vo path path-vars value options]
    "Given a VO and a path that points to an array, this appends the
    given value to that array.")

  (insert-at [vo path path-vars value] [proxy vo path path-vars value]
    [proxy vo path path-vars value options]
    "Given a VO and a path that points to an array location, this
    inserts the given value in that array. Out of bounds indexes are
    automatically trimmed. Negative indexes count from the end of the
    array, e.g. an index of -1 equals the last index of the array.
    Inserting into an empty array is allowed, and has the same effect
    as an `append-to` on an empty array, i.e. the index is ignored.")

  (move-to [vo path path-vars to] [proxy vo path path-vars to] [proxy vo path path-vars to options]
    "Given a VO, a path that points to an array location, and a new 'to'
    index, it moves the item to the new index. Out of bounds indexes
    are automatically trimmed. Negative indexes count from the end of
    the array, e.g. an index of -1 equals the last index of the array.")

  (replace-at [vo path path-vars value] [proxy vo path path-vars value]
    [proxy vo path path-vars value options]
    "Given a VO and a path to a value, which may point to an ordinary
    value or to an index in an array, this replaces that value with
    the new value. If the value is a vector and the path points to an
    index in an array, the appointed array member is replaced with all
    the values in the given vector. E.g. (replace-at [0 1 2] [1] nil
    [9 9 9]) => [0 9 9 9 2]")

  (merge-at [vo path path-vars value] [proxy vo path path-vars value]
    [proxy vo path path-vars value options]
    "Given a VO and path in a VO, pointing to a VO (possibly inside an
    array), this merges the given map into that VO, replacing any
    existing values. A standard supported option is
    :allow-nil-or-empty-path? This defaults to false, which means a
    path cannot be nil or empty.")

  (remove-at [vo path path-vars] [proxy vo path path-vars] [proxy vo path path-vars options]
    "Given a VO and a path in a VO, removes that what the path points to."))


(defprotocol VOSearchProxy
  "Search for VOs."
  (search [vo] [proxy vo] [proxy vo options]
    "Search for VOs."))


(defprotocol VOHistoryProxy
  "Get the history of a particular VO."
  (get-slice [proxy vo] [proxy vo options]
    "Get latest slice of history of a VO. Can be limited to a certain amount of :slices."))


;; Backwards compatibility aliases

(def ^{:doc "Alias of VOProxy/get-vo"}    get         get-vo)
(def ^{:doc "Alias of VOProxy/put-vo"}    put         put-vo)
(def ^{:doc "Alias of VOProxy/remove-at"} remove-from remove-at)


;;; A filter for value objects.

(declare ^:private vo-keep-vo)

(defn- vo-keep-array
  "Iterates over an array in a value object field (using the zipper
  pointing to the field)."
  [vz keep-map]
  (if-let [in-array (zip/down vz)]
    (if (zip/branch? in-array)
      (loop [former nil
             next in-array]
        (if next
          (let [new (vo-keep-vo next keep-map)]
            (recur new (zip/right new)))
          (zip/up former)))
      vz)
    vz))


(defn- vo-keep-vo
  "Filters out a value object zipper, based on a map holding the keys
  to keep."
  [vz keep-map]
  (if-let [in-fields (zip/down vz)]
    (loop [former nil
           next in-fields]
      (if next
        (let [field-key (first (zip/node next))
              keep-item (clj/get keep-map field-key)
              new (if-not keep-item
                    (zip/edit next (constantly nil))
                    (if-not (map? keep-item)
                      (if-not (and (fn? keep-item) (not= keep-item *)) next
                       #_else (zip/edit next keep-item))
                      ;; Check whether the value of the current key-value pair is a
                      ;; vector or a value object. If so, recurse!
                      (guard-let [value-vz (-> next zip/down zip/right) :when zip/branch?]
                        (zip/up (if (vector? (zip/node value-vz))
                                  (vo-keep-array value-vz (clj/get keep-map field-key))
                                  (vo-keep-vo value-vz (clj/get keep-map field-key))))
                        next)))]
          (recur new (zip/right new)))
        (zip/up former)))
    vz))


(defn vo-keep
  "Filter out (possibly nested) fields from a value object. The
  keep-map should contain truthful values for the keys one whishes to
  keep. Keep-maps are recursive, in that one can specify a keep-map as
  a value for a key. If the value for a key is not a map, all nested
  fields are kept. For example:

  (vo-keep (Publication {:name \"greta\"
                         :tags [\"foo\" \"bar\"]
                         :booklet (Booklet {:isZoomable false
                                            :spreads [(Spread {:width 1
                                                               :height 100})
                                                      (Spread {:width 2})]})})
               {:tags true
                :booklet {:spreads {:width true}}})
  => (Publication {:booklet (Booklet {:spreads [(Spread{:width 1})
                                                (Spread{:width 2})]}),
                   :tags [\"foo\" \"bar\"]})"
  [vo keep-map]
  ;; If the keep-map is not a map, but is a truthy value, don't start zipping.
  (if (and keep-map (not (map? keep-map)))
    ;; It the keep-map is a function, apply it to the entire VO.
    (if (and (fn? keep-map) (not= keep-map *)) (keep-map vo) vo)
    ;; The keep-map is actually a map, so start zipping (which is used for efficiency).
    (let [vz (vo-zipper vo)]
      ;; One could also call zip/root, but the zipper should already be at the root if everything
      ;; went correct.
      (zip/node (vo-keep-vo vz keep-map)))))

(defn inner-keep-map
  "Returns the inner keep map or last truthy value (like *) for the given `vo path`
  into a `vo-keep map`.

   This fn assumes the path and keep map use keywords to specify the VO fields."
  [vo-keep-map path]
  (reduce (fn [keep-map item] (if (and (map? keep-map) (keyword? item)) (item keep-map) #_else keep-map))
          vo-keep-map path))

(defn path-keep
  "Returns true if a path operation should be applied
   according to the given `vo path` and `vo-keep map`.

   This fn assumes the path and keep map use keywords to specify the VO fields."
  [vo-keep-map path]
  (not (nil? (inner-keep-map vo-keep-map path))))


(defprotocol VOTransformer
  "In mathematics, a transformation could be any function mapping a set X to another set or itself.
   The term “transformation” refers to a function from X to itself that preserves this structure.

   Usage:
   (transform map-or-function (ValueObject {}))"
  (transform [transformer vo]))

(extend-type prime.vo.ValueObject
  VOTransformer
    (transform [target-vo source-vo]
      (conj target-vo source-vo)))

(extend-type java.util.Map
  VOTransformer
    (transform [vo keep-map]
      (vo-keep keep-map vo)))


;;; Delegator proxies.

(defn- mk-proxy-function-call
  "Returns a call to VOProxy function `name` using the supplied proxy
  and argumentlist."
  [name arglist proxy]
  `(~(symbol "prime.vo.proxy" (str name)) ~proxy ~@(rest arglist)))

(defn- mk-checked-proxy-function-call
  "Returns a nil-checked call to VOProxy function `name` using the supplied proxy
  and argumentlist."
  [name arglist proxy]
  `(if ~proxy
    ~(mk-proxy-function-call name arglist proxy)
   ;else, proxy is nil
    (log/warn ~(str proxy) (str "is nil [!]  Ignoring: (" ~(str name) " " ~(str proxy)) ~@(rest arglist) ")")))

(defn- mk-proxy-call
  "Returns a call to VOProxy function `name` wrapped in `path-keep` check if keep-map is provided."
  [name arglist keep-map proxy]

  (let [proxy-call (mk-checked-proxy-function-call name arglist proxy)]
    (cond
      (and keep-map (some #(= 'path %) arglist))
      `(when (path-keep ~keep-map ~'path) ~proxy-call)

      :else
      proxy-call)))


(defn- vo-proxy-delegator-proxy-forms
  "Returns a sequence alternating binding symbols and proxy function
  calls. The function calls are to the `name` function of the VOProxy,
  using the given `arglist`. When `name` is \"get\" or \"get-vo\", the
  supplied `proxies` are tried in order, and the result of the first
  to succeed is bound to the `proxy-result-sym`. Otherwise, all
  `proxies` are called, `proxy-result-sym` is bound to the result of
  the call to the `result-proxy`, and `meta-result-sym` is bound to
  the result of the call to the `meta-proxy`."
  [name arglist proxies result-proxy proxy-result-sym meta-proxy meta-result-sym keep-map]
  (let [proxy-calls (into {} (map (juxt identity (partial mk-proxy-call name arglist keep-map)) proxies))]
    (if (#{'get 'get-vo} name)
      [proxy-result-sym `(or ~@(vals proxy-calls))]
      (forcat [proxy proxies]
        [(condp = proxy
           result-proxy proxy-result-sym
           meta-proxy meta-result-sym
           '_)
         (proxy-calls proxy)]))))

(defn- prepare-vo-keep-map [keep-map, type]
  ; Make sure the ID field is kept. (assoc it)
  (assoc (eval (clojure.walk/postwalk-replace {* true '* true} keep-map))
         (-> (companion-object-symbol (eval type))
             ^prime.vo.ValueObjectCompanion (eval)
             (.manifest) (id-field) (.keyword))
         true))

(defn- ->name [name]
  (or ({'get 'get-vo, 'put 'put-vo, 'remove-from 'remove-at} name)
      name))

(defn- vo-proxy-delegator-fn
  "Given a VOProxy function `name`, its arguments list, and a sequence
  of pairs of value object types and their delegation options, returns
  a spec for reifying the `name` function in the VOProxy."
  [name arglist vo-opts-pairs]
  (let [conditions (forcat [[type opts] vo-opts-pairs
                            :let [name (->name name)]
                            :when ((:methods opts (constantly true)) name)]
                     (let [pre-form (clj/get opts (keyword (str "pre-" name)))
                           post-form (clj/get opts (keyword (str "post-" name)) 'vo)
                           proxies (:proxies opts)
                           result-proxy (clj/get proxies (if (:return-result-of opts)
                                                       (index-of (:return-result-of opts) proxies)
                                                       0))
                           meta-proxy (:with-meta opts)
                           proxy-result-sym (if (= name 'get-vo) 'vo #_else (gensym "proxy-result-"))
                           meta-result-sym (gensym "meta-result-")
                           keep-map (:keep opts)
                           proxy-forms (vo-proxy-delegator-proxy-forms name arglist proxies
                                                                       result-proxy
                                                                       proxy-result-sym
                                                                       meta-proxy
                                                                       meta-result-sym
                                                                       keep-map)]
                       (assert (not (empty? proxies))
                               (str "need to specify at least one value in :proxies for " type))
                       (assert result-proxy
                               (str ":return-result-of option must specify one of the values in "
                                    ":proxies for " type))
                       (assert (if meta-proxy (index-of meta-proxy proxies) true)
                               (str ":with-meta option must specify one of the values in :proxies "
                                    "for " type))
                       (assert (not= meta-proxy result-proxy)
                               (str ":with-meta cannot be the same as :return-result-of for " type))
                       `[(instance? ~type ~'vo)
                         (let ~(cond
                                (and keep-map (or (= name 'update) (= name 'put-vo)))
                                ['vo `(if ~'vo (vo-keep ~'vo ~keep-map))]

                                (and keep-map (some #(= 'value %) arglist))
                                ['value `(if ~'value (vo-keep ~'value (inner-keep-map ~keep-map (filter keyword? ~'path))))]
                                  
                                :else [])
                           (let ~(vec (concat [(if pre-form 'vo #_else '_) pre-form]
                                              proxy-forms
                                              (if (= name 'get-vo)
                                                ['vo  (if keep-map `(vo-keep ~post-form ~keep-map)
                                                       #_else post-form)]
                                               #_else ['_ post-form])))
                             ~(if (and meta-proxy (not= name 'get-vo))
                                `(with-meta ~proxy-result-sym ~meta-result-sym)
                                `~proxy-result-sym)))]))]
    `(~name ~arglist
            (cond ~@conditions
                  :else (throw (IllegalArgumentException.
                                (str "Attempting to use a proxy delegator that does not support "
                                     ~'vo)))))))


;; ---TODO: Maybe having a keep filter per proxy in :proxies is also an option?

(defmacro vo-proxy-delegator
  "Given a sequence alternating value object types and their delegation
  options maps, returns a reified VOProxy supporting the delegations.

  The following options exist:

  :proxies - a vector of symbols evaluating to existing VOProx
  implementations. This option is required and at least one proxy must
  be specified.

  :return-result-of - one of the symbols in the :proxies option,
  telling the delegator which result should be returned. By default,
  the result of the first proxy is returned.

  :pre-<VOProxy function> - a form that is evaluated before the
  VOProxy function is executed for all the specifend :proxies. The result
  of the pre form is used in the proxy function, which allows one to change
  the input. For example, one could specify:
  `` :pre-update (do (log \"update requested\") (assoc vo :at (now)). ``

  :post-<VOProxy function> - a form that is evaluated after the
  VOProxy function is executed for all specifend :proxies.
  When defining :post-get-vo, it's result is returned instead.

  :with-meta - one of the symbols in the :proxies option, telling the
  delegator to add the result of that proxy as meta data to the actual
  result.

  :keep - a map that is used by the `vo-keep` function. It is used
  on the vo argument of the VOProxy function, before that function is
  called on the actual :proxies, except for get-vo (see below).
  When a :keep map is supplied, the VOTransformer implementation of this proxy
  forwards to vo-keep.

  :methods - a set of <VOProxy function> symbols that should be supported.
  If only `#{ get-vo }` is supplied (a readonly proxy) and a :keep map is
  defined, the returned result is filtered by `vo-keep` after :post-get-vo.

  Note that, when a `get-vo` function is executed, the proxies in
  :proxies are executed in order, and as soon as one returns a result,
  that result is used and the other proxies are not executed. The
  :with-meta option has no effect on `get-vo` calls either."
  [delegations]
  (let [vo-opts-pairs (map (fn [[type {:keys [keep] :as opts} :as pair]]
                            (if keep [type (assoc opts :keep (prepare-vo-keep-map keep type))]
                             #_else  pair))
                           (partition 2 delegations))]
    `(reify
      VOTransformer
      (transform [this ~'vo]
        (cond
          ~@(forcat [[type opts] vo-opts-pairs :when (:keep opts)]
              `[(instance? ~type ~'vo)
                (vo-keep ~'vo ~(:keep opts))])
          :else ~'vo))
      VOProxy
       ~@(for [{:keys [name arglists]} (vals (:sigs VOProxy))
               ;; Ignore the first arglist, like (get-vo [vo])
               ;; Forward all to the last (full options arglist)
               my-args (->> (next arglists) (drop-last 1))]
           (let [full-args
                 (concat my-args
                         (take (- (count (last arglists)) (count my-args))
                               (repeat nil)))]
             ;; Return implementation which forwards the call using nil for the remaining arguments
             `(~name ~my-args
                ~(mk-proxy-function-call name full-args (first my-args)))))
       ;; Implement the full options (last arglist):
       ~@(for [{:keys [name arglists]} (vals (:sigs VOProxy))]
           (vo-proxy-delegator-fn name (last arglists) vo-opts-pairs)))))


(defmacro def-vo-proxy-delegator
  "Convenience macro to create and define a vo-proxy-delegator."
  [name & delegations]
  `(def ~name (vo-proxy-delegator ~delegations)))


;;; Default proxies.

(def vo-default-opts {
  `get-vo
    [ [['vo]]
      'get-vo]
  `put-vo
    [ [['vo] ['vo 'options]]
      'put-vo]
  `update
    [ [['vo] ['vo 'id] ['vo 'id 'options]]
      'update]
  `delete
    [ [['vo] ['vo 'options]]
      'delete]
  `append-to
    [ [['vo 'path 'path-vars 'value] ['vo 'path 'path-vars 'value 'options]]
      'append-to]
  `insert-at
    [ [['vo 'path 'path-vars 'value] ['vo 'path 'path-vars 'value 'options]]
      'insert-at]
  `move-to
    [ [['vo 'path 'path-vars 'to] ['vo 'path 'path-vars 'to 'options]]
      'move-to]
  `replace-at
    [ [['vo 'path 'path-vars 'value] ['vo 'path 'path-vars 'value 'options]]
      'replace-at]
  `merge-at
    [ [['vo 'path 'path-vars 'value] ['vo 'path 'path-vars 'value 'options]]
      'merge-at]
  `remove-at
    [ [['vo 'path 'path-vars] ['vo 'path 'path-vars 'options]]
      'remove-at]
  })


(defmacro default-vo-proxy [vo & options]
  `(do
    (extend-type ~vo
      VOProxy
        ~@(for [[fnc [params fncname]] vo-default-opts]
          `(~fnc
            ~@(for [param params]
              `(~param
              ~(do
                (let [option (first (filter #(= fncname (first %)) options))
                      conditions (rest option)]
                  (if (next conditions)
                      `(cond
                        ~@(forcat [[condition voproxy] (partition 2 conditions)]
                            [condition (cons fnc (cons voproxy param))]) ; `[~(first option) (do ~(concat (list fnc (second option)) param))]
                        )
                    `(do ~(concat (list fnc (second option)) param))))))))))))
