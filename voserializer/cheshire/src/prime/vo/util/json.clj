;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo.util.json
  "Namespace for adding encoders to Cheshire for ValueObjects. This
  namespace does not contain any public functions of interest; it just
  needs to be required. It does have dynamic vars that influence the
  resulting encoding."
  (:require [prime.vo :as vo]
            [prime.types :refer (to-String)]
            [cheshire.generate :refer (add-encoder)])
  (:import [prime.types VORef EnumValue package$ValueType package$ValueTypes$Tdef
            package$ValueTypes$Tarray package$ValueTypes$Tenum FileRef]
           [prime.vo IDField ValueObject ValueObjectManifest ValueObjectField ValueObjectCompanion ID]
           [com.fasterxml.jackson.core JsonGenerator]))


;;; Configuration options for encoding.

;; Bind the following var to another function if the field names of the VOs need
;; to be encoded differently than the standard behaviour (writing out the full
;; name of the field name). The function takes a ValueObjectField and should
;; return a String.
(def ^:dynamic *field-transform-fn*  (fn [^ValueObjectField vof] (.name vof)))

;; Bind the following var to another function if the enumerations in VOs need to
;; be encoded diffently than the standard behaviour (writing out the name of the
;; enumerator). The functions takes an EnumValue and a JsonGenerator, what it
;; returns is unimportant.
(def ^:dynamic *encode-enum-fn* (fn [^EnumValue in ^JsonGenerator out] (.writeString out (str in))))


;;; Encoders.

(def ^:dynamic ^:private *vo-baseTypeID* nil)

(defn- encode-vo
  ([^JsonGenerator out ^prime.vo.ValueObject vo ^String date-format ^Exception ex]
     (encode-vo out vo date-format ex (or *vo-baseTypeID* (.. vo voManifest ID))))

  ([^JsonGenerator out ^prime.vo.ValueObject vo ^String date-format ^Exception ex ^Integer baseTypeID]
     (.writeStartObject out)
     (when-not (== baseTypeID (.. vo voManifest ID))
       (.writeNumberField out "t" (.. vo voManifest ID)))

     (doseq [[^ValueObjectField k v] vo] ;; Note that prime.vo/*voseq-key-fn* is used here.
       (.writeFieldName out (str (*field-transform-fn* k)))
       (cond
        (instance? ValueObject v)
        ;; First try to find and call a protocol implementation for this type immediately.
        (if-let [to-json (:to-json (clojure.core/find-protocol-impl cheshire.generate/JSONable v))]
          (to-json v out)
          ;; else: Regular VO, no protocol found
          (encode-vo out v date-format ex (.. ^package$ValueTypes$Tdef (. k valueType)
                                              empty voManifest ID)))

        (vector? v)
        (let [innerType (. ^package$ValueTypes$Tarray (. k valueType) innerType)
              voType (if (instance? package$ValueTypes$Tdef innerType)
                       (.. ^package$ValueTypes$Tdef innerType empty voManifest ID)
                       -1)]
          (if (> voType 0)
            (binding [*vo-baseTypeID* voType] (cheshire.generate/generate-array out v date-format ex nil))
            (cheshire.generate/generate-array out v date-format ex nil)))

        :else
        (cheshire.generate/generate out v date-format ex nil)))

     (.writeEndObject out)))


(defn- encode-enum [^EnumValue in ^JsonGenerator out]
  (*encode-enum-fn* in out))

(defn- encode-voref
  [^JsonGenerator out ^VORef in ^String date-format ^Exception ex]
  (cheshire.generate/generate out (._id in) date-format ex nil))

(defn- encode-instant
  [^org.joda.time.ReadableInstant in ^JsonGenerator out]
  (.writeNumber out (.getMillis in)))

(defn- encode-uri
  [^java.net.URI in ^JsonGenerator out]
  (.writeString out (to-String in)))

(defn- encode-url
  [^java.net.URL in ^JsonGenerator out]
  (.writeString out (to-String in)))

(defn- encode-internetAddress
  [^javax.mail.internet.InternetAddress in ^JsonGenerator out]
  (.writeString out (to-String in)))

(defn- encode-objectId
  [^org.bson.types.ObjectId in ^JsonGenerator out]
  (.writeString out (to-String in)))

(defn- encode-fileref
  [^prime.types.FileRef in ^JsonGenerator out]
  (.writeString out (.prefixedString in)))

(defn- encode-rgba
  [^prime.types.RGBA in ^JsonGenerator out]
  (.writeNumber out (.toInt in)))


;;; Register encoders and advice cheshire.generate/generate.

(add-encoder prime.types.EnumValue encode-enum)
(add-encoder java.net.URI encode-uri)
(add-encoder java.net.URL encode-url)
(add-encoder org.joda.time.ReadableInstant encode-instant)
(add-encoder org.bson.types.ObjectId encode-objectId)
(add-encoder javax.mail.internet.InternetAddress encode-internetAddress)
(add-encoder prime.types.FileRef encode-fileref)
(add-encoder prime.types.RGBA encode-rgba)

(alter-var-root
 #'cheshire.generate/generate
 (fn [orig-generate]
   (fn [^JsonGenerator jg obj ^String date-format ^Exception ex key-fn]
     (binding [prime.vo/*voseq-key-fn* identity]
       ;; First try to find and call a protocol implementation for this type immediately.
       (if-let [to-json (:to-json (clojure.core/find-protocol-impl cheshire.generate/JSONable obj))]
         (to-json obj jg)
         ;; else: No protocol found
         (condp instance? obj
           ValueObject (encode-vo jg obj date-format ex)
           VORef (encode-voref jg obj date-format ex)
           (orig-generate jg obj date-format ex key-fn)))))))
