;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo.printer
  "Extends the Clojure printer with formatted printing of ValueObject instances."
  (:import java.io.Writer
           [prime.types package$ValueType EnumValue FileRef RGBA VORefImpl]
           [prime.vo ValueObject ValueObjectField]))

(def ^:private print-sequential #'clojure.core/print-sequential)
(def ^:private print-meta       #'clojure.core/print-meta)
(def ^:private pr-on            #'clojure.core/pr-on)


;
; Value type printing
;

(defmethod print-method ValueObjectField [^ValueObjectField v, ^Writer w]
  (.write w "(ValueObjectField. {:id 0x")  (.write w (Integer/toHexString (.id v)))
  (.write w ", :name ")                    (pr-on (.name v) w)
  (.write w ", :valueType ")               (pr-on (.valueType v) w)
  (.write w ", :defaultValue ")            (pr-on (.defaultValue v) w)
  (.write w "})"))

(defmethod print-method package$ValueType [^package$ValueType v, ^Writer w]
  (pr-on (.keyword v) w))

(defmethod print-method EnumValue [^EnumValue v, ^Writer w]
  (let [str (.. v getClass getName (split "\\$"))]
    (.write w "(")
    (.write w ^String (get str 0))
    (.write w "/")
    (.write w (.substring ^String (get str 1) 1))
    (when (.isInstance scala.Product v)
      ; It's a case class with a String parameter
      (if-let [v (.toString v)]
        (do (.write w " \"")
            (.write w (clojure.string/replace v "\"" "\\\""))
            (.write w "\"")))
      ;else
        (.write w " nil"))
    (.write w ")")))

(defmethod print-method FileRef   [^FileRef   v, ^Writer w]
  (.write w "(prime.types/FileRef \"")
  (.write w (.prefixedString v))
  (.write w "\")"))

(defmethod print-method VORefImpl [^VORefImpl v, ^Writer w]
  (.write w "(prime.types/VORef ")
  (pr-on (._id v) w)
  (.write w " => ")
  (pr-on (._cached v) w)
  (.write w ")"))

(defmethod print-method RGBA      [^RGBA      v, ^Writer w]
  (.write w "(prime.types/RGBA 0x")
  (.write w (.substring (.toRGBString v) 1))
  (.write w " ")
  (.write w (Float/toString (.alphaPercent v)))
  (.write w ")"))


;
; VO Printing
;

(defn print-vo [^ValueObject vo print-one ^Writer w]
  (do
    (.write w "(")
    (.write w (.. (class vo) getPackage getName))
    (.write w "/")
    (.write w (.. vo voManifest VOType erasure getSimpleName))
    (print-sequential "{",
      (fn [e  ^Writer w]
        (do (print-one (key e) w) (.append w \space) (print-one (val e) w)))
      ", "
      "})"
      (seq vo) w)))

(defmethod print-method ValueObject [^ValueObject vo, ^Writer w]
  (print-meta vo w)
  (print-vo vo pr-on w))

(prefer-method print-method ValueObject clojure.lang.IPersistentMap)
(prefer-method print-method ValueObject java.util.Map)

;; BROKEN!
;; - http://stackoverflow.com/questions/6427967/clojure-reader-macro
;; - http://www.infoq.com/presentations/The-Taming-of-the-Deftype
;; - https://github.com/ghoseb/chainmap
;;
;; #=(vo.spread/Box{:w #=(java.lang.Integer. "12345")})
;; #<RuntimeException java.lang.RuntimeException: java.lang.ClassNotFoundException: vo.spread>
(defmethod print-dup ValueObject [^ValueObject vo, ^Writer w]
  (print-meta vo w)
  (.write w "#=")
  (prime.vo.printer/print-vo vo pr-on w))

(prefer-method print-dup ValueObject clojure.lang.IPersistentMap)
(prefer-method print-dup ValueObject clojure.lang.IPersistentCollection)
(prefer-method print-dup ValueObject java.util.Map)

(defmethod print-dup org.joda.time.DateMidnight [^org.joda.time.DateMidnight d ^java.io.Writer w]
  (.write w "\"") (.write w (.toString d "YYYY-MM-DD")) (.write w "\""))

(defmethod print-dup org.joda.time.DateTime [^org.joda.time.DateTime d ^java.io.Writer w]
  (.write w "\"") (.write w (.toString d)) (.write w "\""))

(defmethod print-dup EnumValue [^EnumValue v, ^Writer w] (.write w "#=") (print-method v w))

#_(
  (.write w (.getName ^Class (class o)))
  (.write w "/create ")
  (print-sequential "[" print-dup " " "]" o w)
  (.write w ")"))
