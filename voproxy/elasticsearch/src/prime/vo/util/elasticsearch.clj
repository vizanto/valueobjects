;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo.util.elasticsearch
  (:refer-clojure :exclude (get))
  (:require [prime.vo :as vo]
            [clojure.set     :as set]
            [cheshire [core :as core] [generate :as gen]]
            [clj-elasticsearch.client :as ces]
            [prime.vo.source :refer (def-valuesource)]
            [prime.vo.util.json :as json]) ; For loading the right VO encoders in Cheshire.
  (:import [prime.types EnumValue package$ValueType package$ValueTypes$Tdef
            package$ValueTypes$Tarray package$ValueTypes$Tenum]
           [prime.vo IDField ValueObject ValueObjectManifest ValueObjectField ValueObjectCompanion ID]
           [com.fasterxml.jackson.core JsonGenerator]
           [org.elasticsearch.action.get GetResponse]
           [org.elasticsearch.action.search SearchResponse]
           [org.elasticsearch.search SearchHit SearchHitField]))


;;; Demonize TransportClient, if Immutant is available.

(try
  (require 'immutant.daemons)
  (eval '(extend-type org.elasticsearch.client.Client
    immutant.daemons/Daemon
    (start [this])
    (stop  [this] (.close this))))
  (catch Exception e))

(defn create-client [hosts cluster-name & options]
  (ces/make-client :transport {:hosts hosts :cluster-name cluster-name}))


;;; Generate ElasticSearch mapping from VO.

(defprotocol TermFilter "Used while creating mappings and term-filter queries from a ValueObject."
  (term-kv-pair [value key kv-pair] "Returns a vector of [^String key, value] which gets appended to the term-filter map.")
  (mapping-field-type-defaults [valueType] "Returns a map with default options for ElasticSearch mapping."))

(extend-protocol TermFilter

  Object
  (term-kv-pair [v k kv-pair] kv-pair)

  EnumValue
  (term-kv-pair [v k kv-pair]
    (if (.isInstance scala.Product v)
      [(str k ".x") (. v toString)]
    #_else
      [(str k ".v") (. v value   )]))

  (mapping-field-type-defaults [value]
    {:properties {
      :v {:type "integer"}
      :x {:type "string", :index "not_analyzed"} ;x = extended value, currently only String
    }})

  package$ValueType
  (mapping-field-type-defaults [valueType]
    (case (. valueType keyword)
      :prime.types/boolean    nil
      :prime.types/integer    nil
      :prime.types/decimal    nil
      :prime.types/Date       nil
      :prime.types/Date+time  nil
      :prime.types/Interval   {:properties {:s {:type "date"} :e {:type "date"}}}
      :prime.types/Color      nil
      :prime.types/String     nil
      :prime.types/URI        {:index "not_analyzed"}
      :prime.types/URL        {:index "not_analyzed"}
      :prime.types/E-mail     {:index "not_analyzed"}
      :prime.types/ObjectId   {:index "not_analyzed"}
      :prime.types/FileRef    {:index "not_analyzed"}
      #_default
        (condp instance? valueType
          package$ValueTypes$Tdef   (mapping-field-type-defaults (.. ^package$ValueTypes$Tdef  valueType empty))
          package$ValueTypes$Tenum  (mapping-field-type-defaults (.. ^package$ValueTypes$Tenum valueType t valueSet first))
          package$ValueTypes$Tarray (assert false "array should be mapped as it's contents"))))

  prime.vo.ValueObject
  (mapping-field-type-defaults [vo] nil)
)

(defn mapping-field-type-name
  [^package$ValueType valueType]
  (case (. valueType keyword)
    :prime.types/boolean    "boolean"
    :prime.types/integer    "integer"
    :prime.types/decimal    "double"
    :prime.types/Date       "date"
    :prime.types/Date+time  "date"
    :prime.types/Interval   "object"
    :prime.types/Color      "integer"
    :prime.types/String     "string"
    :prime.types/URI        "string"
    :prime.types/URL        "string"
    :prime.types/E-mail     "string"
    :prime.types/ObjectId   "string"
    :prime.types/FileRef    "string"
    #_default
      (condp instance? valueType
        package$ValueTypes$Tdef   "object"
        package$ValueTypes$Tenum  "object"
        package$ValueTypes$Tarray (assert false "array should be mapped as it's contents"))))

(declare vo-mapping)

