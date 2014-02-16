;; Copyright © 2014, JUXT LTD. All Rights Reserved.
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
(ns juxt.accounting.loaders
  (:require
   jig
   [datomic.api :as d]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.pprint :refer (pprint)]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)]
   [juxt.accounting.database :as db])
  (:import (jig Lifecycle))
  )

(defn add-consulting-rate-transations [dburi transactions]
  (let [conn (d/connect dburi)
        db (d/db conn)]
    (doseq [{:keys [credit-account debit-account codes] :as tx} transactions]
      @(d/transact conn (vec
                         (apply concat
                                (for [{:keys [credit-account debit-account codes items]} transactions]
                                  (apply concat
                                         (for [{:keys [date code]} items]
                                           (db/assemble-transaction
                                            db
                                            date
                                            [{:debit-account debit-account
                                              :credit-account credit-account
                                              :description (get-in codes [code :description])
                                              :amount (get-in codes [code :rate])}]
                                            ;; TODO: Add expenses
                                            "transaction loaded from file"))))))))))

(deftype ConsultingRateLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))
          txfile-content
          (edn/read-string
           {:readers {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}}
           (slurp txfile))]

      (add-consulting-rate-transations
       (:dburi system)
       (:transactions txfile-content))

      system))

  (stop [_ system] system))

#_[{:credit-account :congreve.liabilities, :debit-account :mastodonc.assets-pending-invoice, :codes {:8h {:description "Full day consulting", :rate 400}, :4h {:description "Half day consulting", :rate 200}}, :items [{:date #inst "2014-01-02T00:00:00.000-00:00", :code :8h, :expenses [{:description "Train", :cost 28} {:description "Bus", :cost 2}]}]}]
