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

(ns ^{:doc "See http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded"}
  juxt.accounting.system
  (:require
   [juxt.accounting
    [database :as db]
    [driver :refer (process-accounts-file)]]
   [datomic.api :as d]))

(defn system [config]
  (merge config {}))

(defn start [system service-creator]
  (d/delete-database (-> system :db :uri))
  (db/init (-> system :db :uri))
  (process-accounts-file (-> system :accounts-file) (-> system :db :uri)))

(defn stop [system])
