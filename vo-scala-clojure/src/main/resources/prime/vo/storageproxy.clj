;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns prime.vo.storageproxy
	(:require [prime.vo.util.elasticsearch :as es]))

(defprotocol VOProxy
  "Doc string"
  ; Get
  ; Get will get you a VO based on ID
  (get-vo [vo] [proxy vo])

  ; Put
  ; Putting will replace the existing VO with the new one.
  ; Requires ID
  (put [vo] [proxy vo] [proxy vo options] "Replaces existing VO.")

  ; Update
  ; Updating will add the new VO to the extisting one. Replacing data if it already exists
  (update [this] [this vo id] [this vo id options] "Updates a VO")

  ; Update-if
  ; Same as update but only executes when certain requirements are met.
  #_(update-if [this predicate options])

  ; [es vo path value pos]
  (insertAt [vo] [this vo])

  ; [es vo path pos]
  (moveTo [vo] [this vo])

  ; [es ^ValueObject vo id & {:as options :keys [index]}]
  (appendTo [vo] [this vo id] [this vo id options])

  ; [es vo pos value]
  (replaceAt [vo] [this vo])

  ; Delete
  ; Deletes a VO. Use with care :-)
  (delete [vo] [proxy vo])
)

(defprotocol VOSearchProxy
  ; Search
  ; Search elastic. Returns VO's
  (search [vo] [this vo] [this vo options])
)

(defrecord ElasticSearchVOProxy [^String index ^org.elasticsearch.client.transport.TransportClient client]
  ;ElasticSearchMapped
  ;(index-name   [this] index)
  ;(mapping-name [this] (str (.. empty-vo voManifest ID)))

  VOProxy
  ; [es es-index ^ValueObject vo options]
  (get-vo [this vo] 
  	(prn vo)
  	(es/get client index vo))

  ; [es ^ValueObject vo & {:as options :keys [index]}]
  (put [this vo]
  	(es/put client vo :index index))

  ; [es ^ValueObject vo id & {:as options :keys [index]}]
  (update [this vo id options] 
  	(es/update client vo id :index index options))
  
  ; [es vo path value pos]
  (insertAt [vo] vo)

  ; [es vo path pos]
  (moveTo [vo] vo)

  ; [es ^ValueObject vo id & {:as options :keys [index]}]
  (appendTo [vo id options]
  	(es/appendTo client vo id :index index options))

  ; [es vo pos value]
  (replaceAt [vo] vo)

  ; [es ^ValueObject vo & {:as options :keys [index]}]
  (delete [vo] vo)

  VOSearchProxy
  ; [es ^ValueObject vo indices & {:as options :keys [ query filter from size types sort highlighting only exclude script-fields preference facets named-filters boost explain version min-score listener ignore-indices routing listener-threaded? search-type operation-threading query-hint scroll source]}]
  (search [this vo & options]
    vo)
)
