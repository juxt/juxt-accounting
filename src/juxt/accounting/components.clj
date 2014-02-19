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
(ns juxt.accounting.components
  (:require
   jig
   [jig.bidi :refer (add-bidi-routes)]
   [clojure.tools.logging :refer :all]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojurewerkz.money.currencies :as mc :refer (to-currency-unit)]
   [juxt.accounting.database :as db]
   [juxt.accounting.static :refer (process-static-file)]
   [juxt.accounting.web :refer (create-bidi-routes)]
   [juxt.accounting.ofx :as ofx]
   [datomic.api :as d])
  (:import (jig Lifecycle)))

(deftype Database [config]
  Lifecycle
  (init [_ system]
    (let [dburi (-> config :db :uri)]
      (when-not (-> config :db :persistent)
        (infof "Deleting database: %s" dburi)
        (d/delete-database dburi))
      (infof "Initializing database: %s" dburi)
      (db/init dburi)
      (assoc system :dburi dburi)))
  (start [_ system]
    (infof "Starting Database component")
    system)
  (stop [_ system]
    (when-not (-> config :db :persistent)
      (d/delete-database (-> config :db :uri)))
    (d/shutdown false)
    system))
