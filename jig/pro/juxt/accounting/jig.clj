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
(ns pro.juxt.accounting.jig
  (:require
   jig
   [jig.web
    [app :refer (add-routes)]]
   [clojure.tools.logging :refer :all]
   [pro.juxt.accounting
    [database :as db]
    [driver :refer (process-accounts-file)]
    [service :refer (create-routes-terse)]]
   [datomic.api :as d]
   [io.pedestal.service.http :as bootstrap]
   [io.pedestal.service.http.body-params :as body-params])
  (:import (jig Lifecycle)))

(deftype Database [config]
  Lifecycle
  (init [_ system]
    (infof "Initialising Database component, config is %s"
           (select-keys config [:db :accounts-file]))
    (d/delete-database (-> config :db :uri))
    (db/init (-> config :db :uri))
    system)
  (start [_ system]
    (infof "Starting Database component")
    (process-accounts-file (-> config :accounts-file) (-> config :db :uri))
    system)
  (stop [_ system]
    (d/delete-database (-> config :db :uri))
;; There are Datomic API calls to release resources, which have been investigated in order to see if they fix the classloading issues.
;;    (d/shutdown false)
    system))

(deftype PedestalService [config]
  Lifecycle
  (init [_ system]
    (infof "Initialising PedestalService component: %s" (:jig/id config))
    (-> system
        (assoc-in [(:jig/id config) :data]
                  (get-in system [:jig/config :jig/components (:pro.juxt.accounting/data config)]))
        (add-routes config (create-routes-terse system))))
  (start [_ system] system)
  (stop [_ system] system))
