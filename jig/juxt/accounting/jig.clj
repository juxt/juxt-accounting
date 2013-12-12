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
   [jig.web
    [app :refer (add-routes)]
    [stencil :refer (link-to-stencil-loader)]]
   [clojure.tools.logging :refer :all]
   [clojure.java.io :as io]
   [juxt.accounting
    [database :as db]
    [driver :refer (process-accounts-file)]
    [service :refer (create-routes-terse)]
    [ofx :as ofx]]
   [datomic.api :as d]
   [io.pedestal.service.http :as bootstrap]
   [io.pedestal.service.http.body-params :as body-params])
  (:import (jig Lifecycle)))

(deftype Database [config]
  Lifecycle
  (init [_ system]
    (infof "Initialising Database component, config is %s"
           (select-keys config [:db :accounts-file]))
    (let [dburi (-> config :db :uri)]
      (when-not (-> config :db :persistent)
        (infof "Deleting database: %s" dburi)
        (d/delete-database dburi))
      (infof "Initializing database: %s" dburi)
      (db/init dburi)
      system))
  (start [_ system]
    (infof "Starting Database component")
    (process-accounts-file (-> config :accounts-file) (-> config :db :uri))
    system)
  (stop [_ system]
    (when-not (-> config :db :persistent)
      (d/delete-database (-> config :db :uri)))
    (d/shutdown false)
    system))

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

(deftype PedestalService [config]
  Lifecycle
  (init [_ system]
    (infof "Initialising PedestalService component: %s" (:jig/id config))
    (doseq [k [:bootstrap-dist :jquery-dist]]
      (when-not (is-directory (some-> config k io/file))
        (throw (ex-info (format "Dist dir for %s not valid: %s" (name k) (-> config k)) {}))))

    (-> system
        (assoc-in [(:jig/id config) :data]
                  (get-in system [:jig/config :jig/components (:juxt.accounting/data config)]))

        (link-to-stencil-loader config)

        (add-routes config (create-routes-terse (:bootstrap-dist config) (:jquery-dist config)))))
  (start [_ system] system)
  (stop [_ system] system))
