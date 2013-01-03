;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo
  (:require [clojure.set     :as    s])
  (:import  [prime.vo ValueObject ValueObjectField ValueObjectManifest_0 ValueObjectManifest_1 ValueObjectManifest_N ID]
            [prime.types package$ValueType package$ValueTypes$Tarray package$ValueTypes$Tdef]))

(def ^:dynamic *proxy-map* "
  A function from VOType => VOProxy. Used to fetch a ValueObject when dereferencing VORef properties.
  Initially {}

  Usage:
    (binding [*proxy-map* { MyReferencedValueObject (my-valueobject-proxy ...) }] @(:voref-field vo))
  " {})

(def ^:dynamic *voseq-key-fn* "
  A function from ValueObjectField => Any, or nil.
  When not nil, creating a seq from ValueObject will call this function to produce the key value.
  Initially nil.

  Usage example:
    (binding [*voseq-key-fn* #(.id ^ValueObjectField %)] (map name vo))
  " nil)

(defn fields [in]
  (condp instance? in
    ValueObject           (fields (.. ^ValueObject in voManifest))
    ValueObjectManifest_N (remove nil? (.. ^ValueObjectManifest_N in fields))
    ValueObjectManifest_1 (remove nil? (cons (.. ^ValueObjectManifest_1 in first) nil))
    ValueObjectManifest_0 nil))

(defn field-set [^ValueObject vo]
  (set (map #(.keyword ^ValueObjectField %) (fields vo))))

(defn field-filtered-seq [^ValueObject vo, only, exclude]
  (let [only       (set only   )
        exclude    (set exclude)
        all-keys   (field-set vo)
        field-keys (if-not (empty? only)    (s/select     only       all-keys) all-keys)
        field-keys (if-not (empty? exclude) (s/difference field-keys exclude)  field-keys)]
    (assert (empty? (s/difference only    all-keys)) (str ":only    contains key not present in VO field-set: " all-keys))
    (assert (empty? (s/difference exclude all-keys)) (str ":exclude contains key not present in VO field-set: " all-keys))
    (filter
      #(field-keys (.keyword ^ValueObjectField %))
      (fields vo))))

(defn type-default-vo [^package$ValueType field]
  (condp instance? field
    package$ValueTypes$Tdef   (.empty field)
    package$ValueTypes$Tarray (type-default-vo (.innerType field))
    nil))

(defn fields-path-seq [^ValueObject vo [first-field-name & path]]
  (let [field (.. vo voManifest (findOrNull first-field-name))]
    (if field
      (if path
        (let [inner-vo    (type-default-vo (.valueType field))
              next-fields (fields-path-seq inner-vo path)]
          (if next-fields (cons field next-fields)))
      #_else
        (cons field nil)))))
