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
   [juxt.accounting.service :refer (create-bidi-routes)]
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

(deftype DataExtractor [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:accounts-file config) "No accounts file")
    (let [accountsfile (io/file (:accounts-file config))]
      (update-in system
                [:inputs]
                conj
                (edn/read-string
                 {:readers {'juxt.accounting/currency
                            (fn [x] (to-currency-unit (str x)))}}
                 (slurp (io/file (:accounts-file config))))
                )))
  (stop [_ system] system))



(def is-directory (every-pred identity (memfn exists) (memfn isDirectory)))

(deftype Website [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (infof "Initializing Website: %s" (:jig/id config))
    (let [dburi (:dburi system)
          template-loader (get-in system [(:jig/id (jig.util/satisfying-dependency system config 'jig.stencil/StencilLoader)) :jig.stencil/loader])
          ]
      (doseq [k [:bootstrap-dist :jquery-dist]]
        (when-not (is-directory (some-> config k io/file))
          (throw (ex-info (format "Dist dir for %s not valid: %s" (name k) (-> config k)) {}))))

      (-> system
          (assoc-in [(:jig/id config) :data]
                    (get-in system [:jig/config :jig/components (:juxt.accounting/data config)]))

          ;;(link-to-stencil-loader config)

          (add-bidi-routes config
                           (create-bidi-routes
                            (merge config
                                   {:dburi dburi
                                    :template-loader template-loader
                                    :data (:data system)}))))))
  (stop [_ system] system))