(defn field-mapping
  ([option-map ^ValueObjectField field unique]
    (field-mapping (conj (if unique {:index "not_analyzed"} #_else {}) option-map)
                   (. field valueType) (. field id) (. field keyword)))

  ([option-map, ^package$ValueType value-type, id, field-key]
    (if (instance? package$ValueTypes$Tarray value-type)
      (field-mapping option-map (. ^package$ValueTypes$Tarray value-type innerType) id field-key)
    ; Else: not an array
    { (Integer/toHexString id),
    (conj {:store "no"
           ;:index_name (name field-key)
           :type       (mapping-field-type-name value-type)}
      (if (and
            (nil? (mapping-field-type-defaults value-type))
            (instance? package$ValueTypes$Tdef  value-type))
          (let [^package$ValueTypes$Tdef value-type value-type
                ^ValueObject             empty      (.. value-type empty)]
            (conj
              (if (.. value-type ref)
                {:type (mapping-field-type-name (.valueType (._id ^IDField (. empty voManifest)))) :index "not_analyzed"}
              #_else
                (vo-mapping empty (or option-map {})))

              (dissoc option-map :type)
          ))
        #_else
          (conj {} (mapping-field-type-defaults value-type) option-map) ;overwrites defaults
    ))
    })))


(defn vo-mapping "Create an ElasticSearch compatible mapping from an empty ValueObject.
  options are
  - :only    exclusive #{set} of fields to include in mapping
  - :exclude #{set} of fields not to include in mapping
  - any ElasticSearch option.

  Known issues / TODO:
   - ValueObjects fields need to have their subtypes mappings merged in,
      or ElasticSearch will complain about strict mapping when storing a vo that has subtype specific fields.
  "
  ([^ValueObject vo] (vo-mapping vo {}))

  ([^ValueObject vo, option-map]
   (let [id-field  (vo/id-field vo)
         field-set (vo/field-filtered-seq vo (:only option-map) (:exclude option-map))]
    (let [unknown-options
          (set/difference
            (set (keys option-map))
            (set (map vo/keyword field-set))
            #{ :exclude :only } )]
      (assert (empty? unknown-options) (print-str "\n\tMapping non-existant field(s):" unknown-options "\n\t " vo)))
    { :type       "object"
      :dynamic    "strict"
      :properties
      (into {}
        (cons
          {"t" {:type "integer", :store "no"}},
          (map
            (fn [^ValueObjectField field] (field-mapping (option-map (.keyword field)) field (if id-field (identical? id-field field))))
            field-set)
          ))
    }))
)

(defn- ^String field-hexname [^ValueObjectField field]
  (Integer/toHexString (.id field)))

(defn- encode-enum [^EnumValue in ^JsonGenerator out]
  (.writeStartObject out)
  (if-not (.isInstance scala.Product in)
    (.writeNumberField out "v", (.value in))
    (.writeObjectField out "x", (.toString in)))
  (.writeEndObject out))

(defn hexify-path "Recursively walks path.
  Maps keywords to hex-names (for JSON access) and keeps all other values as they are."
  [^ValueObject vo path]
  (prime.vo/fields-path-seq vo path field-hexname))

(defn keyword->hex-field   [^prime.vo.ValueObject vo, ^clojure.lang.Named key]
  (if-let [path (prime.vo/fields-path-seq vo (clojure.string/split (name key) #"[.]") field-hexname)]
    (clojure.string/join "." (filter string? path))))

(defn map-keywords->hex-fields [^prime.vo.ValueObject vo, expr]
  (cond
    (instance? ValueObject expr)
      expr
    (keyword? expr)
      (or (keyword->hex-field vo expr) expr)
    (vector? expr) ; Clojure wart: A MapEntry is a vector, but not a PersistentCollection :-S and does not implement (empty ..)
      (vec (map (partial map-keywords->hex-fields vo) expr))
    (empty expr)
      (into (empty expr) (map (partial map-keywords->hex-fields vo) expr))
    (= expr <=)  "lte"  (= expr <)   "lt"
    (= expr >=)  "gte"  (= expr >)   "gt"
    (= expr not) "not"
    :else expr))

(defn vo-hexname [^ValueObject vo] (Integer/toHexString (.. vo voManifest ID)))

(defmacro map-enum-as-integer
  [enum-class]
  `(do
     (gen/add-encoder ~enum-class
                  (fn [^prime.types.EnumValue in# ^JsonGenerator out#]
                    (.writeNumber out# (. in# value))))
     (extend-type ~enum-class, TermFilter
                  (term-kv-pair [v# k# kv-pair#] [k# (. v# value)])
                  (mapping-field-type-defaults [value#] {:type "integer"}) )))

(defmacro map-enum-as-string
  [enum-class string-method]
  `(do
     (gen/add-encoder# ~enum-class
                   (fn [^prime.types.EnumValue in# ^JsonGenerator out#]
                     (.writeString out# (. in# ~string-method))))
     (extend-type ~enum-class, TermFilter
                  (term-kv-pair [v# k# kv-pair#] [k# (. v# ~string-method)])
                  (mapping-field-type-defaults [value#] {:type "string"}) )))

(defmacro map-as-string-type
  [class elasticsearch-type to-string-fn]
  `(do
     (gen/add-encoder ~class
                      (fn [in# ^JsonGenerator out#]
                        (.writeString out# (~to-string-fn in#))))
     (extend-type ~class, TermFilter
                  (term-kv-pair [v# k# kv-pair#] [k# (~to-string-fn v#)])
                  (mapping-field-type-defaults [value#] {:type ~elasticsearch-type}) )))


;;; ElasticSearch index management (mapping API).

(defn vo-index-mapping-pair [[^ValueObject vo, options]]
  (assert options)
  [ (Integer/toHexString (.. vo voManifest ID)),
    (conj (dissoc (vo-mapping vo options) :type) options) ])

(defn put-mapping [es index-name, vo-options-pair]
  (if (map? vo-options-pair)
    (doseq [pair vo-options-pair]
      (put-mapping es index-name pair))
  ;else
  (let [[type mapping] (vo-index-mapping-pair vo-options-pair)]
    (ces/put-mapping es, {
      :ignore-conflicts? true
      :index  index-name
      :type   type
      :source {type mapping}
    }))))

(defn index-exists? [es indices]
  {:pre [(or (string? indices) (vector? indices)) (not-empty indices)]}
  (-> (ces/exists-index es
        {:indices (if (instance? String indices) [indices] #_else indices)})
      :exists))

(defn create-index
  ([es index-name vo->options-map]
    (create-index es index-name vo->options-map {}))
  ([es index-name vo->options-map root-options]
    (try
      (ces/create-index es, {
        :index  index-name
        :source (assoc root-options :mappings (into {} (map vo-index-mapping-pair vo->options-map)))
      })
    (catch org.elasticsearch.indices.IndexAlreadyExistsException e
      (put-mapping es index-name vo->options-map)))))


;;; ElasticSearch ValueSource.

(declare convert-search-result)

(def-valuesource ElasticSearch-ValueSource [^int type, ^java.util.Map jmap, response]
  (typeID   [this, base] (if (not (== -1 type)) type #_else base))

  (contains [this, name idx]          (.containsKey jmap (Integer/toHexString (bit-shift-right idx 8))))
  (anyAt    [this, name idx notFound] (or
    (if jmap
      (let [item (.get jmap (Integer/toHexString (bit-shift-right idx 8)))]
        (convert-search-result item)))
    notFound)))

(defn convert-search-result [item]
  (condp instance? item
    SearchHitField
    (convert-search-result (.value ^SearchHitField item))

    java.util.Map
    (let [item ^java.util.Map item]
      (if-let [x (.get item "x")]
        x
        (if-let [v (.get item "v")]
          v
          (ElasticSearch-ValueSource. (or (.get item "t") -1), item, nil))))

    java.util.List
    (map convert-search-result item)

    item))


;;; ElasticSearch querying API.

(defn- generate-hexed-fields-smile [obj]
  (binding [json/*field-transform-fn* field-hexname
            json/*encode-enum-fn* encode-enum]
    (core/generate-smile obj)))

(defn vo->term-filter
  ([vo]
    (let [terms (vo->term-filter vo "")]
      (if (== 1 (count terms))
        {:term terms}
      #_else
        {:and (map #(let [[k v] %1] {:term {k v}}) terms)})))

  ([vo, prefix]
    (binding [prime.vo/*voseq-key-fn* #(str prefix (field-hexname %))]
      (into {}
        (map (fn [pair] (let [[k v] pair]
              (cond
                (map? v)     (vo->term-filter v (str k "."))
                (vector? v)  (if (instance? ValueObject (first v)) (vo->term-filter (first v) (str k ".")) #_else [k (first v)])
                :else        (term-kv-pair v k pair))))
        (seq vo))))))


(defn get
  "options: see clj-elasticsearch.client/get-doc"
  ([es es-index vo]
    (get es es-index vo {}))

  ([es es-index ^ValueObject vo options]
    ;{:pre [ ... ]} is bugged for multi arity fns
    (assert es) (assert (string? es-index)) (assert (not (empty? vo))) (assert options)
    (let [resp ^GetResponse
          (ces/get-doc es (assoc options, :type (Integer/toHexString (.. vo voManifest ID)), :index es-index, :format :java, :id (.. ^ID vo _id toString)))]
      (.apply (. vo voCompanion)
              (ElasticSearch-ValueSource. (.. vo voManifest ID) (.getSourceAsMap resp) resp)))))

(defn put
  "options: see clj-elasticsearch.client/index-doc"
  [es index ^ValueObject vo options]
  (prn es index vo options)
  (assert index ":index required")
  (assert (prime.vo/has-id? vo) (str "vo: " (prn-str vo) " requires an id"))
  (ces/index-doc es (conj options {
    :index  index
    :id     (prime.types/to-String (.. ^ID vo _id))
    :type   (Integer/toHexString (.. vo voManifest ID))
    :source (generate-hexed-fields-smile vo)})))


;;; Search.

(defn SearchHit->fields [^SearchHit sh] (.fields sh)     )
(defn SearchHit->source [^SearchHit sh] (.sourceAsMap sh))

(defn SearchHit->ValueObject [source-fn, ^ValueObjectCompanion voCompanion, ^SearchHit hit]
  (.apply voCompanion
    (ElasticSearch-ValueSource. (Integer/parseInt (.type hit) 16) (source-fn hit) hit)))

(defn scroll-seq [es, ^SearchResponse scroll-req keep-alive, from]
  (let [ ^SearchResponse response (ces/scroll es {:scroll-id (.getScrollId scroll-req) :scroll keep-alive, :format :java})
          hits     (.. response getHits hits)
          num-hits (.. response getHits totalHits)
          last-hit (+  from (count hits)) ]
    (if (and (.getScrollId response) (< last-hit num-hits))
      (concat hits (lazy-seq (scroll-seq es response keep-alive last-hit)))
    #_else    hits)))


; TODO:
; - add "type" filter by default.
; - use id->voCompanion when type filter is removed, to allow searching for multiple types.
(defn search
  "options: see clj-elasticsearch.client/search
  example: (vo/search {:filter {:term {:name 'Henk'}}})

  Set indices to [\"*\"] to search in all.

  Returns: seq of SearchHits, with the full SearchResponse as meta-data.
  "
  [es indices ^ValueObject vo {:as options :keys [
    ; extra-source parameters
    query filter from size types sort highlighting only exclude script-fields preference facets named-filters boost explain version min-score
    ; ces/search parameters
    listener ignore-indices routing listener-threaded? search-type operation-threading query-hint scroll source]}]
  {:pre [(instance? ValueObject vo) (or (string? indices) (vector? indices)) (not-empty indices)]}
  (assert (not (and size scroll)) "Error: Cannot use size and scroll in same query!")
    (let [
      typefilter {:type {:value (vo-hexname vo)}}
      filter     (map-keywords->hex-fields vo filter)
      filter     (if-not filter (if-not (empty? vo) (vo->term-filter vo))
                  #_else+filter (if-not (empty? vo) {:and (conj [filter] (vo->term-filter vo))}  #_else filter))
      filter     (if filter {:and [typefilter filter]} typefilter)
      fields     (if (or only exclude) (map field-hexname (vo/field-filtered-seq vo only exclude)))
      es-options (into {:format :java, :indices (if (vector? indices) indices [indices])}
        (clojure.core/filter val {
          :listener listener, :ignore-indices ignore-indices, :routing routing, :listener_threaded? listener-threaded?, :search-type search-type
          :operation-threading operation-threading, :query-hint query-hint, :scroll scroll, :source source
          :extra-source (generate-hexed-fields-smile (into {} (clojure.core/filter val {
            :query            (map-keywords->hex-fields vo query),
            :filter           filter,
            :from             from,
            :size             size,
            :types            types,
            :sort             (map-keywords->hex-fields vo sort),
            :highlighting     (map-keywords->hex-fields vo highlighting),
            :fields           fields,
            :script_fields    (map-keywords->hex-fields vo script-fields),
            :preference       preference,
            :facets           (map-keywords->hex-fields vo facets),
            :named_filters    (map-keywords->hex-fields vo named-filters),
            :boost            boost,
            :explain          explain,
            :version          version,
            :min_score        min-score
          })))
      }))
      response ^SearchResponse (ces/search es es-options)
    ]
      (with-meta
        (map
          (partial SearchHit->ValueObject (if fields SearchHit->fields #_else SearchHit->source) (. vo voCompanion))
          (if-not scroll (.. response getHits hits) #_else (scroll-seq es response scroll 0)))
        {:request es-options, :response response})))

;; (defn ^org.elasticsearch.action.index.IndexRequest ->IndexRequest [^String index ^String type ^String id, input options]
;;   (let [^"[B" input (if (instance? ValueObject input) (generate-hexed-fields-smile input) input)
;;             request (new org.elasticsearch.action.index.IndexRequest index type id)
;;         {:keys [routing parent timestamp ttl version versionType]} options]
;;     (. request source input)
;;     (when routing     (. request routing     routing))
;;     (when parent      (. request parent      parent))
;;     (when timestamp   (. request timestamp   timestamp))
;;     (when ttl         (. request ttl         ttl))
;;     (when version     (. request version     version))
;;     (when versionType (. request versionType versionType))
;;     request))

(defn- patched-update-options [type id options]
  (let [{:keys [doc] :as options} (assoc options :type type :id id)]
    (if doc
      (assoc options :doc (generate-hexed-fields-smile doc))
      options)))

(defn update
  [es index ^ValueObject vo id {:as options :keys [fields]}]
  {:pre [(instance? ValueObject vo) (not (nil? id)) index]}
  (let [type    (Integer/toHexString (.. vo voManifest ID))
        id      (prime.types/to-String id)
        fields  (map field-hexname fields)]
    (ces/update-doc es (patched-update-options type id (assoc options :index index :doc vo
                                                              :doc-as-upsert? true)))))



(defn delete [es index ^ValueObject vo {:as options :keys []}]
  {:pre [(instance? ValueObject vo)]}
  (assert index ":index required")
  (assert (prime.vo/has-id? vo) (str "vo: " (prn-str vo) " requires an id"))
  (ces/delete-doc es (conj options {
    :index  index
    :id     (prime.types/to-String (.. ^ID vo _id))
    :type   (Integer/toHexString (.. vo voManifest ID))
  })))

(defn- get-pos [step varname]
  (let [[k v] (first step)]
    (assert (string? k) k)
    (apply str "var pos = -1; for (var i = 0; (i < path.size() && pos == -1); i++) { if(path[i]['" k "'] == n" varname ") { pos = i; }}")))

(defn- get-pos-for-str [step]
  (prn "Step: " step)
  (apply str "var pos = -1; for (var i = 0; (i < path.size() && pos == -1); i++) { if(path[i] == " (core/encode step) ") { pos = i; }}"))

(defn resolve-path-simple [path]
  (loop [i 0 r "var path = ctx._source" varnum -1]
    (let [step (nth path i nil)]
      (if (nil? step)
        [r varnum] ; Return result
        (let [varnum (if (map? step) (inc varnum) #_else varnum)
          r
          (cond
            (map? step) (str r "; " (get-pos step varnum) " path = path[pos]")
            :else (str r "['" step "']")
          )]
          (recur (inc i) r varnum))))))

(defn resolve-path [vo path]
  (if (empty? path)
    nil
    (resolve-path-simple (hexify-path vo path))))

(defn script-query [client index vo id script params options]
  (let [type (Integer/toHexString (.. vo voManifest ID))
        id   (prime.types/to-String id)]
    (ces/update-doc client (patched-update-options type id (assoc options
      :index index
      :source (generate-hexed-fields-smile
        (binding [prime.vo/*voseq-key-fn* field-hexname] {
          :p (prn script params)
          :script script
          :params params
        })))))))

;TODO convert names!
; TODO -> Use insert-at script for this. Except leave position in .add(newval).
(defn append-to "Add something to the end of an array"
  [client index vo path path-vars value options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (prn vo path path-vars value options)
  (let [
    [resolved-path varnum]  (resolve-path vo path)
    script                  (apply str resolved-path "; if(path == null) { " (.substring resolved-path 11) " = [newval]; } else { path.add(newval)};")
    parameters              (into {} (cons [:newval value] (map-indexed #(do [(str "n" %1) %2]) path-vars)))
    ]
    (prn script parameters)
    (script-query client index vo (:id vo) script parameters options)))

(defn insert-at [client index vo path path-vars value options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (let [
    [resolved-path varnum]  (resolve-path vo (pop path))
    script                  (apply str resolved-path ".add(to, newval);")
    parameters              (into {} (merge {:to (last path) :newval value} (map-indexed #(do {(str "n" %1) %2}) path-vars)))
    ]
    (script-query client index vo (:id vo) script parameters options)))

(defn move-to [client index vo path path-vars to options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (assert (or (number? (last path)) (string? (last path)) (map? (last path))) "Last step of the path has to be a map, number or string")
  (let [
    [resolved-path varnum]  (resolve-path vo (pop path))
    last-pathnode           (last (hexify-path vo path))
    from-pos-loop           (cond
                              (string? last-pathnode) (get-pos-for-str last-pathnode)
                              (map? last-pathnode) (get-pos last-pathnode (inc varnum))
                              (number? last-pathnode) (apply str "var pos = " last-pathnode ";"))
    script                  (apply str resolved-path "; " from-pos-loop "; var from = pos; var tmpval = path.get(from); path.remove(from); if( to < 0 ) { to = path.size() + to; } if (to < 0 ) {to = 0; } if (to > path.size() ) { to = path.size()-1 } path.add(to, tmpval);")
    newval                  (get-in vo (:steps resolved-path))
    parameters              (merge {:to to} (into {} (map-indexed #(do {(str "n" %1) %2}) path-vars)))
    ]
    (script-query client index vo (:id vo) script parameters options)))

(defn replace-at [client index vo path path-vars value options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (assert (or (number? (last path)) (string? (last path)) (map? (last path))) "Last step of the path has to be a map, number or string")
  (let [
    [resolved-path varnum]  (resolve-path vo (pop path))
    last-pathnode           (last (hexify-path vo path))
    from-pos-loop           (cond
                              (string? last-pathnode) (get-pos-for-str last-pathnode)
                              (map? last-pathnode) (get-pos last-pathnode (inc varnum))
                              (number? last-pathnode) (apply str "var pos = " last-pathnode ";"))
    script                  (apply str resolved-path "; " from-pos-loop "; path[pos] = newval;")
    parameters              (into {} (cons [:newval value] (map-indexed #(do [(str "n" %1) %2]) path-vars)))
    ]
    (script-query client index vo (:id vo) script parameters options)))

(defn merge-at [client index vo path path-vars options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (assert (number? (last path)) "Last step of the path has to be a number")
  (let [
    [resolved-path varnum]  (resolve-path vo path)
    script                  (apply str resolved-path "; foreach (x : values) { path[x] = values[x]; }")
    parameters              (into {} (cons  [:values (get-in vo (map #(if-not (keyword? %) 0 %) path))] (map-indexed #(do [(str "n" %1) %2]) path-vars)))
    ]
    (script-query client index vo (:id vo) script parameters options)))

(defn remove-from [client index vo path path-vars options]
  {:pre [(instance? ValueObject vo) (not (nil? (:id vo))) index]}
  (assert (or (number? (last path)) (string? (last path)) (map? (last path))) "Last step of the path has to be a map, number or string")
  (let [
    [resolved-path varnum]  (resolve-path vo (pop path))
    last-pathnode           (last (hexify-path vo path))
    from-pos-loop           (cond
                              (string? last-pathnode) (get-pos-for-str last-pathnode)
                              (map? last-pathnode) (get-pos last-pathnode (inc varnum))
                              (number? last-pathnode) (apply str "var pos = " last-pathnode ";"))
    script                  (apply str resolved-path "; " from-pos-loop "; path.remove(pos);")
    parameters              (into {}  (map-indexed #(do [(str "n" %1) %2]) path-vars))
    ]
    (script-query client index vo (:id vo) script parameters options)))


;;; Query helpers

(defn has-child-vo
  "Construct a 'has_child' query for the given VO type and optional query.
   If no query is given: query is built using vo as term filter, or match_all if vo is empty."
  ([vo] (has-child-vo vo (if (empty? vo) {"match_all" {}} #_else (vo->term-filter vo))))

  ([vo child-query]
    {"has_child" {"type" (vo-hexname vo), "query" child-query}}))

(defn convert [res]
  (ces/convert (:response (meta res)) :clj))