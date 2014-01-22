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
(ns juxt.accounting.jig
  (:require
   jig
   [jig.bidi :refer (add-bidi-routes)]
   [clojure.tools.logging :refer :all]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojurewerkz.money.currencies :as mc :refer (to-currency-unit)]
   [juxt.accounting
    [database :as db]
    [driver :refer (process-accounts-file)]
    [service :refer (create-bidi-routes)]
    [ofx :as ofx]]
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
      (assoc system
        :data (edn/read-string
                   {:readers {'juxt.accounting/currency
                              (fn [x] (to-currency-unit (str x)))}}
                   (slurp (io/file (:accounts-file config))))
        :basedir (.getParentFile accountsfile))))
  (stop [_ system] system))

(deftype DataLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (let [dburi (:dburi system)
          data (:data system)
          basedir (:basedir system)]
      (if (and dburi data)
        (process-accounts-file basedir data dburi)
        (cond (nil? dburi) (ex-info "No dburi" {})
              (nil? data) (ex-info "No data" {}))))
    system)
  (stop [_ system] system))

;; An optional module that can process statements. Should depend on the
;; instance of the database component above.
(deftype StatementProcessor [config]
  Lifecycle
  (init [_ system]
    system)
  (start [_ system]
    (let [dir (:statement-directory config)]
      (let [dburi (some-> system :jig/config :jig/components
                          (get (:database config)) :db :uri)]
        (ofx/add-transactions (io/file dir) dburi (:account-mappings config)))
      system))
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
