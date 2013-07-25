;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
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
(ns pro.juxt.accounting.main
  (:require
   [clojure.java.io :as io]
   [datomic.api :as d]
   [pro.juxt.accounting
    [database :as db]
    [driver :refer (process-accounts-file)]]))

(defn delete-invoices []
  (doall (map #(.delete %) (seq (.listFiles (io/file "/home/malcolm/Dropbox.private/JUXT/invoices-new"))))))

(defn -main [& args]
  (delete-invoices)
  (let [dburi "datomic:mem://pro.juxt/accounts"]
    ;; Delete any prior databases
    (d/delete-database dburi)
    ;; Initialise a new database
    (db/init dburi)
    ;; Process accounts
    (process-accounts-file (first args) dburi)
    ;; TODO Start Pedestal web service to view state - be able to re-read
    ;; accounts.edn if it changes (use up-reload for this?
    ))

(-main "/home/malcolm/Dropbox.private/JUXT/accounts.edn")
