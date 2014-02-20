;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; This file is part of JUXT Accounting.
;;
;; JUXT Accounting is free software: you can redistribute it and/or modify it under the
;; terms of the GNU Affero General Public License as published by the Free
;; Software Foundation, either version 3 of the License, or (at your option) any
;; later version.
;;
;; JUXT Accounting is distributed in the hope that it will be useful but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
;; A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
;; details.
;;
;; Please see the LICENSE file for a copy of the GNU Affero General Public License.
;;
(ns juxt.accounting.entities
  (:require
   jig
   [hiccup.core :refer (html h)]
   [jig.bidi :refer (add-bidi-routes)])
  (:import (jig Lifecycle)))

;; This was an attempt to try out some pluggability, but it's proven
;; difficult to get the boilerplate added. A rethink is needed.

(defn entities-page []
  (fn [req]
    {:status 200 :body
     (html
      [:h1 "TEST Entities page"])}))

(defn create-handlers []
  (let [p (promise)]
    @(deliver p {:entities (entities-page)})))

(defn create-routes [handlers]
  ["/"
   [
    ["test" (:entities handlers)]
    ]])

(deftype Entities [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (-> system
        (add-bidi-routes config (create-routes (create-handlers)))))
  (stop [_ system] system))
